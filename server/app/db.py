"""Async SQLAlchemy engine, session factory and schema bootstrap."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Any

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import get_settings
from app.database_url import normalise_database_url


class Base(DeclarativeBase):
    pass


_settings = get_settings()

# A pasted provider URL is rewritten rather than rejected: see database_url.py
# for what libpq accepts and asyncpg does not.
_url, _connect_args = normalise_database_url(_settings.database_url)

engine = create_async_engine(
    _url,
    echo=False,
    future=True,
    connect_args=_connect_args,
    # Serverless instances come and go, and a pooler closes idle connections
    # from its side; without this the first query after an idle spell fails on
    # a socket the pool still believes is good.
    pool_pre_ping=True,
)
SessionLocal = async_sessionmaker(engine, expire_on_commit=False, class_=AsyncSession)


if engine.dialect.name == "sqlite":

    @event.listens_for(engine.sync_engine, "connect")
    def _sqlite_enforce_foreign_keys(dbapi_connection, connection_record) -> None:
        """SQLite ignores foreign keys unless asked, per connection.

        Every ``ondelete="CASCADE"`` in ``models.py`` is a no-op without this,
        so deleting a class left its timetable, bells and device tokens behind
        as orphans that nothing could reach or clean up. Postgres needs no
        equivalent.
        """
        cursor = dbapi_connection.cursor()
        try:
            cursor.execute("PRAGMA foreign_keys=ON")
        finally:
            cursor.close()

        # SQLite's built-in ``lower()`` folds ASCII and nothing else, so
        # «Алгебра» did not match «алгебра» in the bot's homework search -
        # on the development file only, because Postgres folds Cyrillic
        # properly and production is Postgres. A search that behaves one way
        # for the developer and another for the class is a search nobody can
        # reason about, so the builtin is replaced with Python's, which knows
        # the whole alphabet. SQLite allows overriding it; the cost is a
        # Python call per row, and the rows here are one class's homework.
        dbapi_connection.create_function("lower", 1, _unicode_lower)
        dbapi_connection.create_function("upper", 1, _unicode_upper)


def _unicode_lower(value):
    """``None`` in, ``None`` out - SQL semantics, not Python's."""
    return value.lower() if isinstance(value, str) else value


def _unicode_upper(value):
    return value.upper() if isinstance(value, str) else value


async def init_db() -> None:
    """Create tables that do not exist yet.

    The schema is small and additive; if it ever needs destructive changes,
    introduce Alembic rather than extending this function.
    """
    # Both imports register mappers. The FSM table lives beside the storage
    # that uses it rather than in models.py, and importing only models here
    # meant a fresh Postgres bootstrapped by scripts.init_db had every table
    # but fsm_states - so the first multi-step conversation in the bot died
    # on "relation does not exist". The tests never saw it because collecting
    # test_fsm_storage.py imported the module before create_all ran.
    from app import fsm_storage, models  # noqa: F401

    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.create_all)


@asynccontextmanager
async def session_scope() -> AsyncIterator[AsyncSession]:
    """Transactional session for background/bot code."""
    async with SessionLocal() as session:
        try:
            yield session
            await session.commit()
        except Exception:
            await session.rollback()
            raise


def rows_affected(result: Any) -> int:
    """How many rows a DELETE or an UPDATE actually touched.

    Eleven places asked `result.rowcount or 0`, and every one of them was a
    sweep or a conditional write reporting what it had done. One name for it
    reads better than eleven copies of the same `or 0`.

    It also gives the cast a single home. `AsyncSession.execute` is typed as
    returning `Result[Any]`, which declares no `rowcount`; what comes back from
    a DML statement is a `CursorResult`, which does. That gap is SQLAlchemy's
    stubs, not ours - but it is the only thing standing between this project
    and a clean `attr-defined` check, and that check is worth having: it is
    what would have caught `Term.days`, the missing attribute that crashed
    «🗓 Четверти» on every press in production.

    `None` becomes 0: a driver is allowed not to report a count, and «не знаю»
    is closer to nought swept than to a crash in a cron tick.
    """
    return getattr(result, "rowcount", None) or 0


#: The Alembic revision this code needs the database to be at.
#:
#: Hardcoded rather than read from ``migrations/`` because that directory is
#: not in the serverless bundle — alembic is deliberately absent from
#: requirements.txt, since migrations are run from a workstation and never from
#: inside a request. A constant that has to be kept in step by hand would rot,
#: so ``tests/test_schema_version.py`` pins it to the real head and fails the
#: build if a new revision lands without updating it.
EXPECTED_REVISION = "0013"


async def current_revision(session: AsyncSession) -> str | None:
    """What revision the database actually reports, or ``None``.

    ``None`` means the question could not be answered — most often a local
    SQLite bootstrapped by ``create_all``, which never gets an
    ``alembic_version`` table. That is a normal state for development, so it is
    reported as «unknown» rather than treated as a fault.

    Swallowing the error is not enough on Postgres: a statement that raises
    leaves its transaction aborted, and every statement after it on the same
    session fails with ``InFailedSQLTransactionError`` no matter what it asks.
    So the session is rolled back before the answer is returned, and this
    function is safe to call anywhere rather than only last. It was only ever
    harmless because ``/api/v1/warmup`` happens to ask it last — a fact about
    that caller, which the next one will not know.
    """
    from sqlalchemy import text as sa_text

    try:
        result = await session.execute(sa_text("SELECT version_num FROM alembic_version"))
        row = result.first()
    except Exception:  # noqa: BLE001 - a missing table is an answer, not a crash
        await session.rollback()
        return None
    return str(row[0]) if row else None
