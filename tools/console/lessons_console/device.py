"""Strict parsers and guarded device operations behind an injected command boundary."""

import hashlib
import json
import os
import re
import shutil
import socket
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

from lessons_console.deploy import Refusal
from lessons_console.environment import jdk21

PACKAGE = re.compile(r"[A-Za-z][A-Za-z0-9_]*(?:\.[A-Za-z][A-Za-z0-9_]*)+")
SERIAL = re.compile(r"[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")


def serial(value: str) -> str:
    if not isinstance(value, str) or not SERIAL.fullmatch(value):
        raise Refusal("Select one valid online device serial.")
    return value


def package(value: str) -> str:
    if not isinstance(value, str) or not PACKAGE.fullmatch(value):
        raise Refusal("APK application id is malformed.")
    return value


def devices(output: str) -> list[str]:
    lines = output.replace("\r", "").splitlines()
    if not lines or lines[0].strip() != "List of devices attached":
        raise Refusal("Device discovery returned an unsupported answer.")
    seen = set()
    online = []
    for line in lines[1:]:
        if not line.strip():
            continue
        fields = line.split()
        if len(fields) < 2 or fields[1] not in {"device", "offline", "unauthorized", "no"}:
            raise Refusal("Device discovery contained an unsupported state.")
        item = serial(fields[0])
        if item in seen:
            raise Refusal("Device discovery repeated a serial.")
        seen.add(item)
        if fields[1] == "device":
            online.append(item)
    return online


@dataclass(frozen=True)
class Candidate:
    metadata: str
    apk: Path
    package: str
    version: int
    digest: str

    def parameters(self) -> dict[str, str]:
        return {"metadata": self.metadata, "package": self.package,
                "version": str(self.version), "sha256": self.digest}


def candidate(root: Path, relative: str) -> Candidate:
    base = (root / "android/app/build/outputs/apk").resolve()
    supplied = root / relative
    metadata = supplied.resolve()
    if (Path(relative).is_absolute() or metadata.name != "output-metadata.json"
            or not metadata.is_relative_to(base) or metadata != Path(os.path.abspath(supplied))):
        raise Refusal("Select recognized AGP output metadata inside this checkout.")
    try:
        data = json.loads(metadata.read_text(encoding="utf-8"))
        elements = data["elements"]
        if data["artifactType"]["type"] != "APK" or len(elements) != 1:
            raise Refusal("Only one unambiguous standalone AGP APK is supported.")
        element = elements[0]
        output = element["outputFile"]
        version = element["versionCode"]
        if (element.get("filters") or not isinstance(output, str)
                or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk", output)
                or not isinstance(version, int) or isinstance(version, bool)
                or not 1 <= version <= 2100000000):
            raise Refusal("AGP APK output or version is ambiguous.")
        apk = (metadata.parent / output).resolve()
        if apk.parent != metadata.parent or not apk.is_file():
            raise Refusal("AGP APK output is missing or escapes its directory.")
        application = package(data["applicationId"])
        digest = hashlib.sha256(apk.read_bytes()).hexdigest()
    except (OSError, KeyError, TypeError, ValueError) as error:
        if isinstance(error, Refusal):
            raise
        raise Refusal("Could not read a recognized AGP APK output.") from None
    return Candidate(metadata.relative_to(root.resolve()).as_posix(), apk, application, version, digest)


def candidates(root: Path) -> list[Candidate]:
    result = []
    for path in sorted((root / "android/app/build/outputs/apk").rglob("output-metadata.json")):
        try:
            result.append(candidate(root, path.relative_to(root).as_posix()))
        except Refusal:
            continue
    return result


def certificate(output: str) -> str:
    identities = set()
    for line in output.replace("\r", "").splitlines():
        if line.startswith("Source Stamp Signer"):
            continue
        if "certificate SHA-256 digest:" not in line:
            continue
        match = re.fullmatch(r"(?:Signer #[1-9][0-9]*|V[1-4] Signer:?) certificate SHA-256 digest: ([0-9a-fA-F]{64})", line)
        if not match:
            raise Refusal("APK verifier returned an unsupported certificate identity.")
        identities.add(match.group(1).lower())
    if len(identities) != 1:
        raise Refusal("APK must have exactly one verified signing identity.")
    return identities.pop()


@dataclass(frozen=True)
class Answer:
    code: int
    output: str
    diagnostic: bool = False


def discover_tools(root: Path) -> tuple[str | None, str | None, dict[str, str]]:
    env = dict(os.environ)
    sdk = env.get("ANDROID_HOME") or env.get("ANDROID_SDK_ROOT")
    adb = shutil.which("adb")
    if not adb and sdk:
        found = Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if found.is_file():
            adb = str(found)
    signer = shutil.which("apksigner")
    if not signer and sdk:
        found = sorted((Path(sdk) / "build-tools").glob("*/" + ("apksigner.bat" if os.name == "nt" else "apksigner")),
                       key=lambda path: tuple(int(part) if part.isdigit() else 0 for part in re.split(r"[.-]", path.parent.name)))
        signer = str(found[-1]) if found else None
    java = jdk21(root)
    if java:
        env["JAVA_HOME"] = str(java)
        env["PATH"] = str(java / "bin") + os.pathsep + env.get("PATH", "")
    else:
        signer = None
    return adb, signer, env


class Commands:
    def __init__(self, root: Path):
        self.adb, self.signer, self.env = discover_tools(root)

    def __call__(self, argv: list[str]) -> Answer:
        executable = self.adb if argv[0] == "adb" else self.signer if argv[0] == "apksigner" else None
        if not executable:
            raise Refusal("Required adb or apksigner/JDK 21 is unavailable.")
        arguments = argv[1:]
        if argv[0] == "adb":
            # Never start/reset the shared daemon as a side effect of a console job.
            self.bridge_ready()
            arguments = ["-H", "127.0.0.1", "-P", "5037", *arguments]
        try:
            result = subprocess.run([executable, *arguments], capture_output=True, text=True,
                                    encoding="utf-8", errors="replace", timeout=30, env=self.env, check=False)
        except (OSError, subprocess.TimeoutExpired):
            raise Refusal("Device command could not complete; no fallback was attempted.") from None
        if len(result.stdout) > 1_048_576 or len(result.stderr) > 1_048_576:
            raise Refusal("Device command exceeded its diagnostic output limit.")
        return Answer(result.returncode, result.stdout, bool(result.stderr.strip()))

    def bridge_ready(self) -> None:
        if any(self.env.get(name) for name in (
            "ADB_SERVER_SOCKET", "ANDROID_ADB_SERVER_PORT", "ADB_SERVER_PORT", "ANDROID_ADB_SERVER_ADDRESS"
        )):
            raise Refusal("Custom adb server settings are unsupported here; use the existing local bridge on port 5037.")
        def receive(stream, count):
            data = b""
            while len(data) < count:
                part = stream.recv(count - len(data))
                if not part:
                    raise Refusal("The local Android bridge did not answer its version probe.")
                data += part
            return data
        try:
            with socket.create_connection(("127.0.0.1", 5037), timeout=2) as stream:
                stream.sendall(b"000chost:version")
                if receive(stream, 4) != b"OKAY" or receive(stream, 4) != b"0004":
                    raise Refusal("The local Android bridge is unavailable.")
                server_version = int(receive(stream, 4), 16)
            local = subprocess.run([self.adb, "version"], capture_output=True, text=True,
                                   encoding="utf-8", errors="replace", timeout=5, env=self.env, check=False)
            match = re.search(r"^Android Debug Bridge version 1\.0\.(\d+)$", local.stdout, re.MULTILINE)
            if local.returncode or not match or int(match.group(1)) != server_version:
                raise Refusal("The running Android bridge and adb tool versions differ; align them outside the console.")
        except Refusal:
            raise
        except (OSError, ValueError, subprocess.TimeoutExpired):
            raise Refusal("Start Android Studio or run adb start-server, then Refresh; the console never starts or resets the bridge.") from None


class Device:
    def __init__(self, root: Path, command=None):
        self.root = root
        self.command = command or Commands(root)

    def checked(self, argv: list[str]) -> str:
        result = self.command(argv)
        if result.code:
            raise Refusal("Device command failed; no destructive fallback was attempted.")
        return result.output

    def online(self) -> list[str]:
        return devices(self.checked(["adb", "devices", "-l"]))

    def selected(self, selected: str) -> list[str]:
        selected = serial(selected)
        if selected not in self.online():
            raise Refusal("The selected device is no longer online.")
        return ["adb", "-s", selected]

    def installed(self, prefix: list[str], application: str) -> tuple[int, str] | None:
        probe = self.command([*prefix, "shell", "pm", "list", "packages", application])
        if probe.code or probe.diagnostic:
            raise Refusal("Installed-package discovery failed; absence was not established.")
        output = probe.output
        rows = output.strip().splitlines()
        if any(not re.fullmatch(r"package:" + PACKAGE.pattern, row) for row in rows):
            raise Refusal("Installed-package discovery was inconclusive.")
        if "package:" + application not in rows:
            return None
        paths = self.checked([*prefix, "shell", "pm", "path", application]).strip().splitlines()
        bases = [line.removeprefix("package:") for line in paths if line.startswith("package:") and line.endswith("/base.apk")]
        if len(bases) != 1 or any(not line.startswith("package:/") for line in paths):
            raise Refusal("Installed base APK could not be identified unambiguously.")
        base = bases[0]
        if not re.fullmatch(r"/[A-Za-z0-9_./=+@~-]+/base\.apk", base) or "/../" in base:
            raise Refusal("Installed base APK path is unsupported.")
        info = self.checked([*prefix, "shell", "dumpsys", "package", application])
        versions = set(re.findall(r"\bversionCode=(\d+)\b", info))
        if len(versions) != 1:
            raise Refusal("Installed package version could not be established.")
        return int(versions.pop()), base

    def install(self, selected: str, expected: Candidate, execute: bool = True) -> dict[str, str]:
        prefix = self.selected(selected)
        current = candidate(self.root, expected.metadata)
        if current != expected:
            raise Refusal("The selected APK or metadata changed; select it again.")
        with tempfile.TemporaryDirectory(prefix="lessons-install-") as directory:
            staged = Path(directory) / "candidate.apk"
            shutil.copyfile(current.apk, staged)
            if hashlib.sha256(staged.read_bytes()).hexdigest() != expected.digest:
                raise Refusal("The candidate changed while its snapshot was copied.")
            identity = certificate(self.checked(["apksigner", "verify", "--print-certs", str(staged)]))
            installed = self.installed(prefix, current.package)
            if installed is not None:
                version, base = installed
                if current.version < version:
                    raise Refusal("Refusing an APK version downgrade.")
                previous = Path(directory) / "installed.apk"
                self.checked([*prefix, "pull", base, str(previous)])
                if not previous.is_file():
                    raise Refusal("Installed base APK pull did not produce a file.")
                old_identity = certificate(self.checked(["apksigner", "verify", "--print-certs", str(previous)]))
                if old_identity != identity:
                    raise Refusal("Refusing an update signed by a different certificate.")
            if not execute:
                return {"status": "eligible", "package": current.package, "version": str(current.version)}
            # Install the verified private snapshot, never the mutable AGP source path.
            if hashlib.sha256(staged.read_bytes()).hexdigest() != expected.digest:
                raise Refusal("The verified snapshot changed before installation.")
            self.selected(selected)
            answer = self.checked([*prefix, "install", "-r", str(staged)])
            if answer.strip() != "Success" and not answer.rstrip().endswith("\nSuccess"):
                raise Refusal("Installation did not report success.")
            after = self.installed(prefix, current.package)
            if after is None or after[0] != current.version:
                raise Refusal("Installed package version readback does not match the snapshot.")
            return {"status": "installed", "package": current.package, "version": str(current.version)}

    def reverse(self, selected: str, remove: bool) -> dict[str, str]:
        prefix = self.selected(selected)
        def mappings():
            output = self.checked([*prefix, "reverse", "--list"])
            pairs = []
            for line in output.strip().splitlines():
                fields = line.split()
                if len(fields) != 3:
                    raise Refusal("Reverse mapping readback is malformed.")
                pairs.append(tuple(fields[-2:]))
            return [pair for pair in pairs if pair[0] == "tcp:8000"]
        before = mappings()
        if before and before != [("tcp:8000", "tcp:8000")]:
            raise Refusal("Port 8000 already maps somewhere else; no mapping was changed.")
        if remove and not before:
            return {"status": "not mapped", "port": "8000"}
        if remove:
            self.checked([*prefix, "reverse", "--remove", "tcp:8000"])
        else:
            self.checked([*prefix, "reverse", "tcp:8000", "tcp:8000"])
        matches = mappings()
        if (remove and matches) or (not remove and matches != [("tcp:8000", "tcp:8000")]):
            raise Refusal("Reverse mapping readback does not match the requested change.")
        return {"status": "removed" if remove else "mapped", "port": "8000"}

    def pid(self, selected: str, application: str) -> tuple[list[str], str]:
        prefix = self.selected(selected)
        answer = self.command([*prefix, "shell", "pidof", package(application)])
        value = answer.output.strip()
        if answer.code or not re.fullmatch(r"[1-9][0-9]{0,9}", value):
            raise Refusal("Start one app process before requesting its filtered logcat.")
        return prefix, value
