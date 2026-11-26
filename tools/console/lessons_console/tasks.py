"""The only selectable commands. Changing the table is a reviewed source change."""

import os
import shutil
import sys
from dataclasses import dataclass
from pathlib import Path

from lessons_console.environment import (
    clean_server_environment,
    jdk21,
    server_problem,
    server_python,
)


@dataclass(frozen=True)
class Task:
    key: str
    name: str
    tab: str
    tool: str
    argv: tuple[str, ...]
    cwd: str
    heavy: bool
    description: str
    ci: tuple[str, str] | None = None
    always: bool = False
    settings: tuple[str, ...] = ()


TASKS = (
    Task("prepare", "Prepare server", "Build", "console", ("prepare",), "server", True,
         "Create this checkout's server environment and install CI's lock and dev tools.",
         ("server", "Install")),
    Task("server.lint", "Server: Ruff", "Build", "server", ("ruff", "check", "app", "tests", "scripts", "migrations"),
         "server", False, "Check server source and tests.", ("server", "Lint")),
    Task("server.types", "Server: mypy", "Build", "server", ("python", "-m", "mypy"),
         "server", False, "Check attributes with the server's configured mypy policy.", ("server", "Type check")),
    Task("server.test", "Server: complete tests", "Build", "server", ("pytest", "-q", "-n", "auto"),
         "server", True, "Run the complete ordinary suite; Host cases remain separate.", ("server", "Test")),
    Task("server.count", "Server: report count", "Build", "server",
         ("python", "scripts/check_test_counts.py", "server", "--junit", "work/pytest.xml"),
         "server", False, "Compare the completed full report with the published count.", ("server", "Check server test count")),
    Task("android.build", "Android: tests and APKs", "Build", "gradle",
         ("./gradlew", "test", "assembleDebug", "assembleRelease"), "android", True,
         "Run JVM tests, build debug/release APKs and check both outputs exist.", ("android", "Test and assemble")),
    Task("android.detekt", "Android: Detekt", "Build", "gradle", ("./gradlew", "detekt"),
         "android", True, "Check Kotlin, including after a failed build.", ("android", "Detekt"), True),
    Task("android.count", "Android: report count", "Build", "server",
         ("python", "../server/scripts/check_test_counts.py", "android", "--root", "."),
         "android", False, "Compare completed JVM reports with the published count.", ("android", "Check Android test count")),
    Task("contract.lint", "Contract: lint", "Contract", "console", ("buf-lint",), ".", False,
         "Run Buf STANDARD lint at CI's verified version.", ("contract", "buf:lint")),
    Task("contract.generate", "Contract: generate (writes sources)", "Contract", "console", ("buf-generate",),
         ".", True, "Regenerate both committed contract trees; review their diff."),
    Task("contract.breaking", "Contract: breaking vs origin/master", "Contract", "console", ("buf-breaking",),
         ".", False, "Fetch origin/master and compare; CI instead uses the PR base.", ("contract", "buf:breaking")),
    Task("contract.fresh", "Contract: generated freshness", "Contract", "console", ("buf-fresh",),
         ".", True, "Generate into temporary storage and compare both source trees.", ("contract", "Generated code is what proto/ generates")),
)
BY_KEY = {task.key: task for task in TASKS}
BATCHES = {
    "server.all": ("server.lint", "server.types", "server.test", "server.count"),
    "android.all": ("android.build", "android.detekt", "android.count"),
    "contract.all": ("contract.lint", "contract.breaking", "contract.fresh"),
}


def disabled_reason(task: Task, root: Path) -> str | None:
    if task.tool == "server":
        return server_problem(root)
    if task.tool == "gradle":
        if not jdk21(root):
            return "No verified JDK 21. Set JAVA_HOME or android/.gradle/config.properties."
        if not (os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")):
            return "Set ANDROID_HOME to an existing Android SDK; the console never installs it."
    if task.key == "prepare" and not shutil.which("uv"):
        return "uv is missing; install it outside the console."
    return None


def resolve(task: Task, root: Path) -> tuple[list[str], Path, dict[str, str]]:
    env = clean_server_environment()
    if task.tool == "server":
        python = server_python(root)
        if task.argv[0] == "python":
            argv = [str(python), *task.argv[1:]]
        else:
            executable = python.parent / (task.argv[0] + (".exe" if os.name == "nt" else ""))
            argv = [str(executable), *task.argv[1:]]
    elif task.tool == "gradle":
        java = jdk21(root)
        if not java:
            raise ValueError("JDK 21 is required")
        env["JAVA_HOME"] = str(java)
        env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")
        wrapper = str(root / "android" / ("gradlew.bat" if os.name == "nt" else "gradlew"))
        # A persistent daemon would outlive the wrapper and retain our owned job lock.
        argv = [wrapper, *task.argv[1:], "--max-workers=1", "--no-parallel", "--console=plain", "--no-daemon"]
    else:
        argv = [sys.executable, "-m", "lessons_console.actions", task.argv[0], str(root)]
    return argv, root / task.cwd, env
