"""Device tokens for the read-only client API.

Tokens are random 256-bit strings shown to the device once and stored only as a
SHA-256 hash, so a database leak does not hand out working credentials.
"""

from __future__ import annotations

import hashlib
import secrets
import string
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from sqlalchemy import delete, func, select
from sqlalchemy.ext.asyncio import AsyncSession

_CODE_ALPHABET = string.ascii_uppercase + string.digits
# Characters that are easy to misread when someone types a join code from a
# whiteboard or a chat message.
_AMBIGUOUS = set("O0I1")
_SAFE_CODE_ALPHABET = "".join(c for c in _CODE_ALPHABET if c not in _AMBIGUOUS)


def new_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


#: Length of a newly issued join code.
#:
#: Eight, not six. Every guess at this endpoint is checked against *every*
#: class at once, so the search space is the whole thing an attacker has to
#: beat: six characters of a 32-symbol alphabet is 2**30, which a working rate
#: limit makes slow and an absent one made trivial. Eight is 2**40 — a thousand
#: times more — and is still short enough to read off a whiteboard.
#:
#: Existing codes keep working: lookup is an exact match on whatever is stored,
#: and the request schema accepts 4 to 16 characters.
JOIN_CODE_LENGTH = 8


def new_join_code(length: int = JOIN_CODE_LENGTH) -> str:
    return "".join(secrets.choice(_SAFE_CODE_ALPHABET) for _ in range(length))


def client_bucket(raw: str) -> str:
    """Hashes a client address into the key the throttle counts against.

    A bucket only ever needs to tell two clients apart, so the address itself
    never has to be written down: what lands in the table, and in anything that
    dumps the table, is a digest.

    It is **not** anonymisation, and it would be worse than useless to record
    it as such. The whole IPv4 space is four billion candidates — a laptop
    walks it against an unkeyed SHA-256 in seconds — so anyone holding both the
    table and the intent can recover the addresses. Making that untrue needs a
    key the attacker does not have (an HMAC under a server secret), which this
    does not have, because a throttle that stops working when a secret is unset
    is a worse failure than the one it would fix. What the digest buys is that
    the address is not sitting in plain sight; treat the column as personal
    data anyway.
    """
    return hashlib.sha256(raw.encode("utf-8")).hexdigest()


def normalise_phone(raw: str) -> str:
    """Reduce a phone number to digits, with Russian 8XXX treated as 7XXX.

    Telegram hands out numbers in E.164 without the plus, while people type them
    in half a dozen formats. Comparing normalised digits is the only thing that
    reliably matches an invite to the person who shows up.
    """
    digits = "".join(c for c in raw if c.isdigit())
    if len(digits) == 11 and digits.startswith("8"):
        digits = "7" + digits[1:]
    return digits


@dataclass(frozen=True)
class Admission:
    """What :meth:`JoinThrottle.admit` decided.

    ``retry_after`` is ``None`` when the attempt may go ahead, and then
    ``attempt_id`` is the row counting it; refused, it is the seconds until the
    caller may try again and there is no row.
    """

    attempt_id: int | None
    retry_after: float | None


class JoinThrottle:
    """Sliding-window limit on attempts per caller, counted in the database.

    Not in memory. The previous implementation kept a dict and justified it on
    the deployment being "a single uvicorn worker sharing one event loop" — true
    of `uvicorn app.main:app`, false of the Vercel Functions deployment this
    project actually ships to, where each concurrent invocation is a separate
    process with its own empty dict. The limit was therefore never reached, and
    the endpoint it guards hands out a permanent read token for a real class's
    timetable, homework and teacher names.

    `/join` and the diary sign-in record only failures, so a classroom of
    pupils joining from one school NAT is never blocked by each other's
    successes. The school directory records every call (:meth:`record`),
    because there each call spends a request from an allowance somebody else
    pays for, whether or not it finds anything.

    **Every instance must use the same window.** They share one table, and
    each recorded attempt prunes the *whole* table to its own window, not just
    its own key's rows — so a limiter with a longer window than the others
    would have its history cut to theirs without anything saying so.
    ``test_every_throttle_on_the_attempts_table_uses_one_window`` holds it.

    Every method takes the caller's session and is awaited inside the request,
    which makes this a couple of indexed queries on a table holding one window's
    worth of attempts. That is the price of a limit that is real.

    **A door counts through** :meth:`admit`, which writes the attempt first and
    counts after, never through :meth:`blocked_for` followed by :meth:`record`.
    Check-then-record let every request of a concurrent burst read a count from
    before any of them was written: two hundred wrong passwords sent at once
    from one address all reached the diary inside one window whose limit is
    ten, and the directory's twenty searches were as many as a burst could fit.
    Written first, the n-th attempt to commit sees at least n rows, so no more
    than ``limit`` of them are let through, however they overlap. A door that
    counts only failures hands the row back (:meth:`forgive`) once it knows the
    attempt was not one.
    """

    __slots__ = ("limit", "window")

    def __init__(self, limit: int, window: float) -> None:
        self.limit = limit
        self.window = window

    async def admit(self, session: AsyncSession, key: str) -> Admission:
        """Count one attempt by [key] and say whether it may go ahead. Commits.

        Let through, the attempt stays counted until :meth:`forgive` takes it
        back. Refused, it is taken back at once: a refusal asked nothing of
        anybody, and counting it would keep a caller who keeps asking locked out
        for as long as they ask rather than for the window.
        """
        from app.models import JoinAttempt

        now = _utcnow()
        attempt = JoinAttempt(client_key=key, created_at=now)
        session.add(attempt)
        await session.flush()
        attempt_id = attempt.id
        await self._prune(session, now)
        await session.commit()

        # After the commit, in a statement of its own: under READ COMMITTED it
        # sees every attempt committed before it, this one included.
        counted = await session.scalar(
            select(func.count())
            .select_from(JoinAttempt)
            .where(
                JoinAttempt.client_key == key,
                JoinAttempt.created_at > now - timedelta(seconds=self.window),
            )
        )
        if (counted or 0) <= self.limit:
            return Admission(attempt_id=attempt_id, retry_after=None)

        await self._drop(session, attempt_id)
        # Never 0: the attempts that filled the window may all be in flight and
        # about to be forgiven, and a client told to wait no time asks at once.
        return Admission(
            attempt_id=None, retry_after=max(await self.blocked_for(session, key) or 0.0, 1.0)
        )

    async def forgive(self, session: AsyncSession, admission: Admission) -> None:
        """Take back an attempt :meth:`admit` counted, because it turned out not
        to be one this door counts — a success, or nothing having judged it.
        Commits."""
        if admission.attempt_id is not None:
            await self._drop(session, admission.attempt_id)

    async def _drop(self, session: AsyncSession, attempt_id: int) -> None:
        from app.models import JoinAttempt

        await session.execute(delete(JoinAttempt).where(JoinAttempt.id == attempt_id))
        await session.commit()

    async def _prune(self, session: AsyncSession, now: datetime) -> None:
        from app.models import JoinAttempt

        # Pruned here rather than on a schedule: there is no scheduler in a
        # serverless deployment, and the only moment this table is known to be
        # growing is the moment something is being added to it.
        await session.execute(
            delete(JoinAttempt).where(
                JoinAttempt.created_at <= now - timedelta(seconds=self.window)
            )
        )

    async def blocked_for(self, session: AsyncSession, key: str) -> float | None:
        """Seconds until [key] may try again, or ``None`` if it may try now."""
        from app.models import JoinAttempt

        now = _utcnow()
        cutoff = now - timedelta(seconds=self.window)

        oldest = await session.scalar(
            select(func.min(JoinAttempt.created_at)).where(
                JoinAttempt.client_key == key,
                JoinAttempt.created_at > cutoff,
            )
        )
        if oldest is None:
            return None

        attempts = await session.scalar(
            select(func.count())
            .select_from(JoinAttempt)
            .where(
                JoinAttempt.client_key == key,
                JoinAttempt.created_at > cutoff,
            )
        )
        if (attempts or 0) < self.limit:
            return None

        remaining = (oldest + timedelta(seconds=self.window) - now).total_seconds()
        return max(remaining, 0.0)

    async def record_failure(self, session: AsyncSession, key: str) -> None:
        """Counts one failed attempt, and clears out expired ones.

        A name for what `/join` and the diary sign-in count, which is only what
        went wrong. It is the same row as :meth:`record`'s.
        """
        await self.record(session, key)

    async def record(self, session: AsyncSession, key: str) -> None:
        """Counts one attempt, whatever came of it, and clears out expired ones.

        For a caller that counts every call rather than only the failures —
        the school directory, where a search that finds its school has spent
        the same upstream request as one that finds nothing. Commits.
        """
        from app.models import JoinAttempt

        now = _utcnow()
        session.add(JoinAttempt(client_key=key, created_at=now))
        await self._prune(session, now)
        await session.commit()


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` column the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)
