"""The two one-shot scripts, and the question they both have to answer first.

`scripts/init_db` runs `create_all` and stamps `alembic_version`;
`scripts/seed_demo` runs that and then installs a class carrying the published
join code `DEMO24` in open mode. Both read `DATABASE_URL` and neither asks
twice, which is fine against a file and is not fine against the database the
bot is serving from — and `CLAUDE.md` puts `python -m scripts.seed_demo` two
lines above `DATABASE_URL='postgresql+asyncpg://…' alembic upgrade head`, so
the shell that ran one is the shell that runs the other.

`seed_demo` grew a refusal after it wrote to whatever `DATABASE_URL` pointed
at and said nothing about it. Nothing tested the refusal: deleting the call, or
adding "postgresql" to the list of schemes it considers local, left the whole
suite green — a guard that exists because of an incident, held by nothing.
`init_db`, which is the one that actually runs the DDL, had no refusal at all
and printed the word «local» for any URL carrying no inline password.
"""

from __future__ import annotations

import subprocess
import sys
from pathlib import Path

import pytest

from scripts import init_db, seed_demo, target

SERVER_ROOT = Path(__file__).resolve().parent.parent

#: A Neon URL of the shape this project actually uses, password and all.
PRODUCTION = (
    "postgresql+asyncpg://lessons_owner:s3cr3t-p%40ss"
    "@ep-quiet-river-123456.eu-central-1.aws.neon.tech/lessons?sslmode=require"
)

#: The same host, reached without an inline password — a pgpass file, a
#: certificate, an IAM token. This is the one that used to be announced as
#: «local», because the check for "is there a password to hide" was doing
#: duty as the check for "is this my laptop".
PRODUCTION_WITHOUT_A_PASSWORD = (
    "postgresql+asyncpg://ep-quiet-river-123456.eu-central-1.aws.neon.tech/lessons"
)

LOCAL = "sqlite+aiosqlite:///./lessons.db"


# --------------------------------------------------------------------------
# Which database is this
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("url", "local"),
    [
        (LOCAL, True),
        ("sqlite:///:memory:", True),
        (PRODUCTION, False),
        (PRODUCTION_WITHOUT_A_PASSWORD, False),
        ("postgresql://u:p@localhost/lessons", False),
        ("postgres://u:p@localhost/lessons", False),
    ],
)
def test_only_a_file_counts_as_a_database_nobody_minds_losing(url, local):
    """Postgres on «localhost» is still not a file.

    It is a running server somebody is using for something, and on a laptop
    that forwards a port it is not even on the laptop. The dialect is the
    question, not the hostname.
    """
    assert target.is_local(url) is local


def test_where_it_is_about_to_write_never_says_local_about_a_server():
    """The failure this replaces: a URL with no «@» in it was called «local».

    The safety line and the password-hiding line were the same line, so a DSN
    that authenticated some other way was announced as the developer's own
    machine by the one sentence whose whole job is to say otherwise.
    """
    rendered = target.where(PRODUCTION_WITHOUT_A_PASSWORD)

    assert rendered != "local"
    assert "ep-quiet-river-123456" in rendered
    assert "/lessons" in rendered


def test_where_it_is_about_to_write_never_carries_the_password():
    rendered = target.where(PRODUCTION)

    assert "s3cr3t" not in rendered
    assert "p%40ss" not in rendered
    assert "lessons_owner" not in rendered
    assert rendered.endswith("neon.tech/lessons")


# --------------------------------------------------------------------------
# The refusal
# --------------------------------------------------------------------------


def test_a_local_file_is_written_to_without_being_asked_twice():
    """The refusal must be invisible on the run everybody actually does."""
    target.refuse_unless_local(LOCAL, variable="ANYTHING", because="…")


def test_an_unset_database_url_is_refused_rather_than_assumed_to_be_a_file():
    """Empty is not local. `get_settings` defaults it to SQLite, but a shell
    that exported it to nothing has said nothing, and the refusal is the only
    place that difference can still be noticed."""
    assert target.is_local("") is False
    assert target.where("") == "(no DATABASE_URL)"

    with pytest.raises(SystemExit):
        target.refuse_unless_local("", variable="ANYTHING", because="…")


def test_a_real_database_is_refused_and_told_why(monkeypatch):
    monkeypatch.delenv("SEED_DEMO_I_MEAN_IT", raising=False)

    with pytest.raises(SystemExit) as refused:
        target.refuse_unless_local(
            PRODUCTION,
            variable="SEED_DEMO_I_MEAN_IT",
            because="это разнесёт настоящий класс",
        )

    said = str(refused.value)
    # Where, why, and how to override it — in the refusal, not in the docs.
    assert "ep-quiet-river-123456" in said
    assert "это разнесёт настоящий класс" in said
    assert "SEED_DEMO_I_MEAN_IT=yes" in said
    # And never the password, in the one message somebody will paste into chat.
    assert "s3cr3t" not in said


def test_the_override_is_the_exact_word_and_says_so_out_loud(monkeypatch, capsys):
    monkeypatch.setenv("SEED_DEMO_I_MEAN_IT", "yes")
    target.refuse_unless_local(PRODUCTION, variable="SEED_DEMO_I_MEAN_IT", because="…")

    assert "NON-LOCAL" in capsys.readouterr().out


@pytest.mark.parametrize("value", ["", "no", "true", "1", "YES", " yes "])
def test_anything_other_than_yes_is_still_a_refusal(monkeypatch, value):
    """«true» and «1» are what a person types from habit, and neither is a
    sentence they would have had to mean."""
    monkeypatch.setenv("SEED_DEMO_I_MEAN_IT", value)

    with pytest.raises(SystemExit):
        target.refuse_unless_local(PRODUCTION, variable="SEED_DEMO_I_MEAN_IT", because="…")


def test_each_script_names_its_own_override_and_its_own_consequence():
    """Two scripts, two different things to be warned about.

    A shared sentence would be a shared lie: one hands out read tokens for a
    real class and the other runs DDL and stamps a revision.
    """
    assert seed_demo.OVERRIDE != init_db.OVERRIDE
    assert "DEMO24" in seed_demo.WHAT_THIS_WOULD_DO
    assert "alembic" in init_db.WHAT_THIS_WOULD_DO


# --------------------------------------------------------------------------
# End to end, which is the only place the wiring shows
# --------------------------------------------------------------------------


@pytest.mark.parametrize("script", ["scripts.seed_demo", "scripts.init_db"])
def test_neither_script_touches_a_real_database_when_run_for_real(script, monkeypatch):
    """Run them, at a URL nothing could be listening on.

    Every test above holds the helper. This holds the *call*: deleting the one
    line in `seed()` or `main()` was invisible until now, and the connection
    would be attempted before anybody read the output. A refusal means the
    process exited before SQLAlchemy was asked for an engine, which is why the
    assertion is about the message and not only about the exit code — a driver
    that cannot resolve the host also exits non-zero.
    """
    import os

    environment = {
        key: value
        for key, value in os.environ.items()
        if not key.endswith("_I_MEAN_IT")
    }
    environment["DATABASE_URL"] = PRODUCTION

    result = subprocess.run(
        [sys.executable, "-m", script],
        cwd=str(SERVER_ROOT),
        env=environment,
        capture_output=True,
        text=True,
        timeout=120,
    )

    assert result.returncode != 0, result.stdout
    assert "Refusing to write to" in result.stderr, result.stderr + result.stdout
    assert "ep-quiet-river-123456" in result.stderr
    assert "s3cr3t" not in result.stderr + result.stdout
    # It stopped on its own word rather than on the driver's: a script that
    # reached the database and failed there leaves a traceback, and one that
    # reached it and succeeded would have printed one of these two lines.
    assert "Traceback" not in result.stderr, result.stderr
    assert "Creating schema" not in result.stdout
    assert "DEMO24" not in result.stdout
