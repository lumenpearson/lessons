"""Who is allowed to do what.

Role ladder (weakest first): VIEWER -> EDITOR -> ADMIN -> OWNER.

* VIEWER  — reads the schedule and homework in the bot.
* EDITOR  — writes homework, substitutions and events. This is the role you hand to a
            classmate whose phone number you add.
* ADMIN   — everything an editor can do, plus the weekly timetable, bells and
            granting/revoking EDITOR and VIEWER.
* OWNER   — everything, plus granting ADMIN and rotating the join code.
            OWNER is granted only by ``OWNER_IDS`` in the environment, so it can
            never be escalated into from inside the bot.
"""

from __future__ import annotations

from datetime import datetime

from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.models import BotUser, PhoneInvite, Role, SchoolClass
from app.security import normalise_phone


def is_env_owner(telegram_id: int) -> bool:
    return telegram_id in get_settings().owner_id_list


async def get_membership(
    session: AsyncSession, telegram_id: int, class_id: int
) -> BotUser | None:
    return await session.scalar(
        select(BotUser).where(BotUser.telegram_id == telegram_id, BotUser.class_id == class_id)
    )


async def get_role(session: AsyncSession, telegram_id: int, class_id: int) -> Role | None:
    """Effective role, with the environment owner list taking precedence."""
    if is_env_owner(telegram_id):
        return Role.OWNER
    membership = await get_membership(session, telegram_id, class_id)
    return membership.role if membership else None


async def require_role(
    session: AsyncSession, telegram_id: int, class_id: int, minimum: Role
) -> bool:
    role = await get_role(session, telegram_id, class_id)
    return role is not None and role.at_least(minimum)


async def list_memberships(session: AsyncSession, telegram_id: int) -> list[BotUser]:
    """Every class this account is in, oldest membership first.

    The order is stated rather than left to the database because three things
    read it as if it were: the class the middleware falls back to, the one
    ``default_class_for`` picks, and the order of the «🔀 Сменить класс»
    buttons. An unordered ``SELECT`` is free to hand back a different order
    after any write, so a parent with two children could aim at the second
    button and open the other child — and the fallback class could differ
    between two taps. SQLite happens to return insertion order, which is why
    the tests never saw it; Postgres makes no such promise.
    """
    return list(
        await session.scalars(
            select(BotUser).where(BotUser.telegram_id == telegram_id).order_by(BotUser.id)
        )
    )


async def default_class_for(session: AsyncSession, telegram_id: int) -> SchoolClass | None:
    """The class a user lands in when they open the bot.

    Environment owners see the first class even before they are members of it,
    which is what makes bootstrapping a fresh install possible.
    """
    memberships = await list_memberships(session, telegram_id)
    if memberships:
        return await session.get(SchoolClass, memberships[0].class_id)
    if is_env_owner(telegram_id):
        return await session.scalar(select(SchoolClass).order_by(SchoolClass.id).limit(1))
    return None


def can_grant(actor: Role, target: Role) -> bool:
    """Nobody may grant a role at or above their own, and OWNER is env-only.

    Without this rule an admin could promote a friend to admin and then be
    demoted by them; the strict inequality removes that whole class of problem.
    """
    if target is Role.OWNER:
        return False
    if not actor.at_least(Role.ADMIN):
        return False
    return actor.rank > target.rank


async def claim_phone_invites(
    session: AsyncSession,
    telegram_id: int,
    phone: str,
    username: str | None,
    full_name: str | None,
) -> list[tuple[SchoolClass, Role]]:
    """Apply every pending invite matching ``phone``.

    Called when a user shares their contact with the bot. Telegram guarantees the
    number really belongs to that account, which is what makes an invite-by-phone
    safe to auto-apply.

    @return (class, effective role) per invite claimed — the role the account
        holds in that class afterwards, which is not always the invite's.
    """
    normalised = normalise_phone(phone)
    if not normalised:
        return []

    invites = list(
        await session.scalars(
            select(PhoneInvite).where(
                PhoneInvite.phone == normalised, PhoneInvite.used_by.is_(None)
            )
        )
    )

    granted: list[tuple[SchoolClass, Role]] = []
    for invite in invites:
        school_class = await session.get(SchoolClass, invite.class_id)
        if school_class is None:
            continue

        membership = await get_membership(session, telegram_id, invite.class_id)
        if membership is None:
            membership = BotUser(
                telegram_id=telegram_id,
                class_id=invite.class_id,
                role=invite.role,
                granted_by=invite.invited_by,
            )
            session.add(membership)
        elif invite.role.rank > membership.role.rank:
            membership.role = invite.role

        membership.phone = normalised
        membership.username = username
        membership.full_name = full_name

        invite.used_by = telegram_id
        invite.used_at = datetime.utcnow()
        # The role they now hold, not the one the invite named. The rule above
        # keeps the higher of the two, so an invite below somebody's existing
        # role changes nothing — but the caller draws the main menu from what
        # is returned here, and an admin was told «роль: Наблюдатель» and given
        # an observer's keyboard, with «🧩 Расписание», «👥 Доступ» and
        # «⚙️ Класс» simply absent.
        granted.append((school_class, membership.role))

    await session.commit()
    return granted
