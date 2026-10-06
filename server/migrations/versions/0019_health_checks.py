"""Keep what each self-check of the cron tick said last, and when the owner was told.

Revision ID: 0019
Revises: 0018
Create Date: 2026-10-06

Every tick ends with four checks (``services/health.py``): the schema, v2,
the diary's proxy and the deploy. The owner is written to when one starts
failing, when it comes back, and every six hours while it stays failing -
never once per tick - so what each check said last has to outlive the
function instance that asked it. ``health_checks`` holds one row per check,
and the bot's «📊 Проект» reads it.

**Destroys nothing.** One new table; no existing row is read or rewritten.

**Apply this BEFORE the merge**, the ordinary additive order: the new code
writes the table on every tick and the bot reads it, and the old code never
mentions it.

The DDL is the model's own, as ``CLAUDE.md`` requires -
``CreateTable(HealthCheck.__table__).compile(dialect=postgresql.dialect())``
prints exactly::

    CREATE TABLE health_checks (
        name VARCHAR(32) NOT NULL,
        status VARCHAR(16) NOT NULL,
        reason VARCHAR(200) NOT NULL,
        since TIMESTAMP WITHOUT TIME ZONE NOT NULL,
        last_alert_at TIMESTAMP WITHOUT TIME ZONE,
        checked_at TIMESTAMP WITHOUT TIME ZONE NOT NULL,
        previous_checked_at TIMESTAMP WITHOUT TIME ZONE,
        round_trip_ms INTEGER,
        PRIMARY KEY (name)
    )

``tests/test_health_checks_revision.py`` holds the revision to that text.
Applied through the Neon connector with ``alembic_version`` stamped in the
same transaction; on local SQLite the table arrives through ``create_all``,
which is why the guard below asks first.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0019"
down_revision: str | None = "0018"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _missing(table: str) -> bool:
    # 0016's guard, for 0016's reasons: a database built by `create_all` after
    # the model landed - `0001` on an empty file, or `scripts.init_db` -
    # already has the table, and creating it again is a hard error rather
    # than a no-op. Offline there is nobody to ask.
    if context.is_offline_mode():
        return True
    return not sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not _missing("health_checks"):
        return
    op.create_table(
        "health_checks",
        sa.Column("name", sa.String(length=32), nullable=False),
        sa.Column("status", sa.String(length=16), nullable=False),
        sa.Column("reason", sa.String(length=200), nullable=False),
        sa.Column("since", sa.DateTime(), nullable=False),
        sa.Column("last_alert_at", sa.DateTime(), nullable=True),
        sa.Column("checked_at", sa.DateTime(), nullable=False),
        sa.Column("previous_checked_at", sa.DateTime(), nullable=True),
        sa.Column("round_trip_ms", sa.Integer(), nullable=True),
        sa.PrimaryKeyConstraint("name"),
    )


def downgrade() -> None:
    op.drop_table("health_checks")
