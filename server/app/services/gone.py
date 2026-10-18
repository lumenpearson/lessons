"""A row another writer deleted between a write's read and its commit (#384).

A write that changes a row reads it, changes it in memory and commits. When
somebody else deletes the row in between, the flush's ``UPDATE`` matches
nothing and SQLAlchemy raises ``StaleDataError``. Nothing is written, and the
answer is the one a moment later would have had anyway: the row is gone. It
is never a server error, and never a reason to make the row again — an upsert
that meets it says «not found» too, because whoever deleted the row meant it
to be gone, and a write that re-created it would undo them without either
side being told.

Each shell says it once, in its own words: v1 in the route class every router
is built with (``api/routing.py``), v2 in ``rpc/call.invoke``, and the bot's
screens through :func:`committed`, each with the sentence it already says for
a row that is not there. Only ``StaleDataError`` is caught: a ``DELETE`` that
matched nothing is a warning of SQLAlchemy's, not an error, and lands where
it was meant to.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable

from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError


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
