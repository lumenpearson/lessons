"""Make room in ``day_overrides.kind`` for two day kinds that did not fit.

Revision ID: 0014
Revises: 0013
Create Date: 2026-09-21

``DayKind`` gains ``SELF_STUDY`` and ``DAY_OFF``. A ``SAEnum`` in this project
stores the member **name**, so the column is a ``VARCHAR`` exactly as wide as
the longest of them — it was ``VARCHAR(9)``, for ``SHORTENED``, and
``SELF_STUDY`` is ten characters. Without this, writing one raises a
``StringDataRightTruncation`` from the driver at the moment somebody marks a
day, and nothing before that point says a word.

The DDL is taken from the model rather than written out, the way ``CLAUDE.md``
requires: ``CreateTable(DayOverride.__table__)`` prints ``VARCHAR(10)`` today,
and if a third kind is added later this revision is not the thing that has to
be remembered — the next one is written the same way from the same source.

What *has* to be remembered is that the next kind needs a revision at all, and
nothing said so: the local database is SQLite, which ignores ``VARCHAR``
lengths entirely, so the member that does not fit is green everywhere until
production. ``tests/test_enum_column_widths.py`` is that guard now — this
revision closed the instance, and that file closes the class.

**Destroys nothing.** Widening a ``VARCHAR`` rewrites no row on Postgres and
cannot fail on existing data: every value already in the column is at most nine
characters, and all of them stay exactly what they were. No row is deleted and
no value anybody entered is touched.

**Apply this BEFORE the merge**, which is the ordinary order in ``CLAUDE.md``
and is right here for the ordinary reason: it is the *new* code that needs the
wider column, and the old code is perfectly happy with one. That is the
opposite of ``0013``, which added a ``UNIQUE`` — a constraint is what the old
code breaks against, so it went on after. A widening has no such edge; a
deployment that runs this and then never merges is a database with three spare
characters in one column.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0014"
down_revision: str | None = "0013"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None

#: The width the model asks for, read off the model rather than counted by
#: hand. Kept as a literal here because a migration has to say what it did on
#: the day it ran — importing `app.models` would make this revision mean
#: something different after the next kind is added, which is the one thing a
#: revision must never do.
_WIDTH = 10
_WAS = 9


def upgrade() -> None:
    # SQLite cannot alter a column type, and the only way a local SQLite file
    # exists here is `create_all`, which builds the column at whatever width
    # the model currently asks for. Postgres's alone, exactly as 0011 and 0012.
    if op.get_bind().dialect.name == "sqlite":
        return
    op.alter_column(
        "day_overrides",
        "kind",
        existing_type=sa.String(length=_WAS),
        type_=sa.String(length=_WIDTH),
        existing_nullable=False,
    )


def downgrade() -> None:
    """Narrowing back is only safe while no row uses the new kinds.

    Left as the plain reverse rather than guarded: a downgrade that silently
    dropped somebody's «самоподготовка» days to make itself fit would be worse
    than one that refuses. Postgres refuses on its own, with the row that does
    not fit named in the error.
    """
    if op.get_bind().dialect.name == "sqlite":
        return
    op.alter_column(
        "day_overrides",
        "kind",
        existing_type=sa.String(length=_WIDTH),
        type_=sa.String(length=_WAS),
        existing_nullable=False,
    )
