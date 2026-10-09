"""One assignment per subject per day, and the rule that makes that true.

Both shells already said this — `api/edit.py:homework_put`'s docstring opens
with «Upsert by (date, subject)» and the bot's flow does the same thing by hand
— but neither the schema nor the code kept the promise. `homework` carries an
index on `(class_id, due_date)` and no unique constraint, and both writers were
read-then-write, so two people saving «Алгебра» for Friday at the same moment
made two rows. Nothing complains: the evening digest simply lists the subject
twice, and whichever row a phone ticks off leaves the other one unticked.

The implementation is here rather than twice over because that is what
`services/` is for: two thin shells over one rule, so they cannot drift.

The savepoint is the same shape `subjects._adopt` uses, and for the same
reason. It also means this code is correct **before and after** the unique
constraint exists: without it the `IntegrityError` branch is simply never
taken. That is deliberate — see `migrations/versions/0013`, which explains why
the constraint has to land after this code rather than before it.

What v1's ``PUT /homework`` and ``DELETE /homework/{id}`` held in their router
is here too, because v2's ``HomeworkService`` writes the same assignments: the
line in the journal and the notice the class is told, in v1's words
(:func:`put`, :func:`delete`). Nothing here commits — the caller commits an
assignment together with its line, and only then tells the class — and nothing
here imports ``services/tasks.py``, which imports this module for the
homework's lookup.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date as Date

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.models import Homework, SchoolClass
from app.services import audit, clock, notify, subjects


async def _find(
    session: AsyncSession, class_id: int, due_date: Date, subject_name: str
) -> Homework | None:
    return await session.scalar(
        select(Homework).where(
            Homework.class_id == class_id,
            Homework.due_date == due_date,
            Homework.subject_name == subject_name,
        )
    )


async def homework_of(session: AsyncSession, class_id: int, homework_id: int) -> Homework | None:
    """This class's homework ``homework_id``, or ``None``: an id of another
    class's homework finds nothing, as every lookup by id here does. v1's
    ``/homework/{id}/done`` and v2's homework ticks read it, and v2's
    ``HomeworkService`` will."""
    return await session.scalar(
        select(Homework).where(Homework.id == homework_id, Homework.class_id == class_id)
    )


async def upsert(
    session: AsyncSession,
    class_id: int,
    due_date: Date,
    subject: str,
    text: str,
    actor: int,
    attachment_url: str | None = None,
) -> tuple[Homework, bool]:
    """Save the assignment, and say whether it is new.

    The subject goes through the dictionary first: `app/schedule.py` looks an
    assignment up against the lesson by exact name, so «алгебра» typed on a
    phone has to become the «Алгебра» the class already uses — otherwise it
    founds a second assignment beside the first instead of replacing it, which
    is the same duplicate by another route.

    Nothing is committed. The caller commits it together with its audit line,
    so an assignment and the record of who set it land as one fact.
    """
    subject_name = await subjects.spelling(session, class_id, subject)

    existing = await _find(session, class_id, due_date, subject_name)
    if existing is None:
        fresh = Homework(
            class_id=class_id,
            due_date=due_date,
            subject_name=subject_name,
            text=text,
            attachment_url=attachment_url,
            created_by=actor,
        )
        try:
            async with session.begin_nested():
                session.add(fresh)
                await session.flush()
        except IntegrityError:
            # Somebody else wrote the same (day, subject) between the read
            # above and this insert. Their row is the one that exists, so this
            # call becomes the update it would have been a millisecond later.
            existing = await _find(session, class_id, due_date, subject_name)
            if existing is None:  # pragma: no cover - the constraint said it is there
                raise
        else:
            return fresh, True

    existing.text = text
    existing.created_by = actor
    if attachment_url is not None:
        existing.attachment_url = attachment_url
    return existing, False


@dataclass(frozen=True)
class Saved:
    """An assignment written, whether it is new, and the notice the class is
    told of it."""

    homework: Homework
    created: bool
    notice: str


async def _announced(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    row: Homework,
    *,
    created: bool,
) -> Saved:
    """Stage the assignment's line in the journal and word its notice, as v1's
    ``PUT`` did: «добавлено» for a new one and «обновлено» for one changed, on
    the date as people read it from the class's today. The text is cut before
    it is escaped (``notify.shorten``)."""
    verb = "добавлено" if created else "обновлено"
    action = "homework.add" if created else "homework.update"
    when = wording.human_date(row.due_date, clock.today(school_class))
    await audit.record(
        session, school_class.id, actor, action, f"ДЗ {verb}: {row.subject_name}, {when}"
    )
    notice = wording.homework_saved_notice(verb, row.subject_name, when, notify.shorten(row.text))
    return Saved(row, created, notice)


async def put(
    session: AsyncSession,
    school_class: SchoolClass,
    actor: int | None,
    due_date: Date,
    subject: str,
    text: str,
    *,
    attachment_url: str | None = None,
) -> Saved:
    """v1's ``PUT /homework``: :func:`upsert` by (date, subject), then its line
    in the journal and its notice. Nothing is committed: the caller commits
    the assignment with its line, and only then tells the class."""
    row, created = await upsert(
        session, school_class.id, due_date, subject, text, actor, attachment_url=attachment_url
    )
    return await _announced(session, school_class, actor, row, created=created)


async def delete(
    session: AsyncSession, school_class: SchoolClass, actor: int | None, row: Homework
) -> str:
    """Delete an assignment and stage its line in the journal; answer the
    notice the class is told. v1's ``DELETE /homework/{id}``, and v2's
    ``DeleteHomework``. Nothing is committed."""
    when = wording.human_date(row.due_date, clock.today(school_class))
    subject = row.subject_name
    await audit.record(
        session, school_class.id, actor, "homework.delete", f"ДЗ удалено: {subject}, {when}"
    )
    await session.delete(row)
    return wording.homework_deleted_notice(subject, when)
