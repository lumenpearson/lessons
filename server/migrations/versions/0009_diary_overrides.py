"""Corrections a family lays over what the diary sent down.

Revision ID: 0009
Revises: 0008
Create Date: 2026-09-15

One new table and nothing else. ``diary_overrides`` holds a value put over a
field of a lesson or a homework item on the way out, with what the diary said
at the time kept beside it so that a later change upstream can be noticed
rather than hidden. Nothing is ever sent to dnevnik2; the upstream stays
read-only to this project.

Keyed by the upstream login rather than by ``diary_sessions.id``: a session is
re-made every few days when the upstream token dies, and a correction that went
with it would disappear on its own, silently, which is the one thing a "reset"
button must not do for you.

**Additive throughout.** No existing table is read, rewritten or dropped, so
this can be applied to the running code before the deploy that needs it — which
is the order ``docs/deploy.md`` prescribes and the only order that is safe
here. Unlike ``0006``, this revision destroys nothing.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0009"
down_revision: str | None = "0008"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _inspector():
    return sa.inspect(op.get_bind())


def _has_table(table: str) -> bool:
    # The guard every revision after 0002 uses: a database bootstrapped by
    # ``create_all`` after the model landed already has the table, and CREATE
    # TABLE on it is a hard error rather than a no-op.
    if context.is_offline_mode():
        return False
    return _inspector().has_table(table)


def upgrade() -> None:
    if _has_table("diary_overrides"):
        return
    op.create_table(
        "diary_overrides",
        sa.Column("id", sa.Integer(), primary_key=True),
        sa.Column("login", sa.String(200), nullable=False),
        sa.Column("student_id", sa.BigInteger(), nullable=False),
        sa.Column("target", sa.String(300), nullable=False),
        sa.Column("field", sa.String(40), nullable=False),
        sa.Column("value", sa.Text(), nullable=False),
        sa.Column("original", sa.Text(), nullable=True),
        sa.Column("created_at", sa.DateTime(), server_default=sa.func.now(), nullable=True),
        sa.Column("updated_at", sa.DateTime(), server_default=sa.func.now(), nullable=True),
        sa.UniqueConstraint("login", "student_id", "target", "field", name="uq_diary_override"),
    )
    # The read path always asks the same question — every correction for one
    # child — so the index is on exactly that pair rather than on login alone.
    op.create_index("ix_diary_override_owner", "diary_overrides", ["login", "student_id"])


def downgrade() -> None:
    op.drop_index("ix_diary_override_owner", table_name="diary_overrides")
    op.drop_table("diary_overrides")
