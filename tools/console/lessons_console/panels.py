"""Device and deployment surfaces; operations remain in the closed task registry."""

from textual.app import ComposeResult
from textual.containers import Horizontal, Vertical
from textual.widgets import Button, Input, RichLog, Select, Static


class DevicePanel(Vertical):
    def compose(self) -> ComposeResult:
        yield Static("Refresh online devices and this checkout's AGP APK outputs. Start Android Studio or adb start-server first.")
        with Horizontal():
            yield Select([], prompt="Select an online device", id="device-serial")
            yield Select([], prompt="Select an AGP APK", id="device-apk")
            yield Button("Refresh", id="device-refresh")
        with Horizontal():
            yield Button("Check install", id="device-check")
            yield Button("Install APK", id="device-install", variant="warning")
            yield Button("App logcat", id="device-logcat")
            yield Button("Stop", id="device-stop")
        with Horizontal():
            yield Button("Map tcp:8000", id="device-reverse-add")
            yield Button("Remove tcp:8000", id="device-reverse-remove")
        yield Static("No device operation requested. Installation preserves data; there is no uninstall or downgrade control.", id="device-status", markup=False)
        yield RichLog(markup=False, highlight=False, wrap=True, id="device-output")


class DeployPanel(Vertical):
    def __init__(self, configured: str):
        super().__init__()
        self.configured = configured

    def compose(self) -> ComposeResult:
        yield Static("Read-only production diagnostic. Configure LESSONS_PRODUCTION_ORIGIN or enter an HTTPS origin. No deployment is performed.")
        with Horizontal():
            yield Input(value=self.configured, placeholder="https://your-deployment.example", id="deploy-origin")
            yield Button("Read warmup", id="deploy-warmup")
        yield Static("Production condition: not checked.", id="production-status", markup=False)
        yield Static("Ready for this checkout: not checked.", id="checkout-status", markup=False)
        yield RichLog(markup=False, highlight=False, wrap=True, id="deploy-output")
