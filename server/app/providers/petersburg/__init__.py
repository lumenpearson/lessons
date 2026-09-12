"""The Petersburg electronic diary, behind one door.

Nothing outside this package knows the upstream's URLs, its parameter names,
its cookie or its field spellings. What leaves here are the models in
:mod:`app.providers.petersburg.models` and the errors in
:mod:`app.providers.petersburg.exceptions`.
"""

from app.providers.petersburg.client import PetersburgClient, close_client
from app.providers.petersburg.exceptions import (
    BadCredentials,
    PetersburgError,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)

__all__ = [
    "BadCredentials",
    "PetersburgClient",
    "PetersburgError",
    "SessionExpired",
    "UnexpectedResponse",
    "UpstreamUnavailable",
    "close_client",
]
