"""``0015``, the revision that gives a second diary its columns.

Three things are pinned here, each of which was once wrong:

- on Postgres it builds exactly the columns the model declares — the rule
  ``CLAUDE.md`` sets for every revision, held by a test rather than by whoever
  applies it through the Neon connector (``test_quota.py`` does the same for
  ``0016``);
- it asks per column whether there is anything to add, rather than skipping
  SQLite wholesale. The wholesale skip stamped a ``lessons.db`` built before
  the model changed as ``0015`` with none of the columns, ``/warmup`` said
  «ok», and the first read of a class raised «no such column»; and on an empty
  Postgres, where ``0001``'s ``create_all`` has already built them, adding a
  column twice is a hard error;
- going down it unbinds the classes the old code cannot read, as well as
  expiring the sessions it would replay at the wrong upstream.

Nothing here connects to Postgres. The Postgres half renders the revision
offline, the way ``alembic upgrade --sql`` would, in this process.
"""

from __future__ import annotations

import importlib.util
import io
import os
import sqlite3
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import sqlalchemy as sa
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy.dialects import postgresql

from app.models import DiarySession, SchoolClass

SERVER_ROOT = Path(__file__).resolve().parent.parent
VERSIONS = SERVER_ROOT / "migrations" / "versions"

#: What 0015 adds, in its order. Named here once so a column the revision
#: loses, or gains without the model, fails the comparison below.
ADDED = {
    "diary_sessions": (
        "provider",
        "region",
        "kept_alive_at",
        "keepalive_attempted_at",
        "upstream_ok_at",
    ),
    "classes": ("diary_region", "diary_school_id", "diary_school_name"),
}
MODEL_TABLES = {"diary_sessions": DiarySession.__table__, "classes": SchoolClass.__table__}


def _load(filename: str, name: str):
    spec = importlib.util.spec_from_file_location(name, VERSIONS / filename)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _revision():
    return _load("0015_diary_provider_columns.py", "revision_0015")


def _render(function) -> list[str]:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return [" ".join(part.split()) for part in rendered.getvalue().split(";") if part.strip()]


def test_the_revision_builds_on_postgres_exactly_what_the_model_declares():
    """Each ``ADD COLUMN`` is the model's column compiled for Postgres, and
    each is the additive shape: nullable, no default, no index, no key — the
    shape that lets it go on before the merge. The revision's own guard runs
    here unpatched: offline it has nobody to ask and adds all eight, which is
    what a database at ``0014`` needs."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0015", "0014")
    dialect = postgresql.dialect()

    expected = []
    for table_name, columns in ADDED.items():
        table = MODEL_TABLES[table_name]
        for name in columns:
            column = table.c[name]
            assert column.nullable, name
            assert column.server_default is None, name
            assert not column.foreign_keys, name
            assert not any(name in index.columns for index in table.indexes), name
            expected.append(
                f"ALTER TABLE {table_name} ADD COLUMN {name} {column.type.compile(dialect=dialect)}"
            )

    assert _render(revision.upgrade) == expected


def test_the_way_down_unbinds_what_the_old_code_cannot_read_then_drops_the_columns():
    """The two updates come before the drops, because after them nothing says
    which session or class was «Сетевой город»'s."""
    statements = _render(_revision().downgrade)

    assert statements[:2] == [
        "UPDATE diary_sessions SET expired_at = CURRENT_TIMESTAMP "
        "WHERE provider IS NOT NULL AND provider <> 'petersburg' AND expired_at IS NULL",
        "UPDATE classes SET diary_provider = NULL "
        "WHERE diary_provider IS NOT NULL AND diary_provider <> 'petersburg'",
    ]
    dropped = [
        f"ALTER TABLE {table} DROP COLUMN {name}"
        for table, columns in ADDED.items()
        for name in columns
    ]
    assert sorted(statements[2:]) == sorted(dropped)


# ---- run for real, on SQLite ---------------------------------------------------


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def _columns(db: sqlite3.Connection, table: str) -> set[str]:
    return {row[1] for row in db.execute(f"PRAGMA table_info({table})")}


def _at_0014(database: Path) -> None:
    """A file shaped the way ``scripts.init_db`` left it before this batch.

    ``0001`` builds today's whole schema, so a chain from nothing never makes a
    ``0014`` database; taking this revision's columns and ``0016``'s table
    back out and putting the stamp back does.
    """
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr
    with sqlite3.connect(database) as db:
        for table, columns in ADDED.items():
            for name in columns:
                db.execute(f"ALTER TABLE {table} DROP COLUMN {name}")
        db.execute("DROP TABLE usage_counters")
        db.execute("UPDATE alembic_version SET version_num = '0014'")


def test_a_file_built_before_the_model_changed_gets_every_column(tmp_path):
    """The wholesale SQLite skip left this file stamped ``0016`` with none of
    the eight: «no such column: classes.diary_region» on the first read of a
    class, which for the bot is every update."""
    database = tmp_path / "at14.db"
    _at_0014(database)

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    with sqlite3.connect(database) as db:
        for table, columns in ADDED.items():
            assert set(columns) <= _columns(db, table), table
        assert db.execute("select version_num from alembic_version").fetchall() == [("0016",)]

    # What the app itself reads: every mapped column of both tables.
    engine = sa.create_engine(f"sqlite:///{database}")
    try:
        with engine.connect() as connection:
            connection.execute(sa.select(SchoolClass.__table__)).all()
            connection.execute(sa.select(DiarySession.__table__)).all()
    finally:
        engine.dispose()


def test_the_way_down_on_sqlite_expires_and_unbinds_then_the_way_up_again(tmp_path):
    database = tmp_path / "down.db"
    _at_0014(database)
    assert _alembic(database, "upgrade", "head").returncode == 0

    with sqlite3.connect(database) as db:
        for class_id, provider in ((1, "petersburg"), (2, "netschool")):
            db.execute(
                "INSERT INTO classes (id, name, join_code, join_mode, created_at, "
                "diary_provider) VALUES (?, ?, ?, 'OPEN', '2026-09-01 00:00:00', ?)",
                (class_id, f"9{class_id}", f"CODE000{class_id}", provider),
            )
        for session_id, provider in ((1, None), (2, "petersburg"), (3, "netschool")):
            db.execute(
                "INSERT INTO diary_sessions (id, token_hash, upstream_token, login, "
                "created_at, provider) VALUES (?, ?, 'sealed', 'parent', "
                "'2026-09-01 00:00:00', ?)",
                (session_id, f"hash{session_id}", provider),
            )

    down = _alembic(database, "downgrade", "0014")
    assert down.returncode == 0, down.stderr
    with sqlite3.connect(database) as db:
        for table, columns in ADDED.items():
            assert not set(columns) & _columns(db, table), table
        assert db.execute("SELECT id, diary_provider FROM classes ORDER BY id").fetchall() == [
            (1, "petersburg"),
            (2, None),
        ]
        expired = db.execute(
            "SELECT id FROM diary_sessions WHERE expired_at IS NOT NULL ORDER BY id"
        ).fetchall()
        assert expired == [(3,)]

    again = _alembic(database, "upgrade", "head")
    assert again.returncode == 0, again.stderr


def _pretending_to_be_postgres(database: Path, filename: str, name: str) -> None:
    """Run one revision's ``upgrade`` against a schema ``create_all`` built,
    with the connection's dialect answering «postgresql».

    That is the empty-Postgres path of ``docker compose up``: ``0001``'s
    ``create_all`` has already built today's schema when the later revisions
    run. CI has no Postgres, and the guards ask the inspector rather than the
    dialect's name, so a SQLite file that says it is Postgres reaches exactly
    the branch Postgres would — and a statement the guard should have held back
    reaches SQLite, which refuses it as Postgres would.
    """
    revision = _load(filename, name)
    engine = sa.create_engine(f"sqlite:///{database}")
    try:
        with engine.begin() as connection:
            connection.dialect.name = "postgresql"
            context = MigrationContext.configure(connection)
            with Operations.context(context):
                if hasattr(revision, "context"):
                    # alembic.context is a proxy only a running `env.py` fills.
                    revision.context = SimpleNamespace(is_offline_mode=lambda: False)
                revision.upgrade()
    finally:
        engine.dispose()


def test_on_a_schema_create_all_already_built_the_revision_adds_nothing(tmp_path):
    """Before the per-column guard this was ``duplicate column name: provider``
    — on Postgres «column "provider" of relation "diary_sessions" already
    exists», and the migrate service never finished."""
    database = tmp_path / "empty.db"
    database.touch()
    assert _alembic(database, "upgrade", "head").returncode == 0

    _pretending_to_be_postgres(database, "0015_diary_provider_columns.py", "revision_0015_pg")

    with sqlite3.connect(database) as db:
        for table, columns in ADDED.items():
            assert set(columns) <= _columns(db, table)


def test_0013_on_a_schema_create_all_already_built_adds_no_second_constraint(tmp_path):
    """The chain's other stop on an empty Postgres, one revision earlier:
    ``create_all`` already made ``uq_homework_per_subject_per_day``, and making
    it again is «relation already exists». Fixing 0015 alone only moved the
    failure here."""
    database = tmp_path / "empty13.db"
    database.touch()
    assert _alembic(database, "upgrade", "head").returncode == 0

    _pretending_to_be_postgres(
        database, "0013_one_homework_per_subject_per_day.py", "revision_0013_pg"
    )

    with sqlite3.connect(database) as db:
        schema = db.execute("SELECT sql FROM sqlite_master WHERE name = 'homework'").fetchone()[0]
    assert schema.count("uq_homework_per_subject_per_day") == 1
