"""Corrections laid over what came down: getting the rows in and out.

The rules for applying them live in ``services/diary_overrides.py``, which is
free of SQLAlchemy so that they can be tested without a database. This is the
other half. The rows are filed under the child — the diary, and the pupil's id
on it — rather than under the session or the login: a session ends every few
days and a correction must not, and the login is whatever the phone typed,
which nothing upstream vouches for (#165). So they are shared by everyone whose
own diary lists the child. Which children a session reaches is decided by
``DiaryService.child``, before any row here is read or written: the session's
own diary is asked for its pupils on every call, and an id it does not list
reaches nothing.

Nothing here commits. v1's routes commit after the call, and v2's ``invoke``
once for the whole request, so that a batch of corrections lands whole or not
at all (``docs/specs/2026-10-05-server-v2-design.md``, decision 4). The one
write that can meet a racing twin — one field two parents correct in the same
instant — concedes inside a savepoint rather than by failing a commit.

Beside ``diary_overrides`` rather than inside ``services/diary.py``, which is
the sessions: the two halves of one feature sit next to each other, and the
session module no longer carries a second subject in its middle.
"""

from __future__ import annotations

from sqlalchemy import ColumnElement, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from app.models import DiaryOverride
from app.providers.diary.registry import PETERSBURG, Scope, row_for

#: What every scope starts with — upper case on purpose; see `child_scope`.
SCOPE_PREFIX = "CHILD:"


class UnknownDiaryServer(ValueError):
    """A session whose diary this code cannot name a server for: a provider no
    implementation answers, or a «Сетевой город» region the allow-list does not
    hold. Refused rather than given a fallback scope, which would file one
    server's pupils beside another's."""


def child_scope(provider: str | None, region: str | None) -> str:
    """The diary a child's corrections are filed under; ``student_id`` is the
    child. Stored in ``DiaryOverride.login``, a column named for what it held
    before.

    Not the session: a diary session ends every few days, and a correction
    that went with it would vanish before anybody pressed «сбросить», with
    nothing left to reset. Not the login either: it is what the phone typed,
    and nothing upstream vouches for it.

    **Petersburg** is one city-wide server, and a ``NULL`` provider is a row
    from before there was a second one: ``CHILD:petersburg``.

    **«Сетевой город»** is taken to number its pupils per regional server (see
    below), so the scope names the server — ``CHILD:netschool:`` and the host
    of the region's origin, resolved through the allow-list exactly as the
    session's own calls are. The host, not the region key: if a region's
    origin ever moves, the corrections stop matching (lost) rather than attach
    to whichever child carries the same number on the new server (leaked), and
    two regions on one server rightly share its numbering. A region the
    allow-list does not hold raises :class:`UnknownDiaryServer`, never a guess.

    Those two are the only shapes. A child a diary lists by an id outside the
    provider's own numbering (``Student.id_space``) gets no scope at all —
    :meth:`DiaryService.scope_of` answers ``None`` and the routes offer it no
    corrections — because the number would then be the whole key, and nothing
    says that numbering names one person across families.

    Assumed and never observed: that a «Сетевой город» pupil id is unique per
    server rather than per school, and that two parents' accounts see one
    child under one id, in either diary. Were it per school, the school would
    have to come from the diary's own answer — never from the ``school_id`` a
    phone sends at registration, which nothing compares with the diary.

    **Upper case is the point of the prefix.** A key written before this
    function existed is ``login.strip().casefold()`` for Petersburg, or
    ``netschool:`` and 64 lower-case hex digits — casefolded or lower-case
    either way — so no scope can ever equal one, whatever login somebody types:
    «CHILD:petersburg» folds to ``child:petersburg``. Code reverted to before
    this change therefore cannot read a per-child row, or write into one, under
    any login; revision ``0017`` rewrites the old rows into scopes. What such
    code writes while it runs is filed under logins this code never reads, so
    a revert is not lossless — ``0017``'s docstring says how to fold them in.
    """
    # The row says which of the two shapes a provider's scope takes, so a
    # provider added to the table files its children without a branch here.
    row = row_for(provider or PETERSBURG)
    if row is None:
        raise UnknownDiaryServer(f"unknown diary provider {provider!r}")
    if row.scope is Scope.PROVIDER:
        return f"{SCOPE_PREFIX}{row.key}"
    server = row.server_of(region)
    if server is None:
        raise UnknownDiaryServer(f"no {row.key} server for region {region!r}")
    return f"{SCOPE_PREFIX}{row.key}:{server}"


def _of_child(scope: str, student_id: int) -> tuple[ColumnElement[bool], ColumnElement[bool]]:
    """The two halves of the key, as the WHERE clause every query here uses.

    Refuses anything that is not a scope, because a login passed where a scope
    belongs is #165 again — a string the phone chose deciding whose
    corrections are read — and it would type-check perfectly well.
    """
    if not scope.startswith(SCOPE_PREFIX):
        raise ValueError("corrections are filed under child_scope(), not under a login")
    return DiaryOverride.login == scope, DiaryOverride.student_id == student_id


async def load_corrections(
    session: AsyncSession, scope: str, student_id: int
) -> dict[str, dict[str, tuple[str, str | None]]]:
    """Every correction for one child, shaped the way the overlay wants it.

    All of them, not a date range: a target carries its date inside a string,
    and asking the database to reason about that would make the key format
    something the schema knows. A family has a handful of these.
    """
    rows = await session.scalars(select(DiaryOverride).where(*_of_child(scope, student_id)))
    corrections: dict[str, dict[str, tuple[str, str | None]]] = {}
    for row in rows:
        corrections.setdefault(row.target, {})[row.field] = (row.value, row.original)
    return corrections


async def list_overrides(
    session: AsyncSession, scope: str, student_id: int
) -> list[DiaryOverride]:
    """The raw rows, for the screen that lists and resets them."""
    rows = await session.scalars(
        select(DiaryOverride)
        .where(*_of_child(scope, student_id))
        .order_by(DiaryOverride.target, DiaryOverride.field)
    )
    return list(rows)


async def put_override(
    session: AsyncSession,
    scope: str,
    student_id: int,
    target: str,
    field: str,
    value: str,
    original: str | None,
) -> DiaryOverride:
    """Writes a correction, replacing the one that was there.

    Upsert rather than insert: correcting the same field twice is the ordinary
    case — a person fixes a typo in their own fix — and a second row would make
    the unique constraint the thing that reports it. The last writer wins,
    ``original`` included, whoever wrote the row before: there is one
    correction per field per child, and no column says whose it was.

    Nothing is committed. The row is flushed and read back, because every
    caller answers with it, and the database sets its stamps.
    """
    one = (
        *_of_child(scope, student_id),
        DiaryOverride.target == target,
        DiaryOverride.field == field,
    )
    row = await session.scalar(select(DiaryOverride).where(*one))
    if row is None:
        fresh = DiaryOverride(
            login=scope,
            student_id=student_id,
            target=target,
            field=field,
            value=value,
            original=original,
        )
        try:
            async with session.begin_nested():
                session.add(fresh)
                await session.flush()
        except IntegrityError:
            # Select-then-insert has a gap, and two adults who both see the
            # child — or one device double-tapping through a retry — fall into
            # it. The unique constraint catches it, which is the constraint
            # doing its job; what it must not do is become a 500 on the way
            # out: the answer to both writes is the row that is now there,
            # carrying the later value. Only the savepoint is rolled back, so
            # the caller's transaction, and what it wrote before this, go on.
            row = await session.scalar(select(DiaryOverride).where(*one))
            if row is None:
                raise
        else:
            await session.refresh(fresh)
            return fresh
    row.value = value
    row.original = original
    await session.flush()
    await session.refresh(row)
    return row


async def drop_override(
    session: AsyncSession, scope: str, student_id: int, target: str, field: str
) -> bool:
    """Resets one field. Nothing is committed.

    @return whether there was anything to reset.
    """
    row = await session.scalar(
        select(DiaryOverride).where(
            *_of_child(scope, student_id),
            DiaryOverride.target == target,
            DiaryOverride.field == field,
        )
    )
    if row is None:
        return False
    await session.delete(row)
    return True


async def drop_overrides(session: AsyncSession, scope: str, student_id: int) -> int:
    """Resets everything for one child, for everyone who sees that child — the
    corrections are the child's, not the account's — and never beyond it: the
    rows go through `list_overrides`, whose WHERE carries ``student_id``.
    Nothing is committed.

    @return how many were dropped.
    """
    rows = await list_overrides(session, scope, student_id)
    for row in rows:
        await session.delete(row)
    return len(rows)
