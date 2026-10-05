"""The one error table, held to ``errors.proto`` and to ``docs/api.md``.

``rpc/errors.py`` maps every refusal to a canonical code, an ``ErrorReason``,
the metadata the proto names for that reason, and the shell's own sentence;
``rest/errors.py`` writes it as Google's JSON error body. Both are read here
against the files that promise them, so that a reason moved to another code,
a metadata key the proto does not name, or a status the documentation does not
list fails here rather than on a phone.
"""

from __future__ import annotations

import ast
import json
import logging
import re
from pathlib import Path

from connectrpc.code import Code

from app.contract.google.rpc.error_details_pb import ErrorInfo, RetryInfo
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rest.errors import STATUS, error_response
from app.rpc import errors
from app.rpc.errors import CODES, Refusal, connect_error, validate
from app.schemas import JoinRequest

SERVER = Path(__file__).resolve().parents[1]
ERRORS_PROTO = SERVER.parent / "proto" / "lessons" / "v2" / "errors.proto"
API_DOC = SERVER.parent / "docs" / "api.md"
RPC = SERVER / "app" / "rpc"

#: The reasons no method of this stage can produce yet, and the stage that
#: brings them. A reason leaves this set in the commit whose handler raises it.
LATER = {
    "DEVICE_TOKEN_INVALID": "3a: the gate",
    "DIARY_TOKEN_INVALID": "3a: the gate",
    "DEVICE_NOT_LINKED": "3a: the gate",
    "ROLE_REQUIRED": "3a: the gate",
    "RESOURCE_NOT_FOUND": "3a: the gate",
    "CLIENT_TOO_OLD": "3a: the gate",
    "FEATURE_UNSUPPORTED": "3a: WatchClass",
    "RESOURCE_EXISTS": "3b",
    "NO_BELL_FOR_LESSON": "3b",
    "EMPTY_BELL_SCHEDULE": "3b",
    "DIARY_UNAVAILABLE": "3b",
    "DIARY_REAUTH": "3b",
    "DIARY_CREDENTIALS_REJECTED": "3b",
    "DIRECTORY_DISABLED": "3b",
    "DIRECTORY_SPENT": "3b",
    "DIRECTORY_UNAVAILABLE": "3b",
    "DIARY_NO_STUDENTS": "3b",
    "DIARY_UPSTREAM_UNREADABLE": "3b",
    "CORRECTIONS_UNAVAILABLE": "3b",
    "RESOURCE_IN_USE": "3b",
    "SUBJECT_RENAME_CLASH": "3b",
    "CLASS_DEVICE_NOT_LINKED": "3b",
    "ROLE_GRANT_REFUSED": "3b",
    "TERM_BOUNDS_REFUSED": "3b",
    "NO_LESSON_ON_DAY": "3b",
    "LESSON_NOT_ON_TIMETABLE": "3b",
}


def _proto_reasons() -> dict[str, str]:
    """Each value of ``ErrorReason`` and the comment above it, joined."""
    found: dict[str, str] = {}
    comment: list[str] = []
    for line in ERRORS_PROTO.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped.startswith("//"):
            comment.append(stripped.removeprefix("//").strip())
            continue
        value = re.fullmatch(r"([A-Z_]+) = \d+;", stripped)
        if value and value.group(1) != "ERROR_REASON_UNSPECIFIED":
            found[value.group(1)] = " ".join(comment)
        comment = []
    return found


def _proto_metadata(comment: str) -> set[str]:
    """The keys a reason's comment names after «metadata», up to its colon or full stop."""
    match = re.search(r"metadata (.*?)(?::\s|\.\s|\.$|$)", comment)
    return set(re.findall(r"`([a-z_]+)`", match.group(1))) if match else set()


def _refusals_in_app_rpc() -> list[tuple[str, str, set[str]]]:
    """Every ``Refusal(ErrorReason.X, …, key=…)`` written under ``app/rpc``:
    where it is, its reason, and its metadata keys."""
    found = []
    for path in sorted(RPC.rglob("*.py")):
        for node in ast.walk(ast.parse(path.read_text(encoding="utf-8"))):
            if not (isinstance(node, ast.Call) and getattr(node.func, "id", None) == "Refusal"):
                continue
            first = node.args[0] if node.args else None
            assert isinstance(first, ast.Attribute), f"{path.name}:{node.lineno} names no reason"
            keys = {kw.arg for kw in node.keywords if kw.arg and kw.arg != "violations"}
            found.append((f"{path.name}:{node.lineno}", first.attr, keys))
    return found


def test_every_reason_is_under_the_code_errors_proto_gives_it() -> None:
    reasons = _proto_reasons()
    assert len(reasons) == 33
    table = {reason.name: code.name for reason, code in CODES.items()}
    assert table == {name: comment.split(".")[0] for name, comment in reasons.items()}


def test_every_refusal_carries_only_the_metadata_errors_proto_names() -> None:
    reasons = _proto_reasons()
    wrong = [
        f"{where}: {reason} carries {sorted(keys - _proto_metadata(reasons[reason]))}"
        for where, reason, keys in _refusals_in_app_rpc()
        if not keys <= _proto_metadata(reasons[reason])
    ]
    assert wrong == []


def test_the_metadata_reader_reads_what_it_is_written_for() -> None:
    """Held here rather than trusted: a reader that found nothing would pass
    the test above for ever."""
    reasons = _proto_reasons()
    assert _proto_metadata(reasons["THROTTLED"]) == {"retry_after_seconds"}
    assert _proto_metadata(reasons["RESOURCE_IN_USE"]) == {"resource", "used_by", "count"}
    assert _proto_metadata(reasons["DIRECTORY_SPENT"]) == {"retry_after_seconds"}
    assert _proto_metadata(reasons["VALIDATION_FAILED"]) == set()


def test_every_reason_is_produced_or_waits_for_a_later_stage() -> None:
    produced = {reason for _where, reason, _keys in _refusals_in_app_rpc()}
    every = set(_proto_reasons())
    assert produced & set(LATER) == set(), "a reason this stage produces is still listed as later"
    assert every - produced - set(LATER) == set(), "a reason nothing produces and nothing awaits"
    assert set(LATER) <= every


def test_a_refusal_is_its_code_its_reason_and_the_details_the_proto_promises() -> None:
    error = connect_error(
        Refusal(ErrorReason.THROTTLED, "Too many join attempts", retry_after_seconds=7)
    )
    assert error.code is Code.RESOURCE_EXHAUSTED
    assert error.message == "Too many join attempts"
    values = [detail.value() for detail in error.details]
    assert values[0] == ErrorInfo(
        reason="THROTTLED", domain="lessons.app", metadata={"retry_after_seconds": "7"}
    )
    assert isinstance(values[1], RetryInfo)
    assert values[1].retry_delay.seconds == 7


def test_an_unknown_failure_is_internal_and_says_nothing_of_itself(caplog) -> None:
    with caplog.at_level(logging.ERROR, logger="app.rpc.errors"):
        error = connect_error(RuntimeError("password=hunter2 leaked into a message"))
    assert error.code is Code.INTERNAL
    assert error.message == errors.INTERNAL_MESSAGE
    assert list(error.details) == []
    assert "hunter2" not in error.message
    assert any(record.exc_info for record in caplog.records)


def test_validation_names_the_field_and_never_the_value() -> None:
    secret = "Pa55w0rd-s3cret-" * 3
    try:
        validate(JoinRequest, {"code": secret, "device_name": None})
    except Refusal as refusal:
        assert refusal.reason is ErrorReason.VALIDATION_FAILED
        assert [field for field, _ in refusal.violations] == ["code"]
        assert secret not in refusal.message
        assert all(secret not in description for _, description in refusal.violations)
    else:
        raise AssertionError("a 48-character code was accepted")


def test_rest_writes_google_s_error_body_through_the_type_registry() -> None:
    response = error_response(
        connect_error(Refusal(ErrorReason.ROLE_REQUIRED, "admin role required", role="ROLE_ADMIN"))
    )
    assert response.status_code == 403
    assert json.loads(response.body) == {
        "error": {
            "code": 403,
            "message": "admin role required",
            "status": "PERMISSION_DENIED",
            "details": [
                {
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "ROLE_REQUIRED",
                    "domain": "lessons.app",
                    "metadata": {"role": "ROLE_ADMIN"},
                }
            ],
        }
    }


def test_rest_sends_retry_after_and_the_retry_info_beside_it() -> None:
    response = error_response(
        connect_error(
            Refusal(ErrorReason.THROTTLED, "Too many join attempts", retry_after_seconds=7)
        )
    )
    assert response.status_code == 429
    assert response.headers["Retry-After"] == "7"
    details = json.loads(response.body)["error"]["details"]
    assert {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "7s"} in details


def test_rest_names_the_scheme_on_a_401_and_the_field_on_a_400() -> None:
    unauthenticated = error_response(
        connect_error(Refusal(ErrorReason.DEVICE_TOKEN_INVALID, "Invalid token"))
    )
    assert unauthenticated.status_code == 401
    assert unauthenticated.headers["WWW-Authenticate"] == "Bearer"

    invalid = error_response(
        connect_error(
            Refusal(ErrorReason.VALIDATION_FAILED, "bad year", violations=[("year", "bad year")])
        )
    )
    assert invalid.status_code == 400
    details = json.loads(invalid.body)["error"]["details"]
    assert {
        "@type": "type.googleapis.com/google.rpc.BadRequest",
        "fieldViolations": [{"field": "year", "description": "bad year"}],
    } in details


def test_the_status_table_is_docs_api_md_s() -> None:
    """Every code v2 sends has the status ``docs/api.md`` gives it, INTERNAL included."""
    section = API_DOC.read_text(encoding="utf-8").split("## v2: the contract", 1)[1]
    documented: dict[str, int] = {}
    for codes, status in re.findall(r"^\| ((?:`[A-Z_]+`(?:, )?)+) \| (\d{3}) \|$", section, re.M):
        for code in re.findall(r"`([A-Z_]+)`", codes):
            documented[code] = int(status)
    sent = {code.name for code in CODES.values()} | {"INTERNAL", "UNIMPLEMENTED"}
    assert sent <= set(documented), sorted(sent - set(documented))
    assert {code: STATUS[Code[code]] for code in documented} == documented
