"""Report facts refuse missing cases and stale published totals (#660)."""

import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location(
    "check_test_counts", ROOT / "server/scripts/check_test_counts.py"
)
assert SPEC and SPEC.loader
counts = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(counts)


def report(path, body):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(body, encoding="utf-8")
    return path


def test_junit_counts_cases_including_skips(tmp_path):
    path = report(
        tmp_path / "results.xml",
        '<testsuites><testsuite tests="2"><testcase classname="A" name="one"/>'
        '<testcase classname="A" name="two"><skipped/></testcase></testsuite></testsuites>',
    )
    assert counts.junit_cases(path) == {("A", "one"), ("A", "two")}


@pytest.mark.parametrize(
    "body",
    [
        None,
        "<broken",
        '<testsuite tests="0"/>',
        '<testsuite tests="1"><testcase classname="A" name="x"><failure/></testcase></testsuite>',
        '<testsuite tests="1"><testcase classname="A" name="x"><error/></testcase></testsuite>',
        '<testsuite tests="2"><testcase classname="A" name="x"/></testsuite>',
        '<other><testcase classname="A" name="x"/></other>',
    ],
    ids=["missing", "malformed", "empty", "failed", "error", "wrong_total", "not_junit"],
)
def test_bad_or_failed_reports_are_refused(tmp_path, body):
    path = tmp_path / "results.xml"
    if body is not None:
        report(path, body)
    with pytest.raises(counts.CountsError, match=re.escape(path.name)):
        counts.junit_cases(path)


def test_duplicate_cases_in_one_task_are_refused(tmp_path):
    path = report(
        tmp_path / "duplicates.xml",
        '<testsuite tests="2"><testcase classname="A" name="x"/>'
        '<testcase classname="A" name="x"/></testsuite>',
    )
    with pytest.raises(counts.CountsError, match="duplicates.xml"):
        counts.junit_cases(path)


def test_variant_copies_count_once(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":app")\ninclude(":core:model")\n')
    pair = (
        '<testsuite tests="2"><testcase classname="Screen" name="one"/>'
        '<testcase classname="Screen" name="two"/></testsuite>'
    )
    report(tmp_path / "app/build/test-results/testDebugUnitTest/TEST-screen.xml", pair)
    report(tmp_path / "app/build/test-results/testReleaseUnitTest/TEST-screen.xml", pair)
    report(
        tmp_path / "core/model/build/test-results/test/TEST-model.xml",
        '<testsuite tests="1"><testcase classname="Model" name="one"/></testsuite>',
    )
    assert counts.android_counts(tmp_path) == {":app": 2, ":core:model": 1}


def test_modules_are_discovered(tmp_path):
    report(
        tmp_path / "settings.gradle.kts",
        '// include(":ghost")\n/* include(":also-ghost") */\n'
        'include(":feature:tools")\ninclude(":empty")\n',
    )
    report(
        tmp_path / "feature/tools/build/test-results/test/TEST-tools.xml",
        '<testsuite tests="1"><testcase classname="Tools" name="works"/></testsuite>',
    )
    assert counts.android_counts(tmp_path) == {":feature:tools": 1, ":empty": 0}


def test_zero_case_module_report_is_legitimate(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":empty")\n')
    report(tmp_path / "empty/build/test-results/test/TEST-empty.xml", '<testsuite tests="0"/>')
    assert counts.android_counts(tmp_path) == {":empty": 0}


def test_duplicates_across_reports_in_one_task_are_refused(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":app")\n')
    case = '<testsuite tests="1"><testcase classname="A" name="one"/></testsuite>'
    report(tmp_path / "app/build/test-results/testDebugUnitTest/TEST-a.xml", case)
    report(tmp_path / "app/build/test-results/testDebugUnitTest/TEST-duplicate.xml", case)
    with pytest.raises(counts.CountsError, match="testDebugUnitTest"):
        counts.android_counts(tmp_path)


def test_unlisted_module_reports_are_not_silently_lost(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":app")\n')
    report(
        tmp_path / "unlisted/build/test-results/test/TEST-extra.xml",
        '<testsuite tests="1"><testcase classname="Extra" name="one"/></testsuite>',
    )
    with pytest.raises(counts.CountsError, match="unlisted"):
        counts.android_counts(tmp_path)


@pytest.mark.parametrize("settings", ["", 'include(":app")\ninclude(projectNames)'])
def test_empty_or_unsupported_module_discovery_is_refused(tmp_path, settings):
    report(tmp_path / "settings.gradle.kts", settings)
    with pytest.raises(counts.CountsError, match="settings.gradle.kts"):
        counts.android_counts(tmp_path)


@pytest.mark.parametrize(
    "example",
    ['"include(\\":ghost\\")"', '"""\ninclude(":ghost")\n"""'],
    ids=["quoted", "raw_string"],
)
def test_include_like_text_is_not_a_module_declaration(tmp_path, example):
    report(tmp_path / "settings.gradle.kts", f'val example = {example}\ninclude(":app")\n')
    assert counts.android_counts(tmp_path) == {":app": 0}


@pytest.mark.parametrize(
    "source,expected",
    [
        ('/* outer /* inner */ include(":ghost") */ include(":app")', {":app": 0}),
        ('include(":app", /* explanation */ ":tools")', {":app": 0, ":tools": 0}),
        ('include(":app", // explanation\n ":tools")', {":app": 0, ":tools": 0}),
    ],
    ids=["nested", "between_arguments", "line_between_arguments"],
)
def test_comments_do_not_change_literal_module_declarations(tmp_path, source, expected):
    report(tmp_path / "settings.gradle.kts", source)
    assert counts.android_counts(tmp_path) == expected


@pytest.mark.parametrize(
    "literal", ['"/* include(\\":ghost\\") */"', '"""/* include(":ghost") */"""']
)
def test_comment_markers_in_strings_are_not_comments(tmp_path, literal):
    report(tmp_path / "settings.gradle.kts", f'val note = {literal}\ninclude(":app")')
    assert counts.android_counts(tmp_path) == {":app": 0}


def test_an_unclosed_comment_cannot_hide_module_declarations(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":app")\n/* unfinished include(":ghost")')
    with pytest.raises(counts.CountsError, match="unterminated"):
        counts.android_counts(tmp_path)


@pytest.mark.parametrize(
    "statement",
    [
        'if (false) include(":ghost")',
        'if (false)\ninclude(":ghost")',
        'run { include(":ghost") }',
        'val result = include(":ghost")',
        "val alias = ::include",
    ],
)
def test_unsupported_include_context_is_refused(tmp_path, statement):
    report(tmp_path / "settings.gradle.kts", f'include(":app")\n{statement}\n')
    with pytest.raises(counts.CountsError, match="unsupported"):
        counts.android_counts(tmp_path)


def test_unconditional_include_supports_semicolons_and_multiline_arguments(tmp_path):
    report(tmp_path / "settings.gradle.kts", 'include(":app");include(\n":tools",\n)\n')
    assert counts.android_counts(tmp_path) == {":app": 0, ":tools": 0}


@pytest.mark.parametrize(
    "body",
    [
        '<testsuite tests="1" skipped="3"><testcase classname="A" name="one"/></testsuite>',
        '<testsuite tests="1" skipped="-1"><testcase classname="A" name="one"/></testsuite>',
        '<testsuites><properties><testcase classname="A" name="one"/></properties></testsuites>',
        '<testsuite><properties><testcase classname="A" name="one"/></properties></testsuite>',
        '<testsuites><testcase classname="A" name="one"/></testsuites>',
        '<testsuites><properties><testsuite tests="1"><testcase classname="A" name="one"/>'
        "</testsuite></properties></testsuites>",
        '<testsuite tests="1" skipped="1"><testcase classname="A" name="one">'
        "<properties><skipped/></properties></testcase></testsuite>",
        "<testsuites/>",
    ],
)
def test_malformed_junit_structure_or_skip_counts_are_refused(tmp_path, body):
    path = report(tmp_path / "malformed.xml", body)
    with pytest.raises(counts.CountsError, match="malformed.xml"):
        counts.junit_cases(path, allow_empty=True)


def report_pair(tmp_path, *, missing_model=False):
    repo = tmp_path / "repo"
    published = report(
        repo / "docs/counts.json",
        '{"server":2,"android":{"total":2,"modules":{":app":1,":model":1}}}',
    )
    server = report(
        repo / "complete.xml",
        '<testsuite tests="2" skipped="0"><testcase classname="A" name="one"/>'
        '<testcase classname="A" name="two"/></testsuite>',
    )
    build = repo / "android"
    report(build / "settings.gradle.kts", 'include(":app")\ninclude(":model")\n')
    report(build / "model/src/test/kotlin/ModelTest.kt", "class ModelTest\n")
    xml = '<testsuite tests="1" skipped="0"><testcase classname="A" name="one"/></testsuite>'
    report(build / "app/build/test-results/test/TEST-a.xml", xml)
    if not missing_model:
        report(build / "model/build/test-results/test/TEST-a.xml", xml)
    args = ["write", "--server-junit", str(server), "--android-root", str(build)]
    return repo, published, server, build, args


def test_missing_positive_module_reports_cannot_replace_counts(tmp_path, capsys):
    repo, published, _, _, args = report_pair(tmp_path, missing_model=True)
    before = published.read_bytes()
    assert counts.main(args, root=repo) != 0
    assert published.read_bytes() == before
    error = capsys.readouterr().err
    assert ":model" in error and "--allow-empty-module" in error


def test_explicit_empty_acknowledgement_allows_an_intentional_zero(tmp_path):
    repo, published, _, _, args = report_pair(tmp_path, missing_model=True)
    assert counts.main([*args, "--allow-empty-module", ":model"], root=repo) == 0
    assert json.loads(published.read_text())[counts.ANDROID] == {
        "total": 1,
        "modules": {":app": 1, ":model": 0},
    }


def test_zero_report_evidence_allows_a_previously_positive_module_to_be_empty(tmp_path):
    repo, published, _, build, args = report_pair(tmp_path, missing_model=True)
    report(build / "model/build/test-results/test/TEST-a.xml", '<testsuite tests="0" skipped="0"/>')
    assert counts.main(args, root=repo) == 0
    assert json.loads(published.read_text())[counts.ANDROID]["modules"][":model"] == 0


@pytest.mark.parametrize("side", ["server", "android"])
@pytest.mark.parametrize(
    "bad",
    [
        '<testsuite tests="1" skipped="3"><testcase classname="A" name="one"/></testsuite>',
        '<testsuites><properties><testcase classname="A" name="one"/></properties></testsuites>',
    ],
)
def test_malformed_junit_cannot_replace_published_facts(tmp_path, side, bad):
    repo, published, server, build, args = report_pair(tmp_path)
    before = published.read_bytes()
    target = server if side == "server" else build / "app/build/test-results/test/TEST-a.xml"
    report(target, bad)
    assert counts.main(args, root=repo) != 0
    assert published.read_bytes() == before


@pytest.mark.parametrize(
    "text",
    [
        '{"server":true,"android":{"total":3,"modules":{":app":3}}}',
        '{"server":0,"android":{"total":3,"modules":{":app":3}}}',
        '{"server":2,"android":{"total":4,"modules":{":app":3}}}',
        '{"server":2,"android":{"total":3,"modules":{":app":-1}}}',
        '{"server":2,"android":{"total":3,"modules":{":app":true}}}',
        '{"server":2,"android":{"total":3,"modules":{}}}',
        '{"server":2,"android":{"total":3}}',
        '{"server":2,"android":{"total":3,"modules":{":app":3}},"extra":1}',
    ],
)
def test_invalid_count_shapes_are_refused(text):
    with pytest.raises(counts.CountsError):
        counts.validate_counts(json.loads(text))


def test_valid_counts_keep_each_modules_measured_total():
    data = json.loads('{"server":2,"android":{"total":3,"modules":{":app":3}}}')
    assert counts.validate_counts(data) == data


def test_cli_refuses_a_missing_report_with_a_nonzero_status(tmp_path, capsys):
    report(
        tmp_path / "docs/counts.json",
        '{"server":2,"android":{"total":3,"modules":{":app":3}}}',
    )
    missing = tmp_path / "missing.xml"
    assert counts.main([counts.SERVER, "--junit", str(missing)], root=tmp_path) != 0
    assert "missing.xml" in capsys.readouterr().err


def test_cli_write_records_both_real_totals(tmp_path):
    repo = tmp_path / "repo"
    published = report(
        repo / "docs/counts.json",
        '{"server":9,"android":{"total":9,"modules":{":app":9}}}',
    )
    server = report(
        tmp_path / "complete.xml",
        '<testsuite tests="2"><testcase classname="A" name="one"/>'
        '<testcase classname="A" name="two"><skipped/></testcase></testsuite>',
    )
    build = tmp_path / "build-tree"
    report(build / "settings.gradle.kts", 'include(":app")\n')
    report(
        build / "app/build/test-results/test/TEST-a.xml",
        '<testsuite tests="1"><testcase classname="Screen" name="one"/></testsuite>',
    )
    assert (
        counts.main(
            ["write", "--server-junit", str(server), "--android-root", str(build)], root=repo
        )
        == 0
    )
    assert json.loads(published.read_text()) == json.loads(
        '{"server":2,"android":{"total":1,"modules":{":app":1}}}'
    )


def test_a_failed_side_cannot_partly_replace_published_counts(tmp_path):
    repo = tmp_path / "repo"
    before = '{"server":9,"android":{"total":9,"modules":{":app":9}}}'
    published = report(repo / "docs/counts.json", before)
    server = report(
        tmp_path / "complete.xml",
        '<testsuite tests="1"><testcase classname="A" name="one"/></testsuite>',
    )
    build = tmp_path / "build-tree"
    report(build / "settings.gradle.kts", 'include(":app")\n')
    report(
        build / "app/build/test-results/test/TEST-a.xml",
        '<testsuite tests="1"><testcase classname="Screen" name="one">'
        "<failure/></testcase></testsuite>",
    )
    assert (
        counts.main(
            ["write", "--server-junit", str(server), "--android-root", str(build)], root=repo
        )
        != 0
    )
    assert published.read_text() == before


def test_android_cli_refuses_a_different_measured_module_count(tmp_path, capsys):
    repo = tmp_path / "repo"
    report(
        repo / "docs/counts.json",
        '{"server":2,"android":{"total":9,"modules":{":app":9}}}',
    )
    build = tmp_path / "build-tree"
    report(build / "settings.gradle.kts", 'include(":app")\n')
    report(
        build / "app/build/test-results/test/TEST-a.xml",
        '<testsuite tests="1"><testcase classname="Screen" name="one"/></testsuite>',
    )
    assert counts.main([counts.ANDROID, "--root", str(build)], root=repo) != 0
    assert ":app" in capsys.readouterr().err


DECLARED_SOURCES = [
    ("HANDOVER.md", counts.SERVER),
    ("README.md", counts.SERVER),
    ("CLAUDE.md", counts.SERVER),
    ("CONTRIBUTING.md", counts.SERVER),
    ("docs/architecture.md", counts.SERVER),
    (".claude/skills/gates/SKILL.md", counts.SERVER),
    (".claude/agents/server-tests.md", counts.SERVER),
    ("HANDOVER.md", counts.ANDROID),
    ("README.md", counts.ANDROID),
    ("docs/architecture.md", counts.ANDROID),
    (".claude/skills/gates/SKILL.md", counts.ANDROID),
]


@pytest.fixture
def document_tree(tmp_path):
    documents = {
        "HANDOVER.md": (
            "## Previous batch\ncd server && pytest -q -n auto # 17 tests\n"
            "cd android && ./gradlew test # 19 tests\n"
            "## 8. If you are starting new work\n"
            "cd server && pytest -q -n auto # 2 tests\n"
            "cd android && ./gradlew test # 3 tests\n"
        ),
        "README.md": (
            "## Previous status\n| `pytest -q -n auto` | 17 tests |\n"
            "## Honest status\n| `pytest -q -n auto` | 2 tests, green |\n"
            "| `./gradlew test` | 3 tests, green |\nPrevious PostgreSQL: 17 cases.\n"
        ),
        "CLAUDE.md": (
            "## Old commands\n- **`pytest -q -n auto`** — 17 tests\n"
            "## Commands\n- **`pytest -q -n auto`** — 2 tests\n"
        ),
        "CONTRIBUTING.md": ("## Commands\n| `pytest -q -n auto` | the server tests, 2 of them |\n"),
        "docs/architecture.md": (
            "## Previous tests\n17 tests on the server, 19 on Android;\n"
            "## Testing\n2 tests on the server, 3 on Android;\n"
            "On Android that is `:app` 1, `:feature:tools` 2.\n\n"
            "Previous build: `:app` 17, `:historical` 19.\n"
        ),
        ".claude/skills/gates/SKILL.md": (
            "## Server, from `server/`\n"
            "3. `pytest -q -n auto`, bare, from `server/` "
            "with its venv active — 2 tests today\n"
            "## Android, from `android/`\n"
            "1. `./gradlew test` — all JVM unit tests across the five modules, 3 today.\n"
        ),
        ".claude/agents/server-tests.md": (
            "You own `server/tests/`. 2 tests; `pytest -q -n auto`\n"
            "## Previous ownership\nYou own `server/tests/`. 17 tests;\n"
        ),
    }
    for name, body in documents.items():
        report(tmp_path / name, body)
    data = json.loads('{"server":2,"android":{"total":3,"modules":{":app":1,":feature:tools":2}}}')
    return tmp_path, data


@pytest.mark.parametrize("name,metric", DECLARED_SOURCES)
def test_a_changed_current_count_names_its_document(document_tree, name, metric):
    root, data = document_tree
    path = root / name
    number = "2" if metric == counts.SERVER else "3"
    path.write_text(path.read_text(encoding="utf-8").replace(number, "99"), encoding="utf-8")
    errors = counts.document_differences(root, data)
    assert any(name in error and metric in error for error in errors), errors


@pytest.mark.parametrize("name,metric", DECLARED_SOURCES)
def test_a_missing_declaration_cannot_disable_the_rule(document_tree, name, metric):
    root, data = document_tree
    path = root / name
    number = "2" if metric == counts.SERVER else "3"
    path.write_text(path.read_text(encoding="utf-8").replace(number, "unknown"), encoding="utf-8")
    errors = counts.document_differences(root, data)
    assert any(name in error and metric in error for error in errors), errors


def test_historical_run_counts_are_not_current_declarations(document_tree):
    root, data = document_tree
    assert not (errors := counts.document_differences(root, data)), errors


def test_two_current_declarations_are_ambiguous(document_tree):
    root, data = document_tree
    path = root / "README.md"
    path.write_text(path.read_text() + "| `pytest -q -n auto` | 2 tests |\n", encoding="utf-8")
    errors = counts.document_differences(root, data)
    assert any("README.md" in error and counts.SERVER in error for error in errors), errors


def test_current_repository_declarations_match_the_fact_file():
    data = json.loads((ROOT / "docs/counts.json").read_text(encoding="utf-8"))
    assert not (errors := counts.document_differences(ROOT, data)), "\n".join(errors)


def test_a_missing_document_cannot_silently_drop_its_count(tmp_path):
    data = json.loads('{"server":2,"android":{"total":3,"modules":{":app":3}}}')
    errors = counts.document_differences(tmp_path, data)
    assert any("HANDOVER.md" in error and counts.SERVER in error for error in errors), errors


@pytest.mark.parametrize("name", [":app", ":feature:tools"])
@pytest.mark.parametrize("change", ["wrong", "missing"])
def test_current_module_counts_cannot_drift(document_tree, name, change):
    root, data = document_tree
    path = root / "docs/architecture.md"
    old = f"`{name}` {data[counts.ANDROID]['modules'][name]}"
    replacement = f"`{name}` 99" if change == "wrong" else "removed"
    path.write_text(path.read_text(encoding="utf-8").replace(old, replacement), encoding="utf-8")
    errors = counts.document_differences(root, data)
    assert any("docs/architecture.md" in error and name in error for error in errors), errors


@pytest.mark.parametrize("extra", ["`:app` 1", "`:forgotten` 0"])
def test_duplicate_or_unlisted_current_module_counts_are_refused(document_tree, extra):
    root, data = document_tree
    path = root / "docs/architecture.md"
    source = path.read_text(encoding="utf-8").replace("`:app` 1,", f"{extra}, `:app` 1,")
    path.write_text(source, encoding="utf-8")
    errors = counts.document_differences(root, data)
    name = extra.split("`")[1]
    assert any("docs/architecture.md" in error and name in error for error in errors), errors


def test_a_new_zero_case_module_also_needs_a_current_declaration(document_tree):
    root, data = document_tree
    data[counts.ANDROID]["modules"][":empty"] = 0
    errors = counts.document_differences(root, data)
    assert any("docs/architecture.md" in error and ":empty" in error for error in errors), errors


@pytest.mark.parametrize("change", ["add", "remove"])
def test_changed_module_set_can_be_regenerated_after_successful_raw_checks(document_tree, change):
    repo, data = document_tree
    architecture = repo / "docs/architecture.md"
    if change == "remove":
        data[counts.ANDROID]["modules"][":empty"] = 0
        architecture.write_text(
            architecture.read_text().replace(
                "`:feature:tools` 2.", "`:feature:tools` 2, `:empty` 0."
            ),
            encoding="utf-8",
        )
    published = report(repo / "docs/counts.json", json.dumps(data))
    build = repo / "android"
    settings = 'include(":app")\ninclude(":feature:tools")\n'
    if change == "add":
        settings += 'include(":empty")\n'
    report(build / "settings.gradle.kts", settings)
    for name, number in (("app", 1), ("feature/tools", 2)):
        cases = "".join(f'<testcase classname="A" name="case{i}"/>' for i in range(number))
        report(
            build / name / "build/test-results/test/TEST-a.xml",
            f'<testsuite tests="{number}" skipped="0">{cases}</testsuite>',
        )
    script = repo / "server/scripts/check_test_counts.py"
    script.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(ROOT / "server/scripts/check_test_counts.py", script)
    copied_tests = repo / "server/tests/test_counts.py"
    copied_tests.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(Path(__file__), copied_tests)
    raw = repo / "complete.xml"
    environment = {**os.environ, "PYTEST_DISABLE_PLUGIN_AUTOLOAD": "1"}
    environment.pop("PYTEST_ADDOPTS", None)
    ran = subprocess.run(
        [
            sys.executable,
            "-m",
            "pytest",
            "-q",
            str(copied_tests),
            "-k",
            "current_repository_declarations or published_module_names or valid_counts_keep",
            f"--junitxml={raw}",
        ],
        cwd=repo / "server",
        env=environment,
        capture_output=True,
        text=True,
        timeout=45,
    )
    assert ran.returncode == 0, ran.stdout + ran.stderr
    assert len(counts.junit_cases(raw)) == 2
    assert counts.main([counts.SERVER, "--junit", str(raw)], root=repo) != 0
    assert (
        counts.main(["write", "--server-junit", str(raw), "--android-root", str(build)], root=repo)
        == 0
    )
    current = json.loads(published.read_text())
    source = architecture.read_text()
    if change == "add":
        source = source.replace("`:feature:tools` 2.", "`:feature:tools` 2, `:empty` 0.")
    else:
        source = source.replace(", `:empty` 0", "")
    architecture.write_text(source, encoding="utf-8")
    assert not counts.document_differences(repo, current)
    assert counts.main([counts.SERVER, "--junit", str(raw)], root=repo) == 0
    assert counts.main([counts.ANDROID, "--root", str(build)], root=repo) == 0
