"""Check current agent navigation against public source names and test definitions."""

import ast
import fnmatch
import re
import shlex
import subprocess
from functools import lru_cache
from pathlib import Path

EXTENSIONS = {".kt", ".py", ".md", ".xml", ".kts", ".yml", ".yaml", ".json", ".conf"}
OUTPUTS = {"build", "work", ".git", ".gradle", ".venv", ".venv312", "__pycache__", ".superpowers"}
ROUTES = {
    "api",
    "bundle",
    "days",
    "diary",
    "events",
    "health",
    "homework",
    "join",
    "manage",
    "me",
    "overrides",
    "plugin",
    "pre-push",
    "start",
    "where-are-we",
    "webapi",
}


def _kotlin_code(text: str) -> str:
    """Mask comments and strings before looking for class declarations."""
    result = []
    index = 0
    while index < len(text):
        if text.startswith("//", index):
            end = text.find("\n", index)
            index = len(text) if end < 0 else end
        elif text.startswith("/*", index):
            depth = 1
            index += 2
            while index < len(text) and depth:
                if text.startswith("/*", index):
                    depth += 1
                    index += 2
                elif text.startswith("*/", index):
                    depth -= 1
                    index += 2
                else:
                    index += 1
            result.append(" ")
        elif text.startswith('"""', index):
            end = text.find('"""', index + 3)
            index = len(text) if end < 0 else end + 3
            result.append(" ")
        elif text[index] in {'"', "'"}:
            quote = text[index]
            index += 1
            while index < len(text):
                char = text[index]
                index += 1
                if char == "\\":
                    index += 1
                elif char == quote:
                    break
            result.append(" ")
        else:
            result.append(text[index])
            index += 1
    return "".join(result)


def inventory(root: Path) -> tuple[set[str], set[str]]:
    names = (
        subprocess.run(
            ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"],
            cwd=root,
            check=True,
            capture_output=True,
        )
        .stdout.decode("utf-8")
        .split("\0")
    )
    paths = {
        name
        for name in names
        if name
        and not OUTPUTS.intersection(Path(name).parts)
        and not any(part.startswith(".env") for part in Path(name).parts)
        and (root / name).is_file()
    }
    tests = set()
    for name in sorted(paths):
        path = root / name
        if "/tests/" in name and path.name.startswith("test_") and path.suffix == ".py":
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=name)
            tests.update(
                node.name
                for node in ast.walk(tree)
                if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
                and (node.name.startswith("test_") or node.name.endswith("Test"))
            )
        elif path.suffix == ".kt" and any(p in name for p in ("/src/test/", "/src/androidTest/")):
            tests.update(
                re.findall(r"\bclass\s+(\w+Test)\b", _kotlin_code(path.read_text(encoding="utf-8")))
            )
    return paths, tests


def _non_source(token: str) -> bool:
    return (
        "://" in token
        or "<" in token
        or "…" in token
        or token
        in {".mcp.json", "settings.local.json", "Europe/Moscow", "~/.gradle/gradle.properties"}
        or token.startswith(".venv/")
        or bool(re.search(r"(?:^|/)\.env(?:\..*)?$", token))
        or token in EXTENSIONS
        or (
            token.startswith("/")
            and token.split("/", 2)[1] in ROUTES
            and Path(token.split(":", 1)[0]).suffix not in EXTENSIONS
        )
    )


@lru_cache(maxsize=8)
def _candidates(paths: frozenset[str]) -> frozenset[str]:
    return paths | frozenset(
        parent.as_posix() for p in paths for parent in Path(p).parents if parent != Path(".")
    )


def _exists(token: str, paths: set[str]) -> bool:
    token = token.removeprefix("./").lstrip("/")
    while token.startswith("../"):
        token = token[3:]
    token = token.split(":", 1)[0]
    pattern = token.rstrip("/")
    patterns = {pattern.replace(".../", "*/"), pattern.replace(".../", "")}
    if "/" in token and Path(token).suffix not in EXTENSIONS and "." in Path(token).name:
        patterns.add(token.rsplit(".", 1)[0] + ".py")
    if not Path(pattern).suffix and not token.endswith("/"):
        patterns |= {pattern + ".py", pattern + "/__init__.py"}
    candidates = _candidates(frozenset(paths))
    return any(
        fnmatch.fnmatchcase(path, p) or fnmatch.fnmatchcase(path, "*/" + p)
        for path in candidates
        for p in patterns
    )


def _missing(token: str, paths: set[str], tests: set[str]) -> list[str]:
    if _non_source(token):
        return []
    if "::" in token:
        source, *selectors = token.split("::")
        errors = _missing(source, paths, tests)
        for selector in selectors:
            errors.extend(_missing(selector.split("[", 1)[0], paths, tests))
        return errors
    if not re.fullmatch(r"[\w.*:/-]+", token):
        return []
    if re.fullmatch(r"test_\w+|\w+Test", token):
        return [] if token in tests else [token]
    if "/" in token or Path(token).suffix in EXTENSIONS:
        return [] if _exists(token, paths) else [token]
    return []


def _span_missing(span: str, paths: set[str], tests: set[str]) -> list[str]:
    if re.search(r"<[^>]*>", span):
        return []
    if " " not in span:
        return _missing(span, paths, tests)
    if span.startswith(("GET /", "POST /", "PUT /", "PATCH /", "DELETE /")):
        return []
    first = span.split()[0]
    if not re.fullmatch(r"[A-Za-z_][\w-]*", first) and not (
        "=" in first or "/" in first or Path(first).suffix in EXTENSIONS
    ):
        return []
    try:
        words = shlex.split(span)
    except ValueError:
        return [span]
    errors = []
    skip = False
    selector = False
    keyword = False
    for word in words:
        if skip:
            skip = False
            continue
        if selector:
            names = [name for name in tests if fnmatch.fnmatchcase(name, word)]
            if not names:
                errors.append(word)
            selector = False
            continue
        if keyword:
            if re.fullmatch(r"test_\w+|\w+Test", word):
                errors.extend(_missing(word, paths, tests))
            keyword = False
            continue
        if word == "--tests":
            selector = True
        elif word == "-k":
            keyword = True
        elif word in {
            "--junitxml",
            "--junit",
            "--server-junit",
            "--root",
            "--android-root",
            "-m",
        }:
            skip = True
        elif word.startswith(("-", ":")) or "=" in word:
            continue
        elif first == "git" and word in {"origin/main", "origin/dev"}:
            continue
        else:
            errors.extend(_missing(word, paths, tests))
    return errors


def _spans(text: str):
    fence = None
    lines = []
    for line in text.splitlines():
        marker = re.match(r"\s*(`{3,}|~{3,})", line)
        if marker:
            if fence is None:
                fence = marker[1][0]
            elif marker[1][0] == fence:
                fence = None
            lines.append("")
        else:
            lines.append(line if fence is None else "")
    public = "\n".join(lines)
    for match in re.finditer(r"(?<!`)(`+)(?!`)([\s\S]*?)(?<!`)\1(?!`)", public):
        yield public.count("\n", 0, match.start()) + 1, " ".join(match[2].split())


def unresolved(text: str, paths: set[str], tests: set[str]) -> list[str]:
    return [missing for _, span in _spans(text) for missing in _span_missing(span, paths, tests)]


def differences(root: Path) -> list[str]:
    paths, tests = inventory(root)
    documents = [root / "CLAUDE.md", root / "AGENTS.md"]
    documents.extend(
        root / name
        for name in sorted(paths)
        if name.startswith(".claude/") and name.endswith(".md")
    )
    errors = []
    for document in documents:
        for line, span in _spans(document.read_text(encoding="utf-8")):
            errors.extend(
                f"{document.relative_to(root).as_posix()}:{line}: {missing}"
                for missing in _span_missing(span, paths, tests)
            )
    return errors
