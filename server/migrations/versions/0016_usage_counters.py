"""Count the anonymous school directory's share of DaData's daily allowance.

Revision ID: 0016
Revises: 0015
Create Date: 2026-09-25

The phone asks ``GET /api/v1/directory/school-regions`` before it belongs to
any class, so nothing about the caller is known and a per-address throttle is
the only other limit on it. DaData's key allows ten thousand requests a day,
and the bot and ``/manage/schools`` need that same allowance to create a
class. ``usage_counters`` holds one row per allowance per day, spent atomically
by ``services/quota.spend``, so the anonymous door stops at its own share and
never reaches the admins'.

A table of its own rather than rows in ``join_attempts``: that table is pruned
to the shortest throttle window on every recorded attempt and by the cron after
an hour, which would cut a day's count to fifteen minutes.

**Destroys nothing.** One new table; no existing row is read or rewritten.

**Apply this BEFORE the merge**, the ordinary additive order: the new code
spends from the table, and the old code never mentions it.

The DDL is the model's own, as ``CLAUDE.md`` requires —
``CreateTable(UsageCounter.__table__).compile(dialect=postgresql.dialect())``
prints exactly::

    CREATE TABLE usage_counters (
        scope VARCHAR(32) NOT NULL,
        day DATE NOT NULL,
        used INTEGER NOT NULL,
        PRIMARY KEY (scope, day)
    )

``tests/test_quota.py`` holds the revision to that text. Applied through the
Neon connector with ``alembic_version`` stamped in the same transaction; on
local SQLite the table arrives through ``create_all``, which is why the guard
below asks first.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0016"
down_revision: str | None = "0015"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _missing(table: str) -> bool:
    # A database built by `create_all` after the model landed — `0001` on an
    # empty file, or `scripts.init_db` — already has the table, and creating it
    # again is a hard error rather than a no-op.
    if context.is_offline_mode():
        return True
    return not sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not _missing("usage_counters"):
        return
    op.create_table(
        "usage_counters",
        sa.Column("scope", sa.String(length=32), nullable=False),
        sa.Column("day", sa.Date(), nullable=False),
        sa.Column("used", sa.Integer(), nullable=False),
        sa.PrimaryKeyConstraint("scope", "day"),
    )


def downgrade() -> None:
    op.drop_table("usage_counters")
