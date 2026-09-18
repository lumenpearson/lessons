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
from sqlalchemy.exc import IntegrityError
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


async def clashing(
    session: AsyncSession, class_id: int, name: str, *, besides: int | None = None
) -> Subject | None:
    """The entry that would collide with ``name``, ignoring case.

    The uniqueness check, kept here beside the matcher it has to agree with.
    When the two disagree the damage is not a duplicate row: a guard that
    compares exactly lets «ФИЗИКА» be created next to «Физика», and then
    :func:`sync_from_timetable`, which folds case, sees one subject where the
    dictionary has two and rewrites every «Физика» lesson onto whichever row it
    happened to keep — a read quietly renaming a subject and dropping the
    colour of the one it abandoned.

    @param besides a row to ignore, for a rename checking its own new name.
    """
    cleaned = normalise(name)
    if not cleaned:
        return None
    query = select(Subject).where(
        Subject.class_id == class_id,
        func.lower(Subject.name) == _fold(cleaned),
    )
    if besides is not None:
        query = query.where(Subject.id != besides)
    return await session.scalar(query)


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
    subject, _created = await _adopt(session, class_id, normalise(in_use) or cleaned)
    return subject


async def _adopt(
    session: AsyncSession, class_id: int, name: str
) -> tuple[Subject | None, bool]:
    """Insert one dictionary entry, conceding to whoever got there first.

    @return the row and whether *this* call is the one that made it. The second
        half is not bookkeeping: «🔄 Собрать из расписания» reports the number
        back, and a concession is a row that was already there.

    In a savepoint rather than bare, because this is reached from the read
    path: a class whose dictionary was never filled has every phone in it poll
    the bundle on the same timer, so two requests both finding the name absent
    is the ordinary case rather than a rare one. Without the savepoint the
    loser of ``uq_subject_name`` takes the whole request down with it, and a
    500 with no Russian sentence in it is what the class sees on exactly the
    first read this healing exists for.

    Nothing is committed — the savepoint is released into the caller's
    transaction, which still owns the decision to keep it.
    """
    subject = Subject(class_id=class_id, name=name)
    try:
        async with session.begin_nested():
            session.add(subject)
            await session.flush()
    except IntegrityError:
        return await find(session, class_id, name), False
    return subject, True


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


async def spelling(session: AsyncSession, class_id: int, name: str) -> str:
    """The class's own spelling of ``name``, or ``name`` if it has none.

    :func:`canonical` for the things that carry a subject's *name* without
    belonging to the weekly template — домашнее задание and a замена. They have
    no ``subject_id`` to link, so agreeing on the spelling is the whole of what
    can be agreed, and it is enough for the three things that were wrong:

    * homework upserts on ``(date, subject_name)``, so «алгебра» typed on a
      phone founded a second задание beside the «Алгебра» already there, and
      both went out in the evening digest;
    * :func:`app.services.structure.rename_subject` moves homework by exact old
      name, so the stray spelling survived a rename and then named a subject
      the class no longer had;
    * ``app/schedule.py`` looks a замена's colour up by exact name, so one
      typed in the wrong case drew grey among coloured lessons.

    Unlike :func:`canonical` this never founds a dictionary entry. Homework is
    set for what the class already teaches; a picker that grew a subject
    because somebody wrote down an assignment would be the dictionary learning
    from the wrong half of the app.
    """
    subject = await find(session, class_id, name)
    return subject.name if subject is not None else normalise(name)


async def lessons_using(session: AsyncSession, class_id: int, subject: Subject) -> int:
    """How many timetable rows this dictionary entry speaks for.

    By link *or* by spelling: a class healed by :func:`sync_from_timetable` is
    linked, one that predates the link is not, and both count. Without this the
    caller cannot tell a subject that is merely listed from one the weekly
    template is built out of.
    """
    return await session.scalar(
        select(func.count())
        .select_from(TimetableEntry)
        .where(
            TimetableEntry.class_id == class_id,
            (TimetableEntry.subject_id == subject.id)
            | (func.lower(TimetableEntry.subject_name) == _fold(subject.name)),
        )
    ) or 0


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

    # Oldest wins, and the order is stated rather than left to the database:
    # a class that acquired two spellings of one subject before the uniqueness
    # check folded case (see `clashing`) must not have the survivor chosen
    # afresh on every read, or two phones polling a minute apart drag its
    # lessons back and forth between the two rows.
    known: dict[str, Subject] = {}
    for subject in await session.scalars(
        select(Subject).where(Subject.class_id == class_id).order_by(Subject.id)
    ):
        known.setdefault(_fold(subject.name), subject)
    missing = [name for name in sorted(set(names)) if _fold(name) not in known]
    unlinked = await session.scalar(
        select(func.count())
        .select_from(TimetableEntry)
        .where(TimetableEntry.class_id == class_id, TimetableEntry.subject_id.is_(None))
    )
    if not missing and not unlinked:
        return 0

    created = 0
    for name in missing:
        subject, adopted = await _adopt(session, class_id, name)
        if subject is None:
            continue
        # Only what this call actually created. The savepoint above concedes to
        # a racing insert and hands back the row that won, and counting that as
        # ours told an admin «добавлено 4» about a dictionary that gained one.
        created += adopted
        known[_fold(name)] = subject

    for name in sorted(set(names)):
        subject = known.get(_fold(name))
        if subject is None:
            continue
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
    return created
