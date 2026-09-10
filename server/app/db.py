"""Async SQLAlchemy engine, session factory and schema bootstrap."""

from __future__ import annotations

from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker, create_async_engine
from sqlalchemy.orm import DeclarativeBase

from app.config import get_settings


class Base(DeclarativeBase):
    pass


_settings = get_settings()

engine = create_async_engine(_settings.database_url, echo=False, future=True)
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
    from app import models  # noqa: F401  (import registers the mappers)

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
