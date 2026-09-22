"""Personal tasks, homework ticks, reminders, access requests, audit log,
device linking, the calendar feed token - and the FSM table where it is missing.

Revision ID: 0003
Revises: 0002
Create Date: 2026-09-12

Additive only. Every statement is guarded by an inspector check because a
database bootstrapped by ``create_all`` (the SQLite dev file, or a Postgres
that ran ``scripts.init_db`` after this model landed) already has all of it,
and ``CREATE TABLE`` on a table that exists is an error, not a no-op.

New columns are nullable or carry a server default, so no table is rewritten
and no row is touched: this can run against a live class in the middle of a
school day.
"""

from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import context, op

revision: str = "0003"
down_revision: str | None = "0002"
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


# The values are lower case and the ORM writes the member NAME — «VIEWER» —
# and that is not the mismatch it looks like: `native_enum=False` emits no
# CHECK on SQLAlchemy 2.0, so this list reaches the database as nothing at all.
# What does reach it is `length`, spelled out here because these four values
# are not the four names and would otherwise have sized the column at 6 by
# accident rather than on purpose. It is the only thing the column enforces,
# and it is why a fifth role would be a revision of its own — see `0014`.
_ROLE = sa.Enum("viewer", "editor", "admin", "owner", name="role", native_enum=False, length=6)


def _inspector():
    if context.is_offline_mode():
        return None
    return sa.inspect(op.get_bind())


def _has_table(inspector, table: str) -> bool:
    return inspector is None or not inspector.has_table(table)


def _lacks_column(inspector, table: str, column: str) -> bool:
    if inspector is None:
        return True
    if not inspector.has_table(table):
        return False
    return all(c["name"] != column for c in inspector.get_columns(table))


def upgrade() -> None:
    inspector = _inspector()

    # Not new, but missing wherever 0001 ran before the FSM table was part of
    # the metadata it created from: bootstrap and baseline imported only
    # models.py, and fsm_states is declared in fsm_storage.py. A database
    # already at 0002 never re-runs 0001, so it is created here if absent.
    if _has_table(inspector, "fsm_states"):
        op.create_table(
            "fsm_states",
            sa.Column("key", sa.String(200), primary_key=True),
            sa.Column("state", sa.String(200), nullable=True),
            sa.Column("data", sa.Text(), nullable=False, server_default="{}"),
            sa.Column("updated_at", sa.DateTime(), nullable=False),
        )

    if _lacks_column(inspector, "classes", "calendar_token"):
        op.add_column("classes", sa.Column("calendar_token", sa.String(64), nullable=True))
        op.create_index(
            "ix_classes_calendar_token", "classes", ["calendar_token"], unique=True
        )

    for column in (
        sa.Column("telegram_id", sa.BigInteger(), nullable=True),
        sa.Column("link_code", sa.String(16), nullable=True),
        sa.Column("linked_at", sa.DateTime(), nullable=True),
    ):
        if _lacks_column(inspector, "device_tokens", column.name):
            op.add_column("device_tokens", column)
            if column.name == "telegram_id":
                op.create_index(
                    "ix_device_tokens_telegram_id", "device_tokens", ["telegram_id"]
                )
            if column.name == "link_code":
                op.create_index(
                    "ix_device_tokens_link_code", "device_tokens", ["link_code"], unique=True
                )

    if _has_table(inspector, "personal_tasks"):
        op.create_table(
            "personal_tasks",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column("title", sa.String(200), nullable=False),
            sa.Column("notes", sa.Text(), nullable=True),
            sa.Column("subject_name", sa.String(120), nullable=True),
            sa.Column("due_date", sa.Date(), nullable=True),
            sa.Column("due_time", sa.Time(), nullable=True),
            sa.Column("priority", sa.Integer(), nullable=False, server_default="1"),
            sa.Column("done", sa.Boolean(), nullable=False, server_default=sa.false()),
            sa.Column("done_at", sa.DateTime(), nullable=True),
            sa.Column(
                "homework_id",
                sa.Integer(),
                sa.ForeignKey("homework.id", ondelete="SET NULL"),
                nullable=True,
            ),
            sa.Column("remind_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
            sa.Column("updated_at", sa.DateTime(), server_default=sa.func.now()),
        )
        op.create_index("ix_personal_tasks_class_id", "personal_tasks", ["class_id"])
        op.create_index("ix_personal_tasks_telegram_id", "personal_tasks", ["telegram_id"])
        op.create_index(
            "ix_task_owner", "personal_tasks", ["class_id", "telegram_id", "done", "due_date"]
        )

    if _has_table(inspector, "homework_done"):
        op.create_table(
            "homework_done",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "homework_id",
                sa.Integer(),
                sa.ForeignKey("homework.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column("done_at", sa.DateTime(), server_default=sa.func.now()),
            sa.UniqueConstraint("homework_id", "telegram_id", name="uq_homework_done"),
        )
        op.create_index("ix_homework_done_homework_id", "homework_done", ["homework_id"])
        op.create_index("ix_homework_done_telegram_id", "homework_done", ["telegram_id"])

    if _has_table(inspector, "reminder_settings"):
        op.create_table(
            "reminder_settings",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column("morning_at", sa.Time(), nullable=True),
            sa.Column("evening_at", sa.Time(), nullable=True),
            sa.Column(
                "notify_changes", sa.Boolean(), nullable=False, server_default=sa.true()
            ),
            sa.Column(
                "notify_homework", sa.Boolean(), nullable=False, server_default=sa.false()
            ),
            sa.Column("last_morning_sent", sa.Date(), nullable=True),
            sa.Column("last_evening_sent", sa.Date(), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
            sa.UniqueConstraint("class_id", "telegram_id", name="uq_reminder_owner"),
        )
        op.create_index("ix_reminder_settings_class_id", "reminder_settings", ["class_id"])
        op.create_index(
            "ix_reminder_settings_telegram_id", "reminder_settings", ["telegram_id"]
        )

    if _has_table(inspector, "access_requests"):
        op.create_table(
            "access_requests",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=False),
            sa.Column("requested_role", _ROLE, nullable=False),
            sa.Column("status", sa.String(16), nullable=False, server_default="pending"),
            sa.Column("message", sa.String(300), nullable=True),
            sa.Column("decided_by", sa.BigInteger(), nullable=True),
            sa.Column("decided_at", sa.DateTime(), nullable=True),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )
        op.create_index("ix_access_requests_class_id", "access_requests", ["class_id"])
        op.create_index("ix_access_requests_telegram_id", "access_requests", ["telegram_id"])
        op.create_index("ix_access_request_lookup", "access_requests", ["class_id", "status"])

    if _has_table(inspector, "audit_log"):
        op.create_table(
            "audit_log",
            sa.Column("id", sa.Integer(), primary_key=True),
            sa.Column(
                "class_id",
                sa.Integer(),
                sa.ForeignKey("classes.id", ondelete="CASCADE"),
                nullable=False,
            ),
            sa.Column("telegram_id", sa.BigInteger(), nullable=True),
            sa.Column("action", sa.String(64), nullable=False),
            sa.Column("summary", sa.String(500), nullable=False),
            sa.Column("created_at", sa.DateTime(), server_default=sa.func.now()),
        )
        op.create_index("ix_audit_log_class_id", "audit_log", ["class_id"])
        op.create_index("ix_audit_lookup", "audit_log", ["class_id", "created_at"])


def downgrade() -> None:
    for table in ("audit_log", "access_requests", "reminder_settings", "homework_done",
                  "personal_tasks"):
        op.drop_table(table)
    with op.batch_alter_table("device_tokens") as batch:
        batch.drop_index("ix_device_tokens_link_code")
        batch.drop_index("ix_device_tokens_telegram_id")
        batch.drop_column("linked_at")
        batch.drop_column("link_code")
        batch.drop_column("telegram_id")
    with op.batch_alter_table("classes") as batch:
        batch.drop_index("ix_classes_calendar_token")
        batch.drop_column("calendar_token")
