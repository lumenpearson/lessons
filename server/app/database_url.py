"""Making a pasted connection string work.

Managed Postgres providers hand out a libpq URL: the one the ``psql`` client
wants. asyncpg is not libpq and rejects several of its parameters outright, so
pasting a Neon or Supabase string verbatim fails at startup with an error that
names a query parameter and explains nothing.

Rather than documenting "edit the URL by hand before pasting it", which is a
step everyone gets wrong once, this module rewrites it.
"""

from __future__ import annotations

from typing import Any
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

# libpq understands these; asyncpg raises TypeError on every one of them.
# sslmode is translated rather than dropped, since it carries real intent.
_LIBPQ_ONLY = {
    "sslmode",
    "channel_binding",
    "target_session_attrs",
    "connect_timeout",
    "application_name",
    "options",
    "gssencmode",
    "krbsrvname",
    "sslrootcert",
    "sslcert",
    "sslkey",
}

_SSL_REQUIRED = {"require", "verify-ca", "verify-full"}


def normalise_database_url(raw: str) -> tuple[str, dict[str, Any]]:
    """Return a URL SQLAlchemy can open, plus the connect args it needs.

    Handles, in order:

    * ``postgresql://`` and ``postgres://`` get the ``+asyncpg`` driver, because
      the sync driver would block the event loop even where it did connect.
    * ``sslmode`` becomes asyncpg's ``ssl`` connect arg. Dropping it silently
      would downgrade a connection the provider expects to be encrypted.
    * Other libpq-only parameters are removed.
    * A pooled endpoint disables prepared-statement caching. This is the subtle
      one: PgBouncer in transaction mode hands each transaction whichever
      backend is free, while asyncpg prepares statements on the connection it
      first saw. The mismatch surfaces later as "prepared statement _asyncpg_
      already exists" under load, not at startup, which makes it a genuinely
      nasty thing to debug in production.

    SQLite and anything already carrying a driver are returned untouched.
    """
    if not raw or "+" in raw.split("://", 1)[0]:
        return raw, {}

    parts = urlsplit(raw)
    if parts.scheme not in {"postgresql", "postgres"}:
        return raw, {}

    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    connect_args: dict[str, Any] = {}

    sslmode = query.get("sslmode", "").lower()
    if sslmode in _SSL_REQUIRED:
        connect_args["ssl"] = "require"
    elif sslmode in {"disable", "allow"}:
        connect_args["ssl"] = False

    remaining = {k: v for k, v in query.items() if k not in _LIBPQ_ONLY}

    if is_pooled(parts.hostname or ""):
        # asyncpg's own cache, and SQLAlchemy's separate one on top of it.
        connect_args["statement_cache_size"] = 0
        remaining["prepared_statement_cache_size"] = "0"

    url = urlunsplit(
        ("postgresql+asyncpg", parts.netloc, parts.path, urlencode(remaining), parts.fragment)
    )
    return url, connect_args


def is_pooled(host: str) -> bool:
    """Whether this host is a connection pooler rather than Postgres itself.

    Name-based because the providers say so in the hostname and offer no other
    signal. A false negative only costs the caching that a direct connection can
    safely keep, so erring toward "not pooled" is the safe direction.
    """
    return "-pooler" in host or host.startswith("pooler.")


def describe(raw: str) -> str:
    """A form safe to log or print: no password, ever."""
    if "://" not in raw:
        return raw
    scheme, rest = raw.split("://", 1)
    if "@" in rest:
        rest = rest.split("@", 1)[1]
    return f"{scheme}://{rest.split('?', 1)[0]}"
