"""FastAPI dependencies: device-token authentication."""

from __future__ import annotations

from datetime import datetime

from fastapi import Depends, Header, HTTPException, status
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.db import get_session
from app.models import DeviceToken, SchoolClass
from app.security import hash_token


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
        raise HTTPException(status_code=status.HTTP_401_UNAUTHORIZED, detail="Invalid token")

    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Class no longer exists")

    device.last_seen_at = datetime.utcnow()
    await session.commit()
    return school_class
