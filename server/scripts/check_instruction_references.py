"""Check current agent navigation against regular public source and test definitions."""

import ast
import fnmatch
import re
import shlex
import stat
import subprocess
from functools import lru_cache
from pathlib import Path

EXTENSIONS = {
    ".kt",
    ".py",
    ".md",
    ".xml",
    ".kts",
    ".yml",
    ".yaml",
    ".json",
    ".conf",
    ".toml",
    ".txt",
    ".ttf",
    ".properties",
    ".sh",
    ".in",
    ".lock",
    ".proto",
    ".png",
    ".svg",
    ".html",
    ".bat",
    ".pro",
}
SOURCE_NAMES = {
    "Dockerfile",
    "Makefile",
    "gradlew",
    ".gitignore",
    ".dockerignore",
    ".vercelignore",
    ".python-version",
}
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


def _public_file(root: Path, name: str) -> bool:
    path = root / name
    if OUTPUTS.intersection(Path(name).parts) or any(
        part.startswith(".env") for part in Path(name).parts
    ):
        return False
    try:
        return (
            stat.S_ISREG(path.lstat().st_mode)
            and path.resolve().is_relative_to(root)
            and not any(parent.is_symlink() for parent in path.parents if parent != root)
        )
    except OSError:
        return False


def _comment_end(text: str, index: int) -> int:
    if text.startswith("//", index):
        end = text.find("\n", index)
        return len(text) if end < 0 else end
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
    return index


def _template_end(text: str, index: int) -> int:
    depth = 1
    while index < len(text) and depth:
        if text.startswith(("//", "/*"), index):
            index = _comment_end(text, index)
        elif text[index] in {'"', "'"}:
            index = _string_end(text, index)
        else:
            depth += (text[index] == "{") - (text[index] == "}")
            index += 1
    return index


def _string_end(text: str, index: int) -> int:
    quote = '"""' if text.startswith('"""', index) else text[index]
    index += len(quote)
    while index < len(text):
        if text.startswith(quote, index):
            return index + len(quote)
        if quote != '"""' and text[index] == "\\":
            index += 2
        elif quote != "'" and text.startswith("${", index):
            index = _template_end(text, index + 2)
        else:
            index += 1
    return index


def _kotlin_code(text: str) -> str:
    result = []
    index = 0
    while index < len(text):
        if text.startswith(("//", "/*"), index):
            index = _comment_end(text, index)
            result.append(" ")
        elif text[index] in {'"', "'"}:
            index = _string_end(text, index)
            result.append(" ")
        else:
            result.append(text[index])
            index += 1
    return "".join(result)


class TestDefinitions(set[str]):
    """Standalone names plus the owners needed by explicit pytest selectors."""

    def __init__(self, root: Path):
        super().__init__()
        self.root = root
        self.nodes: dict[str, set[str]] = {}
        self.module_symbols: dict[str, set[str]] = {}

    def module_has(self, name: str, symbol: str) -> bool:
        if not _public_file(self.root, name):
            return False
        if name not in self.module_symbols:
            tree = ast.parse((self.root / name).read_text(encoding="utf-8"), filename=name)
            self.module_symbols[name] = {
                item.name
                for item in tree.body
                if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef))
            }
            self.module_symbols[name].update(
                alias.asname or alias.name
                for item in tree.body
                if isinstance(item, (ast.Import, ast.ImportFrom))
                for alias in item.names
            )
        return symbol in self.module_symbols[name]


def _python_nodes(body, prefix="") -> set[str]:
    nodes = set()
    for item in body:
        if isinstance(item, (ast.FunctionDef, ast.AsyncFunctionDef)) and item.name.startswith(
            "test_"
        ):
            nodes.add(prefix + item.name)
        elif isinstance(item, ast.ClassDef) and (
            item.name.startswith("Test")
            or any(
                isinstance(base, ast.Name)
                and base.id == "TestCase"
                or isinstance(base, ast.Attribute)
                and base.attr == "TestCase"
                for base in item.bases
            )
        ):
            nodes.add(prefix + item.name)
            nodes.update(_python_nodes(item.body, prefix + item.name + "::"))
    return nodes


def inventory(root: Path) -> tuple[set[str], TestDefinitions]:
    root = root.resolve()
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
    paths = {name for name in names if name and _public_file(root, name)}
    tests = TestDefinitions(root)
    for name in sorted(paths):
        path = root / name
        if (
            "/tests/" in name
            and path.suffix == ".py"
            and (path.name.startswith("test_") or path.name.endswith("_test.py"))
        ):
            if not _public_file(root, name):
                continue
            tree = ast.parse(path.read_text(encoding="utf-8"), filename=name)
            nodes = _python_nodes(tree.body)
            tests.nodes[name] = nodes
            tests.update(part for node in nodes for part in node.split("::"))
        elif path.suffix == ".kt" and any(p in name for p in ("/src/test/", "/src/androidTest/")):
            if _public_file(root, name):
                tests.update(
                    re.findall(
                        r"\bclass\s+(\w+Test)\b", _kotlin_code(path.read_text(encoding="utf-8"))
                    )
                )
    return paths, tests


def _non_source(token: str) -> bool:
    return (
        "://" in token
        or "<" in token
        or "…" in token
        or Path(token).name
        in {".mcp.json", "settings.local.json", "local.properties", "keystore.properties"}
        or Path(token).suffix in {".jks", ".keystore"}
        or token.startswith("::")
        or token in {"Europe/Moscow", "~/.gradle/gradle.properties"}
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
def _path_parts(paths: frozenset[str]):
    directories = {
        parent.as_posix() for name in paths for parent in Path(name).parents if parent != Path(".")
    }
    return ({p: tuple(p.split("/")) for p in paths}, {p: tuple(p.split("/")) for p in directories})


def _parts_match(parts: tuple[str, ...], pattern: tuple[str, ...]) -> bool:
    if not pattern:
        return not parts
    if pattern[0] in {"...", "**"}:
        return any(_parts_match(parts[i:], pattern[1:]) for i in range(len(parts) + 1))
    return (
        bool(parts)
        and fnmatch.fnmatchcase(parts[0], pattern[0])
        and _parts_match(parts[1:], pattern[1:])
    )


def _matching(token: str, paths: set[str], tests: set[str]) -> set[str]:
    token = token.removeprefix("./").lstrip("/")
    while token.startswith("../"):
        token = token[3:]
    token = token.split(":", 1)[0]
    is_directory = token.endswith("/")
    pattern = tuple(token.rstrip("/").split("/"))
    files, directories = _path_parts(frozenset(paths))
    candidates = directories if is_directory else files
    matches = {
        name
        for name, parts in candidates.items()
        if any(_parts_match(parts[i:], pattern) for i in range(len(parts)))
    }
    if not is_directory and not Path(token).suffix and token not in SOURCE_NAMES:
        matches.update(
            name
            for name, parts in directories.items()
            if any(_parts_match(parts[i:], pattern) for i in range(len(parts)))
        )
        for suffix in (".py", "/__init__.py"):
            p = tuple((token + suffix).split("/"))
            matches.update(
                name
                for name, parts in files.items()
                if any(_parts_match(parts[i:], p) for i in range(len(parts)))
            )
    if (
        not matches
        and isinstance(tests, TestDefinitions)
        and "/" in token
        and (Path(token).suffix not in EXTENSIONS and "." in Path(token).name)
    ):
        module, symbol = token.rsplit(".", 1)
        for candidate in _matching(module + ".py", paths, set()):
            if tests.module_has(candidate, symbol):
                matches.add(candidate)
    return matches


def _test_name(token: str) -> bool:
    return bool(re.fullmatch(r"test_\w+|\w+Test|Test\w+", token))


def _missing(token: str, paths: set[str], tests: set[str]) -> list[str]:
    if _non_source(token):
        return []
    if "::" in token:
        source, *selectors = token.split("::")
        selected = "::".join(s.split("[", 1)[0] for s in selectors)
        files = _matching(source, paths, tests)
        if isinstance(tests, TestDefinitions):
            return [] if any(selected in tests.nodes.get(f, set()) for f in files) else [token]
        return _missing(source, paths, tests) + [s for s in selectors if s not in tests]
    token = token.split(":", 1)[0]
    if not re.fullmatch(r"[\w.*:/?\[\]!-]+", token):
        return []
    if _test_name(token):
        return [] if token in tests else [token]
    if "/" in token or Path(token).suffix in EXTENSIONS or token in SOURCE_NAMES:
        return [] if _matching(token, paths, tests) else [token]
    return []


def _keyword_matches(keyword: str, words: list[str], paths: set[str], tests: set[str]) -> bool:
    if not isinstance(tests, TestDefinitions):
        return any(keyword.lower() in name.lower() for name in tests)
    owners = set()
    qualified = False
    for word in words:
        source = word.split("::", 1)[0]
        if source.endswith(".py"):
            qualified = True
            owners.update(_matching(source, paths, tests))
    if not qualified:
        owners = set(tests.nodes)
    return any(
        keyword.lower() in (name + " " + node).lower()
        for name in owners
        for node in tests.nodes.get(name, set())
    )


def _local_module_missing(module: str, paths: set[str], tests: set[str]) -> bool:
    if module.split(".", 1)[0] not in {"app", "scripts", "tests", "server", "api"}:
        return False
    stem = module.replace(".", "/")
    return not (
        _matching(stem + ".py", paths, tests) or _matching(stem + "/__main__.py", paths, tests)
    )


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
    skip = selector = keyword = False
    python_module = first in {"python", "python3"}
    module_argument = False
    for word in words:
        if skip:
            skip = False
            continue
        if module_argument:
            if _local_module_missing(word, paths, tests):
                errors.append(word)
            module_argument = False
            python_module = False
            continue
        if selector:
            if not any(fnmatch.fnmatchcase(name, word) for name in tests):
                errors.append(word)
            selector = False
            continue
        if keyword:
            if re.fullmatch(r"\w+", word) and not _keyword_matches(word, words, paths, tests):
                errors.append(word)
            keyword = False
            continue
        if word == "--tests":
            selector = True
        elif word == "-k":
            keyword = True
        elif word == "-m" and python_module:
            module_argument = True
        elif word in {"--junitxml", "--junit", "--server-junit", "--root", "--android-root", "-m"}:
            skip = True
        elif word.startswith(("-", ":")) or "=" in word:
            continue
        elif first == "git" and word in {"origin/main", "origin/dev"}:
            continue
        elif first == "ruff" and word in {"app", "tests", "scripts", "migrations"}:
            if not _matching(word + "/", paths, tests):
                errors.append(word)
        else:
            errors.extend(_missing(word, paths, tests))
    return errors


def _spans(text: str):
    fence = None
    lines = []
    for line in text.splitlines():
        marker = re.match(r" {0,3}(`{3,}|~{3,})(.*)$", line)
        if marker and fence is None:
            fence = (marker[1][0], len(marker[1]))
            lines.append("")
        elif fence is not None:
            if (
                marker
                and marker[1][0] == fence[0]
                and len(marker[1]) >= fence[1]
                and not marker[2].strip()
            ):
                fence = None
            lines.append("")
        else:
            lines.append(line)
    public = "\n".join(lines)
    for match in re.finditer(r"(?<!`)(`+)(?!`)([\s\S]*?)(?<!`)\1(?!`)", public):
        yield public.count("\n", 0, match.start()) + 1, " ".join(match[2].split())


def unresolved(text: str, paths: set[str], tests: set[str]) -> list[str]:
    return [missing for _, span in _spans(text) for missing in _span_missing(span, paths, tests)]


def differences(root: Path) -> list[str]:
    root = root.resolve()
    paths, tests = inventory(root)
    documents = ["CLAUDE.md", "AGENTS.md"]
    documents.extend(
        name for name in sorted(paths) if name.startswith(".claude/") and name.endswith(".md")
    )
    errors = []
    for name in documents:
        if name not in paths or not _public_file(root, name):
            errors.append(f"{name}: missing regular public instruction document")
            continue
        for line, span in _spans((root / name).read_text(encoding="utf-8")):
            errors.extend(
                f"{name}:{line}: {missing}" for missing in _span_missing(span, paths, tests)
            )
    return errors
