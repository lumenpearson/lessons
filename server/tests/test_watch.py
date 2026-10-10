"""The class-changed bus (``app/watch.py``): what wakes a class's watchers, and when.

Asked of real sessions over the suite's SQLite, with the listener attached for
the test alone: the suite runs with streaming off, as every deployment but a
streaming host does (``docs/specs/2026-10-05-server-v2-design.md``,
decision 13). A test reads ``changes`` before and after, never a revision's
text: a revision is opaque to every client, and to these tests too.
"""

from __future__ import annotations

import ast
import re
from datetime import date, time
from pathlib import Path
from typing import Any

import pytest
from sqlalchemy import select
from sqlalchemy import update as sa_update

from app import watch
from app.bot.handlers.timetable import timetable_apply
from app.db import Base, SessionLocal
from app.models import (
    AuditEntry,
    BellPeriod,
    BellSchedule,
    BotUser,
    DayEvent,
    DayKind,
    DayOverride,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    PersonalTask,
    ReminderSettings,
    Role,
    SchoolClass,
    Subject,
    Term,
    TermKind,
    TimetableEntry,
    WeekParity,
)
from app.services import calendar, structure, timetable_edit

APP = Path(__file__).resolve().parents[1] / "app"


@pytest.fixture
def bus():
    """The listener, attached for one test."""
    already = watch.listening()
    watch.attach()
    yield watch
    if not already:
        watch.detach()


def _homework(class_id: int, text: str = "№ 1–5") -> Homework:
    return Homework(
        class_id=class_id, due_date=date(2026, 9, 15), subject_name="Алгебра", text=text
    )


def _row(table: str, class_id: int) -> Any:
    """A new row of ``table`` in ``class_id``, as little as the table needs."""
    return {
        "bot_users": lambda: BotUser(telegram_id=3001, class_id=class_id, role=Role.EDITOR),
        "bell_schedules": lambda: BellSchedule(class_id=class_id, name="Сокращённое"),
        "subjects": lambda: Subject(class_id=class_id, name="Химия"),
        "timetable_entries": lambda: TimetableEntry(
            class_id=class_id, weekday=2, index=1, subject_name="Химия", parity=WeekParity.ANY
        ),
        "terms": lambda: Term(
            class_id=class_id,
            year=2026,
            kind=TermKind.QUARTER,
            index=1,
            starts_on=date(2026, 9, 1),
            ends_on=date(2026, 10, 26),
        ),
        "day_overrides": lambda: DayOverride(
            class_id=class_id, date=date(2026, 11, 4), kind=DayKind.HOLIDAY
        ),
        "lesson_overrides": lambda: LessonOverride(
            class_id=class_id, date=date(2026, 9, 14), index=1, action=OverrideAction.CANCEL
        ),
        "day_events": lambda: DayEvent(
            class_id=class_id,
            date=date(2026, 9, 15),
            starts_at=time(15),
            ends_at=time(16),
            title="Родительское собрание",
            kind=EventKind.MEETING,
        ),
        "homework": lambda: _homework(class_id),
    }[table]()


#: The window tables a plain row of which is a row of its class; the class
#: itself and a bell period are asked on their own below.
ROWS = sorted(watch.WINDOW_TABLES - {"classes", "bell_periods"})


def test_every_window_table_is_asked_here() -> None:
    assert watch.WINDOW_TABLES == {
        "classes",
        "bot_users",
        "bell_schedules",
        "bell_periods",
        "subjects",
        "timetable_entries",
        "terms",
        "day_overrides",
        "lesson_overrides",
        "day_events",
        "homework",
    }
    assert {*ROWS, "classes", "bell_periods"} == watch.WINDOW_TABLES
    # A phone's own row is left out on purpose: every call writes its
    # last_seen_at, which would wake the whole class each quarter of an hour
    # per phone (Ruling 149).
    assert "device_tokens" not in watch.WINDOW_TABLES


@pytest.mark.parametrize("table", ROWS)
async def test_a_committed_insert_into_a_window_table_wakes_its_class(
    bus, session, school_class, table
) -> None:
    start = bus.changes(school_class.id)
    session.add(_row(table, school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_an_update_and_a_delete_wake_it_and_a_write_that_changes_nothing_does_not(
    bus, session, school_class
) -> None:
    entry = await session.scalar(
        select(TimetableEntry).where(
            TimetableEntry.class_id == school_class.id, TimetableEntry.index == 1
        )
    )
    start = bus.changes(school_class.id)
    entry.room = "101"
    await session.commit()
    entry.room = "101"
    await session.commit()
    await session.delete(entry)
    await session.commit()
    assert bus.changes(school_class.id) == start + 2


async def test_the_class_row_wakes_its_own_class(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    school_class.name = "9Б"
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_a_bell_period_wakes_the_class_of_its_schedule(bus, school_class) -> None:
    """In a session that has not read the schedule: the bus reads it to find
    the class, and a period added by id, one changed and one deleted each
    wake it once."""
    class_id, schedule_id = school_class.id, school_class.bell_schedule_id
    start = bus.changes(class_id)
    async with SessionLocal() as fresh:
        period = await fresh.scalar(
            select(BellPeriod).where(BellPeriod.schedule_id == schedule_id, BellPeriod.index == 1)
        )
        period.ends_at = time(9, 10)
        await fresh.commit()
        fresh.add(
            BellPeriod(schedule_id=schedule_id, index=9, starts_at=time(16), ends_at=time(16, 45))
        )
        await fresh.commit()
        await fresh.delete(period)
        await fresh.commit()
    assert bus.changes(class_id) == start + 3


async def test_a_bell_period_added_through_its_schedule_wakes_the_class(
    bus, session, school_class
) -> None:
    start = bus.changes(school_class.id)
    schedule = BellSchedule(class_id=school_class.id, name="Субботнее")
    schedule.periods.append(BellPeriod(index=1, starts_at=time(9), ends_at=time(9, 40)))
    session.add(schedule)
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


async def test_what_changes_no_window_wakes_nobody(bus, session, school_class) -> None:
    start = bus.changes(school_class.id)
    session.add_all(
        [
            PersonalTask(class_id=school_class.id, telegram_id=2001, title="Купить тетрадь"),
            ReminderSettings(class_id=school_class.id, telegram_id=2001),
            AuditEntry(class_id=school_class.id, telegram_id=2001, action="x", summary="y"),
        ]
    )
    await session.commit()
    assert bus.changes(school_class.id) == start


async def test_a_rollback_forgets_what_the_transaction_collected(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    session.add(_homework(class_id))
    await session.flush()
    await session.rollback()
    session.add(PersonalTask(class_id=class_id, telegram_id=2001, title="Купить тетрадь"))
    await session.commit()
    assert bus.changes(class_id) == start


async def test_a_savepoint_released_publishes_nothing_until_the_commit(
    bus, session, school_class
) -> None:
    """SQLAlchemy fires ``after_commit`` for a savepoint's release as well. A
    watcher woken there would read the window before the transaction around
    it commits, find nothing new, and never be woken for the change again:
    ``homework.create`` writes inside exactly such a savepoint."""
    class_id = school_class.id
    start = bus.changes(class_id)
    with bus.watching(class_id) as woken:
        async with session.begin_nested():
            session.add(_homework(class_id))
        assert (bus.changes(class_id), woken.is_set()) == (start, False)
        await session.commit()
        assert (bus.changes(class_id), woken.is_set()) == (start + 1, True)


async def test_a_savepoint_rolled_back_never_hides_what_the_commit_holds(
    bus, session, school_class
) -> None:
    class_id = school_class.id
    start = bus.changes(class_id)
    session.add(_row("day_events", class_id))
    nested = await session.begin_nested()  # flushes the event into the outer transaction
    session.add(_homework(class_id))
    await session.flush()
    await nested.rollback()
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_a_touch_goes_out_with_the_commit_and_is_forgotten_with_a_rollback(
    bus, session, school_class
) -> None:
    """What a bulk statement does: the unit of work never sees it."""
    class_id = school_class.id
    statement = (
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    start = bus.changes(class_id)
    await session.execute(statement)
    await session.rollback()
    assert bus.changes(class_id) == start, "an untouched bulk write is invisible to the bus"
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.rollback()
    await session.commit()
    assert bus.changes(class_id) == start
    await session.execute(statement)
    bus.touch(session, class_id)
    await session.commit()
    assert bus.changes(class_id) == start + 1


async def test_without_the_listener_nothing_is_collected_or_published(
    session, school_class
) -> None:
    assert not watch.listening(), "the suite runs with streaming off"
    class_id = school_class.id
    start = watch.changes(class_id)
    await session.execute(
        sa_update(TimetableEntry).where(TimetableEntry.class_id == class_id).values(room="7")
    )
    watch.touch(session, class_id)
    assert not session.info
    session.add(_homework(class_id))
    await session.commit()
    assert watch.changes(class_id) == start


async def test_a_watcher_is_woken_by_the_commit_never_by_the_flush_nor_by_another_class(
    bus, session, school_class
) -> None:
    other = SchoolClass(name="5А", join_code="OTHER5")
    session.add(other)
    await session.commit()
    class_id, other_id = school_class.id, other.id
    with bus.watching(class_id) as woken, bus.watching(other_id) as elsewhere:
        session.add(_homework(class_id))
        await session.flush()
        assert not woken.is_set()
        await session.commit()
        assert woken.is_set()
        assert not elsewhere.is_set()
        assert (bus.watchers(class_id), bus.watchers(other_id)) == (1, 1)
    assert (bus.watchers(class_id), bus.watchers(other_id)) == (0, 0)


def test_a_revision_moves_with_every_change_and_names_this_process(bus, monkeypatch) -> None:
    """A restarted host counts from nothing again; its name is what keeps its
    first revision from equalling one a phone kept from before the restart."""
    nobody = 987654
    first = bus.revision(nobody)
    bus.publish([nobody])
    second = bus.revision(nobody)
    assert first != second
    assert bus.changed_at(nobody) is not None
    monkeypatch.setattr(watch, "BOOT", "another-start")
    assert bus.revision(nobody) not in (first, second)


async def test_attaching_twice_hears_each_change_once(bus, session, school_class) -> None:
    bus.attach()
    start = bus.changes(school_class.id)
    session.add(_homework(school_class.id))
    await session.commit()
    assert bus.changes(school_class.id) == start + 1


# ---- every bulk write on a window table touches the bus ---------------------

#: The statements sqlalchemy builds that never pass through the unit of work,
#: as functions and as the methods of a model's ``__table__``.
_BULK = {"update", "delete", "insert"}

#: How raw SQL handed to ``text()`` begins when it writes: it never passes
#: through the unit of work either.
_DML = re.compile(r"\s*(?:update|delete|insert|replace|merge)\b", re.IGNORECASE)
#: The table such SQL writes, where its first words name it.
_DML_TABLE = re.compile(
    r"\s*(?:update(?:\s+or\s+\w+)?|delete\s+from"
    r"|(?:insert(?:\s+or\s+\w+)?|replace|merge)\s+into)\s+[\"`\[]?(\w+)",
    re.IGNORECASE,
)


def _tables() -> dict[str, str]:
    """Every mapped class's name and its table, the FSM storage's included."""
    from app import fsm_storage, models  # noqa: F401 - both register mappers

    return {mapper.class_.__name__: mapper.local_table.name for mapper in Base.registry.mappers}


def _bulk_names(tree: ast.Module) -> tuple[set[str], set[str], set[str]]:
    """The names a module calls sqlalchemy's bulk statements by, however it
    imports them, the names it calls ``text`` by, and the names it calls
    sqlalchemy itself by."""
    functions: set[str] = set()
    texts: set[str] = set()
    modules: set[str] = set()
    for node in ast.walk(tree):
        if isinstance(node, ast.ImportFrom) and (node.module or "").split(".")[0] == "sqlalchemy":
            for alias in node.names:
                if alias.name in _BULK:
                    functions.add(alias.asname or alias.name)
                elif alias.name == "text":
                    texts.add(alias.asname or alias.name)
        elif isinstance(node, ast.Import):
            modules.update(
                alias.asname or alias.name
                for alias in node.names
                if alias.name.split(".")[0] == "sqlalchemy"
            )
    return functions, texts, modules


def _on_table(node: ast.expr) -> bool:
    return isinstance(node, ast.Attribute) and node.attr == "__table__"


def _leading_sql(call: ast.Call) -> str | None:
    """The text a call's first argument starts with, where it is written out."""
    first = call.args[0] if call.args else None
    if isinstance(first, ast.JoinedStr) and first.values:
        first = first.values[0]
    if isinstance(first, ast.Constant) and isinstance(first.value, str):
        return first.value
    return None


def _writes(call: ast.Call) -> bool:
    """Whether a ``text()`` writes: its SQL begins with a write, or the walk
    cannot read how it begins and takes it for one."""
    sql = _leading_sql(call)
    return sql is None or _DML.match(sql) is not None


def _is_bulk(call: ast.Call, functions: set[str], texts: set[str], modules: set[str]) -> bool:
    func = call.func
    if isinstance(func, ast.Name):
        if func.id in texts:
            return _writes(call)
        return func.id in functions
    if not isinstance(func, ast.Attribute):
        return False
    if func.attr in _BULK and _on_table(func.value):
        # `Model.__table__.delete()`: Core's spelling of the same statement.
        return True
    root = func.value
    while isinstance(root, ast.Attribute):
        root = root.value
    if not (isinstance(root, ast.Name) and root.id in modules):
        return False
    if func.attr == "text":
        return _writes(call)
    return func.attr in _BULK


def _on_a_window_table(call: ast.Call, tables: dict[str, str]) -> bool:
    """A statement on a model the walk can name is judged by its table, and
    raw SQL by the table its first words name; any other — a loop variable,
    an attribute, SQL the walk cannot read — is taken to be a window table."""
    func = call.func
    target = func.value if isinstance(func, ast.Attribute) and _on_table(func.value) else None
    if target is None and call.args:
        target = call.args[0]
    if isinstance(target, ast.Attribute) and _on_table(target):
        target = target.value
    if isinstance(target, ast.Name) and target.id in tables:
        return tables[target.id] in watch.WINDOW_TABLES
    sql = _leading_sql(call)
    named = _DML_TABLE.match(sql) if sql is not None else None
    if named is not None:
        return named.group(1).lower() in watch.WINDOW_TABLES
    return True


def _touches(scope: ast.AST) -> bool:
    return any(
        isinstance(node, ast.Call)
        and isinstance(node.func, ast.Attribute)
        and node.func.attr == "touch"
        and isinstance(node.func.value, ast.Name)
        and node.func.value.id == "watch"
        for node in ast.walk(scope)
    )


def bulk_writes(source: str, tables: dict[str, str]) -> list[tuple[str, int, bool]]:
    """Each bulk statement on a window table in ``source``: the top-level
    function or method it is built in, its line, and whether that function
    touches the bus. A helper defined inside the function is the function's."""
    tree = ast.parse(source)
    functions, texts, modules = _bulk_names(tree)
    scopes: list[tuple[str, ast.AST]] = []
    for node in tree.body:
        if isinstance(node, ast.FunctionDef | ast.AsyncFunctionDef):
            scopes.append((node.name, node))
        elif isinstance(node, ast.ClassDef):
            scopes += [
                (f"{node.name}.{item.name}", item)
                for item in node.body
                if isinstance(item, ast.FunctionDef | ast.AsyncFunctionDef)
            ]
        else:
            scopes.append(("<module>", node))
    return [
        (name, call.lineno, _touches(scope))
        for name, scope in scopes
        for call in ast.walk(scope)
        if isinstance(call, ast.Call)
        and _is_bulk(call, functions, texts, modules)
        and _on_a_window_table(call, tables)
    ]


def test_every_bulk_write_on_a_window_table_touches_the_bus() -> None:
    """The design asks this of ``services/``; it is asked of all of ``app/``,
    because the bus hears every shell, and the bot's «-» on a weekday was a
    bare delete in a handler until this stage moved it into the service."""
    tables = _tables()
    found = {
        path.relative_to(APP.parent).as_posix(): bulk_writes(path.read_text("utf-8"), tables)
        for path in sorted(APP.rglob("*.py"))
        if "contract" not in path.relative_to(APP).parts
    }
    untouched = [
        f"{name} {scope}:{line}"
        for name, writes in found.items()
        for scope, line, touched in writes
        if not touched
    ]
    assert untouched == [], (
        "a bulk statement on a window table that the bus never hears of; call "
        "watch.touch(session, class_id) in the same function: " + ", ".join(untouched)
    )
    # And the walk is not vacuous: it sees the writes this stage touched.
    assert {"app/services/structure.py", "app/services/timetable_edit.py"} <= {
        name for name, writes in found.items() if writes
    }


def test_the_walk_sees_an_untouched_bulk_write_however_it_is_spelled() -> None:
    """Including Core's ``Model.__table__`` methods and raw SQL through
    ``text()``, which pass the unit of work by just as much; a ``text()`` that
    reads, or writes a table no window is read from, is no window write."""
    slips = """
from sqlalchemy import delete as sa_delete
from sqlalchemy import text
from sqlalchemy import text as sql
from sqlalchemy import update
import sqlalchemy as sa


async def direct(session, class_id):
    await session.execute(sa_delete(Homework).where(Homework.class_id == class_id))


async def looped(session, class_id):
    for model in (Homework, DayEvent):
        await session.execute(update(model).values(subject_name="x"))


class Holder:
    async def method(self, session):
        await session.execute(sa.delete(TimetableEntry))


async def core(session):
    await session.execute(Homework.__table__.delete())


async def core_function(session):
    await session.execute(sa.insert(DayEvent.__table__))


async def raw(session, class_id):
    await session.execute(text("  UPDATE homework SET text = 'x' WHERE class_id = :c"))


async def raw_aliased(session, table):
    await session.execute(sql(f"DELETE FROM {table}"))


async def raw_unread(session, statement):
    await session.execute(sa.text(statement))


async def elsewhere(session):
    await session.execute(sa_delete(PersonalTask))
    await session.execute(PersonalTask.__table__.update())
    await session.execute(text("delete from join_attempts"))
    await session.execute(sql("SELECT 1 FROM homework"))


async def kept(session, class_id):
    await session.execute(sa_delete(Homework))
    watch.touch(session, class_id)
"""
    writes = bulk_writes(slips, _tables())
    assert [(scope, touched) for scope, _line, touched in writes] == [
        ("direct", False),
        ("looped", False),
        ("Holder.method", False),
        ("core", False),
        ("core_function", False),
        ("raw", False),
        ("raw_aliased", False),
        ("raw_unread", False),
        ("kept", True),
    ]


@pytest.mark.parametrize(
    "write",
    ["empty_a_weekday", "remove_a_lesson", "move_a_lesson", "mint_the_feed_secret", "bot_dash"],
)
async def test_a_write_made_of_bulk_statements_alone_wakes_the_class(
    bus, session, school_class, FakeState, FakeMessage, write
) -> None:
    """Each path here writes a window table through bulk statements and
    nothing else, so only its touch can wake anybody."""
    class_id = school_class.id
    start = bus.changes(class_id)
    if write == "empty_a_weekday":
        await structure.apply_timetable(session, school_class, {1: []}, [])
    elif write == "remove_a_lesson":
        assert await timetable_edit.remove_lesson(session, class_id, 1, 3) == 1
    elif write == "move_a_lesson":
        assert await timetable_edit.move_lesson(session, class_id, 1, 1, up=False) == 2
    elif write == "mint_the_feed_secret":
        assert await calendar.ensure_calendar_token(session, school_class)
    else:
        message = FakeMessage(text="-")
        state = FakeState(data={"weekday": 1})
        await timetable_apply(message, state, session, school_class, Role.ADMIN)
        assert "очищено" in message.last
    await session.commit()
    assert bus.changes(class_id) == start + 1
