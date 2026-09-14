"""The two foreign keys 0006 should have declared.

Revision ID: 0007
Revises: 0006
Create Date: 2026-09-14

``diary_sessions.class_id`` and ``diary_link_codes.class_id`` are declared in
``models.py`` as ``ForeignKey("classes.id", ondelete="CASCADE")``, and 0006
created them as plain indexed integers. Every other class-scoped table in this
schema cascades — eighteen of them — so the two that do not are not a design
choice, they are the ones that got missed.

A database built by ``create_all`` therefore cascades and a migrated one does
not, which is the worse half of the bug: the behaviour that gets tested
locally is not the behaviour that runs. Deleting a class on production left
its diary sessions behind — rows keyed to a class that no longer exists, which
``_session_for`` matches on ``class_id`` and would hand back inside whatever
class later took that id.

Orphans are deleted rather than adopted or nulled. ``diary_link_codes`` cannot
null (the column is NOT NULL) and an orphaned ticket is worth nothing anyway;
an orphaned session belongs to a class that is gone, and the person signs in
again — the same path the upstream already puts them on every few days when it
expires its own token.

The guard is the one 0003 through 0006 use, one step further out: a database
bootstrapped by ``create_all`` already has both constraints, and adding them
again is an error rather than a no-op. That guard is also why SQLite never
reaches the ``ALTER`` — it could not execute one, since adding a constraint
there means rewriting the table — and it never has to: ``0001``'s guarded
``create_all`` builds both tables from the model, foreign keys included.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0007"
down_revision: str | None = "0006"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

# (table, constraint name). The column is ``class_id`` on both and the target
# is ``classes.id`` on both; naming them explicitly is what lets downgrade
# drop exactly these and not whatever else the dialect invented.
LINKS: list[tuple[str, str]] = [
    ("diary_sessions", "fk_diary_sessions_class_id_classes"),
    ("diary_link_codes", "fk_diary_link_codes_class_id_classes"),
]


def _inspector():
    return sa.inspect(op.get_bind())


def _needs_key(table: str) -> bool:
    if context.is_offline_mode():
        return False
    inspector = _inspector()
    if not inspector.has_table(table):
        return False
    already = any(
        key["constrained_columns"] == ["class_id"] for key in inspector.get_foreign_keys(table)
    )
    return not already


def upgrade() -> None:
    for table, name in LINKS:
        if not _needs_key(table):
            continue
        # ADD CONSTRAINT validates the rows that are already there, so a single
        # row pointing at a deleted class fails the whole migration. The window
        # where such a row could be written is exactly the time this schema ran
        # without the key.
        op.execute(
            sa.text(
                f"DELETE FROM {table} WHERE class_id IS NOT NULL "  # noqa: S608 — table is a literal above
                "AND class_id NOT IN (SELECT id FROM classes)"
            )
        )
        op.create_foreign_key(
            name, table, "classes", ["class_id"], ["id"], ondelete="CASCADE"
        )


def downgrade() -> None:
    for table, name in LINKS:
        op.drop_constraint(name, table, type_="foreignkey")
