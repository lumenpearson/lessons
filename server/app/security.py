"""Device tokens for the read-only client API.

Tokens are random 256-bit strings shown to the device once and stored only as a
SHA-256 hash, so a database leak does not hand out working credentials.
"""

from __future__ import annotations

import hashlib
import secrets
import string
import time

_CODE_ALPHABET = string.ascii_uppercase + string.digits
# Characters that are easy to misread when someone types a join code from a
# whiteboard or a chat message.
_AMBIGUOUS = set("O0I1")
_SAFE_CODE_ALPHABET = "".join(c for c in _CODE_ALPHABET if c not in _AMBIGUOUS)


def new_token() -> str:
    return secrets.token_urlsafe(32)


def hash_token(token: str) -> str:
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_join_code(length: int = 6) -> str:
    return "".join(secrets.choice(_SAFE_CODE_ALPHABET) for _ in range(length))


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


class RateLimiter:
    """Sliding-window limiter keyed by client address, held in this process.

    The deployment is a single uvicorn worker sharing one event loop, so an
    in-memory dict is a correct shared store and Redis would be a dependency
    with no second reader. ``record_failure`` and ``blocked_for`` contain no
    ``await``, so they are atomic with respect to other tasks.

    Keys are pruned lazily and the table is capped, so a client cycling through
    addresses cannot grow it without bound.
    """

    __slots__ = ("limit", "window", "max_keys", "_hits")

    def __init__(self, limit: int, window: float, max_keys: int = 10_000) -> None:
        self.limit = limit
        self.window = window
        self.max_keys = max_keys
        self._hits: dict[str, list[float]] = {}

    def _fresh(self, key: str, now: float) -> list[float]:
        cutoff = now - self.window
        stamps = [stamp for stamp in self._hits.get(key, ()) if stamp > cutoff]
        if stamps:
            self._hits[key] = stamps
        else:
            self._hits.pop(key, None)
        return stamps

    def blocked_for(self, key: str, now: float | None = None) -> float | None:
        """Seconds the caller must wait, or ``None`` if it may proceed."""
        now = time.monotonic() if now is None else now
        stamps = self._fresh(key, now)
        if len(stamps) < self.limit:
            return None
        return max(stamps[0] + self.window - now, 0.0)

    def record_failure(self, key: str, now: float | None = None) -> None:
        now = time.monotonic() if now is None else now
        self._prune(now)
        stamps = self._fresh(key, now)
        # Cap the per-key list: a blocked caller must not be able to grow it.
        if len(stamps) <= self.limit:
            stamps.append(now)
            self._hits[key] = stamps

    def _prune(self, now: float) -> None:
        if len(self._hits) < self.max_keys:
            return
        cutoff = now - self.window
        self._hits = {k: v for k, v in self._hits.items() if v and v[-1] > cutoff}
        if len(self._hits) >= self.max_keys:
            # Still full of live entries: keep the most recently seen half.
            ordered = sorted(self._hits.items(), key=lambda kv: kv[1][-1], reverse=True)
            self._hits = dict(ordered[: self.max_keys // 2])

    def reset(self) -> None:
        self._hits.clear()
