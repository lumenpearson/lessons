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

``downgrade`` does exactly that before dropping the columns.

The DDL is taken from the model, as ``CLAUDE.md`` requires:
``CreateTable(...).compile(dialect=postgresql.dialect())`` prints these column
types. Applied through the Neon connector, ``alembic_version`` stamped in the
same transaction; on local SQLite the columns arrive through ``create_all``.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0015"
down_revision: str | None = "0014"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # Local SQLite only ever exists here through `create_all`, which builds
    # every column the model declares; there is nothing to add. Postgres's
    # alone, exactly as the additive revisions before this one.
    if op.get_bind().dialect.name == "sqlite":
        return
    op.add_column("diary_sessions", sa.Column("provider", sa.String(length=32), nullable=True))
    op.add_column("diary_sessions", sa.Column("region", sa.String(length=32), nullable=True))
    op.add_column("diary_sessions", sa.Column("kept_alive_at", sa.DateTime(), nullable=True))
    op.add_column(
        "diary_sessions", sa.Column("keepalive_attempted_at", sa.DateTime(), nullable=True)
    )
    op.add_column("diary_sessions", sa.Column("upstream_ok_at", sa.DateTime(), nullable=True))
    op.add_column("classes", sa.Column("diary_region", sa.String(length=32), nullable=True))
    op.add_column("classes", sa.Column("diary_school_id", sa.BigInteger(), nullable=True))
    op.add_column("classes", sa.Column("diary_school_name", sa.String(length=300), nullable=True))


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        return
    # Expire any non-Petersburg session before the columns that tell it apart
    # are gone, so the reverted code cannot replay its credential at the wrong
    # upstream. The rows stay; expired ones are swept within a day.
    op.execute(
        "UPDATE diary_sessions SET expired_at = now() "
        "WHERE provider IS NOT NULL AND provider <> 'petersburg' AND expired_at IS NULL"
    )
    for column in (
        "upstream_ok_at",
        "keepalive_attempted_at",
        "kept_alive_at",
        "region",
        "provider",
    ):
        op.drop_column("diary_sessions", column)
    for column in ("diary_school_name", "diary_school_id", "diary_region"):
        op.drop_column("classes", column)
