"""The subject dictionary, kept in step with the timetable.

Two lists used to describe the same thing and never spoke: «📚 Предметы» and
the names in the weekly template. A class could — and one did — hold 35 lessons
under 20 distinct names with an empty dictionary. `TimetableEntry.subject_id`
existed in the schema the whole time and was written by nothing at all.

That is not merely untidy. The resolver looks a lesson's colour up **by name**
in the dictionary (`app/schedule.py`), so an empty dictionary means a
colourless timetable, and a subject renamed in one place and not the other
means a lesson that silently loses its colour. Two spellings of one subject —
«Алгебра» and «алгебра» — are two rows, two colours, and two entries in a
picker that should have offered one.

So: every write that puts a subject name into the timetable comes through
here, which finds the dictionary entry or creates it and hands back the row to
link. Matching is case-insensitive and whitespace-collapsed, and the *stored*
spelling is the dictionary's — a paste that shouts «АЛГЕБРА» at a class that
already has «Алгебра» joins the existing subject rather than founding a second
one.

Nothing here commits. The caller's transaction owns the change, like the rest
of ``app/services/``.
"""

from __future__ import annotations

from sqlalchemy import func, select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Subject, TimetableEntry

#: Both columns are ``String(120)``; the shorter of the two is the limit.
MAX_NAME = 120


def normalise(raw: str | None) -> str:
    """The name as it will be stored and compared: trimmed, single-spaced."""
    return " ".join((raw or "").split())[:MAX_NAME]


def _fold(name: str) -> str:
    """The comparison key. Case only — nothing clever.

    Deliberately not stripping punctuation or abbreviating: «Алг.» and
    «Алгебра» are two names a class may genuinely use for two things, and a
    matcher that merged them would be guessing about somebody's timetable.
    """
    return name.casefold()


async def find(session: AsyncSession, class_id: int, name: str) -> Subject | None:
    """The dictionary entry for ``name``, ignoring case. ``None`` if new."""
    cleaned = normalise(name)
    if not cleaned:
        return None
    return await session.scalar(
        select(Subject).where(
            Subject.class_id == class_id,
            func.lower(Subject.name) == _fold(cleaned),
        )
    )


async def ensure(session: AsyncSession, class_id: int, name: str) -> Subject | None:
    """The dictionary entry for ``name``, created if the class has none.

    Returns ``None`` only for an empty name, which is not a subject and must
    not become a blank row in the picker.

    Flushes, because the caller needs the new row's id to link a lesson to it
    inside the same transaction.
    """
    cleaned = normalise(name)
    if not cleaned:
        return None

    existing = await find(session, class_id, cleaned)
    if existing is not None:
        return existing

    # Nothing in the dictionary yet — but the timetable may already spell this
    # subject, and its spelling is the class's. Without this, typing «физика»
    # into a class whose template says «Физика» would found the dictionary
    # entry in lower case and drag every existing row down to it: one typist
    # quietly renaming a subject for everybody. Only on the create path, which
    # is once per subject per class.
    in_use = await session.scalar(
        select(TimetableEntry.subject_name)
        .where(
            TimetableEntry.class_id == class_id,
            func.lower(TimetableEntry.subject_name) == _fold(cleaned),
        )
        .limit(1)
    )
    subject = Subject(class_id=class_id, name=normalise(in_use) or cleaned)
    session.add(subject)
    await session.flush()
    return subject


async def canonical(session: AsyncSession, class_id: int, name: str) -> tuple[str, int | None]:
    """The name to store on a lesson, and the dictionary row to point at.

    The returned name is the dictionary's spelling, not the caller's: that is
    what makes «АЛГЕБРА» in a paste land on the class's existing «Алгебра»
    instead of beside it.
    """
    subject = await ensure(session, class_id, name)
    if subject is None:
        return normalise(name), None
    return subject.name, subject.id


async def sync_from_timetable(session: AsyncSession, class_id: int) -> int:
    """Adopt every name the timetable already uses, and link the rows to it.

    Idempotent, and free when there is nothing to do — which is the normal
    case, because every write path links as it goes. It exists for the classes
    that predate the link, and those are exactly the ones with the emptiest
    dictionaries: a class of 35 lessons should not need somebody to press
    «собрать из расписания» before its timetable has colours.

    Called from reads, so the cheap path has to be genuinely cheap: two selects
    and a count, and not one statement more unless something is actually out of
    step.

    @return how many dictionary entries were created.
    """
    names = [
        normalise(name)
        for name in await session.scalars(
            select(TimetableEntry.subject_name)
            .where(TimetableEntry.class_id == class_id)
            .distinct()
        )
        if normalise(name)
    ]
    if not names:
        return 0

    known = {
        _fold(subject.name): subject
        for subject in await session.scalars(
            select(Subject).where(Subject.class_id == class_id)
        )
    }
    missing = [name for name in sorted(set(names)) if _fold(name) not in known]
    unlinked = await session.scalar(
        select(func.count())
        .select_from(TimetableEntry)
        .where(TimetableEntry.class_id == class_id, TimetableEntry.subject_id.is_(None))
    )
    if not missing and not unlinked:
        return 0

    for name in missing:
        subject = Subject(class_id=class_id, name=name)
        session.add(subject)
        await session.flush()
        known[_fold(name)] = subject

    for name in sorted(set(names)):
        subject = known[_fold(name)]
        # Only the rows that are actually wrong. Settling the spelling at the
        # same time matters: two cases of one name in the template draw as two
        # subjects everywhere downstream, colours included.
        await session.execute(
            sa_update(TimetableEntry)
            .where(
                TimetableEntry.class_id == class_id,
                func.lower(TimetableEntry.subject_name) == _fold(name),
                (TimetableEntry.subject_id.is_(None))
                | (TimetableEntry.subject_id != subject.id)
                | (TimetableEntry.subject_name != subject.name),
            )
            .values(subject_id=subject.id, subject_name=subject.name)
        )
    return len(missing)
