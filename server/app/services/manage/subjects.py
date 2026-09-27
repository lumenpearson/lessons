"""«📚 Предметы» and ``/manage/subjects``: what an admin does to the dictionary.

The dictionary itself - matching names, adopting the timetable's - is
:mod:`app.services.subjects`; the cascade a rename sets off is
:func:`app.services.structure.rename_subject`. This is the part the two shells
used to write out twice: the checks in front of a change and the line it
leaves in the log.
"""

from __future__ import annotations

from datetime import date as Date

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import Subject, TimetableEntry
from app.services import audit, structure
from app.services import subjects as dictionary

#: What an admin may set beside the name: column -> (audit action, the word the
#: log line uses, the word for «taken away»). The colour is «цвет … убран» and
#: the other two «убрано», as the bot always wrote them; the phone's copy of
#: this table said «убрано» for all three.
DETAILS: dict[str, tuple[str, str, str]] = {
    "short_name": ("subject.short_name", "сокращение", "убрано"),
    "teacher": ("subject.teacher", "учитель", "убрано"),
    "color": ("subject.colour", "цвет", "убран"),
}


class SubjectExists(Exception):
    """The name is already an entry of the class, ignoring case.

    ``existing`` is that entry: the bot opens it, the API answers 409.
    """

    def __init__(self, existing: Subject) -> None:
        super().__init__(existing.name)
        self.existing = existing


class HomeworkClash(Exception):
    """A rename would put two assignments on one subject and one day.

    Homework is unique per subject per day, and it may be written under a name
    that is no dictionary entry, so the name check cannot see this. See
    :func:`app.services.structure.homework_clashing`.
    """

    def __init__(self, days: list[Date]) -> None:
        super().__init__(", ".join(day.isoformat() for day in days))
        self.days = days


class SubjectInUse(Exception):
    """The weekly template still teaches the subject; ``lessons`` says how often."""

    def __init__(self, lessons: int) -> None:
        super().__init__(str(lessons))
        self.lessons = lessons


async def listing(session: AsyncSession, class_id: int) -> list[Subject]:
    """The dictionary, alphabetically, after adopting what the timetable uses.

    So the list cannot be empty while the class has a full timetable. The
    adoption writes when something is out of step, and the caller commits it.
    """
    await dictionary.sync_from_timetable(session, class_id)
    return list(
        await session.scalars(
            select(Subject).where(Subject.class_id == class_id).order_by(Subject.name)
        )
    )


async def subject_of(session: AsyncSession, class_id: int, subject_id: int) -> Subject | None:
    """Scoped by the query itself: an id naming another class's subject finds
    nothing, whatever the payload claims."""
    return await session.scalar(
        select(Subject).where(Subject.id == subject_id, Subject.class_id == class_id)
    )


async def collect(session: AsyncSession, class_id: int, actor_id: int | None) -> int:
    """Write down every name the timetable uses that the dictionary lacks.

    «Собрать из расписания» in the bot. It invents nothing, which is why an
    editor may press it. Exact spellings, unlike :func:`listing`'s adoption:
    this is the button for "I have just pasted a day and want to see its
    subjects", and it was written before the adoption folded case.

    @return how many entries it added; nothing is logged when that is none.
    """
    names = list(
        await session.scalars(
            select(TimetableEntry.subject_name)
            .where(TimetableEntry.class_id == class_id)
            .distinct()
        )
    )
    known = {
        subject.name
        for subject in await session.scalars(select(Subject).where(Subject.class_id == class_id))
    }
    created = [name for name in sorted(names) if name and name not in known]
    for name in created:
        session.add(Subject(class_id=class_id, name=name[: dictionary.MAX_NAME]))
    if created:
        await audit.record(
            session,
            class_id,
            actor_id,
            "subject.collect",
            f"собрано предметов из расписания: {len(created)}",
        )
    return len(created)


async def create(
    session: AsyncSession,
    class_id: int,
    actor_id: int | None,
    name: str,
    *,
    short_name: str | None = None,
    teacher: str | None = None,
    color: str | None = None,
) -> Subject:
    """Add an entry. The name is unique within the class, ignoring case - that
    uniqueness is the whole point of the dictionary.

    @raises SubjectExists when the class already has the name.
    """
    existing = await dictionary.clashing(session, class_id, name)
    if existing is not None:
        raise SubjectExists(existing)
    subject = Subject(
        class_id=class_id, name=name, short_name=short_name, teacher=teacher, color=color
    )
    session.add(subject)
    await audit.record(session, class_id, actor_id, "subject.add", f"добавлен предмет «{name}»")
    return subject


async def rename(
    session: AsyncSession, class_id: int, actor_id: int | None, subject: Subject, name: str
) -> int | None:
    """Rename ``subject``, and with it every row that spells its old name.

    The timetable, the homework and the substitutions store the subject as
    text, so all three move in the caller's one transaction.

    @return how many rows moved, or ``None`` when ``name`` is what the subject
        is already called - nothing is written then.
    @raises SubjectExists when another entry has the name: merging two subjects
        is a different operation, and doing it by accident cannot be undone.
    @raises HomeworkClash when both names have homework on the same day.
    """
    if name == subject.name:
        return None
    clash = await dictionary.clashing(session, class_id, name, besides=subject.id)
    if clash is not None:
        raise SubjectExists(clash)
    days = await structure.homework_clashing(session, class_id, subject.name, name)
    if days:
        raise HomeworkClash(days)

    old_name = subject.name
    moved = await structure.rename_subject(session, class_id, subject, name)
    await audit.record(
        session,
        class_id,
        actor_id,
        "subject.rename",
        f"предмет «{old_name}» → «{name}», строк обновлено: {moved}",
    )
    return moved


async def set_detail(
    session: AsyncSession,
    class_id: int,
    actor_id: int | None,
    subject: Subject,
    column: str,
    value: str | None,
) -> None:
    """Set the short name, the teacher or the colour; ``None`` takes it away.

    ``column`` is a key of :data:`DETAILS`, and anything else is a ``KeyError``
    rather than a column written blind.
    """
    action, label, removed = DETAILS[column]
    setattr(subject, column, value)
    await audit.record(
        session,
        class_id,
        actor_id,
        action,
        f"{label} предмета «{subject.name}»: {value or removed}",
    )


async def delete(
    session: AsyncSession, class_id: int, actor_id: int | None, subject: Subject
) -> str:
    """Take an entry out of the dictionary, if nothing teaches it any more.

    Deleting a subject the timetable still uses used to be allowed, and it left
    the lessons alone - but the name is still in the template, so the next read
    adopted it back, stripped of its colour and its teacher, and the admin was
    left believing they had deleted something. So the order is stated instead:
    out of the timetable first, out of the dictionary second.

    @return the name it had, for the shell to say.
    @raises SubjectInUse while the weekly template still teaches it.
    """
    in_use = await dictionary.lessons_using(session, class_id, subject)
    if in_use:
        raise SubjectInUse(in_use)
    name = subject.name
    await session.delete(subject)
    await audit.record(session, class_id, actor_id, "subject.delete", f"удалён предмет «{name}»")
    return name
