"""The generic gate: who may call a method, decided from the method itself.

Every method of the contract names its credential (``(lessons.v2.auth)``) and,
when it acts on the class, its least role (``(lessons.v2.min_role)``).
:data:`TABLE` is those two options, read once at import from the descriptors;
:func:`admit` asks them of every call before any handler runs, so a handler
cannot forget a check — it never makes one
(``docs/specs/2026-10-05-server-v2-design.md``, decision 3). In this order:

1. the client version, so an old APK with a dead token is told to update
   rather than to sign in again;
2. for a diary method, whether the diary runs at all — before any token is
   resolved, so that a deployment without ``DIARY_SECRET`` touches no session
   row (#302: v1 expires every session it is asked about);
3. the bearer of the method's kind, by ``api/deps.py``'s rules, with the
   device's class, its ``last_seen_at``, and beside it the client version
   (decision 15);
4. a linked account, for ``DEVICE_LINKED`` and for any least role above
   viewer, refused **before** the role is read, as v1's three role checks do;
5. the role, read with ``linking.effective_role`` on every call, so a role
   taken away in the bot is gone here in the same instant.
"""

from __future__ import annotations

from collections.abc import Sequence
from dataclasses import dataclass

from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import bearer, find_device, header, touch_last_seen
from app.config import Settings
from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.options_pb import AuthKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.crypto import diary_enabled
from app.models import DeviceToken, DiarySession, Role, SchoolClass
from app.rpc.errors import Refusal
from app.rpc.methods import METHODS, Method
from app.rpc.values import proto_name
from app.services import linking
from app.services.diary import DiaryDisabled, find_session

#: The header every v2 request carries its client's version in.
CLIENT_HEADER = "x-lessons-client"

#: The largest versionCode an APK can carry: `android/app/build.gradle.kts`
#: refuses to build above it, because Google Play will not publish above it.
MAX_CLIENT_VERSION = 2_100_000_000

#: Each method's ``(auth, min_role)``, as the contract declares them. Compared
#: with the descriptors, read independently, for all 76 methods by
#: ``test_rpc_gate.py``.
TABLE: dict[str, tuple[AuthKind, ProtoRole | None]] = {
    key: (method.auth, method.min_role) for key, method in METHODS.items()
}

#: v1's words for each refusal the gate makes (``api/deps.py``,
#: ``api/diary.py``, the three role checks), kept, because the shell's
#: words are v1's wherever v1 had them.
MISSING_BEARER = "Missing bearer token"
UNKNOWN_DEVICE = "Invalid token"
UNKNOWN_DIARY_SESSION = "Diary session is not valid"
CLASS_GONE = "Class no longer exists"
NOT_LINKED = "device is not linked"
#: New in v2, so English like v1's own generic answers.
BAD_CLIENT_HEADER = f"X-Lessons-Client must be a whole number between 1 and {MAX_CLIENT_VERSION}"
CLIENT_TOO_OLD = "This app is older than the oldest version this server answers; update it"

#: Header lines of a request, names in any case, repeated lines kept.
Headers = Sequence[tuple[str, str]]


@dataclass
class Admitted:
    """What the gate found out about a call it let through."""

    #: The ``X-Lessons-Client`` version, when the client sent a usable one.
    client_version: int | None = None
    device: DeviceToken | None = None
    school_class: SchoolClass | None = None
    #: The linked account's role in the class; ``None`` for an unlinked device
    #: or an account that is not a member. Read for every device method,
    #: because ``GetMe`` and the window's ``DeviceAccess`` answer it.
    role: Role | None = None
    diary: DiarySession | None = None


def client_version(headers: Headers, settings: Settings) -> int | None:
    """The caller's ``X-Lessons-Client``, or a refusal (decision 9).

    A missing header is never refused, with a minimum set or not: the minimum
    retires the family's old APKs, which all send it, and a third-party client
    or a ``curl`` without it is not an old APK. A header that is not a whole
    number from 1 to :data:`MAX_CLIENT_VERSION` is refused only when a minimum
    is set — without one there is nothing to compare it with, and it is
    ignored like a missing one.
    """
    raw = header(headers, CLIENT_HEADER)
    if raw is None:
        return None
    text = raw.strip()
    # Ten ASCII digits at most before `int` is asked, so a header of a million
    # digits is never parsed; then the build's own ceiling.
    version = int(text) if text.isascii() and text.isdecimal() and len(text) <= 10 else 0
    if version > MAX_CLIENT_VERSION:
        version = 0
    minimum = settings.min_client_version
    if minimum <= 0:
        return version or None
    if version <= 0:
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            BAD_CLIENT_HEADER,
            violations=[("X-Lessons-Client", BAD_CLIENT_HEADER)],
        )
    if version < minimum:
        raise Refusal(ErrorReason.CLIENT_TOO_OLD, CLIENT_TOO_OLD, min_version=minimum)
    return version


def _needs_link(method: Method) -> bool:
    above_viewer = method.min_role is not None and method.min_role > ProtoRole.VIEWER
    return method.auth is AuthKind.DEVICE_LINKED or above_viewer


async def admit(
    method: Method, session: AsyncSession, settings: Settings, headers: Headers
) -> Admitted:
    """Everything the gate checks, in the order the module says, or a refusal."""
    admitted = Admitted(client_version=client_version(headers, settings))
    if method.auth is AuthKind.NONE:
        return admitted

    token = bearer(header(headers, "authorization"))
    if method.auth is AuthKind.DIARY:
        if not diary_enabled():
            raise DiaryDisabled("DIARY_SECRET is unusable")
        row = await find_session(session, token) if token is not None else None
        if row is None:
            sentence = MISSING_BEARER if token is None else UNKNOWN_DIARY_SESSION
            raise Refusal(ErrorReason.DIARY_TOKEN_INVALID, sentence)
        admitted.diary = row
        return admitted

    device = await find_device(session, token) if token is not None else None
    if device is None:
        sentence = MISSING_BEARER if token is None else UNKNOWN_DEVICE
        raise Refusal(ErrorReason.DEVICE_TOKEN_INVALID, sentence)
    await touch_last_seen(session, device, client_version=admitted.client_version)
    # After the touch, not before: a rollback inside it expires what the
    # session holds, and this is the row the handler is about to read.
    school_class = await session.get(SchoolClass, device.class_id)
    if school_class is None:
        raise Refusal(ErrorReason.RESOURCE_NOT_FOUND, CLASS_GONE, resource="class")
    if _needs_link(method) and device.telegram_id is None:
        raise Refusal(ErrorReason.DEVICE_NOT_LINKED, NOT_LINKED)

    role = await linking.effective_role(session, device)
    if method.min_role is not None and method.min_role > ProtoRole.VIEWER:
        least = Role[method.min_role.name]
        if role is None or not role.at_least(least):
            raise Refusal(
                ErrorReason.ROLE_REQUIRED,
                f"{least.value} role required",
                role=proto_name(method.min_role),
            )
    admitted.device, admitted.school_class, admitted.role = device, school_class, role
    return admitted
