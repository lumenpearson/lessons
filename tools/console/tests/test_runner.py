import asyncio
import os
import subprocess
import sys

from lessons_console.locking import MachineLock
from lessons_console.redaction import Redactor
from lessons_console.runner import Runner
from lessons_console.tasks import Task


def fake(key="fake", heavy=True):
    return Task(key, key, "Build", "fake", (), ".", heavy, "Fake behavior fixture")


def make_runner(tmp_path, code, redactor=None):
    def resolve(task, root):
        return [sys.executable, "-u", "-c", code[task.key]], root, dict(os.environ)
    return Runner(tmp_path, redactor=redactor, lock_path=tmp_path / "shared.lock", resolver=resolve)


async def until(predicate):
    async with asyncio.timeout(8):
        while not predicate():
            await asyncio.sleep(0.02)


async def test_second_heavy_job_queues_but_light_job_runs(tmp_path):
    code = {"first": "import time; print('first'); time.sleep(60)",
            "second": "print('second')", "light": "print('light')"}
    runner = make_runner(tmp_path, code)
    first = runner.submit(fake("first"))
    try:
        await until(lambda: "first" in first.lines)
        second = runner.submit(fake("second"))
        light = runner.submit(fake("light", heavy=False))
        await asyncio.wait_for(light.finished.wait(), 8)
        assert light.state == "passed" and second.state == "queued"
        first.cancel.set()
        await asyncio.wait_for(second.finished.wait(), 8)
        assert first.state == "cancelled" and second.state == "passed"
    finally:
        await runner.close()


async def test_redaction_happens_before_screen_and_disk(tmp_path):
    runner = make_runner(tmp_path, {"fake": "print('prefix test/secret-password suffix [brackets]')"},
                         Redactor({"BOT_TOKEN": "test/secret-password"}))
    run = runner.submit(fake())
    await asyncio.wait_for(run.finished.wait(), 8)
    assert run.state == "passed"
    assert "test/secret-password" not in "\n".join(run.lines)
    assert "test/secret-password" not in run.path.read_text()
    assert "[brackets]" in run.path.read_text()


async def test_cancel_kills_grandchild_before_lock_release(tmp_path):
    child = "import time; print('grandchild-ready',flush=True); time.sleep(60)"
    parent = f"import subprocess,sys,time; subprocess.Popen([sys.executable,'-u','-c',{child!r}]); time.sleep(60)"
    runner = make_runner(tmp_path, {"fake": parent})
    run = runner.submit(fake())
    try:
        await until(lambda: "grandchild-ready" in run.lines)
        other = MachineLock(tmp_path / "shared.lock")
        assert not other.acquire("another process")
        run.cancel.set()
        await asyncio.wait_for(run.finished.wait(), 8)
        assert run.state == "cancelled"
        assert other.acquire("after complete owned tree exit")
        other.release()
    finally:
        await runner.close()


async def test_cancelled_queued_job_never_spawns(tmp_path):
    blocker = MachineLock(tmp_path / "shared.lock")
    assert blocker.acquire("external console")
    runner = make_runner(tmp_path, {"fake": "raise AssertionError('must never spawn')"})
    run = runner.submit(fake())
    await asyncio.sleep(0.15)
    run.cancel.set()
    await asyncio.wait_for(run.finished.wait(), 8)
    assert run.state == "cancelled" and run.path is None
    blocker.release()


async def test_close_cancels_live_jobs(tmp_path):
    runner = make_runner(tmp_path, {"fake": "import time; print('ready'); time.sleep(60)"})
    run = runner.submit(fake())
    await until(lambda: "ready" in run.lines)
    await asyncio.wait_for(runner.close(), 8)
    assert run.state == "cancelled" and run.finished.is_set()


def test_dead_owner_does_not_leave_a_kernel_lock(tmp_path):
    path = tmp_path / "lock"
    code = ("from pathlib import Path; from lessons_console.locking import MachineLock; "
            f"lock=MachineLock(Path({str(path)!r})); assert lock.acquire('fake dead owner')")
    subprocess.run([sys.executable, "-c", code], check=True, timeout=8)
    lock = MachineLock(path)
    assert lock.acquire("new owner")
    lock.release()


async def test_nonzero_process_is_failed_and_retains_diagnostics(tmp_path):
    runner = make_runner(tmp_path, {"fake": "print('diagnostic'); raise SystemExit(7)"})
    run = runner.submit(fake())
    await asyncio.wait_for(run.finished.wait(), 8)
    assert run.state == "failed" and "diagnostic" in run.path.read_text()


async def test_coroutine_cancel_drains_child_after_launcher_exits(tmp_path):
    marker = tmp_path / "ticks"
    child = ("import pathlib,time; "
             f"p=pathlib.Path({str(marker)!r}); "
             "print('child-ready',flush=True); "
             "exec(\"while True:\\n p.write_text(str(time.time_ns()))\\n time.sleep(0.02)\")")
    parent = ("import os,subprocess,sys; print('group='+str(os.getpid()),flush=True); "
              f"subprocess.Popen([sys.executable,'-u','-c',{child!r}])")
    runner = make_runner(tmp_path, {"fake": parent})
    run = runner.submit(fake())
    group = None
    try:
        await until(lambda: "child-ready" in run.lines and marker.exists())
        group = int(next(line.removeprefix("group=") for line in run.lines if line.startswith("group=")))
        await asyncio.sleep(0.1)
        run.worker.cancel()
        await asyncio.wait_for(asyncio.gather(run.worker, return_exceptions=True), 3)
        assert run.finished.is_set() and run.state == "cancelled"
        contents = marker.read_text()
        await asyncio.sleep(0.1)
        assert marker.read_text() == contents
        other = MachineLock(tmp_path / "shared.lock")
        assert other.acquire("after coroutine cancellation")
        other.release()
    finally:
        if os.name != "nt" and group is not None:
            try:
                os.killpg(group, 9)
            except ProcessLookupError:
                pass
        await runner.close()


async def test_reader_disk_error_terminates_verbose_child_promptly(tmp_path):
    code = "import os; print('disk-error-trigger',flush=True); os.write(1,b'x'*2_000_000)"
    runner = make_runner(tmp_path, {"fake": code})
    original = runner.emit

    def fail_on_child_output(run, line, stream):
        if line == "disk-error-trigger":
            raise OSError("fake disk full")
        original(run, line, stream)

    runner.emit = fail_on_child_output
    run = runner.submit(fake())
    try:
        await asyncio.wait_for(run.finished.wait(), 2)
        assert run.state == "failed"
        assert "Runner failed: OSError" in run.lines
        other = MachineLock(tmp_path / "shared.lock")
        assert other.acquire("after reader failure")
        other.release()
    finally:
        await runner.close()
