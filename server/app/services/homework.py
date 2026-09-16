"""One задание per subject per day, and the rule that makes that true.

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
"""

from __future__ import annotations

from datetime import date as Date

from sqlalchemy import select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Homework
from app.services import subjects


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


async def upsert(
    session: AsyncSession,
    class_id: int,
    due_date: Date,
    subject: str,
    text: str,
    actor: int,
    attachment_url: str | None = None,
) -> tuple[Homework, bool]:
    """Save the задание, and say whether it is new.

    The subject goes through the dictionary first: `app/schedule.py` looks a
    задание up against the lesson by exact name, so «алгебра» typed on a phone
    has to become the «Алгебра» the class already uses — otherwise it founds a
    second задание beside the first instead of replacing it, which is the same
    duplicate by another route.

    Nothing is committed. The caller commits it together with its audit line,
    so a задание and the record of who set it land as one fact.
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
