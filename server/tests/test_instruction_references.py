"""Agent navigation fails when referenced source or test definitions disappear (#661)."""

import importlib.util
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def references():
    path = ROOT / "server/scripts/check_instruction_references.py"
    assert path.is_file(), "instruction reference guard has not been implemented"
    spec = importlib.util.spec_from_file_location("instruction_references", path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


@pytest.mark.parametrize(
    "token",
    [
        "gone.py",
        "app/gone.py",
        "test_removed",
        "RemovedTest",
        "docs/gone.md",
        "python scripts/gone.py --junit work/result.xml",
        "pytest tests/gone.py",
        "./gradlew :app:test --tests '*RemovedTest*'",
    ],
)
def test_missing_references_are_reported(references, token):
    errors = references.unresolved(f"Read `{token}`.", {"server/app/new.py"}, set())
    assert errors and any("gone" in e or "RemovedTest" in e or "test_removed" in e for e in errors)


@pytest.mark.parametrize(
    "token",
    [
        "source.py",
        "app/source.py",
        "server/app/source.py",
        "app/.../source.py",
        "app/*.py",
        "server/app/",
        "test_present",
        "PresentTest",
        "python app/source.py --junit work/result.xml",
        "pytest tests/test_source.py -k test_present",
        "./gradlew :app:test --tests '*PresentTest*'",
    ],
)
def test_existing_paths_and_definitions_resolve(references, token):
    paths = {"server/app/source.py", "server/tests/test_source.py", "android/gradlew"}
    assert references.unresolved(f"Read `{token}`.", paths, {"test_present", "PresentTest"}) == []


@pytest.mark.parametrize(
    "token",
    [
        "/api/v1/health",
        "GET /api/v1/warmup",
        "https://example.test/source.py",
        "Europe/Moscow",
        ".md",
        "server/.env",
        "server/.env.*",
        ".mcp.json",
        "settings.local.json",
        "~/.gradle/gradle.properties",
        "./gradlew :<module>:detekt",
        "DATABASE_URL=... .venv/bin/alembic upgrade head",
        "local.properties",
        "android/local.properties",
        "keystore.properties",
        "android/keystore.properties",
        "::error::",
    ],
)
def test_non_source_categories_do_not_require_local_secrets(references, token):
    assert references.unresolved(f"Example `{token}`.", {"android/gradlew"}, set()) == []


def test_fenced_examples_are_not_current_navigation(references):
    assert (
        references.unresolved("```python\n`gone.py`\n```\nHistorical gone.py.", set(), set()) == []
    )


def test_extension_cannot_resolve_to_a_different_file(references):
    assert references.unresolved("`source.kt`", {"server/app/source.py"}, set())


def test_module_symbol_reference_checks_the_module(references):
    assert (
        references.unresolved("`api/deps.py:current_device`", {"server/app/api/deps.py"}, set())
        == []
    )
    assert references.unresolved("`api/gone.py:current_device`", {"server/app/api/deps.py"}, set())


def test_new_untracked_source_and_real_test_definitions_are_discovered(references, tmp_path):
    subprocess.run(["git", "init", "--quiet", str(tmp_path)], check=True)
    (tmp_path / ".gitignore").write_text("build/\n.env\n", encoding="utf-8")
    tests = tmp_path / "server/tests"
    tests.mkdir(parents=True)
    (tests / "test_new.py").write_text(
        'def test_real(): pass\n# def test_comment(): pass\ntext = "def test_string(): pass"\n',
        encoding="utf-8",
    )
    kotlin = tmp_path / "android/app/src/test/kotlin"
    kotlin.mkdir(parents=True)
    (kotlin / "ActualTest.kt").write_text(
        "class ActualTest {}\n// class CommentTest {}\n"
        'val text = "class StringTest {}"\n'
        "/* nested /* comment */ class BlockTest {} */\n",
        encoding="utf-8",
    )
    (kotlin / "Production.kt").write_text("class Helper {}", encoding="utf-8")
    build = tmp_path / "build"
    build.mkdir()
    (build / "GhostTest.kt").write_text("class GhostTest {}", encoding="utf-8")
    (tmp_path / ".env").write_bytes(b"\xff")
    paths, names = references.inventory(tmp_path)
    assert "server/tests/test_new.py" in paths
    assert "build/GhostTest.kt" not in paths
    assert "test_real" in names and "ActualTest" in names
    assert not names & {
        "test_comment",
        "test_string",
        "CommentTest",
        "StringTest",
        "BlockTest",
        "GhostTest",
    }


def test_renaming_source_and_definitions_invalidates_navigation(references, tmp_path):
    subprocess.run(["git", "init", "--quiet", str(tmp_path)], check=True)
    (tmp_path / "CLAUDE.md").write_text("Read `old.py` and `test_old`.", encoding="utf-8")
    (tmp_path / "AGENTS.md").write_text("", encoding="utf-8")
    tests = tmp_path / "server/tests"
    tests.mkdir(parents=True)
    (tests / "test_new.py").write_text("def test_new(): pass", encoding="utf-8")
    errors = references.differences(tmp_path)
    assert any("CLAUDE.md:1" in e and "old.py" in e for e in errors)
    assert any("CLAUDE.md:1" in e and "test_old" in e for e in errors)


def test_current_agent_references_resolve(references):
    assert references.differences(ROOT) == []


@pytest.mark.parametrize(
    "text",
    [
        "Read `python\n scripts/source.py` then `test_present`.",
        "Read ``source.py`` then `test_present`.",
        "Example `gh item-add --url <the issue's URL>`.",
    ],
)
def test_markdown_spans_and_command_placeholders(references, text):
    paths = {"server/scripts/source.py"}
    assert references.unresolved(text, paths, {"test_present"}) == []


@pytest.mark.parametrize(
    "command",
    [
        "pytest tests/test_source.py::test_removed",
        "python -m pytest tests/test_source.py -k test_removed",
    ],
)
def test_removed_python_selectors_are_not_hidden_by_existing_files(references, command):
    errors = references.unresolved(
        f"`{command}`", {"server/tests/test_source.py"}, {"test_present"}
    )
    assert any("test_removed" in error for error in errors)


@pytest.mark.parametrize("span", ["ruff check app/gone.py", "/api/gone.py"])
def test_source_paths_do_not_depend_on_command_or_route_prefix(references, span):
    assert any("gone.py" in e for e in references.unresolved(f"`{span}`", set(), set()))


@pytest.mark.parametrize("public_reference", ["AGENTS.md", "gone.py"])
def test_only_public_instruction_documents_are_read(references, tmp_path, public_reference):
    subprocess.run(["git", "init", "--quiet", str(tmp_path)], check=True)
    (tmp_path / "CLAUDE.md").write_text("", encoding="utf-8")
    (tmp_path / "AGENTS.md").write_text("", encoding="utf-8")
    (tmp_path / ".gitignore").write_text(".claude/local/\n", encoding="utf-8")
    private = tmp_path / ".claude/local"
    private.mkdir(parents=True)
    (private / "README.md").write_bytes(b"\xff")
    public = tmp_path / ".claude/agents"
    public.mkdir(parents=True)
    (public / "new.md").write_text(f"Read `{public_reference}`.", encoding="utf-8")
    errors = references.differences(tmp_path)
    if public_reference == "AGENTS.md":
        assert errors == []
    else:
        assert errors == [".claude/agents/new.md:1: gone.py"]


@pytest.mark.parametrize(
    "token",
    [
        "pyproject.toml",
        "requirements.txt",
        "google_sans_flex.ttf",
        "Dockerfile",
        "source.py:method",
    ],
)
def test_current_filename_forms_cannot_disappear(references, token):
    assert references.unresolved(f"`{token}`", set(), set())


def test_explicit_extension_is_not_a_python_attribute(references):
    assert references.unresolved(
        "`android/gradle/libs.versions.toml`", {"android/gradle/libs.versions.py"}, set()
    )
    assert references.unresolved("`.claude/settings.local.json`", set(), set()) == []


@pytest.mark.parametrize(
    "command",
    [
        "python -m scripts.seed_demo",
        "ruff check app tests scripts migrations",
    ],
)
def test_current_local_command_operands_are_source(references, command):
    assert references.unresolved(f"`{command}`", set(), set())


def _source_repo(tmp_path, files):
    subprocess.run(["git", "init", "--quiet", str(tmp_path)], check=True)
    for name, text in {"CLAUDE.md": "", "AGENTS.md": "", **files}.items():
        path = tmp_path / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8")
    return tmp_path


@pytest.mark.parametrize(
    "selector",
    [
        "tests/test_a.py::test_moved",
        "tests/test_a.py::TestDoesNotExist::test_here",
        "tests/test_a.py::test_nested",
        "tests/test_a.py -k parity",
    ],
)
def test_python_selection_stays_with_its_owner(references, tmp_path, selector):
    root = _source_repo(
        tmp_path,
        {
            "server/tests/test_a.py": (
                "def test_here(): pass\ndef helper():\n def test_nested(): pass\n"
                "class TestGroup:\n def test_member(self): pass\n"
            ),
            "server/tests/test_b.py": "def test_moved(): pass\ndef test_parity(): pass\n",
        },
    )
    paths, tests = references.inventory(root)
    assert references.unresolved(f"`pytest {selector}`", paths, tests)
    assert "test_nested" not in tests
    assert references.unresolved("`pytest tests/test_b.py::test_moved`", paths, tests) == []
    assert references.unresolved("`pytest tests/test_b.py -k parity`", paths, tests) == []
    assert (
        references.unresolved("`pytest tests/test_a.py::TestGroup::test_member`", paths, tests)
        == []
    )


@pytest.mark.parametrize(
    "source",
    [
        'val message = "${"class GhostTest"}"',
        'val message = """${"""class GhostTest"""}"""',
    ],
)
def test_kotlin_template_strings_cannot_define_a_test(references, tmp_path, source):
    root = _source_repo(tmp_path, {"android/app/src/test/kotlin/Ghost.kt": source})
    _, tests = references.inventory(root)
    assert "GhostTest" not in tests


@pytest.mark.parametrize(
    "token,paths",
    [
        ("docs/only/", {"docs/only"}),
        ("src/real.xml", {"src/real.xml/child.py"}),
        ("app/*.py", {"server/app/deep/nested/test.py"}),
    ],
)
def test_reference_kind_and_glob_depth_are_preserved(references, token, paths):
    assert references.unresolved(f"`{token}`", paths, set())


@pytest.mark.parametrize(
    "name,payload",
    [
        (".claude/guide.md", "`outside_marker.py`"),
        ("server/tests/test_link.py", "def test_outside(): pass"),
        ("android/app/src/test/kotlin/LinkTest.kt", "class OutsideTest {}"),
    ],
)
def test_symlink_targets_are_not_public_source(references, tmp_path, name, payload):
    root = _source_repo(tmp_path, {"CLAUDE.md": f"Read `{name}`."})
    target = tmp_path.parent / (tmp_path.name + "-outside" + Path(name).suffix)
    target.write_text(payload, encoding="utf-8")
    link = root / name
    link.parent.mkdir(parents=True, exist_ok=True)
    try:
        link.symlink_to(target)
    except OSError as error:
        pytest.skip(f"file symlink unavailable: {error}")
    paths, tests = references.inventory(root)
    assert name not in paths
    assert "test_outside" not in tests and "OutsideTest" not in tests
    assert all("outside_marker" not in error for error in references.differences(root))


def test_a_short_inner_fence_cannot_hide_a_following_reference(references):
    text = "~~~~\n~~~\n`gone.py`\n~~~~\n`real_missing.py`\n"
    assert references.unresolved(text, set(), set()) == ["real_missing.py"]


@pytest.mark.parametrize("pattern", ["app/[ab].py", "app/?.py"])
def test_component_glob_spellings_have_the_same_missing_target_rule(references, pattern):
    assert references.unresolved(f"`{pattern}`", {"server/app/longname.py"}, set())
    assert references.unresolved(f"`{pattern}`", {"server/app/a.py"}, set()) == []
