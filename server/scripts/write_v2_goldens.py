"""Write or compare the canonical v2 samples consumed by Android (#273)."""

from __future__ import annotations

import argparse
import json
from pathlib import Path
from typing import Any

from protobuf import (
    DescEnum,
    DescFieldValueEnum,
    DescFieldValueList,
    DescFieldValueMessage,
    DescMessage,
)

from app.rpc.methods import METHODS

ROOT = Path(__file__).resolve().parents[2]
WELL_KNOWN = {"google.protobuf.Timestamp", "google.protobuf.FieldMask"}
UNREAD = {
    "lessons.v2.DeviceService/CreateDevice": {
        "timezone": "Session has no zone; the schedule window remains the class-zone source.",
    },
    "lessons.v2.ScheduleService/GetScheduleWindow": {
        "window.schoolClass.city": "SchoolClassInfo has no city; directory moves in 5b.",
        "window.device": "Device permissions still come from v1 GetMe until 5b.",
    },
}


def fields(desc):
    return list(desc.fields)


def _oneof(field):
    return field.proto.has_field("oneof_index") and not field.proto.proto3_optional


def _message(value):
    if isinstance(value, DescFieldValueMessage):
        return value.message
    return value if isinstance(value, DescMessage) else None


def _enum(value):
    if isinstance(value, DescFieldValueEnum):
        return value.enum
    return value if isinstance(value, DescEnum) else None


def _scalar(field):
    kind = field.proto.type.name
    if kind == "STRING":
        if "timezone" in field.name:
            return "Europe/Moscow"
        if field.name in {"date", "start_date", "end_date"}:
            return "2026-09-21"
        if field.name in {"start", "end", "start_time", "end_time"}:
            return "08:30"
        return "sample"
    if kind == "BOOL":
        return True
    if kind in {"INT64", "UINT64", "SINT64", "FIXED64", "SFIXED64"}:
        return "9007199254740993"
    if kind in {"DOUBLE", "FLOAT"}:
        return 1.25
    if kind == "BYTES":
        return "AQI="
    return 7


def _singular(value, field, mode, choices, path):
    if message := _message(value):
        return sample(message, mode=mode, choices=choices, path=path)
    if enum := _enum(value):
        known = next(
            (v.proto.name for v in enum.values if v.proto.number), enum.values[0].proto.name
        )
        return 99 if mode in {"enum99", "futureEnum"} else known
    return _scalar(field)


def sample(desc, *, mode="full", choices=None, path=""):
    """Populate wire fields, with one selected alternative per real oneof."""
    if desc.type_name == "google.protobuf.Timestamp":
        return "2026-09-21T14:13:20.005Z"
    if desc.type_name == "google.protobuf.FieldMask":
        return "shortName,school"
    choices = choices or {}
    result = {}
    selected = {}
    for field in fields(desc):
        if _oneof(field):
            group = f"{path}#{field.proto.oneof_index}"
            selected.setdefault(group, choices.get(group, field.name))
            if selected[group] != field.name:
                continue
        child_path = f"{path}{field.json_name}."
        value = field.value
        if isinstance(value, DescFieldValueList):
            result[field.json_name] = [_singular(value.element, field, mode, choices, child_path)]
            if enum := _enum(value.element):
                known = [v.proto.name for v in enum.values if v.proto.number]
                result[field.json_name] = [known[0], 99, known[-1]]
        else:
            result[field.json_name] = _singular(value, field, mode, choices, child_path)
    return result


def _future_enums(desc, value):
    if not isinstance(value, dict):
        return value
    result = dict(value)
    for field in fields(desc):
        if field.json_name not in result:
            continue
        kind = field.value
        current = result[field.json_name]
        repeated = isinstance(kind, DescFieldValueList)
        if repeated:
            kind = kind.element
        items = current if repeated else [current]
        if _enum(kind):
            items = ["A_FUTURE_ENUM_NAME" if item == 99 else item for item in items]
        elif message := _message(kind):
            items = [_future_enums(message, item) for item in items]
        result[field.json_name] = items if repeated else items[0]
    return result


def _alternatives(desc, path="", ancestors=None):
    if desc.type_name in WELL_KNOWN:
        return
    ancestors = ancestors or {}
    for field in fields(desc):
        choices = dict(ancestors)
        if _oneof(field):
            choices[f"{path}#{field.proto.oneof_index}"] = field.name
            yield f"oneof:{path}{field.name}", choices
        value = field.value
        if isinstance(value, DescFieldValueList):
            value = value.element
        if message := _message(value):
            yield from _alternatives(message, f"{path}{field.json_name}.", choices)


def coverage_errors(desc, samples, prefix=""):
    """Report any recursively unread wire field, including each oneof alternative."""
    if desc.type_name in WELL_KNOWN:
        return []
    errors = []
    for field in fields(desc):
        found = [
            sample[field.json_name]
            for sample in samples
            if isinstance(sample, dict) and field.json_name in sample
        ]
        path = prefix + field.json_name
        if not found:
            errors.append(path)
            continue
        value = field.value
        if isinstance(value, DescFieldValueList):
            value = value.element
            found = [item for array in found for item in array]
        if message := _message(value):
            errors.extend(coverage_errors(message, found, path + "."))
    return errors


def _canonical(typ, mode, choices):
    if mode == "empty":
        return json.loads(typ().to_json())
    raw = sample(typ.desc(), mode=mode, choices=choices)
    canonical = json.loads(typ.from_json(json.dumps(raw)).to_json())
    # A future server may know a name that this runtime does not know yet.
    return _future_enums(typ.desc(), canonical) if mode == "futureEnum" else canonical


def expected_files():
    files: dict[str, Any] = {}
    rows = []
    credentials = {}
    kinds = {"NONE": "none", "DEVICE": "device", "DEVICE_LINKED": "device", "DIARY": "diary"}
    for key, method in sorted(METHODS.items()):
        credential = kinds[method.auth.name]
        credentials[key] = credential
        row = {
            "method": key,
            "credential": credential,
            "rest": not method.streaming,
            "requestType": method.input.desc().type_name,
            "responseType": method.output.desc().type_name,
            "unread": UNREAD.get(key, {}),
            "samples": [],
        }
        rows.append(row)
        if method.streaming:
            continue
        variants = [("full", {}), ("empty", {}), ("enum99", {}), ("futureEnum", {})]
        variants += list(_alternatives(method.input.desc()))
        requests, responses = [], []
        stem = key.removeprefix("lessons.v2.").replace("/", ".")
        for mode, choices in variants:
            suffix = "" if mode == "full" else "." + mode.replace(":", "-")
            request_name = f"{stem}{suffix}.request.json"
            response_name = f"{stem}{suffix}.response.json"
            request = _canonical(method.input, mode, choices)
            response = _canonical(method.output, mode, {})
            files[request_name], files[response_name] = request, response
            requests.append(request)
            responses.append(response)
            row["samples"].append(
                {"kind": mode, "request": request_name, "response": response_name}
            )
        for typ, samples in ((method.input, requests), (method.output, responses)):
            if errors := coverage_errors(typ.desc(), samples):
                raise ValueError(f"{key} leaves wire fields uncovered: {errors}")
    files["index.json"] = {"methods": rows}
    files["credentials.json"] = credentials
    return {
        name: json.dumps(value, ensure_ascii=False, sort_keys=True, indent=2) + "\n"
        for name, value in files.items()
    }


def main(argv: list[str] | None = None, *, root: Path | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--update", action="store_true", help="Explicitly rewrite canonical fixture files"
    )
    args = parser.parse_args(argv)
    folder = (root or ROOT) / "server/tests/golden/v2"
    expected = expected_files()
    if args.update:
        folder.mkdir(parents=True, exist_ok=True)
        for name, content in expected.items():
            (folder / name).write_text(content, encoding="utf-8", newline="\n")
        return 0
    actual = {p.name: p.read_text(encoding="utf-8") for p in folder.glob("*.json")}
    changed = sorted(
        name for name in set(actual) | set(expected) if actual.get(name) != expected.get(name)
    )
    if changed:
        print("Canonical v2 samples differ: " + ", ".join(changed))
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
