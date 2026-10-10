"""CI's «Host» job, read against the tests it runs and the host it starts.

The job is the one place the host target runs (``.github/workflows/ci.yml``;
``docs/specs/2026-10-05-server-v2-design.md``, decisions 13 and 14), and it
cannot be run from here: it needs Linux, the host's lock and a network. What
can be held here is that it asks what ``test_host_live.py`` needs — both
servers, streaming on, no deployment marker, the address and the database
the tests read — and that the ordinary run leaves those tests skipped.
"""

from __future__ import annotations

import os
from pathlib import Path
from typing import Any

import pytest
import yaml

ROOT = Path(__file__).resolve().parents[2]
CI = ROOT / ".github" / "workflows" / "ci.yml"


def _job() -> dict[str, Any]:
    return yaml.safe_load(CI.read_text(encoding="utf-8"))["jobs"]["host"]


def _step(job: dict[str, Any], name: str) -> dict[str, Any]:
    return next(step for step in job["steps"] if step.get("name") == name)


def test_the_job_runs_both_servers_whenever_the_server_job_does() -> None:
    job = _job()
    assert job["needs"] == "changes"
    assert job["if"] == "needs.changes.outputs.server == 'true'"
    assert job["strategy"]["matrix"]["server"] == ["pyvoy", "hypercorn"]
    assert job["runs-on"] == "ubuntu-latest"


def test_the_job_installs_the_image_s_two_locks_and_the_grpc_client() -> None:
    install = _step(_job(), "Install")["run"]
    for part in ("-r ../requirements.txt", "-r requirements-host.txt", '-e ".[dev,host-check]"'):
        assert part in install, part


def test_the_job_starts_the_host_as_a_local_run_does_with_streaming_on() -> None:
    """Without the marker: with it, the settings refusal would reject the
    SQLite default and the host would never start (decision 13)."""
    start = _step(_job(), "Start the host")
    assert "python -m app.host --server ${{ matrix.server }}" in start["run"]
    environment = start["env"]
    assert "LESSONS_TARGET" not in environment and "VERCEL" not in environment
    assert environment["LESSONS_STREAMING"] == "true"
    assert environment["RUN_BOT"] == "false"
    assert environment["DATABASE_URL"].startswith("sqlite+aiosqlite:///")
    # The webhook and the tick mounted, so the tests can find them refusing.
    assert environment["BOT_TOKEN"] and environment["WEBHOOK_SECRET"] and environment["CRON_SECRET"]


def test_the_job_tests_the_host_it_started() -> None:
    job = _job()
    start, test = _step(job, "Start the host"), _step(job, "Test against it")
    assert test["run"] == "pytest -q -p no:xdist -m host"
    assert test["env"]["LESSONS_HOST_DATABASE_URL"] == start["env"]["DATABASE_URL"]
    assert test["env"]["LESSONS_HOST_URL"] == f"http://127.0.0.1:{start['env']['PORT']}"


def test_the_ordinary_run_skips_the_tests_that_need_a_host(request) -> None:
    if os.environ.get("LESSONS_HOST_URL"):
        pytest.skip("a host is running: its tests are not skipped")
    marked = [item for item in request.session.items if item.get_closest_marker("host")]
    if not marked:
        pytest.skip("test_host_live.py is not part of this run")
    assert all(item.get_closest_marker("skip") is not None for item in marked)
