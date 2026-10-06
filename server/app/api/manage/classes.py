"""``/class``: the card «⚙️ Класс» shows, its settings, and deleting the class.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring. The card's patch is
``services/manage/classes.update``, which v2's ``UpdateClass`` applies too.
"""

from __future__ import annotations

import logging

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor, owner_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass
from app.schemas import ClassDeleteIn, ClassPatch, DeletedOut, ManagedClassOut
from app.services.manage import classes as classes_service
from app.timezones import label_for

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# ⚙️ The class card
# --------------------------------------------------------------------------


async def _class_out(session: AsyncSession, school_class: SchoolClass) -> ManagedClassOut:
    counts = await classes_service.counts(session, school_class.id)
    return ManagedClassOut(
        id=school_class.id,
        name=school_class.name,
        school=school_class.school,
        city=school_class.city,
        timezone=school_class.timezone_name,
        timezone_label=label_for(school_class.timezone_name),
        join_code=school_class.join_code,
        members=counts.members,
        devices=counts.devices,
        pending_requests=counts.pending,
        bell_schedule_id=school_class.bell_schedule_id,
        calendar_ready=bool(school_class.calendar_token),
        # `.value`, so the wire says «open». The column stores the member name
        # because that is how SQLAlchemy persists an enum, and the two are not
        # the same string.
        join_mode=school_class.join_mode.value,
    )


@router.get("/class", response_model=ManagedClassOut)
async def class_card(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedClassOut:
    """What «⚙️ Класс» shows, as data."""
    return await _class_out(session, school_class)


@router.patch("/class", response_model=ManagedClassOut)
async def class_update(
    payload: ClassPatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedClassOut:
    """Rename the class, re-home it, move its time zone, or change who may join.

    One audit line per field changed rather than one for the request, because
    that is what the log is read for: «что изменилось», not «кто открыл
    настройки». The patch is ``services/manage/classes.update``, which v2's
    ``UpdateClass`` applies too: the letter read by the bot's rule, the zone
    asked about before anything is written, the name recomposed from the grade
    and the letter unless the request names the class, and the join mode last.
    """
    try:
        await classes_service.update(
            session, school_class, actor.telegram_id, payload.model_dump(exclude_unset=True)
        )
    except classes_service.UnknownTimezone as unknown:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=wording.UNKNOWN_TIMEZONE_DETAIL,
        ) from unknown

    await session.commit()
    return await _class_out(session, school_class)


@router.delete("/class", response_model=DeletedOut)
async def class_delete(
    payload: ClassDeleteIn,
    actor: Actor = Depends(owner_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Delete the class and everything hanging off it. Owner only.

    ``confirm_name`` must be the class's name, exactly as the bot makes an
    owner type it back: a confirmation dialog is answered by the same thumb
    that opened it, while a name has to be read off the screen first. The app
    will show its own «вы уверены» sheet, but the check that matters is this
    one, because the endpoint is reachable without the sheet.

    Nothing is written to the audit log: the log lives in the class and goes
    with it. Every device token goes too, so the caller's own token stops
    working - which is correct, there is nothing left for it to read.
    """
    class_id = school_class.id
    try:
        await classes_service.delete(session, school_class, payload.confirm_name)
    except classes_service.NameMismatch as mismatch:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="confirm_name does not match the class name",
        ) from mismatch

    log.info("class %s deleted by %s", class_id, actor.telegram_id)
    await session.commit()
    return DeletedOut(id=class_id)
