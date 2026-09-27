"""``/class``: the card «⚙️ Класс» shows, its settings, and deleting the class.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

import logging

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, _count, admin_actor, owner_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import AccessRequest, BotUser, DeviceToken, JoinMode, SchoolClass
from app.schemas import ClassDeleteIn, ClassPatch, DeletedOut, ManagedClassOut
from app.services import audit
from app.services import terms as terms_service
from app.timezones import is_supported, label_for

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# ⚙️ The class card
# --------------------------------------------------------------------------


async def _class_out(session: AsyncSession, school_class: SchoolClass) -> ManagedClassOut:
    return ManagedClassOut(
        id=school_class.id,
        name=school_class.name,
        school=school_class.school,
        city=school_class.city,
        timezone=school_class.timezone_name,
        timezone_label=label_for(school_class.timezone_name),
        join_code=school_class.join_code,
        members=await _count(session, BotUser, BotUser.class_id == school_class.id),
        devices=await _count(
            session,
            DeviceToken,
            DeviceToken.class_id == school_class.id,
            DeviceToken.revoked.is_(False),
        ),
        pending_requests=await _count(
            session,
            AccessRequest,
            AccessRequest.class_id == school_class.id,
            AccessRequest.status == "pending",
        ),
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
    настройки». Changing the zone moves no stored time - a bell rings at 08:30
    whatever the zone says - it changes which instant the class calls «сейчас».

    Moving the grade or the letter recomposes the name, because that is where
    the name came from; a name sent in the same request wins over both.
    """
    changes = payload.model_dump(exclude_unset=True)
    zone = changes.pop("timezone", None)
    mode = changes.pop("join_mode", None)
    if "letter" in changes:
        # The same rule the bot's «Буква» step applies, not a second one. It
        # trims, and it reads «-» as «no letter» — which is what the bot tells
        # people to send and what the phone's own field offers. Stored raw, a
        # «-» became the literal name «9-» and a padded « А » a `letter` that
        # never equals the «А» anything compares it with, while `compose_name`
        # trimmed on its way past and left the name looking correct.
        changes["letter"] = terms_service.normalise_letter(changes["letter"])
    if zone is not None and not is_supported(zone):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="unknown timezone",
        )

    for column, value in changes.items():
        setattr(school_class, column, value)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            f"class.{column}",
            f"{column}: {value or 'убрано'}",
        )
    if zone is not None:
        school_class.timezone = zone
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "class.timezone",
            f"часовой пояс: {zone}",
        )
    if ("grade" in changes or "letter" in changes) and "name" not in changes:
        # A class moved from 9 to 10 is not called «9А» any more. The name was
        # composed from these two at creation (`bot/handlers/start.py`), and a
        # move that left the old name standing showed the wrong class on every
        # screen that prints one. Unless the same request also names the class:
        # an admin who typed a name has said what they want, and recomposing
        # over it would overrule them within the one request.
        composed = terms_service.compose_name(
            school_class.grade, school_class.letter, fallback=school_class.name
        )
        if composed != school_class.name:
            school_class.name = composed
            await audit.record(
                session,
                school_class.id,
                actor.telegram_id,
                "class.name",
                f"name: {composed}",
            )
    if mode is not None:
        # Through the enum rather than by the string, because the attribute is
        # read back as one by `_class_out` in this same request - and because
        # the audit line should say what changed rather than echo a wire value.
        school_class.join_mode = JoinMode(mode)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            # The same action name the bot's own toggle writes, so the log
            # reads as one history however the switch was flipped.
            "access.join_mode",
            "вход только по личным приглашениям"
            if school_class.join_mode is JoinMode.INVITE
            else "вход по коду класса снова разрешён",
        )

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
    if payload.confirm_name.strip() != school_class.name:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="confirm_name does not match the class name",
        )

    class_id = school_class.id
    log.info("class %s deleted by %s", class_id, actor.telegram_id)
    await session.delete(school_class)
    await session.commit()
    return DeletedOut(id=class_id)
