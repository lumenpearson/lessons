"""The REST transcoder, generically: routes, binding, statuses, the ETag rule.

The routes come from the contract's own ``google.api.http`` rules, so they are
compared here with the descriptors read independently of ``rpc/methods.py``.
Binding is asked of :func:`app.rest.decode` for every method with a path
variable, and of the live app through handlers put into ``HANDLERS`` for the
test, so that what is checked is the transcoder and not a method's rules
(``docs/specs/2026-10-05-server-v2-design.md``, decision 6).
"""

from __future__ import annotations

import importlib
import pkgutil
import re
from pathlib import Path

import pytest
from protobuf.wkt import FieldMask

import app.contract.lessons.v2 as contract_v2
from app.contract.google.api import annotations_pb
from app.contract.lessons.v2 import diary_pb, me_pb, schedule_pb, subject_pb
from app.main import app
from app.rest import CREATED, MAX_BODY, TOO_LARGE, decode, route_path
from app.rpc.errors import Refusal
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS, Binding, Method

PROTO = Path(__file__).resolve().parents[2] / "proto" / "lessons" / "v2"


def _bindings() -> dict[str, tuple[str, str]]:
    """Every unary method's (verb, template), read from the descriptors."""
    found: dict[str, tuple[str, str]] = {}
    for info in pkgutil.iter_modules(contract_v2.__path__):
        if not info.name.endswith("_pb"):
            continue
        module = importlib.import_module(f"{contract_v2.__name__}.{info.name}")
        for service in module.desc().services:
            for method in service.methods:
                options = method.proto.options
                if options is None or annotations_pb.ext_http not in options:
                    continue
                rule = options[annotations_pb.ext_http]
                found[f"{service.type_name}/{method.name}"] = (
                    rule.pattern.field,
                    rule.pattern.value,
                )
    return found


def _sample(method: Method, dotted: str) -> tuple[str, object]:
    """A path value for ``dotted``, as sent and as the request should hold it."""
    message = method.input.desc()
    *parents, last = dotted.split(".")
    for part in parents:
        message = next(f for f in message.fields if f.name == part).value.message
    field = next(f for f in message.fields if f.name == last)
    if field.proto.type.name == "STRING":
        return "2026-09-07", "2026-09-07"
    return "7", 7


def _leaf(message: object, dotted: str) -> object:
    for part in dotted.split("."):
        message = message[next(f for f in message.desc().fields if f.name == part)]
    return message


def test_every_unary_method_has_its_route_with_its_verb() -> None:
    routes = {
        (route.path, verb)
        for route in app.routes
        for verb in (getattr(route, "methods", None) or ())
    }
    bindings = _bindings()
    assert len(bindings) == 75
    named = {
        route.name: route.path
        for route in app.routes
        if getattr(route, "path", "").startswith("/api/v2/") and hasattr(route, "methods")
    }
    # Every route under /api/v2 is a method's, and every method's is named for it.
    assert set(named) == set(bindings)
    for key in bindings:
        assert named[key] == route_path(METHODS[key])
    for key, (verb, template) in bindings.items():
        path = "/api" + re.sub(
            r"\{([^}]+)\}", lambda m: "{" + m.group(1).replace(".", "__") + "}", template
        )
        assert (path, verb.upper()) in routes, key
        assert route_path(METHODS[key]) == path


def test_every_path_variable_binds_the_dotted_ones_included() -> None:
    checked = 0
    for method in METHODS.values():
        if method.binding is None or not method.binding.variables:
            continue
        params, expected = {}, {}
        for variable in method.binding.variables:
            sent, held = _sample(method, variable)
            params[variable.replace(".", "__")] = sent
            expected[variable] = held
        body = b"{}" if method.binding.body else b""
        request = decode(method, path_params=params, query=[], headers=[], body=body)
        for variable, held in expected.items():
            assert _leaf(request, variable) == held, (method.key, variable)
        checked += 1
    assert checked > 30


def test_a_patch_takes_its_update_mask_and_allow_missing_from_the_query() -> None:
    update = decode(
        METHODS["lessons.v2.MeService/UpdateTask"],
        path_params={"task__id": "5"},
        query=[("updateMask", "title,done")],
        headers=[],
        body='{"title": "Конспект", "id": 99}'.encode(),
    )
    assert update.task.id == 5  # the path wins over the body
    assert update.task.title == "Конспект"
    assert list(update.update_mask.paths) == ["title", "done"]

    day = decode(
        METHODS["lessons.v2.DayService/UpdateDay"],
        path_params={"day__date": "2026-09-07"},
        query=[("allow_missing", "true")],
        headers=[],
        body=b"{}",
    )
    assert day.allow_missing is True
    assert day.day.date == "2026-09-07"


def test_a_get_query_reaches_nested_and_repeated_fields() -> None:
    """No GET of the contract has a nested or a repeated field yet, so the rule
    is asked of a method built for the test over messages that do."""
    repeated = Method(
        service="lessons.v2.Test",
        name="Repeated",
        input=diary_pb.ProviderCapabilities,
        output=diary_pb.ProviderCapabilities,
        auth=METHODS["lessons.v2.MeService/GetMe"].auth,
        min_role=None,
        side_effect_free=True,
        streaming=False,
        binding=Binding(verb="get", path="/v2/test/{provider}", body=""),
    )
    request = decode(
        repeated,
        path_params={"provider": "netschool"},
        query=[
            ("regions", "samara"),
            ("regions", "tomsk"),
            ("signInMethods", "SIGN_IN_METHOD_PASSWORD"),
        ],
        headers=[],
        body=b"",
    )
    assert request.provider == "netschool"
    assert list(request.regions) == ["samara", "tomsk"]
    assert [method.name for method in request.sign_in_methods] == ["PASSWORD"]

    nested = Method(
        service="lessons.v2.Test",
        name="Nested",
        input=me_pb.UpdateTaskRequest,
        output=me_pb.UpdateTaskResponse,
        auth=METHODS["lessons.v2.MeService/GetMe"].auth,
        min_role=None,
        side_effect_free=True,
        streaming=False,
        binding=Binding(verb="get", path="/v2/test", body=""),
    )
    request = decode(
        nested,
        path_params={},
        query=[("task.title", "x"), ("task.priority", "2"), ("task.done", "true"), ("nope", "1")],
        headers=[],
        body=b"",
    )
    assert (request.task.title, request.task.priority, request.task.done) == ("x", 2, True)


@pytest.mark.parametrize(
    ("key", "query", "body"),
    [
        ("lessons.v2.MeService/ListTasks", [("includeDone", "maybe")], b""),
        ("lessons.v2.DeviceService/CreateDevice", [], b"[1, 2]"),
        ("lessons.v2.DeviceService/CreateDevice", [], b'{"code": '),
        ("lessons.v2.MeService/UpdateTask", [], b'"not a task"'),
        ("lessons.v2.ScheduleService/GetScheduleWindow", [], b""),
        # Neither is a JSON syntax error: bytes that are not UTF-8, and a
        # nesting deeper than the interpreter's stack, a RecursionError.
        ("lessons.v2.DeviceService/CreateDevice", [], b'{"code": "\xff"}'),
        ("lessons.v2.DeviceService/CreateDevice", [], b"[" * 200_000),
        # Valid JSON that is not valid UTF-8 once decoded: Connect refuses it, and
        # over REST it used to reach the answer and fail there as a 500 (#339).
        ("lessons.v2.MeService/UpdateTask", [], b'{"title": "\\ud800"}'),
        ("lessons.v2.DayService/UpdateDay", [], b'{"kind": true}'),
        ("lessons.v2.MeService/UpdateTask", [], b'{"priority": 99999999999}'),
    ],
    ids=[
        "bool",
        "not-an-object",
        "truncated",
        "body-field",
        "bad-path",
        "not-utf8",
        "too-deep",
        "lone-surrogate",
        "enum-of-the-wrong-type",
        "int-out-of-range",
    ],
)
def test_what_does_not_decode_is_request_undecodable(key, query, body) -> None:
    method = METHODS[key]
    params = {v.replace(".", "__"): "7" for v in method.binding.variables}
    if "{year}" in route_path(method):
        params = {"year": "two thousand"}
    with pytest.raises(Refusal) as refused:
        decode(method, path_params=params, query=query, headers=[], body=body)
    assert refused.value.reason.name == "REQUEST_UNDECODABLE"
    assert refused.value.message == "The request could not be decoded"


def test_an_unknown_enum_name_reads_as_unspecified_as_it_does_over_connect() -> None:
    """connectrpc's JSON codec ignores unknown fields, an unknown enum name
    among them, so the transcoder does the same rather than refusing what
    Connect accepts."""
    request = decode(
        METHODS["lessons.v2.DayService/UpdateDay"],
        path_params={"day__date": "2026-09-07"},
        query=[],
        headers=[],
        body=b'{"kind": "NOT_A_KIND"}',
    )
    assert request.day.kind.name == "UNSPECIFIED"


def test_if_none_match_comes_from_the_header_which_wins_over_the_query() -> None:
    method = METHODS["lessons.v2.ScheduleService/GetScheduleWindow"]
    request = decode(
        method,
        path_params={"year": "2026"},
        query=[("ifNoneMatch", '"from-query"')],
        headers=[("if-none-match", '"from-header"')],
        body=b"",
    )
    assert request.if_none_match == '"from-header"'
    assert request.year == 2026


def test_the_created_table_is_what_the_proto_comments_promise() -> None:
    promised = set()
    for path in sorted(PROTO.glob("*.proto")):
        text = path.read_text(encoding="utf-8")
        service = re.search(r"^service (\w+)", text, re.M)
        for comment, name in re.findall(r"((?:\s*//[^\n]*\n)+)\s*rpc (\w+)\(", text):
            if "REST answers 201" in " ".join(comment.split()):
                promised.add(f"lessons.v2.{service.group(1)}/{name}")
    assert len(promised) == 8
    assert promised == set(CREATED)


async def test_a_body_over_four_megabytes_is_refused_by_both_transports(v2) -> None:
    oversized = b'{"code": "' + b"x" * MAX_BODY + b'"}'
    rest = await v2.http.post(
        "/api/v2/devices", content=oversized, headers={"Content-Type": "application/json"}
    )
    connect = await v2.http.post(
        "/api/rpc/lessons.v2.DeviceService/CreateDevice",
        content=oversized,
        headers={"Content-Type": "application/json"},
    )
    assert rest.status_code == connect.status_code == 429
    assert rest.json()["error"]["message"] == connect.json()["message"] == TOO_LARGE


async def _echo(call, request):
    return me_pb.UpdateTaskResponse(task=request.task)


async def test_both_transports_bind_one_request_to_one_message(v2, v2_tokens, monkeypatch) -> None:
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/UpdateTask", _echo)
    request = me_pb.UpdateTaskRequest(
        task=me_pb.Task(id=5, title="Конспект", done=True),
        update_mask=FieldMask(paths=["title", "done"]),
    )
    answer = await v2.both("MeService/UpdateTask", request, token=v2_tokens["viewer"])
    assert answer.message.task.id == 5
    assert answer.message.task.title == "Конспект"


async def test_a_create_the_proto_promises_answers_201_and_any_other_200(
    v2, v2_tokens, monkeypatch
) -> None:
    async def created(call, request):
        return subject_pb.CreateSubjectResponse()

    async def minted(call, request):
        return me_pb.CreateLinkCodeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.SubjectService/CreateSubject", created)
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/CreateLinkCode", minted)
    subject = await v2.rest("SubjectService/CreateSubject", token=v2_tokens["admin"])
    link = await v2.rest("MeService/CreateLinkCode", token=v2_tokens["unlinked"])
    assert (subject.status, link.status) == (201, 200)


async def test_the_etag_rule_works_both_ways(v2, v2_tokens, monkeypatch) -> None:
    async def window(call, request):
        if request.if_none_match == '"tag"':
            return schedule_pb.GetScheduleWindowResponse(not_modified=True, etag='"tag"')
        return schedule_pb.GetScheduleWindowResponse(
            etag='"tag"', window=schedule_pb.ScheduleWindow()
        )

    monkeypatch.setitem(HANDLERS, "lessons.v2.ScheduleService/GetScheduleWindow", window)
    fresh = await v2.both(
        "ScheduleService/GetScheduleWindow",
        schedule_pb.GetScheduleWindowRequest(year=2026),
        token=v2_tokens["unlinked"],
    )
    assert fresh.status == 200 and fresh.headers["etag"] == '"tag"'
    cached = await v2.both(
        "ScheduleService/GetScheduleWindow",
        schedule_pb.GetScheduleWindowRequest(year=2026, if_none_match='"tag"'),
        token=v2_tokens["unlinked"],
    )
    assert cached.status == 304
    assert cached.body == b""
    assert cached.headers["etag"] == '"tag"'
    assert cached.message.not_modified is True


async def test_diary_reads_are_never_cached_and_nothing_answers_a_browser(
    v2, monkeypatch, v2_tokens
) -> None:
    async def capabilities(call, request):
        return diary_pb.GetDiaryCapabilitiesResponse()

    async def me(call, request):
        return me_pb.GetMeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.DiaryService/GetDiaryCapabilities", capabilities)
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetMe", me)
    diary = await v2.http.get(
        "/api/v2/diary/capabilities", headers={"Origin": "https://evil.example"}
    )
    assert diary.headers["cache-control"] == "private, no-store"
    assert "access-control-allow-origin" not in diary.headers
    own = await v2.http.get(
        "/api/v2/me", headers={"Authorization": f"Bearer {v2_tokens['viewer']}"}
    )
    assert own.status_code == 200 and "cache-control" not in own.headers
    preflight = await v2.http.options(
        "/api/v2/me",
        headers={"Origin": "https://evil.example", "Access-Control-Request-Method": "GET"},
    )
    assert "access-control-allow-origin" not in preflight.headers


async def test_a_refusal_is_google_s_body_under_its_status(v2, monkeypatch) -> None:
    async def me(call, request):
        return me_pb.GetMeResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetMe", me)
    response = await v2.http.get("/api/v2/me")
    assert response.status_code == 401
    assert response.headers["www-authenticate"] == "Bearer"
    assert response.json() == {
        "error": {
            "code": 401,
            "message": "Missing bearer token",
            "status": "UNAUTHENTICATED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "DEVICE_TOKEN_INVALID",
                    "domain": "lessons.app",
                }
            ],
        }
    }


async def test_the_wrong_verb_is_405(v2) -> None:
    assert (await v2.http.get("/api/v2/devices")).status_code == 405


def _bearer(token):
    return {"Content-Type": "application/json", "Authorization": f"Bearer {token}"}


async def test_a_surrogate_in_a_body_is_refused_the_same_by_both_transports(
    v2, v2_tokens, monkeypatch
) -> None:
    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/UpdateTask", _echo)
    rest = await v2.http.patch(
        "/api/v2/me/tasks/5",
        content=b'{"title": "\\ud800"}',
        headers=_bearer(v2_tokens["viewer"]),
    )
    connect = await v2.http.post(
        "/api/rpc/lessons.v2.MeService/UpdateTask",
        content=b'{"task": {"id": 5, "title": "\\ud800"}}',
        headers=_bearer(v2_tokens["viewer"]),
    )
    assert rest.status_code == connect.status_code == 400
    assert rest.json()["error"]["status"] == "INVALID_ARGUMENT"
    assert rest.json()["error"]["message"] == connect.json()["message"]


async def test_an_answer_that_cannot_be_written_is_internal_in_googles_shape(
    v2, v2_tokens, monkeypatch
) -> None:
    async def unwritable(call, request):
        return me_pb.UpdateTaskResponse(task=me_pb.Task(id=5, title="\ud800"))

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/UpdateTask", unwritable)
    response = await v2.http.patch(
        "/api/v2/me/tasks/5", content=b"{}", headers=_bearer(v2_tokens["viewer"])
    )
    assert response.status_code == 500
    assert response.headers["content-type"].startswith("application/json")
    error = response.json()["error"]
    assert (error["code"], error["status"]) == (500, "INTERNAL")
    assert "ud800" not in response.text


async def test_a_throttle_answers_with_retry_after(v2, v2_tokens, monkeypatch) -> None:
    from app.rpc.errors import ErrorReason

    async def throttled(call, request):
        raise Refusal(ErrorReason.THROTTLED, "Slow down", retry_after_seconds=30)

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetMe", throttled)
    answer = await v2.both("MeService/GetMe", token=v2_tokens["viewer"])
    assert answer.status == 429
    assert answer.headers["retry-after"] == "30"
    assert answer.retry_seconds == 30


async def test_the_class_s_secret_calendar_url_is_never_cached(v2, v2_tokens, monkeypatch) -> None:
    async def feed(call, request):
        return me_pb.GetCalendarFeedResponse()

    monkeypatch.setitem(HANDLERS, "lessons.v2.MeService/GetCalendarFeed", feed)
    response = await v2.http.get(
        "/api/v2/me/calendarFeed", headers={"Authorization": f"Bearer {v2_tokens['viewer']}"}
    )
    assert response.status_code == 200
    assert response.headers["cache-control"] == "private, no-store"


async def test_an_unreadable_answer_is_never_the_same_outcome_as_another(v2) -> None:
    """The harness's own rule: an answer it could not read is not «ok», so a
    REST 405 and a Connect 404 cannot pass ``both`` as one outcome."""
    import httpx

    known = await v2.connect("MeService/GetMe")
    answer = type(known)
    rest = answer("rest", 405, httpx.Headers(), b'{"detail": "Method Not Allowed"}')
    connect = answer("connect", 404, httpx.Headers(), b"")
    assert rest.code is None and connect.code is None
    assert rest.outcome() != connect.outcome()
    assert rest.outcome() != answer("rest", 200, httpx.Headers(), b"", message=None).outcome()
