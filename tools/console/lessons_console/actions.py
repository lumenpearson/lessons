"""Compound closed tasks; invoked by the runner, never by a shell string."""

import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.request
from pathlib import Path

from lessons_console.environment import (
    buf_release,
    cache_directory,
    clean_server_environment,
    server_python,
)


def run(argv: list[str], root: Path) -> None:
    subprocess.run(argv, cwd=root, env=clean_server_environment(), check=True)


def verified_buf(root: Path) -> Path:
    version, url, digest = buf_release(root)
    cached = cache_directory() / "buf" / version / ("buf.exe" if os.name == "nt" else "buf")
    cached.parent.mkdir(parents=True, exist_ok=True)
    if not cached.is_file() or hashlib.sha256(cached.read_bytes()).hexdigest() != digest:
        # Each process gets its own partial file; only verified bytes become executable.
        fd, temporary = tempfile.mkstemp(prefix="buf-", dir=cached.parent)
        try:
            with os.fdopen(fd, "wb") as output, urllib.request.urlopen(url, timeout=60) as response:
                shutil.copyfileobj(response, output)
            if hashlib.sha256(Path(temporary).read_bytes()).hexdigest() != digest:
                raise ValueError("Buf checksum mismatch; refusing download")
            Path(temporary).chmod(0o755)
            os.replace(temporary, cached)
        finally:
            Path(temporary).unlink(missing_ok=True)
    result = subprocess.run([str(cached), "--version"], capture_output=True, text=True, check=True)
    if result.stdout.strip() != version:
        raise ValueError("Buf version does not match CI")
    return cached


def compare_tree(expected: Path, actual: Path) -> None:
    def files(directory: Path) -> dict[str, bytes]:
        if not directory.is_dir():
            raise ValueError("Generated tree is missing")
        return {str(path.relative_to(directory)): path.read_bytes().replace(b"\r\n", b"\n")
                for path in directory.rglob("*")
                if path.is_file() and "__pycache__" not in path.parts}
    left, right = files(expected), files(actual)
    changed = sorted(key for key in left.keys() | right.keys() if left.get(key) != right.get(key))
    if changed:
        print("Generated source differs: " + ", ".join(changed[:30]), flush=True)
        raise ValueError("Committed generated source is stale")


def action(name: str, root: Path) -> None:
    if name == "prepare":
        uv = shutil.which("uv")
        if not uv:
            raise ValueError("uv is required")
        python = server_python(root)
        if not python.is_file():
            run([uv, "venv", str(root / "server/.venv"), "--python", "3.12"], root)
        run([uv, "pip", "install", "--python", str(python), "-r", "../requirements.txt", "-e", ".[dev]"], root / "server")
        run([uv, "pip", "check", "--python", str(python)], root)
        print("Prepare complete. Refresh task availability before running gates.", flush=True)
        return
    buf = str(verified_buf(root))
    if name == "buf-lint":
        run([buf, "lint"], root)
    elif name == "buf-generate":
        run([buf, "generate"], root)
    elif name == "buf-breaking":
        run(["git", "fetch", "origin", "master"], root)
        run([buf, "breaking", "--against", ".git#ref=origin/master"], root)
    elif name == "buf-fresh":
        with tempfile.TemporaryDirectory(prefix="lessons-contract-") as directory:
            generated = Path(directory)
            run([buf, "generate", "--output", str(generated)], root)
            for relative in ("server/app/contract", "android/core/data/src/main/contract/java"):
                compare_tree(generated / relative, root / relative)
    else:
        raise ValueError("Unknown closed console action")


if __name__ == "__main__":
    try:
        action(sys.argv[1], Path(sys.argv[2]))
    except (OSError, ValueError, subprocess.CalledProcessError) as error:
        # Do not render arbitrary exception values: network errors can contain credentials.
        print(f"Console action failed ({type(error).__name__}). See the preceding redacted output.", flush=True)
        raise SystemExit(1) from None
