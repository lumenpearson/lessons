"""The year cut into terms, and the class given a number.

Revision ID: 0008
Revises: 0007
Create Date: 2026-09-14

Two additions that go together.

``classes.grade`` and ``classes.letter`` make "which year of school is this" a
number instead of something to read out of the name. The rest of the app has to
reason about it — the term scheme follows the grade — and «9А» is not a thing to
parse: a class may be «9 инж», «11 ФМ» or «5-й Б», and a regular expression over
that is a guess that fails silently on the one class written differently. Both
are nullable, because every class that already exists has a name and no number,
and filling one in from the name would be exactly that guess.

``terms`` holds quarters and half-years as rows rather than as a formula. The
dates are a school's own decision and they move — the holidays shift, a region
starts its spring break early, a quarantine eats a week — so a class is seeded
with the conventional set and edits it. ``classes.term_kind`` is null until
somebody chooses, so "nobody decided, so quarters" stays distinguishable from
"an admin chose quarters for an eleventh year", which schools do.

Additive throughout: nothing existing is read, rewritten or dropped, so this
can be applied to the running code before the deploy that needs it — which is
the order docs/deploy.md prescribes.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0008"
down_revision: str | None = "0007"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _inspector():
    return sa.inspect(op.get_bind())


def _has_table(table: str) -> bool:
    if context.is_offline_mode():
        return False
    return _inspector().has_table(table)


def _has_column(table: str, column: str) -> bool:
    # The guard every revision after 0002 uses: a database bootstrapped by
    # ``create_all`` after the model landed already has the column, and ADD
    # COLUMN on it is a hard error rather than a no-op.
    if context.is_offline_mode() or not _has_table(table):
        return False
    return any(existing["name"] == column for existing in _inspector().get_columns(table))


def upgrade() -> None:
    if not _has_column("classes", "grade"):
        op.add_column("classes", sa.Column("grade", sa.Integer(), nullable=True))
    if not _has_column("classes", "letter"):
        op.add_column("classes", sa.Column("letter", sa.String(8), nullable=True))
    if not _has_column("classes", "term_kind"):
        # Non-native enum: a bare VARCHAR, like every enum in this schema. A
        # real Postgres ENUM would need its own type created and dropped, and
        # altering one later is a migration of its own.
        #
        # This used to say «a VARCHAR with a CHECK», and it is worth being
        # exact, because two things follow from there being no CHECK. The
        # values below are inert — SQLAlchemy 2.0 emits no constraint for
        # `native_enum=False`, so nothing would have rejected them even though
        # a `SAEnum` stores the member NAME and the ORM writes «QUARTER», not
        # «quarter». And the only thing the column really enforces is its
        # width, which is why adding a member is a revision: see `0014`.
        op.add_column(
            "classes",
            sa.Column(
                "term_kind",
                sa.Enum("quarter", "semester", name="termkind", native_enum=False),
                nullable=True,
            ),
        )

    if not _has_table("terms"):
        op.create_table(
            "terms",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("year", sa.Integer(), nullable=False),
            sa.Column(
                "kind",
                sa.Enum("quarter", "semester", name="termkind", native_enum=False),
                nullable=False,
            ),
            sa.Column("index", sa.Integer(), nullable=False),
            sa.Column("starts_on", sa.Date(), nullable=False),
            sa.Column("ends_on", sa.Date(), nullable=False),
            sa.UniqueConstraint("class_id", "year", "index", name="uq_term_slot"),
        )
        op.create_index("ix_terms_class_id", "terms", ["class_id"])


def downgrade() -> None:
    op.drop_index("ix_terms_class_id", table_name="terms")
    op.drop_table("terms")
    op.drop_column("classes", "term_kind")
    op.drop_column("classes", "letter")
    op.drop_column("classes", "grade")
