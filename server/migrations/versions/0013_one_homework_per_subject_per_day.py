"""One задание per subject per day, said by the database.

Revision ID: 0013
Revises: 0012
Create Date: 2026-09-16

`homework` carries `ix_homework_lookup` on `(class_id, due_date)` and nothing
that makes a row unique. Both writers — `api/edit.py:homework_put`, whose
docstring opens with «Upsert by (date, subject)», and the bot's flow — read
then wrote, so two people saving «Алгебра» for Friday at the same moment made
two rows. Nothing complains: the evening digest lists the subject twice, and
whichever row a phone ticks off leaves the other one unticked.

**THIS REVISION DELETES ROWS.** Where a class already has more than one задание
for the same `(class_id, due_date, subject_name)`, it keeps the one with the
highest `id` — the most recently written, which is the one the two shells' own
«sending it again replaces the text» rule means — and deletes the rest. On a
class that never hit the race that is nothing; on one that did, the text that
disappears is text somebody typed, and the count is worth reading out of the
`SELECT` below before running the `DELETE`:

    SELECT class_id, due_date, subject_name, count(*)
      FROM homework GROUP BY 1, 2, 3 HAVING count(*) > 1;

**Apply this one AFTER the code it ships with, not before.** `CLAUDE.md`'s rule
— migration first, merge second — is about a column the new code knows and the
database does not, and it is right for every additive revision in this chain,
`0012` included. A constraint is the other direction: it is the *old* code that
breaks against it. Before `services/homework.py` exists, the losing side of the
race does a plain INSERT and gets an `IntegrityError` nobody catches, which is
a 500 where today there is a duplicate. With that service in place the loser
re-reads and updates, which is what it meant to do all along. So: merge, then
apply. The window in between behaves exactly as production does today.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0013"
down_revision: str | None = "0012"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

_NAME = "uq_homework_per_subject_per_day"
_COLUMNS = ("class_id", "due_date", "subject_name")

#: Keep the newest of each group. `id` is the insertion order and the two
#: shells both treat a later write as replacing an earlier one, so the highest
#: id is the row a reader would have been shown anyway.
_DEDUPE = sa.text(
    """
    DELETE FROM homework
     WHERE id NOT IN (
           SELECT max(id) FROM homework
            GROUP BY class_id, due_date, subject_name
     )
    """
)


def _has_table(table: str) -> bool:
    if context.is_offline_mode():
        return False
    return sa.inspect(op.get_bind()).has_table(table)


def upgrade() -> None:
    if not context.is_offline_mode() and not _has_table("homework"):
        return
    op.execute(_DEDUPE)
    op.create_unique_constraint(_NAME, "homework", list(_COLUMNS))


def downgrade() -> None:
    op.drop_constraint(_NAME, "homework", type_="unique")
