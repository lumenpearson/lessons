"""Baseline: everything the model declares, created if missing.

Revision ID: 0001
Revises:
Create Date: 2026-09-11

This revision is a create_all with ``checkfirst``, not a hand-written list of
tables, and that is the point: it has to be correct on two databases that look
nothing alike.

* A fresh database gets the whole schema.
* A database created by the old ``python -m scripts.init_db`` already has most
  of it and has no ``alembic_version`` row, so this is the first thing that runs
  against it. Creating only what is missing brings it under Alembic's control
  without touching a byte of existing data — and at the time of writing what is
  missing is exactly ``join_attempts``.

Nothing after this revision may use create_all. From here on the schema changes
by explicit ALTER, which is what 0002 does.
"""

from __future__ import annotations

from collections.abc import Sequence

from alembic import context, op

from app.db import Base

# Importing the models registers them on the metadata.
from app import models  # noqa: F401  isort:skip

revision: str = "0001"
down_revision: str | None = None
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    # --sql mode has no database to check against, so it emits the whole schema.
    checkfirst = not context.is_offline_mode()
    Base.metadata.create_all(bind=op.get_bind(), checkfirst=checkfirst)


def downgrade() -> None:
    # Deliberately not drop_all. Downgrading past the baseline means destroying
    # every timetable, homework entry and device token in the database, and an
    # accidental `alembic downgrade base` is far likelier than a deliberate one.
    raise RuntimeError(
        "Refusing to drop the whole schema. Drop the database by hand if that "
        "is really what you mean."
    )
