"""That an enum column is as wide on Postgres as the model needs it to be.

``SAEnum(SomeStrEnum)`` stores the member **NAME**, and with
``native_enum=False`` the column is a ``VARCHAR`` exactly as wide as the
longest of those names. SQLAlchemy recomputes that width from the enum class,
so adding a member widens the model silently — and the live column does not
move, because a database does not grow on its own.

Nothing in the suite could see that. The test database is built by
``create_all`` and **SQLite ignores ``VARCHAR`` lengths entirely**, so a
member one character too long for the live column passes ruff, mypy and every
other test, and fails for the first time on production Postgres with a
``StringDataRightTruncation`` out of the driver, at the moment somebody marks a
day. That is the ``SELF_STUDY`` story: revision ``0014`` widened
``day_overrides.kind`` from nine to ten and closed that one instance without
closing the class.

The widths below are therefore pinned by hand, the same shape
``app.db.EXPECTED_REVISION`` already uses and for the same reason: what the
migration chain has actually produced cannot be read from here, so it is
written down, and something has to notice when it stops being true. Adding an
enum member that does not fit stops here, and the answer is a revision that
widens the column — see ``0014`` for the shape.
"""

from __future__ import annotations

import sqlalchemy as sa

import app.fsm_storage  # noqa: F401  (registers its table on the same metadata)
import app.models  # noqa: F401
from app.db import Base

#: ``(table, column) -> the width the applied migration chain left on Postgres``.
#:
#: ``0001`` created everything the model had then; ``0003`` gave the three
#: ``role`` columns an explicit ``length=6``; ``0008`` added ``term_kind`` and
#: ``terms.kind`` at 8; ``0010`` added ``join_mode`` at 6; ``0014`` widened
#: ``day_overrides.kind`` to 10.
LIVE_WIDTHS: dict[tuple[str, str], int] = {
    ("access_requests", "requested_role"): 6,
    ("bot_users", "role"): 6,
    ("classes", "join_mode"): 6,
    ("classes", "term_kind"): 8,
    ("day_events", "kind"): 7,
    ("day_overrides", "kind"): 10,
    ("lesson_overrides", "action"): 7,
    ("phone_invites", "role"): 6,
    ("terms", "kind"): 8,
    ("timetable_entries", "parity"): 4,
}


def _enum_columns() -> dict[tuple[str, str], sa.Enum]:
    return {
        (table.name, column.name): column.type
        for table in Base.metadata.sorted_tables
        for column in table.columns
        if isinstance(column.type, sa.Enum)
    }


def test_no_enum_member_is_wider_than_the_column_holding_it():
    """The one assertion this file is for.

    Red means a member was added to an enum whose name does not fit the live
    column. It is a migration, not a line — and until that migration is applied
    the write raises where somebody pressed a button.
    """
    for key, kind in sorted(_enum_columns().items()):
        assert kind.length == LIVE_WIDTHS[key], (
            f"{key[0]}.{key[1]} is VARCHAR({LIVE_WIDTHS[key]}) on Postgres and the model "
            f"now needs {kind.length} for «{max(kind.enums, key=len)}»"
        )


def test_every_enum_column_in_the_schema_is_in_the_table_above():
    """A pinned table only guards what is in it.

    A new table with an enum column would otherwise be unguarded from the day
    it lands, which is exactly the state this file was written to end — and
    SQLite would go on saying nothing about it.
    """
    assert sorted(_enum_columns()) == sorted(LIVE_WIDTHS)


def test_no_enum_column_declares_a_width_of_its_own():
    """SQLAlchemy computes the width from the member names, and every column
    here lets it.

    A hand-written ``length=`` on an ``SAEnum`` in ``models.py`` would freeze
    that width while members went on being added, which the assertion above
    cannot see: it compares the model against the database, and both would be
    wrong together.
    """
    for (table, column), kind in sorted(_enum_columns().items()):
        assert kind.length == len(max(kind.enums, key=len)), (
            f"{table}.{column} declares a width rather than taking it from the enum"
        )
