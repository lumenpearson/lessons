import asyncio
import os
import sys
from dataclasses import replace

from textual.widgets import Input, Select, Static, TabbedContent, TabPane

from lessons_console.app import Console
from lessons_console.redaction import Redactor
from lessons_console.runner import Runner
from lessons_console.tasks import BY_KEY


async def test_volatile_logcat_never_opens_a_transcript_and_clears_memory(tmp_path):
    runner = Runner(tmp_path, redactor=Redactor({"TOKEN": "private-test-token"}),
                    resolver=lambda task, root: ([sys.executable, "-u", "-c",
                    "import time; print('Authorization: Bearer private-test-token'); time.sleep(60)"],
                    root, dict(os.environ)))
    task = replace(BY_KEY["device.logcat"], tool="fake")
    run = runner.submit(task)
    try:
        async with asyncio.timeout(5):
            while not any("Authorization" in line for line in run.lines):
                await asyncio.sleep(0.02)
        assert "private-test-token" not in "\n".join(run.lines)
        assert run.path is None and not (tmp_path / "tools/console/.runs").exists()
        run.cancel.set()
        await asyncio.wait_for(run.finished.wait(), 5)
        assert run.lines == [] and run.result is None
        assert not (tmp_path / "tools/console/.runs").exists()
    finally:
        await runner.close()


async def test_six_tab_device_refresh_and_stale_origin_reply_are_owned(root, monkeypatch):
    monkeypatch.setattr("lessons_console.app.tools_summary", lambda root: "Fake tools")
    monkeypatch.setattr("lessons_console.app.candidates", lambda root: [])
    release = asyncio.Event()
    class FakeRunner(Runner):
        async def _execute(self, run):
            run.state = "running"
            self.changed()
            if run.task.key == "deploy.warmup":
                await release.wait()
                run.result = {"status": "ok", "schema": "old-schema", "compatibility": "ready", "checkout_expected": "old-schema"}
            else:
                run.result = {"devices": ["fixture-device"]}
            run.state = "passed"
            run.finished.set()
            self.changed()
    runner = FakeRunner(root)
    app = Console(root, runner, emulator_detector=list)
    async with app.run_test(size=(120, 45)) as pilot:
        try:
            assert {pane.id for pane in app.query(TabPane)} == {"build", "contract", "device", "deploy", "environment", "docs"}
            async def click_in_tab(tab, control):
                await pilot.click("#--content-tab-" + tab)
                async with asyncio.timeout(4):
                    while (app.query_one(TabbedContent).active != tab
                           or not app.query_one(control).region.width):
                        await pilot.pause(0.01)
                    await pilot.pause()
                assert await pilot.click(control)
            await click_in_tab("device", "#device-refresh")
            async with asyncio.timeout(4):
                while not runner.runs or not runner.runs[-1].finished.is_set():
                    await pilot.pause(0.02)
            await pilot.pause()
            selection = app.query_one("#device-serial", Select)
            selection.value = "fixture-device"
            await pilot.pause()
            assert selection.value == "fixture-device"
            app.query_one("#deploy-origin", Input).value = "https://old.example"
            await pilot.pause()
            await click_in_tab("deploy", "#deploy-warmup")
            async with asyncio.timeout(4):
                while len(runner.runs) < 2:
                    await pilot.pause(0.02)
            app.query_one("#deploy-origin", Input).value = "https://new.example"
            await pilot.pause()
            release.set()
            await runner.runs[-1].finished.wait()
            await pilot.pause()
            status = str(app.query_one("#production-status", Static).render())
            assert "old-schema" not in status and "origin changed" in status
            assert dict(runner.runs[-1].task.parameters)["origin"] == "https://old.example"
        finally:
            release.set()
