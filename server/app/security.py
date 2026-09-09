"""Device tokens for the read-only client API.

Tokens are random 256-bit strings shown to the device once and stored only as a
SHA-256 hash, so a database leak does not hand out working credentials.
"""

from __future__ import annotations

import hashlib
import secrets
import string

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
