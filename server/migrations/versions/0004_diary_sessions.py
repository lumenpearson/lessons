"""Sessions with the Petersburg electronic diary.

Revision ID: 0004
Revises: 0003
Create Date: 2026-09-12

One table, additive, inspector-guarded like 0003 so that a database which was
bootstrapped by ``create_all`` after the model landed does not trip over a
``CREATE TABLE`` for a table it already has.

Worth saying what is *not* in it: there is no password column. The upstream's
own session token is stored and refreshed, and when it stops being accepted
the person signs in again. That costs a login screen every few days and buys
not holding a family's password for the service that holds their child's
marks.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0004"
down_revision: str | None = "0003"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _missing(table: str) -> bool:
    if context.is_offline_mode():
        return True
    return not sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not _missing("diary_sessions"):
        return
    op.create_table(
        "diary_sessions",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("token_hash", sa.String(64), nullable=False),
        sa.Column("upstream_token", sa.Text(), nullable=False),
        sa.Column("login", sa.String(200), nullable=False),
        sa.Column("telegram_id", sa.BigInteger(), nullable=True),
        sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        sa.Column("last_used_at", sa.DateTime(), nullable=True),
        sa.Column("expired_at", sa.DateTime(), nullable=True),
    )
    op.create_index(
        "ix_diary_sessions_token_hash", "diary_sessions", ["token_hash"], unique=True
    )
    op.create_index("ix_diary_sessions_telegram_id", "diary_sessions", ["telegram_id"])


def downgrade() -> None:
    op.drop_table("diary_sessions")
