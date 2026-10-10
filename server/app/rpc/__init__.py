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
import logging
from collections.abc import Awaitable, Callable, MutableMapping
from typing import Any

from connectrpc.code import Code
from connectrpc.codec import Codec, proto_binary_codec, proto_json_codec
from connectrpc.compression import Compression
from connectrpc.compression.gzip import GzipCompression
from connectrpc.errors import ConnectError
from starlette.responses import PlainTextResponse, Response

from app.api.deps import peer_host
from app.rpc.call import invoke, stream
from app.rpc.errors import INTERNAL_MESSAGE, connect_error, undecodable
from app.rpc.methods import METHODS, Method

log = logging.getLogger(__name__)

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
        # A message that cannot be serialised — a lone surrogate in a string —
        # would otherwise leave as ``unknown`` carrying the exception's text.
        try:
            return self._inner.encode(message)
        except Exception:
            log.exception("v2 answer could not be encoded")
            raise ConnectError(Code.INTERNAL, INTERNAL_MESSAGE) from None

    def decode(self, data: bytes | bytearray, message_class: type[Any]) -> Any:
        try:
            return self._inner.decode(data, message_class)
        except Exception:
            raise connect_error(undecodable()) from None


#: Binary first, as the library's own default list has it.
CODECS: tuple[Codec, ...] = (_Decoding(proto_binary_codec()), _Decoding(proto_json_codec()))


class _Decompressing:
    """A compression that answers ``REQUEST_UNDECODABLE`` where the library's raises.

    A body that claims ``Content-Encoding: gzip`` and is not gzip makes
    ``gzip``/``zlib`` raise ``zlib.error`` out of ``connectrpc``'s request
    reader, ahead of any codec, and that is a ``500`` with a traceback (#337).
    The library's own refusals are ``ConnectError``s and pass unchanged — a body
    past the read limit stays ``RESOURCE_EXHAUSTED``.
    """

    def __init__(self, inner: Compression) -> None:
        self._inner = inner

    def name(self) -> str:
        return self._inner.name()

    def compress(self, data: bytes | bytearray | memoryview) -> bytes:
        return self._inner.compress(data)

    def decompress(
        self, data: bytes | bytearray | memoryview, read_max_bytes: int | None = None
    ) -> bytes:
        try:
            return self._inner.decompress(data, read_max_bytes)
        except ConnectError:
            raise
        except Exception:
            raise connect_error(undecodable()) from None


#: What the library accepts by default (gzip; identity is always added), wrapped.
COMPRESSIONS: tuple[Compression, ...] = (_Decompressing(GzipCompression()),)


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
    def call(self: object, request: Any, ctx: Any) -> Any:
        # The generator itself, not one wrapped in another: connectrpc closes
        # what it is handed when the client goes, and a wrapper's close would
        # not reach the stream inside it (`call.stream` says why that matters).
        return stream(
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
    return application(adapter(), codecs=CODECS, compressions=COMPRESSIONS)


def is_native_grpc(scope: Scope) -> bool:
    """Whether a request is native gRPC — ``application/grpc`` or
    ``application/grpc+…``, never gRPC-Web — as the guard below and the host's
    root (``app/host.py``) both ask it."""
    for name, value in scope.get("headers", ()):
        if name.lower() == b"content-type":
            media = value.decode("latin-1").split(";", 1)[0].strip().lower()
            return media == "application/grpc" or media.startswith("application/grpc+")
    return False


def _lenient_utf8(raw: bytes) -> bytes:
    """``raw`` as UTF-8: itself if it is, otherwise read as Latin-1 and re-encoded."""
    try:
        raw.decode("utf-8")
    except UnicodeDecodeError:
        return raw.decode("latin-1").encode("utf-8")
    return raw


def _readable(scope: Scope) -> Scope:
    """A copy of ``scope`` whose headers and query string ``connectrpc`` can decode.

    The library reads both with ``bytes.decode()``, which is UTF-8, and a
    header whose value is the Latin-1 byte 0xE9 raises ``UnicodeDecodeError`` out of it:
    ``unknown`` with the exception's text, a 500 (#338). v1 reads headers as
    Starlette does, as Latin-1, and never fails on one, so a byte sequence that
    is not UTF-8 is read that way here, and what it means is judged where it
    is read: the gate refuses a token that is not one, the codec a query that
    does not decode. The caller's scope is not touched; valid UTF-8 is left as
    it is.
    """
    return {
        **scope,
        "headers": [
            (_lenient_utf8(name), _lenient_utf8(value)) for name, value in scope.get("headers", ())
        ],
        "query_string": _lenient_utf8(scope.get("query_string", b"")),
    }


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
        if scope.get("http_version") not in ("2", "3") and is_native_grpc(scope):
            await PlainTextResponse(GRPC_REFUSED, status_code=415)(scope, receive, send)
            return
        path = scope["path"].removeprefix(scope.get("root_path", ""))
        service = "/" + path.lstrip("/").partition("/")[0]
        app = self._apps.get(service)
        if app is None:
            await Response(status_code=404)(scope, receive, send)
            return
        await app(_readable(scope), receive, send)


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
