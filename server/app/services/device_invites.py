"""One-time codes that let one phone into one class.

The class code is one secret shared by everybody who is in the class. That is
fine for a class that would hand its timetable to anyone who asked, and it is
the wrong shape for one that would not: a code printed on paper and read out in
a chat is worth exactly as much as the least careful person holding it, and
rotating it throws out everybody at once rather than the person it leaked
through.

A class in :attr:`~app.models.JoinMode.INVITE` stops accepting that code and
accepts these instead. One is worth one join, for fifteen minutes, for one
Telegram account, in one class, and the bot only mints one for somebody it
already recognises as a member — so the question "who let this phone in" always
has a name in it.

Redeeming links the device to that account in the same breath. In ``OPEN`` a
phone joins anonymously and links afterwards by typing a second code into the
bot, which most people never do, so the device list fills up with rows nobody
can identify. Here the identity is not an extra step, because the code could
not have existed without it.

**Switching modes takes nothing away.** Turning ``INVITE`` on does not revoke a
device that joined on the class code, exactly as rotating the code does not —
the two are the same promise, and a switch that silently threw a class off its
timetable would be the kind of thing an admin finds out about from thirty
people at once. Turning it back off makes the class code work again.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta

from sqlalchemy import delete as sa_delete
from sqlalchemy import select
from sqlalchemy import update as sa_update
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DeviceInvite
from app.security import hash_token, new_join_code

#: How long a code is worth anything.
#:
#: The same fifteen minutes as the diary's ticket, for the same reason: the job
#: is «walk from this chat to that phone», which is seconds, and the code
#: travels through Telegram — forwardable, screenshotted, synced to every device
#: the account is signed in on.
CODE_MINUTES = 15

#: Ten characters, where a class code is :data:`~app.security.JOIN_CODE_LENGTH`
#: — eight.
#:
#: Not for the extra entropy — the expiry and the single use are what make this
#: safe — but so that a personal code can never be mistaken for a class code by
#: the endpoint that accepts both. `/join` looks the class up first, and two
#: codes of different lengths cannot collide there however the alphabet falls.
#:
#: It shares :func:`~app.security.new_join_code`, so it shares that alphabet:
#: upper case, no ``O0I1``. Both halves matter here. `/join` upper-cases what
#: it is given before hashing, so a code with a lower-case letter in it would
#: never match the hash stored for it; and the pair a human most often mistypes
#: off a phone screen is the pair that is not in the alphabet at all.
CODE_LENGTH = 10


def utcnow() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


async def mint(session: AsyncSession, *, telegram_id: int, class_id: int) -> str:
    """A fresh code for this person in this class. Returns the plaintext.

    Any **live** code they already held is dropped first: two live codes is two
    chances for the older one — the one further up the chat, more likely to be
    scrolled past by somebody else — to still work. Spent ones are left where
    they are; see [burn] for what they are still worth.

    The drop is not a lock, so two presses processed at the same instant can
    still leave two live codes: each deletes before either has committed its
    insert. Both belong to the same account, both die in fifteen minutes and
    each is worth one phone, so the cost is one extra code in one chat — worth
    saying out loud rather than paying for with a unique index that would make
    an ordinary double-tap an error.
    """
    await session.execute(
        sa_delete(DeviceInvite).where(
            DeviceInvite.telegram_id == telegram_id,
            DeviceInvite.class_id == class_id,
            # Redeemed rows survive this. Deleting them would destroy the
            # record [burn] keeps on purpose, and in the commonest flow of all
            # — connect one phone, press again for the second — it would
            # destroy it seconds after it was written.
            DeviceInvite.used_at.is_(None),
        )
    )
    code = new_join_code(CODE_LENGTH)
    session.add(
        DeviceInvite(
            code_hash=hash_token(code),
            class_id=class_id,
            telegram_id=telegram_id,
            expires_at=utcnow() + timedelta(minutes=CODE_MINUTES),
        )
    )
    await session.commit()
    return code


async def find_live(session: AsyncSession, code: str) -> DeviceInvite | None:
    """The unused, unexpired invite this code names, or ``None``.

    Expiry is compared here rather than in a ``WHERE`` clause so that "expired"
    and "already used" are the same answer to the caller: both mean this code is
    not worth a join, and telling them apart out loud would say whether a code
    ever existed.
    """
    row = await session.scalar(
        select(DeviceInvite).where(DeviceInvite.code_hash == hash_token(code))
    )
    if row is None or row.is_used or row.expires_at <= utcnow():
        return None
    return row


async def burn(session: AsyncSession, invite: DeviceInvite) -> bool:
    """Spend it. ``False`` if somebody else already did, and the caller stops.

    One conditional ``UPDATE`` rather than an attribute assignment, because
    "one code, one phone" is a promise about two requests and not about one.
    :func:`find_live` reads; a second request reading in the same instant reads
    the same live row, and two attribute writes are two rows-worth of nothing —
    the code lets two phones in. Here the second statement waits on the first's
    lock, re-checks ``used_at IS NULL`` after it, and matches nothing.

    Marked rather than deleted, and the difference is a day of hindsight: the
    lasting answer to «чей это телефон» is on the device token, which carries
    the account, but the row that let it in is what «мой код перестал работать»
    is asked about, and that question arrives minutes after the fact. [prune]
    takes it a day later.
    """
    result = await session.execute(
        sa_update(DeviceInvite)
        .where(DeviceInvite.id == invite.id, DeviceInvite.used_at.is_(None))
        .values(used_at=utcnow())
    )
    return (result.rowcount or 0) == 1


async def drop_for(session: AsyncSession, *, telegram_id: int, class_id: int) -> int:
    """Kill the live codes one person holds in one class. @return how many.

    Called when their membership goes. A code is checked against nothing but
    its own hash at redemption — [find_live] never re-reads the ``BotUser`` row
    — so without this, somebody removed from the class in the fifteen minutes
    after pressing the button still gets a phone in, and in ``INVITE`` that is
    the only door there is. Staged, not committed: the caller commits it with
    the removal it belongs to, so the two land together or not at all.

    Spent rows are left alone. Revoking somebody does not un-happen the phone
    they already connected — that is [app.bot.handlers.manage] territory, and
    the row is the record of it.
    """
    result = await session.execute(
        sa_delete(DeviceInvite).where(
            DeviceInvite.telegram_id == telegram_id,
            DeviceInvite.class_id == class_id,
            DeviceInvite.used_at.is_(None),
        )
    )
    return result.rowcount or 0


async def prune(session: AsyncSession) -> int:
    """Drops codes that can no longer be redeemed. @return how many went.

    Called from the reminder tick rather than on a timer of its own: this
    deployment has no clock between requests, so anything periodic has to ride
    something that is already periodic.
    """
    cutoff = utcnow() - timedelta(days=1)
    result = await session.execute(
        sa_delete(DeviceInvite).where(DeviceInvite.expires_at < cutoff)
    )
    await session.commit()
    return result.rowcount or 0
