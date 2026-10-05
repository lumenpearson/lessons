"""Every method of the contract, read once from the generated descriptors.

Three readers need the same facts about a method — the gate (its credential
and least role), the RPC adapters (its service, its Python name) and the REST
transcoder (its ``google.api.http`` rule, its message classes) — so they are
read here, at import, into one table keyed ``"lessons.v2.<Service>/<Method>"``,
the Connect path without its leading slash. Nothing about a method is written
by hand: a method added to ``proto/`` is in this table on the next
``buf generate``, with no row to forget.
"""

from __future__ import annotations

import importlib
import pkgutil
import re
from dataclasses import dataclass
from types import ModuleType

from protobuf import DescMessage, DescMethod, Message

import app.contract.lessons.v2 as contract_v2
from app.contract.google.api import annotations_pb
from app.contract.lessons.v2 import options_pb

#: A path variable of a ``google.api.http`` template: ``{year}``, ``{task.id}``.
#: A ``{name=pattern}`` form is matched so that it can be refused at import —
#: the contract has none, and the transcoder binds only plain variables.
VARIABLE = re.compile(r"\{([^}=]+)(=[^}]*)?\}")


@dataclass(frozen=True)
class Binding:
    """A method's REST binding, as its ``google.api.http`` rule writes it."""

    #: "get", "post", "patch" or "delete".
    verb: str
    #: The template as the proto writes it, ``/v2/…``; it is served under ``/api``.
    path: str
    #: "" for no body, "*" for the whole request, or the request field it is.
    body: str

    @property
    def variables(self) -> list[str]:
        """The path's variables, dotted ones as written: ``["task.id"]``."""
        return [match.group(1) for match in VARIABLE.finditer(self.path)]


@dataclass(frozen=True)
class Method:
    """One method of the contract, and everything a transport needs to serve it."""

    #: The service's full name, ``"lessons.v2.ScheduleService"``.
    service: str
    #: The method's name, ``"GetScheduleWindow"``.
    name: str
    input: type[Message]
    output: type[Message]
    auth: options_pb.AuthKind
    #: The least role, or ``None`` where the method names none.
    min_role: options_pb.Role | None
    side_effect_free: bool
    streaming: bool
    #: ``None`` for a stream, which REST cannot carry.
    binding: Binding | None

    @property
    def key(self) -> str:
        """``"lessons.v2.ScheduleService/GetScheduleWindow"``: the Connect path."""
        return f"{self.service}/{self.name}"

    @property
    def attribute(self) -> str:
        """The method's name on the generated ``Protocol``: ``get_schedule_window``."""
        return re.sub(r"(?<!^)(?=[A-Z])", "_", self.name).lower()


def _module_of(file_name: str) -> ModuleType:
    """The generated module of a proto file: ``lessons/v2/me.proto`` → ``…me_pb``."""
    dotted = file_name.removesuffix(".proto").replace("/", ".")
    return importlib.import_module(f"app.contract.{dotted}_pb")


def message_class(desc: DescMessage) -> type[Message]:
    """The generated class of a top-level message, from its descriptor.

    Every request and response of the contract is top-level in its own file
    (Buf's ``RPC_REQUEST_STANDARD_NAME``), so its name in that module is its
    proto name; ``test_rpc_gate.py`` checks each class's ``desc()`` is the
    descriptor it was found by."""
    return getattr(_module_of(desc.file.proto.name), desc.name)


def _binding(desc: DescMethod) -> Binding | None:
    options = desc.proto.options
    if options is None or annotations_pb.ext_http not in options:
        return None
    rule = options[annotations_pb.ext_http]
    if rule.pattern is None:
        return None
    binding = Binding(verb=rule.pattern.field, path=rule.pattern.value, body=rule.body or "")
    for match in VARIABLE.finditer(binding.path):
        if match.group(2):
            raise RuntimeError(f"{desc.name}: no pattern variable is bound: {match.group(0)}")
    return binding


def _method(desc: DescMethod) -> Method:
    options = desc.proto.options
    if options is None or options_pb.ext_auth not in options:
        raise RuntimeError(f"{desc.parent.type_name}.{desc.name} names no credential")
    min_role = options[options_pb.ext_min_role] if options_pb.ext_min_role in options else None
    return Method(
        service=desc.parent.type_name,
        name=desc.name,
        input=message_class(desc.input),
        output=message_class(desc.output),
        auth=options[options_pb.ext_auth],
        min_role=min_role,
        side_effect_free=desc.idempotency.name == "NO_SIDE_EFFECTS",
        streaming=desc.method_kind != "unary",
        binding=_binding(desc),
    )


def _read() -> dict[str, Method]:
    methods: dict[str, Method] = {}
    for module in pkgutil.iter_modules(contract_v2.__path__):
        if not module.name.endswith("_pb"):
            continue
        generated = importlib.import_module(f"{contract_v2.__name__}.{module.name}")
        for service in generated.desc().services:
            for desc in service.methods:
                method = _method(desc)
                methods[method.key] = method
    return methods


#: Every method of ``lessons.v2``, keyed ``"lessons.v2.<Service>/<Method>"``.
METHODS: dict[str, Method] = _read()
