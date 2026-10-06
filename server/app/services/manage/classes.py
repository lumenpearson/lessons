"""«⚙️ Класс» and ``/manage/class``: the class's own card and settings.

Three ways in change the class itself - the card's typed fields, the zone
picker in ``handlers/start/timezone.py`` and the join switch in ``handlers/access.py``
- and ``PATCH /manage/class`` changes all of them at once. One audit line per
field changed, with the same action names and the same words from either
side, because that is what the log is read for: «что изменилось», not «кто
открыл настройки». :func:`update` is that patch, moved here from v1's router
so that v2's ``UpdateClass`` applies it too (the server-v2 design, decision 2).
"""

from __future__ import annotations

from collections.abc import Mapping
from dataclasses import dataclass
from typing import Any

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import AccessRequest, BotUser, DeviceToken, JoinMode, SchoolClass
from app.services import audit
from app.services import terms as terms_service
from app.timezones import is_supported, label_for

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


def timezone_label(school_class: SchoolClass) -> str:
    """«МСК+2 (UTC+5) · Екатеринбург, Уфа, Пермь»: the label the bot prints for
    the zone the class runs on. Here so that v2's handler reaches it through
    ``services/``, where its imports stop (the server-v2 design, decision 2)."""
    return label_for(school_class.timezone_name)


async def class_of(session: AsyncSession, class_id: int) -> SchoolClass | None:
    """A class by id, for a screen that already knows the person is in it."""
    return await session.get(SchoolClass, class_id)


async def classes_of(session: AsyncSession, memberships: list[BotUser]) -> list[SchoolClass]:
    """The classes behind a person's memberships, in the memberships' order.

    The order is the one «🔀 Сменить класс» draws its buttons in; a membership
    whose class has gone is skipped rather than drawn as a dead button.
    """
    found: list[SchoolClass] = []
    for member in memberships:
        school_class = await session.get(SchoolClass, member.class_id)
        if school_class is not None:
            found.append(school_class)
    return found


async def members(session: AsyncSession, class_id: int) -> list[BotUser]:
    """Everybody with a role in the class, for a shell to put names to ids."""
    return list(await session.scalars(select(BotUser).where(BotUser.class_id == class_id)))


def display_name(full_name: str | None, username: str | None, telegram_id: int | None) -> str:
    """The best name held for somebody, as plain text, in the order the bot picks it.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can act on, and every answer that shows one is an
    admin's. Not escaped, because it goes into JSON and protobuf; the bot keeps
    an escaping twin (``bot/manage_render/_common.person``). Moved from
    ``api/manage/_common._person`` (the server-v2 design, decision 2).
    """
    if username:
        return f"@{username}"
    if full_name:
        return full_name
    return str(telegram_id) if telegram_id is not None else "—"


async def member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> :func:`display_name`, for everybody with a role in the class."""
    return {
        member.telegram_id: display_name(member.full_name, member.username, member.telegram_id)
        for member in await members(session, class_id)
    }


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


async def update(
    session: AsyncSession,
    school_class: SchoolClass,
    actor_id: int | None,
    changes: Mapping[str, Any],
) -> None:
    """Apply a patch to the class card: one audit line per field changed.

    ``changes`` maps the fields of v1's ``ClassPatch`` - ``name``, ``grade``,
    ``letter``, ``school``, ``city``, ``timezone``, and ``join_mode`` as
    ``"open"`` or ``"invite"`` - to their new values. An absent key is left
    alone, and ``None`` clears a field that may be cleared. v1's ``PATCH`` and
    v2's ``UpdateClass`` both call this, so the order and the lines are one.

    Changing the zone moves no stored time - a bell rings at 08:30 whatever
    the zone says - it changes which instant the class calls «сейчас». Moving
    the grade or the letter recomposes the name, because that is where the
    name came from; a name in the same patch wins over both.

    @raises UnknownTimezone for a zone that is not on the list, before any
        field is written.
    """
    fields = dict(changes)
    zone = fields.pop("timezone", None)
    mode = fields.pop("join_mode", None)
    if "letter" in fields:
        # The same rule the bot's «Буква» step applies, not a second one. It
        # trims, and it reads «-» as «no letter» — which is what the bot tells
        # people to send and what the phone's own field offers. Stored raw, a
        # «-» became the literal name «9-» and a padded « А » a `letter` that
        # never equals the «А» anything compares it with, while `compose_name`
        # trimmed on its way past and left the name looking correct.
        fields["letter"] = terms_service.normalise_letter(fields["letter"])
    if zone is not None and not is_supported(zone):
        raise UnknownTimezone(zone)

    for column, value in fields.items():
        await set_field(session, school_class, actor_id, column, value)
    if zone is not None:
        await set_timezone(session, school_class, actor_id, zone)
    if ("grade" in fields or "letter" in fields) and "name" not in fields:
        # A class moved from 9 to 10 is not called «9А» any more. The name was
        # composed from these two at creation
        # (`bot/handlers/start/onboarding.py`), and a move that left the old
        # name standing showed the wrong class on every screen that prints one.
        # Unless the same patch also names the class: an admin who typed a
        # name has said what they want, and recomposing over it would overrule
        # them within the one request.
        composed = terms_service.compose_name(
            school_class.grade, school_class.letter, fallback=school_class.name
        )
        if composed != school_class.name:
            school_class.name = composed
            await audit.record(
                session, school_class.id, actor_id, "class.name", f"name: {composed}"
            )
    if mode is not None:
        # Through the enum rather than by the string, because the attribute is
        # read back as one by the card this same request answers with. The same
        # function the bot's own switch calls, so the log reads as one history
        # however the switch was flipped - and a switch to the mode already in
        # force writes no line from either side.
        await set_join_mode(session, school_class, actor_id, JoinMode(mode))


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
