"""The daily allowance, and the revision that gives it a table.

``services/quota.py`` counts somebody else's allowance per day in
``usage_counters``; ``0016`` is what makes that table on a database that
predates it. What is pinned here:

- a day is Moscow's, and ``Retry-After`` runs to Moscow midnight, never 0;
- a spend is all or nothing and changes nothing when it is refused;
- the revision builds on Postgres exactly the DDL the model declares — the
  rule ``CLAUDE.md`` sets for every revision, held by a test rather than by
  whoever applies it — and on a database that lacks the table it creates it,
  and on the way down drops it.

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
from datetime import UTC, date, datetime, timedelta, timezone
from pathlib import Path

import pytest
from alembic.migration import MigrationContext
from alembic.operations import Operations
from sqlalchemy.dialects import postgresql
from sqlalchemy.schema import CreateTable

from app.models import UsageCounter
from app.services import quota

SERVER_ROOT = Path(__file__).resolve().parent.parent
REVISION = SERVER_ROOT / "migrations" / "versions" / "0016_usage_counters.py"


# ---- the day --------------------------------------------------------------


def test_the_day_is_moscows():
    """21:30 UTC on the 25th is half past midnight on the 26th in Moscow, and
    the 26th's share is the one being spent."""
    assert quota.quota_day(datetime(2026, 9, 25, 20, 59, tzinfo=UTC)) == date(2026, 9, 25)
    assert quota.quota_day(datetime(2026, 9, 25, 21, 30, tzinfo=UTC)) == date(2026, 9, 26)


def test_a_naive_now_is_read_as_utc_like_every_other_clock_here():
    assert quota.quota_day(datetime(2026, 9, 25, 21, 30)) == date(2026, 9, 26)


def test_the_reset_is_at_moscow_midnight_whatever_zone_now_is_in():
    ten_to = datetime(2026, 9, 25, 20, 50, tzinfo=UTC)
    assert quota.seconds_to_reset(ten_to) == 600
    # The same instant written in another zone is the same ten minutes.
    assert quota.seconds_to_reset(ten_to.astimezone(timezone(timedelta(hours=-7)))) == 600


def test_the_reset_is_never_zero_seconds_away():
    """«Retry after 0» is «retry now», which is refused again at once."""
    midnight = datetime(2026, 9, 25, 21, 0, tzinfo=UTC)
    assert quota.seconds_to_reset(midnight) == 24 * 3600
    assert quota.seconds_to_reset(midnight - timedelta(microseconds=1)) == 1


# ---- the spend ------------------------------------------------------------


async def test_scopes_and_days_are_counted_apart(session):
    today, tomorrow = date(2026, 9, 25), date(2026, 9, 26)

    assert await quota.spend(session, "a", 2, cap=2, day=today)
    assert not await quota.spend(session, "a", 1, cap=2, day=today)
    assert await quota.spend(session, "b", 1, cap=2, day=today)
    assert await quota.spend(session, "a", 1, cap=2, day=tomorrow)

    assert await quota.used(session, "a", today) == 2
    assert await quota.used(session, "b", today) == 1
    assert await quota.used(session, "a", tomorrow) == 1
    assert await quota.used(session, "c", today) == 0


async def test_a_first_spend_larger_than_the_cap_writes_nothing(session):
    """The insert that opens a day is not guarded by the update's WHERE, so
    the refusal has to happen before it — or the day would open over the cap."""
    assert not await quota.spend(session, "a", 3, cap=2, day=date(2026, 9, 25))
    assert await quota.used(session, "a", date(2026, 9, 25)) == 0
    assert await session.get(UsageCounter, ("a", date(2026, 9, 25))) is None


async def test_a_spend_of_nothing_is_a_mistake_not_a_free_pass(session):
    with pytest.raises(ValueError):
        await quota.spend(session, "a", 0, cap=2, day=date(2026, 9, 25))


# ---- the revision ---------------------------------------------------------


def _revision():
    spec = importlib.util.spec_from_file_location("revision_0016", REVISION)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _words(sql: str) -> str:
    return " ".join(sql.replace(";", " ").split())


def test_the_revision_builds_on_postgres_exactly_what_the_model_declares(monkeypatch):
    """``CLAUDE.md``: take the DDL from the model. The revision's own
    ``create_table`` is rendered for Postgres, offline, and has to be the
    model's ``CREATE TABLE`` word for word — so a column changed in the model
    and not here, or here and not in the model, fails before anybody applies
    it through the Neon connector."""
    revision = _revision()
    assert (revision.revision, revision.down_revision) == ("0016", "0015")
    # Offline there is no database to ask whether the table exists; the
    # module's own guard answers «missing» in that mode, as here.
    monkeypatch.setattr(revision, "_missing", lambda table: True)

    rendered = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": rendered}
    )
    with Operations.context(context):
        revision.upgrade()

    model = CreateTable(UsageCounter.__table__).compile(dialect=postgresql.dialect())
    assert _words(rendered.getvalue()) == _words(str(model))

    dropped = io.StringIO()
    context = MigrationContext.configure(
        dialect_name="postgresql", opts={"as_sql": True, "output_buffer": dropped}
    )
    with Operations.context(context):
        revision.downgrade()
    assert _words(dropped.getvalue()) == "DROP TABLE usage_counters"


def _alembic(database: Path, *args: str) -> subprocess.CompletedProcess:
    env = dict(os.environ, DATABASE_URL=f"sqlite+aiosqlite:///{database}")
    return subprocess.run(
        [sys.executable, "-m", "alembic", *args],
        env=env,
        cwd=str(SERVER_ROOT),
        capture_output=True,
        text=True,
    )


def test_the_revision_creates_the_table_where_it_is_missing_and_drops_it_going_down(tmp_path):
    """Run for real, on a database that is at ``0015`` and has no such table.

    The chain from nothing never gets here — ``0001`` builds today's whole
    schema, the table with it, and this revision finds it and does nothing,
    which is the other half and is checked first. So the table is taken away
    and the stamp put back, which is exactly a database that predates it.
    """
    database = tmp_path / "quota.db"
    database.touch()

    built = _alembic(database, "upgrade", "head")
    assert built.returncode == 0, built.stderr

    with sqlite3.connect(database) as db:
        db.execute("DROP TABLE usage_counters")
        db.execute("UPDATE alembic_version SET version_num = '0015'")

    upgraded = _alembic(database, "upgrade", "head")
    assert upgraded.returncode == 0, upgraded.stderr
    with sqlite3.connect(database) as db:
        columns = {row[1]: row for row in db.execute("PRAGMA table_info(usage_counters)")}
        assert set(columns) == {"scope", "day", "used"}
        # (cid, name, type, notnull, default, pk)
        assert [columns[name][5] for name in ("scope", "day")] == [1, 2]
        assert columns["used"][3] == 1
        assert db.execute("select version_num from alembic_version").fetchall() == [("0016",)]

    downgraded = _alembic(database, "downgrade", "0015")
    assert downgraded.returncode == 0, downgraded.stderr
    with sqlite3.connect(database) as db:
        tables = {row[0] for row in db.execute("select name from sqlite_master")}
        assert "usage_counters" not in tables
        assert "join_attempts" in tables
