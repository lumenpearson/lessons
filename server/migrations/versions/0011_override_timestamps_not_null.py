"""Make the corrections' timestamps what the model says they are.

Revision ID: 0011
Revises: 0010
Create Date: 2026-09-15

``0009`` declared ``diary_overrides.created_at`` and ``updated_at`` without
``nullable=False`` while the model builds both ``NOT NULL``. Nothing breaks
from it — each carries ``server_default now()``, so neither is ever actually
null — but ``0009`` is already applied, so the running database disagrees
permanently with what ``create_all`` would produce, and the next person doing
the compile-and-compare pass this project uses to check a revision has to
re-adjudicate a diff that means nothing.

**Destroys nothing.** It rewrites no value and drops no row; it tightens two
columns that cannot hold a null to say so. The one way it could fail is a row
that already has one, which the ``UPDATE`` below cannot produce and the server
default cannot either — it is there so that the revision is true of a database
somebody wrote to by hand, rather than being true only of ours.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0011"
down_revision: str | None = "0010"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_COLUMNS = ("created_at", "updated_at")


def _has_table(table: str) -> bool:
    if context.is_offline_mode():
        return False
    return sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    # SQLite cannot alter a column, and a database that got here through
    # `create_all` already has both columns NOT NULL — which is the only way a
    # local SQLite file exists at all. So this is Postgres's alone.
    if op.get_bind().dialect.name == "sqlite":
        return
    if not context.is_offline_mode() and not _has_table("diary_overrides"):
        return
    for column in _COLUMNS:
        op.execute(
            sa.text(
                f"UPDATE diary_overrides SET {column} = now() WHERE {column} IS NULL"  # noqa: S608
            )
        )
        op.alter_column("diary_overrides", column, nullable=False)


def downgrade() -> None:
    if op.get_bind().dialect.name == "sqlite":
        return
    for column in _COLUMNS:
        op.alter_column("diary_overrides", column, nullable=True)
