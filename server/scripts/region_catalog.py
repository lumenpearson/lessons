"""Generate ``app/catalog/data/regions.json`` from the survey of every region's diary.

The phone asks «which region are you in» before it asks anything else, and the
answer decides what it offers: a sign-in, a hand-off to Госуслуги, or a class
code. That answer is the survey's (``docs/diaries/regions.md``), which is
markdown written for people. This turns it into data once, at build time, so
that neither the phone nor the server ever parses prose, and the survey stays
the one place a region's diary is written down.

Three inputs, and what each is trusted for:

- ``docs/diaries/regions.md`` — which systems a region runs, in what role, on
  which hosts, how sure the survey is. ``docs/diaries.md``'s tables are read
  only to cross-check it: they are a tool's summary and cut names off with «…».
- ``app/providers/netschool/regions.py`` and the Petersburg client's
  ``BASE_URL`` — every origin a sign-in may go to. Never the survey's hosts:
  the allow-list is the server's one guard against being pointed at somebody's
  address, and a hand-typed copy on the phone would be a second allow-list.
- ``region_catalog.toml`` beside this file — what nothing in the repository
  derives: keys, codes, English names, zones, aliases, cities, DaData's
  spellings, the platforms' names, and the phone's search lexicon.

``action`` and ``recommended`` are decided here and nowhere else. The phone
reads them rather than re-deriving them, because two implementations of «which
diary should this family use» disagree within a month.

Run from ``server/``: ``python -m scripts.region_catalog`` writes the file;
``--check`` fails when the committed one is not what this would write, which is
what ``tests/test_region_catalog.py`` runs so a survey edit cannot ship without
the catalog it implies.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import tomllib
from collections import Counter
from collections.abc import Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from app.catalog import normalise_name
from app.providers.diary import registry
from app.providers.netschool import regions as allow_list
from app.providers.petersburg.client import BASE_URL as PETERSBURG_ORIGIN

SERVER = Path(__file__).resolve().parents[1]
ROOT = SERVER.parent
REGIONS_MD = ROOT / "docs" / "diaries" / "regions.md"
DIARIES_MD = ROOT / "docs" / "diaries.md"
PAGES = ROOT / "docs" / "diaries"
OVERLAY = Path(__file__).resolve().with_name("region_catalog.toml")
OUTPUT = SERVER / "app" / "catalog" / "data" / "regions.json"

SCHEMA = 1
SOURCE = "docs/diaries/regions.md"

#: The survey's role words, mapped onto the research's own vocabulary, which
#: shows through in regions.md's «Corrected during verification» prose.
ROLES = {
    "in use now": "primary",
    "alongside": "secondary",
    "previous": "legacy",
    "moving to": "migrating_to",
    "unclear": "unclear",
}
#: Roles a family is never offered: a system the region has left, and one the
#: survey could not place. Their ``action`` is ``none`` whatever the platform.
NEVER_OFFERED = ("legacy", "unclear")
CONFIDENCE = ("low", "medium", "high")

TOR = "tor-myschool"
PETERSBURG = registry.PETERSBURG
NETSCHOOL = registry.NETSCHOOL
#: Where every ТОР row hands off to. Госуслуги's own page, opened in whatever
#: the phone opens links with — never signed into from here (the decision
#: record, §1: a Госуслуги session is a session to the whole state account).
TOR_HANDOFF = "https://www.gosuslugi.ru/school"

#: The words the phone decodes. It has a string resource per reason and per
#: modifier (its «почему» sheet) and a branch per state, so a new word here is a
#: phone change first and a generator change second, never the other way round.
REASONS = (
    "primary", "tor_primary", "front_end", "moving_to", "unreachable", "unsupported", "no_diary",
)
MODIFIERS = ("unverified", "esia_only")
KINDS = ("diary", "portal", "admissions", "none")
SIGNIN = ("password", "mixed", "esia_only", "oauth", "retired", "unknown", "none")
TOR_STATES = ("primary", "moving_to", "front_end", "unclear", "absent", "unlisted")
EXCLUSIONS = ("http-only",)

_KEY = re.compile(r"^[a-z][a-z0-9]*(?:-[a-z0-9]+)*$")
_MANDATORY = "*Mandatory region.*"
_ROLE_START = re.compile(r"^- \*\*(?:in use now|alongside|previous|moving to|unclear):")
_BULLET = re.compile(r"^- \*\*(in use now|alongside|previous|moving to|unclear): (.+?)\*\* — (.*)$")
_LINK = re.compile(r"^\[([^\]]+)\]\(([a-z0-9-]+)\.md\)( as a front end)?$")
# Anchored on the labels, never on the first full stop: «Since» carries
# «01.12.2016», a label can be «Е-услуги. Образование», and every host is dots.
_HOSTS = re.compile(r"Hosts: (`[^`]+`(?:, `[^`]+`)*)\. (?=Since: |Confidence: )")
_CONFIDENCE = re.compile(r"Confidence: (high|medium|low)\.$")
_TABLE_LINK = re.compile(r"^\[[^\]]+\]\(diaries/([a-z0-9-]+)\.md\)")
_MONTHS = (
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
_SURVEY_MONTH = re.compile(r"In use now \((" + "|".join(_MONTHS) + r") (\d{4})\)")


class CatalogError(Exception):
    """The survey, the overlay and the allow-list do not add up to a catalog."""


# --------------------------------------------------------------------------
# The survey
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Host:
    """One address from a bullet's ``Hosts:``, taken apart.

    ``name`` is the Unicode form and ``ascii`` the one a URL is built from, so
    that «школа.образование33.рф» and its punycode twin are one host, not two.
    ``*.eljur.ru`` stays a pattern: it names every school's subdomain and is
    never somewhere to send anybody.
    """

    name: str
    ascii: str
    port: int | None
    path: str

    @property
    def pattern(self) -> bool:
        return self.name.startswith("*.")

    def display(self) -> str:
        port = f":{self.port}" if self.port is not None else ""
        return f"{self.name}{port}{self.path}"

    def url(self) -> str | None:
        if self.pattern:
            return None
        port = f":{self.port}" if self.port is not None else ""
        return f"https://{self.ascii}{port}{self.path}"


@dataclass(frozen=True)
class SurveySystem:
    role: str
    label: str
    page: str | None
    front_end: bool
    hosts: tuple[Host, ...]
    confidence: str


@dataclass(frozen=True)
class SurveyRegion:
    name: str
    mandatory: bool
    systems: tuple[SurveySystem, ...]


def parse_host(token: str) -> Host:
    """``netcity.admsakhalin.ru:11111`` → name, port; ``gosuslugi.ru/school`` → name, path."""
    text = token.strip().replace("\\|", "|").lower()
    head, slash, rest = text.partition("/")
    name, colon, port = head.partition(":")
    if colon and not port.isdigit():
        raise CatalogError(f"host «{token}» has a port that is not a number")
    if not name:
        raise CatalogError(f"host «{token}» has no name")
    if name.startswith("*."):
        ascii_name = name
    else:
        try:
            ascii_name = name.encode("idna").decode("ascii")
        except UnicodeError as exc:
            raise CatalogError(f"host «{token}» is not a hostname: {exc}") from exc
        name = ascii_name.encode("ascii").decode("idna")
    return Host(
        name=name,
        ascii=ascii_name,
        port=int(port) if colon else None,
        path=slash + rest if slash else "",
    )


def _hosts(tokens: Sequence[str]) -> tuple[Host, ...]:
    """Parsed, with an IDN and its punycode twin folded into the first seen."""
    seen: set[tuple[str, int | None, str]] = set()
    kept: list[Host] = []
    for token in tokens:
        host = parse_host(token)
        identity = (host.ascii, host.port, host.path)
        if identity in seen:
            continue
        seen.add(identity)
        kept.append(host)
    return tuple(kept)


def _system(line: str, region: str) -> SurveySystem:
    match = _BULLET.match(line)
    if match is None:
        raise CatalogError(f"{region}: a system bullet this cannot read: {line[:120]}")
    role_words, label, body = match.groups()
    label = label.replace("\\|", "|")

    page: str | None = None
    front_end = False
    link = _LINK.match(label)
    if link is not None:
        label, page, front = link.groups()
        front_end = front is not None

    if body.count("Confidence: ") != 1 or (confidence := _CONFIDENCE.search(body)) is None:
        raise CatalogError(f"{region}: «{label}» must end on exactly one «Confidence: …».")
    tokens: list[str] = []
    if "Hosts: " in body:
        hosts = _HOSTS.findall(body)
        if len(hosts) != 1 or body.count("Hosts: ") != 1:
            raise CatalogError(f"{region}: «{label}» has a «Hosts:» field this cannot read.")
        tokens = re.findall(r"`([^`]+)`", hosts[0])

    return SurveySystem(
        role=ROLES[role_words],
        label=label,
        page=page,
        front_end=front_end,
        hosts=_hosts(tokens),
        confidence=confidence.group(1),
    )


def parse_survey(text: str) -> list[SurveyRegion]:
    """Every ``## `` block of regions.md, with its system bullets, in file order.

    The other bullets of a block — «Open-source clients», «Notes.», «Checked:»
    — and the indented evidence under each system are not read. A bullet that
    *starts* like a system but does not parse stops the build rather than
    leaving a region one system short.
    """
    parsed: list[SurveyRegion] = []
    for block in re.split(r"^## ", text, flags=re.MULTILINE)[1:]:
        lines = block.splitlines()
        name = lines[0].strip()
        systems = tuple(
            _system(line, name) for line in lines[1:] if _ROLE_START.match(line)
        )
        mandatory = any(line.strip() == _MANDATORY for line in lines[1:])
        parsed.append(SurveyRegion(name=name, mandatory=mandatory, systems=systems))
    return parsed


def _table(text: str, header_start: str) -> list[list[str]]:
    """The rows of the markdown table whose header line starts with ``header_start``."""
    lines = text.splitlines()
    starts = [index for index, line in enumerate(lines) if line.startswith(header_start)]
    if len(starts) != 1:
        raise CatalogError(f"docs/diaries.md has {len(starts)} tables headed «{header_start}»")
    rows: list[list[str]] = []
    # Past the header and the «| --- |» line under it.
    for line in lines[starts[0] + 2 :]:
        if not line.startswith("|"):
            break
        cells = re.split(r"(?<!\\)\|", line.strip())[1:-1]
        rows.append([cell.strip() for cell in cells])
    return rows


def platform_table(text: str) -> dict[str, tuple[int, int]]:
    """docs/diaries.md's platforms table: page → (regions on it now, moving to it)."""
    def number(cell: str) -> int:
        return 0 if cell == "—" else int(cell)

    counts: dict[str, tuple[int, int]] = {}
    for cells in _table(text, "| Platform | Regions on it now |"):
        link = _TABLE_LINK.match(cells[0])
        if link is None:
            raise CatalogError(f"the platforms table has a row with no page: {cells[0]}")
        counts[link.group(1)] = (number(cells[1]), number(cells[2]))
    return counts


def survey_month(text: str) -> str:
    """«In use now (September 2026)» in the regions table's header, as ``2026-09``."""
    match = _SURVEY_MONTH.search(text)
    if match is None:
        raise CatalogError("docs/diaries.md's regions table does not say which month it is")
    return f"{match.group(2)}-{_MONTHS.index(match.group(1)) + 1:02d}"


def cross_check(survey: Sequence[SurveyRegion], table_md: str) -> None:
    """Hold regions.md to the summary table in docs/diaries.md, row by row.

    The table is a tool's summary and truncates, so it is never read for data —
    but it is what a reader looks at first, and a survey edit that leaves the two
    disagreeing about a region's diary is one of them being wrong.
    """
    rows = _table(table_md, "| Region | In use now (")
    names = [region.name for region in survey]
    table_names = [cells[0].strip("*") for cells in rows]
    if table_names != names:
        raise CatalogError("docs/diaries.md's regions table and regions.md list different regions")
    for region, cells in zip(survey, rows, strict=True):
        where = f"docs/diaries.md, {region.name}"
        if cells[0].startswith("**") != region.mandatory:
            raise CatalogError(f"{where}: bold in the table and «{_MANDATORY}» disagree")
        primary = _primary(region)
        link = _TABLE_LINK.match(cells[1])
        if primary.page is None:
            if link is not None or not cells[1].startswith(primary.label):
                raise CatalogError(f"{where}: the table's first system is not «{primary.label}»")
        elif link is None or link.group(1) != primary.page:
            raise CatalogError(f"{where}: the table's first system is not on {primary.page}.md")
        if cells[4] != primary.confidence:
            raise CatalogError(f"{where}: the table's confidence is not the primary's")
        moving = [system for system in region.systems if system.role == "migrating_to"]
        moving_link = _TABLE_LINK.match(cells[2])
        if [system.page for system in moving] != ([moving_link.group(1)] if moving_link else []):
            raise CatalogError(f"{where}: «Moving to» and the «moving to» bullets disagree")


def _primary(region: SurveyRegion) -> SurveySystem:
    primaries = [system for system in region.systems if system.role == "primary"]
    if len(primaries) != 1:
        raise CatalogError(f"{region.name}: {len(primaries)} «in use now» systems, not one")
    return primaries[0]


# --------------------------------------------------------------------------
# The overlay
# --------------------------------------------------------------------------


def _fields(entry: dict[str, Any], required: set[str], optional: set[str], where: str) -> None:
    missing = required - entry.keys()
    unknown = entry.keys() - required - optional
    if missing or unknown:
        raise CatalogError(
            f"{where}: missing {sorted(missing)}, unknown {sorted(unknown)} in the overlay"
        )


@dataclass(frozen=True)
class Platform:
    key: str
    page: str | None
    host: str | None
    name_ru: str
    name_en: str
    kind: str
    signin: str
    verified: bool

    @property
    def provider(self) -> str | None:
        return self.key if self.key in registry.KEYS else None

    def out(self) -> dict[str, Any]:
        return {
            "name_ru": self.name_ru,
            "name_en": self.name_en,
            "page": self.page,
            "provider": self.provider,
            "kind": self.kind,
            "signin": self.signin,
            "origin": PETERSBURG_ORIGIN if self.key == PETERSBURG else None,
            "handoff": TOR_HANDOFF if self.key == TOR else None,
        }


def _platforms(overlay: dict[str, Any]) -> dict[str, Platform]:
    platforms: dict[str, Platform] = {}
    for entry in overlay.get("platform", []):
        where = f"[[platform]] {entry.get('key')!r}"
        _fields(entry, {"key", "name_ru", "name_en", "kind", "signin"},
                {"page", "host", "verified"}, where)
        key = entry["key"]
        if key in platforms or not _KEY.match(key):
            raise CatalogError(f"{where}: a platform key must be a unique slug")
        if entry["kind"] not in KINDS or entry["signin"] not in SIGNIN:
            raise CatalogError(f"{where}: kind must be one of {KINDS}, signin one of {SIGNIN}")
        page = entry.get("page")
        if page is not None and not (PAGES / f"{page}.md").is_file():
            raise CatalogError(f"{where}: docs/diaries/{page}.md does not exist")
        if "verified" in entry and key != PETERSBURG:
            # «Сетевой город»'s answer is per region and lives in the allow-list.
            raise CatalogError(f"{where}: only Petersburg's `verified` is kept here")
        _names(entry, where)
        platforms[key] = Platform(
            key=key,
            page=page,
            host=entry.get("host"),
            name_ru=entry["name_ru"],
            name_en=entry["name_en"],
            kind=entry["kind"],
            signin=entry["signin"],
            verified=bool(entry.get("verified", False)),
        )
    for needed in (PETERSBURG, NETSCHOOL, TOR):
        if needed not in platforms:
            raise CatalogError(f"the overlay has no [[platform]] {needed!r}")
    return platforms


def _names(entry: dict[str, Any], where: str) -> None:
    """Both names, and the English one really English — it is the only twin they get.

    Names in the JSON escape ``ResourceTranslationTest``, which is what holds
    every other Russian string on the phone to an English one.
    """
    ru, en = entry["name_ru"].strip(), entry["name_en"].strip()
    if not ru or not en or re.search(r"[Ѐ-ӿ]", en):
        raise CatalogError(f"{where}: needs a Russian name and an English one without Cyrillic")


def _platform_of(
    system: SurveySystem, platforms: dict[str, Platform], unlinked: dict[str, str], region: str
) -> Platform:
    if system.page is None:
        key = unlinked.get(system.label)
        if key is None:
            raise CatalogError(f"{region}: «{system.label}» links no page and is not in [unlinked]")
        return platforms[key]
    candidates = [platform for platform in platforms.values() if platform.page == system.page]
    if len(candidates) > 1:
        # One page, several platforms: myschool-federal.md is both ТОР and the
        # federal portal, and only the host says which a bullet means.
        shown = {host.display() for host in system.hosts}
        candidates = [platform for platform in candidates if platform.host in shown]
    if len(candidates) != 1:
        raise CatalogError(f"{region}: «{system.label}» maps to {len(candidates)} platforms")
    return candidates[0]


@dataclass(frozen=True)
class RegionSpec:
    name_ru: str
    key: str
    code: str
    iso: str | None
    name_en: str
    zone: str
    aliases: tuple[str, ...]
    cities: tuple[str, ...]
    dadata_names: tuple[str, ...]
    tor_absent: bool
    netschool_excluded: str | None


def _region_specs(overlay: dict[str, Any], survey: Sequence[SurveyRegion]) -> dict[str, RegionSpec]:
    specs: dict[str, RegionSpec] = {}
    keys: set[str] = set()
    codes: set[str] = set()
    dadata: dict[str, str] = {}
    for entry in overlay.get("region", []):
        where = f"[[region]] {entry.get('name_ru')!r}"
        _fields(
            entry,
            {"name_ru", "key", "code", "name_en", "zone", "aliases", "cities", "dadata_names"},
            {"iso", "tor", "netschool_excluded"},
            where,
        )
        key, code = entry["key"], entry["code"]
        if not _KEY.match(key) or len(key) > 32 or key in keys:
            # 32: the width of classes.diary_region and diary_sessions.region.
            raise CatalogError(f"{where}: key must be a unique slug of at most 32 characters")
        if not re.fullmatch(r"\d\d", code) or code in codes:
            raise CatalogError(f"{where}: code must be two digits and unique")
        try:
            ZoneInfo(entry["zone"])
        except (ZoneInfoNotFoundError, ValueError) as exc:
            raise CatalogError(f"{where}: zone {entry['zone']!r} is not an IANA zone") from exc
        if entry.get("tor") not in (None, "absent"):
            raise CatalogError(f"{where}: `tor` can only say «absent»; the survey says the rest")
        if entry.get("netschool_excluded") not in (None, *EXCLUSIONS):
            raise CatalogError(f"{where}: netschool_excluded must be one of {EXCLUSIONS}")
        if not entry["dadata_names"]:
            raise CatalogError(f"{where}: needs at least one of DaData's spellings")
        for name in entry["dadata_names"]:
            other = dadata.setdefault(normalise_name(name), key)
            if other != key:
                raise CatalogError(f"{where}: DaData's «{name}» already places {other!r}")
        _names(entry, where)
        keys.add(key)
        codes.add(code)
        if entry["name_ru"] in specs:
            raise CatalogError(f"{where}: listed twice")
        specs[entry["name_ru"]] = RegionSpec(
            name_ru=entry["name_ru"],
            key=key,
            code=code,
            iso=entry.get("iso"),
            name_en=entry["name_en"],
            zone=entry["zone"],
            aliases=tuple(entry["aliases"]),
            cities=tuple(entry["cities"]),
            dadata_names=tuple(entry["dadata_names"]),
            tor_absent=entry.get("tor") == "absent",
            netschool_excluded=entry.get("netschool_excluded"),
        )
    headings = {region.name for region in survey}
    if set(specs) != headings:
        raise CatalogError(
            "the overlay's regions are not regions.md's headings: "
            f"missing {sorted(headings - set(specs))}, unknown {sorted(set(specs) - headings)}"
        )
    return specs


def _search(overlay: dict[str, Any]) -> dict[str, Any]:
    search = overlay.get("search", {})
    _fields(
        search,
        {"fold", "stop_words", "latin_layout", "cyrillic_layout", "translit", "school_words"},
        set(),
        "[search]",
    )
    latin, cyrillic = search["latin_layout"], search["cyrillic_layout"]
    if len(latin) != len(cyrillic) or len(set(latin)) != len(latin) or len(set(cyrillic)) != len(
        cyrillic
    ):
        raise CatalogError("[search]: the two layouts must pair key for key, each key once")
    if any(len(source) != 1 or len(target) != 1 for source, target in search["fold"].items()):
        raise CatalogError("[search]: fold maps one character to one character")
    pairs = [tuple(pair) for pair in search["translit"]]
    if any(len(pair) != 2 or not pair[0].isascii() for pair in pairs):
        raise CatalogError("[search]: translit is a list of [latin, cyrillic] pairs")
    if [len(pair[0]) for pair in pairs] != sorted((len(pair[0]) for pair in pairs), reverse=True):
        raise CatalogError("[search]: translit must list longer Latin spellings first")
    return {
        "fold": dict(search["fold"]),
        "stop_words": list(search["stop_words"]),
        "latin_layout": latin,
        "cyrillic_layout": cyrillic,
        "translit": [list(pair) for pair in pairs],
        "school_words": list(search["school_words"]),
    }


def _survey_block(overlay: dict[str, Any], table_md: str) -> dict[str, str]:
    block = overlay.get("survey", {})
    _fields(block, {"month"}, set(), "[survey]")
    month = block["month"]
    if month != survey_month(table_md):
        raise CatalogError(f"[survey] month {month} is not the month docs/diaries.md describes")
    year, number = (int(part) for part in month.split("-"))
    # A survey from September onwards describes the year that has just begun.
    start = year if number >= 9 else year - 1
    return {"month": month, "school_year": f"{start}/{(start + 1) % 100:02d}"}


# --------------------------------------------------------------------------
# Deciding
# --------------------------------------------------------------------------


def _netschool_block(
    region: RegionSpec, system: SurveySystem, platform: Platform
) -> dict[str, Any] | None:
    """The allow-list row this system is, or ``None`` if the phone may not go there.

    Linked by the region's key and nothing else — the survey's hosts only have
    to *agree* with the allow-list's origin, which catches a region whose server
    moved in one file and not the other.
    """
    if platform.key != NETSCHOOL or system.role in NEVER_OFFERED:
        return None
    row = allow_list.get(region.key)
    if row is None:
        return None
    origin = urlsplit(row.origin)
    shown = {(host.ascii, host.port) for host in system.hosts if not host.path}
    if (origin.hostname, origin.port) not in shown:
        raise CatalogError(
            f"{region.name_ru}: the allow-list's {row.origin} is not among the survey's hosts"
        )
    if row.title != region.name_ru:
        raise CatalogError(f"{region.name_ru}: the allow-list calls {row.key!r} «{row.title}»")
    return {
        "region": row.key,
        "origin": row.origin,
        "password": row.password,
        "verified": row.verified,
        "zone": row.zone,
    }


def _system_out(region: RegionSpec, system: SurveySystem, platform: Platform) -> dict[str, Any]:
    netschool = _netschool_block(region, system, platform)
    excluded = None
    if platform.key == NETSCHOOL and system.role == "primary" and region.netschool_excluded:
        excluded = region.netschool_excluded

    action, handoff = "none", None
    if system.role not in NEVER_OFFERED:
        if platform.key == PETERSBURG:
            action = "signin"
        elif netschool is not None:
            # A password region is signed into on the phone; a Госуслуги-only one
            # is its own origin's page, whose Госуслуги button the family presses
            # in the browser (K19) — not ТОР's, which is a different diary.
            action, handoff = ("signin", None) if netschool["password"] else (
                "handoff", netschool["origin"]
            )
        elif platform.key == TOR:
            action, handoff = "handoff", TOR_HANDOFF

    if platform.key == PETERSBURG:
        web_url: str | None = PETERSBURG_ORIGIN
    elif netschool is not None:
        web_url = netschool["origin"]
    elif platform.key == TOR:
        web_url = TOR_HANDOFF
    elif excluded is not None:
        # An origin that serves only http is not somewhere this app sends anybody.
        web_url = None
    else:
        web_url = next((url for host in system.hosts if (url := host.url())), None)

    return {
        "platform": platform.key,
        "role": system.role,
        "front_end": system.front_end,
        "confidence": system.confidence,
        "hosts": [host.display() for host in system.hosts],
        "action": action,
        "handoff_url": handoff,
        "web_url": web_url,
        "netschool": netschool,
        "excluded": excluded,
    }


def recommend(systems: Sequence[dict[str, Any]], platforms: dict[str, Platform]) -> dict[str, Any]:
    """Which one system the phone puts first for a region, and why. First match wins.

    1. The primary, if it can be signed into here: ``primary``, plus
       ``unverified`` while nobody has signed into it for real.
    2. The primary, if it is ТОР: ``tor_primary``.
    3. The primary, if it is a Госуслуги-only «Сетевой город»: ``primary`` +
       ``esia_only``.
    4. The primary, if it is a «Сетевой город» the allow-list leaves out
       (Волгоград): ``unreachable``. It sits above the front-end rule on
       purpose: Волгоград also has «Госуслуги Моя школа» in front of it, and the
       family should first be told why their own diary cannot be opened.
    5. ТОР in front of the regional system, sure at least to «medium»:
       ``front_end``.
    6. ТОР as where the region is moving: ``moving_to``.
    7. Otherwise the primary, which the app cannot read: ``unsupported`` — or
       ``no_diary`` where it is paper.

    The JSON carries only these codes; the sentences are the phone's string
    resources, so every one of them has its English twin checked.
    """
    primary_index = next(i for i, system in enumerate(systems) if system["role"] == "primary")
    primary = systems[primary_index]

    def pick(index: int, reason: str, modifiers: Sequence[str] = ()) -> dict[str, Any]:
        return {
            "system": index,
            "platform": systems[index]["platform"],
            "confidence": systems[index]["confidence"],
            "reason": reason,
            "modifiers": list(modifiers),
        }

    def verified(system: dict[str, Any]) -> bool:
        if system["netschool"] is not None:
            return bool(system["netschool"]["verified"])
        return platforms[system["platform"]].verified

    if primary["action"] == "signin":
        return pick(primary_index, "primary", () if verified(primary) else ("unverified",))
    if primary["platform"] == TOR:
        return pick(primary_index, "tor_primary")
    if primary["platform"] == NETSCHOOL and primary["action"] == "handoff":
        return pick(primary_index, "primary", ("esia_only",))
    if primary["excluded"] is not None:
        return pick(primary_index, "unreachable")
    for index, system in enumerate(systems):
        if (
            system["platform"] == TOR
            and system["front_end"]
            and system["role"] == "secondary"
            and CONFIDENCE.index(system["confidence"]) >= CONFIDENCE.index("medium")
        ):
            return pick(index, "front_end")
    for index, system in enumerate(systems):
        if system["platform"] == TOR and system["role"] == "migrating_to":
            return pick(index, "moving_to")
    if platforms[primary["platform"]].kind == "none":
        return pick(primary_index, "no_diary")
    return pick(primary_index, "unsupported")


def _tor_state(systems: Sequence[dict[str, Any]], spec: RegionSpec) -> str:
    """Where ТОР stands in a region: the phone never offers it where this is «absent»."""
    tor = [system for system in systems if system["platform"] == TOR]
    roles = {system["role"] for system in tor}
    if spec.tor_absent:
        if tor:
            raise CatalogError(f"{spec.name_ru}: `tor` says absent, and the survey lists ТОР")
        return "absent"
    if "primary" in roles:
        return "primary"
    if "migrating_to" in roles:
        return "moving_to"
    if any(system["front_end"] and system["role"] == "secondary" for system in tor):
        return "front_end"
    if "unclear" in roles:
        return "unclear"
    # The survey lists no ТОР row here. docs/diaries.md says the app is offered
    # in front of every regional system but two, without evidence per region;
    # «unlisted» says exactly that much and no more.
    return "unlisted"


# --------------------------------------------------------------------------
# Building
# --------------------------------------------------------------------------


def build(regions_md: str, table_md: str, overlay: dict[str, Any]) -> dict[str, Any]:
    """The whole catalog, as a JSON-ready dict, or a :class:`CatalogError` saying why not."""
    survey = parse_survey(regions_md)
    cross_check(survey, table_md)
    platforms = _platforms(overlay)
    unlinked = dict(overlay.get("unlinked", {}))
    for label, key in unlinked.items():
        if key not in platforms or platforms[key].page is not None:
            raise CatalogError(f"[unlinked] «{label}» names {key!r}, not a page-less platform")
    specs = _region_specs(overlay, survey)

    out_regions: list[dict[str, Any]] = []
    for order, surveyed in enumerate(survey, start=1):
        spec = specs[surveyed.name]
        _primary(surveyed)
        systems = [
            _system_out(spec, system, _platform_of(system, platforms, unlinked, surveyed.name))
            for system in surveyed.systems
        ]
        if spec.netschool_excluded and not any(system["excluded"] for system in systems):
            raise CatalogError(f"{spec.name_ru}: excluded, and no primary «Сетевой город»")
        if spec.netschool_excluded and allow_list.get(spec.key) is not None:
            raise CatalogError(f"{spec.name_ru}: excluded, yet the allow-list has it")
        recommended = recommend(systems, platforms)
        tor = _tor_state(systems, spec)
        if tor == "absent" and recommended["platform"] == TOR:
            raise CatalogError(f"{spec.name_ru}: ТОР recommended where the survey has none")
        out_regions.append(
            {
                "key": spec.key,
                "order": order,
                "code": spec.code,
                "iso": spec.iso,
                "name_ru": spec.name_ru,
                "name_en": spec.name_en,
                "mandatory": surveyed.mandatory,
                "zone": spec.zone,
                "aliases": list(spec.aliases),
                "cities": list(spec.cities),
                "dadata_names": list(spec.dadata_names),
                "systems": systems,
                "recommended": recommended,
                "tor": tor,
            }
        )

    _check_allow_list(out_regions)
    used = {system["platform"] for region in out_regions for system in region["systems"]}
    if unused := sorted(set(platforms) - used):
        raise CatalogError(f"platforms no region uses: {unused}; take them out of the overlay")
    return {
        "schema": SCHEMA,
        "source": SOURCE,
        "survey": _survey_block(overlay, table_md),
        "search": _search(overlay),
        "platforms": {key: platforms[key].out() for key in sorted(used)},
        "regions": out_regions,
    }


def _check_allow_list(regions: Sequence[dict[str, Any]]) -> None:
    """Every allow-listed region reached exactly once, under its own key, in its own zone."""
    linked = Counter(
        system["netschool"]["region"]
        for region in regions
        for system in region["systems"]
        if system["netschool"] is not None
    )
    by_key = {region["key"]: region for region in regions}
    for row in allow_list.listed(password_only=False):
        if linked[row.key] != 1:
            raise CatalogError(f"allow-listed {row.key!r} is linked {linked[row.key]} times")
        region = by_key.get(row.key)
        if region is None or region["zone"] != row.zone:
            raise CatalogError(f"the allow-list's {row.key!r} has no region of that key and zone")


def render(catalog: dict[str, Any]) -> str:
    """Deterministic text: sorted keys, the survey's region order, one trailing newline.

    Regions are never sorted by name — Russian collation files every
    «Республика …» under Р, and the order the phone lists is the survey's.
    """
    return json.dumps(catalog, ensure_ascii=False, indent=1, sort_keys=True) + "\n"


def build_from_repository() -> dict[str, Any]:
    return build(
        REGIONS_MD.read_text(encoding="utf-8"),
        DIARIES_MD.read_text(encoding="utf-8"),
        tomllib.loads(OVERLAY.read_text(encoding="utf-8")),
    )


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="python -m scripts.region_catalog", description=__doc__)
    parser.add_argument(
        "--check",
        action="store_true",
        help="exit 1 if the committed catalog is not what this would write",
    )
    args = parser.parse_args(argv)
    try:
        text = render(build_from_repository())
    except CatalogError as exc:
        print(f"region catalog: {exc}", file=sys.stderr)
        return 1
    relative = OUTPUT.relative_to(ROOT)
    if args.check:
        current = OUTPUT.read_text(encoding="utf-8") if OUTPUT.is_file() else ""
        if current != text:
            print(
                f"{relative} is not what the survey implies; run "
                "`python -m scripts.region_catalog` from server/ and commit the result",
                file=sys.stderr,
            )
            return 1
        print(f"{relative} is current")
        return 0
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(text, encoding="utf-8")
    print(f"wrote {relative}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
