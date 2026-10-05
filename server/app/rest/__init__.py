"""v2 over REST: one Starlette route per unary method, from its ``google.api.http`` rule.

The contract's annotations are the routes — written ``/v2/…``, served under
``/api`` — read at import from the same table the gate and the RPC adapters
use (``rpc/methods.py``), and every route calls the same ``invoke``. So REST
and RPC cannot disagree about a rule; only the transcoding can differ, and it
is tested once, generically
(``docs/specs/2026-10-05-server-v2-design.md``, decision 6).

**Binding a request** (:func:`decode`):

- path variables, dotted ones included; Starlette cannot name a parameter
  ``task.id``, so each is renamed ``task__id`` in the route and back here;
- the body as the rule says: ``"*"``, one field, or none;
- **every field the path and the body leave unbound from the query string,
  whatever the verb** — an ``Update*``'s ``update_mask`` and ``UpdateDay``'s
  ``allow_missing`` arrive there; dotted names reach nested fields, a repeated
  name a repeated field, a ``bool`` is ``true``/``false``, and a well-known
  type (``FieldMask``, ``Timestamp``) is its JSON string form;
- ``If-None-Match`` fills an ``if_none_match`` field, and wins over a query
  parameter of that name.

Canonical proto3 JSON, unknown fields ignored, as Connect does it. A body
over 4 MB is refused, Connect's own limit; anything that does not decode is
``INVALID_ARGUMENT`` / ``REQUEST_UNDECODABLE``, and never a 500 and never a
sentence that quotes what was sent.

**Answering**: ``201`` for the eight methods whose proto comment promises it
(:data:`CREATED`), ``200`` for every other success, with the response as
canonical JSON; ``304`` and no body when the response says ``not_modified``;
its ``etag`` as ``ETag``; diary reads ``Cache-Control: private, no-store``;
no CORS header at all (question 5). A refusal is Google's error body
(``rest/errors.py``).
"""

from __future__ import annotations

import json
from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Any

from connectrpc.code import Code
from connectrpc.errors import ConnectError
from connectrpc.server import DEFAULT_READ_MAX_BYTES
from protobuf import (
    DescField,
    DescFieldValueList,
    DescFieldValueMessage,
    DescFieldValueScalar,
    DescMessage,
    Message,
    ScalarType,
    message_from_json_value,
)
from starlette.requests import Request
from starlette.responses import Response
from starlette.routing import Route

from app.rest.errors import error_response
from app.rpc.call import invoke
from app.rpc.errors import Refusal, connect_error, undecodable
from app.rpc.methods import METHODS, Method

#: The methods whose proto comment says «REST answers 201». Comments do not
#: reach the descriptors at runtime, so the set is written here;
#: ``test_rest.py`` reads ``proto/lessons/v2`` and holds the two level.
CREATED = frozenset(
    {
        "lessons.v2.BellService/CreateBellSchedule",
        "lessons.v2.DeviceService/CreateDevice",
        "lessons.v2.DiaryService/CreateDiarySession",
        "lessons.v2.EventService/CreateEvent",
        "lessons.v2.HomeworkService/CreateHomework",
        "lessons.v2.MeService/CreateTask",
        "lessons.v2.SubjectService/CreateSubject",
        "lessons.v2.SubstitutionService/CreateSubstitution",
    }
)

#: What every diary read answers with: a family's marks are nobody's cache's.
NO_STORE = "private, no-store"

#: Connect's own limit on a request message, so the two transports refuse the
#: same size with the same words.
MAX_BODY = DEFAULT_READ_MAX_BYTES
TOO_LARGE = f"message is larger than configured max {MAX_BODY}"

#: The well-known types whose JSON form is a string, so a query parameter can
#: carry them as it is.
_STRING_JSON = frozenset(
    {
        "google.protobuf.FieldMask",
        "google.protobuf.Timestamp",
        "google.protobuf.Duration",
    }
)


def route_path(method: Method) -> str:
    """The Starlette path of ``method``'s route: ``/api`` plus its template,
    each dotted variable renamed ``a__b``."""
    if method.binding is None:
        raise ValueError(f"{method.key} has no REST binding")
    path = method.binding.path
    for variable in method.binding.variables:
        path = path.replace("{" + variable + "}", "{" + variable.replace(".", "__") + "}")
    return "/api" + path


def _field(message: Any, name: str) -> DescField | None:
    """A field of a message descriptor by its proto name or its JSON name."""
    for field in message.fields:
        if name in (field.name, field.json_name):
            return field
    return None


def _query_value(field: DescField, raw: str) -> Any:
    """A query parameter's text as the JSON value of ``field``'s type."""
    value = field.value
    kind: Any = value.element if isinstance(value, DescFieldValueList) else value
    if isinstance(kind, DescFieldValueScalar):
        kind = kind.scalar
    if kind is ScalarType.BOOL:
        if raw in ("true", "1"):
            return True
        if raw in ("false", "0"):
            return False
        raise undecodable()
    if isinstance(kind, DescFieldValueMessage):
        kind = kind.message
    if isinstance(kind, DescMessage) and kind.type_name not in _STRING_JSON:
        # A message in a query string has no form the contract defines.
        raise undecodable()
    # Numbers, enums and strings: canonical JSON reads each from a string.
    return raw


def _put(target: dict[str, Any], message: Any, dotted: str, value: Any, *, append: bool) -> None:
    """Set ``dotted`` — ``"task.id"`` — in a JSON object of ``message``'s type.

    The key written is the field's JSON name, and its proto-name spelling is
    removed first, so a body that said ``task_id`` and a path that says
    ``taskId`` cannot both reach the parser. Unknown names are ignored, as an
    unknown JSON field is.
    """
    parts = dotted.split(".")
    for part in parts[:-1]:
        field = _field(message, part)
        if field is None or not isinstance(field.value, DescFieldValueMessage):
            return
        if field.name != field.json_name and field.name in target:
            target.setdefault(field.json_name, target.pop(field.name))
        nested = target.setdefault(field.json_name, {})
        if not isinstance(nested, dict):
            raise undecodable()
        target, message = nested, field.value.message
    field = _field(message, parts[-1])
    if field is None:
        return
    if field.name != field.json_name:
        target.pop(field.name, None)
    if append and isinstance(field.value, DescFieldValueList):
        previous = target.get(field.json_name)
        target[field.json_name] = [*(previous if isinstance(previous, list) else []), value]
    else:
        target[field.json_name] = value


def _leaf(message: Any, dotted: str) -> DescField | None:
    for part in dotted.split(".")[:-1]:
        field = _field(message, part)
        if field is None or not isinstance(field.value, DescFieldValueMessage):
            return None
        message = field.value.message
    return _field(message, dotted.split(".")[-1])


def decode(
    method: Method,
    *,
    path_params: Mapping[str, str],
    query: Sequence[tuple[str, str]],
    headers: Sequence[tuple[str, str]],
    body: bytes,
) -> Message:
    """The request message a REST call means, or ``REQUEST_UNDECODABLE``."""
    if method.binding is None:
        raise ValueError(f"{method.key} has no REST binding")
    desc = method.input.desc()
    value: dict[str, Any] = {}
    bound: set[str] = set()

    if method.binding.body:
        try:
            parsed = json.loads(body) if body.strip() else {}
        except (ValueError, RecursionError):
            # ValueError covers bad JSON and bytes that are not UTF-8; a body
            # nested past the interpreter's depth is a RecursionError, which
            # is not one, and would otherwise be a 500.
            raise undecodable() from None
        if method.binding.body == "*":
            if not isinstance(parsed, dict):
                raise undecodable()
            value = parsed
            bound = {field.name for field in desc.fields}
        else:
            _put(value, desc, method.binding.body, parsed, append=False)
            bound = {method.binding.body}

    for name, raw in query:
        top = _field(desc, name.split(".")[0])
        if top is None or top.name in bound:
            continue
        leaf = _leaf(desc, name)
        if leaf is None:
            continue
        _put(value, desc, name, _query_value(leaf, raw), append=True)

    if _field(desc, "if_none_match") is not None:
        tags = [tag for key, tag in headers if key.lower() == "if-none-match"]
        if tags:
            _put(value, desc, "if_none_match", ", ".join(tags), append=False)

    for name, raw in path_params.items():
        _put(value, desc, name.replace("__", "."), raw, append=False)

    try:
        return message_from_json_value(method.input, value, ignore_unknown_fields=True)
    except Exception:
        # Whatever the parser says may quote the value it refused.
        raise undecodable() from None


async def _read_body(request: Request) -> bytes:
    """The body, or ``RESOURCE_EXHAUSTED`` past :data:`MAX_BODY` — read in
    chunks, so an oversized body is never held whole."""
    chunks: list[bytes] = []
    size = 0
    async for chunk in request.stream():
        size += len(chunk)
        if size > MAX_BODY:
            raise ConnectError(Code.RESOURCE_EXHAUSTED, TOO_LARGE)
        chunks.append(chunk)
    return b"".join(chunks)


def _answer(method: Method, response: Message) -> Response:
    fields = {field.name: field for field in response.desc().fields}
    headers: dict[str, str] = {}
    etag = response[fields["etag"]] if "etag" in fields else ""
    if etag:
        headers["ETag"] = etag
    if method.service == "lessons.v2.DiaryService" and method.binding is not None:
        if method.binding.verb == "get":
            headers["Cache-Control"] = NO_STORE
    if "not_modified" in fields and response[fields["not_modified"]]:
        return Response(status_code=304, headers=headers)
    status = 201 if method.key in CREATED else 200
    return Response(
        response.to_json(), status_code=status, media_type="application/json", headers=headers
    )


def _endpoint(method: Method) -> Callable[[Request], Awaitable[Response]]:
    async def endpoint(request: Request) -> Response:
        headers = request.headers.items()
        try:
            body = await _read_body(request) if method.binding and method.binding.body else b""
            message = decode(
                method,
                path_params=request.path_params,
                query=request.query_params.multi_items(),
                headers=headers,
                body=body,
            )
            response = await invoke(
                method,
                message,
                headers=headers,
                peer=request.client.host if request.client else None,
            )
        except ConnectError as error:
            return error_response(error)
        except Refusal as refusal:
            return error_response(connect_error(refusal))
        return _answer(method, response)

    return endpoint


def rest_routes() -> list[Route]:
    """One route per unary method of the contract, in the contract's order."""
    return [
        Route(
            route_path(method),
            _endpoint(method),
            methods=[method.binding.verb.upper()],
            name=method.key,
        )
        for method in METHODS.values()
        if method.binding is not None
    ]
