# Instruction references that still resolve (#661)

The owner requested autonomous delivery of #661 after #660, followed by #635,
including independent review, full gates, merge and production verification.
The choices below are the executor's, within that scope; no new product behavior
or dependency is required. Questions were explicitly waived by the owner.

## Intent and boundary

Read CLAUDE.md, AGENTS.md and every Markdown document under .claude/. Check inline
backtick references to repository paths and test definitions, including source
arguments in commands. Report the document, line and unresolved token. Deliberately
historical names belong outside backticks. This protects navigation after moves;
it does not execute documentation commands or validate their runtime behavior.

Use a stdlib-only helper under server/scripts and existing pytest. Source inventory
includes tracked and new untracked, non-ignored source names before commit. Private
configuration is never opened: regular public test sources, instruction documents and Git's filename inventory
are read. A slash-qualified Python module.symbol reference also reads its public
module AST lazily to distinguish a real symbol from a filename extension. File
symlinks and links through parent directories are excluded; no target is opened.
Directory existence comes from source path prefixes, not local generated files.

## Reference grammar

Inline spans are distinguished from fenced examples. File references can be full
repository paths, suffix paths relative to the described component, basenames,
or paths with a collapsed ... directory. Existing globs and directories require
at least one source match of the same kind. Ordinary stars stay within a path
component; only ... and ** span directory levels. Multiple matches satisfy existence; resolving a unique
semantic target is outside this check. A path with an explicit extension must
match that extension. A colon symbol suffix checks its source path; Python module
style references without an extension may resolve to a .py file or package.

Test names test_* and *Test must have definitions in Python test ASTs or Kotlin
test source, not only appear in a comment or string. Python explicit nodes retain
file/class ownership; nested helper definitions do not count. Documented simple
keyword selections must match within their source file. Kotlin string templates
mask their nested strings before classes are recognized. Command spans are tokenized;
check source paths and test selectors, omit switches, generated report arguments,
environment assignments, optional virtualenv executables and Gradle task syntax.
No commands run. Full shell correctness and arbitrary dynamic task discovery are
not claimed. Known HTTP/bot routes, URLs, time-zone names, extension examples,
private/local configurations and explicit placeholder spans are separate categories.
Keep these categories narrow and documented; do not add per-missing-file exemptions.
Existing misleading inline examples should be rewritten as prose, not silently
accepted as real source. Fenced examples remain examples, outside the assertion.

## Integration and proof

Existing server pytest checks the actual current instructions. Server CI must run
when referenced filename inventories or test definitions can change, including
Android and .github paths. Keep the changes-arm path guard and its regression
tests aligned. Explain the choice in docs/engineering before the CI edit.

RED/GREEN fixtures must cover removed/renamed files and definitions; comment/string
fake definitions; suffix/ellipsis/glob/directory references; command source args
and generated outputs; routes/private configuration; newly untracked source; and
the actual current tree. Do not weaken #660's raw-suite/count regeneration order.
After a successful complete raw suite, regenerate measured server facts, update
current declarations, then pass count comparisons and final document checks.
Run Ruff/mypy/full SQLite plus all four native Android gates, fresh whole-branch
review, exact-head PR CI, pinned merge, main CI and production checks. No new full
PostgreSQL run is needed for a source-document guard without application changes.
APKs are built, not installed; live account/device behavior is outside this batch.

## Review focus

1. A renamed file cannot survive through a matching generated/untracked report.
2. A removed test cannot survive through comments, strings or production symbols.
3. Local setup instructions do not require credentials/configuration to exist.
4. Shell source arguments and quoted Gradle test selectors remain checked.
5. File moves in Android or .github cannot bypass the server job consuming them.
