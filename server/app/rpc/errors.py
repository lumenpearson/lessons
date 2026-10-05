"""The one table: what a refusal is on the wire, whichever transport carries it.

A refusal leaves a handler in one of two shapes. A :class:`Refusal` is raised
where v1 raised an ``HTTPException`` inline — an unknown id, a date out of
bounds, the gate's own answers. Anything else is a service's or a provider's
exception carrying facts, and :data:`TABLE` words it: a canonical code, an
``ErrorReason``, the metadata ``errors.proto`` names for that reason, and the
shell's own sentence, which is v1's wherever v1 had one
(``docs/specs/2026-10-05-server-v2-design.md``, decision 5) — kept once in
``app/wording.py`` where both versions say it.

:func:`connect_error` turns either into the ``ConnectError`` both transports
send: Connect as itself, REST through ``app/rest/errors.py``. Anything the
table does not know is ``INTERNAL``: logged here with its traceback, answered
with a fixed sentence, because an exception's own text can carry what was
typed — protobuf-py's decoder writes the refused value into its message.
"""

from __future__ import annotations

import logging
from collections.abc import Callable, Mapping, Sequence
from typing import Any, TypeVar

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from protobuf import Message
from protobuf.wkt import Duration
from pydantic import BaseModel, ValidationError

from app import wording
from app.contract.google.rpc.error_details_pb import BadRequest, ErrorInfo, RetryInfo
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.services import diary as diary_service
from app.services import join, window

log = logging.getLogger(__name__)

#: ``ErrorInfo.domain`` on every refusal, as ``docs/api.md`` promises.
DOMAIN = "lessons.app"

#: The sentence an unexpected failure answers with. Fixed, and English like
#: v1's own generic answers: the app acts on the code, never on this.
INTERNAL_MESSAGE = "The server failed to answer this request"

#: The sentence an undecodable request answers with, on both transports. It
#: names no value on purpose: the decoder's own message quotes what was sent.
UNDECODABLE_MESSAGE = "The request could not be decoded"

#: Each reason's canonical code, as the comment beside it in ``errors.proto``
#: begins. ``test_rpc_errors.py`` reads the file and holds the two level.
CODES: Mapping[ErrorReason, Code] = {
    ErrorReason.DEVICE_TOKEN_INVALID: Code.UNAUTHENTICATED,
    ErrorReason.DIARY_TOKEN_INVALID: Code.UNAUTHENTICATED,
    ErrorReason.DEVICE_NOT_LINKED: Code.PERMISSION_DENIED,
    ErrorReason.ROLE_REQUIRED: Code.PERMISSION_DENIED,
    ErrorReason.JOIN_CODE_UNKNOWN: Code.NOT_FOUND,
    ErrorReason.CLASS_INVITE_ONLY: Code.PERMISSION_DENIED,
    ErrorReason.DEVICE_LIMIT_REACHED: Code.RESOURCE_EXHAUSTED,
    ErrorReason.THROTTLED: Code.RESOURCE_EXHAUSTED,
    ErrorReason.RESOURCE_NOT_FOUND: Code.NOT_FOUND,
    ErrorReason.RESOURCE_EXISTS: Code.ALREADY_EXISTS,
    ErrorReason.VALIDATION_FAILED: Code.INVALID_ARGUMENT,
    ErrorReason.NO_BELL_FOR_LESSON: Code.FAILED_PRECONDITION,
    ErrorReason.EMPTY_BELL_SCHEDULE: Code.FAILED_PRECONDITION,
    ErrorReason.DIARY_DISABLED: Code.UNAVAILABLE,
    ErrorReason.DIARY_UNAVAILABLE: Code.UNAVAILABLE,
    ErrorReason.DIARY_REAUTH: Code.UNAUTHENTICATED,
    ErrorReason.DIARY_CREDENTIALS_REJECTED: Code.PERMISSION_DENIED,
    ErrorReason.DIRECTORY_DISABLED: Code.UNAVAILABLE,
    ErrorReason.DIRECTORY_SPENT: Code.RESOURCE_EXHAUSTED,
    ErrorReason.DIRECTORY_UNAVAILABLE: Code.UNAVAILABLE,
    ErrorReason.CLIENT_TOO_OLD: Code.FAILED_PRECONDITION,
    ErrorReason.FEATURE_UNSUPPORTED: Code.UNIMPLEMENTED,
    ErrorReason.REQUEST_UNDECODABLE: Code.INVALID_ARGUMENT,
    ErrorReason.DIARY_NO_STUDENTS: Code.PERMISSION_DENIED,
    ErrorReason.DIARY_UPSTREAM_UNREADABLE: Code.UNAVAILABLE,
    ErrorReason.CORRECTIONS_UNAVAILABLE: Code.FAILED_PRECONDITION,
    ErrorReason.RESOURCE_IN_USE: Code.FAILED_PRECONDITION,
    ErrorReason.SUBJECT_RENAME_CLASH: Code.FAILED_PRECONDITION,
    ErrorReason.CLASS_DEVICE_NOT_LINKED: Code.FAILED_PRECONDITION,
    ErrorReason.ROLE_GRANT_REFUSED: Code.PERMISSION_DENIED,
    ErrorReason.TERM_BOUNDS_REFUSED: Code.FAILED_PRECONDITION,
    ErrorReason.NO_LESSON_ON_DAY: Code.FAILED_PRECONDITION,
    ErrorReason.LESSON_NOT_ON_TIMETABLE: Code.FAILED_PRECONDITION,
}


class Refusal(Exception):
    """A refusal a handler or the gate raises: a reason, the shell's sentence,
    and the facts.

    ``metadata`` keys are the ones ``errors.proto`` names for the reason —
    ``test_rpc_errors.py`` reads every ``Refusal(...)`` under ``app/rpc`` and
    holds them to the file. ``retry_after_seconds`` also becomes a
    ``google.rpc.RetryInfo``, and on REST a ``Retry-After``. ``violations``
    are ``(field, description)`` pairs for a ``google.rpc.BadRequest``, named
    by the proto field path; a description never quotes the value.
    """

    def __init__(
        self,
        reason: ErrorReason,
        message: str,
        *,
        violations: Sequence[tuple[str, str]] = (),
        **metadata: str | int,
    ) -> None:
        super().__init__(message)
        self.reason = reason
        self.message = message
        self.violations = tuple(violations)
        self.metadata = {key: str(value) for key, value in metadata.items()}


E = TypeVar("E", bound=Exception)
M = TypeVar("M", bound=BaseModel)


def _join_throttled(error: join.JoinThrottled) -> Refusal:
    return Refusal(
        ErrorReason.THROTTLED, wording.JOIN_THROTTLED_DETAIL, retry_after_seconds=error.seconds
    )


def _join_code_unknown(_error: join.JoinCodeUnknown) -> Refusal:
    return Refusal(ErrorReason.JOIN_CODE_UNKNOWN, wording.JOIN_UNKNOWN_CODE_DETAIL)


def _class_invite_only(_error: join.ClassInviteOnly) -> Refusal:
    return Refusal(ErrorReason.CLASS_INVITE_ONLY, wording.JOIN_INVITE_ONLY_DETAIL)


def _device_limit_reached(error: join.DeviceLimitReached) -> Refusal:
    return Refusal(
        ErrorReason.DEVICE_LIMIT_REACHED, wording.JOIN_DEVICE_LIMIT_DETAIL, limit=error.limit
    )


def _diary_disabled(_error: diary_service.DiaryDisabled) -> Refusal:
    return Refusal(ErrorReason.DIARY_DISABLED, wording.DIARY_DISABLED_DETAIL)


def _year_out_of_bounds(_error: window.YearOutOfBounds) -> Refusal:
    sentence = f"year must be between {window.FIRST_YEAR} and {window.LAST_YEAR}"
    return Refusal(ErrorReason.VALIDATION_FAILED, sentence, violations=[("year", sentence)])


#: Every service and provider exception a v2 method can meet, and its refusal.
#: Matched along the exception's MRO, so a subclass is worded by its own row
#: when it has one and by its base's otherwise. 3a holds the rows its four
#: methods meet; 3b adds the rest, method by method.
TABLE: Mapping[type[Exception], Callable[[Any], Refusal]] = {
    join.JoinThrottled: _join_throttled,
    join.JoinCodeUnknown: _join_code_unknown,
    join.ClassInviteOnly: _class_invite_only,
    join.DeviceLimitReached: _device_limit_reached,
    diary_service.DiaryDisabled: _diary_disabled,
    window.YearOutOfBounds: _year_out_of_bounds,
}


def _details(refusal: Refusal) -> list[Message]:
    details: list[Message] = [
        ErrorInfo(reason=refusal.reason.name, domain=DOMAIN, metadata=refusal.metadata)
    ]
    if refusal.violations:
        details.append(
            BadRequest(
                field_violations=[
                    BadRequest.FieldViolation(field=field, description=description)
                    for field, description in refusal.violations
                ]
            )
        )
    seconds = refusal.metadata.get("retry_after_seconds")
    if seconds is not None:
        details.append(RetryInfo(retry_delay=Duration(seconds=int(seconds))))
    return details


def refusal_of(error: BaseException) -> Refusal | None:
    """The table's refusal for ``error``, or ``None`` if the table does not know it."""
    if isinstance(error, Refusal):
        return error
    for cls in type(error).__mro__:
        row = TABLE.get(cls)
        if row is not None:
            return row(error)
    return None


def connect_error(error: BaseException) -> ConnectError:
    """What ``error`` is on the wire. Never raises, and never quotes ``error``'s text."""
    if isinstance(error, ConnectError):
        return error
    refusal = refusal_of(error)
    if refusal is None:
        log.error("v2 call failed", exc_info=error)
        return ConnectError(Code.INTERNAL, INTERNAL_MESSAGE)
    return ConnectError(CODES[refusal.reason], refusal.message, details=_details(refusal))


def undecodable() -> Refusal:
    """The answer to a request body or query that does not decode."""
    return Refusal(ErrorReason.REQUEST_UNDECODABLE, UNDECODABLE_MESSAGE)


def validate(model: type[M], data: Mapping[str, object]) -> M:
    """``model`` validated from ``data``, or ``VALIDATION_FAILED`` naming each
    field — and never the value, which pydantic keeps under ``input``.

    A handler validates with v1's own schema where one exists, so v1 and v2
    refuse the same requests; only the words differ.
    """
    try:
        return model.model_validate(dict(data))
    except ValidationError as failure:
        violations = [
            (".".join(str(part) for part in error["loc"]), error["msg"])
            for error in failure.errors(include_input=False, include_url=False)
        ]
        fields = ", ".join(sorted({field for field, _ in violations}))
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            f"invalid request field: {fields}",
            violations=violations,
        ) from None
