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
from app.di import container


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

    # Shutdown closed the container and forgot it, and `app.state` names what
    # it was given rather than asking again — so without this every test after
    # this one in the same worker would be served from a container that has
    # shut its app scope. Nothing app-scoped holds a resource today, so a
    # closed one goes on answering and the damage would be invisible; the first
    # app-scope object that owns a connection or a client would take the rest
    # of the suite with it. Asking `container()` builds a fresh one.
    main.app.state.dishka_container = container()
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


# ---- asking the database which migration it is on -------------------------


class _AbortedTransaction(Exception):
    """asyncpg's ``InFailedSQLTransactionError``, by another name."""


class _PostgresLikeSession:
    """A session with the one rule SQLite does not have.

    This is a stand-in, said plainly: on SQLite a ``SELECT`` against a table
    that is not there raises and leaves the connection perfectly usable, so the
    defect cannot be reproduced against the database the tests run on. On
    Postgres — the only database this code has ever run against in production —
    a statement that raises aborts its transaction, and every statement after
    it fails with «current transaction is aborted» regardless of what it asks.
    That rule is what is modelled here, and it is the whole defect.
    """

    def __init__(self) -> None:
        self.aborted = False
        self.rolled_back = False

    async def execute(self, statement, *args, **kwargs):
        if self.aborted:
            raise _AbortedTransaction(
                "current transaction is aborted, "
                "commands ignored until end of transaction block"
            )
        if "alembic_version" in str(statement):
            self.aborted = True
            raise _AbortedTransaction('relation "alembic_version" does not exist')
        return object()

    async def rollback(self) -> None:
        self.aborted = False
        self.rolled_back = True


async def test_asking_for_the_revision_leaves_the_session_usable():
    """``current_revision`` swallows the error, which is right — a database
    with no ``alembic_version`` is an answer, not a fault. What it also has to
    do is end the transaction that error aborted, or it hands the next caller
    a session on which nothing works.

    It was harmless only because ``/api/v1/warmup`` happens to ask it last.
    That is a fact about that one caller, and the next one will not know it:
    a health page that asked for the revision and then counted classes would
    report the database empty, and «degraded» would turn into something
    worse-looking and wrong.
    """
    from sqlalchemy import text as sa_text

    from app import db

    session = _PostgresLikeSession()

    assert await db.current_revision(session) is None
    # The statement the next caller makes. Without the rollback it raises
    # «current transaction is aborted» whatever it asks for.
    await session.execute(sa_text("SELECT 1"))
    assert session.rolled_back is True
