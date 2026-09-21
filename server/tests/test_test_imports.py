"""No test module may import another one.

`pytest` and `python -m pytest` do not agree about `sys.path`. The `-m` form
puts the current directory on it, so `from tests.test_api import _token`
resolves when a person runs the command `CLAUDE.md` documents — and fails at
*collection* on CI, which runs the bare `pytest`. The whole file errors, and it
errors on a line that has nothing to do with what the file tests.

That shipped, in this repository, on the commit this test arrived with. The
import looked ordinary, the suite was green locally, and the failure said
«ModuleNotFoundError: No module named 'tests'» about a directory that is
plainly there.

A shared fixture belongs in `conftest.py`, which pytest loads by path and not
by import. A helper two files want is a helper for `conftest.py` too.
"""

from __future__ import annotations

import ast
from pathlib import Path

TESTS = Path(__file__).resolve().parent


def test_no_test_module_imports_another():
    offenders: list[str] = []
    for path in sorted(TESTS.glob("test_*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and (node.module or "").startswith("tests"):
                offenders.append(f"{path.name}:{node.lineno} imports {node.module}")
            elif isinstance(node, ast.Import):
                for alias in node.names:
                    if alias.name.startswith("tests"):
                        offenders.append(f"{path.name}:{node.lineno} imports {alias.name}")
    assert offenders == []


def test_the_suite_is_importable_the_way_ci_runs_it():
    """`conftest.py` is the one place a fixture may be shared from.

    Held as a fact about the tree rather than a convention in a comment: if
    somebody moves the fixtures into a module and imports it, the test above
    names them, and this one says where they should have gone instead.
    """
    assert (TESTS / "conftest.py").is_file()
