"""One reading of an ``update_mask``, for every ``Update*`` method (AIP-134).

Each update method names the fields its mask takes, and the proto comments
state what a masked field left unset means: it is cleared. What they leave to
the server is a request with no mask at all, which AIP-134 answers and v1's
``PATCH`` already did: change what the request sets. Written once here, so
that 3b's seven update methods cannot read a mask seven ways
(``docs/specs/2026-10-05-server-v2-3b-plan.md``, Ruling 5).
"""

from __future__ import annotations

from collections.abc import Sequence

from protobuf import Message
from protobuf.wkt import FieldMask

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rpc.errors import Refusal

#: Fixed, and naming no path: a path is the client's own text, and a refusal
#: never repeats what was sent.
NOT_CHANGEABLE = "update_mask names a field this method does not change"


def update_paths(
    mask: FieldMask | None, resource: Message | None, changeable: Sequence[str]
) -> list[str]:
    """The fields of ``resource`` an ``Update*`` request changes, in ``changeable``'s order.

    - A mask with paths is taken as it is. Every path must be one of
      ``changeable``, or the request is ``VALIDATION_FAILED`` on
      ``update_mask``; ``*`` is not taken. A masked path whose field
      ``resource`` leaves unset clears that field, as the proto comments say.
    - No mask, or one without paths, means every changeable field
      ``resource`` sets: AIP-134's implied mask, and v1's ``PATCH``, where
      only the fields present change.

    Each field comes once, in the method's order, whatever order the mask
    named them in, so a change that can be refused runs before the others.
    """
    if mask is None or not mask.paths:
        if resource is None:
            return []
        return [name for name in changeable if resource.has_field(name)]
    named = set(mask.paths)
    if not named <= set(changeable):
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            NOT_CHANGEABLE,
            violations=[("update_mask", NOT_CHANGEABLE)],
        )
    return [name for name in changeable if name in named]
