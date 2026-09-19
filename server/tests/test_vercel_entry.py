"""The twenty lines of ``api/index.py``, which nothing else in this repository
reads.

``vercel.json`` names that file and Vercel looks in it for a module-level ASGI
callable called ``app``. Nothing imported it from here: it lives above
``server/``, so ``ruff check app tests scripts migrations`` walks past it and
``testpaths = ["tests"]`` never collected it. CI *notices* a change to it — the
``changes`` job in ``ci.yml`` sets ``server=true`` for ``api/*`` — and then runs
a job that does not look at it. Rename the symbol, or drop the ``sys.path``
line that makes ``app`` importable from the repository root, and both gates
stay green while every request to the deployment is a 500.

Two conditions, because they fail apart. The first is that the entry point
re-exports the *same* application object the server runs, so the deployment
cannot drift into answering with something else. The second is that it can find
that object at all from where Vercel starts it: the repository root, with no
``server/`` on the path and no editable install of ``app``.
"""

from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path
from types import ModuleType

from app.main import app as the_real_app

ROOT = Path(__file__).resolve().parents[2]
ENTRY = ROOT / "api" / "index.py"


def _load() -> ModuleType:
    """Execute ``api/index.py`` the way an importer would, and clean up after it.

    The module inserts ``server/`` at the front of ``sys.path`` on import. That
    is its job, but ``sys.path`` is a global: a duplicated entry left behind
    here would follow every later test in this worker.
    """
    spec = importlib.util.spec_from_file_location("vercel_entry_under_test", ENTRY)
    assert spec is not None and spec.loader is not None, ENTRY
    module = importlib.util.module_from_spec(spec)

    before = list(sys.path)
    try:
        spec.loader.exec_module(module)
    finally:
        sys.path[:] = before
    return module


def test_the_entry_point_exists_where_vercel_json_says_it_does():
    assert ENTRY.is_file(), f"vercel.json routes every request to {ENTRY}"


def test_the_entry_point_exposes_the_very_app_the_server_runs():
    """Not «an app» — *the* app.

    A second ``FastAPI()`` here would answer, and answer wrongly: no routers,
    no lifespan, no bot. Identity is the only assertion that says the
    deployment and ``uvicorn app.main:app`` run the same object. ``__all__`` is
    checked beside it because that string is the file's own statement of its
    contract, and Vercel looks the attribute up by name.
    """
    module = _load()

    assert module.app is the_real_app
    assert module.__all__ == ["app"]


#: Reproduce the import conditions on Vercel, which are not this suite's.
#:
#: Here ``app`` is importable from anywhere, because ``pip install -e`` left a
#: finder on ``sys.meta_path`` that answers for it. There it is importable only
#: because ``api/index.py`` says so: the platform installs ``requirements.txt``,
#: which names no first-party package, and starts from the repository root.
#: Taking the finder and ``server/`` away is what makes that file's ``sys.path``
#: line load-bearing again — without it this test would pass on an entry point
#: that had lost the line entirely.
_AS_VERCEL_STARTS_IT = """
import pathlib
import sys

root = pathlib.Path({root!r}).resolve()
server = root / "server"
sys.path = [entry for entry in sys.path if entry and pathlib.Path(entry).resolve() != server]
sys.path.insert(0, str(root))
sys.meta_path = [
    finder
    for finder in sys.meta_path
    if not getattr(finder, "__module__", "").startswith("__editable__")
]

try:
    import app  # noqa: F401
except ImportError:
    pass
else:
    print("PRECONDITION-FAILED")
    raise SystemExit(0)

import api.index as entry

print(sys.modules["app.main"].__file__)
print(entry.app is sys.modules["app.main"].app)
"""


def test_the_entry_point_finds_the_package_the_way_vercel_does():
    """The ``sys.path`` line, proved by taking every other way in away.

    ``api/`` is not a package and is not meant to become one: Vercel imports it
    from the repository root as a namespace package, which is what this does.
    """
    result = subprocess.run(
        [sys.executable, "-c", _AS_VERCEL_STARTS_IT.format(root=str(ROOT))],
        cwd=str(ROOT),
        capture_output=True,
        text=True,
    )

    assert result.returncode == 0, result.stderr
    lines = [line for line in result.stdout.splitlines() if line.strip()]
    assert "PRECONDITION-FAILED" not in lines, (
        "`app` was importable without the entry point, so this proved nothing "
        "about the sys.path line it exists to hold"
    )
    assert lines[-2:] == [str(ROOT / "server" / "app" / "main.py"), "True"], result.stdout
