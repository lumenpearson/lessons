"""Async visible job scheduling around subprocesses; no shell command entry."""

import asyncio
import os
import signal
import subprocess
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

from lessons_console.locking import MachineLock, WindowsJob
from lessons_console.redaction import Redactor
from lessons_console.tasks import Task, resolve


@dataclass
class Run:
    task: Task
    state: str = "queued"
    lines: list[str] = field(default_factory=list)
    path: Path | None = None
    cancel: asyncio.Event = field(default_factory=asyncio.Event)
    finished: asyncio.Event = field(default_factory=asyncio.Event)
    worker: asyncio.Task | None = field(default=None, repr=False)


class Runner:
    def __init__(self, root: Path, changed: Callable[[], None] = lambda: None,
                 redactor: Redactor | None = None, lock_path: Path | None = None,
                 resolver=resolve):
        self.root = root
        self.changed = changed
        self.redactor = redactor or Redactor()
        self.lock_path = lock_path
        self.resolver = resolver
        self.runs: list[Run] = []
        self._heavy = asyncio.Lock()

    def submit(self, task: Task) -> Run:
        run = Run(task)
        self.runs.append(run)
        run.worker = asyncio.create_task(self._execute(run))
        self.changed()
        return run

    def emit(self, run: Run, line: str, stream) -> None:
        safe = self.redactor(line)
        run.lines.append(safe)
        # Keep display memory bounded; the complete sanitized transcript remains on disk.
        if len(run.lines) > 2000:
            del run.lines[:1000]
        stream.write(safe + "\n")
        stream.flush()
        self.changed()

    async def _execute(self, run: Run) -> None:
        lock = MachineLock(self.lock_path)
        local_held = False
        process = None
        windows = None
        attached = False
        reader = None
        spawn = None
        try:
            if run.task.heavy:
                while self._heavy.locked():
                    if run.cancel.is_set():
                        return
                    await asyncio.sleep(0.05)
                await self._heavy.acquire()
                local_held = True
                while not lock.acquire(run.task.name):
                    if run.cancel.is_set():
                        return
                    await asyncio.sleep(0.1)
            if run.cancel.is_set():
                return
            run.state = "running"
            directory = self.root / "tools/console/.runs"
            directory.mkdir(parents=True, exist_ok=True)
            stamp = datetime.now(UTC).strftime("%Y%m%dT%H%M%S.%fZ")
            run.path = directory / f"{stamp}-{run.task.key}.log"
            argv, cwd, env = self.resolver(run.task, self.root)
            with run.path.open("w", encoding="utf-8") as stream:
                self.emit(run, "Task: " + run.task.name, stream)
                if self.redactor.short_values:
                    self.emit(run, "Some secrets are shorter than eight characters; value matching cannot redact them.", stream)
                if run.task.tool == "server":
                    from lessons_console.environment import server_python
                    python = server_python(self.root)
                    for module in ("ruff", "mypy"):
                        version = await asyncio.to_thread(subprocess.run,
                            [str(python), "-m", module, "--version"], cwd=cwd, env=env,
                            capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=15)
                        self.emit(run, version.stdout.strip() or f"{module}: unavailable", stream)
                kwargs = {"creationflags": 0x4 | subprocess.CREATE_NEW_PROCESS_GROUP} if os.name == "nt" else {"start_new_session": True}
                if os.name == "nt":
                    windows = WindowsJob()
                # Shield creation so cancellation cannot lose the handle returned by
                # the worker thread after it has already created a suspended child.
                spawn = asyncio.create_task(asyncio.to_thread(
                    subprocess.Popen, argv, cwd=cwd, env=env, stdout=subprocess.PIPE,
                    stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, **kwargs))
                process = await asyncio.shield(spawn)
                if windows:
                    try:
                        windows.attach_and_resume(process)
                        attached = True
                    except Exception:
                        process.kill()
                        await asyncio.to_thread(process.wait)
                        raise
                self.changed()

                async def consume() -> None:
                    assert process.stdout
                    while raw := await asyncio.to_thread(process.stdout.readline):
                        self.emit(run, raw.decode("utf-8", errors="replace").rstrip("\r\n"), stream)

                reader = asyncio.create_task(consume())
                while process.poll() is None:
                    if reader.done():
                        reader.result()
                    if run.cancel.is_set():
                        self._terminate(process, windows)
                        break
                    await asyncio.sleep(0.05)
                await asyncio.to_thread(process.wait)
                # Descendants remain occupants even after their launcher exits.
                if windows:
                    while windows.active():
                        if reader.done():
                            reader.result()
                        if run.cancel.is_set():
                            windows.terminate()
                        await asyncio.sleep(0.05)
                else:
                    while self._group_exists(process.pid):
                        if reader.done():
                            reader.result()
                        if run.cancel.is_set():
                            self._terminate(process, None)
                        await asyncio.sleep(0.05)
                await asyncio.shield(reader)
                if run.cancel.is_set():
                    run.state = "cancelled"
                elif process.returncode:
                    run.state = "failed"
                elif run.task.key == "android.build" and not all(
                    list((self.root / f"android/app/build/outputs/apk/{variant}").glob("*.apk"))
                    for variant in ("debug", "release")
                ):
                    self.emit(run, "Build reported success but one or both APK outputs are missing.", stream)
                    run.state = "failed"
                else:
                    run.state = "passed"
                self.emit(run, "Result: " + run.state, stream)
        except asyncio.CancelledError:
            run.cancel.set()
            run.state = "cancelled"
            raise
        except Exception as error:  # noqa: BLE001 -- owned processes must be cleaned up on every failure.
            run.state = "failed"
            # Arbitrary exception strings may include subprocess arguments or secrets.
            run.lines.append("Runner failed: " + type(error).__name__)
        finally:
            async def cleanup() -> None:
                nonlocal process
                if process is None and spawn is not None:
                    try:
                        process = await spawn
                    except Exception as error:  # noqa: BLE001 -- failed creation has no child to reclaim.
                        run.lines.append("Process creation failed: " + type(error).__name__)
                if process is not None:
                    if windows and not attached and process.poll() is None:
                        process.kill()
                    if run.state in {"failed", "cancelled"} or run.cancel.is_set():
                        # The launcher may already have exited while its child lives.
                        self._terminate(process, windows)
                    await asyncio.to_thread(process.wait)
                    while windows.active() if windows else self._group_exists(process.pid):
                        if run.cancel.is_set():
                            self._terminate(process, windows)
                        await asyncio.sleep(0.05)
                if reader is not None:
                    await asyncio.gather(reader, return_exceptions=True)
                if windows:
                    windows.close()
                if process is not None and process.stdout is not None:
                    process.stdout.close()

            # Neither coroutine cancellation nor a second cancellation may hand the
            # machine lock back before the known tree and its output reader finish.
            drain = asyncio.create_task(cleanup())
            while not drain.done():
                try:
                    await asyncio.shield(drain)
                except asyncio.CancelledError:
                    run.cancel.set()
                    run.state = "cancelled"
            drain.result()
            if run.state == "queued":
                run.state = "cancelled"
            lock.release()
            if local_held:
                self._heavy.release()
            run.finished.set()
            self.changed()

    @staticmethod
    def _group_exists(pid: int) -> bool:
        if Path("/proc").is_dir():
            # Orphan zombies can await an unrelated init reaper after cancellation;
            # they cannot run or consume the protected resource.
            for entry in Path("/proc").iterdir():
                if not entry.name.isdigit():
                    continue
                try:
                    fields = (entry / "stat").read_text().rsplit(")", 1)[1].split()
                    if int(fields[2]) == pid and fields[0] != "Z":
                        return True
                except (OSError, ValueError, IndexError):
                    continue
            return False
        try:
            os.killpg(pid, 0)
            return True
        except ProcessLookupError:
            return False

    @staticmethod
    def _terminate(process, windows: WindowsJob | None) -> None:
        if windows:
            windows.terminate()
        else:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass

    async def close(self) -> None:
        for run in self.runs:
            if not run.finished.is_set():
                run.cancel.set()
        await asyncio.gather(*(run.worker for run in self.runs if run.worker), return_exceptions=True)
