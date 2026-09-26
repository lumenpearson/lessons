"""Which provider a stored key means, and what a class is bound to.

`provider_for` is the one map from a key to an implementation, and it imports
the provider module lazily — so neither Petersburg's client nor «Сетевой
город»'s lands on the cold-start path of a request that does not use it, the
same care `main.py` takes.

`binding` is the one place that reads a class's diary binding. `_bound` in the
bot, the main menu, the class card and the web form all call it, so that
"is this class bound, and to what" has a single answer and the web form never
imports aiogram to get it.
"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.providers.diary.base import DiaryProvider

if TYPE_CHECKING:
    from app.models import SchoolClass

PETERSBURG = "petersburg"
NETSCHOOL = "netschool"
KEYS = (PETERSBURG, NETSCHOOL)


def provider_for(key: str | None) -> DiaryProvider | None:
    """The provider for a key, or ``None`` for an unknown or absent one.

    A ``None`` (or unrecognised) key on a session row means the row pre-dates
    the ``provider`` column, which is Petersburg — but the caller decides that,
    not this function, which answers only about the key it was given. Lazy
    imports keep a provider's HTTP module off the path until it is asked for.
    """
    if key is None:
        return None
    if key == PETERSBURG:
        from app.providers.petersburg.provider import PetersburgProvider

        return PetersburgProvider()
    if key == NETSCHOOL:
        from app.providers.netschool.provider import NetSchoolProvider

        return NetSchoolProvider()
    return None


@dataclass(frozen=True)
class Binding:
    """A class's diary binding, resolved and validated.

    For «Сетевой город» ``region`` names an allow-listed regional server and
    ``school`` its id and name; for Petersburg both are ``None``. A class whose
    stored region has since been dropped from the allow-list resolves to *no*
    binding, so a diary that can no longer be reached stops being offered.
    """

    provider: DiaryProvider
    region: str | None = None
    school_id: int | None = None
    school_name: str | None = None


def binding(school_class: SchoolClass) -> Binding | None:
    """What ``school_class`` is bound to, or ``None`` if nothing usable.

    Reads only the class columns and the region allow-list — no aiogram, no
    HTTP — so it is safe on the request path and in the web form.
    """
    provider = provider_for(school_class.diary_provider)
    if provider is None:
        return None
    if school_class.diary_provider == NETSCHOOL:
        from app.providers.netschool import regions

        region = regions.get(school_class.diary_region)
        # A class bound to a region since removed from the allow-list, or that
        # never finished the school step, is not a usable binding.
        if region is None or not region.password or school_class.diary_school_id is None:
            return None
        return Binding(
            provider=provider,
            region=region.key,
            school_id=school_class.diary_school_id,
            school_name=school_class.diary_school_name,
        )
    return Binding(provider=provider)
