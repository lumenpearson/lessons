"""What startup is allowed to do before it can answer the first request.

On a serverless deployment every cold start pays for this work, and the
database sits in another region, so a round trip here is not free. Emitting
create_all on each cold start also lets two simultaneous cold starts issue the
same DDL against Postgres and deadlock each other.
"""

from __future__ import annotations

import pytest

from app import main


class _Dialect:
    def __init__(self, name: str) -> None:
        self.name = name


class _Engine:
    def __init__(self, name: str) -> None:
        self.dialect = _Dialect(name)


async def _run_lifespan(monkeypatch, dialect_name: str) -> bool:
    called = False

    async def _spy() -> None:
        nonlocal called
        called = True

    monkeypatch.setattr(main, "engine", _Engine(dialect_name))
    monkeypatch.setattr(main, "init_db", _spy)

    async with main.lifespan(main.app):
        pass
    return called


@pytest.mark.asyncio
async def test_postgres_startup_does_not_emit_ddl(monkeypatch):
    assert await _run_lifespan(monkeypatch, "postgresql") is False


@pytest.mark.asyncio
async def test_sqlite_startup_still_bootstraps_the_schema(monkeypatch):
    """Local development has no separate provisioning step to lean on."""
    assert await _run_lifespan(monkeypatch, "sqlite") is True
