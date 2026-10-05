"""How a request says who it is: the device bearer and the caller's address.

Two shells read these rules — v1's FastAPI dependencies below, and the v2 gate
(``app/rpc/gate.py``), which has a list of header lines and a peer address
rather than a Starlette request. So every rule here is written over those two
plain things first, and the FastAPI dependencies are thin wrappers: one
reading of a bearer and one bucket per caller, whichever version of the API
asked.
"""

from __future__ import annotations

import logging
from collections.abc import Iterable
from datetime import UTC, datetime, timedelta

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import Depends, Header, HTTPException, Request, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.config import get_settings
from app.models import DeviceToken, SchoolClass
from app.security import client_bucket, hash_token

log = logging.getLogger(__name__)

# ``last_seen_at`` is telemetry with day-level resolution at best. Writing it on
# every request turned a read-only API into one write transaction per poll,
# which on SQLite means a write lock taken on the widget's refresh interval.
LAST_SEEN_INTERVAL = timedelta(minutes=15)


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


def header(headers: Iterable[tuple[str, str]], name: str) -> str | None:
    """Every line of header ``name``, joined back into the one list it means.

    RFC 9110 §5.3: several field lines of a comma-separated field mean the same
    as one line with the values joined. Reading only the first line is how a
    proxy that adds its own line rather than extending the caller's let the
    caller choose their own rate-limit bucket. ``None`` when there is no line.
    """
    wanted = name.lower()
    values = [value for key, value in headers if key.lower() == wanted]
    return ", ".join(values) if values else None


def _forwarded_entry(value: str | None, hops: int) -> str | None:
    """The address the outermost *trusted* proxy put into a forwarding header.

    Entries are appended left to right, so with ``hops`` proxies in front the
    one they added is ``hops`` places from the right. Everything to its left is
    whatever the caller chose to send, and is ignored. A header with fewer
    entries than there are proxies cannot have come through them, so it yields
    nothing rather than the closest match.
    """
    if not value:
        return None
    parts = [part.strip() for part in value.split(",")]
    parts = [part for part in parts if part]
    if len(parts) < hops:
        return None
    return parts[-hops]


def caller_bucket(
    headers: Iterable[tuple[str, str]], peer: str | None, *, scope: str = ""
) -> str:
    """Identifies the caller for rate-limiting purposes.

    Over the header lines and the peer address rather than a Starlette request,
    so that v1's endpoints and v2's gate measure one caller by one rule — and so
    that a caller alternating the two versions draws on one budget
    (``docs/specs/2026-10-05-server-v2-design.md``, decision 11).

    Reading the leftmost ``X-Forwarded-For`` entry is the usual advice and it is
    exactly wrong: that entry is whatever the client sent, so an attacker sets
    it themselves and lands in a fresh bucket on every request, defeating the
    limit they are being measured by. So no forwarding header is believed unless
    the deployment says how many proxies are in front of it.

    On Vercel the platform writes ``x-vercel-forwarded-for`` itself, replacing
    any copy the client sent, so that one is trustworthy with no configuration —
    and it has to be used, because there every request arrives from the same
    internal address and the peer would put the whole internet in one bucket.

    Otherwise the peer address is used, which is right when the app is run
    directly and, for anything in between, is what ``TRUSTED_PROXY_HOPS`` is for.

    @param scope keeps two families of failures apart — ten wrong diary
        passwords must not spend a phone's thirty join attempts. It is mixed
        into the address **before** the digest and never written in front of
        it: ``JoinAttempt.client_key`` is ``VARCHAR(64)`` and a SHA-256 hex
        digest is exactly 64 characters, so a prefix is a value Postgres
        refuses outright — ``value too long for type character varying(64)``,
        raised out of the insert that was supposed to *record* a failed
        sign-in. SQLite ignores the width, which is why the whole of this was
        green here and 500 there.
    """
    lines = list(headers)
    settings = get_settings()

    address: str | None = None
    if settings.behind_vercel:
        address = _forwarded_entry(header(lines, "x-vercel-forwarded-for"), 1)

    if address is None:
        hops = settings.trusted_proxy_hops
        if hops > 0:
            address = _forwarded_entry(header(lines, "x-forwarded-for"), hops)

    if address is None:
        address = peer or "unknown"

    return client_bucket(f"{scope}{address}")


def peer_host(client_address: str | None) -> str | None:
    """The host of a peer that ``connectrpc`` reports as ``"host:port"``.

    Its ``RequestContext.client_address`` is ``f"{host}:{port}"`` whenever the
    server knows the client, an IPv6 host included and without brackets
    (``"::1:52144"``), and ``None`` otherwise. The port is a different number
    on every connection, so a bucket keyed on it would give each connection a
    budget of its own. It cannot be told apart from the address by looking —
    ``"::1:4321"`` is itself a valid IPv6 address — which is why this strips by
    the format the library writes rather than by parsing the result.
    """
    if not client_address:
        return None
    host, separator, _port = client_address.rpartition(":")
    return host if separator else client_address


def request_bucket(request: Request, *, scope: str = "") -> str:
    """:func:`caller_bucket` for a v1 endpoint, which holds a Starlette request."""
    return caller_bucket(
        request.headers.items(), request.client.host if request.client else None, scope=scope
    )


def bearer(authorization: str | None) -> str | None:
    """The token of ``Authorization: Bearer <token>``, or ``None``.

    The scheme is matched without regard to case, as RFC 9110 has it; anything
    that is not a bearer, or a bearer with nothing after it, is no credential.
    """
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        return None
    return token


async def find_device(session: AsyncSession, token: str) -> DeviceToken | None:
    """The live device behind a bearer token, or ``None`` for an unknown or
    revoked one. Looked up by hash: the token itself is never stored."""
    return await session.scalar(
        select(DeviceToken).where(
            DeviceToken.token_hash == hash_token(token),
            DeviceToken.revoked.is_(False),
        )
    )


async def touch_last_seen(session: AsyncSession, device: DeviceToken) -> None:
    """Record that ``device`` phoned home, at most every fifteen minutes. Commits.

    Kept on every read on purpose — v1's and v2's alike — because it is
    telemetry no client observes (the server-v2 design, decision 10).
    """
    now = _utcnow()
    seen = device.last_seen_at
    if seen is not None and timedelta(0) <= now - seen < LAST_SEEN_INTERVAL:
        return

    device.last_seen_at = now
    try:
        await session.commit()
    except SQLAlchemyError:
        # Recording that a device phoned home is not worth failing the read the
        # widget actually asked for.
        log.warning("could not record last_seen_at for device %s", device.id, exc_info=True)
        await session.rollback()
        # A rollback expires every instance in the session, including the class
        # this request is about to serialise - and an expired instance in an
        # async session reloads itself lazily, which raises MissingGreenlet from
        # whatever attribute the endpoint touches next. So the failure this
        # branch exists to absorb used to turn into a 500 anyway. Refreshing
        # here brings the rows back inside the greenlet that can do the I/O.
        #
        # And the refresh itself may not raise. It is a SELECT down the
        # connection the commit just lost, so when the commit failed it
        # usually fails too - and this is the last statement of a branch whose
        # entire purpose is that a failed write does not fail the read the
        # widget asked for. A refresh that fails leaves `device` expired, which
        # is where it was before any of this; raising would throw away the read
        # as well. `services/diary.py:_refresh_quietly` is the same call with
        # the same guard.
        try:
            await session.refresh(device)
        except SQLAlchemyError:
            log.warning("could not refresh device %s after a rollback", device.id, exc_info=True)


@inject
async def current_device(
    authorization: str = Header(default=""),
    *,
    session: FromDishka[AsyncSession],
) -> DeviceToken:
    """The device behind the bearer token, or 401.

    Separate from :func:`current_class` because the linking endpoints act on
    the device row itself, and the write endpoints derive their permission
    from ``device.telegram_id``. FastAPI caches a dependency's result for the
    request, so an endpoint that asks for both the device and the class still
    costs one token lookup.
    """
    token = bearer(authorization)
    if token is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    device = await find_device(session, token)
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return device


@inject
async def current_class(
    device: DeviceToken = Depends(current_device),
    *,
    session: FromDishka[AsyncSession],
) -> SchoolClass:
    await touch_last_seen(session, device)
    # After the write, not before: a rollback inside it expires everything the
    # session holds, and this is the object the endpoint is about to read.
    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Class no longer exists")
    return school_class
