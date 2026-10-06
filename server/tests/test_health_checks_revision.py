"""``0019``, the revision that keeps what each self-check said last.

The ordinary additive shape: one new table, created where it is missing and
dropped on the way down, held as ``0016``'s is held - on Postgres it builds
exactly the DDL the model declares, so a column changed in the one and not
the other fails here before anybody applies it through the Neon connector.

Nothing here connects to Postgres: the Postgres half renders the revision
offline, the way ``alembic upgrade --sql`` would.
"""

from __future__ import annotations

import importlib.util
import io
import os
import sqlite3
import subprocess
import sys
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateTable

from app.db import EXPECTED_REVISION
from app.models import HealthCheck

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0019_health_checks.py"


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0019", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _words(sql: str) -> str:
    return " ".join(sql.replace(";", " ").split())


def _render(function) -> str:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return _words(rendered.getvalue())


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def test_the_revision_follows_0018_and_the_code_expects_it_or_a_later_one():
    """Not the head by name: ``test_schema_version.py`` pins the head, and a
    second pin here repeated #358's defect - it failed the moment a later
    revision moved the head, although nothing about ``0019`` had changed."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0019", "0018")
    assert EXPECTED_REVISION >= "0019"


def test_on_postgres_it_builds_exactly_what_the_model_declares(monkeypatch):
    revision = _revision()
    # Offline there is no database to ask whether the table exists; the
    # module's own guard answers «missing» in that mode, as here.
    monkeypatch.setattr(revision, "_missing", lambda table: True)

    model = CreateTable(HealthCheck.__table__).compile(dialect=postgresql.dialect())
    assert _render(revision.upgrade) == _words(str(model))
    assert _render(revision.downgrade) == "DROP TABLE health_checks"


def test_a_file_without_the_table_gets_it_and_loses_it_going_down(tmp_path):
    """Run for real, on a database at ``0018`` with no such table.

    The chain from nothing never gets here: ``0001`` builds today's whole
    schema, the table with it, and this revision finds it and does nothing,
    which is the other half and is checked first.
    """
    database = tmp_path / "health.db"
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr

    with sqlite3.connect(database) as db:
        db.execute("DROP TABLE health_checks")
        db.execute("UPDATE alembic_version SET version_num = '0018'")

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    with sqlite3.connect(database) as db:
        # (cid, name, type, notnull, default, pk)
        columns = {row[1]: row for row in db.execute("PRAGMA table_info(health_checks)")}
        assert list(columns) == [column.name for column in HealthCheck.__table__.columns]
        assert columns["name"][5] == 1
        assert [name for name, row in columns.items() if row[3]] == [
            "name", "status", "reason", "since", "checked_at",
        ]

    down = _alembic(database, "downgrade", "0018")
    assert down.returncode == 0, down.stderr
    with sqlite3.connect(database) as db:
        tables = {row[0] for row in db.execute("select name from sqlite_master")}
        assert "health_checks" not in tables
        assert "device_tokens" in tables
