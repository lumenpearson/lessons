"""Make the corrections' timestamps what the model says they are.

Revision ID: 0011
Revises: 0010
Create Date: 2026-09-15

``0009`` declared ``diary_overrides.created_at`` and ``updated_at`` without
``nullable=False`` while the model builds both ``NOT NULL``. This closes that.

**On our own production it changes nothing, and that is the whole point.**
``0009`` was applied through the Neon connector, which is not alembic running —
it is the revision's DDL executed as one transaction, and the DDL is taken from
the model rather than written out by hand (see ``docs/deploy.md``). So the
columns there are already ``NOT NULL``: the divergence was never in the
database, it was between the revision file and the model. Anyone who applies
this chain with ``alembic upgrade head`` — a second deployment, a school
running its own server — gets the looser schema, and from here on gets this
statement right after it.

**Destroys nothing.** It rewrites no value and drops no row; it tightens two
columns that cannot hold a null to say so, and on a database built from the
model it is a no-op the dialect resolves to nothing. The one way it could fail
is a row that already holds a null, which neither the server default nor any
code path can produce — the ``UPDATE`` below is there so the revision is true
of a database somebody wrote to by hand, not only of ours.
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
