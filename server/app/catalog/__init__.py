"""The region catalog, as the server reads it.

``data/regions.json`` is generated from the survey of every region's diary by
``scripts/region_catalog.py`` and committed; nothing here writes it, and a
change to a region belongs in the survey or in ``scripts/region_catalog.toml``,
never in the JSON. The phone bundles the same file and reads everything in it.
The server reads four things: a region's key, its two-digit subject code, its
names, and the spellings DaData uses for it — which is what turning a school
found in the company register into «this region» needs, and nothing more.

The file is read on first use, not at import: the directory endpoint is the one
caller on a request path, and every other cold start should not pay for
parsing eighty-nine regions it will never look at.
"""

from __future__ import annotations

import functools
import json
import re
from dataclasses import dataclass
from pathlib import Path

#: Beside this module, so that it ships wherever ``app`` does: Vercel uploads
#: ``server/app`` whole, and a wheel carries it as package data (pyproject.toml).
DATA = Path(__file__).resolve().parent / "data" / "regions.json"

#: Anything that is not a letter or a digit. DaData writes «Респ Саха /Якутия/»
#: and «Кемеровская область - Кузбасс», the survey «Республика Саха (Якутия)»
#: and «Кемеровская область — Кузбасс»; the punctuation is where they differ.
_NOT_ALNUM = re.compile(r"[\W_]+")


@dataclass(frozen=True)
class CatalogRegion:
    key: str
    code: str
    name_ru: str
    name_en: str
    dadata_names: tuple[str, ...]


@dataclass(frozen=True)
class _Catalog:
    regions: tuple[CatalogRegion, ...]
    by_key: dict[str, CatalogRegion]
    by_code: dict[str, CatalogRegion]
    by_dadata_name: dict[str, CatalogRegion]
    school_words: tuple[str, ...]


def normalise_name(text: str) -> str:
    """A region's name reduced to what two spellings of it have in common.

    Case, ``ё`` and every run of punctuation and spaces are dropped, so
    «Респ Саха /Якутия/» and «респ саха якутия» are one name. It is the rule
    ``dadata_names`` are compared by, here and in the generator that refuses a
    spelling two regions would share — one function, so the two cannot disagree.
    """
    folded = text.casefold().replace("ё", "е")
    return " ".join(_NOT_ALNUM.sub(" ", folded).split())


@functools.cache
def _load() -> _Catalog:
    raw = json.loads(DATA.read_text(encoding="utf-8"))
    regions = tuple(
        CatalogRegion(
            key=entry["key"],
            code=entry["code"],
            name_ru=entry["name_ru"],
            name_en=entry["name_en"],
            dadata_names=tuple(entry["dadata_names"]),
        )
        for entry in raw["regions"]
    )
    return _Catalog(
        regions=regions,
        by_key={region.key: region for region in regions},
        by_code={region.code: region for region in regions},
        by_dadata_name={
            normalise_name(name): region for region in regions for name in region.dadata_names
        },
        school_words=tuple(raw["search"]["school_words"]),
    )


def regions() -> tuple[CatalogRegion, ...]:
    """Every region, in the survey's order (Constitution Art. 65)."""
    return _load().regions


def get(key: str | None) -> CatalogRegion | None:
    """The region stored under ``key``, or ``None`` for an unknown one."""
    if not key:
        return None
    return _load().by_key.get(key)


def by_code(code: str | None) -> CatalogRegion | None:
    """The region whose two-digit subject code this is.

    Only two digits: DaData's ``region_kladr_id`` is thirteen, and the caller
    cuts it, because «is this a code at all» is a question about DaData's answer
    rather than about the catalog.
    """
    if code is None or len(code) != 2 or not code.isdigit():
        return None
    return _load().by_code.get(code)


def by_dadata_name(text: str | None) -> CatalogRegion | None:
    """The region DaData means by ``text`` («Респ Татарстан», «г Москва»).

    An exact match after :func:`normalise_name`, never a partial one: «Алтай»
    is a republic and «Алтайский» a krai, and a guess between them would put a
    school in the wrong region without anybody seeing it happen. A name the
    catalog does not know answers ``None`` and the caller shows DaData's label.
    """
    if not text:
        return None
    return _load().by_dadata_name.get(normalise_name(text))


def school_words() -> tuple[str, ...]:
    """The words a school's name is made of rather than told apart by.

    The phone reads the same list out of the same file, so «too common to look
    up» means one thing on both sides of the directory call.
    """
    return _load().school_words
