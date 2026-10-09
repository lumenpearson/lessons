"""Which diaries this deployment serves, as one table, and what a class is bound to.

:data:`TABLE` is the diary as data (``docs/specs/2026-10-05-server-v2-design.md``,
decision 12): one :class:`Row` per provider — its key, where its implementation
lives, what a class's binding to it needs, the ways in a phone may draw a form
for, the data it has, how a child's corrections are scoped, whether the tick
keeps its sessions open, and how what a phone hands over is sealed. Every
question asked of a provider key reads its row, so a provider is added by a row
and its module, and no ``if`` on a key has to learn about it.

A row names its implementation, and a regional provider its allow-list, as a
module path imported on first use — so neither Petersburg's client nor «Сетевой
город»'s lands on the cold-start path of a request that does not use it, the
same care `main.py` takes.

`binding` is the one place that reads a class's diary binding. `_bound` in the
bot, the main menu, the class card and the web form all call it, so that
"is this class bound, and to what" has a single answer and the web form never
imports aiogram to get it.
"""

from __future__ import annotations

import importlib
from dataclasses import dataclass
from enum import StrEnum
from types import ModuleType
from typing import TYPE_CHECKING
from urllib.parse import urlsplit

from app.providers.diary.base import DiaryProvider

if TYPE_CHECKING:
    from app.models import SchoolClass

PETERSBURG = "petersburg"
NETSCHOOL = "netschool"


class Needs(StrEnum):
    """What a class's binding to a provider needs besides the provider's key."""

    #: Nothing: the provider is one server, as Petersburg's city diary is.
    NOTHING = "nothing"
    #: A region of the provider's allow-list that takes a password, and the
    #: diary's own id of the school in it, as «Сетевой город» needs.
    REGION_AND_SCHOOL = "region_and_school"


class Feature(StrEnum):
    """What a provider has: v2's ``DiaryFeature``, by its value's name without
    the prefix, which ``test_diary_registry.py`` holds level with the proto.

    A provider declares a feature when its connection reads it from the diary,
    and only then: one that answers with a constant empty list and asks the
    diary nothing never implemented it, while an empty answer from the diary is
    an answer (the 3b plan, Ruling 104)."""

    SCHEDULE = "schedule"
    HOMEWORK = "homework"
    MARKS = "marks"
    PERIODS = "periods"
    SUBJECTS = "subjects"
    TEACHERS = "teachers"
    ATTENDANCE = "attendance"
    TURNSTILE = "turnstile"
    MEAL_ACCOUNT = "meal_account"
    FINAL_MARKS = "final_marks"


class SignIn(StrEnum):
    """How a phone gets into a provider's diary, so it draws the right form: v2's
    ``SignInMethod``, by its value's name without the prefix."""

    #: A login and a password, typed into the phone's own form; the phone signs
    #: in with the diary itself and hands the session over.
    PASSWORD = "password"
    #: A session the phone opened elsewhere, such as the diary's own page.
    SESSION_ADOPT = "session_adopt"


class Scope(StrEnum):
    """What a child's corrections are filed under besides the child's id
    (`services/diary_corrections.child_scope`)."""

    #: The provider: one server numbers every pupil.
    PROVIDER = "provider"
    #: The regional server the session's region names: each numbers its own.
    REGIONAL_SERVER = "regional_server"


@dataclass(frozen=True)
class Row:
    """One provider, as every question about its key is answered."""

    key: str
    #: ``"module:Class"``, imported on the first :meth:`provider`.
    implementation: str
    needs: Needs
    #: The module holding a regional provider's allow-list (``get``, ``listed``
    #: and regions with ``key``, ``origin`` and ``password``), imported on first
    #: use; ``None`` for a provider that is one server.
    regions: str | None
    sign_in: tuple[SignIn, ...]
    features: frozenset[Feature]
    scope: Scope
    #: Whether the cron tick pings the provider's live sessions
    #: (`services/diary_keepalive`): «Сетевой город»'s idle out in minutes.
    kept_alive: bool
    #: The one field of what a phone hands over that is the whole session,
    #: sealed as it is (Petersburg's ``token``); ``None`` when the session is the
    #: JSON of everything handed, as «Сетевой город»'s ``at``, cookies, ``ver``
    #: and ``time_out`` are.
    bare_field: str | None

    def provider(self) -> DiaryProvider:
        """The implementation, imported on the first call for it."""
        module, _, name = self.implementation.partition(":")
        return getattr(importlib.import_module(module), name)()

    def _allow_list(self) -> ModuleType | None:
        return importlib.import_module(self.regions) if self.regions else None

    def served_region(self, key: str | None) -> str | None:
        """``key``, when it names a region of the allow-list that takes a
        password; ``None`` otherwise, and always for a provider that is one
        server. Asked before any upstream call, so a region this server does not
        serve never receives one, whichever door it came through."""
        allow = self._allow_list()
        region = allow.get(key) if allow is not None else None
        return region.key if region is not None and region.password else None

    def listed_regions(self) -> list[str]:
        """The allow-list keys a phone may sign in to with a password, in the
        bot's order; none for a provider that is one server."""
        allow = self._allow_list()
        return [region.key for region in allow.listed()] if allow is not None else []

    def server_of(self, region: str | None) -> str | None:
        """The host of ``region``'s server, lower case, or ``None`` when the
        allow-list does not hold the region. Every region, a password-less one
        included: its sessions' corrections are still filed under it."""
        allow = self._allow_list()
        known = allow.get(region) if allow is not None else None
        return urlsplit(known.origin).netloc.lower() if known is not None else None


TABLE: tuple[Row, ...] = (
    Row(
        key=PETERSBURG,
        implementation="app.providers.petersburg.provider:PetersburgProvider",
        needs=Needs.NOTHING,
        regions=None,
        sign_in=(SignIn.PASSWORD,),
        features=frozenset(
            {
                Feature.SCHEDULE,
                Feature.HOMEWORK,
                Feature.MARKS,
                Feature.PERIODS,
                Feature.SUBJECTS,
                Feature.TEACHERS,
                Feature.TURNSTILE,
            }
        ),
        scope=Scope.PROVIDER,
        kept_alive=False,
        bare_field="token",
    ),
    Row(
        key=NETSCHOOL,
        implementation="app.providers.netschool.provider:NetSchoolProvider",
        needs=Needs.REGION_AND_SCHOOL,
        regions="app.providers.netschool.regions",
        sign_in=(SignIn.PASSWORD,),
        # Its connection answers subjects, teachers and the turnstile with a
        # constant empty list and asks the diary nothing (`NetSchoolConnection`):
        # never implemented, so never declared.
        features=frozenset({Feature.SCHEDULE, Feature.HOMEWORK, Feature.MARKS, Feature.PERIODS}),
        scope=Scope.REGIONAL_SERVER,
        kept_alive=True,
        bare_field=None,
    ),
)

#: Every key, in the table's order.
KEYS = tuple(row.key for row in TABLE)

_BY_KEY = {row.key: row for row in TABLE}


def row_for(key: str | None) -> Row | None:
    """The row for a key, or ``None`` for an unknown or absent one.

    A ``None`` key on a session row means the row pre-dates the ``provider``
    column, which is Petersburg — but the caller decides that, not this
    function, which answers only about the key it was given."""
    return _BY_KEY.get(key) if key is not None else None


def provider_for(key: str | None) -> DiaryProvider | None:
    """The provider for a key, or ``None`` for an unknown or absent one."""
    row = row_for(key)
    return row.provider() if row is not None else None


def kept_alive() -> tuple[str, ...]:
    """The keys whose live sessions the tick pings."""
    return tuple(row.key for row in TABLE if row.kept_alive)


@dataclass(frozen=True)
class Binding:
    """A class's diary binding, resolved and validated.

    For a provider whose binding needs a region and a school, ``region`` names
    an allow-listed regional server and ``school`` its id and name; otherwise
    both are ``None``. A class whose stored region has since been dropped from
    the allow-list resolves to *no* binding, so a diary that can no longer be
    reached stops being offered.
    """

    provider: DiaryProvider
    region: str | None = None
    school_id: int | None = None
    school_name: str | None = None


def binding(school_class: SchoolClass) -> Binding | None:
    """What ``school_class`` is bound to, or ``None`` if nothing usable.

    Reads only the class columns and the row — its allow-list, when it has one;
    no aiogram, no HTTP — so it is safe on the request path and in the web form.
    """
    row = row_for(school_class.diary_provider)
    if row is None:
        return None
    if row.needs is Needs.NOTHING:
        return Binding(provider=row.provider())
    region = row.served_region(school_class.diary_region)
    # A class bound to a region since removed from the allow-list, or that
    # never finished the school step, is not a usable binding.
    if region is None or school_class.diary_school_id is None:
        return None
    return Binding(
        provider=row.provider(),
        region=region,
        school_id=school_class.diary_school_id,
        school_name=school_class.diary_school_name,
    )
