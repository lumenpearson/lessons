"""«🏖 Особые дни»: the dates a class marks as not an ordinary school day.

A :class:`DayOverride` is how the class says "this date is not a normal school
day". «Обычный день» is the absence of a row, not a kind of row, so choosing it
deletes the mark instead of storing ``DayKind.NORMAL`` - otherwise the resolver
would have two ways to spell the same thing.

Three shells mark a day: the bot's «⚙️ Класс», a phone through v1's
``PUT /api/v1/days``, and v2's ``UpdateDay``. They mark it through one write,
:func:`put_day`, which holds the rules v1's router held — a schedule the day
names is this class's and rings something, and a shortened day names one — as
facts each shell words for itself (``docs/specs/2026-10-05-server-v2-design.md``,
decision 2). The bot used to write through a near-duplicate that asked none of
them. What still differs between the shells is a step of a screen, not a rule:
the bot offers the class default as its picker's starting value and writes its
own line in the journal; the phone's two versions write v1's line and tell the
class in v1's words (:func:`set_day`, :func:`update_day`).

Nothing here commits: the caller commits a mark together with its line, and
only then tells the class.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date as Date
from datetime import timedelta
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.models import BellSchedule, DayKind, DayOverride, SchoolClass
from app.services import audit, clock
from app.services.manage import bells

#: The fields of a mark :func:`put_day` writes.
FIELDS = ("kind", "note", "bell_schedule_id")


class ScheduleNotInClass(ValueError):
    """``bell_schedule_id`` names no bell schedule of this class: another
    class's, or one that does not exist. A day pointed at another class's
    bells would ring times this class never set."""


class ShortenedNeedsSchedule(ValueError):
    """A shortened day that names no bell schedule.

    «Сокращённые уроки» is a claim about the times, and the times come from a
    bell schedule. Without one the resolver falls back to the class default,
    so the day announces shortened lessons and then draws the normal ones —
    which is worse than not marking it at all, because somebody reads the
    label and packs for a short day.
    """


@dataclass(frozen=True)
class DayPut:
    """What :func:`put_day` did: the mark the day carries afterwards, ``None``
    for an ordinary day; whether one stood before; and whether anything was
    written."""

    mark: DayOverride | None
    had_mark: bool
    changed: bool


@dataclass(frozen=True)
class DayWritten:
    """A day marked or unmarked, and the notice the class is told of it:
    ``None`` when there is nothing to tell."""

    mark: DayOverride | None
    notice: str | None


async def upcoming(
    session: AsyncSession, class_id: int, today: Date, only: DayKind | None = None
) -> list[DayOverride]:
    """The marks from ``today`` on, by date; narrowed to one kind if asked."""
    query = (
        select(DayOverride)
        .where(DayOverride.class_id == class_id, DayOverride.date >= today)
        .order_by(DayOverride.date)
    )
    if only is not None:
        query = query.where(DayOverride.kind == only)
    return list(await session.scalars(query))


async def schedule_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Bell schedule id -> name, for the marks that point at one."""
    return {
        schedule.id: schedule.name
        for schedule in await session.scalars(
            select(BellSchedule).where(BellSchedule.class_id == class_id)
        )
    }


async def mark_on(session: AsyncSession, class_id: int, day: Date) -> DayOverride | None:
    """The class's mark on ``day``, if it has one."""
    return await session.scalar(
        select(DayOverride).where(DayOverride.class_id == class_id, DayOverride.date == day)
    )


async def put_day(
    session: AsyncSession, school_class: SchoolClass, day: Date, changes: Mapping[str, Any]
) -> DayPut:
    """Mark ``day``, change its mark, or take it off: the one write of a day's mark.

    ``changes`` maps ``kind``, ``note`` and ``bell_schedule_id`` to what they
    become; a field it leaves out keeps what the mark holds, or none on a new
    mark. ``kind`` ``NORMAL`` takes the mark off, and so does a new mark with
    no kind at all: there is nothing to mark. A schedule it names must be this
    class's and ring something, and a day whose kind or schedule it changes may
    not end up shortened with no schedule; all three are asked before anything
    is written, so a refusal leaves the day as it was. A schedule is stored on
    whatever kind it is sent with, as v1 stored it: the resolver rings it on
    any day that names one.

    A mark written by somebody else between the read and the insert meets the
    unique constraint inside a savepoint, and this write changes that mark
    instead, as it would have a moment later. Nothing is committed.

    @raises ScheduleNotInClass, bells.ScheduleEmpty, ShortenedNeedsSchedule
    """
    schedule_id = changes.get("bell_schedule_id")
    if schedule_id is not None:
        schedule = await bells.schedule_of(session, school_class.id, schedule_id)
        if schedule is None:
            raise ScheduleNotInClass()
        # A schedule may be created empty and filled in later, and a day
        # pointed at an empty one draws no lessons at all under a card that
        # says «сокращённые уроки»; a substitution for it would then be held
        # to the class default's bells by `timetable_edit.rung_indexes_on`.
        if not await bells.rings_anything(session, schedule):
            raise bells.ScheduleEmpty(schedule.name)
    current = await mark_on(session, school_class.id, day)
    kind = changes.get("kind", current.kind if current is not None else None)
    rings = changes.get(
        "bell_schedule_id", current.bell_schedule_id if current is not None else None
    )
    touches_bells = bool(changes.keys() & {"kind", "bell_schedule_id"})
    if touches_bells and kind == DayKind.SHORTENED and rings is None:
        raise ShortenedNeedsSchedule()

    if kind is None or kind == DayKind.NORMAL:
        if current is None:
            return DayPut(None, had_mark=False, changed=False)
        await session.delete(current)
        return DayPut(None, had_mark=True, changed=True)

    wanted = {name: changes[name] for name in FIELDS if name in changes}
    if current is None:
        fresh = DayOverride(class_id=school_class.id, date=day, **wanted)
        try:
            async with session.begin_nested():
                session.add(fresh)
                await session.flush()
        except IntegrityError:
            # Somebody else marked the same date between the read above and
            # this insert. Their mark is the one that exists, so this write
            # becomes the change it would have been a moment later.
            current = await mark_on(session, school_class.id, day)
            if current is None:  # pragma: no cover - the constraint said it is there
                raise
        else:
            return DayPut(fresh, had_mark=False, changed=True)
    changed = {name: value for name, value in wanted.items() if getattr(current, name) != value}
    for name, value in changed.items():
        setattr(current, name, value)
    return DayPut(current, had_mark=True, changed=bool(changed))


async def mark_period(
    session: AsyncSession, school_class: SchoolClass, first: Date, days: int, kind: DayKind
) -> None:
    """Mark ``days`` consecutive dates from ``first`` as ``kind``, through
    :func:`put_day`: each keeps its note, and none keeps a bell schedule,
    which only a shortened day means anything by and a period never is."""
    for offset in range(days):
        await put_day(
            session,
            school_class,
            first + timedelta(days=offset),
            {"kind": kind, "bell_schedule_id": None},
        )


async def _announced(
    session: AsyncSession, school_class: SchoolClass, actor: int | None, day: Date, put: DayPut
) -> str | None:
    """Stage the day's line in the journal and word its notice, as v1's
    ``PUT /days`` did: «day.set» with the kind for a mark, «day.clear» for one
    taken off, and nothing for a day that had none to take off."""
    when = wording.human_date(day, clock.today(school_class))
    if put.mark is None:
        if not put.had_mark:
            return None
        await audit.record(
            session, school_class.id, actor, "day.clear", f"День снова обычный: {when}"
        )
        return wording.day_cleared_notice(when)
    label = wording.DAY_SET_LABELS[put.mark.kind]
    await audit.record(session, school_class.id, actor, "day.set", f"{when}: {label}")
    return wording.day_set_notice(when, label, put.mark.note)


async def set_day(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    day: Date,
    *,
    kind: DayKind,
    note: str | None,
    bell_schedule_id: int | None,
) -> DayWritten:
    """v1's ``PUT /days``: every field as sent, then the line and the notice —
    whenever a mark is set, sent twice or not, as v1 told the class, and when
    one is taken off only if there was one. Nothing is committed.

    @raises ScheduleNotInClass, bells.ScheduleEmpty, ShortenedNeedsSchedule
    """
    put = await put_day(
        session,
        school_class,
        day,
        {"kind": kind, "note": note, "bell_schedule_id": bell_schedule_id},
    )
    return DayWritten(put.mark, await _announced(session, school_class, actor, day, put))


async def update_day(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    day: Date,
    changes: Mapping[str, Any],
) -> DayWritten:
    """v2's ``UpdateDay``: :func:`put_day` with the fields the mask names, then
    the line and the notice — only when something changed, so that a retried
    update tells nobody twice. Nothing is committed.

    @raises ScheduleNotInClass, bells.ScheduleEmpty, ShortenedNeedsSchedule
    """
    put = await put_day(session, school_class, day, changes)
    notice = await _announced(session, school_class, actor, day, put) if put.changed else None
    return DayWritten(put.mark, notice)
