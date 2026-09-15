"""How a phone is let into a class, and the personal codes that do it.

Revision ID: 0010
Revises: 0009
Create Date: 2026-09-15

``classes.join_mode`` is ``OPEN`` or ``INVITE`` — the member names, because
that is what SQLAlchemy stores for an enum column and what ``bot_users.role``
already holds; the API spells the same two in lower case. ``OPEN`` is what
every class already was — the class code admits whoever types it — so it is
the server default and no existing class changes behaviour by being migrated.
``INVITE`` stops the class code admitting anything and accepts the one-time
codes in ``device_invites`` instead, which the bot mints for a member it
already recognises.

``device_invites`` is one code, worth one join, for fifteen minutes, for one
Telegram account, in one class. Hashed like every other credential here, so a
leak of the table is a list of codes that cannot be used.

**Additive throughout.** Nothing existing is read, rewritten or dropped: the
column arrives with a default that preserves the current behaviour, and the
table is new. It can be applied to the running code before the deploy that
needs it — the order ``docs/deploy.md`` prescribes and the only safe one.

``classes.is_public`` is **not** dropped here, though the code that shipped
with this revision stops mapping it. It was a flag no code path ever read — an
admin who «закрыл» the class closed nothing — and ``join_mode`` is that promise
kept; but dropping a column is not additive, and it has a server default, so an
insert that omits it is fine. It stays until there is a revision whose job is
to take things away.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0010"
down_revision: str | None = "0009"
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
    # ``create_all`` after the model landed already has it, and adding it again
    # is a hard error rather than a no-op.
    if context.is_offline_mode() or not _has_table(table):
        return False
    return any(existing["name"] == column for existing in _inspector().get_columns(table))


def upgrade() -> None:
    if not _has_column("classes", "join_mode"):
        op.add_column(
            "classes",
            sa.Column(
                "join_mode",
                # A plain VARCHAR, like every other enum in this schema: a real
                # Postgres ENUM would need its own type created and dropped,
                # and altering one later is a migration of its own.
                #
                # Upper case because that is what SQLAlchemy writes — a
                # PEP-435 enum is stored by member name, not by value, which
                # is why `roles` in this database reads «OWNER». A default of
                # «open» would be on every existing class and unreadable by
                # the ORM the moment the code that knows the column deploys.
                sa.Enum("OPEN", "INVITE", name="joinmode", native_enum=False),
                nullable=False,
                server_default="OPEN",
            ),
        )

    if not _has_table("device_invites"):
        op.create_table(
            "device_invites",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column("code_hash", sa.String(64), nullable=False),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column(
                "created_at", sa.DateTime(), server_default=sa.func.now(), nullable=False
            ),
            sa.Column("expires_at", sa.DateTime(), nullable=False),
            sa.Column("used_at", sa.DateTime(), nullable=True),
        )
        op.create_index(
            "ix_device_invites_code_hash", "device_invites", ["code_hash"], unique=True
        )
        op.create_index("ix_device_invites_class_id", "device_invites", ["class_id"])
        op.create_index("ix_device_invites_telegram_id", "device_invites", ["telegram_id"])


def downgrade() -> None:
    op.drop_index("ix_device_invites_telegram_id", table_name="device_invites")
    op.drop_index("ix_device_invites_class_id", table_name="device_invites")
    op.drop_index("ix_device_invites_code_hash", table_name="device_invites")
    op.drop_table("device_invites")
    op.drop_column("classes", "join_mode")
