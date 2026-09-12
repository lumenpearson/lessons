"""Widen every stored Telegram user id to 64 bits.

Revision ID: 0002
Revises: 0001
Create Date: 2026-09-11

``Integer`` maps to int4 on Postgres, which stops at 2147483647. Telegram user
ids passed that in 2021 and the API documents them as up to 52 bits, so a
database created before the model was corrected stores these five columns too
narrow: the first person with a modern id to touch the bot gets a driver-level
overflow rather than a row.

A database created *after* the correction already has them as BIGINT, so each
column is checked before it is touched. SQLite has one integer type and stores
64 bits regardless, so on SQLite this whole revision is a no-op — which also
keeps the test database out of the batch-mode table rebuild it would otherwise
need.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0002"
down_revision: str | None = "0001"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# (table, column, nullable) — every column in the schema holding a Telegram id.
_ID_COLUMNS: tuple[tuple[str, str, bool], ...] = (
    ("homework", "created_by", True),
    ("bot_users", "telegram_id", False),
    ("bot_users", "granted_by", True),
    ("phone_invites", "invited_by", True),
    ("phone_invites", "used_by", True),
)


def _widen(to: type[sa.types.TypeEngine], frm: type[sa.types.TypeEngine]) -> None:
    bind = op.get_bind()
    if bind.dialect.name == "sqlite":
        return

    # --sql mode has no database to ask, so it emits every statement and the
    # reader decides. Online it asks, because a database created after the model
    # was corrected already has these columns wide and rewriting a table for no
    # change is a lock nobody needs.
    offline = context.is_offline_mode()
    inspector = None if offline else sa.inspect(bind)

    for table, column, nullable in _ID_COLUMNS:
        if inspector is not None:
            if not inspector.has_table(table):
                continue
            current = next(
                (c for c in inspector.get_columns(table) if c["name"] == column),
                None,
            )
            if current is None or _already(current["type"], to):
                continue
        op.alter_column(
            table,
            column,
            existing_type=frm(),
            type_=to(),
            existing_nullable=nullable,
        )


def _already(current: sa.types.TypeEngine, to: type[sa.types.TypeEngine]) -> bool:
    """Whether the reflected column is already the target width.

    Not a bare ``isinstance``: ``BigInteger`` subclasses ``Integer``, so on the
    way down every BIGINT column passed ``isinstance(current, Integer)`` and the
    downgrade skipped all five while still recording itself as applied.
    """
    if to is sa.Integer:
        return not isinstance(current, sa.BigInteger)
    return isinstance(current, to)


def upgrade() -> None:
    _widen(to=sa.BigInteger, frm=sa.Integer)


def downgrade() -> None:
    # Narrowing back would truncate any id that needed the width in the first
    # place, so it is offered only for a database that has not yet stored one.
    _widen(to=sa.Integer, frm=sa.BigInteger)
