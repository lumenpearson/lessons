"""The class's events — a trip, an exam, a canteen break — written from a phone.

v1's ``PUT /events`` and ``DELETE /events/{id}`` held these rules in their
router, and v2's ``EventService`` writes the same events, so they live here
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2): what an event
stands in for when nobody says, its line in the journal, and the notice the
class is told, in v1's words (``app/wording.py``). The bot's «📅 Событие»
flow keeps its own line and its own notice, worded for a chat.

Nothing here commits: the caller commits an event together with its line, and
only then tells the class.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date as Date
from datetime import time as Time

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.models import DayEvent, EventKind, SchoolClass
from app.services import audit, clock

#: What stands in for the lessons it overlaps when nobody says: a
#: «мероприятие» or a trip does, and a canteen break sits in a break. The
#: bot's flow says the same, and v1's ``covers_lesson`` defaulted so.
COVERS_BY_DEFAULT = frozenset({EventKind.EVENT, EventKind.TRIP})


@dataclass(frozen=True)
class Written:
    """An event written, and the notice the class is told of it."""

    event: DayEvent
    notice: str


def _span(event: DayEvent) -> str:
    return f"{event.starts_at:%H:%M}–{event.ends_at:%H:%M}"


async def event_of(session: AsyncSession, class_id: int, event_id: int) -> DayEvent | None:
    """This class's event ``event_id``, or ``None``: an id of another class's
    event finds nothing, as every lookup by id here does."""
    return await session.scalar(
        select(DayEvent).where(DayEvent.id == event_id, DayEvent.class_id == class_id)
    )


async def between(session: AsyncSession, class_id: int, start: Date, end: Date) -> list[DayEvent]:
    """The class's events from ``start`` to ``end``, both included, by date,
    then the time they start, then id: v2's ``ListEvents``, windowed by
    ``clock.window`` as ``ListHomework`` is. Writes nothing."""
    return list(
        await session.scalars(
            select(DayEvent)
            .where(DayEvent.class_id == class_id, DayEvent.date >= start, DayEvent.date <= end)
            .order_by(DayEvent.date, DayEvent.starts_at, DayEvent.id)
        )
    )


async def create(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    *,
    date: Date,
    starts_at: Time,
    ends_at: Time,
    title: str,
    kind: EventKind,
    location: str | None = None,
    covers_lesson: bool | None = None,
) -> Written:
    """A new event, its line in the journal, and its notice: v1's ``PUT
    /events``, and v2's ``CreateEvent``.

    Always a new row: events have no natural key, and two «Обед» on one day
    are two breaks. ``covers_lesson`` ``None`` is what ``kind`` means
    (:data:`COVERS_BY_DEFAULT`). Flushed, so that the caller can answer the
    id; not committed.
    """
    event = DayEvent(
        class_id=school_class.id,
        date=date,
        starts_at=starts_at,
        ends_at=ends_at,
        title=title,
        kind=kind,
        location=location,
        covers_lesson=covers_lesson if covers_lesson is not None else kind in COVERS_BY_DEFAULT,
    )
    session.add(event)
    when = wording.human_date(date, clock.today(school_class))
    span = _span(event)
    await audit.record(
        session, school_class.id, actor, "event.add", f"Событие: {title}, {when} {span}"
    )
    await session.flush()
    return Written(event, wording.event_notice(title, when, span, location))


async def delete(
    session: AsyncSession, school_class: SchoolClass, actor: int | None, event: DayEvent
) -> str:
    """Delete an event and stage its line in the journal; answer the notice
    the class is told. v1's ``DELETE /events/{id}``, and v2's
    ``DeleteEvent``. Nothing is committed."""
    when = wording.human_date(event.date, clock.today(school_class))
    title = event.title
    await audit.record(
        session, school_class.id, actor, "event.delete", f"Событие удалено: {title}, {when}"
    )
    await session.delete(event)
    return wording.event_cancelled_notice(title, when)
