"""A change to a file the server's tests read runs the server's tests on CI.

`ci.yml`'s «What changed» job decides from the changed paths whether the
server job runs at all, and a commit whose paths match none of its patterns
runs no server test and comes back green. The patterns named the server's own
tree and the documents #159 had found, while the suite read more than that:
`test_schema_version` reads every document that can name the migration head -
`README.md`, `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`,
every Markdown file under `docs/` and `.claude/` - and fails on one that names
an old head, and the contract tests read the proto sources. A commit touching
only `CLAUDE.md` or a skill ran nothing, so a stale head in one of them could
merge green and fail the next commit that did run the suite (#295).

So the two lists are held level here: every file outside `server/` that the
suite reads is matched by a pattern of the server's arm of that `case`.
"""

from __future__ import annotations

import ast
import fnmatch
import re
from pathlib import Path

import yaml

SERVER = Path(__file__).resolve().parents[1]
ROOT = SERVER.parent
CI = ROOT / ".github" / "workflows" / "ci.yml"

#: Every file outside `server/` that a server test reads, as a glob over the
#: repository, and the test that reads it. The documents `test_schema_version`
#: reads are not listed: they come from its own fixture, `head_documents`.
READ_BY_THE_SUITE = {
    ".github/workflows/*.yml": "test_env_example, for the secrets; this file, for ci.yml",
    "docs/build.md": "test_env_example",
    "docs/deploy.md": "test_corrections_per_child_revision, test_deployment_config",
    "docs/diaries.md": "test_region_catalog, through scripts/region_catalog.py",
    "docs/diaries/*.md": "test_region_catalog, through scripts/region_catalog.py",
    "proto/lessons/v2/*.proto": "test_contract, test_contract_json, test_contract_mirror",
    "buf.yaml": "test_contract",
    "buf.gen.yaml": "test_contract",
    "buf.lock": "test_contract",
    "requirements.in": "test_requirements_mirror, test_contract, test_diary_client",
    "requirements.txt": "test_requirements_mirror",
    ".python-version": "test_requirements_mirror",
    "docker-compose.yml": "test_compose",
    "api/*.py": "test_vercel_entry",
    "vercel.json": "test_vercel_entry",
    ".vercelignore": "test_region_catalog",
}


def _server_patterns() -> list[str]:
    """The patterns of the `case` arm that sets `server=true`, as bash reads them."""
    workflow = yaml.safe_load(CI.read_text(encoding="utf-8"))
    script = next(
        step["run"] for step in workflow["jobs"]["changes"]["steps"] if step.get("id") == "filter"
    )
    arms = re.findall(r"^\s*(\S+)\)\s+server=true\s*;;", script, re.MULTILINE)
    assert len(arms) == 1, f"expected one `case` arm setting server=true in {CI.name}, found {arms}"
    return arms[0].split("|")


def _runs_the_server(path: str, patterns: list[str]) -> bool:
    """Whether a commit touching only `path` runs the server job.

    `fnmatchcase` is bash's `case` for these patterns: both match the whole
    path, and in both `*` crosses a `/`, which is why «docs/*.md» covers every
    Markdown file under `docs/` at any depth.
    """
    return any(fnmatch.fnmatchcase(path, pattern) for pattern in patterns)


def _relative(paths: list[Path]) -> list[str]:
    return [path.relative_to(ROOT).as_posix() for path in paths]


def _read_by_the_suite(head_documents) -> dict[str, str]:
    """Each file outside `server/` the suite reads, and the test that reads it."""
    read = {
        document: "test_schema_version, through head_documents"
        for document in _relative(head_documents())
    }
    for pattern, reader in READ_BY_THE_SUITE.items():
        matched = _relative(sorted(ROOT.glob(pattern)))
        assert matched, f"{pattern} matches no file; if nothing reads it any more, drop it here"
        read.update(dict.fromkeys(matched, reader))
    return read


def test_a_change_to_anything_the_suite_reads_runs_the_suite(head_documents):
    patterns = _server_patterns()
    unrun = {
        path: reader
        for path, reader in sorted(_read_by_the_suite(head_documents).items())
        if not _runs_the_server(path, patterns)
    }
    assert not unrun, "\n".join(
        f"a server test reads {path} ({reader}), but a change to {path} alone "
        f"runs no server test: add it to the server patterns in {CI.name}"
        for path, reader in unrun.items()
    )


def test_the_documents_are_the_ones_the_head_is_read_from(head_documents):
    """The fixture is the list test_schema_version reads, and it is not empty:
    an empty one would make the test above pass by checking nothing."""
    documents = _relative(head_documents())
    assert "CLAUDE.md" in documents
    assert any(document.startswith(".claude/skills/") for document in documents)
    assert "docs/history.md" not in documents


def _names_at_the_root(source: str) -> set[str]:
    """String constants in `source` that are the name of something at the
    repository's root, as in ``ROOT / "vercel.json"``."""
    entries = {entry.name for entry in ROOT.iterdir()} - {"server", ".git"}
    return {
        node.value
        for node in ast.walk(ast.parse(source))
        if isinstance(node, ast.Constant) and node.value in entries
    }


def test_every_name_at_the_root_a_test_spells_is_in_the_list(head_documents):
    """The list above is written by hand; this is what keeps it from going stale.

    A server test or script that builds a path from the repository's root
    spells the first part of it, so a test that starts reading `HANDOVER.md`
    or something under `android/` names it here before it is in the list, and
    so before the patterns are asked about it. It sees the root's names only,
    so a new file under a directory already listed is the list's to say.
    """
    tops = {path.split("/")[0] for path in _read_by_the_suite(head_documents)}
    unlisted = {
        f"{source.relative_to(SERVER).as_posix()} names {name}"
        for source in sorted([*(SERVER / "tests").glob("*.py"), *(SERVER / "scripts").glob("*.py")])
        for name in _names_at_the_root(source.read_text(encoding="utf-8"))
        if name not in tops
    }
    assert not unlisted, (
        f"{sorted(unlisted)}: if a server test reads it, put it in READ_BY_THE_SUITE "
        f"with the test that reads it, and in the server patterns of {CI.name}"
    )
