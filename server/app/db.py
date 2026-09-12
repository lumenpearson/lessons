"""Async SQLAlchemy engine, session factory and schema bootstrap."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

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


async def get_session() -> AsyncIterator[AsyncSession]:
    """FastAPI dependency."""
    async with SessionLocal() as session:
        yield session
