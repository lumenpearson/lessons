"""Who is allowed to do what — asked here, decided in :mod:`app.services.roles`.

The role ladder moved to ``services/`` because the services the API shares
with the bot ask it too — ``services/linking`` for what a linked phone may
write, ``services/access`` for what a request may be granted — and a service
may not import the bot (#205). The handlers and the middleware have always
asked here, so every name is imported back and is the very same function.
"""

from __future__ import annotations

from app.services.roles import (
    can_grant,
    claim_phone_invites,
    default_class_for,
    get_membership,
    get_role,
    is_env_owner,
    list_memberships,
    require_role,
)

__all__ = [
    "can_grant",
    "claim_phone_invites",
    "default_class_for",
    "get_membership",
    "get_role",
    "is_env_owner",
    "list_memberships",
    "require_role",
]
