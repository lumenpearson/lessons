"""«⚙️ Класс» and ``/manage/class``: the class's own card and settings.

Three ways in change the class itself - the card's typed fields, the zone
picker in ``handlers/start.py`` and the join switch in ``handlers/access.py``
- and ``PATCH /manage/class`` changes all of them at once. One audit line per
field changed, with the same action names and the same words from either
side, because that is what the log is read for: «что изменилось», not «кто
открыл настройки».
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AccessRequest, BotUser, DeviceToken, JoinMode, SchoolClass
from app.services import audit
from app.timezones import is_supported

#: What the log says when a join mode is switched, by the mode switched to.
JOIN_MODE_SUMMARY = {
    JoinMode.INVITE: "вход только по личным приглашениям",
    JoinMode.OPEN: "вход по коду класса снова разрешён",
}


class UnknownTimezone(ValueError):
    """A zone this deployment does not offer."""


class NameMismatch(ValueError):
    """The name typed back to confirm a deletion is not the class's name."""


@dataclass(frozen=True)
class Counts:
    """The three numbers the class card shows."""

    members: int
    devices: int
    pending: int


async def _count(session: AsyncSession, model: type, *where: object) -> int:
    return int(await session.scalar(select(func.count()).select_from(model).where(*where)) or 0)


async def counts(session: AsyncSession, class_id: int) -> Counts:
    """Members, phones that are still switched on, and requests nobody answered."""
    return Counts(
        members=await _count(session, BotUser, BotUser.class_id == class_id),
        devices=await _count(
            session,
            DeviceToken,
            DeviceToken.class_id == class_id,
            DeviceToken.revoked.is_(False),
        ),
        pending=await _count(
            session,
            AccessRequest,
            AccessRequest.class_id == class_id,
            AccessRequest.status == "pending",
        ),
    )


async def members(session: AsyncSession, class_id: int) -> list[BotUser]:
    """Everybody with a role in the class, for a shell to put names to ids."""
    return list(await session.scalars(select(BotUser).where(BotUser.class_id == class_id)))


async def set_field(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    column: str,
    value: object,
) -> None:
    """Set one plain column of the class and log it as ``class.<column>``."""
    setattr(school_class, column, value)
    await audit.record(
        session, school_class.id, actor_id, f"class.{column}", f"{column}: {value or 'убрано'}"
    )


async def set_timezone(
    session: AsyncSession, school_class: SchoolClass, actor_id: int | None, zone: str
) -> None:
    """Move the class to another zone.

    No stored time moves - a bell rings at 08:30 whatever the zone says - what
    changes is which instant the class calls «сейчас». Logged from both sides;
    the bot's picker used to change it without a line, so the one change that
    shifts every «now» on every phone was the one the log could not explain.

    @raises UnknownTimezone for a zone that is not on the list.
    """
    if not is_supported(zone):
        raise UnknownTimezone(zone)
    school_class.timezone = zone
    await audit.record(
        session, school_class.id, actor_id, "class.timezone", f"часовой пояс: {zone}"
    )


async def set_join_mode(
    session: AsyncSession, school_class: SchoolClass, actor_id: int | None, mode: JoinMode
) -> bool:
    """Who may join with the class code. Revokes no device, in either direction.

    @return whether it changed. Switching to the mode already in force writes
        nothing, from either side.
    """
    if school_class.join_mode is mode:
        return False
    school_class.join_mode = mode
    await audit.record(
        session, school_class.id, actor_id, "access.join_mode", JOIN_MODE_SUMMARY[mode]
    )
    return True


async def delete(session: AsyncSession, school_class: SchoolClass, confirm_name: str) -> None:
    """Delete the class and everything hanging off it.

    The name typed back is the confirmation, and the only one: a «вы уверены?»
    button is pressed by the same thumb that pressed the one before it, while a
    name has to be read off the screen first. Nothing is written to the log -
    the log lives in the class and goes with it.

    @raises NameMismatch unless ``confirm_name`` is the name, give or take
        the whitespace around it.
    """
    if confirm_name.strip() != school_class.name:
        raise NameMismatch(confirm_name)
    await session.delete(school_class)
