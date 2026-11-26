"""Closed device/deploy orchestration; command output is never an API to the UI."""

import json
import queue
import re
import subprocess
import sys
import threading
import time
from pathlib import Path

from lessons_console.deploy import Refusal, origin, warmup
from lessons_console.device import Device, candidate, package, serial

FIELDS = {
    "device.refresh": frozenset(),
    "device.check": frozenset({"serial", "metadata", "package", "version", "sha256"}),
    "device.install": frozenset({"serial", "metadata", "package", "version", "sha256"}),
    "device.logcat": frozenset({"serial", "package"}),
    "device.reverse.add": frozenset({"serial"}),
    "device.reverse.remove": frozenset({"serial"}),
    "deploy.warmup": frozenset({"origin"}),
}


def parameters(key: str, values: dict) -> tuple[tuple[str, str], ...]:
    if key not in FIELDS or not isinstance(values, dict) or set(values) != FIELDS[key]:
        raise Refusal("This console operation has missing or unsupported parameters.")
    if any(not isinstance(value, str) or len(value) > 1024 for value in values.values()):
        raise Refusal("This console operation has malformed parameters.")
    if "serial" in values:
        serial(values["serial"])
    if "package" in values:
        package(values["package"])
    if "sha256" in values and not re.fullmatch(r"[0-9a-f]{64}", values["sha256"]):
        raise Refusal("The selected APK digest is malformed.")
    if "version" in values and not re.fullmatch(r"[1-9][0-9]{0,9}", values["version"]):
        raise Refusal("The selected APK version is malformed.")
    if "metadata" in values and (Path(values["metadata"]).is_absolute()
            or ".." in Path(values["metadata"]).parts or "\\" in values["metadata"]
            or not values["metadata"].startswith("android/app/build/outputs/apk/")
            or not values["metadata"].endswith("/output-metadata.json")):
        raise Refusal("The selected AGP output metadata path is malformed.")
    result = dict(values)
    if "origin" in result:
        result["origin"] = origin(result["origin"])
    return tuple(sorted(result.items()))


def logcat(device: Device, selected: str, application: str) -> None:
    prefix, pid = device.pid(selected, application)
    commands = device.command
    commands.bridge_ready()
    process = subprocess.Popen(
        [commands.adb, "-H", "127.0.0.1", "-P", "5037", *prefix[1:], "logcat", f"--pid={pid}", "-v", "brief", "-T", "1"],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=commands.env,
    )
    messages: queue.Queue[bytes] = queue.Queue(maxsize=200)
    stop = threading.Event()

    def read() -> None:
        assert process.stdout
        for line in iter(process.stdout.readline, b""):
            while not stop.is_set():
                try:
                    messages.put(line, timeout=0.1)
                    break
                except queue.Full:
                    continue
    reader = threading.Thread(target=read, daemon=True)
    reader.start()
    checked = time.monotonic()
    try:
        while process.poll() is None:
            try:
                print(messages.get(timeout=0.2).decode("utf-8", errors="replace").rstrip("\r\n"), flush=True)
            except queue.Empty:
                pass
            if time.monotonic() - checked >= 1:
                _, current_pid = device.pid(selected, application)
                if current_pid != pid:
                    raise Refusal("Logcat stopped because the selected app process changed.")
                checked = time.monotonic()
        if process.returncode:
            raise Refusal("Filtered logcat stopped; no device-wide fallback was attempted.")
    finally:
        stop.set()
        if process.poll() is None:
            process.kill()
        process.wait()
        reader.join(timeout=1)
        if process.stdout:
            process.stdout.close()


def operate(key: str, root: Path, supplied: dict, device=None, fetch=None) -> dict:
    values = dict(parameters(key, supplied))
    if key == "deploy.warmup":
        return warmup(root, values["origin"], **({"fetch": fetch} if fetch else {}))
    target = device or Device(root)
    if key == "device.refresh":
        return {"devices": target.online()}
    selected = values["serial"]
    if key in {"device.check", "device.install"}:
        chosen = candidate(root, values["metadata"])
        if any(chosen.parameters()[name] != values[name] for name in chosen.parameters()):
            raise Refusal("The selected APK or metadata changed; refresh and select it again.")
        return target.install(selected, chosen, execute=key == "device.install")
    if key.startswith("device.reverse."):
        return target.reverse(selected, remove=key.endswith("remove"))
    if key == "device.logcat":
        logcat(target, selected, values["package"])
        return {"status": "stopped"}
    raise Refusal("Unknown console operation.")


if __name__ == "__main__":
    try:
        result = operate(sys.argv[1], Path(sys.argv[2]), json.loads(sys.argv[3]))
        print("LESSONS_RESULT " + json.dumps(result), flush=True)
    except Refusal as error:
        print(str(error), flush=True)
        raise SystemExit(1) from None
    except Exception as error:  # noqa: BLE001 -- never expose arbitrary device/HTTP errors.
        print("Device/deploy operation failed: " + type(error).__name__, flush=True)
        raise SystemExit(1) from None
