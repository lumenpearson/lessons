# Detekt baselines cannot silently absorb another finding (#635)

The owner requested autonomous delivery of #635 after #660/#661, with full gates,
fresh independent review, pinned merge and production verification. The choices
below are the executor's; questions were explicitly waived. The verified base is
#720's merge6c92e226. Current default baselines contain491entries: app182,
core:data202, core:designsystem73, core:model13, widget21; manually suppressed0.
These values come from the built project, not a guessed future version.

## Intent

The existing JVM suite must fail when detektBaseline adds a finding to any of the
project's default detekt-baseline.xml files. It must print the added entry, without
requiring Git history, a network, Android instrumentation or a new CI job. A move
or removal remains reviewable and cannot leave unused numerical headroom.

Use core:model's existing pure JVM/JUnit4.13.2 suite. Keep implementation helpers
in test source, not the shipped application. No dependency or plugin version changes;
JDK21 and detekt2.0.0-alpha.6 are the versions the project already builds.

## Budget records

Each baseline has a sibling detekt-baseline-budget.xml. Its SmellBaseline root has
one nonnegative maxEntries attribute and the accepted CurrentIssues and
ManuallySuppressedIssues ID sets. The snapshot is necessary to print which IDs are
new; a number alone cannot do that without Git history. Counts include both groups.
Reject malformed roots/groups/IDs, duplicates and mismatched max/snapshot counts.

Current baseline count must equal the budget. Growth fails and prints added IDs;
removal fails until the same PR lowers maxEntries and refreshes accepted IDs. Equal
counts with changed IDs or group membership require an explicit snapshot refresh
after review. Permanent manual suppression is not an ordinary filename move.
Never raise maxEntries to bless a new finding: fix it or use the existing justified
in-place suppression policy. The initial records copy the verified491existing IDs.
No routine budget-regeneration command is introduced that could absorb findings.

The checker discovers baseline/budget pairs recursively under the Android root,
excluding generated build/.gradle trees and directory links; no list of five paths
is baked into the rule. Missing counterparts fail. An empty baseline needs a zero
budget. A future default baseline is covered when it appears. The project currently
uses detekt's default filename in every module; custom baseline bindings require an
explicit extension of discovery and Gradle inputs, not a claim of generic DSL parsing.

Only regular files inside the discovered Android root are parsed. The JDK DOM
reader consumes bytes, preserving encoding declarations and BOMs, and rejects
DOCTYPE/external DTD/schema access and non-whitespace container text. Windows
reparse directories are pruned before descent; resolved real paths hold containment.
Both groups retain membership and are read as data;
no XML or source content is executed. Synthetic link/entity fixtures use no private
files. See the [JDK21 JAXP guide](https://docs.oracle.com/en/java/javase/21/security/java-api-xml-processing-jaxp-security-guide.html).

## Build behavior

core:model's Test inputs include eligible regular records under both default
filenames and its own build script, with relative path sensitivity. A module-local
ValueSource inventories paths/types without following links; rejected types are
metadata inputs, so an identical-content file link cannot reuse a warm success.
It is queried at configuration time and rechecked by the configuration cache on
every build ([Gradle requirements](https://docs.gradle.org/current/userguide/configuration_cache_requirements.html)).
No root cross-configuration or convention plugin. Gradle fingerprints input
contents before deciding UP-TO-DATE ([Gradle incremental builds](https://docs.gradle.org/current/userguide/incremental_build.html));
a JVM assertion alone is insufficient when only a baseline XML changes.

Prove this with owned, byte-backed-up baseline-only and budget-only mutations after
a successful focused invocation. The same invocation must execute and fail; restore
only the known injected bytes, then run all four native gates. Preserve logs and
snapshots; do not delete or reset another session's work. Final application baselines
must be byte-identical to the verified base: no finding is grandfathered by this fix.
Also prove directory-link pruning against the actual configured Test inputs,
regular-to-identical-link invalidation and default-console added-ID diagnostics.

## Verification and limits

RED/GREEN covers growth/added diagnostics, lowering on removal, moves with unchanged
limit, manual suppressions, zero entries, malformed/duplicate records, new nested
pairs, missing counterparts and XML/link boundaries. Measure real JVM reports after
test/assembleDebug/assembleRelease/detekt. Run Ruff/mypy/full server SQLite with Git
Bash selected, then #660 regeneration from completed reports, current declarations
and final document checks. Keep heavy native/server runs separate. No new full PG
or live device/account flow is claimed for this test/build-only change.

The budget XML itself is reviewable policy. Deliberately editing a limit upward or
removing the checker can change policy; this guard prevents an ordinary baseline
rewrite from doing so silently. It does not prove that every old baseline ID still
matches a finding, or turn detekt into a type-resolved gate. #624's prior cleanup is
not repeated. Independent final review, exact-head PR/main CI, pinned merge and
production verification remain delivery gates; #661's release is already complete.
