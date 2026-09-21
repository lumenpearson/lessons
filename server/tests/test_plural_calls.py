"""``plural()`` already carries the number, and every caller has to know it.

``plural(10, 'минута', 'минуты', 'минут')`` answers «10 минут», not «минут».
Written into an f-string beside the count it was built from, it prints «10 10
минут» — and the message still contains «10 минут», so a substring assertion
about it passes. That is not hypothetical: three call sites shipped this, and
the test over one of them was green throughout.

This reads the source rather than the output because the output cannot be
enumerated. Every renderer under ``app/bot/`` would have to be called with a
count in each of the three Russian forms to find the next one, and the next one
will be in a branch nobody thought to build a fixture for. The slip has exactly
one shape in the source, and one shape is what a parser can be certain about.
"""

from __future__ import annotations

import ast
from pathlib import Path

BOT = Path(__file__).resolve().parents[1] / "app" / "bot"


def _modules() -> list[Path]:
    return sorted(BOT.rglob("*.py"))


def _number_before(node: ast.JoinedStr, call: ast.FormattedValue) -> str | None:
    """The bare `{count}` that immediately precedes a `{plural(count, …)}`.

    Returns the name it interpolates, or None when the piece in front is not a
    lone interpolation — a literal, a nested call, or nothing at all.
    """
    pieces = node.values
    index = pieces.index(call)
    if index == 0:
        return None
    before = pieces[index - 1]
    # «{n} » — a constant of nothing but spacing, then the interpolation before it.
    if isinstance(before, ast.Constant) and isinstance(before.value, str):
        if before.value.strip() != "":
            return None
        if index < 2:
            return None
        before = pieces[index - 2]
    if not isinstance(before, ast.FormattedValue):
        return None
    inner = before.value
    return inner.id if isinstance(inner, ast.Name) else None


def _first_argument(call: ast.Call) -> str | None:
    if not call.args:
        return None
    first = call.args[0]
    return first.id if isinstance(first, ast.Name) else None


def test_no_renderer_prints_the_count_twice():
    doubled: list[str] = []
    for path in _modules():
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if not isinstance(node, ast.JoinedStr):
                continue
            for piece in node.values:
                if not isinstance(piece, ast.FormattedValue):
                    continue
                call = piece.value
                if not isinstance(call, ast.Call):
                    continue
                name = call.func
                if not (isinstance(name, ast.Name) and name.id == "plural"):
                    continue
                counted = _first_argument(call)
                if counted is None:
                    continue
                if _number_before(node, piece) == counted:
                    doubled.append(
                        f"{path.name}:{piece.lineno} — "
                        f"«{{{counted}}} {{plural({counted}, …)}}»"
                    )
    assert doubled == []


def test_the_check_recognises_the_slip_it_is_written_for():
    """Held here rather than trusted: the parser above has three ways to miss.

    `{n} {plural(n, …)}`, `{n}{plural(n, …)}` and the same pair with the
    spacing carried by the literal between them are one mistake with three
    spellings, and a check that catches only the first is worth nothing.
    """
    for source in (
        'f"{count} {plural(count, \'день\', \'дня\', \'дней\')}"',
        'f"{count}{plural(count, \'день\', \'дня\', \'дней\')}"',
        'f"осталось {count} {plural(count, \'день\', \'дня\', \'дней\')}."',
    ):
        tree = ast.parse(source)
        joined = next(n for n in ast.walk(tree) if isinstance(n, ast.JoinedStr))
        call = next(
            piece
            for piece in joined.values
            if isinstance(piece, ast.FormattedValue) and isinstance(piece.value, ast.Call)
        )
        assert _number_before(joined, call) == "count", source

    # And it does not cry over the correct spelling, nor over a different count.
    for source in (
        'f"осталось {plural(count, \'день\', \'дня\', \'дней\')}."',
        'f"{total} из {plural(count, \'дня\', \'дней\', \'дней\')}"',
    ):
        tree = ast.parse(source)
        joined = next(n for n in ast.walk(tree) if isinstance(n, ast.JoinedStr))
        call = next(
            piece
            for piece in joined.values
            if isinstance(piece, ast.FormattedValue) and isinstance(piece.value, ast.Call)
        )
        assert _number_before(joined, call) != "count", source
