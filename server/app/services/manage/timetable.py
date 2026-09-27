"""«📤 Экспорт», «📥 Импорт» and ``/manage/timetable``: the weekly template as text.

The same format in both directions, which is what makes it a backup: a text
saved out of the bot goes back in through the app, and the other way round.
The grammar is :mod:`app.services.timetable_io` and the write is
:func:`app.services.structure.apply_timetable`; this is what the two shells
did around them.
"""

from __future__ import annotations

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import BellPeriod, BellSchedule, SchoolClass, TimetableEntry
from app.services import audit, structure, timetable_io
from app.services.structure import BellRow, TimetableImport


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
