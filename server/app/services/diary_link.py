"""One-time tickets from a Telegram chat to the diary sign-in form.

The password is the whole confidentiality question, and the answer here is
that it never enters Telegram. The bot sends a URL; the form is served over
HTTPS by this same app; the password goes from the browser to the upstream and
is written down nowhere.

The alternative — typing it to the bot — puts it in the chat history, on
Telegram's servers, in the notification that lands on a locked screen and in
whatever backs that phone up. Deleting the message afterwards undoes exactly
none of those, so there is no such path: a deployment with no ``PUBLIC_BASE_URL``
has nowhere to serve the form, and `bot/handlers/diary.py` says the page cannot
be opened and stops there rather than falling back to a password in the chat.

What Telegram ever carries is the code in this module: worth one sign-in, for
fifteen minutes, for one Telegram account, in one class. Stored as a hash like
every other credential in this project, so a leak of ``diary_link_codes`` is a
list of tickets that cannot be used.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import delete as sa_delete
from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import rows_affected
from app.models import DiaryLinkCode
from app.security import hash_token, new_token

#: How long a ticket is worth anything.
#:
#: Short because the whole job is «walk from this chat to that browser», which
#: is seconds, and because the link travels through Telegram — a chat is
#: forwardable, screenshotted, and synced to every device the account is on.
#: Long enough to survive finding a laptop.
TICKET_MINUTES = 15


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


async def mint(session: AsyncSession, *, telegram_id: int, class_id: int) -> str:
    """A fresh ticket for this person in this class. Returns the plaintext.

    Any ticket they already held is dropped first: two live links is two
    chances for the older one — the one further up the chat, more likely to be
    scrolled past by somebody else — to still work.
    """
    await session.execute(
        sa_delete(DiaryLinkCode).where(
            DiaryLinkCode.telegram_id == telegram_id,
            DiaryLinkCode.class_id == class_id,
        )
    )
    code = new_token()
    session.add(
        DiaryLinkCode(
            code_hash=hash_token(code),
            telegram_id=telegram_id,
            class_id=class_id,
            expires_at=utcnow() + timedelta(minutes=TICKET_MINUTES),
        )
    )
    await session.commit()
    return code


async def claim(session: AsyncSession, code: str) -> DiaryLinkCode | None:
    """Spend a ticket. ``None`` if it is unknown, used or out of time.

    Marked used **before** the sign-in it authorises is attempted, and that
    order is the point: a ticket that survived a wrong password would let
    whoever has the link keep guessing against the upstream from our address,
    which is our address getting rate-limited for somebody else's attack.

    One conditional ``UPDATE`` decides it, exactly as
    :func:`app.services.device_invites.burn` decides a join, and for the same
    reason: "one link, one sign-in" is a promise about two requests, not one.
    Reading the row, checking it in Python and then assigning ``used_at`` lets
    two ``POST /diary/signin/{code}`` arriving together both read the same live
    row and both write it — one ticket, two diary sessions on one account. Here
    the second statement waits on the first's lock, re-checks
    ``used_at IS NULL`` after it, matches nothing, and the caller gets ``None``.

    The row is read back afterwards because the caller needs the ``telegram_id``
    and ``class_id`` it carries, and a ``SELECT`` inside the same transaction as
    the winning ``UPDATE`` can only see the row that update just spent.
    """
    now = utcnow()
    result = await session.execute(
        sa_update(DiaryLinkCode)
        .where(
            DiaryLinkCode.code_hash == hash_token(code),
            DiaryLinkCode.used_at.is_(None),
            # In the statement rather than compared after it, so that one
            # statement is the whole decision. It also keeps a refusal from
            # writing: a timed-out ticket marked used is a row that reads as
            # «кто-то вошёл» when nobody did, and the page says the same thing
            # to the person either way.
            DiaryLinkCode.expires_at > now,
        )
        .values(used_at=now)
    )
    if rows_affected(result) != 1:
        return None
    row = await session.scalar(
        select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
    )
    await session.commit()
    return row


async def drop_for(session: AsyncSession, *, telegram_id: int, class_id: int) -> int:
    """Drop every outstanding sign-in ticket this account holds in this class.

    Used when a member is removed: an unspent ticket is worth a sign-in for
    fifteen minutes, and an ex-member must not be able to open a session in a
    class they have just been taken out of. Committed by the caller.
    """
    result = await session.execute(
        sa_delete(DiaryLinkCode).where(
            DiaryLinkCode.telegram_id == telegram_id,
            DiaryLinkCode.class_id == class_id,
        )
    )
    return rows_affected(result)


async def purge(session: AsyncSession) -> int:
    """Drop tickets nobody can use any more. Returns how many went.

    Called from the cron tick rather than on a timer: on Vercel nothing runs
    between requests, so anything swept has to be swept by something arriving
    from outside.
    """
    result = await session.execute(
        sa_delete(DiaryLinkCode).where(DiaryLinkCode.expires_at <= utcnow())
    )
    await session.commit()
    return rows_affected(result)
