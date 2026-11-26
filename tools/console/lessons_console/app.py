"""Four local tabs; all subprocess work flows through the closed task runner."""

import asyncio
from pathlib import Path
from typing import ClassVar

from textual.app import App, ComposeResult
from textual.containers import Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import (
    Button,
    Footer,
    Header,
    Label,
    Markdown,
    RichLog,
    Select,
    Static,
    TabbedContent,
    TabPane,
)

from lessons_console.environment import (
    emulator_occupants,
    environment_policy,
    repository,
    setting_status,
    tools_summary,
)
from lessons_console.runner import Runner
from lessons_console.tasks import BATCHES, BY_KEY, TASKS, disabled_reason


class Admission(ModalScreen[bool]):
    def __init__(self, occupants: list[str]):
        super().__init__()
        self.occupants = occupants

    def compose(self) -> ComposeResult:
        with VerticalScroll(id="admission"):
            yield Label("An Android emulator is running")
            yield Static(
                "Detected: " + ", ".join(self.occupants) + ". Starting this build or test "
                "beside it may slow both down or exhaust memory. Start the selected work anyway?"
            )
            with Horizontal():
                yield Button("Start alongside emulator", id="admit", variant="warning")
                yield Button("Cancel", id="decline")

    def on_button_pressed(self, event: Button.Pressed) -> None:
        self.dismiss(event.button.id == "admit")


class Console(App):
    TITLE = "Lessons console — local developer tools"
    BINDINGS: ClassVar = [("q", "quit", "Quit safely"), ("r", "refresh", "Refresh"), ("c", "cancel", "Cancel active jobs")]
    CSS = """
    #boundary { height: auto; padding: 1; color: $warning; }
    Horizontal { height: 3; }
    Select { width: 1fr; }
    RichLog { height: 1fr; min-height: 8; }
    #queue { height: auto; max-height: 8; }
    #admission { width: 75%; height: auto; max-height: 80%; border: thick $warning; padding: 2; background: $surface; }
    Admission { align: center middle; background: $background 70%; }
    #environment-info { height: auto; padding: 1; }
    #settings { height: auto; padding: 1; }
    """

    def __init__(self, root: Path | None = None, runner: Runner | None = None,
                 emulator_detector=emulator_occupants):
        super().__init__()
        self.root = root or repository()
        self.runner = runner or Runner(self.root)
        self.runner.changed = self.render_runs
        self.emulator_detector = emulator_detector
        self._rendered: tuple = ()
        self._batches: list[asyncio.Task] = []
        self._cancel_epoch = 0

    def compose(self) -> ComposeResult:
        yield Header()
        yield Static(
            "One heavy console job runs at a time, with live logs and a visible queue. "
            "Host execution / transport selectors: 6c. Device / Deploy: 6b. "
            "Local represented gates do not establish complete CI, signed-device or production acceptance.",
            id="boundary",
        )
        yield Static("No console jobs. The machine may have external occupants.", id="queue")
        with TabbedContent():
            for tab in ("Build", "Contract"):
                with TabPane(tab, id=tab.lower()):
                    with Horizontal():
                        yield Select([(task.name, task.key) for task in TASKS if task.tab == tab],
                                     allow_blank=False, id=tab.lower() + "-task")
                        yield Button("Run", id="run-" + tab.lower(), variant="primary")
                    if tab == "Build":
                        with Horizontal():
                            yield Button("Server gates", id="batch-server")
                            yield Button("Android gates", id="batch-android")
                            yield Button("Cancel jobs", id="cancel")
                        yield Select(self.history_options(), value="current", allow_blank=False,
                                     id="history")
                    else:
                        yield Button("Contract gates", id="batch-contract")
                    yield RichLog(markup=False, highlight=False, wrap=True, id=tab.lower() + "-output")
            with TabPane("Environment", id="environment"), VerticalScroll():
                yield Button("Refresh tool discovery", id="refresh")
                yield Static("Discovering tools…", id="environment-info", markup=False)
                yield Static("", id="settings", markup=False)
                yield Static("", id="policy", markup=False)
            with TabPane("Docs", id="docs"):
                documents = [self.root / "CLAUDE.md", self.root / "HANDOVER.md"]
                documents.extend(sorted((self.root / "docs").rglob("*.md")))
                yield Select([(str(path.relative_to(self.root)), str(path.relative_to(self.root)))
                              for path in documents if path.is_file()],
                             allow_blank=False, id="document")
                with VerticalScroll():
                    yield Markdown("Select a document.", id="reader")
        yield Footer()

    async def on_mount(self) -> None:
        await self.action_refresh()
        self.show_document("CLAUDE.md")

    async def action_refresh(self) -> None:
        # Discovery can spawn only the checkout interpreter probe; keep it off Textual's loop.
        try:
            summary = await asyncio.to_thread(tools_summary, self.root)
            statuses = await asyncio.to_thread(setting_status, self.root)
            self.query_one("#environment-info", Static).update(summary)
            self.query_one("#settings", Static).update("Settings (names only):\n" + "\n".join(
                name + (": set" if present else ": missing") for name, present in statuses.items()))
            self.query_one("#policy", Static).update(await asyncio.to_thread(environment_policy, self.root))
        except Exception as error:  # noqa: BLE001 -- the UI boundary reports only exception types.
            self.query_one("#environment-info", Static).update("Discovery failed: " + type(error).__name__)

    def show_document(self, relative: str) -> None:
        path = (self.root / relative).resolve()
        if path.is_relative_to(self.root.resolve()) and path.is_file():
            self.query_one("#reader", Markdown).update(path.read_text(encoding="utf-8"))

    def history_options(self) -> list[tuple[str, str]]:
        logs = sorted((self.root / "tools/console/.runs").glob("*.log"), reverse=True)[:100]
        return [("Current runs", "current"), *((path.name, path.name) for path in logs)]

    def show_history(self, name: str) -> None:
        if name == "current":
            self._rendered = ()
            self.render_runs()
            return
        directory = (self.root / "tools/console/.runs").resolve()
        path = (directory / name).resolve()
        if path.parent == directory and path.is_file():
            log = self.query_one("#build-output", RichLog)
            log.clear()
            # Read history through redaction again if this session knows more secrets.
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
                log.write(self.runner.redactor(line))

    def on_select_changed(self, event: Select.Changed) -> None:
        if event.select.id == "document" and isinstance(event.value, str):
            self.show_document(event.value)
        elif event.select.id == "history" and isinstance(event.value, str):
            self.show_history(event.value)

    async def on_button_pressed(self, event: Button.Pressed) -> None:
        identifier = event.button.id or ""
        if identifier == "cancel":
            self.action_cancel()
        elif identifier == "refresh":
            await self.action_refresh()
        elif identifier.startswith("run-"):
            tab = identifier.removeprefix("run-")
            key = self.query_one("#" + tab + "-task", Select).value
            if isinstance(key, str):
                await self.request_start((key,))
        elif identifier.startswith("batch-"):
            await self.request_start(BATCHES[identifier.removeprefix("batch-") + ".all"])

    async def request_start(self, keys: tuple[str, ...]) -> None:
        if any(BY_KEY[key].heavy for key in keys):
            try:
                occupants = await asyncio.to_thread(self.emulator_detector)
            except (OSError, RuntimeError, TimeoutError):
                occupants = []
                self.notify("Emulator detection unavailable; check whether an emulator is running.", severity="warning")
            if occupants:
                self.push_screen(Admission(occupants), lambda admitted: self.start(keys) if admitted else None)
                return
        self.start(keys)

    def start(self, keys: tuple[str, ...]) -> None:
        epoch = self._cancel_epoch
        async def execute_batch() -> None:
            failed = False
            for key in keys:
                if epoch != self._cancel_epoch:
                    break
                task = BY_KEY[key]
                if failed and not task.always:
                    continue
                reason = await asyncio.to_thread(disabled_reason, task, self.root)
                if epoch != self._cancel_epoch:
                    break
                if reason:
                    self.notify(reason, severity="warning", timeout=10)
                    failed = True
                    continue
                run = self.runner.submit(task)
                await run.finished.wait()
                if run.state == "cancelled":
                    break
                if run.state != "passed":
                    failed = True
        self._batches.append(asyncio.create_task(execute_batch()))

    def render_runs(self) -> None:
        if not self.is_mounted:
            return
        self.query_one("#queue", Static).update("\n".join(
            f"{run.task.name}: {run.state}" for run in self.runner.runs[-8:]
        ) or "No console jobs. External occupants remain unknown.")
        fingerprint = tuple((id(run), run.state, len(run.lines), run.lines[-1:] and run.lines[-1])
                            for run in self.runner.runs)
        if fingerprint == self._rendered:
            return
        self._rendered = fingerprint
        for tab in ("Build", "Contract"):
            if tab == "Build" and self.query_one("#history", Select).value != "current":
                continue
            log = self.query_one("#" + tab.lower() + "-output", RichLog)
            log.clear()
            for run in self.runner.runs:
                if run.task.tab == tab:
                    log.write(f"{run.task.name}: {run.state}" + (f" — {run.path}" if run.path else ""))
                    for line in run.lines:
                        log.write(line)

    def action_cancel(self) -> None:
        self._cancel_epoch += 1
        for run in self.runner.runs:
            if not run.finished.is_set():
                run.cancel.set()

    async def action_quit(self) -> None:
        self.action_cancel()
        await asyncio.gather(*self._batches, return_exceptions=True)
        await self.runner.close()
        self.exit()

    async def on_unmount(self) -> None:
        self.action_cancel()
        await asyncio.gather(*self._batches, return_exceptions=True)
        await self.runner.close()


def main() -> None:
    Console().run()
