"""The class-changed bus: which classes a committed transaction changed, told to whoever watches.

``WatchClass`` (``rpc/watch.py``) streams «this class changed» from the host
target (``docs/specs/2026-10-05-server-v2-design.md``, decision 13), and it has
to hear every change, whichever shell made it: v1, v2 and the bot all write
through this process's sessions, so the bus listens to the session itself.

- **ORM writes** are collected before each flush, by the class they belong to:
  a row's ``class_id``, a bell period through its schedule, the class row
  through its ``id``.
- **Bulk statements** (``update``, ``delete`` and ``insert`` executed directly)
  never pass through the unit of work, so each one on a window table calls
  :func:`touch` with the class it already has at hand.
  ``tests/test_watch.py`` finds every such statement under ``app/`` and fails
  on a function that makes one and does not touch.
- What a transaction collected is published once its **outermost** commit is
  done, and forgotten when that transaction ends any other way. SQLAlchemy
  fires ``after_commit`` for a savepoint's release too, and a release commits
  nothing a watcher's next read could see — so a release publishes nothing,
  and the class goes out with the commit that makes it visible. A savepoint
  rolled back keeps what it collected: that can wake a watcher for nothing,
  which costs it a sync answered ``304``, and it can never fail to wake one.

Only the tables a schedule window is read from wake anybody
(:data:`WINDOW_TABLES`): a pupil's task, a tick, a diary session, a reminder
setting or an audit line changes no window, and a phone's own row is left out
on purpose (the constant says why).

**One process, one event loop.** What a process writes is all it can hear,
which is why the host is a complete deployment rather than a sidecar of
Vercel (the design's question 2), and why ``app.host`` runs pyvoy with one
worker thread: a watcher's event is set from the loop that committed, and the
engine's pool is that loop's anyway. A script, a migration or a second
instance writing the same database wakes nobody here.

**Attached only where streaming is on** (``app.main``, ``Settings.
streaming_enabled``), so Vercel, the suite and a run without
``LESSONS_STREAMING`` pay one ``if`` in :func:`touch` and nothing else.
"""

from __future__ import annotations

import asyncio
import secrets
from collections.abc import Iterable, Iterator
from contextlib import contextmanager
from datetime import UTC, datetime
from typing import Any

from sqlalchemy import event
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm import Session, SessionTransaction

from app.models import BellPeriod, BellSchedule, SchoolClass

#: The tables a schedule window is read from: the class itself, its bells,
#: its weekly template and its subjects, its terms, every dated thing laid
#: over them, and its members, whose role every window carries in its
#: ``DeviceAccess`` («a role changed in the bot is a new tag»,
#: ``services/window.strong_etag``). A write anywhere else wakes nobody.
#: ``device_tokens`` is not here although a phone's link is in its window too:
#: every call writes ``last_seen_at`` there, which would wake the whole class
#: each quarter of an hour per phone.
WINDOW_TABLES = frozenset(
    {
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
)

#: This process's name, in every revision it hands out. The counts below start
#: from nothing on every start, so without it a restarted host would answer a
#: phone the very revision that phone kept from before the restart, and the
#: change made in between would never be fetched.
BOOT = secrets.token_hex(6)

#: Where a session keeps the classes its transaction changed.
_KEY = "lessons.watch.changed"

_listening = False
_changes: dict[int, int] = {}
_changed_at: dict[int, datetime] = {}
_watchers: dict[int, set[asyncio.Event]] = {}


def listening() -> bool:
    """Whether this process hears its own writes: streaming is on here."""
    return _listening


def changes(class_id: int) -> int:
    """How many committed transactions have changed ``class_id`` since this process started."""
    return _changes.get(class_id, 0)


def revision(class_id: int) -> str:
    """What ``WatchClass`` sends: opaque to a client, meant to be compared
    only against an earlier revision of the same class, in this process —
    two different classes can share one string."""
    return f"{BOOT}.{changes(class_id)}"


def changed_at(class_id: int) -> datetime | None:
    """When this process last saw ``class_id`` change; ``None`` when it has not."""
    return _changed_at.get(class_id)


def watchers(class_id: int) -> int:
    """How many streams are watching ``class_id`` now."""
    return len(_watchers.get(class_id, ()))


def touch(session: AsyncSession | Session, class_id: int) -> None:
    """Say that ``class_id`` changes in ``session``'s transaction.

    For a bulk statement, which the unit of work never sees. Published with
    the transaction's outermost commit and forgotten with anything else, like
    what a flush collects; a no-op where streaming is off.
    """
    if not _listening:
        return
    session.info.setdefault(_KEY, set()).add(class_id)


@contextmanager
def watching(class_id: int) -> Iterator[asyncio.Event]:
    """An event, set every time a commit changes ``class_id``, while the block runs.

    The watcher clears it before it reads the revision, so changes made while
    it was busy are one wake rather than several, and none is lost.
    """
    woken = asyncio.Event()
    _watchers.setdefault(class_id, set()).add(woken)
    try:
        yield woken
    finally:
        remaining = _watchers.get(class_id)
        if remaining is not None:
            remaining.discard(woken)
            if not remaining:
                del _watchers[class_id]


def publish(class_ids: Iterable[int]) -> None:
    """Count a change of each class and wake whoever watches it."""
    now = datetime.now(UTC)
    for class_id in class_ids:
        _changes[class_id] = changes(class_id) + 1
        _changed_at[class_id] = now
        for woken in _watchers.get(class_id, ()):
            woken.set()


def _class_of(session: Session, row: Any) -> int | None:
    if isinstance(row, SchoolClass):
        # None for a class being created, whom nobody can be watching yet.
        return row.id
    if isinstance(row, BellPeriod):
        if row.schedule_id is None:
            # Added through the relationship and not flushed yet.
            parent = row.schedule
        else:
            with session.no_autoflush:
                parent = session.get(BellSchedule, row.schedule_id)
        return parent.class_id if parent is not None else None
    return getattr(row, "class_id", None)


def _collect(session: Session, _context: Any, _instances: Any) -> None:
    changed: set[int] = session.info.setdefault(_KEY, set())
    modified = [row for row in session.dirty if session.is_modified(row, include_collections=False)]
    for row in (*session.new, *modified, *session.deleted):
        if getattr(row, "__tablename__", None) not in WINDOW_TABLES:
            continue
        class_id = _class_of(session, row)
        if class_id is not None:
            changed.add(class_id)


def _committed(session: Session) -> None:
    # A savepoint's release fires this too; what it wrote is not readable by
    # anyone else until the transaction around it commits.
    if session.in_nested_transaction():
        return
    changed = session.info.pop(_KEY, None)
    if changed:
        publish(changed)


def _ended(session: Session, transaction: SessionTransaction) -> None:
    if transaction.parent is None:
        session.info.pop(_KEY, None)


_LISTENERS = (
    ("before_flush", _collect),
    ("after_commit", _committed),
    ("after_transaction_end", _ended),
)


def attach() -> None:
    """Hear every session of this process. Idempotent."""
    global _listening
    if _listening:
        return
    for name, listener in _LISTENERS:
        event.listen(Session, name, listener)
    _listening = True


def detach() -> None:
    """Stop hearing them; for the tests that attach."""
    global _listening
    if not _listening:
        return
    for name, listener in _LISTENERS:
        event.remove(Session, name, listener)
    _listening = False
