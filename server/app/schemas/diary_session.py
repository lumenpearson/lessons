"""Signing in to the electronic diary, and what this server's diary can do.

A password sign-in (``DiaryLoginIn``) and a session the phone opened with the
diary itself (``DiarySessionBody``) both end in our own bearer. What is read
through that bearer, and the corrections laid over it, are in ``diary``; what
that module says about ``of`` holds for ``DiaryStudentOut`` here too.
"""

from __future__ import annotations

import re
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field, field_validator

from app.providers.diary.http import cookie_value_ok, header_value_ok
from app.schemas._text import _strip_control_chars


class DiaryLoginIn(BaseModel):
    """Credentials, used once and never stored.

    ``login`` rather than ``email`` because the upstream accepts more than one
    kind of identifier and calling it an email would be a promise this project
    cannot keep.
    """

    login: str = Field(min_length=3, max_length=200)
    password: str = Field(min_length=1, max_length=200)
    #: Which diary. Absent means Petersburg, so an older phone that sends only a
    #: login and a password still signs in there. For «Сетевой город» the region
    #: is a key into the allow-list and the school is the upstream's own id;
    #: both are validated in the route before any upstream call.
    provider: str | None = Field(default=None, max_length=32)
    region: str | None = Field(default=None, max_length=32)
    school_id: int | None = Field(default=None, ge=1, le=2_000_000_000)

    @field_validator("login")
    @classmethod
    def _clean_login(cls, value: str) -> str:
        return _clean_login_value(value)


def _clean_login_value(value: str) -> str:
    """A login as the upstream will see it: printable, trimmed, three or more.

    Shared by the password sign-in and the registration of a phone's session,
    because both write it to the same column and «Вход выполнен» prints it
    from there — and the phone cleans it character for character the same way
    (`DiaryLogin.clean`), so a login pasted with an invisible character is not
    a wrong password on one side only. It keys nothing: corrections are filed
    under the child (`services/diary_corrections.child_scope`).
    """
    cleaned = _strip_control_chars(value).strip()
    if len(cleaned) < 3:
        raise ValueError("login must contain at least 3 usable characters")
    return cleaned


class DiaryLoginOut(BaseModel):
    token: str
    login: str


class DiaryStudentOut(BaseModel):
    id: int
    first_name: str
    last_name: str
    middle_name: str | None = None
    full_name: str
    school: str | None = None
    class_name: str | None = None

    @classmethod
    def of(cls, student) -> DiaryStudentOut:
        # Deliberately without ``education_id`` and ``group_id``: they are the
        # upstream's handles, the server resolves them from the student id on
        # every request, and a client that learned them would be a client that
        # could be pointed at somebody else's child.
        return cls(
            id=student.id,
            first_name=student.first_name,
            last_name=student.last_name,
            middle_name=student.middle_name,
            full_name=student.full_name,
            school=student.school,
            class_name=student.class_name,
        )


#: Three dot-separated base64url parts, the last possibly empty: the shape of
#: the ``X-JWT-Token`` Petersburg issues.
_JWT = re.compile(r"[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]*")


#: An allow-list key's shape. Whether it is *in* the allow-list is the route's
#: question, asked before any upstream call.
_REGION_KEY = r"^[a-z0-9-]{2,32}$"


class PetersburgCredentialIn(BaseModel):
    """The session Petersburg handed the phone: the ``X-JWT-Token`` cookie, or
    ``data.token`` when the answer carried no cookie."""

    model_config = ConfigDict(extra="forbid")

    token: str = Field(min_length=16, max_length=4096, repr=False)

    @field_validator("token")
    @classmethod
    def _a_jwt_that_can_be_a_cookie(cls, value: str) -> str:
        # Sent back upstream as a cookie on every read, so it must be one
        # cookie value and nothing more; `_raw` checks the same again.
        if not _JWT.fullmatch(value) or not cookie_value_ok(value):
            raise ValueError("token must be a JWT")
        return value


class NetSchoolCookiesIn(BaseModel):
    """The two session cookies «Сетевой город» sets, and no other — an unknown
    name is refused rather than carried, since it would be sent on every read."""

    model_config = ConfigDict(extra="forbid")

    NSSESSIONID: str = Field(min_length=1, max_length=128, repr=False)
    ESRNSec: str | None = Field(default=None, min_length=1, max_length=2048, repr=False)

    @field_validator("NSSESSIONID", "ESRNSec")
    @classmethod
    def _one_cookie_value(cls, value: str | None) -> str | None:
        # A `;` here would smuggle a second cookie onto every read.
        if value is not None and not cookie_value_ok(value):
            raise ValueError("not a cookie value")
        return value


class NetSchoolCredentialIn(BaseModel):
    """What the phone's «Сетевой город» sign-in ended holding."""

    model_config = ConfigDict(extra="forbid")

    #: The bearer, sent in the ``at`` header, so it must be one header value.
    at: str = Field(min_length=8, max_length=512, repr=False)
    cookies: NetSchoolCookiesIn
    #: The version the server's own sign-in keeps for the SecurityWarning
    #: acknowledgement and the logout.
    ver: str | None = Field(default=None, max_length=32, pattern=r"^[A-Za-z0-9._-]+$")
    #: The login's ``timeOut``. Stored and read by nothing (the keep-alive has
    #: a floor of its own), so any non-negative integer is taken: refusing a
    #: whole session over the unit of a field nobody reads would be absurd.
    time_out: int | None = Field(default=None, ge=0, le=2_147_483_647)

    @field_validator("at")
    @classmethod
    def _one_header_value(cls, value: str) -> str:
        # A line break here would be a second header on every read.
        if not header_value_ok(value):
            raise ValueError("not a header value")
        return value


class _SessionIn(BaseModel):
    """What every registration carries besides the session itself.

    ``extra="forbid"`` is the rule this endpoint exists for, not tidiness: a
    ``password`` key is a 422, so no client can send one here, by mistake or
    otherwise.
    """

    model_config = ConfigDict(extra="forbid")

    #: What the person typed into the phone's own form. Nothing upstream
    #: vouches for it, so it only names the session: a login copied from
    #: another family reaches nothing of theirs (#165), because what a session
    #: reaches is what its own diary lists.
    login: str = Field(min_length=3, max_length=200)

    @field_validator("login")
    @classmethod
    def _clean_login(cls, value: str) -> str:
        return _clean_login_value(value)


class PetersburgSessionIn(_SessionIn):
    provider: Literal["petersburg"]
    credential: PetersburgCredentialIn


class NetSchoolSessionIn(_SessionIn):
    provider: Literal["netschool"]
    #: An allow-list key; one outside it, or a region that takes no password,
    #: is a 422 from the route before any upstream call.
    region: str = Field(pattern=_REGION_KEY)
    #: The upstream school id («scid») the phone signed in with.
    school_id: int = Field(ge=1, le=2_000_000_000)
    credential: NetSchoolCredentialIn


#: The registration body, told apart by ``provider``.
DiarySessionBody = PetersburgSessionIn | NetSchoolSessionIn


class DiarySessionOut(DiaryLoginOut):
    """Our bearer for a session the phone opened, and what the phone needs to
    keep reading it. Never the upstream credential: that stays here, sealed.

    ``token`` and ``login`` sit where ``DiaryLoginOut`` has them, so an app
    decodes both answers the same way.
    """

    provider: Literal["petersburg", "netschool"]
    region: str | None = None
    school_id: int | None = None
    #: What the diary calls the school, where it says (``None`` for Petersburg).
    school_name: str | None = None
    #: The zone the diary cuts its days at — the same rule the server's own
    #: «today» uses for this session (`services/diary.zone_for`).
    zone: str
    #: The pupils the validating read already fetched, so the phone does not
    #: ask again straight away.
    students: list[DiaryStudentOut] = Field(default_factory=list)


class PetersburgCapabilitiesOut(BaseModel):
    """Nothing to say yet about Petersburg beyond that it is served."""


class NetSchoolCapabilitiesOut(BaseModel):
    #: The allow-list keys this server signs in to with a password.
    regions: list[str] = Field(default_factory=list)


class DiaryProvidersOut(BaseModel):
    petersburg: PetersburgCapabilitiesOut = Field(default_factory=PetersburgCapabilitiesOut)
    netschool: NetSchoolCapabilitiesOut = Field(default_factory=NetSchoolCapabilitiesOut)


class DiaryCapabilitiesOut(BaseModel):
    """What this server's diary can do, asked before the phone takes a password.

    ``enabled`` is false without ``DIARY_SECRET``; ``registration`` says the
    server takes a session the phone opened (a server from before this answers
    404 here instead).
    """

    enabled: bool
    registration: bool = True
    providers: DiaryProvidersOut = Field(default_factory=DiaryProvidersOut)
