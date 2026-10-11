"""CI's «CI» job: the one check a ruleset on `main` requires (#424).

A gate cannot be required by its own name when its `if:` can skip it. «Host»
is a matrix, and GitHub does not expand the matrix of a job its `if:` skips:
it reports one check under the unexpanded `Host (${{ matrix.server }})`, so a
required «Host (pyvoy)» would never arrive on a pull request that changes only
`android/`, which would then wait for it for ever. «CI» needs every other job,
runs whatever they ended as, and passes only when each one succeeded or was
skipped, so requiring it requires all of them and names no matrix leg.

What can drift is the list: a gate added later and left out of `needs` would
be required by nothing. So the job is held here to every other job in the
file, and its one step to the verdict it is there to give.
"""

from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci.yml"


def _jobs() -> dict[str, Any]:
    return yaml.safe_load(CI.read_text(encoding="utf-8"))["jobs"]


def test_the_summary_job_needs_every_other_job_and_runs_whatever_they_ended_as() -> None:
    jobs = _jobs()
    summary = jobs["ci"]
    assert summary["name"] == "CI"
    assert sorted(summary["needs"]) == sorted(name for name in jobs if name != "ci")
    # `always()`, not success(): a skipped or failed gate must still be judged
    # here, or the required check is itself skipped, which a ruleset counts as
    # passed.
    assert summary["if"] == "always()"
    assert summary["runs-on"] == "ubuntu-latest"
    assert summary["timeout-minutes"] <= 5


@pytest.mark.parametrize(
    ("results", "passes"),
    [
        ("success success success success success", True),
        ("success skipped success skipped skipped", True),
        ("success skipped skipped skipped success", True),
        ("success skipped success failure skipped", False),
        ("success success cancelled success success", False),
        ("failure skipped skipped skipped skipped", False),
    ],
)
def test_the_summary_passes_only_when_every_gate_passed_or_was_skipped(
    results: str, passes: bool
) -> None:
    bash = shutil.which("bash")
    if bash is None:
        pytest.skip("no bash to run the step's script with")
    (step,) = _jobs()["ci"]["steps"]
    assert step["env"]["RESULTS"] == "${{ join(needs.*.result, ' ') }}"
    ran = subprocess.run(
        [bash, "-c", step["run"]],
        env={**os.environ, "RESULTS": results},
        capture_output=True,
        text=True,
        check=False,
    )
    assert (ran.returncode == 0) is passes, ran.stdout + ran.stderr
