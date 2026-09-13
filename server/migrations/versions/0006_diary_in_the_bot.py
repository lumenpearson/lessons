"""The diary, per member, with its credential encrypted.

Revision ID: 0006
Revises: 0005
Create Date: 2026-09-13

Three things, and the first one is the reason for the other two.

**Existing diary sessions are deleted.** ``diary_sessions.upstream_token`` held
the upstream's live ``X-JWT-Token`` in a plaintext ``Text`` column. From this
revision it holds a Fernet blob (``app/crypto.py``), and there is no honest way
to convert one to the other: sealing the old values would preserve credentials
that have already been sitting in every backup and every replica of this
database in the clear. They are deleted instead. The cost is that anyone signed
in signs in again — which already happens every few days when the upstream
expires its own token, so it is a path that is walked rather than one that is
written down and never taken.

**Sessions learn whose they are.** ``class_id`` so that leaving a class takes
its diary session with it and «Дневник» in one class cannot answer with a
session opened in another; ``student_id`` so a parent account with several
children is asked once rather than on every screen.

**Classes learn what they are bound to.** ``diary_provider`` names the
electronic diary a class reads, ``is_public`` says whether the join code is
enough to get in. Neither holds a credential: binding a class offers its
members a way to sign in to their *own* accounts, and gives the class itself
nothing.

Plus ``diary_link_codes``: one-time tickets from a Telegram chat to the
sign-in form, so that the password never enters Telegram.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0006"
down_revision: str | None = "0005"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def _inspector():
    return sa.inspect(op.get_bind())


def _has_table(table: str) -> bool:
    if context.is_offline_mode():
        return False
    return _inspector().has_table(table)


def _has_column(table: str, column: str) -> bool:
    # The guard 0003, 0004 and 0005 all use: a database bootstrapped by
    # ``create_all`` after the model landed already has the column, and ADD
    # COLUMN on it is a hard error rather than a no-op.
    if context.is_offline_mode():
        return False
    if not _has_table(table):
        return False
    return any(existing["name"] == column for existing in _inspector().get_columns(table))


def upgrade() -> None:
    # Order matters: purge before the schema changes, while the rows are still
    # shaped the way this statement expects.
    if _has_table("diary_sessions"):
        op.execute(sa.text("DELETE FROM diary_sessions"))

        if not _has_column("diary_sessions", "class_id"):
            op.add_column("diary_sessions", sa.Column("class_id", sa.Integer(), nullable=True))
            op.create_index(
                "ix_diary_sessions_class_id", "diary_sessions", ["class_id"]
            )
        if not _has_column("diary_sessions", "student_id"):
            op.add_column(
                "diary_sessions", sa.Column("student_id", sa.BigInteger(), nullable=True)
            )

    if not _has_column("classes", "diary_provider"):
        op.add_column("classes", sa.Column("diary_provider", sa.String(32), nullable=True))
    if not _has_column("classes", "is_public"):
        # server_default so the column can be NOT NULL on a table that already
        # has rows; the model's own default covers rows made from Python.
        op.add_column(
            "classes",
            sa.Column(
                "is_public", sa.Boolean(), nullable=False, server_default=sa.false()
            ),
        )

    if not _has_table("diary_link_codes"):
        op.create_table(
            "diary_link_codes",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("code_hash", sa.String(64), nullable=False),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column("class_id", sa.Integer(), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
            sa.Column("expires_at", sa.DateTime(), nullable=False),
            sa.Column("used_at", sa.DateTime(), nullable=True),
        )
        op.create_index(
            "ix_diary_link_codes_code_hash", "diary_link_codes", ["code_hash"], unique=True
        )
        op.create_index(
            "ix_diary_link_codes_telegram_id", "diary_link_codes", ["telegram_id"]
        )
        op.create_index("ix_diary_link_codes_class_id", "diary_link_codes", ["class_id"])


def downgrade() -> None:
    op.drop_table("diary_link_codes")
    op.drop_column("classes", "is_public")
    op.drop_column("classes", "diary_provider")
    op.drop_column("diary_sessions", "student_id")
    op.drop_index("ix_diary_sessions_class_id", table_name="diary_sessions")
    op.drop_column("diary_sessions", "class_id")
    # The sealed rows are not convertible back to plaintext, and leaving them
    # in a column the old code reads raw would hand the upstream a Fernet blob
    # as a bearer token. Empty is the only correct state to go back to.
    op.execute(sa.text("DELETE FROM diary_sessions"))
