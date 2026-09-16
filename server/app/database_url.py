"""Making a pasted connection string work.

Managed Postgres providers hand out a libpq URL: the one the ``psql`` client
wants. asyncpg is not libpq and rejects several of its parameters outright, so
pasting a Neon or Supabase string verbatim fails at startup with an error that
names a query parameter and explains nothing.

Rather than documenting "edit the URL by hand before pasting it", which is a
step everyone gets wrong once, this module rewrites it.
"""

from __future__ import annotations

import ssl
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

_SSL_REQUIRED = {"require"}
_SSL_VERIFIED = {"verify-ca", "verify-full"}


def _verifying_context(rootcert: str | None, *, check_hostname: bool) -> ssl.SSLContext:
    """An SSL context that actually verifies, the way libpq's verify-* modes do.

    A root certificate that cannot be loaded is a configuration error and is
    reported as one, at import time, naming the parameter - not as an
    ``SSLError`` deep inside the first connection attempt.
    """
    try:
        context = ssl.create_default_context(cafile=rootcert or None)
    except (OSError, ssl.SSLError) as failure:
        raise ValueError(
            f"DATABASE_URL: sslrootcert={rootcert!r} could not be loaded: {failure}"
        ) from failure
    context.check_hostname = check_hostname
    context.verify_mode = ssl.CERT_REQUIRED
    return context


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

    SQLite and any non-asyncpg driver are returned untouched. A URL that
    already says ``postgresql+asyncpg://`` is *not* left alone: that is the
    exact form docs/deploy.md tells the operator to set, and it used to skip
    every rewrite above - so the documented configuration got the pooler's
    prepared-statement failures and a raw ``sslmode`` handed to a driver that
    does not know the word.
    """
    if not raw:
        return raw, {}

    parts = urlsplit(raw)
    if parts.scheme not in {"postgresql", "postgres", "postgresql+asyncpg"}:
        return raw, {}

    query = dict(parse_qsl(parts.query, keep_blank_values=True))
    connect_args: dict[str, Any] = {}

    sslmode = query.get("sslmode", "").lower()
    if sslmode in _SSL_VERIFIED:
        # asyncpg's "require" encrypts but trusts any certificate unless a
        # root.crt happens to sit in the home directory. An operator who wrote
        # verify-ca or verify-full asked for more than that, so they get a
        # real context: the system roots, or the file they named.
        connect_args["ssl"] = _verifying_context(
            query.get("sslrootcert"), check_hostname=sslmode == "verify-full"
        )
    elif sslmode in _SSL_REQUIRED:
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
    host = host.lower()
    # Neon: "ep-...-pooler.<region>.aws.neon.tech". Supabase: the transaction
    # pooler is "aws-0-<region>.pooler.supabase.com" - "pooler." in the middle,
    # not at the front, which the prefix test alone did not see.
    return "-pooler" in host or host.startswith("pooler.") or ".pooler." in host


def describe(raw: str) -> str:
    """A form safe to log or print: no password, ever."""
    if "://" not in raw:
        return raw
    scheme, rest = raw.split("://", 1)
    if "@" in rest:
        # From the *last* "@", not the first. A generated password may contain
        # one unescaped - libpq and asyncpg both read the host as whatever
        # follows the final "@" - and splitting at the first put the tail of
        # that password into the line this function exists to make printable.
        rest = rest.rsplit("@", 1)[1]
    return f"{scheme}://{rest.split('?', 1)[0]}"
