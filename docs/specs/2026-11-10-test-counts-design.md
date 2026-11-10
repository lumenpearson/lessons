# Test counts held against complete runs

Issue #660, part of #626. These are implementation choices made under the
owner's standing instruction to continue autonomously without questions; they
were not separately presented as answers from the owner.

## Intent and boundaries

One committed `docs/counts.json` holds the current server total and Android
total/per-module totals. A standard-library Python tool reads real JUnit reports,
rewrites that file on explicit request, and compares CI's actual complete runs
with it. Existing documentation keeps its manually written sentences; it is not
generated. No application, API, schema, protobuf or Android production code changes.

The baseline at checked #718 head 8d66c4d4 is a real 3643-case server suite and
1797 unique Android JVM cases. These dated figures are seed evidence, not values
to preserve after this batch adds tests. Android's measured module totals are
`:app` 703, `:core:data` 638, `:core:designsystem` 205, `:core:model` 125 and
`:widget` 126. New server figures must come from this batch's complete report.

## Data and report rules

The JSON has exactly `server` (positive integer) and `android` (an object with
positive integer `total` and `modules`, a map of Gradle project names to nonnegative
integers). Booleans are not integers here. Android total equals the module sum.

Server total counts all JUnit test cases, including legitimate skips. Empty,
missing, malformed and failed/error reports are refused with the file and reason.
Duplicate server identities `(classname, name)` are refused. A root must be a
JUnit testsuite/testsuites document; reported counts must agree with its cases.
Suites must occupy the report hierarchy and cases must be direct suite children.
A supplied skipped total must agree with skipped cases, including aggregate suites.

Android modules are discovered from the current literal include declarations in
`android/settings.gradle.kts`, ignoring commented declarations. Unsupported or
empty discovery fails rather than assuming five modules.
Supported includes are unconditional top-level literal calls; conditional, nested,
aliased or otherwise unsupported include uses refuse discovery.
Report paths stay under the supplied Android root. Read `*/build/test-results/*/TEST-*.xml` for discovered
modules. Logical identities include module, class and case name. Variant copies
of the same identity count once; duplicates within the same task are errors.
An included module with no cases contributes zero. Unexpected module reports and
a module set different from the canonical file fail the comparison. This metric
is JVM cases, not instrumentation, screenshots or the number of executions across
build variants.
When every report disappears for a previously positive module, write refuses to
replace the facts unless there is explicit zero-case XML or the operator confirms
the intentional transition with `--allow-empty-module :module`. New genuinely empty
modules can contribute zero without a prior positive measurement being erased.

JUnit cannot prove that a report is fresh or from a complete selection. Explicit
regeneration must use the completed gates; CI repeats the full declared commands
and compares their reports, so a file accidentally written from a narrow run
cannot pass release CI. The tool must not claim stronger provenance than this.

## Tool and interfaces

`server/scripts/check_test_counts.py` has pure functions for JUnit identities,
Android module/report totals, canonical JSON validation and current document
declarations. `CountsError` carries useful refusals; the CLI prints one and exits
nonzero. Tests load the script by path, following the existing migration-test
pattern, so bare pytest never depends on the working directory being on sys.path.

From server, `python scripts/check_test_counts.py server --junit work/pytest.xml`
checks the server count and actual included module names. From Android,
`python ../server/scripts/check_test_counts.py android --root .` checks its totals.
From server, explicit regeneration is
`python scripts/check_test_counts.py write --server-junit work/pytest.xml --android-root ../android`.
Write gathers both sides before atomically replacing the one canonical file; it
does not partially replace one side or edit documents.
First run both complete raw test commands successfully. Their following count
comparisons may fail during an intentional count/module-set change. Regenerate,
update current declarations, then repeat every gate and post-check before publication.
Actual module-name equality is a post-run invariant, not a raw-pytest prerequisite;
adding/removing a module therefore cannot block the successful raw report needed
by the writer. Tests exercise that sequence for added/removed empty modules.

Pytest's existing configuration gains a default JUnit path `work/pytest.xml`, so
the unchanged bare primary gate produces the artifact the following check needs.
An explicit command-line JUnit path still overrides it for owned parallel runs.
Narrow development runs may overwrite the default report; the full gate must be
run before the check or regeneration. No new test dependency or plugin is added.

## Document rule and CI

Module discovery strips line and nested block comments before reading literal
include calls, preserving quoted/raw strings as strings. Comments inside the
argument list are whitespace; an unterminated block comment refuses discovery.
Kotlin permits nested block comments ([language documentation](https://kotlinlang.org/docs/basic-syntax.html#comments));
a flat comment regex must not turn commented includes into projects.

`server/tests/test_counts.py` holds the seven current server declarations in
HANDOVER, README, architecture, CLAUDE, CONTRIBUTING, the gates skill and the
server-tests agent. It also holds the four existing Android declarations in
HANDOVER, README, architecture and the gates skill. Each expected declaration
must occur exactly once and agree with JSON. Anchors identify current declarations,
not every integer or historical run in a document. Missing/renamed anchors fail.
The current opening paragraph of architecture's Testing section also declares the
per-module Android breakdown. Its module names/counts must match the full JSON map,
including new zero-case modules; missing, duplicate and unlisted entries fail.
Later paragraphs and earlier sections remain historical evidence.
History and dated previous-run evidence remain unchanged; future current
declarations must join this explicit registry rather than silently going unchecked.

CI adds one post-run comparison to each existing job. Android uses the same
Python 3.12 setup action already pinned in Server. No new job is introduced.
Both command declarations and their permitted prefixes are updated together,
as #659 now requires. Counts-file changes trigger both jobs; script changes
also trigger Android. The server input guard registers the new JSON input.
The server post-check compares canonical module names with the real settings file;
settings-only changes therefore run Server, without triggering it for all client source.
Mutation tests hold both path-filter changes. The extra Android work on a
server-only counts update is intentional: one shared fact file is checked whole.

## Verification and delivery

See the adjacent implementation plan for RED/GREEN cases, local gates and release
order. Test malformed/failed reports, duplicate/variant identities, module changes,
bad JSON, missing current declarations and preserved historical totals. The
final release must have full SQLite, Ruff/mypy, all four native Android gates,
fresh review, exact-head CI, pinned merge, main CI and production verification.
No new full PostgreSQL run is required for this report/file-only batch; #718's
complete application result remains dated evidence.

The operator implements natively in the isolated task-parent checkout. A fresh
builder checks this design before implementation; a fresh reviewer checks the
finished diff. HANDOVER is updated inside this batch's own PR, including the
verified #718 release and any parent-issue closure performed before it.
