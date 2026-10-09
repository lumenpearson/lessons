"""Substitutions: one lesson on one date replaced or cancelled.

v1's ``PUT /overrides`` held these rules in its router, and the bot's
«🔄 Замены» asked two of them again in its own handler. v2's
``SubstitutionService`` writes the same rows, so the rules live here, and the
three shells call them (``docs/specs/2026-10-05-server-v2-design.md``,
decision 2). Three questions stand between a substitution and a lesson nobody
will ever see, each answered by ``services/timetable_edit`` and refused with a
fact each shell words for itself:

- **does the day draw lessons at all** (:class:`NoLessonOnDay`): not out of
  season, not on a public holiday, and not on a day marked «выходной» or
  «отгул» by hand. Asked of every write but a delete, a change included: a row
  already sitting on such a date is exactly the one whose change would be
  announced about a lesson nobody can see;
- **does the day ring this number** (:class:`NoBellForLesson`), against the
  day's own bells: asked of a new row only, because a row at a number that no
  longer rings has to stay editable — that is how a class gets out of one;
- **is there a lesson underneath** (:class:`LessonNotOnTimetable`), for a
  cancellation and for a replacement with no subject of its own, asked of the
  weekly template rather than the resolver, which on a change would answer
  about the very row being changed.

A cancellation carries no subject, room or teacher of its own. The line in the
journal and the notice the class is told are v1's (:func:`put`,
:func:`create`, :func:`update`, :func:`delete`); the bot words its own and
calls :func:`upsert`. Nothing here commits.
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date as Date
from typing import Any

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.models import LessonOverride, OverrideAction, SchoolClass
from app.services import audit, clock, subjects, timetable_edit

#: The column each field of a write is kept in.
COLUMNS = {
    "action": "action",
    "subject": "subject_name",
    "room": "room",
    "teacher": "teacher",
    "note": "note",
}


class NoLessonOnDay(ValueError):
    """The day draws no lessons at all. ``why`` is the reason for a client to
    act on (``timetable_edit.NoLessons``), and ``sentence`` the one every shell
    shows a person: built from the class's terms and the calendar, never from
    what was sent."""

    def __init__(self, why: str, sentence: str) -> None:
        super().__init__(why)
        self.why = why
        self.sentence = sentence


class NoBellForLesson(ValueError):
    """The day rings no bell for lesson ``index``, so nothing would draw it."""

    def __init__(self, index: int) -> None:
        super().__init__(index)
        self.index = index


class LessonNotOnTimetable(ValueError):
    """Lesson ``index`` is not on the day's weekly template, and the write
    leans on one: a cancellation (``cancelling``), or a replacement with no
    subject of its own, which would inherit nothing."""

    def __init__(self, index: int, *, cancelling: bool) -> None:
        super().__init__(index)
        self.index = index
        self.cancelling = cancelling


class SubstitutionExists(ValueError):
    """The lesson already has a substitution that day: one per date and
    number. v2's ``CreateSubstitution`` refuses with it where v1's ``PUT``
    changed the one there."""


@dataclass(frozen=True)
class Written:
    """A substitution written, and the notice the class is told of it:
    ``None`` when a change changed nothing, so that nobody is told anything."""

    substitution: LessonOverride
    notice: str | None


async def substitution_on(
    session: AsyncSession, class_id: int, day: Date, index: int
) -> LessonOverride | None:
    """The class's substitution of lesson ``index`` on ``day``, if it has one."""
    return await session.scalar(
        select(LessonOverride).where(
            LessonOverride.class_id == class_id,
            LessonOverride.date == day,
            LessonOverride.index == index,
        )
    )


async def substitution_of(
    session: AsyncSession, class_id: int, substitution_id: int
) -> LessonOverride | None:
    """This class's substitution ``substitution_id``, or ``None``: an id of
    another class's substitution finds nothing, as every lookup by id here
    does."""
    return await session.scalar(
        select(LessonOverride).where(
            LessonOverride.id == substitution_id, LessonOverride.class_id == class_id
        )
    )


async def between(
    session: AsyncSession, class_id: int, start: Date, end: Date
) -> list[LessonOverride]:
    """The class's substitutions from ``start`` to ``end``, both included, by
    date, then lesson number: v2's ``ListSubstitutions``, windowed by
    ``clock.window`` as ``ListHomework`` is. Writes nothing."""
    return list(
        await session.scalars(
            select(LessonOverride)
            .where(
                LessonOverride.class_id == class_id,
                LessonOverride.date >= start,
                LessonOverride.date <= end,
            )
            .order_by(LessonOverride.date, LessonOverride.index, LessonOverride.id)
        )
    )


async def _checked(
    session: AsyncSession,
    class_id: int,
    day: Date,
    index: int,
    row: LessonOverride | None,
    changes: Mapping[str, Any],
    *,
    ring: bool,
) -> dict[str, Any]:
    """The columns ``row`` holds once ``changes`` is applied — or a new row's,
    when ``row`` is ``None`` — after the three questions, the bell's only when
    ``ring``. A cancellation's subject, room and teacher are cleared, and a
    subject sent is stored in the class's spelling: the resolver looks a
    substitution's colour up by exact name, so one sent in the wrong case
    draws grey among coloured lessons.

    @raises NoLessonOnDay, NoBellForLesson, LessonNotOnTimetable
    """
    found = await timetable_edit.no_lessons_on(session, class_id, day)
    if found is not None:
        raise NoLessonOnDay(found.why, found.sentence)
    if ring:
        rung = await timetable_edit.rung_indexes_on(session, class_id, day)
        if not timetable_edit.can_ring(rung, index):
            raise NoBellForLesson(index)

    columns = {
        column: getattr(row, column) if row is not None else None for column in COLUMNS.values()
    }
    columns.update({COLUMNS[name]: value for name, value in changes.items()})
    cancelling = columns["action"] == OverrideAction.CANCEL
    if cancelling:
        columns.update(subject_name=None, room=None, teacher=None)
    elif "subject" in changes and columns["subject_name"]:
        columns["subject_name"] = await subjects.spelling(
            session, class_id, columns["subject_name"]
        )
    # Asked of the template, not of the resolver: on a change the resolver
    # answers about the very row being changed, and a substitution that added
    # «Астрономия» at an empty number would vouch for itself while a second
    # write cleared its subject. The resolver drops a replacement with neither
    # a subject of its own nor one underneath, and strikes through only a
    # lesson the day has.
    if cancelling or not columns["subject_name"]:
        if index not in await timetable_edit.template_indexes_on(session, class_id, day):
            raise LessonNotOnTimetable(index, cancelling=cancelling)
    return columns


def _apply(row: LessonOverride, columns: Mapping[str, Any]) -> bool:
    """Set on ``row`` what differs in ``columns``; answer whether anything did."""
    changed = {column: value for column, value in columns.items() if getattr(row, column) != value}
    for column, value in changed.items():
        setattr(row, column, value)
    return bool(changed)


async def upsert(
    session: AsyncSession, class_id: int, day: Date, index: int, changes: Mapping[str, Any]
) -> tuple[LessonOverride, bool]:
    """Write the substitution of lesson ``index`` on ``day``, whether one
    stands there or not, and say whether it is new: the bot's «🔄 Замены» and
    v1's ``PUT /overrides``.

    ``changes`` maps ``action``, ``subject``, ``room``, ``teacher`` and
    ``note`` to what they become; a field it leaves out keeps what the row
    holds, or none on a new row. A substitution of the same lesson written by
    somebody else between the read and the insert meets the unique constraint
    inside a savepoint, and this write changes that one instead, as it would
    have a moment later. Nothing is committed.

    @raises NoLessonOnDay, NoBellForLesson, LessonNotOnTimetable
    """
    row = await substitution_on(session, class_id, day, index)
    columns = await _checked(session, class_id, day, index, row, changes, ring=row is None)
    if row is None:
        fresh = LessonOverride(class_id=class_id, date=day, index=index, **columns)
        try:
            async with session.begin_nested():
                session.add(fresh)
                await session.flush()
        except IntegrityError:
            row = await substitution_on(session, class_id, day, index)
            if row is None:  # pragma: no cover - the constraint said it is there
                raise
            columns = await _checked(session, class_id, day, index, row, changes, ring=False)
        else:
            return fresh, True
    _apply(row, columns)
    return row, False


async def _announced(
    session: AsyncSession, school_class: SchoolClass, actor: int | None, row: LessonOverride
) -> str:
    """Stage the substitution's line in the journal and word its notice, as
    v1's ``PUT /overrides`` did, on the date as people read it from the
    class's today."""
    when = wording.human_date(row.date, clock.today(school_class))
    if row.action == OverrideAction.CANCEL:
        await audit.record(
            session,
            school_class.id,
            actor,
            "override.cancel",
            f"Урок №{row.index} отменён, {when}",
        )
        return wording.substitution_cancelled_notice(row.index, when, row.note)
    what = row.subject_name or "кабинет/учитель"
    await audit.record(
        session,
        school_class.id,
        actor,
        "override.replace",
        f"Замена: урок №{row.index}, {when} — {what}",
    )
    return wording.substitution_replaced_notice(row.index, when, what, row.room, row.note)


async def put(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    day: Date,
    index: int,
    changes: Mapping[str, Any],
) -> Written:
    """v1's ``PUT /overrides`` with ``replace`` or ``cancel``: :func:`upsert`,
    then the line and the notice, every time, as v1 wrote and told them.
    Nothing is committed.

    @raises NoLessonOnDay, NoBellForLesson, LessonNotOnTimetable
    """
    row, _created = await upsert(session, school_class.id, day, index, changes)
    return Written(row, await _announced(session, school_class, actor, row))


async def create(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    day: Date,
    index: int,
    changes: Mapping[str, Any],
) -> Written:
    """v2's ``CreateSubstitution``: a new substitution, never a second one for
    a lesson on a day, after the three questions, with its line and its
    notice. The same lesson written by somebody else between the read and the
    insert meets the unique constraint inside a savepoint and is refused the
    same way; the caller's transaction goes on. Flushed, so that the caller
    can answer the id; not committed.

    @raises SubstitutionExists, NoLessonOnDay, NoBellForLesson, LessonNotOnTimetable
    """
    if await substitution_on(session, school_class.id, day, index) is not None:
        raise SubstitutionExists()
    columns = await _checked(session, school_class.id, day, index, None, changes, ring=True)
    fresh = LessonOverride(class_id=school_class.id, date=day, index=index, **columns)
    try:
        async with session.begin_nested():
            session.add(fresh)
            await session.flush()
    except IntegrityError:
        raise SubstitutionExists() from None
    return Written(fresh, await _announced(session, school_class, actor, fresh))


async def update(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    row: LessonOverride,
    changes: Mapping[str, Any],
) -> Written:
    """v2's ``UpdateSubstitution``: change what ``changes`` names of ``row`` —
    its date and number are the row — after two of the three questions:
    whether the day draws lessons and whether a lesson is underneath. Not the
    bell, so that a row at a number that no longer rings stays editable. A
    change that leaves the row as it was writes nothing, its line included,
    and its notice is ``None``. Nothing is committed.

    @raises NoLessonOnDay, LessonNotOnTimetable
    """
    columns = await _checked(
        session, school_class.id, row.date, row.index, row, changes, ring=False
    )
    if not _apply(row, columns):
        return Written(row, None)
    return Written(row, await _announced(session, school_class, actor, row))


async def delete(
    session: AsyncSession, school_class: SchoolClass, actor: int | None, row: LessonOverride
) -> str:
    """Delete a substitution and stage its line in the journal; answer the
    notice the class is told: the lesson goes back to the timetable. v1's
    ``PUT /overrides`` with ``clear``, and v2's ``DeleteSubstitution``.
    Nothing is committed."""
    when = wording.human_date(row.date, clock.today(school_class))
    index = row.index
    await audit.record(
        session, school_class.id, actor, "override.clear", f"Замена снята: урок №{index}, {when}"
    )
    await session.delete(row)
    return wording.substitution_cleared_notice(index, when)
