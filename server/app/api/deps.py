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


async def current_class(
    authorization: str = Header(default=""),
    session: AsyncSession = Depends(get_session),
) -> SchoolClass:
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

    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Class no longer exists")

    await _touch_last_seen(session, device)
    return school_class
