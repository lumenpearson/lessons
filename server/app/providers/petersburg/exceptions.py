"""Petersburg's error names, which are now the shared diary errors.

This module pre-dates :mod:`app.providers.diary.errors`. Its names are kept as
aliases of the very classes defined there — ``PetersburgError is DiaryError`` —
so every ``from app.providers.petersburg import BadCredentials`` still works and
``isinstance`` holds whether a failure was raised under the old spelling or the
new one. New code should import from :mod:`app.providers.diary.errors`.
"""

from __future__ import annotations

from app.providers.diary.errors import (
    BadCredentials,
    DiaryError,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)

#: The old base name. The same class as :class:`app.providers.diary.errors.DiaryError`.
PetersburgError = DiaryError

__all__ = [
    "BadCredentials",
    "PetersburgError",
    "SessionExpired",
    "UnexpectedResponse",
    "UpstreamUnavailable",
]
