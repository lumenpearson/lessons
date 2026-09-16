"""The two dependency lists that have to say the same thing.

``requirements.txt`` at the repository root exists for one reason: Vercel's
Python builder does not read a ``pyproject.toml`` from a subdirectory. So the
deployed set is installed from the copy, and every test in this suite runs
against the original — which makes drift between them the one kind of
dependency bug nothing else here can see. A floor raised in ``pyproject.toml``
because a handler started calling a new API installs fine in CI and ships a
function that imports the old one.

The httpx ceiling has its own pair of tests in ``test_diary_client.py``,
because *that* bound is about a deprecation rather than about the mirror.
"""

from __future__ import annotations

import pathlib
import re
import tomllib

ROOT = pathlib.Path(__file__).resolve().parents[2]
PYPROJECT = ROOT / "server" / "pyproject.toml"
REQUIREMENTS = ROOT / "requirements.txt"

#: Deliberately not in the function bundle, each for a reason written down
#: where it is left out. ``uvicorn`` is the local server and Vercel brings its
#: own; ``aiosqlite`` is the local database and the serverless one is Postgres;
#: ``alembic`` runs from a workstation against the database, never from inside
#: a request.
DEPLOYED_WITHOUT = {"uvicorn", "aiosqlite", "alembic"}

_NAME = re.compile(r"^[A-Za-z0-9._-]+")


def _name(requirement: str) -> str:
    """``httpx>=0.28.1,<0.29`` → ``httpx``, ``uvicorn[standard]>=0.30`` → ``uvicorn``."""
    match = _NAME.match(requirement.strip())
    assert match, requirement
    return match.group(0).lower().replace("_", "-")


def _declared() -> dict[str, str]:
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    return {_name(item): item.strip() for item in data["project"]["dependencies"]}


def _deployed() -> dict[str, str]:
    lines = [
        line.strip()
        for line in REQUIREMENTS.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]
    return {_name(line): line for line in lines}


def test_every_dependency_the_code_needs_is_in_the_deployed_set():
    missing = {
        name: requirement
        for name, requirement in _declared().items()
        if name not in DEPLOYED_WITHOUT and name not in _deployed()
    }
    assert not missing, (
        "requirements.txt does not install: "
        + ", ".join(sorted(missing))
        + " — Vercel installs from it, so the function would import what is not there."
    )


def test_the_deployed_set_installs_nothing_the_project_does_not_declare():
    """The other direction: a package only the copy knows about is a package
    nothing tests against, and it stays in the bundle long after whatever
    wanted it is gone."""
    extra = set(_deployed()) - set(_declared())
    assert not extra, f"requirements.txt names what pyproject.toml does not: {sorted(extra)}"


def test_the_two_lists_pin_the_same_versions():
    """A floor raised in one place only is invisible until production.

    ``aiogram>=3.31`` is the example that is already written down: below it
    every coloured keyboard raises at construction. Raising that floor in
    ``pyproject.toml`` alone would leave CI green — it installs the original —
    and give the deployment a bot whose every menu throws.
    """
    declared, deployed = _declared(), _deployed()
    differing = {
        name: (declared[name], deployed[name])
        for name in sorted(set(declared) & set(deployed))
        if declared[name] != deployed[name]
    }
    assert not differing, f"the two lists disagree: {differing}"
