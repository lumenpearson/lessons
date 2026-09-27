"""What Vercel installs, and what keeps it the set the tests ran on.

``requirements.txt`` at the repository root exists for one reason: Vercel's
Python builder does not read a ``pyproject.toml`` from a subdirectory. It used
to be a copy of the floors, so every deploy installed whatever was newest that
day and nothing tested that set (#192). It is a lock now: ``requirements.in``
beside it is the deployed share of ``server/pyproject.toml``'s dependencies,
and ``requirements.txt`` is that list resolved by ``uv pip compile`` to one
exact version of every package the function can import. CI installs the lock
together with the package, so the suite runs on exactly those versions.

Three files, and three ways for them to come apart: a floor raised in
``pyproject.toml`` because a handler started calling a new API, and not in the
lock's input; an input edited and the lock not regenerated; a pin edited by
hand that leaves the deploy to choose a package for itself.

The httpx ceiling has its own pair of tests in ``test_diary_client.py``,
because *that* bound is about a deprecation rather than about the mirror.
"""

from __future__ import annotations

import os
import pathlib
import re
import tomllib
from importlib import metadata

import pytest
from packaging.requirements import Requirement
from packaging.utils import NormalizedName, canonicalize_name
from packaging.version import Version

ROOT = pathlib.Path(__file__).resolve().parents[2]
PYPROJECT = ROOT / "server" / "pyproject.toml"
LOCK_INPUT = ROOT / "requirements.in"
LOCK = ROOT / "requirements.txt"
PYTHON_VERSION = ROOT / ".python-version"

#: Deliberately not in the function bundle, each for a reason written down
#: where it is left out. ``uvicorn`` is the local server and Vercel brings its
#: own; ``aiosqlite`` is the local database and the serverless one is Postgres;
#: ``alembic`` runs from a workstation against the database, never from inside
#: a request; ``tzdata`` is the time-zone database for Windows, which is the one
#: platform without its own, and Vercel runs Linux (#170).
DEPLOYED_WITHOUT = {"uvicorn", "aiosqlite", "alembic", "tzdata"}

#: What an environment marker is asked on the function's platform: CPython on
#: x86-64 Linux, at the Python the lock's header names. Only what a marker in
#: this dependency tree can plausibly ask is spelled out; the rest is left to
#: ``packaging``'s defaults, which no marker here reads.
_LINUX = {
    "os_name": "posix",
    "sys_platform": "linux",
    "platform_system": "Linux",
    "platform_machine": "x86_64",
    "implementation_name": "cpython",
    "platform_python_implementation": "CPython",
}


def _requirements(path: pathlib.Path) -> list[str]:
    """Every requirement line of a pip requirements file, comments dropped."""
    return [
        line.split(" #", 1)[0].strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _by_name(requirements: list[str]) -> dict[NormalizedName, str]:
    return {canonicalize_name(Requirement(item).name): item.strip() for item in requirements}


def _declared() -> dict[NormalizedName, str]:
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    return _by_name(data["project"]["dependencies"])


def _deployed() -> dict[NormalizedName, str]:
    return _by_name(_requirements(LOCK_INPUT))


def _pins() -> dict[NormalizedName, str]:
    """``name -> version`` for every line of the lock that pins one;
    ``test_the_lock_pins_every_package_to_one_version`` names the others."""
    pins: dict[NormalizedName, str] = {}
    for line in _requirements(LOCK):
        requirement = Requirement(line)
        specifiers = list(requirement.specifier)
        if len(specifiers) == 1 and specifiers[0].operator == "==":
            pins[canonicalize_name(requirement.name)] = specifiers[0].version
    return pins


def _header() -> str:
    lines = LOCK.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines[:5] if line.startswith("#"))


def _compiled_python() -> str:
    match = re.search(r"--python-version[= ](\S+)", _header())
    assert match, "the lock's header does not say which Python it was resolved for"
    return match.group(1)


# ---- the input: the deployed share of pyproject.toml ----------------------


def test_every_dependency_the_code_needs_is_in_the_deployed_set():
    missing = {
        name: requirement
        for name, requirement in _declared().items()
        if name not in DEPLOYED_WITHOUT and name not in _deployed()
    }
    assert not missing, (
        "requirements.in does not ask for: "
        + ", ".join(sorted(missing))
        + " — Vercel installs its lock, so the function would import what is not there."
    )


def test_the_deployed_set_installs_nothing_the_project_does_not_declare():
    """The other direction: a package only the lock's input knows about is a
    package nothing declares, and it stays in the bundle long after whatever
    wanted it is gone."""
    extra = set(_deployed()) - set(_declared())
    assert not extra, f"requirements.in names what pyproject.toml does not: {sorted(extra)}"


def test_the_two_lists_ask_for_the_same_versions():
    """A floor raised in one place only is a floor one of the two installs
    ignores.

    ``aiogram>=3.31`` is the example that is already written down: below it
    every coloured keyboard raises at construction. Raising that floor in
    ``pyproject.toml`` alone and leaving the lock's input behind would keep
    the deployment on whatever the lock held — until the next regeneration,
    which reads the input and would happily keep it there.
    """
    declared, deployed = _declared(), _deployed()
    differing = {
        name: (declared[name], deployed[name])
        for name in sorted(set(declared) & set(deployed))
        if declared[name] != deployed[name]
    }
    assert not differing, f"pyproject.toml and requirements.in disagree: {differing}"


# ---- the lock: requirements.txt --------------------------------------------


def test_the_lock_pins_every_package_to_one_version():
    """A range left in the lock is a version the deploy chooses on the day,
    which is exactly what the lock exists to stop."""
    loose = []
    for line in _requirements(LOCK):
        requirement = Requirement(line)
        specifiers = list(requirement.specifier)
        if (
            len(specifiers) != 1
            or specifiers[0].operator != "=="
            or "*" in specifiers[0].version
            or requirement.url
        ):
            loose.append(line)
    assert not loose, f"requirements.txt must pin with == and nothing else: {loose}"


def test_every_deployed_requirement_is_pinned_at_a_version_it_allows():
    """The floors and the httpx ceiling, held against what is actually
    deployed. CI's install would refuse the pair as well — this names the
    package and the fix, where pip names a resolution conflict."""
    pins = _pins()
    wrong = {}
    for name, line in _deployed().items():
        requirement = Requirement(line)
        pinned = pins.get(name)
        if pinned is None or not requirement.specifier.contains(pinned, prereleases=True):
            wrong[name] = (line, pinned)
    assert not wrong, (
        f"requirements.txt pins versions its input refuses: {wrong} — regenerate it with the "
        "command in its header"
    )


def test_the_lock_was_compiled_from_this_input():
    """uv marks each package the input asked for by name with ``-r
    requirements.in``. A package added to the input with no regeneration is
    missing from the lock; one removed is still marked there, and still
    deployed."""
    direct: set[NormalizedName] = set()
    current: NormalizedName | None = None
    for line in LOCK.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            current = canonicalize_name(Requirement(stripped).name)
        elif current is not None and "-r requirements.in" in stripped:
            direct.add(current)
    assert direct == set(_deployed()), (
        f"the lock was compiled from another input: it marks {sorted(direct)}, "
        f"requirements.in asks for {sorted(_deployed())} — regenerate it with the command "
        "in its header"
    )


def test_the_lock_is_resolved_for_the_python_and_platform_the_function_runs_on():
    """A lock answers for one Python on one platform. Resolved for another, it
    can pin a release that does not install on the function's, or leave out a
    dependency only the function's needs — which the deploy then picks for
    itself. ``.python-version`` is this repository's statement of that
    Python, and CI's ``setup-python`` asks for the same one."""
    header = _header()
    assert "uv pip compile requirements.in" in header, (
        "the lock's header no longer names the command that regenerates it"
    )
    assert "--python-platform linux" in header, "the function runs on Linux"
    assert _compiled_python() == PYTHON_VERSION.read_text(encoding="utf-8").strip(), (
        "requirements.txt was resolved for another Python than .python-version names"
    )


def test_the_lock_is_exactly_what_the_input_needs_on_the_function_platform():
    """Every package a pinned package needs on the function's platform is
    pinned too, and nothing else is.

    ``pip install -r requirements.txt`` resolves: a dependency the lock does
    not name is still installed, at whatever version is newest that day. uv
    writes a closed lock, so this can only go wrong by hand — a pin moved to a
    release that brought a new dependency along, or a line added that nothing
    asks for and that nothing tests. The dependencies are read from the
    installed packages' own metadata, which is the metadata of the pinned
    versions only where the environment was installed from the lock; CI's
    always is, and a local environment that is not is skipped with the command
    that makes it so.
    """
    pins = _pins()
    stale = {
        name: version
        for name, version in pins.items()
        if (installed := _installed_version(name)) is None or Version(installed) != Version(version)
    }
    if stale:
        message = (
            f"this environment is not the lock's ({len(stale)} packages differ, "
            f'{sorted(stale)[:3]}…): pip install -r ../requirements.txt -e ".[dev]"'
        )
        if os.environ.get("CI"):
            pytest.fail(message)
        pytest.skip(message)

    environment = {**_LINUX, "python_version": _compiled_python()}
    environment["python_full_version"] = environment["python_version"] + ".0"

    unpinned: dict[str, set[str]] = {}
    seen: set[tuple[NormalizedName, frozenset[str]]] = set()
    queue = [
        (canonicalize_name(r.name), frozenset(r.extras))
        for r in map(Requirement, _deployed().values())
    ]
    while queue:
        name, extras = queue.pop()
        if (name, extras) in seen:
            continue
        seen.add((name, extras))
        for line in metadata.requires(name) or []:
            requirement = Requirement(line)
            if requirement.marker and not any(
                requirement.marker.evaluate({**environment, "extra": extra})
                for extra in {"", *extras}
            ):
                continue
            needed = canonicalize_name(requirement.name)
            if needed not in pins:
                unpinned.setdefault(needed, set()).add(name)
                continue
            queue.append((needed, frozenset(requirement.extras)))
    assert not unpinned, (
        f"requirements.txt leaves these to the deploy: {unpinned} — regenerate it with the "
        "command in its header rather than editing a pin"
    )
    orphans = set(pins) - {name for name, _ in seen}
    assert not orphans, f"requirements.txt pins what nothing deployed needs: {sorted(orphans)}"


def _installed_version(name: str) -> str | None:
    try:
        return metadata.version(name)
    except metadata.PackageNotFoundError:
        return None
