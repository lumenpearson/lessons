"""One-time tickets from a Telegram chat to the diary sign-in form.

The password is the whole confidentiality question, and the answer here is
that it never enters Telegram. The bot sends a URL; the form is served over
HTTPS by this same app; the password goes from the browser to the upstream and
is written down nowhere.

The alternative — typing it to the bot — puts it in the chat history, on
Telegram's servers, in the notification that lands on a locked screen and in
whatever backs that phone up. Deleting the message afterwards undoes exactly
none of those. The bot keeps that path as a fallback for a deployment with no
public URL, and says plainly that it is the worse one.

What Telegram ever carries is the code in this module: worth one sign-in, for
fifteen minutes, for one Telegram account, in one class. Stored as a hash like
every other credential in this project, so a leak of ``diary_link_codes`` is a
list of tickets that cannot be used.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import delete as sa_delete
from sqlalchemy import select
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
    """
    row = await session.scalar(
        select(DiaryLinkCode).where(DiaryLinkCode.code_hash == hash_token(code))
    )
    if row is None or row.used_at is not None or row.expires_at <= utcnow():
        return None
    row.used_at = utcnow()
    await session.commit()
    return row


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
