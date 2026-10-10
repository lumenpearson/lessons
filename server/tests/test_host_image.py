"""The host's image (the root ``Dockerfile``) and its lock, read against what they promise.

Nothing here builds the image: there is no Docker where this is developed, and
CI's «Host» job installs the same two locks and starts the same command
instead. What can be held without Docker is held here: that the host's lock
pins every package once, was compiled from its input against Vercel's lock,
and agrees with it on every package both pin; that Vercel's lock carries
nothing of the host's; and that the image installs both, says it is the host,
copies only what its context holds, and runs it as nobody in particular
(``docs/specs/2026-10-05-server-v2-design.md``, decision 13).
"""

from __future__ import annotations

import re
import tomllib
from pathlib import Path

from packaging.requirements import Requirement
from packaging.utils import NormalizedName, canonicalize_name

ROOT = Path(__file__).resolve().parents[2]
SERVER = ROOT / "server"
DOCKERFILE = ROOT / "Dockerfile"
IGNORE = ROOT / ".dockerignore"
HOST_INPUT = SERVER / "requirements-host.in"
HOST_LOCK = SERVER / "requirements-host.txt"
LOCK = ROOT / "requirements.txt"
LOCK_INPUT = ROOT / "requirements.in"
PYPROJECT = SERVER / "pyproject.toml"
PYTHON_VERSION = ROOT / ".python-version"

#: What the host runs and Vercel never does.
HOST_ONLY = {"pyvoy", "envoy-server", "hypercorn"}


def _requirements(path: Path) -> list[str]:
    """Every requirement line of a requirements file, comments dropped."""
    return [
        line.split(" #", 1)[0].strip()
        for line in path.read_text(encoding="utf-8").splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def _names(path: Path) -> set[NormalizedName]:
    return {canonicalize_name(Requirement(line).name) for line in _requirements(path)}


def _pins(path: Path) -> dict[NormalizedName, str]:
    pins: dict[NormalizedName, str] = {}
    for line in _requirements(path):
        requirement = Requirement(line)
        (specifier,) = requirement.specifier
        pins[canonicalize_name(requirement.name)] = specifier.version
    return pins


def _header(path: Path) -> str:
    lines = path.read_text(encoding="utf-8").splitlines()
    return "\n".join(line for line in lines[:5] if line.startswith("#"))


def test_the_host_lock_pins_every_package_to_one_version() -> None:
    loose = []
    for line in _requirements(HOST_LOCK):
        specifiers = list(Requirement(line).specifier)
        if len(specifiers) != 1 or specifiers[0].operator != "==" or "*" in specifiers[0].version:
            loose.append(line)
    assert loose == [], f"requirements-host.txt must pin with == and nothing else: {loose}"


def test_the_host_lock_was_compiled_from_its_input_against_vercel_s() -> None:
    """Its header is the command that regenerates it, from server/; uv marks
    each package the input asks for by name, so an input edited and not
    compiled shows here."""
    header = _header(HOST_LOCK)
    assert "uv pip compile requirements-host.in -c ../requirements.txt" in header
    assert "--universal" in header, "the host runs on Linux and on the console's Windows"
    python = PYTHON_VERSION.read_text(encoding="utf-8").strip()
    assert f"--python-version {python}" in header
    direct: set[NormalizedName] = set()
    current: NormalizedName | None = None
    for line in HOST_LOCK.read_text(encoding="utf-8").splitlines():
        stripped = line.strip()
        if stripped and not stripped.startswith("#"):
            current = canonicalize_name(Requirement(stripped).name)
        elif current is not None and "-r requirements-host.in" in stripped:
            direct.add(current)
    assert direct == _names(HOST_INPUT), (
        f"the host lock marks {sorted(direct)}, its input asks for {sorted(_names(HOST_INPUT))} "
        "— regenerate it with the command in its header"
    )


def test_every_package_both_locks_pin_is_pinned_at_one_version() -> None:
    """The point of compiling one against the other: the image installs both,
    and two pins of one package are a resolution pip refuses, or worse, the
    later one silently winning. A bump of Vercel's lock that moves a package
    the host shares fails here until the host's lock is regenerated."""
    vercel, hosted = _pins(LOCK), _pins(HOST_LOCK)
    shared = set(vercel) & set(hosted)
    assert shared, "the two locks share no package, so this would hold nothing"
    differing = {
        name: (vercel[name], hosted[name]) for name in shared if vercel[name] != hosted[name]
    }
    assert differing == {}, (
        f"requirements.txt and server/requirements-host.txt disagree: {differing} — regenerate "
        "the host's lock with the command in its header"
    )


def test_vercel_s_lock_and_its_input_carry_nothing_of_the_host_s() -> None:
    """Vercel installs requirements.txt, and app.main is its cold start: a
    server package there is weight on every request and a reason it could be
    imported by mistake."""
    declared = {
        canonicalize_name(Requirement(line).name)
        for line in tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))["project"]["dependencies"]
    }
    for name, found in (
        ("requirements.txt", set(_pins(LOCK))),
        ("requirements.in", _names(LOCK_INPUT)),
        ("pyproject.toml's dependencies", declared),
    ):
        assert HOST_ONLY & found == set(), f"{name} carries the host's {sorted(HOST_ONLY & found)}"
    assert HOST_ONLY <= set(_pins(HOST_LOCK))


def _instructions() -> list[str]:
    """The Dockerfile's instructions, continuation lines joined, comments dropped."""
    joined = re.sub(r"\\\n\s*", " ", DOCKERFILE.read_text(encoding="utf-8"))
    return [
        line.strip()
        for line in joined.splitlines()
        if line.strip() and not line.lstrip().startswith("#")
    ]


def test_the_image_installs_both_locks_and_then_the_package_alone() -> None:
    steps = _instructions()
    locks = next(
        i
        for i, step in enumerate(steps)
        if step.startswith("RUN pip install")
        and "-r requirements.txt" in step
        and "-r requirements-host.txt" in step
    )
    package = next(
        i
        for i, step in enumerate(steps)
        if step.startswith("RUN pip install") and "--no-deps ." in step
    )
    assert locks < package
    installs = [step for step in steps if step.startswith("RUN pip install")]
    assert len(installs) == 2, (
        f"nothing may be installed but the two locks and the package: {installs}"
    )


def test_the_image_says_it_is_the_host_and_the_code_never_does() -> None:
    environment = " ".join(step for step in _instructions() if step.startswith("ENV "))
    assert re.search(r"\bLESSONS_TARGET=host\b", environment)
    setting = re.compile(
        r"""environ\[\s*["']LESSONS_TARGET["']\s*\]\s*=(?!=)|setdefault\(\s*["']LESSONS_TARGET"""
        r"""|putenv\(\s*["']LESSONS_TARGET|setenv\(\s*["']LESSONS_TARGET"""
    )
    writers = [
        path.relative_to(SERVER).as_posix()
        for path in sorted((SERVER / "app").rglob("*.py"))
        if setting.search(path.read_text(encoding="utf-8"))
    ]
    assert writers == []


def test_the_image_runs_the_host_as_a_user_of_its_own() -> None:
    steps = _instructions()
    users = [step for step in steps if step.startswith("USER ")]
    assert users and users[-1] != "USER root"
    assert steps[-1] == 'CMD ["python", "-m", "app.host"]'


def test_the_build_context_holds_everything_the_image_copies() -> None:
    """The context is the repository's root with everything ignored but what
    is let back in; a COPY of something not let back in fails the build."""
    let_in = [
        line[1:].rstrip("/")
        for line in IGNORE.read_text(encoding="utf-8").splitlines()
        if line.startswith("!")
    ]
    assert IGNORE.read_text(encoding="utf-8").splitlines().count("*") == 1
    sources = [step.split()[1] for step in _instructions() if step.startswith("COPY ")]
    missing = [
        source
        for source in sources
        if not any(source == entry or source.startswith(entry + "/") for entry in let_in)
    ]
    assert missing == [], f"the Dockerfile copies what .dockerignore leaves out: {missing}"
    assert all((ROOT / source).exists() for source in sources)
