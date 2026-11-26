"""Reviewed CI inventory plus behavioral parity for commands we represent."""

import hashlib
import json
import shlex
from pathlib import Path

import yaml

from lessons_console.environment import buf_release
from lessons_console.tasks import TASKS

CONTRACT = Path(__file__).with_name("ci_contract.json")
SKIPPED_JOBS = {
    "changes": "Routing only; does not test the checkout.",
    "host": "Host lifecycle and live gRPC matrix belong to 6c; 6a does not claim complete CI.",
    "ci": "Aggregates remote job results; there is no equivalent local remote-results gate.",
    "console": "The console's own tests; never recursively launch the console suite.",
}


def inventory(root: Path) -> dict:
    workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    jobs = {}
    for job_name, job in workflow["jobs"].items():
        rows = []
        for index, step in enumerate(job["steps"]):
            script = step.get("run")
            row = {"identity": step.get("name", step.get("id", f"step-{index}"))}
            if script is not None:
                row["run_sha256"] = hashlib.sha256(script.encode()).hexdigest()
            if "uses" in step:
                row["action"] = step["uses"]
                row["with"] = step.get("with", {})
            row["if"] = step.get("if", "success()")
            row["cwd"] = step.get("working-directory", job.get("defaults", {}).get("run", {}).get("working-directory", "."))
            # Environments and failure behavior are part of the equivalent, not comments.
            row["env"] = step.get("env", {})
            row["continue-on-error"] = step.get("continue-on-error", False)
            rows.append(row)
        jobs[job_name] = {"reason": SKIPPED_JOBS.get(job_name, "Represented gates and reviewed setup/artifact steps."),
                          "job": {key: value for key, value in job.items() if key != "steps"},
                          "steps": rows}
    return jobs


def validate_ci(root: Path) -> None:
    expected = json.loads(CONTRACT.read_text(encoding="utf-8"))
    actual = inventory(root)
    if actual != expected:
        raise AssertionError("CI inventory changed: review task equivalence, actions, conditions and script hashes before updating ci_contract.json")
    workflow = yaml.safe_load((root / ".github/workflows/ci.yml").read_text(encoding="utf-8"))
    jobs = workflow["jobs"]
    declared = {task.ci for task in TASKS if task.ci}
    for task in TASKS:
        if not task.ci:
            continue
        job_name, name = task.ci
        if name.startswith("buf:"):
            continue
        step = next((step for step in jobs[job_name]["steps"] if step.get("name") == name), None)
        if step is None:
            raise AssertionError(f"Gate {task.key} has no CI step")
        if task.key not in {"prepare", "contract.fresh"} and tuple(shlex.split(step["run"])) != task.argv:
            raise AssertionError(f"Gate argv drift: {task.key}")
        condition = step.get("if", "success()")
        if (condition == "success() || failure()") != task.always:
            raise AssertionError(f"Gate failure condition drift: {task.key}")
        cwd = step.get("working-directory", jobs[job_name].get("defaults", {}).get("run", {}).get("working-directory", "."))
        if cwd != task.cwd:
            raise AssertionError(f"Gate cwd drift: {task.key}")
    # Nothing executable can disappear under an unclassified new job or action.
    if set(jobs) - {"server", "android", "contract"} - set(SKIPPED_JOBS):
        raise AssertionError("Unclassified CI job")
    for job_name in ("server", "android", "contract"):
        for step in jobs[job_name]["steps"]:
            if ("run" in step and "\n" not in step["run"] and step.get("name")
                    and (job_name, step["name"]) not in declared):
                raise AssertionError("Unrepresented single-line gate")
    buf_step = next(step for step in jobs["contract"]["steps"] if step.get("uses", "").startswith("bufbuild/buf-action@"))
    assert buf_step["with"]["lint"] is True
    assert buf_step["with"]["breaking"] is not False
    assert jobs["server"]["steps"][1]["with"]["python-version"] == "3.12"
    assert any(step.get("with", {}).get("java-version") == "21" for step in jobs["android"]["steps"])
    buf_release(root, windows=False)
