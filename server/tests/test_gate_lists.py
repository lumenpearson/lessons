"""The declared server/Android gates agree with CI and command permissions (#659)."""

import fnmatch
import json
import re
import shlex
from pathlib import Path

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
PATHS = ("CLAUDE.md", "AGENTS.md", ".github/workflows/ci.yml", ".claude/settings.json")


def _differences(sources: dict[str, str]) -> list[str]:
    claude = _section(sources["CLAUDE.md"], "Commands")
    agents = _section(sources["AGENTS.md"], "The checks that are real")
    workflow = yaml.safe_load(sources[".github/workflows/ci.yml"])
    lists = {
        "CLAUDE.md": _commands(re.findall(r"\*\*`([^`\n]+)`\*\*", claude)),
        "AGENTS.md": _commands(re.findall(r"```[^\n]*\n(.*?)```", agents, re.S)),
        ".github/workflows/ci.yml": _commands(
            step["run"]
            for job in (workflow["jobs"]["server"], workflow["jobs"]["android"])
            for step in job["steps"]
            if isinstance(step.get("run"), str)
        ),
    }
    expected = set().union(*lists.values())
    errors = [f"{name}: no gate declarations found" for name, gates in lists.items() if not gates]
    for name, gates in lists.items():
        errors.extend(f"{name}: missing gate {gate}" for gate in sorted(expected - gates))
    allowed = json.loads(sources[".claude/settings.json"])["permissions"]["allow"]
    patterns = [
        entry[5:-1] for entry in allowed if entry.startswith("Bash(") and entry.endswith(")")
    ]
    errors.extend(
        f".claude/settings.json: no allowed prefix covers gate {gate}"
        for gate in sorted(expected)
        if not any(fnmatch.fnmatchcase(gate, pattern) for pattern in patterns)
    )
    return errors


def _section(source: str, heading: str) -> str:
    found = re.search(rf"^## {re.escape(heading)}\s*\n(.*?)(?=^## |\Z)", source, re.M | re.S)
    return found[1] if found else ""


def _commands(blocks) -> set[str]:
    commands = set()
    for block in blocks:
        for line in block.splitlines():
            for words in _shell_commands(line):
                if not words or words[0] == "set" or words[:2] == ["pip", "install"]:
                    continue
                if words[0] == "./gradlew":
                    commands.update(
                        "./gradlew " + task for task in words[1:] if not task.startswith("-")
                    )
                else:
                    commands.add(" ".join(words))
    return commands


def _shell_commands(line):
    lexer = shlex.shlex(line, posix=True, punctuation_chars=";&|")
    lexer.whitespace_split = True
    words = []
    for word in lexer:
        if word in ("&&", "||", ";", "|", "&"):
            yield words
            words = []
        else:
            words.append(word)
    yield words


@pytest.fixture
def gate_sources():
    return {name: (ROOT / name).read_text(encoding="utf-8") for name in PATHS}


def test_declared_gates_match_ci_and_allowed_prefixes(gate_sources):
    assert not (errors := _differences(gate_sources)), "\n".join(errors)


@pytest.mark.parametrize("missing", PATHS)
def test_a_missing_gate_names_the_source_that_lost_it(gate_sources, missing):
    if missing == ".claude/settings.json":
        settings = json.loads(gate_sources[missing])
        settings["permissions"]["allow"] = [
            entry
            for entry in settings["permissions"]["allow"]
            if entry not in ("Bash(pytest *)", "Bash(pytest)")
        ]
        gate_sources[missing] = json.dumps(settings)
    else:
        gate_sources[missing] = gate_sources[missing].replace("pytest -q -n auto", "", 1)
    errors = _differences(gate_sources)
    assert any(missing in error and "pytest -q -n auto" in error for error in errors), errors


@pytest.mark.parametrize(
    "separator",
    [None, "\n", " && ", "&&"],
    ids=["separate_step", "install_newline", "install_chain", "install_adjacent_chain"],
)
def test_a_new_ci_gate_cannot_be_missing_from_the_instructions(gate_sources, separator):
    ci = yaml.safe_load(gate_sources[".github/workflows/ci.yml"])
    steps = ci["jobs"]["server"]["steps"]
    if separator is not None:
        install = next(step for step in steps if step.get("name") == "Install")
        install["run"] += separator + "ruff format --check app"
    else:
        steps.append({"name": "Formatting", "run": "ruff format --check app"})
    gate_sources[".github/workflows/ci.yml"] = yaml.safe_dump(ci)
    errors = _differences(gate_sources)
    for name in ("CLAUDE.md", "AGENTS.md"):
        assert any(name in error and "ruff format --check app" in error for error in errors), errors


def test_a_new_declared_gate_cannot_be_missing_from_ci(gate_sources):
    gate_sources["CLAUDE.md"] = gate_sources["CLAUDE.md"].replace(
        "- **`./gradlew test`**", "- **`./gradlew lint`**\n- **`./gradlew test`**", 1
    )
    errors = _differences(gate_sources)
    assert any("ci.yml" in error and "./gradlew lint" in error for error in errors), errors


def test_permissions_for_narrow_development_commands_are_not_additional_ci_gates(gate_sources):
    settings = json.loads(gate_sources[".claude/settings.json"])
    settings["permissions"]["allow"] += [
        "Bash(pytest -q tests/test_schedule.py)",
        "Bash(./gradlew lint)",
    ]
    gate_sources[".claude/settings.json"] = json.dumps(settings)
    assert not (errors := _differences(gate_sources)), errors


def test_combined_gradle_tasks_mean_the_same_gates_as_separate_steps(gate_sources):
    ci = yaml.safe_load(gate_sources[".github/workflows/ci.yml"])
    steps = ci["jobs"]["android"]["steps"]
    index = next(
        i
        for i, step in enumerate(steps)
        if step.get("run") == "./gradlew test assembleDebug assembleRelease"
    )
    steps[index : index + 1] = [
        {"run": "./gradlew " + task} for task in ("test", "assembleDebug", "assembleRelease")
    ]
    gate_sources[".github/workflows/ci.yml"] = yaml.safe_dump(ci)
    assert not (errors := _differences(gate_sources)), errors


@pytest.mark.parametrize("missing", ["CLAUDE.md", "AGENTS.md"])
def test_an_empty_instruction_section_cannot_silently_disable_the_check(gate_sources, missing):
    gate_sources[missing] = "# No gate declarations\n"
    assert any(missing in error for error in _differences(gate_sources))
