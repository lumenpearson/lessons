"""No test module may import another one, in any of the spellings.

`pytest` and `python -m pytest` do not agree about `sys.path`. The `-m` form
puts the current directory on it, so `from tests.test_api import _token`
resolves when a person runs the command `CLAUDE.md` documents — and fails at
*collection* on CI, which runs the bare `pytest`. The whole file errors, and it
errors on a line that has nothing to do with what the file tests.

That shipped, in this repository, on the commit this test arrived with. The
import looked ordinary, the suite was green locally, and the failure said
«ModuleNotFoundError: No module named 'tests'» about a directory that is
plainly there.

**And then this test went on saying «no test module may import another one»
while five of them did.** It matched only the `tests.`-prefixed spelling; the
bare `from test_bot_handlers import FakeState` was invisible to it, and six
such lines lived here for months under a guard that reported them green. They
worked, which is what made them invisible: under the default `prepend` import
mode pytest puts *this directory* on `sys.path` before importing a test
module, so a sibling resolves by bare name. Under `--import-mode=importlib`,
which pytest now recommends and which is one line of `pyproject.toml` away,
nothing is added to `sys.path` at all and all six die at collection with the
very message quoted above. The rule was right; the matcher was narrower than
the sentence describing it, which is the same defect as a test asserting a
substring of the line it means to pin.

So the matcher below is written against the *shape* — any import that names a
module of this directory — rather than against one prefix of it, and
`test_the_check_recognises_every_spelling_of_the_slip` holds all of them.

`conftest.py` is the one place a helper may be shared from, and it is shared as
a **fixture**: pytest loads `conftest.py` by path rather than by import, so
`import conftest` is refused here too — it resolves under `prepend` and raises
under `importlib`, which is exactly the trade the sibling import was making.
"""

from __future__ import annotations

import ast
from pathlib import Path

TESTS = Path(__file__).resolve().parent


def _names_a_module_of_this_directory(dotted: str) -> bool:
    """Whether `dotted` reaches for a module that lives beside this file.

    Every spelling is the same question about one of the dotted parts: is it
    `conftest`, is it the `tests` package itself, or is it a `test_…` module.
    `app.services.diary` and `sqlalchemy.orm` answer no to all three, which is
    what keeps this from firing on the imports every file here does want.
    """
    parts = dotted.split(".")
    return any(part == "conftest" or part == "tests" or part.startswith("test_") for part in parts)


def _offences(tree: ast.AST) -> list[tuple[int, str]]:
    """Line number and module name of every import of a sibling test module."""
    found: list[tuple[int, str]] = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Import):
            for alias in node.names:
                if _names_a_module_of_this_directory(alias.name):
                    found.append((node.lineno, alias.name))
        elif isinstance(node, ast.ImportFrom):
            if node.module and _names_a_module_of_this_directory(node.module):
                found.append((node.lineno, node.module))
                continue
            # `from . import test_api` and `from tests import test_api` name
            # the module in the alias rather than in `module`. Only looked at
            # for a relative import or one rooted at `tests`, because a name
            # imported from `app.…` is a class or a function and a
            # `test_`-shaped one there would be a false alarm.
            if node.level > 0 or node.module in (None, "tests"):
                for alias in node.names:
                    if _names_a_module_of_this_directory(alias.name):
                        found.append((node.lineno, alias.name))
    return found


def test_no_test_module_imports_another():
    offenders: list[str] = []
    for path in sorted(TESTS.glob("test_*.py")):
        tree = ast.parse(path.read_text(encoding="utf-8"))
        offenders += [f"{path.name}:{line} imports {name}" for line, name in _offences(tree)]
    assert offenders == [], (
        "A test module may not import another one, nor `conftest` itself — under "
        "`--import-mode=importlib` neither is on `sys.path` and the file dies at "
        "collection. Move the helper into `conftest.py` and hand it out as a fixture."
    )


def test_the_check_recognises_every_spelling_of_the_slip():
    """Held here rather than trusted, because the previous version was not.

    One mistake, six spellings. A matcher that catches the first two is what
    this repository already had, and it passed on five files doing the other
    four.
    """
    for source in (
        "from tests.test_api import _token",
        "import tests.test_api",
        "from test_api import _token",
        "import test_api",
        "from . import test_api",
        "from tests import test_api",
        "import conftest",
        "from conftest import FakeCallback",
    ):
        assert _offences(ast.parse(source)), source

    # And it is quiet about everything a test file legitimately imports —
    # including a name that merely *starts* with the same letters.
    for source in (
        "from app.models import TimetableEntry",
        "from app.bot.handlers import content",
        "import pytest",
        "from sqlalchemy import select",
        "from app.services import testing_helpers",
        "import httpx",
    ):
        assert not _offences(ast.parse(source)), source


def test_the_suite_is_importable_the_way_ci_runs_it():
    """`conftest.py` is the one place a fixture may be shared from.

    Held as a fact about the tree rather than a convention in a comment: if
    somebody moves the fixtures into a module and imports it, the test above
    names them, and this one says where they should have gone instead.
    """
    assert (TESTS / "conftest.py").is_file()
