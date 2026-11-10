"""Keep published suite totals equal to their completed JUnit reports (#660)."""

import argparse
import json
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVER = "server"
ANDROID = "android"


class CountsError(ValueError):
    pass


def junit_cases(path: Path, *, allow_empty: bool = False) -> set[tuple[str, str]]:
    try:
        tree = ET.parse(path).getroot()
    except (OSError, ET.ParseError) as exc:
        raise CountsError(f"{path}: cannot read JUnit report ({type(exc).__name__})") from exc
    if tree.tag not in ("testsuite", "testsuites"):
        raise CountsError(f"{path}: not a JUnit testsuite/testsuites document")
    parents = {child: parent for parent in tree.iter() for child in parent}
    suites = [node for node in tree.iter() if node.tag in ("testsuite", "testsuites")]
    if not any(node.tag == "testsuite" for node in suites):
        raise CountsError(f"{path}: report has no testsuite")
    for suite in suites:
        if suite is not tree and parents[suite].tag not in ("testsuite", "testsuites"):
            raise CountsError(f"{path}: suite outside the report hierarchy")
    for case in tree.iter("testcase"):
        if parents[case].tag != "testsuite":
            raise CountsError(f"{path}: testcase outside a testsuite")
        if len(case.findall("skipped")) > 1:
            raise CountsError(f"{path}: duplicate skipped markers")
    if any(parents[node].tag != "testcase" for node in tree.iter("skipped")):
        raise CountsError(f"{path}: skipped marker outside a testcase")
    if tree.findall(".//failure") or tree.findall(".//error"):
        raise CountsError(f"{path}: report contains failed/error cases")
    for suite in suites:
        for field in ("tests", "failures", "errors", "skipped"):
            value = suite.get(field)
            if value is None:
                continue
            if not value.isdecimal():
                raise CountsError(f"{path}: invalid {field} count")
            if field == "tests" and int(value) != len(list(suite.iter("testcase"))):
                raise CountsError(f"{path}: declared tests do not match cases")
            if field == "skipped" and int(value) != sum(
                case.find("skipped") is not None for case in suite.iter("testcase")
            ):
                raise CountsError(f"{path}: declared skipped count does not match cases")
            if field in ("failures", "errors") and int(value):
                raise CountsError(f"{path}: report declares {field}")
    cases = set()
    for case in tree.iter("testcase"):
        identity = (case.get("classname"), case.get("name"))
        if not all(identity):
            raise CountsError(f"{path}: case is missing its class or name")
        if identity in cases:
            raise CountsError(f"{path}: duplicate testcase identity")
        cases.add(identity)
    if not cases and not allow_empty:
        raise CountsError(f"{path}: empty report is not a complete server suite")
    return cases


def _module_name(name):
    return (
        isinstance(name, str)
        and re.fullmatch(r":[\w.-]+(?::[\w.-]+)*", name)
        and not {".", ".."}.intersection(name.split(":")[1:])
    )


def _without_comments(source, settings):
    tokens = re.compile(r'""".*?"""|"(?:\\.|[^"\\])*"|//[^\n]*|/\*', re.S)
    pieces = []
    position = 0
    while token := tokens.search(source, position):
        pieces.append(source[position : token.start()])
        position = token.end()
        value = token[0]
        if value == "/*":
            depth = 1
            for delimiter in re.finditer(r"/\*|\*/", source[position:]):
                depth += 1 if delimiter[0] == "/*" else -1
                if depth == 0:
                    position += delimiter.end()
                    break
            else:
                raise CountsError(f"{settings}: unterminated block comment")
            pieces.append(" ")
        elif value.startswith("//"):
            pieces.append(" ")
        else:
            pieces.append(value)
    pieces.append(source[position:])
    return "".join(pieces)


def _modules(root):
    settings = root / "settings.gradle.kts"
    try:
        source = settings.read_text(encoding="utf-8")
    except OSError as exc:
        raise CountsError(f"{settings}: cannot read module declarations") from exc
    source = _without_comments(source, settings)
    mask = re.sub(
        r'""".*?"""|"(?:\\.|[^"\\])*"',
        lambda token: "".join("\n" if char == "\n" else " " for char in token[0]),
        source,
        flags=re.S,
    )
    calls = []
    braces = parentheses = position = 0
    for token in re.finditer(r"\b(?:include|if|else|when|for|while|do|try)\b|[{}()]", mask):
        if token.start() < position:
            continue
        value = token[0]
        if value == "include":
            start = max(mask.rfind("\n", 0, token.start()), mask.rfind(";", 0, token.start())) + 1
            call = re.match(r"include\s*\((.*?)\)", source[token.start() :], re.S)
            if braces or parentheses or mask[start : token.start()].strip() or call is None:
                raise CountsError(f"{settings}: unsupported module include context")
            calls.append(call[1])
            position = token.start() + call.end()
        elif value in ("if", "else", "when", "for", "while", "do", "try"):
            if not braces and not parentheses:
                raise CountsError(f"{settings}: unsupported conditional module discovery")
        elif value == "{":
            braces += 1
        elif value == "}":
            braces -= 1
        elif value == "(":
            parentheses += 1
        else:
            parentheses -= 1
    names = set()
    for call in calls:
        literals = re.findall(r'"([^"\n]+)"', call)
        remainder = re.sub(r'"[^"\n]+"|[\s,]', "", call)
        if not literals or remainder or not all(_module_name(name) for name in literals):
            raise CountsError(f"{settings}: unsupported module include declaration")
        names.update(literals)
    if not names:
        raise CountsError(f"{settings}: no literal module declarations")
    return names


def _android_results(root: Path) -> tuple[dict[str, int], set[str]]:
    root = root.resolve()
    names = _modules(root)
    paths = {name[1:].replace(":", "/"): name for name in names}
    cases = {name: set() for name in names}
    tasks = {}
    reported = set()
    for path in sorted(root.glob("**/build/test-results/*/TEST-*.xml")):
        if not path.resolve().is_relative_to(root):
            raise CountsError(f"{path}: report is outside the build root")
        module_path = path.relative_to(root).as_posix().split("/build/test-results/", 1)[0]
        if module_path not in paths:
            raise CountsError(f"{path}: unlisted module {module_path}")
        name = paths[module_path]
        reported.add(name)
        found = junit_cases(path, allow_empty=True)
        task = tasks.setdefault((name, path.parent.name), set())
        if task & found:
            raise CountsError(f"{path}: duplicate testcase in task {path.parent.name}")
        task.update(found)
        cases[name].update(found)
    return {name: len(identities) for name, identities in sorted(cases.items())}, reported


def android_counts(root: Path) -> dict[str, int]:
    return _android_results(root)[0]


def validate_counts(data: object) -> dict:
    if not isinstance(data, dict) or set(data) != {SERVER, ANDROID}:
        raise CountsError("counts: expected exactly server and android")
    if type(data[SERVER]) is not int or data[SERVER] <= 0:
        raise CountsError("counts: server must be a positive integer")
    android = data[ANDROID]
    if not isinstance(android, dict) or set(android) != {"total", "modules"}:
        raise CountsError("counts: android needs total and modules")
    total, modules = android["total"], android["modules"]
    if type(total) is not int or total <= 0 or not isinstance(modules, dict) or not modules:
        raise CountsError("counts: invalid Android total/modules")
    if any(
        not _module_name(name) or type(count) is not int or count < 0
        for name, count in modules.items()
    ):
        raise CountsError("counts: invalid module name or nonnegative integer count")
    if sum(modules.values()) != total:
        raise CountsError("counts: Android total differs from module sum")
    return data


def _read_counts(path):
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
        return validate_counts(data)
    except (OSError, ValueError) as exc:
        raise CountsError(f"{path}: {exc}") from exc


DOC_DECLARATIONS = (
    (
        "HANDOVER.md",
        SERVER,
        "8. If you are starting new work",
        r"^cd server\s+&& pytest -q -n auto\s+# (\d+) tests\b",
        1,
    ),
    (
        "HANDOVER.md",
        ANDROID,
        "8. If you are starting new work",
        r"^cd android\s+&& \./gradlew test\s+# (\d+) tests\b",
        1,
    ),
    ("README.md", SERVER, "Honest status", r"^\| `pytest -q -n auto` \| (\d+) tests\b", 1),
    ("README.md", ANDROID, "Honest status", r"^\| `\./gradlew test` \| (\d+) tests\b", 1),
    (
        "CLAUDE.md",
        SERVER,
        "Commands",
        r"^- \*\*`pytest -q -n auto`\*\* — (\d+) tests\b",
        1,
    ),
    (
        "CONTRIBUTING.md",
        SERVER,
        "Commands",
        r"^\| `pytest -q -n auto` \| the server tests, (\d+) of them\b",
        1,
    ),
    (
        "docs/architecture.md",
        SERVER,
        "Testing",
        r"^(\d+) tests on the server, (\d+) on Android;",
        1,
    ),
    (
        "docs/architecture.md",
        ANDROID,
        "Testing",
        r"^(\d+) tests on the server, (\d+) on Android;",
        2,
    ),
    (
        ".claude/skills/gates/SKILL.md",
        SERVER,
        "Server, from `server/`",
        r"^\d+\. `pytest -q -n auto`, bare, from `server/` "
        r"with its venv active — (\d+) tests today\b",
        1,
    ),
    (
        ".claude/skills/gates/SKILL.md",
        ANDROID,
        "Android, from `android/`",
        r"^\d+\. `\./gradlew test` — all JVM unit tests across the [^,\n]+, (\d+) today\b",
        1,
    ),
    (
        ".claude/agents/server-tests.md",
        SERVER,
        None,
        r"^You own `server/tests/`\. (\d+) tests;",
        1,
    ),
)


def _current_section(text, heading):
    lines = text.splitlines()
    start = 0
    if heading is not None:
        starts = [i for i, line in enumerate(lines) if line.rstrip() == "## " + heading]
        if len(starts) != 1:
            return ""
        start = starts[0] + 1
    stop = next((i for i in range(start, len(lines)) if lines[i].startswith("## ")), len(lines))
    return "\n".join(lines[start:stop])


def document_differences(root: Path, counts: dict) -> list[str]:
    validate_counts(counts)
    errors = []
    for name, metric, heading, pattern, group in DOC_DECLARATIONS:
        try:
            source = (root / name).read_text(encoding="utf-8")
        except (OSError, UnicodeError):
            errors.append(f"{name}: cannot read current {metric} declaration")
            continue
        matches = list(re.finditer(pattern, _current_section(source, heading), re.M))
        if len(matches) != 1:
            errors.append(
                f"{name}: expected one current {metric} declaration, found {len(matches)}"
            )
            continue
        actual = int(matches[0][group])
        expected = counts[SERVER] if metric == SERVER else counts[ANDROID]["total"]
        if actual != expected:
            errors.append(f"{name}: {metric} expected {expected}, declaration has {actual}")
    name = "docs/architecture.md"
    try:
        source = (root / name).read_text(encoding="utf-8")
    except (OSError, UnicodeError):
        errors.append(f"{name}: cannot read current Android module declarations")
        return errors
    paragraph = _current_section(source, "Testing").strip().split("\n\n", 1)[0]
    declarations = re.findall(r"`(:[\w:.-]+)`\s+(\d+)\b", paragraph)
    modules = counts[ANDROID]["modules"]
    for module in sorted(modules.keys() | {module for module, _ in declarations}):
        found = [int(value) for candidate, value in declarations if candidate == module]
        if len(found) != 1 or module not in modules or found[0] != modules[module]:
            errors.append(
                f"{name}: {module} expected one current count {modules.get(module, 'absent')}, "
                f"declarations have {found}"
            )
    return errors


def _write_counts(path, data):
    validate_counts(data)
    path.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.NamedTemporaryFile(
        mode="w", encoding="utf-8", dir=path.parent, prefix=".counts-", suffix=".json", delete=False
    ) as output:
        output.write(json.dumps(data, indent=2, sort_keys=True) + "\n")
        temporary = Path(output.name)
    temporary.replace(path)


def main(argv: list[str] | None = None, *, root: Path = ROOT) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_subparsers(dest="mode", required=True)
    modes.add_parser(SERVER).add_argument("--junit", type=Path, required=True)
    modes.add_parser(ANDROID).add_argument("--root", dest="android_root", type=Path, required=True)
    writer = modes.add_parser("write")
    writer.add_argument("--server-junit", type=Path, required=True)
    writer.add_argument("--android-root", type=Path, required=True)
    writer.add_argument("--allow-empty-module", action="append", default=[])
    args = parser.parse_args(argv)
    published = root / "docs/counts.json"
    try:
        if args.mode == "write":
            server = len(junit_cases(args.server_junit))
            modules, reported = _android_results(args.android_root)
            previous = _read_counts(published)[ANDROID]["modules"] if published.exists() else {}
            acknowledged = set(args.allow_empty_module)
            for module in acknowledged:
                if modules.get(module) != 0:
                    raise CountsError(
                        f"{published}: {module}: empty acknowledgement needs a zero module"
                    )
            missing = {
                module
                for module, number in modules.items()
                if number == 0 and previous.get(module, 0) > 0 and module not in reported
            }
            if unexplained := missing - acknowledged:
                flags = " ".join(f"--allow-empty-module {module}" for module in sorted(unexplained))
                raise CountsError(
                    f"{published}: reports disappeared for {', '.join(sorted(unexplained))}; "
                    f"only after confirming an intentional zero, repeat with {flags}"
                )
            _write_counts(
                published,
                {SERVER: server, ANDROID: {"total": sum(modules.values()), "modules": modules}},
            )
            print(
                f"Updated {published}; update current document declarations from these full runs."
            )
            return 0
        expected = _read_counts(published)
        if args.mode == SERVER:
            actual = len(junit_cases(args.junit))
            if actual != expected[SERVER]:
                raise CountsError(
                    f"{published}: server expected {expected[SERVER]}, report has {actual}"
                )
            included = _modules(root / ANDROID)
            if included != set(expected[ANDROID]["modules"]):
                raise CountsError(
                    f"{published}: included module names differ; "
                    f"expected {sorted(expected[ANDROID]['modules'])}, "
                    f"settings have {sorted(included)}"
                )
            print(f"Server: {actual} cases and included module names match {published}")
        else:
            actual = android_counts(args.android_root)
            wanted = expected[ANDROID]["modules"]
            mismatches = [
                f"{name}: expected {wanted.get(name, 'absent')}, "
                f"reports have {actual.get(name, 'absent')}"
                for name in sorted(actual.keys() | wanted.keys())
                if actual.get(name) != wanted.get(name)
            ]
            if mismatches:
                raise CountsError(f"{published}: Android mismatch: " + "; ".join(mismatches))
            print(
                f"Android: {sum(actual.values())} cases in {len(actual)} modules match {published}"
            )
    except (CountsError, OSError) as exc:
        print(str(exc), file=sys.stderr)
        print(
            "Regenerate from complete successful gates, from server: "
            "python scripts/check_test_counts.py "
            "write --server-junit work/pytest.xml --android-root ../android",
            file=sys.stderr,
        )
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
