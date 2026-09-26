"""``0017``, the revision that files the diary corrections under the child.

What it has to be, each half held here:

- **data only** — rendered offline for PostgreSQL, the way ``alembic upgrade
  --sql`` and the Neon connector see it, it is a table lock, DELETEs and an
  UPDATE and no DDL, and the very statements it renders for PostgreSQL run on
  SQLite too (all but the lock, which SQLite has no word for), which is what
  "portable" has to mean when the chain is tested on SQLite;
- **exact** — on a real ``alembic upgrade`` it deletes every legacy «Сетевой
  город» row, keeps one row per field per child among the Petersburg ones (the
  newest, then the highest id) and re-files the rest, and touches no row that
  is already per-child;
- **idempotent**, and its downgrade leaves every row where it is — and a
  downgrade and an upgrade re-file what code older than it wrote meanwhile;
- **what it tells the owner** before the transaction is what it does: the
  counts in its docstring are the rows each destructive step then deletes.

Nothing here connects to Postgres.
"""

from __future__ import annotations

import importlib.util
import io
import os
import re
import sqlite3
import subprocess
import sys
from pathlib import Path

from alembic.migration import MigrationContext
from alembic.operations import Operations

from app.db import EXPECTED_REVISION
from app.services import diary as service

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0017_corrections_per_child.py"

#: What `services/diary.child_scope` answers for Petersburg — asserted below,
#: so the revision's literal and the function cannot drift apart.
PETERSBURG = "CHILD:petersburg"
#: Keys the branch's old `owner_key` built for «Сетевой город»: 74 characters,
#: one per parent's login.
LEGACY_NETSCHOOL = "netschool:" + "0123456789abcdef" * 4
LEGACY_NETSCHOOL_MOTHER = "netschool:" + "fedcba9876543210" * 4
LEGACY_NETSCHOOL_FATHER = "netschool:" + "00112233445566778899aabbccddeeff" * 2
LOCK = "LOCK TABLE diary_overrides IN SHARE ROW EXCLUSIVE MODE"
LESSON = "lesson:2026-09-15:n1:Алгебра"
HOMEWORK = "hw:id:77"


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0017", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _render(function, dialect: str = "postgresql") -> list[str]:
    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name=dialect, opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        function()
    return [" ".join(part.split()) for part in rendered.getvalue().split(";") if part.strip()]


def _runs_on_sqlite(statements: list[str]) -> list[str]:
    """The PostgreSQL statements minus the lock, the one SQLite has no word
    for and the revision skips there itself."""
    return [statement for statement in statements if not statement.startswith(LOCK)]


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def _at_0016(database: Path) -> None:
    """A database one revision behind, built the way the chain builds one —
    which runs ``0017`` over an empty table first, the state production was
    read in on 26 September, and has to go through it untouched."""
    database.touch()
    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr
    with sqlite3.connect(database) as db:
        db.execute("UPDATE alembic_version SET version_num = '0016'")


def _insert(db: sqlite3.Connection, rows: list[tuple]) -> None:
    db.executemany(
        "INSERT INTO diary_overrides (id, login, student_id, target, field, value, "
        "original, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, NULL, ?, ?)",
        [(row_id, login, student, target, field, value, when, when)
         for row_id, login, student, target, field, value, when in rows],
    )


def _rows(database: Path) -> set[tuple]:
    with sqlite3.connect(database) as db:
        return set(
            db.execute("SELECT login, student_id, target, field, value FROM diary_overrides")
        )


#: Every shape a database can hold at 0016, with the row it must become.
BEFORE = [
    # Two parents' logins on one field of one child: the newer one wins.
    (1, "parent@example.com", 4021, LESSON, "room", "204", "2026-09-10 10:00:00"),
    (2, "other@example.com", 4021, LESSON, "room", "301", "2026-09-12 10:00:00"),
    # Three on another field, the newest written first: it survives although
    # its id is the lowest, so `updated_at` ranks before `id`. (Whether the
    # DELETE sees its own deletions cannot change the survivor: the newest row
    # of a group never has a newer keeper and is every other row's.)
    (3, "c@example.com", 4021, LESSON, "teacher", "Третья", "2026-09-14 10:00:00"),
    (4, "a@example.com", 4021, LESSON, "teacher", "Первая", "2026-09-11 10:00:00"),
    (5, "b@example.com", 4021, LESSON, "teacher", "Вторая", "2026-09-13 10:00:00"),
    # As new as each other: the higher id wins, as 0013 kept max(id).
    (6, "a@example.com", 4021, HOMEWORK, "text", "раньше", "2026-09-12 10:00:00"),
    (7, "b@example.com", 4021, HOMEWORK, "text", "позже", "2026-09-12 10:00:00"),
    # A legacy row beside a per-child one, either way round.
    (8, "parent@example.com", 4021, LESSON, "topic", "legacy newer", "2026-09-20 10:00:00"),
    (9, PETERSBURG, 4021, LESSON, "topic", "per-child older", "2026-09-15 10:00:00"),
    (10, "parent@example.com", 4021, LESSON, "homework", "legacy older", "2026-09-10 10:00:00"),
    (11, PETERSBURG, 4021, LESSON, "homework", "per-child newer", "2026-09-16 10:00:00"),
    # A login somebody typed as «CHILD:petersburg», casefolded: a legacy key,
    # re-filed like any other, and nothing collides with it here.
    (12, "child:petersburg", 4022, LESSON, "room", "7", "2026-09-10 10:00:00"),
    # A legacy «Сетевой город» key: gone, whatever its date — and the older
    # Petersburg row on the same (student_id, target, field) is not a loser
    # of anything, which a pre-flight count that ranked the two called it.
    (13, LEGACY_NETSCHOOL, 11, LESSON, "room", "hashed", "2026-09-10 10:00:00"),
    (16, "person11@example.com", 11, LESSON, "room", "person 11", "2026-09-09 10:00:00"),
    # Two parents' legacy «Сетевой город» keys for one pupil: both gone, and
    # neither is a collision step 2 resolves.
    (17, LEGACY_NETSCHOOL_MOTHER, 12, HOMEWORK, "text", "мама", "2026-09-10 10:00:00"),
    (18, LEGACY_NETSCHOOL_FATHER, 12, HOMEWORK, "text", "папа", "2026-09-11 10:00:00"),
    # Already per-child, in other scopes: untouched, even on a field that
    # collides by (student_id, target, field) with the Petersburg rows above.
    (14, "CHILD:netschool:region.zabedu.ru", 11, LESSON, "room", "ns", "2026-09-10 10:00:00"),
    (15, "CHILD:netschool:region.zabedu.ru", 4021, LESSON, "room", "ns 4021",
     "2026-09-01 10:00:00"),
]

AFTER = {
    (PETERSBURG, 4021, LESSON, "room", "301"),
    (PETERSBURG, 4021, LESSON, "teacher", "Третья"),
    (PETERSBURG, 4021, HOMEWORK, "text", "позже"),
    (PETERSBURG, 4021, LESSON, "topic", "legacy newer"),
    (PETERSBURG, 4021, LESSON, "homework", "per-child newer"),
    (PETERSBURG, 4022, LESSON, "room", "7"),
    (PETERSBURG, 11, LESSON, "room", "person 11"),
    ("CHILD:netschool:region.zabedu.ru", 11, LESSON, "room", "ns"),
    ("CHILD:netschool:region.zabedu.ru", 4021, LESSON, "room", "ns 4021"),
}


def test_the_revision_is_data_only_and_names_the_scope_the_code_uses():
    """No DDL: the column keeps its name and every constraint stays as it is.
    And the literal it writes is the one `child_scope` answers."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0017", "0016")
    assert service.child_scope(None, None) == service.child_scope("petersburg", None) == PETERSBURG

    statements = _render(revision.upgrade)
    assert [statement.split()[0] for statement in statements] == [
        "LOCK", "DELETE", "DELETE", "UPDATE"
    ]
    for statement in statements:
        words = set(statement.upper().replace("(", " ").split())
        assert not words & {"CREATE", "ALTER", "DROP", "TRUNCATE", "INDEX", "CONSTRAINT"}
        assert statement.startswith(
            (LOCK, "DELETE FROM diary_overrides", "UPDATE diary_overrides")
        )
    assert statements[3] == (
        f"UPDATE diary_overrides SET login = '{PETERSBURG}' "
        "WHERE substr(login, 1, 6) <> 'CHILD:'"
    )
    # Nothing to put back: a scope does not remember the login it replaced.
    assert _render(revision.downgrade) == []


def test_postgres_is_locked_against_writes_first_and_sqlite_is_not_asked_to():
    """Applied after the merge, the new code writes while this runs. Under READ
    COMMITTED a per-child row committed between the collision DELETE and the
    UPDATE made the UPDATE raise UniqueViolation (reproduced on PostgreSQL 16)
    and the transaction roll back whole; the lock makes that write wait for
    the commit instead. It has to come first — a lock taken after the DELETE
    guards nothing — and SQLite, which has no LOCK TABLE, must not be sent
    one."""
    revision = _revision()
    assert _render(revision.upgrade)[0] == LOCK
    assert [statement.split()[0] for statement in _render(revision.upgrade, "sqlite")] == [
        "DELETE", "DELETE", "UPDATE"
    ]


def test_the_statements_rendered_for_postgres_run_on_sqlite_and_do_the_same(tmp_path):
    """The PostgreSQL text itself, executed — not a SQLite twin of it. Nothing
    here can run Postgres, so this is what "valid SQL" can be asked as: the
    statements parse and run on a second engine, over the model's own table,
    and leave exactly what the real run below leaves."""
    database = tmp_path / "rendered.db"
    _at_0016(database)
    with sqlite3.connect(database) as db:
        _insert(db, BEFORE)
        for statement in _runs_on_sqlite(_render(_revision().upgrade)):
            db.execute(statement)

    assert _rows(database) == AFTER


def test_a_real_upgrade_leaves_exactly_the_survivors(tmp_path):
    database = tmp_path / "at16.db"
    _at_0016(database)
    with sqlite3.connect(database) as db:
        _insert(db, BEFORE)

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr

    assert _rows(database) == AFTER
    with sqlite3.connect(database) as db:
        assert db.execute("SELECT version_num FROM alembic_version").fetchall() == [
            (EXPECTED_REVISION,)
        ]
        # The kept row is the kept row, not a copy: id 2 won the room.
        assert db.execute(
            "SELECT id FROM diary_overrides WHERE field = 'room' AND student_id = 4021 "
            "AND login = ?",
            (PETERSBURG,),
        ).fetchall() == [(2,)]


def test_a_second_run_changes_nothing_and_the_way_down_leaves_every_row(tmp_path):
    """Idempotent, because after the first run there is no legacy row and no
    duplicate — `uq_diary_override` rules the second out. And the downgrade
    is a no-op: the rows wait for the code to come back."""
    database = tmp_path / "twice.db"
    _at_0016(database)
    with sqlite3.connect(database) as db:
        _insert(db, BEFORE)
    assert _alembic(database, "upgrade", "head").returncode == 0

    with sqlite3.connect(database) as db:
        for statement in _runs_on_sqlite(_render(_revision().upgrade)):
            db.execute(statement)
    assert _rows(database) == AFTER

    down = _alembic(database, "downgrade", "0016")
    assert down.returncode == 0, down.stderr
    assert _rows(database) == AFTER

    up = _alembic(database, "upgrade", "head")
    assert up.returncode == 0, up.stderr
    assert _rows(database) == AFTER


def _preflight(docstring: str) -> list[str]:
    """The queries the docstring gives the owner to run before the transaction,
    in the order it gives them."""
    queries = re.findall(r"^\s*(SELECT\b.*?;)", docstring, re.S | re.M)
    return [" ".join(query.split()) for query in queries]


def test_the_counts_the_docstring_gives_the_owner_are_what_each_step_deletes(tmp_path):
    """What the revision destroys is a sentence the owner reads *before* the
    transaction, and here it is a query: so it is run, over every shape the
    table can hold, and held to what each step then deletes. The query this
    replaced ranked a legacy «Сетевой город» row against a Petersburg one it
    never meets, and counted two parents' hashes for one pupil as a collision
    step 2 never resolves — while step 1's deletions had no count at all."""
    netschool, collisions = _preflight(_revision().__doc__)
    database = tmp_path / "preflight.db"
    _at_0016(database)
    with sqlite3.connect(database) as db:
        _insert(db, BEFORE)
        told = [
            db.execute(netschool).fetchone()[0],
            sum(row[-1] for row in db.execute(collisions)),
        ]
        deleted = []
        for statement in _runs_on_sqlite(_render(_revision().upgrade))[:2]:
            before = db.total_changes
            db.execute(statement)
            deleted.append(db.total_changes - before)

    assert told == deleted == [3, 6]


def _service_run(database: Path, work) -> object:
    """Run the app's own correction code against the file, the way the new
    code would in the window around this revision."""
    import asyncio

    from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine

    async def run():
        engine = create_async_engine(f"sqlite+aiosqlite:///{database}")
        try:
            async with AsyncSession(engine, expire_on_commit=False) as session:
                return await work(session)
        finally:
            await engine.dispose()

    return asyncio.run(run())


def test_a_reset_in_the_window_after_the_merge_comes_back_when_this_runs(tmp_path):
    """Why the docstring, `docs/deploy.md` and CLAUDE.md say to keep the
    after-the-merge window to minutes rather than calling it lossless. The new
    code resets only per-child rows, so the legacy row it cannot see survives
    «Сбросить всё», and this revision then files it under the child: the value
    a parent just took off comes back, for everyone who sees the child."""
    database = tmp_path / "window.db"
    _at_0016(database)
    with sqlite3.connect(database) as db:
        _insert(db, [(1, "parent@example.com", 4021, LESSON, "room", "204",
                      "2026-09-10 10:00:00")])

    async def in_the_window(session):
        assert await service.load_corrections(session, PETERSBURG, 4021) == {}
        await service.put_override(session, PETERSBURG, 4021, LESSON, "room", "301", "12")
        return await service.drop_overrides(session, PETERSBURG, 4021)

    assert _service_run(database, in_the_window) == 1
    assert _alembic(database, "upgrade", "head").returncode == 0

    async def after(session):
        return await service.load_corrections(session, PETERSBURG, 4021)

    assert _service_run(database, after) == {LESSON: {"room": ("204", None)}}


def test_what_older_code_wrote_meanwhile_is_refiled_by_the_remedy_the_docs_name(tmp_path):
    """A revert is not lossless. Code older than this revision, running against
    a database already at it, files what it is given under a login — and
    ``upgrade head`` does nothing then, because the stamp says 0017. The
    remedy is written where whoever reverted will look, as a command, and the
    command works: the downgrade does nothing, so the upgrade runs this again."""
    remedy = "alembic downgrade 0016 && alembic upgrade head"
    deploy = (SERVER_ROOT.parent / "docs" / "deploy.md").read_text("utf-8")
    assert remedy in " ".join(_revision().__doc__.split())
    assert remedy in " ".join(deploy.split())

    database = tmp_path / "reverted.db"
    database.touch()
    assert _alembic(database, "upgrade", "head").returncode == 0
    with sqlite3.connect(database) as db:
        _insert(db, [
            (1, PETERSBURG, 4021, LESSON, "room", "204", "2026-09-10 10:00:00"),
            # What reverted code wrote for the same field, later.
            (2, "parent@example.com", 4021, LESSON, "room", "305", "2026-09-20 10:00:00"),
        ])

    assert _alembic(database, "upgrade", "head").returncode == 0
    assert (PETERSBURG, 4021, LESSON, "room", "204") in _rows(database)

    for step in (("downgrade", "0016"), ("upgrade", "head")):
        ran = _alembic(database, *step)
        assert ran.returncode == 0, ran.stderr
    assert _rows(database) == {(PETERSBURG, 4021, LESSON, "room", "305")}
