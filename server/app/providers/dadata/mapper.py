"""A register row in, a school out.

Everything peculiar to ЕГРЮЛ is meant to die here: the name in four spellings,
the address nested two levels down, the status that is a string in one field
and a code in another, the school whose short name is the empty string.

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
        active=_status_of(data) == _LIVE_STATUS,
    )


def to_schools(items: list[dict[str, Any]]) -> list[School]:
    """The readable ones, in the order the upstream ranked them.

    Deduplicated by ОГРН: a school that has been reorganised can appear twice
    in one answer under two spellings of the same name, and two identical rows
    in a picker is a question with no right answer.
    """
    schools: list[School] = []
    seen: set[str] = set()
    for item in items:
        school = to_school(item)
        if school is None:
            log.debug("dadata: unreadable suggestion dropped")
            continue
        key = school.ogrn or school.full_name
        if key in seen:
            continue
        seen.add(key)
        schools.append(school)
    return schools


def humanise(name: str) -> str:
    """«МБОУ "СРЕДНЯЯ ШКОЛА № 197"» → «МБОУ "Средняя школа № 197"».

    Only the shouting is undone, never the wording: schools are told apart by
    number and by a single word («лицей», «гимназия»), and rewriting either
    would be inventing a name the register does not have.

    One capital, on the first real word, and lower case after it — a title,
    not a headline. «Средняя Школа» reads as two proper nouns and is not how
    anybody writes their school down.
    """
    parts: list[str] = []
    capitalised = False
    for word in name.split(" "):
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
    return bool(_ABBREVIATIONS.match(stripped))


def _capitalise(word: str) -> str:
    """Lower the word, keeping whatever quote or bracket it arrived wearing."""
    lowered = word.lower()
    for index, character in enumerate(lowered):
        if character.isalpha():
            return lowered[:index] + character.upper() + lowered[index + 1 :]
    return lowered


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
