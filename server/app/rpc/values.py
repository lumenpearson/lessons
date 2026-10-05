"""Model ↔ message conversions every handler shares.

One place for the conventions ``common.proto`` states, so that two handlers
cannot write a date, a time or a role two ways: a date is ``"YYYY-MM-DD"``, a
time of day ``"HH:MM"`` (v1 wrote seconds; v2 does not), an instant a
``Timestamp``, and an enum is matched to the model's by its member name —
``DayKind.SELF_STUDY`` is ``DAY_KIND_SELF_STUDY`` — so a value added to one
and not the other is a ``KeyError`` in a test rather than a silent default.
"""

from __future__ import annotations

from protobuf import Enum

from app.contract.lessons.v2 import options_pb
from app.models import Role


def proto_name(member: Enum) -> str:
    """The value's name as the proto writes it — ``"ROLE_ADMIN"``, not the
    generated member's ``ADMIN`` — which is what metadata and JSON carry."""
    return next(value.name for value in type(member).desc().values if value.number == member.value)


def role(value: Role | None) -> options_pb.Role:
    return options_pb.Role[value.name] if value is not None else options_pb.Role.UNSPECIFIED
