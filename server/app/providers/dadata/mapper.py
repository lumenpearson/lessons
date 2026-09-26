"""A register row in, a school out.

Everything peculiar to the ЕГРЮЛ company register is meant to die here: the name
in four spellings, the address nested two levels down, the status that is a
string in one field and a code in another, the school whose short name is the
empty string.

Forgiving on the way in, strict on the way out. A row that cannot be read is
dropped rather than taking the search with it — one unreadable suggestion out
of twenty is not worth an error screen — but a school with no name is not a
school, and there is nothing to show for it.
"""

from __future__ import annotations

import logging
import re
from typing import Any

from app.providers.dadata.models import School

log = logging.getLogger(__name__)

#: The register shouts. Names are stored upper case («МУНИЦИПАЛЬНОЕ БЮДЖЕТНОЕ
#: ОБЩЕОБРАЗОВАТЕЛЬНОЕ УЧРЕЖДЕНИЕ…»), which is unreadable in a list of five,
#: so the long words are lowered and the abbreviations left alone.
#:
#: One letter before the ending, not two: «СОШ», «ООШ» and «НОШ» are three
#: letters altogether, and requiring two in front of «ОШ» meant the commonest
#: abbreviation on a Russian school's sign was not an abbreviation here at all
#: - «МБОУ "СОШ № 197"» came out as «МБОУ "Сош № 197"». «МБОУ» and «МОУ» were
#: matched either way, which is why it went unnoticed; nothing ending in «ОШ»
#: has ever been long enough to match.
_ABBREVIATIONS = re.compile(
    r"^(?:[А-ЯЁ]+(?:БОУ|ОУ|ОШ|У)|[A-Z]{2,})$",
)

#: «А.С.», «М.В.», «А.» — initials, which are not shouting and never were.
#: Lowered like a long word they came out as «им. а.с. пушкина», which is the
#: one part of a school's name that is a person's.
_INITIALS = re.compile(r"^(?:[А-ЯЁA-Z]\.){1,3}$")

#: The word after which what follows is somebody's name rather than a
#: description. It is itself always lower case — «СОШ № 5 ИМ. ЛОМОНОСОВА» has
#: no lowerable word in front of it, so «ИМ.» was taken for the name's first
#: real word and given the one capital: «СОШ № 5 Им. ломоносова».
_DEDICATION = {"им", "им.", "имени"}

#: Statuses the register uses. Only the first means "operating".
_LIVE_STATUS = "ACTIVE"


def to_school(item: dict[str, Any]) -> School | None:
    """One suggestion, or ``None`` if there is nothing worth showing."""
    data = item.get("data")
    if not isinstance(data, dict):
        return None

    names = data.get("name")
    names = names if isinstance(names, dict) else {}
    full = (
        _text(names, "full_with_opf")
        or _text(names, "full")
        or _text(item, "value")
        or _text(item, "unrestricted_value")
    )
    if not full:
        return None

    short = _text(names, "short_with_opf") or _text(names, "short")
    address = data.get("address")
    address = address if isinstance(address, dict) else {}
    address_data = address.get("data")
    address_data = address_data if isinstance(address_data, dict) else {}

    return School(
        full_name=full,
        name=humanise(short or full),
        ogrn=_text(data, "ogrn"),
        inn=_text(data, "inn"),
        address=_text(address, "unrestricted_value") or _text(address, "value"),
        city=_text(address_data, "city") or _text(address_data, "settlement"),
        region=_text(address_data, "region_with_type") or _text(address_data, "region"),
        region_code=_region_code(address_data),
        active=_status_of(data) == _LIVE_STATUS,
    )


def to_schools(items: list[dict[str, Any]], *, per_region: bool = False) -> list[School]:
    """The readable ones, in the order the upstream ranked them.

    Deduplicated by registration number: a school that has been reorganised can appear twice
    in one answer under two spellings of the same name, and two identical rows
    in a picker is a question with no right answer.

    @param per_region keep one row per registration number **per region**
        instead of one per number. A branch (филиал) is registered under its
        head school's OGRN, so the bot's picker, which has always deduplicated
        on the number, shows the two as one — and is left that way. The
        directory's question is «which regions is this school in», and folding
        a branch into its head answers it with one region where there are two. Keyed on the
        region's code and, for a row without one, on the register's own name
        for the region: the code is not on every row, and a branch read
        without it is still in another region.
    """
    schools: list[School] = []
    seen: set[tuple[str, str | None]] = set()
    unreadable = 0
    for item in items:
        school = to_school(item)
        if school is None:
            unreadable += 1
            continue
        where = (school.region_code or school.region) if per_region else None
        key = (school.ogrn or school.full_name, where)
        if key in seen:
            continue
        seen.add(key)
        schools.append(school)
    _note_if_nothing_read(items, unreadable)
    return schools


def _note_if_nothing_read(items: list[dict[str, Any]], unreadable: int) -> None:
    """Say so when a whole answer read as nothing.

    Dropping the one suggestion that cannot be read is right; twenty out of
    twenty is not a bad row, it is `suggestions[].data` having been
    restructured — and the bot's answer for that is «По запросу «…» ничего не
    нашлось», the same sentence a school genuinely absent from ЕГРЮЛ gets. The
    two were indistinguishable because this was written at `debug` and
    `main.py` sets the level to `INFO`, so in every deployment the line was
    never emitted at all. The keys are logged because they are what the next
    spelling read in this file has to be.

    Counted rather than read off an empty result: the deduplication above can
    also shorten the list, and «all twenty were duplicates» is not this.
    """
    if not items or unreadable < len(items):
        return
    keys = sorted({key for item in items if isinstance(item, dict) for key in item})
    log.warning(
        "dadata: %d suggestion(s) in, none readable; keys seen: %s",
        len(items),
        ", ".join(keys[:20]) or "(no dict rows at all)",
    )


def humanise(name: str) -> str:
    """«МБОУ "СРЕДНЯЯ ШКОЛА № 197"» → «МБОУ "Средняя школа № 197"».

    Only the shouting is undone, never the wording: schools are told apart by
    number and by a single word («лицей», «гимназия»), and rewriting either
    would be inventing a name the register does not have.

    One capital, on the first real word, and lower case after it — a title,
    not a headline. «Средняя Школа» reads as two proper nouns and is not how
    anybody writes their school down.

    Past «им.» that rule inverts, because past «им.» the name is a person's:
    «им. А.С. Пушкина», not «им. а.с. пушкина». Initials are left as they
    arrived wherever they appear, and «им.» itself is always lower case — it
    used to collect the name's one capital whenever nothing lowerable came
    before it, which is every «СОШ № 5 ИМ. …» in the register.
    """
    parts: list[str] = []
    capitalised = False
    dedicated = False
    for word in name.split(" "):
        if word.strip('"«»()').lower() in _DEDICATION:
            parts.append(word.lower())
            dedicated = True
            capitalised = True
            continue
        if dedicated:
            # A surname, not a description: «Пушкина», never «пушкина». The
            # rule above lowers everything after the first capital, which is
            # right for «Средняя школа» and wrong for the one word the school
            # is actually named after.
            parts.append(word if _keep_case(word) else _capitalise(word))
            continue
        if _keep_case(word):
            parts.append(word)
            continue
        if capitalised:
            parts.append(word.lower())
        else:
            parts.append(_capitalise(word))
            capitalised = True
    return " ".join(parts)


def _keep_case(word: str) -> bool:
    stripped = word.strip('"«»()')
    if not stripped:
        return True
    # A number, a code, or an abbreviation everyone reads as one unit.
    if not any(character.isalpha() for character in stripped):
        return True
    if _INITIALS.match(stripped):
        return True
    return bool(_ABBREVIATIONS.match(stripped))


def _capitalise(word: str) -> str:
    """Lower the word, keeping whatever quote or bracket it arrived wearing."""
    lowered = word.lower()
    for index, character in enumerate(lowered):
        if character.isalpha():
            return lowered[:index] + character.upper() + lowered[index + 1 :]
    return lowered


def _region_code(address_data: dict[str, Any]) -> str | None:
    """The subject code at the front of ``region_kladr_id``, or ``None``.

    A KLADR id is thirteen digits whose first two are the region — «78» for
    Saint Petersburg, «16» for Tatarstan. Anything else in the field (a code
    of a new shape, a stray letter) gives ``None`` rather than a guess, and
    the caller falls back to the region's name. ASCII digits only: ``isdigit``
    alone accepts «²», which is a digit to Unicode and to nobody else.
    """
    kladr = _text(address_data, "region_kladr_id")
    code = kladr[:2] if kladr else ""
    return code if len(code) == 2 and code.isascii() and code.isdigit() else None


def _status_of(data: dict[str, Any]) -> str:
    state = data.get("state")
    if isinstance(state, dict):
        status = _text(state, "status")
        if status:
            return status.upper()
    return ""


def _text(source: dict[str, Any], key: str) -> str | None:
    value = source.get(key)
    return value.strip() if isinstance(value, str) and value.strip() else None
