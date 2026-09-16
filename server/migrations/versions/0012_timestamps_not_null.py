"""Make eight more timestamps what the model says they are.

Revision ID: 0012
Revises: 0011
Create Date: 2026-09-16

``0011`` closed this for ``diary_overrides``; the same drift is in seven other
tables. ``0003`` wrote six columns as ``sa.Column(..., server_default=now())``
with no ``nullable=False``, ``0004`` one and ``0006`` one, while the model
builds all eight ``NOT NULL``:

    personal_tasks.created_at, personal_tasks.updated_at, homework_done.done_at,
    reminder_settings.created_at, access_requests.created_at,
    audit_log.created_at, diary_sessions.created_at, diary_link_codes.created_at

**Destroys nothing, and rewrites nothing that anybody put there.** Every one of
the eight has a server default, so no code path can write a null into them; the
``UPDATE`` before each ``ALTER`` exists for a row somebody wrote by hand, and on
a database built from the model it matches nothing. No row is deleted, no value
a person entered is touched.

**Safe to apply before the code that ships with it, unlike a constraint.** This
tightens columns that already hold no nulls, so code running against the
tightened schema — old or new — behaves identically. That is what makes the
usual order in ``CLAUDE.md`` (migration first, merge second) right here; it is
*not* right for ``0013``, and that revision says why.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0012"
down_revision: str | None = "0011"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

#: (table, column) for every timestamp the revisions left loose. Kept as data
#: rather than eight calls so that the list is readable against the docstring
#: and against the model.
_COLUMNS: tuple[tuple[str, str], ...] = (
    ("personal_tasks", "created_at"),
    ("personal_tasks", "updated_at"),
    ("homework_done", "done_at"),
    ("reminder_settings", "created_at"),
    ("access_requests", "created_at"),
    ("audit_log", "created_at"),
    ("diary_sessions", "created_at"),
    ("diary_link_codes", "created_at"),
)


def _has_table(table: str) -> bool:
    if context.is_offline_mode():
        return False
    return sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    # SQLite cannot alter a column, and the only way a local SQLite file exists
    # is `create_all`, which already builds all eight NOT NULL. Postgres's
    # alone, exactly as 0011.
    if op.get_bind().dialect.name == "sqlite":
        return
    for table, column in _COLUMNS:
        if not context.is_offline_mode() and not _has_table(table):
            continue
        op.execute(
            sa.text(f"UPDATE {table} SET {column} = now() WHERE {column} IS NULL")  # noqa: S608
        )
        op.alter_column(table, column, nullable=False)


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        return
    for table, column in _COLUMNS:
        op.alter_column(table, column, nullable=True)
