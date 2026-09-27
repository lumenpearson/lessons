"""Getting a phone into a class, and linking it to an account.

``POST /join`` takes the class code or a personal connect code in one field;
``/me`` and ``/me/unlink`` are what the phone asks afterwards.
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Literal

from pydantic import BaseModel, Field, field_validator

from app.schemas._text import _strip_control_chars
from app.schemas.bundle import RoleName

if TYPE_CHECKING:
    from app.providers.diary.registry import Binding


#: Mirrors ``JoinRequest.code``'s own ``min_length``; see ``_clean_code``.
_MIN_CODE_LENGTH = 4


class JoinRequest(BaseModel):
    code: str = Field(min_length=4, max_length=16)
    device_name: str | None = Field(default=None, max_length=120)

    @field_validator("code")
    @classmethod
    def _clean_code(cls, value: str) -> str:
        """Sanitise the code, and reject one that sanitises away to nothing.

        This used to share ``_sanitise`` with ``device_name`` and return
        ``cleaned or None``. Pydantic does not re-check an after-validator's
        return against the field's annotation, so a code of four spaces — which
        passes ``min_length=4`` — arrived at the handler as ``None`` and the
        ``.strip()`` there raised ``AttributeError``. That is a 500 out of a
        request the schema exists to reject, on an unauthenticated endpoint.
        """
        cleaned = _strip_control_chars(value).strip()
        if len(cleaned) < _MIN_CODE_LENGTH:
            raise ValueError("code must contain at least 4 usable characters")
        return cleaned

    @field_validator("device_name")
    @classmethod
    def _clean_device_name(cls, value: str | None) -> str | None:
        """Optional, so sanitising it away to nothing simply means absent."""
        if value is None:
            return None
        cleaned = _strip_control_chars(value).strip()
        return cleaned or None


class DiaryBindingOut(BaseModel):
    """Which diary a class reads, so a phone that joined it can sign in there
    without searching for its school.

    Defined here rather than in the diary block below because
    :class:`JoinResponse` carries it. Built only through ``of`` from
    `registry.binding`, so a class bound to a region since dropped from the
    allow-list, one that takes no password, or a «Сетевой город» binding with
    no school all read as no binding at all — the phone is never pointed at a
    diary this server would refuse.
    """

    provider: Literal["petersburg", "netschool"]
    #: The allow-list key, which is the region catalog's key. ``None`` for
    #: Petersburg, which is one city's server.
    region: str | None = None
    #: The upstream's own school id («scid»), which the phone signs in with.
    school_id: int | None = None
    school_name: str | None = None

    @classmethod
    def of(cls, bound: Binding | None) -> DiaryBindingOut | None:
        if bound is None:
            return None
        return cls(
            provider=bound.provider.key,
            region=bound.region,
            school_id=bound.school_id,
            school_name=bound.school_name,
        )


class JoinResponse(BaseModel):
    token: str
    class_id: int
    class_name: str
    school: str | None = None
    timezone: str
    #: The class's diary binding, or ``None`` when it has none this server can
    #: reach. Additive, so an older phone ignores it and ``API_VERSION`` holds.
    diary: DiaryBindingOut | None = None


class MeOut(BaseModel):
    device_name: str | None = None
    linked: bool
    role: RoleName | None = None
    can_edit: bool
    # Only while unlinked. A linked device has no code, and a code that stayed
    # on a linked row could be typed by somebody else and re-home the phone.
    link_code: str | None = None
    bot_deep_link: str | None = None


class UnlinkOut(BaseModel):
    linked: bool
