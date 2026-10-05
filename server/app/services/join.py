"""Getting a phone into a class: a code in, a device token out.

This was the body of v1's ``POST /join`` (``api/public.py``). It moved here so
that v2's ``CreateDevice`` is the same implementation over the same throttle
(``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 11): a caller
who alternates the two versions draws on one budget of thirty wrong codes, not
on two. A refusal is an exception carrying facts, never a sentence, and each
shell words it — v1's ``HTTPException`` details, v2's error table — with the
sentences ``app/wording.py`` keeps for both.

It commits, as the endpoint it came from did, and for the same reason the
throttles do: the attempt that :meth:`JoinThrottle.admit` counted has to be
durable before the code is even looked at, and a refusal that leaves it
counted must leave it counted whatever the caller then rolls back.
"""

from __future__ import annotations

from dataclasses import dataclass

from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceInvite, DeviceToken, JoinMode, SchoolClass
from app.security import (
    MAX_DEVICES_PER_CLASS,
    Throttled,
    hash_token,
    join_limiter,
    new_token,
)
from app.services import audit, device_invites


class JoinRefused(Exception):
    """A code that bought no token. Each subclass is one answer a shell words.

    The limiter and the device cap are ``security``'s (decision 11 of the
    server-v2 design): one instance each, whichever version asked."""


class JoinThrottled(JoinRefused, Throttled):
    """Too many wrong codes from this caller inside the window: the join
    limiter's own refusal, so a shell can word it as v1 did."""


class JoinCodeUnknown(JoinRefused):
    """Neither a class code nor a live personal code. The one refusal that
    stays counted against the caller."""


class ClassInviteOnly(JoinRefused):
    """A real class code for a class that admits personal codes only."""


class DeviceLimitReached(JoinRefused):
    """The class code has put :data:`MAX_DEVICES_PER_CLASS` live phones in."""

    limit = MAX_DEVICES_PER_CLASS


@dataclass(frozen=True)
class Joined:
    """What a successful join hands back: the token, shown once, and its class."""

    token: str
    school_class: SchoolClass


async def live_devices(session: AsyncSession, class_id: int) -> int:
    """Phones that can still read the class. A revoked row is a tombstone kept
    so its token goes on failing, and holds no phone."""
    count = await session.scalar(
        select(func.count())
        .select_from(DeviceToken)
        .where(DeviceToken.class_id == class_id, DeviceToken.revoked.is_(False))
    )
    return count or 0


async def join(
    session: AsyncSession, *, code: str, device_name: str | None, client_key: str
) -> Joined:
    """Exchange a code for a long-lived device token. Commits.

    Read-only when the code is the class's: a token with no Telegram account
    behind it is refused by every write path. A personal invite from the bot
    carries the account that asked for it, so the phone that redeems one writes
    with that account's role at the moment of each request.

    ``code`` is already cleaned of control characters by the shell's own
    validation; ``client_key`` is the caller's bucket, from
    ``api/deps.caller_bucket`` with no scope.
    """
    # Counted before the code is looked at, and handed back below on every
    # answer that is not a wrong code: see `JoinThrottle.admit` for why a check
    # followed by a later record let a concurrent burst through.
    attempt = await join_limiter.admit(session, client_key)
    if attempt.retry_after is not None:
        raise JoinThrottled(attempt.retry_after)

    code = code.strip().upper()
    # The class code first, and a personal invite only if it names no class.
    # The two cannot collide — they are different lengths, see
    # ``services/device_invites.CODE_LENGTH`` — so the order is about which
    # refusal the caller gets rather than about which code wins.
    school_class = await session.scalar(select(SchoolClass).where(SchoolClass.join_code == code))
    invite: DeviceInvite | None = None

    if school_class is not None and school_class.join_mode is JoinMode.INVITE:
        # A real code for a real class, refused because this class does not let
        # a shared secret in. Said plainly rather than as "unknown code": the
        # person holding it has been given it by somebody, and telling them it
        # is wrong sends them back to that person instead of to the bot.
        #
        # Not a failed attempt for the throttle either. The limiter is there to
        # stop somebody walking the code space, and this caller has already
        # found a code — counting it would let a class that switched to invites
        # lock out everybody who still had the old one.
        await join_limiter.forgive(session, attempt)
        raise ClassInviteOnly("class takes personal codes only")

    if school_class is None:
        invite = await device_invites.find_live(session, code)
        if invite is not None:
            school_class = await session.get(SchoolClass, invite.class_id)

    if school_class is None:
        # The one answer that stays counted: the attempt `admit` wrote is it.
        raise JoinCodeUnknown("unknown join code")

    if invite is None and await live_devices(session, school_class.id) >= MAX_DEVICES_PER_CLASS:
        # Only the class code is bounded. A personal code is minted by the bot
        # for a member it knows, one phone at a time, with a name in the
        # journal: it is not what the bound is against, and it is the way in
        # left to a family when somebody has filled the class through the
        # shared code. Not counted against the throttle, for the reason the
        # invite-only refusal is not — a real code, not a guess. A burst of
        # joins that all counted before any committed can pass the bound by its
        # own size, and no further: after it, every join counts past it.
        await join_limiter.forgive(session, attempt)
        raise DeviceLimitReached("class is full")

    # Spent before the token is minted, not after: the update is what makes
    # "one code, one phone" true against a second request that read the same
    # live row, and a token minted first would be a token already handed out
    # by the time we found out we lost.
    if invite is not None and not await device_invites.burn(session, invite):
        # Lost a race microseconds wide: the row was live when it was read and
        # spent by the time it was written. Not counted against the limiter,
        # for the same reason as above — this caller had a real code, and the
        # limiter is there for somebody who does not.
        await join_limiter.forgive(session, attempt)
        raise JoinCodeUnknown("personal code spent by a concurrent join")

    token = new_token()
    session.add(
        DeviceToken(
            token_hash=hash_token(token),
            class_id=school_class.id,
            device_name=device_name,
            # An invite carries the account that asked for it, so the device is
            # linked in the same breath as it joins. On the class code it stays
            # null, which is what "joined, nobody knows whose phone" looks like.
            telegram_id=invite.telegram_id if invite is not None else None,
            linked_at=device_invites.utcnow() if invite is not None else None,
        )
    )
    if invite is not None:
        # The same line `/link` writes, for the same event: a phone that from
        # now on acts with somebody's role. On the invite path this is the only
        # place it can be written — there is no second step to hang it on — and
        # in «по приглашению» this is the only door, so without it the journal
        # stops answering «кто подключил этот телефон» exactly when it becomes
        # the only question worth asking of it.
        await audit.record(
            session,
            school_class.id,
            invite.telegram_id,
            "device.link",
            f"телефон подключён по личному коду: {device_name or 'без названия'}",
        )
    await session.commit()
    # A real code: only failures are counted, so a classroom joining from one
    # school NAT is never blocked by each other's successes. After the commit
    # above, which is the join, so the invite's burn and the token land in one
    # transaction as they always did.
    await join_limiter.forgive(session, attempt)
    return Joined(token=token, school_class=school_class)
