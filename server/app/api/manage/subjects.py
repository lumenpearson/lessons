"""``/subjects``: the class's subject dictionary, as «📚 Предметы» edits it.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _conflict, admin_actor, editor_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass, Subject
from app.schemas import DeletedOut, ManagedSubjectOut, SubjectIn, SubjectPatch, SubjectSavedOut
from app.services.manage import subjects as subjects_service

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
    subject = await subjects_service.subject_of(session, school_class.id, subject_id)
    if subject is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown subject")
    return subject


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
    rows = await subjects_service.listing(session, school_class.id)
    await session.commit()
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
    try:
        subject = await subjects_service.create(
            session,
            school_class.id,
            actor.telegram_id,
            payload.name,
            short_name=payload.short_name,
            teacher=payload.teacher,
            color=payload.color,
        )
    except subjects_service.SubjectExists as taken:
        raise _conflict("a subject with that name is already in this class") from taken
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

    if new_name is not None:
        try:
            moved = (
                await subjects_service.rename(
                    session, school_class.id, actor.telegram_id, subject, new_name
                )
                or 0
            )
        except subjects_service.SubjectExists as taken:
            raise _conflict("a subject with that name is already in this class") from taken
        except subjects_service.HomeworkClash as clash:
            raise _conflict(f"homework under both names on the same day: {clash}") from clash

    for column, value in changes.items():
        await subjects_service.set_detail(
            session, school_class.id, actor.telegram_id, subject, column, value
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
    try:
        await subjects_service.delete(session, school_class.id, actor.telegram_id, subject)
    except subjects_service.SubjectInUse as in_use:
        raise _conflict(f"{in_use.lessons} lesson(s) still use this subject") from in_use
    await session.commit()
    return DeletedOut(id=subject_id)
