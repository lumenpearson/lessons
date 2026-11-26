"""Six local tabs; all subprocess work flows through the closed task runner."""

import asyncio
import os
from pathlib import Path
from typing import ClassVar

from textual.app import App, ComposeResult
from textual.containers import Horizontal, VerticalScroll
from textual.screen import ModalScreen
from textual.widgets import (
    Button,
    Footer,
    Header,
    Input,
    Label,
    Markdown,
    RichLog,
    Select,
    Static,
    TabbedContent,
    TabPane,
)

from lessons_console.deploy import Refusal, origin
from lessons_console.device import candidates
from lessons_console.environment import (
    emulator_occupants,
    environment_policy,
    repository,
    setting_status,
    tools_summary,
)
from lessons_console.panels import DeployPanel, DevicePanel
from lessons_console.runner import Runner
from lessons_console.tasks import BATCHES, BY_KEY, TASKS, bind, disabled_reason


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
    #deploy-origin { width: 1fr; }
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
        self._device_epoch = 0
        self._deploy_epoch = 0
        self._operations: list[asyncio.Task] = []
        self._device_run = None
        self._device_busy = False
        self._deploy_run = None
        self._candidates = {}

    def compose(self) -> ComposeResult:
        yield Header()
        yield Static(
            "One heavy console job runs at a time, with live logs and a visible queue. "
            "Host execution / transport selectors: 6c. "
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
            with TabPane("Device", id="device"):
                yield DevicePanel()
            with TabPane("Deploy", id="deploy"):
                try:
                    configured = origin(os.environ.get("LESSONS_PRODUCTION_ORIGIN", ""))
                except Refusal:
                    configured = ""
                yield DeployPanel(configured)
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
        elif event.select.id in {"device-serial", "device-apk"}:
            self._device_epoch += 1
            if self._device_run and not self._device_run.finished.is_set():
                self._device_run.cancel.set()
            if self.is_mounted:
                self.query_one("#device-status", Static).update("Selection changed. Check the selected device/APK before installing.")

    def on_input_changed(self, event: Input.Changed) -> None:
        if event.input.id == "deploy-origin":
            self._deploy_epoch += 1
            if self._deploy_run and not self._deploy_run.finished.is_set():
                self._deploy_run.cancel.set()
            if self.is_mounted:
                self.query_one("#production-status", Static).update("Production condition: origin changed; check again.")
                self.query_one("#checkout-status", Static).update("Ready for this checkout: not checked.")

    async def on_button_pressed(self, event: Button.Pressed) -> None:
        identifier = event.button.id or ""
        if identifier == "cancel":
            self.action_cancel()
        elif identifier == "refresh":
            await self.action_refresh()
        elif identifier.startswith("device-"):
            self._operations.append(asyncio.create_task(self.device_action(identifier)))
        elif identifier == "deploy-warmup":
            self._operations.append(asyncio.create_task(self.deploy_action()))
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

    async def device_action(self, identifier: str) -> None:
        if identifier == "device-stop":
            if self._device_run and not self._device_run.finished.is_set():
                self._device_run.cancel.set()
            else:
                self._device_epoch += 1
                self.query_one("#device-status", Static).update("No device process is running.")
            return
        if self._device_busy:
            self.notify("Stop the current device operation first.", severity="warning")
            return
        self._device_busy = True
        epoch = self._device_epoch
        try:
            if identifier == "device-refresh":
                found = await asyncio.to_thread(candidates, self.root)
                if epoch != self._device_epoch:
                    return
                operation = bind("device.refresh", {})
            else:
                selected = self.query_one("#device-serial", Select).value
                if not isinstance(selected, str):
                    raise Refusal("Refresh and select an online device first.")
                values = {"serial": selected}
                if identifier in {"device-check", "device-install", "device-logcat"}:
                    metadata = self.query_one("#device-apk", Select).value
                    chosen = self._candidates.get(metadata) if isinstance(metadata, str) else None
                    if chosen is None:
                        raise Refusal("Refresh and select one recognized AGP APK first.")
                    values.update({"package": chosen.package} if identifier == "device-logcat" else chosen.parameters())
                operation = bind(identifier.replace("device-", "device.", 1).replace("reverse-", "reverse."), values)
            # Inputs remain a coherent visible snapshot during check/install.
            for selector in ("#device-serial", "#device-apk"):
                self.query_one(selector, Select).disabled = True
            self._device_run = self.runner.submit(operation)
            owned = self._device_run
            self.query_one("#device-status", Static).update(operation.name + "…")
            await owned.finished.wait()
            if not self.is_mounted or epoch != self._device_epoch:
                return
            if owned.state != "passed" or owned.result is None:
                self.query_one("#device-status", Static).update("Device operation " + owned.state + ". See its bounded diagnostic output.")
                return
            if identifier == "device-refresh":
                self._candidates = {item.metadata: item for item in found}
                self.query_one("#device-apk", Select).set_options([
                    (f"{item.package} · {item.version} · {item.metadata}", item.metadata) for item in found])
                online = owned.result.get("devices", [])
                if not isinstance(online, list) or any(not isinstance(item, str) for item in online):
                    raise Refusal("Device discovery returned an unsupported result.")
                self.query_one("#device-serial", Select).set_options([(item, item) for item in online])
                self.query_one("#device-status", Static).update(f"{len(online)} online device(s), {len(self._candidates)} recognized APK output(s). Select both for install or logcat.")
            else:
                self.query_one("#device-status", Static).update(operation.name + ": " + owned.result.get("status", "complete"))
        except (Refusal, OSError) as error:
            self.query_one("#device-status", Static).update(str(error) if isinstance(error, Refusal) else "Local APK outputs could not be read.")
        finally:
            self._device_busy = False
            if self.is_mounted:
                for selector in ("#device-serial", "#device-apk"):
                    self.query_one(selector, Select).disabled = False

    async def deploy_action(self) -> None:
        if self._deploy_run and not self._deploy_run.finished.is_set():
            self.notify("Wait for the current warmup read or cancel it first.", severity="warning")
            return
        epoch = self._deploy_epoch
        try:
            selected = self.query_one("#deploy-origin", Input).value
            operation = bind("deploy.warmup", {"origin": selected})
            self._deploy_run = self.runner.submit(operation)
            owned = self._deploy_run
            await owned.finished.wait()
            if not self.is_mounted or epoch != self._deploy_epoch:
                return
            if owned.state != "passed" or owned.result is None:
                self.query_one("#production-status", Static).update("Production condition: unavailable; see the bounded diagnostic output.")
                self.query_one("#checkout-status", Static).update("Ready for this checkout: unknown.")
                return
            data = owned.result
            self.query_one("#production-status", Static).update(
                "Production condition: " + data["status"] + "; schema: " + data.get("schema", "not returned")
                + "; production expected schema: " + data.get("expected_schema", "not returned"))
            self.query_one("#checkout-status", Static).update(
                "Ready for this checkout: " + data["compatibility"] + "; checkout expected schema: " + data["checkout_expected"])
        except Refusal as error:
            self.query_one("#production-status", Static).update(str(error))
            self.query_one("#checkout-status", Static).update("Ready for this checkout: not checked.")

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
        for tab in ("Build", "Contract", "Device", "Deploy"):
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
        self._device_epoch += 1
        self._deploy_epoch += 1
        for run in self.runner.runs:
            if not run.finished.is_set():
                run.cancel.set()

    async def action_quit(self) -> None:
        self.action_cancel()
        await asyncio.gather(*self._batches, return_exceptions=True)
        await asyncio.gather(*self._operations, return_exceptions=True)
        await self.runner.close()
        self.exit()

    async def on_unmount(self) -> None:
        self.action_cancel()
        await asyncio.gather(*self._batches, return_exceptions=True)
        await asyncio.gather(*self._operations, return_exceptions=True)
        await self.runner.close()


def main() -> None:
    Console().run()
