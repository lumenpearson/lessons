"""What startup is allowed to do before it can answer the first request.

On a serverless deployment every cold start pays for this work, and the
database sits in another region, so a round trip here is not free. Emitting
create_all on each cold start also lets two simultaneous cold starts issue the
same DDL against Postgres and deadlock each other.
"""

from __future__ import annotations

import pytest

from app import main
from app.config import Settings


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


# ---- the settings the process starts from ---------------------------------


def test_a_mistyped_server_timezone_falls_back_instead_of_raising():
    """The same typo has to mean the same thing in both places it can be made.

    A zone stored on a class goes through ``timezones.resolve``, which falls
    back — «falling back is always better than a 500 on the one endpoint the
    widget depends on». ``TIMEZONE`` in the environment did not: it was handed
    straight to ``ZoneInfo``, so «/start» from somebody with no class yet died
    on ZoneInfoNotFoundError inside the handler. The button did nothing, the
    user was told nothing, and a class carrying the identical typo went on
    rendering its day.
    """
    assert Settings(timezone="Europe/Moskva").tz.key == "Europe/Moscow"
    assert Settings(timezone="").tz.key == "Europe/Moscow"
    # A zone that is real is still the one that is used.
    assert Settings(timezone="Asia/Yekaterinburg").tz.key == "Asia/Yekaterinburg"
