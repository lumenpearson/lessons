import io
import json
from pathlib import Path

import pytest

from lessons_console.deploy import Refusal
from lessons_console.device import Answer, Commands, Device, candidate, certificate, devices
from lessons_console.operations import logcat, operate, parameters
from lessons_console.tasks import bind, resolve

APP = "com.example.lessons"
CERT = "a" * 64


@pytest.fixture
def apk(tmp_path):
    output = tmp_path / "android/app/build/outputs/apk/release"
    output.mkdir(parents=True)
    (output / "app.apk").write_bytes(b"verified-candidate")
    metadata = output / "output-metadata.json"
    metadata.write_text(json.dumps({"artifactType": {"type": "APK"}, "applicationId": APP,
                                   "elements": [{"outputFile": "app.apk", "versionCode": 8, "filters": []}]}))
    return candidate(tmp_path, metadata.relative_to(tmp_path).as_posix())


class FakeCommands:
    def __init__(self, installed=True, version=7, old_certificate=CERT, fail_probe=False):
        self.calls = []
        self.exists = installed
        self.version = version
        self.old_certificate = old_certificate
        self.fail_probe = fail_probe
        self.installed_bytes = None
        self.mappings = []
        self.adb = "fake-adb"
        self.env = {}

    def bridge_ready(self):
        return None

    def __call__(self, argv):
        self.calls.append(argv)
        if argv[:3] == ["adb", "devices", "-l"]:
            return Answer(0, "List of devices attached\nfixture-device device model:fake\noffline-device offline\n")
        if argv[0] == "apksigner":
            identity = self.old_certificate if argv[-1].endswith("installed.apk") else CERT
            return Answer(0, "V2 Signer: certificate SHA-256 digest: " + identity + "\n")
        assert argv[:3] == ["adb", "-s", "fixture-device"]
        rest = argv[3:]
        if rest[:3] == ["shell", "pm", "list"]:
            return Answer(1 if self.fail_probe else 0, "package:" + APP if self.exists else "")
        if rest[:3] == ["shell", "pm", "path"]:
            return Answer(0, "package:/data/app/com.example/base.apk\n")
        if rest[:3] == ["shell", "dumpsys", "package"]:
            return Answer(0, f"versionCode={self.version} minSdk=26")
        if rest[0] == "pull":
            Path(rest[-1]).write_bytes(b"installed-base")
            return Answer(0, "pulled")
        if rest[:2] == ["install", "-r"]:
            self.installed_bytes = Path(rest[-1]).read_bytes()
            self.exists, self.version = True, 8
            return Answer(0, "Performing Streamed Install\nSuccess\n")
        if rest == ["reverse", "--list"]:
            return Answer(0, "\n".join("fixture-device " + " ".join(pair) for pair in self.mappings))
        if rest == ["reverse", "tcp:8000", "tcp:8000"]:
            self.mappings.append(("tcp:8000", "tcp:8000"))
            return Answer(0, "")
        if rest == ["reverse", "--remove", "tcp:8000"]:
            self.mappings = [pair for pair in self.mappings if pair[0] != "tcp:8000"]
            return Answer(0, "")
        raise AssertionError(rest)


def test_online_devices_are_explicit_unique_serials():
    assert devices("List of devices attached\nonline device model:fake\noffline offline\nlocked unauthorized\n") == ["online"]
    with pytest.raises(Refusal):
        devices("List of devices attached\nrepeated device\nrepeated device\n")
    with pytest.raises(Refusal):
        parameters("device.reverse.add", {"serial": "-s other; bad"})


def test_agp_candidate_refuses_escaping_and_ambiguous_output(tmp_path, apk):
    metadata = tmp_path / apk.metadata
    data = json.loads(metadata.read_text())
    data["elements"][0]["outputFile"] = "../outside.apk"
    metadata.write_text(json.dumps(data))
    with pytest.raises(Refusal):
        candidate(tmp_path, apk.metadata)
    data["elements"][0]["outputFile"] = "app.apk"
    data["elements"].append(dict(data["elements"][0]))
    metadata.write_text(json.dumps(data))
    with pytest.raises(Refusal):
        candidate(tmp_path, apk.metadata)


def test_certificate_schemes_retain_one_identity_and_exclude_stamp():
    output = (f"Signer #1 certificate SHA-256 digest: {CERT}\n"
              f"V2 Signer: certificate SHA-256 digest: {CERT}\n"
              f"Source Stamp Signer: certificate SHA-256 digest: {'b' * 64}\n")
    assert certificate(output) == CERT
    with pytest.raises(Refusal):
        certificate(output + f"Signer #2 certificate SHA-256 digest: {'b' * 64}\n")


def test_install_uses_verified_snapshot_even_if_source_changes(tmp_path, apk):
    commands = FakeCommands()
    def command(argv):
        answer = commands(argv)
        if argv[0] == "apksigner" and argv[-1].endswith("candidate.apk"):
            apk.apk.write_bytes(b"changed-source-after-verification")
        return answer
    result = Device(tmp_path, command).install("fixture-device", apk)
    assert result["status"] == "installed"
    assert commands.installed_bytes == b"verified-candidate"
    installs = [argv for argv in commands.calls if "install" in argv]
    assert len(installs) == 1 and installs[0][3:5] == ["install", "-r"]
    assert all(not {"uninstall", "clear", "-d", "--remove-all"} & set(argv) for argv in commands.calls)


def test_install_refuses_signer_downgrade_and_inconclusive_probe(tmp_path, apk):
    for commands in (FakeCommands(old_certificate="b" * 64), FakeCommands(version=9), FakeCommands(fail_probe=True)):
        with pytest.raises(Refusal):
            Device(tmp_path, commands).install("fixture-device", apk)
        assert not any("install" in argv for argv in commands.calls)
    absent = FakeCommands(installed=False)
    assert Device(tmp_path, absent).install("fixture-device", apk)["status"] == "installed"


def test_bound_candidate_change_cannot_become_a_late_install(tmp_path, apk):
    commands = FakeCommands()
    values = {"serial": "fixture-device", **apk.parameters()}
    apk.apk.write_bytes(b"replacement")
    with pytest.raises(Refusal, match="changed"):
        operate("device.install", tmp_path, values, device=Device(tmp_path, commands))
    assert commands.calls == []


def test_reverse_preserves_other_ports_and_refuses_conflicting_8000(tmp_path):
    commands = FakeCommands()
    commands.mappings = [("tcp:9000", "tcp:9000")]
    device = Device(tmp_path, commands)
    assert device.reverse("fixture-device", False)["status"] == "mapped"
    assert device.reverse("fixture-device", True)["status"] == "removed"
    assert commands.mappings == [("tcp:9000", "tcp:9000")]
    commands.mappings.append(("tcp:8000", "tcp:9999"))
    for remove in (False, True):
        with pytest.raises(Refusal, match="somewhere else"):
            device.reverse("fixture-device", remove)
    assert commands.mappings[-1] == ("tcp:8000", "tcp:9999")


def test_custom_adb_server_is_refused_before_any_probe(monkeypatch):
    commands = object.__new__(Commands)
    commands.env = {"ADB_SERVER_SOCKET": "tcp:other-host:9999"}
    monkeypatch.setattr("lessons_console.device.socket.create_connection", lambda *args, **kwargs: pytest.fail("real probe forbidden"))
    with pytest.raises(Refusal, match="Custom adb"):
        commands.bridge_ready()


def test_logcat_is_pid_scoped_and_stops_on_changed_process(tmp_path, monkeypatch):
    commands = FakeCommands()
    device = Device(tmp_path, commands)
    answers = iter([(["adb", "-s", "fixture-device"], "123"), (["adb", "-s", "fixture-device"], "456")])
    monkeypatch.setattr(device, "pid", lambda *args: next(answers))
    class Process:
        stdout = io.BytesIO(b"only-selected-pid\n")
        returncode = None
        def poll(self):
            return self.returncode
        def kill(self):
            self.returncode = 1
        def wait(self):
            return self.returncode
    process = Process()
    seen = []
    monkeypatch.setattr("lessons_console.operations.subprocess.Popen", lambda argv, **kwargs: seen.append(argv) or process)
    clock = iter([0, 2])
    monkeypatch.setattr("lessons_console.operations.time.monotonic", lambda: next(clock))
    with pytest.raises(Refusal, match="process changed"):
        logcat(device, "fixture-device", APP)
    assert "--pid=123" in seen[0] and seen[0][5:7] == ["-s", "fixture-device"]
    assert process.returncode == 1


def test_operations_accept_only_closed_structured_parameters(tmp_path):
    with pytest.raises(Refusal):
        parameters("device.reverse.add", {"serial": "fixture-device", "argv": "uninstall"})
    with pytest.raises(Refusal):
        parameters("device.logcat", {"serial": "fixture-device", "package": "bad package"})
    task = bind("device.reverse.add", {"serial": "fixture-device"})
    argv, _, _ = resolve(task, tmp_path)
    assert argv[-3:-1] == ["device.reverse.add", str(tmp_path)]
    assert json.loads(argv[-1]) == {"serial": "fixture-device"}
