"""Discover source-owned facts without importing server code or reading values."""

import ast
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import yaml


def repository(start: Path | None = None) -> Path:
    for directory in (start or Path.cwd()).resolve().parents:
        if (directory / ".github/workflows/ci.yml").is_file():
            return directory
    candidate = (start or Path.cwd()).resolve()
    if (candidate / ".github/workflows/ci.yml").is_file():
        return candidate
    raise RuntimeError("Run from a Lessons checkout")


def server_python(root: Path) -> Path:
    return root / "server/.venv" / ("Scripts/python.exe" if os.name == "nt" else "bin/python")


def clean_server_environment(environment: dict[str, str] | None = None) -> dict[str, str]:
    env = dict(os.environ if environment is None else environment)
    console_venv = Path(os.path.abspath(sys.prefix))
    env.pop("VIRTUAL_ENV", None)
    env.pop("PYTHONPATH", None)
    env["PATH"] = os.pathsep.join(
        part for part in env.get("PATH", "").split(os.pathsep)
        if part and not Path(os.path.abspath(part)).is_relative_to(console_venv)
    )
    return env


def server_problem(root: Path) -> str | None:
    python = server_python(root)
    if not python.is_file():
        return "This checkout has no server/.venv. Select Prepare."
    try:
        result = subprocess.run(
            [str(python), "-I", "-c", "import app; print(app.__file__)"],
            cwd=root / "server", env=clean_server_environment(), capture_output=True,
            text=True, encoding="utf-8", errors="replace", timeout=10,
            check=False,
        )
        actual = Path(result.stdout.strip()).resolve()
        if result.returncode or actual != (root / "server/app/__init__.py").resolve():
            return "The server environment does not import this checkout's app; select Prepare."
    except (OSError, ValueError, subprocess.TimeoutExpired):
        return "The checkout's server interpreter could not be verified."
    return None


def jdk21(root: Path, environment: dict[str, str] | None = None) -> Path | None:
    env = os.environ if environment is None else environment
    candidates: list[Path] = []
    config = root / "android/.gradle/config.properties"
    if config.is_file():
        for line in config.read_text(encoding="utf-8").splitlines():
            if line.startswith("java.home="):
                candidates.append(Path(line.split("=", 1)[1].replace("\\:", ":").replace("\\\\", "\\")))
    candidates.extend(sorted(Path("C:/Program Files/Eclipse Adoptium").glob("jdk-21*")))
    if env.get("JAVA_HOME"):
        candidates.append(Path(env["JAVA_HOME"]))
    for candidate in candidates:
        release = candidate / "release"
        if release.is_file() and re.search(r'^JAVA_VERSION="21(?:[.\-+"_])',
                                          release.read_text(encoding="utf-8"), re.MULTILINE):
            return candidate
    return None


def settings(root: Path) -> dict[str, str]:
    module = ast.parse((root / "server/app/config.py").read_text(encoding="utf-8"))
    owner = next(node for node in module.body if isinstance(node, ast.ClassDef) and node.name == "Settings")
    result = {}
    for node in owner.body:
        if not isinstance(node, ast.AnnAssign) or not isinstance(node.target, ast.Name):
            continue
        alias = node.target.id.upper()
        if isinstance(node.value, ast.Call):
            for keyword in node.value.keywords:
                if keyword.arg == "alias" and isinstance(keyword.value, ast.Constant):
                    alias = keyword.value.value
        result[node.target.id] = alias
    return result


def setting_status(root: Path, environment: dict[str, str] | None = None) -> dict[str, bool]:
    env = os.environ if environment is None else environment
    names: set[str] = set()
    dotenv = root / "server/.env"
    if dotenv.is_file():
        # Values never leave this line iteration and are never included in errors.
        with dotenv.open(encoding="utf-8-sig", errors="replace") as stream:
            for line in stream:
                match = re.match(r"\s*(?:export\s+)?([A-Za-z_][A-Za-z0-9_]*)\s*=", line)
                if match:
                    names.add(match.group(1).upper())
    example = root / "server/.env.example"
    if example.is_file():
        aliases = {match.group(1) for match in re.finditer(
            r"^\s*([A-Za-z_][A-Za-z0-9_]*)\s*=", example.read_text(encoding="utf-8"), re.MULTILINE)}
    else:
        aliases = set()
    aliases.update(settings(root).values())
    supplied = {name.upper() for name, value in env.items() if value}
    return {name: name in names or name in supplied for name in sorted(aliases)}


def buf_release(root: Path, windows: bool | None = None) -> tuple[str, str, str]:
    workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    action = next(step for step in workflow["jobs"]["contract"]["steps"]
                  if step.get("uses", "").startswith("bufbuild/buf-action@"))
    version = str(action["with"]["version"])
    platform = "Windows" if (os.name == "nt" if windows is None else windows) else "Linux"
    rows = re.findall(
        r"\| (Windows|Linux) x86-64 \| `(https://[^`]+)` \| `([0-9a-f]{64})` \|",
        (root / "docs/build.md").read_text(encoding="utf-8"),
    )
    matches = [(url, digest) for system, url, digest in rows if system == platform]
    if len(matches) != 1:
        raise ValueError("Expected exactly one pinned Buf platform row in docs/build.md")
    url, digest = matches[0]
    suffix = "buf-Windows-x86_64.exe" if platform == "Windows" else "buf-Linux-x86_64"
    if url != f"https://github.com/bufbuild/buf/releases/download/v{version}/{suffix}":
        raise ValueError("Buf download does not match the CI version/platform")
    linux = [(url, sha) for system, url, sha in rows if system == "Linux"]
    if len(linux) != 1 or linux[0][1] != action["with"]["checksum"]:
        raise ValueError("Buf Linux checksum differs from CI")
    return version, url, digest


def cache_directory() -> Path:
    override = os.environ.get("LESSONS_CONSOLE_CACHE")
    if override:
        return Path(override).expanduser().resolve()
    base = Path(os.environ.get("LOCALAPPDATA", Path.home() / ".cache"))
    return base / "lessons-console"


def tools_summary(root: Path) -> str:
    return "\n".join([
        "Python: 3.12 required for console and server gates",
        "Server: " + (server_problem(root) or "checkout-local app verified"),
        "JDK 21: " + (str(jdk21(root)) if jdk21(root) else "missing; Android tasks disabled"),
        "uv: " + ("available" if shutil.which("uv") else "missing; Prepare disabled"),
        "Buf: " + buf_release(root)[0] + " (verified cache; fetch on first Contract run)",
        "Signing: unknown; no passwords or Gradle properties are read.",
    ])


def environment_policy(root: Path) -> str:
    if server_problem(root):
        return "Prepare the checkout's server environment to read its configuration policy."
    # This deliberately asks the source-owned rules about an empty environment,
    # rather than importing server modules or exposing configured values.
    code = ("import json; from app.config import Settings; s=Settings(_env_file=None); "
            "print(json.dumps({'problems':s.deployment_problems(), 'disabled':s.disabled_features()}))")
    result = subprocess.run([str(server_python(root)), "-I", "-c", code], cwd=root / "server",
                            env={}, capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=10, check=False)
    if result.returncode:
        return "Server configuration policy probe failed; values and errors are withheld."
    try:
        data = json.loads(result.stdout)
        return "Empty-environment deployment requirements and disabled features:\n" + "\n".join(
            [*data["problems"], *data["disabled"]])
    except (ValueError, TypeError, KeyError):
        return "Server configuration policy probe returned an unsupported shape."


def emulator_occupants() -> list[str]:
    """A discovery probe only when the user selects a heavy run."""
    adb = shutil.which("adb")
    if not adb:
        android = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
        if android:
            candidate = Path(android) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
            if candidate.is_file():
                adb = str(candidate)
    if not adb:
        return []
    try:
        result = subprocess.run([adb, "devices", "-l"], capture_output=True, text=True,
                                encoding="utf-8", errors="replace", timeout=10, check=False)
    except subprocess.TimeoutExpired:
        raise RuntimeError("Emulator detection timed out") from None
    if result.returncode:
        raise RuntimeError("Emulator detection unavailable")
    occupants = []
    for line in result.stdout.splitlines():
        parts = line.split()
        if len(parts) > 1 and (parts[0].startswith("emulator-") or "sdk_gphone" in line):
            occupants.append(parts[0])
    return occupants
