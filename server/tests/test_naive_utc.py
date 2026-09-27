"""One way of taking «now» in naive UTC, and it is not ``datetime.utcnow()``.

Every timestamp column here is a naive ``DateTime`` holding UTC, and the server
builds that value as ``datetime.now(UTC).replace(tzinfo=None)`` — in
``security``, ``linking``, ``tasks``, the diary services and the cron. Six
places used ``datetime.utcnow()`` instead: the FSM storage four times, the
phone-invite redemption and the demo seed. Same value, but the function is
deprecated from Python 3.12, which is what CI and Vercel run, so each call
warned and each is scheduled to stop working (#197).

The check reads the source rather than the warnings, because a warning is only
raised on a path a test happens to drive, and the four in the FSM storage sit
on paths most tests never reach. It looks for the *attribute* on ``datetime``
— the module or the class — rather than for the text ``utcnow(``: three
services export a helper of that name (``diary_link.utcnow`` and its two
siblings), which is the idiom rather than the slip, and the modules that call
them belong to other people.
"""

from __future__ import annotations

import ast
from pathlib import Path

SERVER = Path(__file__).resolve().parents[1]

#: What ships, and what a person runs against a real database.
ROOTS = ("app", "scripts", "migrations")

#: Both are deprecated from 3.12 for the same reason; ``utcfromtimestamp`` is
#: the same slip made from a number instead of from the clock.
DEPRECATED = {"utcnow", "utcfromtimestamp"}


def _is_datetime(node: ast.expr) -> bool:
    """``datetime`` the module or the class, and ``datetime.datetime``."""
    if isinstance(node, ast.Name):
        return node.id == "datetime"
    return isinstance(node, ast.Attribute) and node.attr == "datetime"


def _offences(tree: ast.AST) -> list[tuple[int, str]]:
    return [
        (node.lineno, node.attr)
        for node in ast.walk(tree)
        if isinstance(node, ast.Attribute)
        and node.attr in DEPRECATED
        and _is_datetime(node.value)
    ]


def test_nothing_takes_the_time_through_the_deprecated_constructors():
    found: list[str] = []
    for root in ROOTS:
        for path in sorted((SERVER / root).rglob("*.py")):
            tree = ast.parse(path.read_text(encoding="utf-8"))
            found += [
                f"{path.relative_to(SERVER).as_posix()}:{line} datetime.{name}"
                for line, name in _offences(tree)
            ]
    assert found == [], (
        "naive UTC is `datetime.now(UTC).replace(tzinfo=None)` here; "
        "`datetime.utcnow()` is deprecated from Python 3.12: " + ", ".join(found)
    )


def test_the_check_recognises_the_slip_and_not_the_idiom():
    """Held here rather than trusted: a matcher narrower than its sentence is
    how a guard reports green over the thing it was written for."""
    for source in (
        "datetime.utcnow()",
        "datetime.datetime.utcnow()",
        "stamp = datetime.utcnow() - timedelta(days=2)",
        "Column(DateTime, default=datetime.utcnow)",
        "datetime.utcfromtimestamp(0)",
    ):
        assert _offences(ast.parse(source)), source

    for source in (
        "datetime.now(UTC).replace(tzinfo=None)",
        "diary_link.utcnow()",
        "utcnow()",
        "device_invites.utcnow() - timedelta(seconds=1)",
    ):
        assert not _offences(ast.parse(source)), source
