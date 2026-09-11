"""Device tokens for the read-only client API.

Tokens are random 256-bit strings shown to the device once and stored only as a
SHA-256 hash, so a database leak does not hand out working credentials.
"""

from __future__ import annotations

import hashlib
import secrets
import string
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

    A bucket only ever needs to tell two clients apart. Keeping the addresses
    themselves would mean storing personal data to answer a question that does
    not need it, so what lands in the table is a digest.
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


class JoinThrottle:
    """Sliding-window limit on failed join attempts, counted in the database.

    Not in memory. The previous implementation kept a dict and justified it on
    the deployment being "a single uvicorn worker sharing one event loop" — true
    of `uvicorn app.main:app`, false of the Vercel Functions deployment this
    project actually ships to, where each concurrent invocation is a separate
    process with its own empty dict. The limit was therefore never reached, and
    the endpoint it guards hands out a permanent read token for a real class's
    timetable, homework and teacher names.

    Only failures are recorded, so a classroom of pupils joining from one school
    NAT is never blocked by each other's successes.

    Both methods take the caller's session and are awaited inside the request,
    which makes this a couple of indexed queries on a table holding one window's
    worth of failures. That is the price of a limit that is real.
    """

    __slots__ = ("limit", "window")

    def __init__(self, limit: int, window: float) -> None:
        self.limit = limit
        self.window = window

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
        """Counts one failed attempt, and clears out expired ones."""
        from app.models import JoinAttempt

        now = _utcnow()
        session.add(JoinAttempt(client_key=key, created_at=now))

        # Pruned here rather than on a schedule: there is no scheduler in a
        # serverless deployment, and the only moment this table is known to be
        # growing is the moment something is being added to it.
        await session.execute(
            delete(JoinAttempt).where(
                JoinAttempt.created_at <= now - timedelta(seconds=self.window)
            )
        )
        await session.commit()


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` column the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)
