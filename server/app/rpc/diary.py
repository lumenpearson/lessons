"""``DiaryService``: one family's account with an electronic diary.

3a serves ``GetDiaryCapabilities``. 3b-7 serves the sessions: a session the
phone opened with the diary itself is kept by ``CreateDiarySession``, through
``services/diary.register`` — v1's ``POST /diary/session``'s rules, order and
counting, on the same budget — and signed out by ``DeleteDiarySession``, v1's
``/logout``. v1's ``POST /diary/login``, a password through this server, has
no twin here (``docs/api.md``, «Not in v2, on purpose»). The corrections are
3b-8's (``docs/specs/2026-10-05-server-v2-3b-plan.md``).
"""

from __future__ import annotations

from typing import TYPE_CHECKING, Any

from protobuf import Message

from app.contract.lessons.v2.diary_pb import (
    CreateDiarySessionRequest,
    CreateDiarySessionResponse,
    DeleteDiarySessionRequest,
    DeleteDiarySessionResponse,
    DiaryCapabilities,
    DiarySession,
    DiaryStudent,
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
    ProviderCapabilities,
)
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.crypto import diary_enabled
from app.models import DiarySession as DiarySessionRow
from app.providers.diary.models import Student
from app.providers.diary.registry import KEYS, NETSCHOOL
from app.rpc.errors import Refusal, validate
from app.schemas import NetSchoolSessionIn, PetersburgSessionIn
from app.services import diary as diary_service

if TYPE_CHECKING:
    from app.rpc.call import Call

#: v1's schema for each case of the request's ``credential``, which is the
#: provider's key: v1's ``DiarySessionBody`` told the two apart by ``provider``.
_SESSIONS: dict[str, type[PetersburgSessionIn] | type[NetSchoolSessionIn]] = {
    "petersburg": PetersburgSessionIn,
    "netschool": NetSchoolSessionIn,
}

#: v1's spelling of a credential's field where v2's differs: the two cookies,
#: whose upper-case names v2's lint will not take.
_V1_NAMES = {"ns_session_id": "NSSESSIONID", "esrn_sec": "ESRNSec"}
_V2_NAMES = {v1: v2 for v2, v1 in _V1_NAMES.items()}

#: A request whose ``credential`` holds neither case. Fixed, naming the oneof.
NO_CREDENTIAL = "credential must be petersburg or netschool"


async def get_diary_capabilities(
    call: Call, request: GetDiaryCapabilitiesRequest
) -> GetDiaryCapabilitiesResponse:
    """What this server's diary can do, before the phone takes a password.

    The same answer v1's ``/diary/capabilities`` gives, in v2's shape
    (decision 12): whether the diary runs here at all, every provider the
    registry knows, and for «Сетевой город» the allow-list's regions that take
    a password. ``sign_in_methods`` and ``features`` stay empty, because v1's
    answer has neither and what each provider declares is 3b's registry table
    to say, from what its connection really implements. Anonymous and
    database-free, as v1's: the gate opens a scope, and nothing asks it for a
    query.
    """
    from app.providers.netschool import regions

    listed = [region.key for region in regions.listed()]
    return GetDiaryCapabilitiesResponse(
        capabilities=DiaryCapabilities(
            enabled=diary_enabled(),
            providers=[
                ProviderCapabilities(provider=key, regions=listed if key == NETSCHOOL else [])
                for key in KEYS
            ],
        )
    )


def _row(call: Call) -> DiarySessionRow:
    """The diary session the gate found for every method of the diary kind."""
    if call.diary is None:
        raise RuntimeError(f"{call.method.key} asked for a diary session it does not take")
    return call.diary


def _plain(message: Message) -> dict[str, Any]:
    """A credential message as v1's schema reads it: the fields that are set,
    under v1's names, nested messages as objects."""
    found: dict[str, Any] = {}
    for field in message.desc().fields:
        if not message.has_field(field.name):
            continue
        value = getattr(message, field.name)
        found[_V1_NAMES.get(field.name, field.name)] = (
            _plain(value) if isinstance(value, Message) else value
        )
    return found


def _v2_path(path: str, case: str) -> str:
    """A violation's path in v1's body, ``credential.cookies.NSSESSIONID``, as
    the request spells it, ``netschool.cookies.ns_session_id``."""
    head, _, rest = path.partition(".")
    if head != "credential":
        return path
    return ".".join([case, *(_V2_NAMES.get(part, part) for part in rest.split(".") if part)])


def _session_form(request: CreateDiarySessionRequest) -> PetersburgSessionIn | NetSchoolSessionIn:
    """The request validated with v1's own schema (decision 5), so v1 and v2
    refuse the same sessions: no password, no unknown cookie, no value a header
    could not carry. A violation names the field as v2 spells it, and never
    what was sent."""
    chosen = request.credential
    if chosen is None or chosen.field not in _SESSIONS:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED, NO_CREDENTIAL, violations=[("credential", NO_CREDENTIAL)]
        )
    sent: dict[str, Any] = {
        "provider": chosen.field,
        "login": request.login,
        "credential": _plain(chosen.value),
    }
    for name in ("region", "school_id"):
        if request.has_field(name):
            sent[name] = getattr(request, name)
    try:
        return validate(_SESSIONS[chosen.field], sent)
    except Refusal as refusal:
        violations = [(_v2_path(field, chosen.field), text) for field, text in refusal.violations]
        fields = ", ".join(sorted({field for field, _ in violations}))
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            f"invalid request field: {fields}",
            violations=violations,
        ) from None


def _student(student: Student) -> DiaryStudent:
    """v1's ``DiaryStudentOut``, field for field: never the upstream's own
    handles, which the server resolves from the id on every call."""
    return DiaryStudent(
        id=student.id,
        first_name=student.first_name,
        last_name=student.last_name,
        middle_name=student.middle_name,
        full_name=student.full_name,
        school=student.school,
        class_name=student.class_name,
    )


async def create_diary_session(
    call: Call, request: CreateDiarySessionRequest
) -> CreateDiarySessionResponse:
    """Keeps a session the phone opened with the diary itself, and answers a
    diary token of ours (REST ``201``, and never cached).

    ``services/diary.register`` decides, in v1's order and on v1's budget —
    the caller's buckets are v1's for the same caller, so a caller alternating
    versions draws on one: a region this server does not serve is refused
    before anything is counted or sent, then the attempt is counted, then the
    diary reads with the session once, from this server's address. What it
    raises the error table words. The session handed over is never echoed
    back, not even in a refusal.
    """
    form = _session_form(request)
    netschool = form if isinstance(form, NetSchoolSessionIn) else None
    registered = await diary_service.register(
        call.session,
        provider=form.provider,
        login=form.login,
        handed=form.credential.model_dump(exclude_none=True),
        region=netschool.region if netschool is not None else None,
        school_id=netschool.school_id if netschool is not None else None,
        failures_key=call.bucket("diary:"),
        opened_key=call.bucket("diary-open:"),
    )
    row = registered.row
    return CreateDiarySessionResponse(
        session=DiarySession(
            token=registered.token,
            login=row.login,
            provider=form.provider,
            region=row.region,
            school_id=registered.school_id,
            school_name=registered.school_name,
            zone=registered.zone,
            students=[_student(student) for student in registered.students],
        )
    )


async def delete_diary_session(
    call: Call, request: DeleteDiarySessionRequest
) -> DeleteDiarySessionResponse:
    """Signs this session out, as v1's ``/logout``: the upstream is told
    where it can be, the row goes, and the token stops working once the call
    commits."""
    await diary_service.sign_out(call.session, _row(call))
    return DeleteDiarySessionResponse()
