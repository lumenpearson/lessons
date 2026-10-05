"""No refusal repeats what was sent: the programme's «No error ever echoes a request field».

A password-shaped string is sent in every field of every method v2 serves —
as text, as an object where text belongs, as a list — on both transports, in
the path, in the query, in the bearer and in the client header, and no
refusal carries it back, in its body or in a header. protobuf-py's own
decoder quotes the value it refused («invalid integer value: '…'»), so this
holds the fixed sentence ``rpc/errors.py`` answers with instead
(``docs/specs/2026-10-05-server-v2-design.md``, decision 5).
"""

from __future__ import annotations

import json
from typing import Any
from urllib.parse import quote, urlencode

import httpx
import pytest

from app.contract.lessons.v2.options_pb import AuthKind
from app.rpc.handlers import HANDLERS
from app.rpc.methods import METHODS

SECRET = "Pa55w0rd-s3cr3t-Hunter2"

SERVED = sorted(key for key in HANDLERS if not METHODS[key].streaming)


def _path(key: str, fill: str = "2026") -> str:
    binding = METHODS[key].binding
    assert binding is not None
    path = binding.path
    for variable in binding.variables:
        path = path.replace("{" + variable + "}", quote(fill, safe=""))
    return "/api" + path


def _field_paths(message) -> list[tuple[str, ...]]:
    """Every request field, and one level into each message-typed one.

    A body field such as ``subject`` carries ``name`` and ``color``, and a
    refusal about ``subject.name`` is as able to quote it as one about a
    top-level field is. One level is the depth the contract has: no request
    nests a message inside a message inside the request.
    """
    paths: list[tuple[str, ...]] = []
    for field in message.fields:
        paths.append((field.json_name,))
        inner = getattr(field.value, "message", None)
        if inner is not None:
            paths.extend((field.json_name, sub.json_name) for sub in inner.fields)
    return paths


def _nest(names: tuple[str, ...], raw: Any) -> Any:
    for name in reversed(names):
        raw = {name: raw}
    return raw


def _leaks(response: httpx.Response) -> bool:
    return SECRET in response.text or any(SECRET in value for value in response.headers.values())


@pytest.mark.parametrize("key", SERVED)
async def test_no_refusal_repeats_what_was_sent(
    v2, v2_tokens, monkeypatch, served_settings, key
) -> None:
    monkeypatch.setattr(served_settings, "min_client_version", 40)
    method = METHODS[key]
    binding = method.binding
    assert binding is not None
    bearer = {"Authorization": f"Bearer {v2_tokens['unlinked']}"}
    # The field loop runs as the most privileged caller of the method's own
    # credential kind, so a validation that comes after the gate's role check
    # is reached and swept; the probes below keep the unlinked bearer.
    privileged = {
        AuthKind.DEVICE: v2_tokens["owner"],
        AuthKind.DEVICE_LINKED: v2_tokens["owner"],
        AuthKind.DIARY: v2_tokens["diary"],
    }.get(method.auth)
    strong = {"Authorization": f"Bearer {privileged}"} if privileged else {}
    answers: list[httpx.Response] = []

    for names in _field_paths(method.input.desc()):
        for raw in (SECRET, SECRET * 8, {"nested": SECRET}, [SECRET]):
            body = json.dumps(_nest(names, raw)).encode()
            answers.append(
                await v2.http.post(
                    f"/api/rpc/{key}",
                    content=body,
                    headers={**strong, "Content-Type": "application/json"},
                )
            )
            if binding.body:
                answers.append(
                    await v2.http.request(
                        binding.verb.upper(),
                        _path(key),
                        content=body,
                        headers={**strong, "Content-Type": "application/json"},
                    )
                )
            elif isinstance(raw, str):
                # A nested field is a dotted query parameter, as the transcoder reads it.
                answers.append(
                    await v2.http.request(
                        binding.verb.upper(),
                        f"{_path(key)}?{urlencode({'.'.join(names): raw})}",
                        headers=strong,
                    )
                )

    if binding.variables:
        answers.append(
            await v2.http.request(binding.verb.upper(), _path(key, SECRET), headers=strong)
        )
    for headers in ({"Authorization": f"Bearer {SECRET}"}, {**bearer, "X-Lessons-Client": SECRET}):
        answers.append(
            await v2.http.post(
                f"/api/rpc/{key}",
                content=b"{}",
                headers={**headers, "Content-Type": "application/json"},
            )
        )
        answers.append(await v2.http.request(binding.verb.upper(), _path(key), headers=headers))

    refused = [response for response in answers if response.status_code >= 400]
    assert refused, "the sweep refused nothing, so it proved nothing"
    leaking = [
        f"{response.request.method} {response.request.url} -> {response.status_code}"
        for response in refused
        if _leaks(response)
    ]
    assert leaking == []


def test_the_decoder_itself_would_have_quoted_it() -> None:
    """Held here rather than trusted: the sweep above means something only
    because the library's own message carries the value."""
    from app.contract.lessons.v2.schedule_pb import GetScheduleWindowRequest

    with pytest.raises(ValueError) as refused:
        GetScheduleWindowRequest.from_json(json.dumps({"year": SECRET}))
    assert SECRET in str(refused.value)


def test_the_sweep_reaches_a_field_inside_a_message() -> None:
    """Held here rather than trusted: a sweep that stopped at the top level
    would pass on every method whose input is one wrapper message."""
    paths = _field_paths(METHODS["lessons.v2.SubjectService/UpdateSubject"].input.desc())
    assert ("subject",) in paths
    assert ("subject", "name") in paths
    assert _nest(("subject", "name"), SECRET) == {"subject": {"name": SECRET}}
