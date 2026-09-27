"""``/subjects``: the class's subject dictionary, as «📚 Предметы» edits it.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor, editor_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass, Subject
from app.schemas import DeletedOut, ManagedSubjectOut, SubjectIn, SubjectPatch, SubjectSavedOut
from app.services import audit, structure
from app.services import subjects as subjects_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📚 Subjects
# --------------------------------------------------------------------------


def _subject_out(subject: Subject) -> ManagedSubjectOut:
    return ManagedSubjectOut(
        id=subject.id,
        name=subject.name,
        short_name=subject.short_name,
        teacher=subject.teacher,
        color=subject.color,
    )


async def _subject_or_404(
    session: AsyncSession, school_class: SchoolClass, subject_id: int
) -> Subject:
    subject = await session.scalar(
        select(Subject).where(Subject.id == subject_id, Subject.class_id == school_class.id)
    )
    if subject is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown subject")
    return subject


async def _name_taken(
    session: AsyncSession, class_id: int, name: str, *, besides: int | None = None
) -> bool:
    """Ignores case, because everything downstream of it does."""
    return await subjects_service.clashing(session, class_id, name, besides=besides) is not None


@router.get("/subjects", response_model=list[ManagedSubjectOut])
async def subjects_list(
    _: Actor = Depends(editor_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedSubjectOut]:
    """The dictionary with ids, for a screen that edits it. An editor may
    read it - it is the same list ``GET /api/v1/subjects`` gives any device.

    Adopts whatever the timetable already uses on the way, so this list is
    never emptily lying about a class with thirty-five lessons in it. Free once
    the two agree, which after the first read they do.
    """
    # Committed whatever the count says, the way `public.bundle` does it. The
    # number that comes back is how many dictionary entries were *created*, and
    # the function also links the timetable rows to them — so a class whose
    # dictionary was already complete but whose lessons were not yet pointed at
    # it got its UPDATEs run and then dropped when the session closed, on every
    # read, forever. It healed only because `/bundle` commits unconditionally
    # and a phone polls it; the screen that exists to edit this list did the
    # work and threw it away.
    await subjects_service.sync_from_timetable(session, school_class.id)
    await session.commit()
    rows = await session.scalars(
        select(Subject).where(Subject.class_id == school_class.id).order_by(Subject.name)
    )
    return [_subject_out(row) for row in rows]


@router.post("/subjects", response_model=SubjectSavedOut, status_code=status.HTTP_201_CREATED)
async def subject_create(
    payload: SubjectIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Add a subject. The name is unique within the class - that uniqueness is
    the whole point of the dictionary, so a duplicate is a 409, not a silent
    second «Алгебра»."""
    if await _name_taken(session, school_class.id, payload.name):
        raise _conflict("a subject with that name is already in this class")

    subject = Subject(
        class_id=school_class.id,
        name=payload.name,
        short_name=payload.short_name,
        teacher=payload.teacher,
        color=payload.color,
    )
    session.add(subject)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "subject.add",
        f"добавлен предмет «{payload.name}»",
    )
    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject))


@router.patch("/subjects/{subject_id}", response_model=SubjectSavedOut)
async def subject_update(
    subject_id: int,
    payload: SubjectPatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Rename a subject, or set its short name, teacher or colour.

    A rename is a cascade: the timetable, the homework and the substitutions store the
    subject as text, so all three move with it in this one transaction, and
    ``moved`` says how many rows did. Renaming onto a name the class already
    uses is refused - merging two subjects is a different operation, and doing
    it by accident cannot be undone.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    changes = payload.model_dump(exclude_unset=True)
    new_name = changes.pop("name", None)
    moved = 0

    if new_name is not None and new_name != subject.name:
        if await _name_taken(session, school_class.id, new_name, besides=subject.id):
            raise _conflict("a subject with that name is already in this class")
        # The dictionary check above cannot see this one: homework may be
        # written under a name that is not a dictionary entry, and homework is
        # unique per subject per day. See `structure.homework_clashing`.
        clash = await structure.homework_clashing(
            session, school_class.id, subject.name, new_name
        )
        if clash:
            days = ", ".join(day.isoformat() for day in clash)
            raise _conflict(
                f"homework under both names on the same day: {days}"
            )
        old_name = subject.name
        moved = await structure.rename_subject(session, school_class.id, subject, new_name)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "subject.rename",
            f"предмет «{old_name}» → «{new_name}», строк обновлено: {moved}",
        )

    #: Column -> (audit tag, the word the log line uses). The bot writes the
    #: colour as «цвет», not «color», and the two logs are read side by side.
    labels = {
        "short_name": ("subject.short_name", "сокращение"),
        "teacher": ("subject.teacher", "учитель"),
        "color": ("subject.colour", "цвет"),
    }
    for column, value in changes.items():
        setattr(subject, column, value)
        action, label = labels[column]
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            action,
            f"{label} предмета «{subject.name}»: {value or 'убрано'}",
        )

    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject), moved=moved)


@router.delete("/subjects/{subject_id}", response_model=DeletedOut)
async def subject_delete(
    subject_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Deleting a subject the timetable still uses is refused.

    It used to be allowed, and it left the lessons alone: the timetable stores
    the name as well as the link, so the class kept its timetable and lost
    only the colour and the teacher. That stopped being true when the
    dictionary started keeping itself. The name is still in the template, so
    the next read adopts it again - the entry returns within one poll, without
    its colour, its short name or its teacher, and the admin is left believing
    they deleted something.

    So the two halves of one list are deleted in one order: take the subject
    out of the weekly template, and then out of the dictionary. A subject
    nothing teaches still deletes in one step, which is the case this endpoint
    was really for.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    in_use = await subjects_service.lessons_using(session, school_class.id, subject)
    if in_use:
        raise _conflict(f"{in_use} lesson(s) still use this subject")
    name = subject.name
    await session.delete(subject)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "subject.delete",
        f"удалён предмет «{name}»",
    )
    await session.commit()
    return DeletedOut(id=subject_id)
