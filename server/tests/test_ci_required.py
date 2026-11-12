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

import json
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


def test_integration_and_production_pushes_run_ci() -> None:
    workflow = yaml.safe_load(CI.read_text(encoding="utf-8"))
    events = workflow.get("on") or workflow[True]
    assert set(events["push"]["branches"]) == {"master", "dev"}
    assert "pull_request" in events


@pytest.mark.parametrize(
    "ref,allowed",
    [("refs/heads/master", True), ("refs/heads/dev", False),
     ("refs/heads/work/example", False), ("refs/heads/main", False),
     ("refs/tags/v0.1.0", True)],
)
def test_production_signing_admits_only_master_and_version_tags(ref, allowed, tmp_path) -> None:
    bash = shutil.which("bash")
    assert bash is not None
    apk = yaml.safe_load((ROOT / ".github/workflows/apk.yml").read_text(encoding="utf-8"))
    steps = apk["jobs"]["build"]["steps"]
    keystore = next(step for step in steps if step.get("id") == "keystore")
    assert keystore.get("if") == (
        "github.ref == 'refs/heads/master' || startsWith(github.ref, 'refs/tags/v')"
    ), "Reject non-production refs before the runner supplies signing inputs"
    for step in steps:
        if step is keystore:
            continue
        for expression in step.get("env", {}).values():
            if "secrets.KEY" in str(expression):
                assert "steps.keystore.outputs.signed == 'true' &&" in expression
                assert "|| ''" in expression
    scripts = [step["run"] for step in steps if "run" in step]
    signing = next(script for script in scripts if 'case "$REF" in' in script)
    decision = signing[signing.index('case "$REF" in'):]
    decision = decision[:decision.index("esac") + len("esac")]
    result = subprocess.run(
        [bash, "-c", decision + "\nprintf 'SIGNING_ALLOWED\\n'"],
        env={**os.environ, "REF": ref, "GITHUB_OUTPUT": str(tmp_path / "outputs")},
        text=True, capture_output=True, check=False,
    )
    assert result.returncode == 0, result.stdout + result.stderr
    assert ("SIGNING_ALLOWED" in result.stdout) is allowed


@pytest.mark.parametrize(
    "expected,actual,allowed",
    [("", "a" * 40, True), ("a" * 40, "a" * 40, True),
     ("a" * 40, "b" * 40, False), ("not-a-commit", "a" * 40, False)],
)
def test_requested_apk_source_is_checked_before_signing(expected, actual, allowed) -> None:
    apk = yaml.safe_load((ROOT / ".github/workflows/apk.yml").read_text(encoding="utf-8"))
    events = apk.get("on") or apk[True]
    assert events["workflow_dispatch"]["inputs"].get("expected_commit", {}).get("type") == "string"
    steps = apk["jobs"]["build"]["steps"]
    source = next((step for step in steps if step.get("id") == "source"), None)
    assert source is not None, "A moving master ref must be checked before signing inputs"
    assert source["env"]["EXPECTED_COMMIT"] == "${{ inputs.expected_commit }}"
    keystore_index = next(i for i, step in enumerate(steps) if step.get("id") == "keystore")
    assert steps.index(source) < keystore_index
    bash = shutil.which("bash")
    assert bash is not None
    result = subprocess.run(
        [bash, "-c", source["run"]],
        env={**os.environ, "EXPECTED_COMMIT": expected, "GITHUB_SHA": actual},
        capture_output=True, text=True, check=False,
    )
    assert (result.returncode == 0) is allowed, result.stdout + result.stderr


@pytest.mark.parametrize(
    "native,build_type,signed,test_fails,expected_calls,passes",
    [
        (False, "release", "true", False, [["test", "assembleRelease"]], True),
        (False, "debug", "false", False, [["test", "assembleDebug"]], True),
        (False, "both", "true", False, [["test", "assembleDebug", "assembleRelease"]], True),
        (True, "release", "true", False,
         [["test"], ["assembleRelease", ":app:assembleReleaseAndroidTest",
                       "-Plessons.nativeCheck=true"]], True),
        (True, "release", "false", False, [], False),
        (True, "release", "true", True, [["test"]], False),
        (False, "release", "true", True, [["test", "assembleRelease"]], False),
    ],
    ids=["ordinary-release", "ordinary-debug", "ordinary-both", "native",
         "native-unsigned", "native-test-failure", "ordinary-test-failure"],
)
def test_native_apk_packaging_does_not_change_the_complete_jvm_gate(
    native, build_type, signed, test_fails, expected_calls, passes, tmp_path,
) -> None:
    bash = shutil.which("bash")
    assert bash is not None
    apk = yaml.safe_load((ROOT / ".github/workflows/apk.yml").read_text(encoding="utf-8"))
    build = next(step for step in apk["jobs"]["build"]["steps"] if step.get("name") == "Build")
    trace = tmp_path / "calls"
    gradle = tmp_path / "gradlew"
    gradle.write_text(
        '#!/usr/bin/env bash\nprintf "%s\\n" "$*" >> "$TRACE"\n'
        'case " $* " in *" test "*) [ "$FAKE_TEST_FAILS" != "true" ] || exit 17 ;; esac\n',
        encoding="utf-8",
    )
    gradle.chmod(0o755)
    ran = subprocess.run(
        [bash, "-e", "-c", build["run"]], cwd=tmp_path,
        env={**os.environ, "INPUT_NATIVE_CHECK": str(native).lower(),
             "INPUT_BUILD_TYPE": build_type, "RELEASE_SIGNED": signed,
             "FAKE_TEST_FAILS": str(test_fails).lower(), "TRACE": trace.as_posix()},
        capture_output=True, text=True, check=False, timeout=30,
    )
    calls = [line.split() for line in trace.read_text().splitlines()] if trace.exists() else []
    assert calls == expected_calls, ran.stdout + ran.stderr
    assert (ran.returncode == 0) is passes, ran.stdout + ran.stderr


@pytest.mark.parametrize(
    "app_signers,test_signers,valid,passes",
    [
        ([('Signer #1', 'a')], [('Signer #1', 'a')], True, True),
        ([('V2 Signer:', 'a')], [('V2 Signer:', 'a')], True, True),
        ([('Signer #1', 'a')], [('V2 Signer:', 'a')], True, True),
        ([('V1 Signer:', 'a'), ('V2 Signer:', 'a')], [('V2 Signer:', 'a')], True, True),
        ([('V2 Signer:', 'a'), ('Source Stamp Signer:', 'b')],
         [('V2 Signer:', 'a')], True, True),
        ([('V2 Signer:', 'a')], [('V2 Signer:', 'b')], True, False),
        ([('Signer #1', 'a')], [('Signer #1', 'a')], False, False),
        ([], [('Signer #1', 'a')], True, False),
        ([('Signer #1', 'a')], [], True, False),
        ([('Source Stamp Signer:', 'a')], [('V2 Signer:', 'a')], True, False),
        ([('Signer #1', 'a'), ('Signer #2', 'b')], [('Signer #1', 'a')], True, False),
    ],
    ids=["legacy", "build-tools37", "mixed-tools", "same-key-schemes", "ignore-source-stamp",
         "different-keys", "invalid-signature", "missing-app", "missing-test",
         "source-stamp-only", "additional-key"],
)
def test_native_apk_pair_requires_one_common_verified_signing_identity(
    app_signers, test_signers, valid, passes, tmp_path,
) -> None:
    bash = shutil.which("bash")
    assert bash is not None
    apk = yaml.safe_load((ROOT / ".github/workflows/apk.yml").read_text(encoding="utf-8"))
    verify = next(step for step in apk["jobs"]["build"]["steps"]
                  if step.get("name") == "Verify native APK pair")
    signer = tmp_path / "sdk/build-tools/37.0.0/apksigner"
    signer.parent.mkdir(parents=True)
    signer.write_text(
        '#!/usr/bin/env bash\ncase "$3" in\n'
        '*/app-release.apk) printf "%s" "$FAKE_APP_CERTS"; '
        '[ "$FAKE_VALID" = "true" ] || exit 9 ;;\n'
        '*/app-release-androidTest.apk) printf "%s" "$FAKE_TEST_CERTS" ;;\n'
        '*) exit 19 ;;\nesac\n', encoding="utf-8",
    )
    signer.chmod(0o755)
    for relative in ("app/build/outputs/apk/release/app-release.apk",
                     "app/build/outputs/apk/androidTest/release/app-release-androidTest.apk"):
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"Local shell-control fixture, not an installable APK")

    def output(signers):
        return "".join(f"{name} certificate SHA-256 digest: {digit * 64}\n"
                       for name, digit in signers)

    ran = subprocess.run(
        [bash, "-e", "-c", verify["run"]], cwd=tmp_path,
        env={**os.environ, "ANDROID_HOME": (tmp_path / "sdk").as_posix(),
             "RUNNER_TEMP": tmp_path.as_posix(), "VERSION_CODE": "1", "GITHUB_SHA": "c" * 40,
             "FAKE_APP_CERTS": output(app_signers), "FAKE_TEST_CERTS": output(test_signers),
             "FAKE_VALID": str(valid).lower()},
        capture_output=True, text=True, check=False, timeout=30,
    )
    assert (ran.returncode == 0) is passes, ran.stdout + ran.stderr
    receipt = tmp_path / "native-check/native-check.json"
    assert receipt.exists() is passes
    if passes:
        metadata = json.loads(receipt.read_text())
        assert metadata["certificate_sha256"] == "a" * 64
        assert metadata["source_commit"] == "c" * 40


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
