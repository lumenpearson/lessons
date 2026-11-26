# Lessons console

Local developer tooling, launched from the repository root with
`uv run --project tools/console lessons-console`. Python 3.12 is required.

Implementation checklist (6a):

- [x] Closed Build and Contract tasks with current CI parity checks.
- [x] Visible queue, process-tree cancellation and conventional machine lock.
- [x] Redacted live output and retained run history.
- [x] Checkout-local server environment, JDK 21 and pinned Buf discovery.
- [x] Working Environment and Docs tabs, with values never displayed.
- [x] Lightweight behavior tests using fake processes and Textual's pilot.

Build offers individual gates, ordered server/Android batches, and Prepare. Prepare
installs the root requirements lock and editable server dev tools into this
checkout's own environment. It is a heavy operation and never silently runs when
another task is selected. Missing prerequisites disable a task with an explanation;
Refresh repeats discovery after preparation. The server interpreter is probed in
isolated mode so another checkout's editable installation cannot be mistaken for
this one. Each server run records the Ruff and mypy versions before its command.

Contract fetches the Buf binary specified by CI and the platform checksum table in
the build documentation. It verifies both the bytes and reported version, with a
versioned cache outside the repository. Generate writes the committed sources;
Freshness generates into temporary storage and compares both generated trees,
ignoring Python bytecode and normalizing CRLF. Breaking fetches origin/master;
that declared local baseline differs from CI's pull-request base.

The console starts idle heavy work directly, or queues behind another console's
kernel lock. When an Android emulator is detected it asks once for the selected
run before starting alongside it. Cancellation and quitting stop the owned process
tree before releasing the heavy lock. The lock is held by the operating system;
dead-owner PID metadata is never used to steal an active lock. On Windows a child
is suspended until assigned to a kill-on-close Job Object.
Console Gradle invocations use `--no-daemon`, alongside the local worker/output
flags. This is a declared difference from CI's daemon reuse: the console must own
the complete build lifetime and release the queue when its command finishes.

Logs are sanitized before display and before writing to `.runs/`. Build's history
selector opens retained transcripts from earlier sessions. Every known secret is
matched in its literal and encoded forms, and credential-shaped headers/URLs are
scrubbed too. Values shorter than eight characters cannot safely be matched within
ordinary text; the run displays that limitation. Server dotenv files contribute
names only to Environment, never values to either the table or transcript. The
console never opens the machine's Gradle properties or signing files. Signing
status remains unknown until the separate safe Gradle status task is available.

The default cache and conventional lock live under the user's local application
data directory on Windows or cache directory elsewhere. `LESSONS_CONSOLE_CACHE`
overrides that location; all consoles sharing a machine must use the same location.
Do not use separate per-worktree overrides for real heavy work. Unit tests inject
their own temporary lock paths. Tool downloads and Prepare require network access;
the console never installs a JDK, Android SDK, adb or uv.

The console lock serializes console jobs across worktrees. External terminals and
agents must still follow their resource coordinator. An idle console lock is not
permission to start work beside somebody else's build or device session.

Host execution and transport selectors belong to 6c. The represented gates do not establish full CI, signed-device or production
acceptance. Tests never execute Gradle, Buf or adb.

Device and Deploy are implemented in 6b. Device's Refresh lists only online serials
and recognized single-APK AGP outputs from this checkout. Select both before Check
install or Install APK. Installation copies the selected hash into a private
snapshot, verifies its certificate and the installed base APK, refuses a different
signer or lower version, installs only with `-r`, and reads the installed version
back. A failed package probe never means the package is absent. There is no
uninstall, data-clear, downgrade, adb restart or automatic retry action.

Device operations require an already-running compatible local Android bridge on
127.0.0.1:5037. Start Android Studio or run `adb start-server`, then Refresh. The
console never starts, kills or resets that shared server. Custom server-related
environment settings are refused with an explanation rather than silently routing
to another bridge. Tool discovery reads paths and JDK release files only; signing
configuration files are never opened. Missing adb, apksigner or JDK 21 prevents
the corresponding operation.

App logcat is filtered to one selected app PID. It stops when that process stops
or changes; it never broadens to all device logs. Output is redacted, remains only
in the active screen buffer, never opens a transcript and is cleared on stop.
The reverse controls touch only tcp:8000 to tcp:8000, with explicit readback; a
conflicting mapping is refused and unrelated ports are left intact. Every targeted
adb command retains the selected serial. Changing selection or origin invalidates
pending replies rather than applying an old result to the new target.

Deploy reads `/api/v1/warmup` at an explicit HTTPS origin, supplied through
`LESSONS_PRODUCTION_ORIGIN` or the input field. It sends no credentials and follows
no redirects, with an eight-second complete-request deadline and a 64 KiB response
limit. The screen separates production's reported condition/schema from readiness
for this checkout's unique literal EXPECTED_REVISION. Healthy replies normally omit
expected_schema; database-down replies cannot establish compatibility. Arbitrary
response/error bodies are never displayed. There is no deploy button.

The 6b tests inject all device tools and HTTP responses. Actual device installation,
logcat, reverse mapping and live production readiness require their own observed
runtime checks; fixture results do not establish them.

From this directory, the two CI commands are:

```
uv run --locked ruff check lessons_console tests
uv run --locked pytest -q
```

CI runs the lightweight suite on Linux and Windows. The tests use fake subprocesses,
including a grandchild cancellation witness, and Textual's headless pilot. Updating
`lessons_console/ci_contract.json` requires reviewing the changed CI scripts,
conditions, actions and their local equivalents; regenerating a fingerprint alone
does not establish equivalence. Host and CI aggregation are explicit exceptions,
and the console suite does not recursively invoke itself.

Pinned versions were read from primary PyPI release metadata on 26 November 2026:
[Textual 8.2.8](https://pypi.org/project/textual/8.2.8/),
[PyYAML 6.0.4](https://pypi.org/project/PyYAML/6.0.4/),
[pytest 9.2.0](https://pypi.org/project/pytest/9.2.0/),
[pytest-asyncio 1.5.0](https://pypi.org/project/pytest-asyncio/1.5.0/), and
[Ruff 0.17.6](https://pypi.org/project/ruff/0.17.6/).
