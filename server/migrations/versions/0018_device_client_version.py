"""Record the app version each phone last sent to v2.

Revision ID: 0018
Revises: 0017
Create Date: 2026-10-05

v2's gate reads ``X-Lessons-Client: <versionCode>`` on every call
(``docs/specs/2026-10-05-server-v2-design.md``, decision 9). Decision 15 keeps
the last one per phone, so that «📱 Устройства» can say which phones still run
an old APK before v1 is retired: ``device_tokens.client_version``, written by
the gate in the statement that writes ``last_seen_at`` and on its
fifteen-minute clock. It is ``NULL`` on every row before this revision, and on
every phone that never sent the header, which is every APK that speaks only v1.

**Destroys nothing.** One nullable column with no default, no index and no key.

**Apply this BEFORE the merge**, the ordinary additive order: the new code
writes and reads the column, and the old code never mentions it.

The DDL is the model's own, as ``CLAUDE.md`` requires: the column's type
compiled for PostgreSQL is ``INTEGER``, so the one statement is::

    ALTER TABLE device_tokens ADD COLUMN client_version INTEGER

``tests/test_client_version_revision.py`` holds the revision to it. Applied
through the Neon connector, with ``alembic_version`` stamped in the same
transaction.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op

revision: str = "0018"
down_revision: str | None = "0017"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _has_column(table: str, column: str) -> bool:
    # 0015's guard, for 0015's reasons: on an empty database 0001's
    # `create_all` has already built the column, and adding it twice is a hard
    # error on Postgres; a SQLite file `scripts.init_db` built before this
    # model change is stamped 0017 without it. Offline (`--sql`) there is
    # nobody to ask, and the database the script is for is one at 0017.
    if op.get_context().as_sql:
        return False
    inspector = sa.inspect(op.get_bind())
    if not inspector.has_table(table):
        return False
    return any(existing["name"] == column for existing in inspector.get_columns(table))


def upgrade() -> None:
    # Nullable with no default: SQLite's own ALTER TABLE ADD COLUMN takes it
    # as it is, and Postgres rewrites no row.
    if not _has_column("device_tokens", "client_version"):
        op.add_column("device_tokens", sa.Column("client_version", sa.Integer(), nullable=True))


def downgrade() -> None:
    op.drop_column("device_tokens", "client_version")
