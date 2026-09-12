"""FastAPI dependencies: device-token authentication."""

from __future__ import annotations

import logging
from datetime import UTC, datetime, timedelta

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.models import DeviceToken, SchoolClass
from app.security import hash_token

log = logging.getLogger(__name__)

# ``last_seen_at`` is telemetry with day-level resolution at best. Writing it on
# every request turned a read-only API into one write transaction per poll,
# which on SQLite means a write lock taken on the widget's refresh interval.
LAST_SEEN_INTERVAL = timedelta(minutes=15)


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


async def _touch_last_seen(session: AsyncSession, device: DeviceToken) -> None:
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
        await session.refresh(device)


async def current_device(
    authorization: str = Header(default=""),
    session: AsyncSession = Depends(get_session),
) -> DeviceToken:
    """The device behind the bearer token, or 401.

    Separate from :func:`current_class` because the linking endpoints act on
    the device row itself, and the write endpoints derive their permission
    from ``device.telegram_id``. FastAPI caches a dependency's result for the
    request, so an endpoint that asks for both the device and the class still
    costs one token lookup.
    """
    scheme, _, token = authorization.partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Missing bearer token",
            headers={"WWW-Authenticate": "Bearer"},
        )

    device = await session.scalar(
        select(DeviceToken).where(
            DeviceToken.token_hash == hash_token(token),
            DeviceToken.revoked.is_(False),
        )
    )
    if device is None:
        raise HTTPException(
            status_code=status.HTTP_401_UNAUTHORIZED,
            detail="Invalid token",
            headers={"WWW-Authenticate": "Bearer"},
        )
    return device


async def current_class(
    device: DeviceToken = Depends(current_device),
    session: AsyncSession = Depends(get_session),
) -> SchoolClass:
    await _touch_last_seen(session, device)
    # After the write, not before: a rollback inside it expires everything the
    # session holds, and this is the object the endpoint is about to read.
    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Class no longer exists")
    return school_class
