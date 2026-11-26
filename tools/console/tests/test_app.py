import asyncio
import os
import sys
import time

from textual.widgets import Select, Static, TabbedContent

from lessons_console.app import Admission, Console
from lessons_console.runner import Runner


async def test_four_tabs_and_fake_light_task(root, tmp_path, monkeypatch):
    monkeypatch.setattr("lessons_console.app.disabled_reason", lambda task, root: None)
    monkeypatch.setattr("lessons_console.app.tools_summary", lambda root: "Fake tools available")
    runner = Runner(tmp_path, lock_path=tmp_path / "lock", resolver=lambda task, root:
                    ([sys.executable, "-c", "print('fake-console-result')"], root, dict(os.environ)))
    app = Console(root, runner)
    async with app.run_test(size=(120, 42)) as pilot:
        assert "One heavy console job" in str(app.query_one("#boundary", Static).render())
        await pilot.click("#--content-tab-contract")
        async with asyncio.timeout(3):
            while (app.query_one(TabbedContent).active != "contract"
                   or not app.query_one("#run-contract").region.width):
                await pilot.pause(0.01)
            await pilot.pause()
        assert await pilot.click("#run-contract")
        async with asyncio.timeout(8):
            while not runner.runs or not runner.runs[-1].finished.is_set():
                await pilot.pause(0.05)
        assert runner.runs[-1].state == "passed"
        assert "fake-console-result" in runner.runs[-1].lines
        await pilot.click("#--content-tab-environment")
        assert "Fake tools" in str(app.query_one("#environment-info", Static).render())
        await pilot.click("#--content-tab-docs")
        assert app.query_one("#document", Select).value == "CLAUDE.md"


async def test_detected_emulator_prompts_and_cancel_keeps_idle(root, monkeypatch):
    monkeypatch.setattr("lessons_console.app.tools_summary", lambda root: "Fake tools")

    def delayed_detector():
        time.sleep(0.1)
        return ["emulator-fixture"]

    app = Console(root, emulator_detector=delayed_detector)
    async with app.run_test(size=(120, 42)) as pilot:
        await pilot.click("#run-build")
        # Dispatch does not await detector completion or the modal's layout.
        async with asyncio.timeout(3):
            while True:
                if isinstance(app.screen, Admission):
                    cancel_buttons = app.screen.query("#decline")
                    if cancel_buttons and cancel_buttons.first().region.width:
                        break
                await pilot.pause(0.01)
        assert isinstance(app.screen, Admission)
        await pilot.click("#decline")
        assert not app.runner.runs


async def test_idle_heavy_job_starts_without_modal(root, tmp_path, monkeypatch):
    monkeypatch.setattr("lessons_console.app.disabled_reason", lambda task, root: None)
    monkeypatch.setattr("lessons_console.app.tools_summary", lambda root: "Fake tools")
    runner = Runner(tmp_path, lock_path=tmp_path / "lock", resolver=lambda task, root:
                    ([sys.executable, "-c", "print('idle heavy ran')"], root, dict(os.environ)))
    app = Console(root, runner, emulator_detector=list)
    async with app.run_test(size=(120, 42)) as pilot:
        await pilot.click("#run-build")
        async with asyncio.timeout(8):
            while not runner.runs or not runner.runs[-1].finished.is_set():
                await pilot.pause(0.05)
        assert not isinstance(app.screen, Admission)
        assert runner.runs[-1].state == "passed"


async def test_android_batch_still_checks_detekt_after_build_failure(root, tmp_path, monkeypatch):
    monkeypatch.setattr("lessons_console.app.disabled_reason", lambda task, root: None)
    monkeypatch.setattr("lessons_console.app.tools_summary", lambda root: "Fake tools")
    runner = Runner(tmp_path, lock_path=tmp_path / "lock", resolver=lambda task, root:
                    ([sys.executable, "-c", "raise SystemExit(1)" if task.key == "android.build" else "print('detekt checked')"],
                     root, dict(os.environ)))
    app = Console(root, runner)
    async with app.run_test(size=(120, 42)) as pilot:
        from lessons_console.tasks import BATCHES
        app.start(BATCHES["android.all"])
        async with asyncio.timeout(8):
            while len(runner.runs) < 2 or not runner.runs[-1].finished.is_set():
                await pilot.pause(0.05)
        assert [(run.task.key, run.state) for run in runner.runs] == [
            ("android.build", "failed"), ("android.detekt", "passed")]
