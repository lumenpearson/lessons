"""v2 over RPC: Connect and gRPC-Web, mounted at ``/api/rpc``.

:func:`rpc_app` is one ASGI app over the seventeen generated service apps. Each
generated ``Protocol`` is implemented by an adapter whose every method turns
Connect's ``RequestContext`` into :func:`call.invoke`'s arguments, so the RPC
path and the REST transcoder run one function
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3).

Two of ``connectrpc`` 0.12.1's defects under HTTP/1.1 are corrected in front
of it (decision 7), because the library answers both with a ``500`` and a
logged traceback:

- a native gRPC request (``application/grpc``, ``application/grpc+…``) is
  refused with ``415`` and a sentence saying where gRPC is served — over
  anything but HTTP/2 and HTTP/3, so the host target's HTTP/2 lets it
  through (3c), and a scope that names no version is HTTP/1.1, as ASGI
  reads it;
- a body that does not decode is ``invalid_argument`` /
  ``REQUEST_UNDECODABLE``, through codecs that wrap the library's own.
"""

from __future__ import annotations

import importlib
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from connectrpc.codec import Codec, proto_binary_codec, proto_json_codec
from starlette.responses import PlainTextResponse, Response

from app.api.deps import peer_host
from app.rpc.call import invoke
from app.rpc.errors import connect_error, undecodable
from app.rpc.methods import METHODS, Method

Scope = MutableMapping[str, Any]
Receive = Callable[[], Awaitable[MutableMapping[str, Any]]]
Send = Callable[[MutableMapping[str, Any]], Awaitable[None]]

#: What a native gRPC request over HTTP/1.1 is told.
GRPC_REFUSED = (
    "Native gRPC is served by the host target over HTTP/2, not by this deployment. "
    "Use Connect or gRPC-Web here."
)


class _Decoding:
    """A codec that answers ``REQUEST_UNDECODABLE`` where the library's raises.

    ``connectrpc`` calls ``codec.decode`` and lets whatever it raises become a
    ``500 unknown`` with a traceback; protobuf-py raises ``ValueError``,
    ``TypeError`` or ``json.JSONDecodeError``, and its message quotes the value
    it refused, so the original is never what the client sees.
    """

    def __init__(self, inner: Codec) -> None:
        self._inner = inner

    def name(self) -> str:
        return self._inner.name()

    def encode(self, message: Any) -> bytes:
        return self._inner.encode(message)

    def decode(self, data: bytes | bytearray, message_class: type[Any]) -> Any:
        try:
            return self._inner.decode(data, message_class)
        except Exception:
            raise connect_error(undecodable()) from None


#: Binary first, as the library's own default list has it.
CODECS: tuple[Codec, ...] = (_Decoding(proto_binary_codec()), _Decoding(proto_json_codec()))


def _unary(method: Method) -> Callable[..., Awaitable[Any]]:
    async def call(self: object, request: Any, ctx: Any) -> Any:
        return await invoke(
            method,
            request,
            headers=list(ctx.request_headers.allitems()),
            peer=peer_host(ctx.client_address),
        )

    return call


def _server_stream(method: Method) -> Callable[..., Any]:
    async def call(self: object, request: Any, ctx: Any) -> Any:
        # A stream's handler answers once, today always with a refusal
        # (`rpc/watch.py`); 3c's host yields from it instead.
        yield await invoke(
            method,
            request,
            headers=list(ctx.request_headers.allitems()),
            peer=peer_host(ctx.client_address),
        )

    return call


def _service_app(service: str, methods: list[Method]) -> Any:
    """The generated ASGI app of ``service``, over an adapter of its ``Protocol``."""
    short = service.rpartition(".")[2]
    stem = methods[0].input.desc().file.proto.name.removesuffix(".proto").replace("/", ".")
    module = importlib.import_module(f"app.contract.{stem}_connect")
    protocol = getattr(module, short)
    application = getattr(module, f"{short}ASGIApplication")
    namespace: dict[str, Any] = {}
    for method in methods:
        if not hasattr(protocol, method.attribute):
            raise RuntimeError(f"{service} has no method {method.attribute}")
        namespace[method.attribute] = (
            _server_stream(method) if method.streaming else _unary(method)
        )
    adapter = type(f"{short}Adapter", (protocol,), namespace)
    return application(adapter(), codecs=CODECS)


def _is_native_grpc(scope: Scope) -> bool:
    for name, value in scope.get("headers", ()):
        if name.lower() == b"content-type":
            media = value.decode("latin-1").split(";", 1)[0].strip().lower()
            return media == "application/grpc" or media.startswith("application/grpc+")
    return False


class _Services:
    """The seventeen service apps under one mount, chosen by the path's first part.

    Not a Starlette ``Router`` of ``Mount``s: a nested mount moves
    ``root_path``, and ``connectrpc`` finds its endpoint by stripping exactly
    the mount's root from the path. This hands each app the scope it was given.
    """

    def __init__(self, apps: dict[str, Any]) -> None:
        self._apps = apps

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            return
        # Not «is it 1.1»: ASGI makes `http_version` optional and reads a
        # missing one as "1.1", so only a scope that says 2 or 3 is let past.
        if scope.get("http_version") not in ("2", "3") and _is_native_grpc(scope):
            await PlainTextResponse(GRPC_REFUSED, status_code=415)(scope, receive, send)
            return
        path = scope["path"].removeprefix(scope.get("root_path", ""))
        service = "/" + path.lstrip("/").partition("/")[0]
        app = self._apps.get(service)
        if app is None:
            await Response(status_code=404)(scope, receive, send)
            return
        await app(scope, receive, send)


def rpc_app() -> _Services:
    """Every service of the contract, each over its adapter, as one ASGI app."""
    by_service: dict[str, list[Method]] = {}
    for method in METHODS.values():
        by_service.setdefault(method.service, []).append(method)
    apps = {}
    for service, methods in sorted(by_service.items()):
        app = _service_app(service, methods)
        apps[app.path] = app
    return _Services(apps)
