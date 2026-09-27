"""Who may do what: the phones on the class's list, and a member asking for more.

An access request is «Хочу редактировать» — a member asking the admins for a
higher role — not a request to join; joining is ``join``.

Part of the management surface; what holds across it is in the package
docstring.
"""

from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel

from app.schemas.bundle import RoleName


class ManagedDeviceOut(BaseModel):
    """One phone on the class's list.

    ``owner`` is a display name, never the Telegram id: an admin needs to know
    whose phone this is, and that is the whole of what they need.
    """

    id: int
    device_name: str | None = None
    linked: bool = False
    owner: str | None = None
    role: RoleName | None = None
    revoked: bool = False
    created_at: datetime | None = None
    last_seen_at: datetime | None = None
    linked_at: datetime | None = None


class AccessRequestOut(BaseModel):
    id: int
    who: str
    requested_role: RoleName
    message: str | None = None
    created_at: datetime | None = None


class RequestDecisionIn(BaseModel):
    """The role to grant. Absent means the one that was asked for, which is
    what pressing «Выдать» in the bot does. Ignored when declining."""

    role: RoleName | None = None


class RequestDecisionOut(BaseModel):
    id: int
    status: Literal["approved", "declined"]
    # The role actually granted, or ``null`` for a declined request.
    role: RoleName | None = None
    who: str
