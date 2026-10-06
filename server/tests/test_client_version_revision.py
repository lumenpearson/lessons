"""``0018``, the revision that records each phone's last app version.

The ordinary additive shape: one nullable integer column on ``device_tokens``,
with no default, no index and no key, so it goes on before the merge and the
running code never notices it (``docs/specs/2026-10-05-server-v2-design.md``,
decision 15). Held as ``0015``'s columns are held: on Postgres it builds
exactly what the model declares, it asks whether the column is there before
it adds it, and on the way down it drops that column and nothing else.

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

from app.db import EXPECTED_REVISION
from app.models import DeviceToken

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0018_device_client_version.py"


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0018", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _render(function) -> list[str]:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return [" ".join(part.split()) for part in rendered.getvalue().split(";") if part.strip()]


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def _columns(database: Path) -> set[str]:
    with sqlite3.connect(database) as db:
        return {row[1] for row in db.execute("PRAGMA table_info(device_tokens)")}


def test_the_revision_follows_0017_and_the_code_expects_it_or_a_later_one() -> None:
    """Not the head by name: ``test_schema_version.py`` pins the head, and a
    second pin here failed the moment ``0019`` moved it."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0018", "0017")
    assert EXPECTED_REVISION >= "0018"


def test_on_postgres_it_adds_exactly_the_column_the_model_declares() -> None:
    column = DeviceToken.__table__.c.client_version
    assert column.nullable
    assert column.server_default is None
    assert not column.foreign_keys
    assert not any("client_version" in index.columns for index in DeviceToken.__table__.indexes)
    kind = column.type.compile(dialect=postgresql.dialect())
    assert kind == "INTEGER"
    assert _render(_revision().upgrade) == [
        f"ALTER TABLE device_tokens ADD COLUMN client_version {kind}"
    ]


def test_on_the_way_down_it_drops_the_column_and_nothing_else() -> None:
    assert _render(_revision().downgrade) == [
        "ALTER TABLE device_tokens DROP COLUMN client_version"
    ]


def test_a_file_at_0017_gets_the_column_and_a_file_that_has_it_is_left_alone(tmp_path) -> None:
    """``0001`` builds today's schema, the column included, so the first
    ``upgrade head`` meets the guard with the column there. Taking the column
    out and the stamp back to ``0017`` then makes a file shaped like a
    deployment at ``0017``."""
    database = tmp_path / "at17.db"
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr
    assert "client_version" in _columns(database)

    with sqlite3.connect(database) as db:
        db.execute("ALTER TABLE device_tokens DROP COLUMN client_version")
        db.execute("UPDATE alembic_version SET version_num = '0017'")
    assert "client_version" not in _columns(database)

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    assert "client_version" in _columns(database)

    down = _alembic(database, "downgrade", "0017")
    assert down.returncode == 0, down.stderr
    assert "client_version" not in _columns(database)
