import json
import shutil
from types import SimpleNamespace

import pytest
import yaml

from lessons_console.actions import compare_tree
from lessons_console.environment import (
    buf_release,
    jdk21,
    server_problem,
    server_python,
    setting_status,
    settings,
)
from lessons_console.parity import validate_ci
from lessons_console.redaction import PUBLIC_FIELDS, SECRET_FIELDS, Redactor, Secret
from lessons_console.tasks import BY_KEY, TASKS, resolve


def test_ci_inventory_and_gate_equivalence(root):
    validate_ci(root)


def test_ci_refuses_an_unreviewed_action_or_command(root, tmp_path):
    target = tmp_path / ".github/workflows"
    target.mkdir(parents=True)
    content = (root / ".github/workflows/ci.yml").read_text()
    (target / "ci.yml").write_text(content.replace("ruff check app", "ruff check changed"))
    with pytest.raises(AssertionError, match="inventory changed"):
        validate_ci(tmp_path)


def test_ci_refuses_job_condition_drift(root, tmp_path):
    target = tmp_path / ".github/workflows"
    target.mkdir(parents=True)
    content = (root / ".github/workflows/ci.yml").read_text(encoding="utf-8")
    assert "if: needs.changes.outputs.android == 'true'" in content
    (target / "ci.yml").write_text(
        content.replace("if: needs.changes.outputs.android == 'true'", "if: false"),
        encoding="utf-8")
    with pytest.raises(AssertionError, match="inventory changed"):
        validate_ci(tmp_path)


def test_settings_classification_covers_every_field(root):
    assert set(settings(root)) == SECRET_FIELDS | PUBLIC_FIELDS
    assert not SECRET_FIELDS & PUBLIC_FIELDS


def test_secret_forms_and_unknown_headers_are_redacted():
    redact = Redactor({"TOKEN": "long/value@secret", "DATABASE_URL": "postgres://name:encoded%40password@db/data"})
    output = redact("long/value@secret long%2Fvalue%40secret encoded@password Authorization: Bearer unknown-token X-Cron-Secret: another-token")
    assert "long" not in output
    assert "password" not in output
    assert "unknown-token" not in output
    assert "another-token" not in output
    assert repr(Secret("sensitive")) == "<redacted>"


def test_short_secret_warning_and_url_password_shape():
    redact = Redactor({"API_KEY": "short"})
    assert redact.short_values
    assert redact("https://person:unseenpassword@host") == "https://person:<redacted>@host"


def test_environment_names_only_and_real_settings(root, tmp_path):
    (tmp_path / "server/app").mkdir(parents=True)
    shutil.copy(root / "server/app/config.py", tmp_path / "server/app/config.py")
    (tmp_path / "server/.env").write_text("BOT_TOKEN=do-not-display\nexport CRON_SECRET=other-value\n")
    result = setting_status(tmp_path, {"DADATA_TOKEN": "private", "TIMEZONE": ""})
    assert result["BOT_TOKEN"] and result["CRON_SECRET"] and result["DADATA_TOKEN"]
    assert not result["TIMEZONE"]
    assert "private" not in json.dumps(result)
    assert "do-not-display" not in json.dumps(result)


def test_jdk_requires_release_version_not_directory_name(tmp_path):
    impostor = tmp_path / "jdk-21-impostor"
    impostor.mkdir()
    (impostor / "release").write_text('JAVA_VERSION="25.0.1"')
    (tmp_path / "android/.gradle").mkdir(parents=True)
    (tmp_path / "android/.gradle/config.properties").write_text("java.home=" + str(impostor))
    assert jdk21(tmp_path, {"JAVA_HOME": str(impostor)}) != impostor
    (impostor / "release").write_text('JAVA_VERSION="21.0.9"')
    assert jdk21(tmp_path, {"JAVA_HOME": str(impostor)}) == impostor


def test_buf_pin_and_checksum_are_owned_by_ci(root):
    version, url, digest = buf_release(root, windows=True)
    assert f"/v{version}/buf-Windows-x86_64.exe" in url
    assert len(digest) == 64


def test_buf_refuses_drift(root, tmp_path):
    (tmp_path / ".github/workflows").mkdir(parents=True)
    (tmp_path / "docs").mkdir()
    shutil.copy(root / ".github/workflows/ci.yml", tmp_path / ".github/workflows/ci.yml")
    content = (root / "docs/build.md").read_text(encoding="utf-8")
    (tmp_path / "docs/build.md").write_text(content.replace("buf-Linux-x86_64`", "buf-Linux-changed`"), encoding="utf-8")
    with pytest.raises(ValueError):
        buf_release(tmp_path, windows=False)


def test_freshness_normalizes_crlf_but_reports_missing_or_changed_files(tmp_path):
    left, right = tmp_path / "left", tmp_path / "right"
    left.mkdir()
    right.mkdir()
    (left / "code.py").write_bytes(b"first\nsecond\n")
    (right / "code.py").write_bytes(b"first\r\nsecond\r\n")
    (right / "__pycache__").mkdir()
    (right / "__pycache__/ignored").write_bytes(b"ignored")
    compare_tree(left, right)
    (right / "code.py").write_bytes(b"changed")
    with pytest.raises(ValueError, match="stale"):
        compare_tree(left, right)


def test_registry_has_no_freeform_shell_or_property_dump(root):
    assert len(BY_KEY) == len(TASKS)
    assert all("--debug" not in task.argv and "properties" not in task.argv for task in TASKS)
    argv, cwd, env = resolve(BY_KEY["server.types"], root)
    assert argv[0].startswith(str(root / "server/.venv"))
    assert argv[1:] == ["-m", "mypy"]
    assert cwd == root / "server" and "VIRTUAL_ENV" not in env


def test_workflow_yaml_on_key_is_accounted_for(root):
    data = yaml.safe_load((root / ".github/workflows/ci.yml").read_text())
    assert "on" in data or True in data


def test_server_probe_refuses_editable_install_from_another_checkout(tmp_path, monkeypatch):
    python = server_python(tmp_path)
    python.parent.mkdir(parents=True)
    python.touch()
    monkeypatch.setattr("lessons_console.environment.subprocess.run", lambda *args, **kwargs:
                        SimpleNamespace(returncode=0, stdout=str(tmp_path / "foreign/app/__init__.py")))
    assert "does not import this checkout" in server_problem(tmp_path)


def test_console_ci_filter_covers_every_external_reader(root):
    import fnmatch
    import re
    workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    script = next(step["run"] for step in workflow["jobs"]["changes"]["steps"] if step.get("id") == "filter")
    arms = re.findall(r"^\s*(\S+)\)\s+console=true\s*;;", script, re.MULTILINE)
    assert len(arms) == 1
    inputs = ("tools/console/lessons_console/new_module.py", ".github/workflows/ci.yml",
              ".github/dependabot.yml", "docs/build.md", "server/app/config.py",
              "server/.env.example", "android/app/build.gradle.kts", ".gitignore")
    assert all(any(fnmatch.fnmatchcase(path, pattern) for pattern in arms[0].split("|"))
               for path in inputs)
    assert 'echo "console=true"' in script
    assert "console" in workflow["jobs"]["ci"]["needs"]


def test_console_gradle_isolates_daemon_lifetime(root, tmp_path, monkeypatch):
    monkeypatch.setattr("lessons_console.tasks.jdk21", lambda root: tmp_path)
    argv, _, _ = resolve(BY_KEY["android.build"], root)
    assert "--no-daemon" in argv
