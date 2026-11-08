"""A row another writer deleted between a write's read and its commit (#384).

A write that changes a row reads it, changes it in memory and commits. When
somebody else deletes the row in between, the flush's ``UPDATE`` matches
nothing and SQLAlchemy raises ``StaleDataError``. Nothing is written, and the
answer is the one a moment later would have had anyway: the row is gone. It
is never a server error, and never a reason to make the row again — an upsert
that meets it says «not found» too, because whoever deleted the row meant it
to be gone, and a write that re-created it would undo them without either
side being told.

For guarded class or bell references, :func:`lock_parent` runs before the
first child write. A parent already removed has the same stale-row refusal;
one still present is kept through commit rather than failing a foreign key later.
No unrelated integrity failure is caught.

Each shell says it once, in its own words: v1 in the route class every router
is built with (``api/routing.py``), v2 in ``rpc/call.invoke``, and the bot's
screens through :func:`committed`, each with the sentence it already says for
a row that is not there. Only ``StaleDataError`` is caught: a ``DELETE`` that
matched nothing is a warning of SQLAlchemy's, not an error, and lands where
it was meant to.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from app.db import rows_affected
from app.models import BellSchedule, SchoolClass


async def lock_parent(
    session: AsyncSession,
    model: type[SchoolClass] | type[BellSchedule],
    row_id: int,
    *,
    exclusive: bool = False,
) -> None:
    """Keep a referenced parent until this transaction ends, or refuse a deleted one.

    PostgreSQL's key-share lock protects the identity without blocking ordinary
    parent edits. SQLite ignores FOR UPDATE, so a checked no-op UPDATE holds its
    write lock instead. Pending children must not autoflush before this check.
    A cascading delete takes the exclusive parent lock before touching children.
    """
    with session.no_autoflush:
        if session.get_bind().dialect.name == "postgresql":
            exists = (
                await session.scalar(
                    select(model.id)
                    .where(model.id == row_id)
                    .with_for_update(read=not exclusive, key_share=not exclusive)
                )
                is not None
            )
        else:
            result = await session.execute(
                update(model)
                .where(model.id == row_id)
                .values(name=model.name)
                .execution_options(synchronize_session=False)
            )
            exists = bool(rows_affected(result))
    if not exists:
        raise StaleDataError("The referenced parent was deleted")


async def committed(
    session: AsyncSession, write: Callable[[], Awaitable[object]] | None = None
) -> bool:
    """Run ``write``, if given, and commit ``session``; or roll it back and
    answer ``False`` when a row the write changed was deleted by another
    writer since it was read.

    ``write`` is for a write that reads again after it has changed a row —
    «📆 Период» marks one day after another, and each day's read flushes the
    change to the day before — so that the error can surface before the
    commit, by autoflush, and is caught there too.

    Rolled back here, so that the session can be used again — the bot's
    middleware commits it once the handler returns, and a session whose flush
    failed refuses every statement until it is rolled back. The rollback
    expires every instance the session holds, the class included: past a
    ``False``, read nothing through them, because an expired attribute loads
    lazily, and under asyncio that is a ``MissingGreenlet``.
    """
    try:
        if write is not None:
            await write()
        await session.commit()
    except StaleDataError:
        await session.rollback()
        return False
    return True
