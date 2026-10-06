"""«📤 Экспорт», «📥 Импорт» and ``/manage/timetable``: the weekly template as text.

The same format in both directions, which is what makes it a backup: a text
saved out of the bot goes back in through the app, and the other way round.
The grammar is :mod:`app.services.timetable_io` and the write is
:func:`app.services.structure.apply_timetable`; this is what the shells did
around them. :func:`import_paste` is what v1's ``POST /timetable/import`` held
in its router, moved here so that v2's ``ImportTimetable`` reads the same
rules (the server-v2 design, decision 2).
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import BellPeriod, BellSchedule, SchoolClass, TimetableEntry
from app.services import audit, structure, timetable_io
from app.services.structure import BellRow, TimetableImport
from app.wording import WEEKDAYS


class PasteEmpty(ValueError):
    """A paste with neither a weekday header nor a «== Звонки ==» block:
    nothing in it says what to replace."""


@dataclass(frozen=True)
class Conflict:
    """One weekday a paste would overwrite: the lessons it has, and the paste's."""

    weekday: int
    existing: int
    incoming: int


@dataclass(frozen=True)
class ImportOutcome:
    """What an import did, or would do, for a shell to answer with.

    ``applied`` is false when nothing was written: a preview, or a paste over
    weekdays that have lessons, sent without ``replace``. ``lessons`` is then
    the paste's own count and ``rejected`` the parser's lines alone, because a
    lesson with no bell to ring it is found only when the paste is applied.
    ``schedule`` is the schedule the paste's bells went into; its rows are
    stale until the caller refreshes them.
    """

    applied: bool
    days: list[int]
    lessons: int
    bells: int
    conflicts: list[Conflict]
    rejected: list[str]
    schedule: BellSchedule | None = None


async def export(session: AsyncSession, school_class: SchoolClass) -> tuple[str, int]:
    """The whole template in the paste format, and how many lessons are in it.

    An empty template is an empty string: there is nothing wrong with a class
    that has not filled one in yet.
    """
    entries = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id)
            .order_by(TimetableEntry.weekday, TimetableEntry.index)
        )
    )
    periods: list[BellPeriod] = []
    if school_class.bell_schedule_id:
        schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is not None:
            periods = list(schedule.periods)
    return timetable_io.export_timetable(entries, periods), len(entries)


async def apply(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    days: dict[int, list],
    bells: list[BellRow],
) -> TimetableImport:
    """Replace exactly the weekdays a paste named, and its bells if it had any.

    Days the paste does not mention are left alone, so importing one day's
    block is a legitimate thing to do, and a day named with nothing under it is
    emptied - that is how a paste says «в четверг уроков нет».

    The log line counts rows, not numbers, in both directions: «8. Алгебра»
    under Monday and under Tuesday is two lessons nobody will see, and so is a
    stored lesson the new bells no longer ring. The bot's copy of this line
    never said the second.
    """
    result = await structure.apply_timetable(session, school_class, days, bells)
    summary = f"импорт расписания: дней {len(days)}, уроков {result.written}"
    if bells:
        summary += f", звонков {len(bells)}"
    if result.dropped:
        summary += f", без звонка пропущено {len(result.dropped)}"
    if result.orphaned:
        summary += f", перестали звонить {len(result.orphaned)}"
    await audit.record(session, school_class.id, actor_id, "timetable.import", summary)
    return result


async def import_paste(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    text: str,
    *,
    replace: bool,
    validate_only: bool = False,
) -> ImportOutcome:
    """Parse a paste and replace exactly the weekdays it names, unless told not to.

    Nothing is written for a preview (``validate_only``), nor for a paste over
    a weekday that already has lessons sent without ``replace``: the answer
    then lists what stands to be overwritten, which is the bot's preview
    before «Применить» and v1's answer to a paste that would win quietly. A
    ``== Звонки ==`` block replaces the default schedule's rows outright, as
    it does in the bot.

    @raises PasteEmpty when the paste names no weekday and brings no bells.
    """
    days, rejected = timetable_io.parse_timetable_block(text)
    bells, _rejected_bells = timetable_io.parse_bells_block(text)
    if not days and not bells:
        raise PasteEmpty()

    existing = await structure.lessons_per_weekday(session, school_class.id, list(days))
    conflicts = [
        Conflict(weekday=weekday, existing=count, incoming=len(days[weekday]))
        for weekday, count in sorted(existing.items())
        if count
    ]
    if validate_only or (conflicts and not replace):
        return ImportOutcome(
            applied=False,
            days=sorted(days),
            lessons=sum(len(rows) for rows in days.values()),
            bells=len(bells),
            conflicts=conflicts,
            rejected=rejected,
        )

    result = await apply(session, school_class, actor_id, days, bells)
    # Reported, not silently dropped: a lesson past the last bell has nowhere
    # to be drawn, and «applied: true, lessons: N» with N short of what was
    # pasted is exactly the answer that hides it. One line per dropped row,
    # named by its weekday: the same number under two weekdays is two lessons
    # gone, and while this counted the distinct numbers it admitted to one.
    rejected = rejected + [
        f"{WEEKDAYS[weekday - 1]}, урок {index}: "
        "нет такого звонка в расписании звонков"
        for weekday, index in result.dropped
    ]
    # The other direction, and the one nothing used to report: these lessons
    # were already stored and the bells this import wrote no longer ring them,
    # so they are still in the database and drawn nowhere.
    rejected = rejected + [
        f"{WEEKDAYS[weekday - 1]}, урок {index}: "
        "больше не звонит — новые звонки короче"
        for weekday, index in result.orphaned
    ]
    return ImportOutcome(
        applied=True,
        days=sorted(days),
        lessons=result.written,
        bells=len(bells),
        conflicts=conflicts,
        rejected=rejected,
        schedule=result.schedule,
    )
