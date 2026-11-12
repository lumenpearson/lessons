"""Canonical samples keep every Android REST DTO on the server's contract (#273)."""

import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from app.rpc.methods import METHODS

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "server/scripts/write_v2_goldens.py"


@pytest.fixture
def writer():
    assert SCRIPT.is_file(), "The canonical v2 fixture writer is missing"
    spec = importlib.util.spec_from_file_location("write_v2_goldens", SCRIPT)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def snapshot(root):
    return {p.relative_to(root).as_posix(): p.read_bytes() for p in root.rglob("*") if p.is_file()}


def golden_folder(root):
    return root / "server/tests/golden/v2"


def index(root):
    return json.loads((golden_folder(root) / "index.json").read_text(encoding="utf-8"))


def test_comparison_on_a_fresh_tree_refuses_missing_samples_without_writing(writer, tmp_path):
    assert writer.main([], root=tmp_path) == 1
    assert snapshot(tmp_path) == {}


def test_explicit_update_is_repeatable_and_the_default_is_read_only(writer, tmp_path):
    assert writer.main(["--update"], root=tmp_path) == 0
    first = snapshot(tmp_path)
    assert writer.main(["--update"], root=tmp_path) == 0
    assert snapshot(tmp_path) == first
    assert writer.main([], root=tmp_path) == 0
    assert snapshot(tmp_path) == first


def test_credentials_cover_the_contract_and_linked_devices_use_the_device_bearer(writer, tmp_path):
    writer.main(["--update"], root=tmp_path)
    credentials = json.loads(
        (golden_folder(tmp_path) / "credentials.json").read_text(encoding="utf-8")
    )

    assert set(credentials) == set(METHODS)
    assert len(credentials) == 76
    assert {
        credentials[key] for key, method in METHODS.items() if method.auth.name == "DEVICE_LINKED"
    } == {"device"}
    assert credentials["lessons.v2.DeviceService/CreateDevice"] == "none"
    assert credentials["lessons.v2.ScheduleService/GetScheduleWindow"] == "device"
    methods = index(tmp_path)["methods"]
    assert sum(row["rest"] for row in methods) == 75
    watch = next(row for row in methods if row["method"] == "lessons.v2.WatchService/WatchClass")
    assert watch["rest"] is False and watch["samples"] == []


@pytest.mark.parametrize("change", ["fixture", "method", "oneof"])
def test_comparison_refuses_a_changed_fixture_or_incomplete_inventory_without_repair(
    writer, tmp_path, change
):
    writer.main(["--update"], root=tmp_path)
    folder = golden_folder(tmp_path)
    if change == "fixture":
        path = folder / "DeviceService.CreateDevice.request.json"
        path.write_text('{"code":"changed"}\n', encoding="utf-8")
    else:
        data = index(tmp_path)
        if change == "method":
            data["methods"].pop()
        else:
            row = next(
                row for row in data["methods"] if row["method"].endswith("/CreateDiarySession")
            )
            row["samples"] = [
                sample for sample in row["samples"] if sample["kind"] != "oneof:netschool"
            ]
        (folder / "index.json").write_text(json.dumps(data), encoding="utf-8")
    before = snapshot(tmp_path)

    assert writer.main([], root=tmp_path) == 1
    assert snapshot(tmp_path) == before


def test_nested_descriptor_growth_cannot_hide_behind_an_old_sample(writer, tmp_path, monkeypatch):
    writer.main(["--update"], root=tmp_path)
    folder = golden_folder(tmp_path)
    row = next(
        row for row in index(tmp_path)["methods"] if row["method"].endswith("/GetScheduleWindow")
    )
    samples = [
        json.loads((folder / sample["response"]).read_text(encoding="utf-8"))
        for sample in row["samples"]
    ]
    original = writer.fields

    def with_future_field(desc):
        current = original(desc)
        if desc.type_name == "lessons.v2.ClassSummary":
            current += [
                SimpleNamespace(
                    json_name="futureField",
                    name="future_field",
                    value=current[1].value,
                    proto=current[1].proto,
                )
            ]
        return current

    monkeypatch.setattr(writer, "fields", with_future_field)
    errors = writer.coverage_errors(METHODS[row["method"]].output.desc(), samples)

    assert "window.schoolClass.futureField" in errors


def test_a_missing_oneof_alternative_fails_recursive_sample_coverage(writer, tmp_path):
    writer.main(["--update"], root=tmp_path)
    folder = golden_folder(tmp_path)
    row = next(
        row for row in index(tmp_path)["methods"] if row["method"].endswith("/CreateDiarySession")
    )
    samples = [
        json.loads((folder / sample["request"]).read_text(encoding="utf-8"))
        for sample in row["samples"]
        if sample["kind"] != "oneof:netschool"
    ]

    assert "netschool" in writer.coverage_errors(METHODS[row["method"]].input.desc(), samples)


def test_checked_in_samples_match_the_current_runtime_and_descriptors(writer):
    assert writer.main([], root=ROOT) == 0
