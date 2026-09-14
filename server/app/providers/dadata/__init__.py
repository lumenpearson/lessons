"""The school directory, behind one door.

Nothing outside this package knows DaData's URL, its parameter names or the
shape of a ЕГРЮЛ row. What leaves here are
:class:`~app.providers.dadata.models.School`,
:class:`~app.providers.dadata.models.SchoolPage` and the errors in
:mod:`app.providers.dadata.exceptions` — so replacing the directory with
another one is a change to this package and to nothing else.
"""

from app.providers.dadata.client import (
    MAX_SUGGESTIONS,
    close_client,
    configured,
    suggest_schools,
)
from app.providers.dadata.exceptions import (
    DirectoryError,
    NotConfigured,
    QuotaExceeded,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.dadata.mapper import to_school, to_schools
from app.providers.dadata.models import School, SchoolPage

__all__ = [
    "MAX_SUGGESTIONS",
    "DirectoryError",
    "NotConfigured",
    "QuotaExceeded",
    "School",
    "SchoolPage",
    "UnexpectedResponse",
    "UpstreamUnavailable",
    "close_client",
    "configured",
    "suggest_schools",
    "to_school",
    "to_schools",
]
