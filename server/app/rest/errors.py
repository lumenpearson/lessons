"""A refusal as REST sends it: Google's JSON error body, under ``docs/api.md``'s status.

``{"error": {"code": <HTTP status>, "message": …, "status": "FAILED_PRECONDITION",
"details": [{"@type": "type.googleapis.com/google.rpc.ErrorInfo", …}]}}`` — the
body Google's own transcoding writes, so a client that knows AIP-193 reads it
without being told about this server. The same ``ConnectError`` Connect sends
is the source, so the two transports cannot word one refusal two ways.
"""

from __future__ import annotations

from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError, ErrorDetail
from protobuf import Registry, message_to_json_value
from protobuf.wkt import Any as AnyMessage
from starlette.responses import JSONResponse

from app.contract.google.rpc import error_details_pb

#: ``docs/api.md``, «Errors»: each canonical code's HTTP status. Codes v2 does
#: not send today are here too, with Google's mapping, so that a library's own
#: refusal (``connectrpc`` raises ``RESOURCE_EXHAUSTED`` for an oversized
#: message) never falls through to a 500.
STATUS: dict[Code, int] = {
    Code.INVALID_ARGUMENT: 400,
    Code.FAILED_PRECONDITION: 400,
    Code.OUT_OF_RANGE: 400,
    Code.UNAUTHENTICATED: 401,
    Code.PERMISSION_DENIED: 403,
    Code.NOT_FOUND: 404,
    Code.ALREADY_EXISTS: 409,
    Code.ABORTED: 409,
    Code.RESOURCE_EXHAUSTED: 429,
    Code.CANCELED: 499,
    Code.INTERNAL: 500,
    Code.UNKNOWN: 500,
    Code.DATA_LOSS: 500,
    Code.UNIMPLEMENTED: 501,
    Code.UNAVAILABLE: 503,
    Code.DEADLINE_EXCEEDED: 504,
}

#: The detail types this server sends, so that an ``Any`` renders as JSON
#: with its ``@type`` rather than as base64. protobuf-py's own registry does
#: the rendering, the way Google's runtime would.
_REGISTRY = Registry(error_details_pb.desc())


def _detail(detail: ErrorDetail) -> Any:
    message = detail.value(_REGISTRY)
    if message is None:
        # A detail this server cannot read — none is sent today — still says
        # what it is, rather than vanishing from the body.
        return {"@type": f"type.googleapis.com/{detail.type_name}"}
    return message_to_json_value(AnyMessage.pack(message), registry=_REGISTRY)


def _metadata(error: ConnectError) -> dict[str, str]:
    for detail in error.details:
        message = detail.value(_REGISTRY)
        if isinstance(message, error_details_pb.ErrorInfo):
            return dict(message.metadata)
    return {}


def error_response(error: ConnectError) -> JSONResponse:
    """The REST answer to ``error``."""
    status = STATUS.get(error.code, 500)
    headers: dict[str, str] = {}
    retry_after = _metadata(error).get("retry_after_seconds")
    if retry_after is not None:
        headers["Retry-After"] = retry_after
    if status == 401:
        # RFC 9110 §11.6.1: a 401 names the scheme it wants. v1 sent it too.
        headers["WWW-Authenticate"] = "Bearer"
    body = {
        "error": {
            "code": status,
            "message": error.message,
            "status": error.code.name,
            "details": [_detail(detail) for detail in error.details],
        }
    }
    return JSONResponse(body, status_code=status, headers=headers)
