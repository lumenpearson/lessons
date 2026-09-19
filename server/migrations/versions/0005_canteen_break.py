"""Which break the canteen falls on.

Revision ID: 0005
Revises: 0004
Create Date: 2026-09-13

One nullable column on ``bell_schedules``. Lunch is a break, not a lesson and
not a weekly event: it is the same break every day the schedule is in force,
and it moves with the bells when a shortened day moves them. Modelled as a
recurring ``DayEvent`` it would have to be re-derived every time a bell row
shifted by five minutes, and a class that shortened its Thursday would eat at
the old time until somebody noticed.

``NULL`` means «не отмечена» — not marked — which is what every existing schedule gets and
what the day view renders as an ordinary break.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0005"
down_revision: str | None = "0004"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _has_column(table: str, column: str) -> bool:
    # Same guard 0003 and 0004 use: a database bootstrapped by ``create_all``
    # after the model landed already has the column, and ADD COLUMN on it is a
    # hard error rather than a no-op.
    if context.is_offline_mode():
        return False
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(table):
        return False
    return any(existing["name"] == column for existing in inspector.get_columns(table))


def upgrade() -> None:
    if _has_column("bell_schedules", "canteen_after_index"):
        return
    op.add_column(
        "bell_schedules", sa.Column("canteen_after_index", sa.Integer(), nullable=True)
    )


def downgrade() -> None:
    op.drop_column("bell_schedules", "canteen_after_index")
