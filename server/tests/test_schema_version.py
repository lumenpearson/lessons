"""That the code and the migrations agree on which schema they need.

This file exists because of an outage. A merge deployed code that read
``classes.diary_provider`` to a database that did not have it yet: nothing
failed at startup, and the first ORM read of a class blew up instead — which
for the bot is the middleware, so every single update died, with an
``UndefinedColumnError`` forty frames down a traceback that named
``session.get`` rather than the deploy that caused it.

Two things came out of it. ``/warmup`` now says so in one line, and the
constant it compares against is pinned here — a hardcoded revision that has to
be bumped by hand is exactly the kind that silently stops being true.
"""

from __future__ import annotations

from pathlib import Path

import pytest
from alembic.config import Config
from alembic.script import ScriptDirectory
from httpx import ASGITransport, AsyncClient
from sqlalchemy import text as sa_text

from app.db import EXPECTED_REVISION, current_revision
from app.main import app

SERVER_ROOT = Path(__file__).resolve().parent.parent


@pytest.fixture(autouse=True)
async def no_stamp(session):
    """Guarantee the database starts and ends with no ``alembic_version``.

    ``alembic_version`` is alembic's table, not the model's, so it is not in
    ``Base.metadata`` — which means the autouse ``drop_all`` that resets every
    other table walks straight past it. A revision stamped by one test then
    survives into the next, and the test that expects «behind» reads the row
    the test before it wrote. Dropping it at both ends is cheaper than
    remembering which tests care.
    """
    await _unstamp(session)
    yield
    await _unstamp(session)


async def _unstamp(session) -> None:
    await session.execute(sa_text("DROP TABLE IF EXISTS alembic_version"))
    await session.commit()


async def _stamp(session, revision: str) -> None:
    """Put the database at ``revision``, the way a real migration leaves it."""
    await session.execute(
        sa_text("CREATE TABLE alembic_version (version_num VARCHAR(32) NOT NULL)")
    )
    await session.execute(
        sa_text("INSERT INTO alembic_version (version_num) VALUES (:v)"), {"v": revision}
    )
    await session.commit()


def _head() -> str:
    config = Config(str(SERVER_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(SERVER_ROOT / "migrations"))
    return ScriptDirectory.from_config(config).get_current_head()


def test_the_constant_the_app_ships_is_the_real_migration_head():
    """The one assertion this file is for.

    ``app.db.EXPECTED_REVISION`` cannot be read from ``migrations/`` at
    runtime — that directory is not in the serverless bundle, because alembic
    is deliberately not a runtime dependency. So it is a constant, and a
    constant is only as good as whatever notices when it drifts. This is that
    whatever: add 0007 without touching it, and the build stops here.
    """
    assert EXPECTED_REVISION == _head()


def test_every_revision_is_reachable_from_the_head():
    """One chain, no forks. Two heads mean `upgrade head` is ambiguous and
    picks one, which is a migration silently not applied."""
    config = Config(str(SERVER_ROOT / "alembic.ini"))
    config.set_main_option("script_location", str(SERVER_ROOT / "migrations"))
    script = ScriptDirectory.from_config(config)

    assert len(script.get_heads()) == 1


async def test_a_database_without_alembic_says_so_rather_than_raising(session):
    """The test database is built by ``create_all``, so it has no
    ``alembic_version`` table at all — a normal state for local development,
    and one the check has to survive rather than crash on."""
    assert await current_revision(session) is None


async def test_warmup_reports_a_schema_it_cannot_identify_as_degraded(session):
    """Not «ok». The endpoint's whole job here is to be the thing that noticed;
    answering «ok» to a database it could not identify would make it the thing
    that hid it."""
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        response = await client.get("/api/v1/warmup")

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "degraded"
    assert body["schema"] == "unknown"
    assert "alembic_version" in body["detail"]


async def test_warmup_says_ok_when_the_database_is_at_the_expected_revision(session):
    """The happy path, faked by writing the table the real migrations write."""
    await _stamp(session, EXPECTED_REVISION)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        body = (await client.get("/api/v1/warmup")).json()

    assert body == {
        "status": "ok",
        "api_version": body["api_version"],
        "schema": EXPECTED_REVISION,
    }


@pytest.mark.parametrize("behind", ["0004", "0005"])
async def test_a_database_behind_the_code_names_both_revisions(session, behind):
    """So the reader does not have to go and find out which one they are on —
    the answer and the command are in the same response."""
    await _stamp(session, behind)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        body = (await client.get("/api/v1/warmup")).json()

    assert body["status"] == "degraded"
    assert body["schema"] == behind
    assert body["expected_schema"] == EXPECTED_REVISION
    assert "alembic upgrade head" in body["detail"]


async def test_a_database_ahead_of_the_code_is_not_told_to_migrate(session):
    """The opposite direction, and it is the documented procedure, not a fault.

    «Migrate, then merge» puts every correct deploy through a window where the
    database is one revision ahead of the code that is still running. Reusing
    the «behind» wording there sends the reader to run a migration they have
    just run, and away from the only thing that could actually be wrong — a
    deploy that never arrived.
    """
    ahead = f"{int(EXPECTED_REVISION) + 1:04d}"
    await _stamp(session, ahead)

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        body = (await client.get("/api/v1/warmup")).json()

    assert body["status"] == "degraded"
    assert body["schema"] == ahead
    assert body["expected_schema"] == EXPECTED_REVISION
    assert "alembic upgrade head" not in body["detail"]
    assert "деплой" in body["detail"]


async def test_a_revision_that_is_not_a_number_does_not_guess_a_direction(session):
    """Nothing here produces one, but the comparison is integer-based and a
    hash would make «ahead» and «behind» equally unfounded."""
    await _stamp(session, "a1b2c3d4")

    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        body = (await client.get("/api/v1/warmup")).json()

    assert body["status"] == "degraded"
    assert body["schema"] == "a1b2c3d4"
    assert "расходятся" in body["detail"]


def test_the_bootstrap_script_leaves_a_database_alembic_can_carry_forward(tmp_path):
    """``scripts.init_db`` builds the schema from the models and says so.

    The schema ``create_all`` writes is the schema the last revision produces,
    so a database it made is at the head — but while nothing wrote the row,
    that database claimed no revision at all, and two things lied about it at
    once: ``/api/v1/warmup`` called a brand-new database «отстающей» for ever,
    and a later ``alembic upgrade head`` started from ``0001`` and died on the
    first column it was told to add to a table that already had it.
    """
    import os
    import sqlite3
    import subprocess
    import sys

    database = tmp_path / "boot.db"
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    result = subprocess.run(
        [sys.executable, "-m", "scripts.init_db"],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
        check=True,
    )
    assert f"Stamped alembic_version at {EXPECTED_REVISION}" in result.stdout, result.stderr

    stored = sqlite3.connect(database).execute("select version_num from alembic_version").fetchall()
    assert stored == [(EXPECTED_REVISION,)]

    # The point of the row, rather than the row itself: alembic finds nothing
    # left to do. Without it the chain runs from the beginning against a schema
    # that already has everything in it — here that dies on «No support for
    # ALTER of constraints in SQLite dialect», and on the Postgres this is
    # actually done to, on the first column 0002 is told to add twice.
    carried = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )
    assert carried.returncode == 0, carried.stderr

    # Run again: a second bootstrap of the same database must not touch what
    # the first one recorded, because by then the row may be a real history.
    again = subprocess.run(
        [sys.executable, "-m", "scripts.init_db"],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
        check=True,
    )
    assert f"Left alembic_version as it was: {EXPECTED_REVISION}" in again.stdout


def test_the_whole_chain_runs_from_nothing_on_sqlite(tmp_path):
    """Every revision, in order, against an empty file.

    The test above starts from a database `create_all` already built and
    stamped, so `alembic upgrade head` finds nothing to do and no revision
    body ever executes. That is a real thing to check and it is not this one:
    run the chain for real and `0013` died with «No support for ALTER of
    constraints in SQLite dialect», leaving the file stamped at `0012` with a
    revision the operator believed had half applied. `0011` and `0012` carry
    the same guard; `0013` did not.

    Postgres is the production path, so this does not prove the schema — the
    revision bodies that matter are skipped here. It proves the chain can be
    walked end to end, which is what anyone verifying it locally does, and
    what a school running its own server on SQLite does once.
    """
    import os
    import subprocess
    import sys

    database = tmp_path / "chain.db"
    database.touch()
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    upgrade = subprocess.run(
        [sys.executable, "-m", "alembic", "upgrade", "head"],
        env=env,
        capture_output=True,
        text=True,
        cwd=str(Path(__file__).resolve().parents[1]),
    )
    assert upgrade.returncode == 0, upgrade.stderr

    current = subprocess.run(
        [sys.executable, "-m", "alembic", "current"],
        env=env,
        capture_output=True,
        text=True,
        cwd=str(Path(__file__).resolve().parents[1]),
    )
    assert EXPECTED_REVISION in current.stdout, current.stdout
