"""Add the columns a second diary provider needs.

Revision ID: 0015
Revises: 0014
Create Date: 2026-09-24

Until now there was one diary, Petersburg's, and a class carried only whether
it was bound (``classes.diary_provider``). A second provider, «Сетевой город»,
serves many regional servers and keeps sessions alive from the cron tick, so a
session and a class each need a little more said about them:

On ``diary_sessions``:

- ``provider`` — which diary this session is with. ``NULL`` on every row
  written before this revision, which means Petersburg. A string, not an enum,
  so a third provider is a value and not a migration.
- ``region`` — for a many-server provider, which regional server, so a session
  can be matched to its class's binding and grouped per origin for the
  keep-alive without unsealing its credential.
- ``kept_alive_at`` / ``keepalive_attempted_at`` / ``upstream_ok_at`` — the
  keep-alive's clocks, kept apart from ``last_used_at`` so that pinging a
  session never looks like the family using it (which would defeat the 30-day
  purge of one nobody opens).

On ``classes``:

- ``diary_region`` — the region key an admin bound, into the provider's own
  allow-list, never a URL.
- ``diary_school_id`` — the upstream's own school id (``scid``); no foreign key
  because it is a foreign system's id, so ``BigInteger``.
- ``diary_school_name`` — what the class card and the sign-in form show.

**Destroys nothing.** Eight nullable columns with no default and no constraint.
The running code neither writes nor reads them, so every row inserted in the
window before the deploy lands has a ``NULL`` provider, which is Petersburg —
exactly what that code already assumes.

**Apply this BEFORE the merge**, the ordinary additive order: it is the *new*
code that needs the columns, and the old code is happy without them.

**Before rolling the code back past the merge** that carries this, expire any
non-Petersburg session first, or old code will read a «Сетевой город»
credential as Petersburg's and replay it against ``dnevnik2`` — one third
party's session handed to another:

    UPDATE diary_sessions SET expired_at = now()
    WHERE provider IS NOT NULL AND provider <> 'petersburg' AND expired_at IS NULL;
    UPDATE classes SET diary_provider = NULL
    WHERE diary_provider IS NOT NULL AND diary_provider <> 'petersburg';

The second statement unbinds every class bound to «Сетевой город». Without it
the old code, which knows one diary, reads ``'netschool'`` two ways at once:
the admin's card and the main menu test the column for truth and say
«Санкт-Петербург», while the diary button compares it with ``'petersburg'``
and answers that the class is not bound. With the region and school gone the
binding means nothing to that code; the admin binds again after the roll
forward. ``downgrade`` does both before dropping the columns.

The DDL is taken from the model, as ``CLAUDE.md`` requires:
``CreateTable(...).compile(dialect=postgresql.dialect())`` prints these column
types, and ``tests/test_diary_provider_revision.py`` holds the revision to them.
Applied through the Neon connector, ``alembic_version`` stamped in the same
transaction. On a database at ``0014`` that is exactly the eight
``ADD COLUMN`` statements below, because each guard finds its column missing.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0015"
down_revision: str | None = "0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _has_column(table: str, column: str) -> bool:
    # The guard the additive revisions before this one carry (0005, 0006,
    # 0008, 0010), asked per column rather than per dialect. An empty
    # database already has every column here, because 0001's `create_all`
    # builds today's schema before 0015 runs, and adding one twice is a hard
    # error on Postgres. A SQLite file built by `scripts.init_db` before the
    # model changed, stamped 0014, has none of them — and skipping SQLite
    # wholesale stamped it 0015 anyway, `/warmup` said «ok», and the first
    # read of a class raised «no such column: classes.diary_region».
    # Offline (`--sql`) there is nobody to ask, and the database the script is
    # written for is one at 0014, which has none of them. Asked of the
    # migration context rather than `alembic.context`, so the test that renders
    # this revision in-process runs this very guard instead of patching it.
    if op.get_context().as_sql:
        return False
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(table):
        return False
    return any(existing["name"] == column for existing in inspector.get_columns(table))


def _columns() -> tuple[tuple[str, sa.Column], ...]:
    # Built afresh per call: `add_column` attaches the Column to a table, and
    # one Column cannot belong to two.
    return (
        ("diary_sessions", sa.Column("provider", sa.String(length=32), nullable=True)),
        ("diary_sessions", sa.Column("region", sa.String(length=32), nullable=True)),
        ("diary_sessions", sa.Column("kept_alive_at", sa.DateTime(), nullable=True)),
        ("diary_sessions", sa.Column("keepalive_attempted_at", sa.DateTime(), nullable=True)),
        ("diary_sessions", sa.Column("upstream_ok_at", sa.DateTime(), nullable=True)),
        ("classes", sa.Column("diary_region", sa.String(length=32), nullable=True)),
        ("classes", sa.Column("diary_school_id", sa.BigInteger(), nullable=True)),
        ("classes", sa.Column("diary_school_name", sa.String(length=300), nullable=True)),
    )


def upgrade() -> None:
    # Nullable with no default, so SQLite's own ALTER TABLE ADD COLUMN takes
    # each of them as it is; no table copy is needed on either dialect.
    for table, column in _columns():
        if not _has_column(table, column.name):
            op.add_column(table, column)


def downgrade() -> None:
    # Expire any non-Petersburg session before the columns that tell it apart
    # are gone, so the reverted code cannot replay its credential at the wrong
    # upstream. The rows stay; expired ones are swept within a day.
    # CURRENT_TIMESTAMP rather than `now()`, which SQLite does not have; on
    # Postgres the two are the same function. Literal SQL rather than a Core
    # UPDATE, whose bound parameters `--sql` would print as placeholders.
    op.execute(
        "UPDATE diary_sessions SET expired_at = CURRENT_TIMESTAMP "
        "WHERE provider IS NOT NULL AND provider <> 'petersburg' AND expired_at IS NULL"
    )
    # And unbind every class the old code cannot read: see the docstring.
    op.execute(
        "UPDATE classes SET diary_provider = NULL "
        "WHERE diary_provider IS NOT NULL AND diary_provider <> 'petersburg'"
    )
    for table, column in reversed(_columns()):
        op.drop_column(table, column.name)
