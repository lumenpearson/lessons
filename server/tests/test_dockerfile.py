"""`server/Dockerfile`, read for the two properties a build would not report.

Neither is visible from outside: an image that runs as root answers exactly as
one that does not, and a package installed before its code works for as long as
the working directory happens to be the one `app/` was copied into. Nothing
here runs Docker, so the file is what is checked.
"""

from __future__ import annotations

from pathlib import Path

DOCKERFILE = Path(__file__).resolve().parents[1] / "Dockerfile"


def _instructions() -> list[tuple[str, str]]:
    """``(INSTRUCTION, arguments)`` in order, continuation lines joined."""
    joined = DOCKERFILE.read_text("utf-8").replace("\\\n", " ")
    out: list[tuple[str, str]] = []
    for line in joined.splitlines():
        stripped = line.strip()
        if not stripped or stripped.startswith("#"):
            continue
        keyword, _, rest = stripped.partition(" ")
        out.append((keyword.upper(), rest.strip()))
    return out


def test_the_server_does_not_run_as_root():
    """#195. The last `USER` is what `CMD` runs as, and without one it is root."""
    users = [args for keyword, args in _instructions() if keyword == "USER"]
    assert users, "no USER: uvicorn runs as root inside the container"
    assert users[-1].split(":")[0] not in {"root", "0"}, users[-1]

    # A USER after CMD would change nothing about what CMD runs as.
    order = [keyword for keyword, _ in _instructions()]
    assert order.index("USER") < order.index("CMD")


def test_the_package_is_installed_after_its_code_is_copied():
    """#195. Installed first, `pip install .` packaged an `app` that was not
    there yet, and the image imported the copy in the working directory
    instead — which only a `python -m` started from `/srv` can see."""
    instructions = _instructions()
    install = next(
        index
        for index, (keyword, args) in enumerate(instructions)
        if keyword == "RUN" and "pip install" in args
    )
    copied = [
        args.split()[0]
        for keyword, args in instructions[:install]
        if keyword == "COPY"
    ]
    assert "pyproject.toml" in copied
    assert "app" in copied, "the package is installed before app/ is in the image"
