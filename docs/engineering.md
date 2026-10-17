# Engineering practice

How this project is built, tested and changed, read against the engineering practice Airbnb
has published for its Android app and its services. Airbnb is not the model because it is
large — almost nothing about a company of thousands of engineers transfers to one owner and
a handful of agents — but because it has written down, with numbers, which practices paid
for themselves at scale, and several of those are exactly the ones a small team cannot
afford to learn the hard way.

Each section says **what Airbnb does**, with the source; **what this repository does
today**, checked against the tree; and **what is left**, as an issue under epic #626. A
practice adopted here is a rule in `CLAUDE.md`, a test, a gate or an agent, not a sentence in
this file — this file is where the reason for it is kept, and the place an agent reads before
proposing a new kind of test, a new check or a new shape of the build.

What is deliberately **not** taken is said too, in the last section, because a practice
rejected for a reason is a decision, and a decision nobody wrote down is rediscovered as an
oversight.

## Testing

### A screen is a function of its state

**Airbnb.** Every screen of Airbnb's Android app exposes one immutable state
object (Mavericks, and on Compose its successor Trio), changed only by reducers, so that the
screen is a pure function of it. The test suite is built on that: each screen declares a
canonical *default* state and named *variants* of it, one changed value per variant, and the
same mocks drive the screenshot tests, the interaction tests and the view-model tests, which
start from a mock state, call one function and assert the exact state that results
([Better Android Testing, parts 1 and 4][bat1]; [Trio, part I][trio1]). Espresso suites and
recorded network «tapes» were dropped as too flaky and too costly to keep current.

**Here.** The domain is tested where it lives — `:core:model` is pure JVM, and
`ScheduleEngine`, `AlertPlanner` and the widget's size ladder are tested without Android at
all — and about two dozen screens, sheets and rows are composed under Robolectric with a
Russian locale and a phone's width (`docs/architecture.md`, «Testing»). Previews exist for
the design system's components and a few sheets, but **no screen has a canonical set of
states**: each Compose test builds the state it needs by hand, a `@Preview` builds another,
and for most of the interface compilation proves the types line up and says nothing about
the screen. There is no screenshot test of any screen or of any of the widget's twelve sizes,
and no test presses every control a state draws.

**The rule this project takes.** Test at the cheapest layer that can hold the question — the
model, then the view model, then the screen at a fixed state, then an interaction — and write
the screen's states once, so that the preview, the screenshot and the interaction test draw
the same thing. The `android-tests` agent carries the layering. Left: the screens' states
(#628) and the presses over them (#629).

### Screenshots are a test, not a picture

**Airbnb.** Every mock of every screen is screenshotted automatically, and the
report of each commit is compared with `master`'s in Happo; a change is *approved* in its
interface and the pull request waits for that approval, which is approval testing rather
than a pixel threshold ([part 2][bat2]). The same run gives every mock leak detection,
accessibility checks and a simulated process death for free, and the accessibility checks —
large font among them — block a merge ([accessibility][a11y]). Showkase generates the
permutations — dark, right-to-left, font scale — from the previews themselves
([Showkase][showkase]).

**Here.** None. The visual decisions are argued in `docs/design.md` and checked by a person on
a device (milestone 9); a regression between two such walks is invisible. The way to get
screenshots without a device is a JVM screenshot library over the Robolectric tests the
project already runs, which is a decision about one dependency and its version — copied from
a project that builds, like every version here. Left: #627.

### A flaky test is a defect

**Airbnb.** Flakiness is fixed once, in the infrastructure, because every feature uses the
same patterns: asynchronous work goes through entry points a test can block, time is read
through a wrapper a test can freeze, storage is cleared after each mock, images load
synchronously, and lint forbids reading state that is not mocked ([part 6][bat6]). Before
that, stability was measured as a *flip rate* from hourly runs of the suite
([integration tests][fastit]).

**Here.** The rule is already the right one — a test that failed once and passed alone is
filed (#355) rather than retried — but finding it depends on somebody reading a red run. No
job reruns the suites to find the tests that disagree with themselves, and no report is kept
from which a rate could be read. Left: #645.

### The test is proven by the fix

None of Airbnb's posts above states this rule, and this project already has it: a test is
kept only once it has been seen red on the code without the fix (`server-tests` and
`android-tests` agents). Nothing is borrowed; it is recorded so that a later reader does not
take its absence from this file for its absence from the project.

## Contracts

### The phones in the field are the client

**Airbnb.** Services moved off the Rails monolith onto Thrift IDLs because untyped
contracts had caused incidents; CI runs a backward-compatibility check on the schema — a
changed field type or id breaks the build — and a schema linter beside it
([Building services, parts 1 and 4][svc1]). Each endpoint has YAML request and response
fixtures validated against the schema, used as the client's mocks *and*, in the deploy
pipeline, as the producer's own contract test, which locks a deploy on a mismatch
([part 4][svc4]). Ghost Platform, the server-driven UI, exists partly so that a change does
not wait for app-store adoption ([Ghost Platform][gp]) — the problem this project has without
that remedy.

**Here.** v2 is a proto contract checked by `buf lint` and `buf breaking` against the base on
every pull request that touches it, with `test_contract.py` holding what Buf cannot read
(#299). **v1 has no such check.** Its schema is whatever FastAPI builds from
`server/app/schemas/`, nothing compares it with the previous one, and the app's JSON tests
decode payloads copied by hand from those schemas (`BundleJsonTest`), so a field renamed on
the server and in that copy passes both suites while every installed APK stops reading it.
`MIN_CLIENT_VERSION` can refuse an old client, which is a lock, not a compatibility rule.
The pull request template now asks the question; a check would answer it. Left: v1's schema
against the base's (#638), and payloads both halves read instead of copies (#639).

### One service, two versions, compared on the same data

**Airbnb.** Where Airbnb needed to know that a new system answers as the old one did, it
replayed real traffic against both and compared: production MySQL workloads are captured at
the proxy and replayed against two targets before an upgrade ([replay testing][replay]).
Viaduct's rewrite ran the old and the new API side by side rather than in one cut-over
([Viaduct five years on][viaduct]). Shadow-traffic diffing between services is often
attributed to Airbnb and is not in anything it has published, so it is not cited here.

**Here.** v2 serves the same rules as v1 through the same services, and
`test_contract_mirror.py` holds the messages level with the schemas they mirror — the
shapes, not the answers. No test asks a v1 read and its v2 twin the same question over the
same database and compares what comes back. Left: #640.

### The interface definition is reviewed as an interface

**Airbnb.** The IDL is the contract: Airbnb's compiler generates the server resources and
the clients, in two languages, with retries, validation and request context built in, and
adds date types so that nobody falls back to strings ([Building services, part 1][svc1]).
Its standards are built into the platform on the stated ground that documenting them alone
does not work ([part 2][svc2]).

**Here.** Already true at this project's size: the contract is one set of files under
`proto/`, linted with the STANDARD rules, the resource map of
`docs/specs/2026-10-04-contract-v2-design.md` is held by a test, and an edit to that map is a
breaking change by rule. Nothing to take; recorded for the same reason as above.

## Delivery

### A merge is a deploy, so the deploy is checked

**Airbnb.** Every service deploys through Spinnaker with automated canary analysis on by
default, its delivery configuration in code; the move onto it was driven by per-service
dashboards of what the canary had prevented, automated pull requests, and finally a
deprecation date for the old tool ([continuous delivery][cd]). The test pipeline runs shallow
integration tests in CI and the deep ones in the delivery stage
([test pipeline][pipeline]).

**Here.** A merge to `main` deploys itself to Vercel, and nothing asks the new deployment
anything. `GET /api/v1/warmup` already answers the question that matters most — is the
database at the revision this code expects — and the tick's self-check notices a broken
deployment within the next tick, but only once an external cron has called it and only for
what the self-check covers. A failed deployment is undone by promoting the previous one
by hand. Left: #641.

The migration rule — additive before the merge, a constraint after — is the same lesson as
Airbnb's compatibility checks, written for a schema: the side a revision goes on is declared
in prose today and checked by nobody (#642).

### Switches, not reverts

**Airbnb.** Flags live in code and are served by ERF, Airbnb's experimentation platform,
which folded feature flags into experiments so that both get *Safe Deploys*: exposure raised
in stages by a ramp controller, and an automatic stop when a core metric drops
([ERF][erf]; [safeguarding changes][safe]). Dynamic configuration (Sitar) is code in Git,
schema-checked in CI, reviewed, rolled out by stages with a fast rollback, and has an audited
emergency path ([dynamic configuration][sitar]).

**Here.** On the server, every optional integration is off until its setting is set, and the
startup log says which are off (`Settings.disabled_features`). On the phone, nothing can be
switched off after install: a feature that misbehaves on an installed APK is fixed by a new
APK, and nothing in this project has ever been released, so there is no channel to ship one
through yet (#127). Left: #637.

### `main` stays green

**Airbnb.** Evergreen, Airbnb's merge queue, requires every commit on the main line to
pass every check as if the changes had been merged one at a time, and verifies independent
changes in parallel from the build graph, across thousands of merges a day
([Evergreen talk][evergreen]). Engineers deploy their own changes, in small batches
([engineering culture][culture]).

**Here.** One required check, «CI», which needs every job and passes only when each succeeded
or was skipped (#424). What it cannot see is two pull requests that each pass against the old
`main` and break the new one together — which is what several agents working at once in this
repository can produce, each on a branch of its own. A merge queue runs the check
on the merge result before it lands; GitHub offers one only to repositories an organization
owns, so what is in reach here is the ruleset's «up to date before merging» on «CI» (#646).

## Static analysis

### A lesson becomes a rule, not a paragraph

**Airbnb.** What Airbnb wants everyone to do, it makes a tool do: custom ktlint and Android
Lint rules hold the accessibility rules and the testing patterns ([accessibility][a11y];
[part 6][bat6]), the test generator relies on a lint-enforced naming of screens
([part 7][bat7]), and the services' alerts are generated from annotations in the IDL rather
than written per service ([part 2][svc2]).

**Here.** The strongest instance is on the server: `test_service_layering.py`, `test_watch.py`,
`test_ci_paths.py` and their neighbours walk the tree and fail on the mistake a paragraph of
`CLAUDE.md` used to warn about. The Kotlin side has fewer: detekt's default rules, lightly
tuned, and five baselines (about 490 entries), `StabilityPromiseTest`, `ResourceTranslationTest`,
and a `grep` for Russian in Kotlin that a person has to remember to run (#315). The traps
`CLAUDE.md` lists for Kotlin — `LocalDateTime.now()`, `ZoneId.systemDefault()`, a
`pointerInput(Unit)` that closes over a plain `val` — are caught by reading. Android lint is
not a gate, no Kotlin formatter is checked, and on the server ruff selects five rule families
and mypy asks one question. Left: custom detekt rules for the traps (#632), Android lint
as a gate (#633), a baseline that cannot grow (#635), more ruff families (#644) and mypy
ratcheted (#643).

### Format is not a review comment

**Airbnb.** Airbnb's style guides are enforced by tools — `eslint-config-airbnb`,
`rubocop-airbnb`, and the Swift guide's formatter, which says a rule that only affects
formatting must be autocorrectable and that autocorrection must never change behaviour, so
that review stays on the logic ([Swift guide][swift]; [JavaScript guide][js]). Its open-source
Kotlin library runs detekt with autocorrection on locally and off in CI
([Mavericks' detekt setup][mvdetekt]). It publishes no Kotlin style guide.

**Here.** `.editorconfig` and `kotlin.code.style=official` say what the format is; nothing
checks it in Kotlin, and `ruff format` is not a gate in Python. Neither half has produced a
defect from formatting, which is why this is a chore rather than a bug: the cost is review
attention spent on whitespace, and a diff that mixes a reformat with a change. Left: #634.

## Build

### The build is code, and it is written once

**Airbnb.** The Android app has over two thousand modules, each of a declared *type* that
fixes what it may depend on: feature modules never depend on one another, and each has a
small navigation module through which others reach it ([Trio, part II][trio2]; the iOS
codebase documents twelve such types, [iOS productivity][ios]). CI tests the modules a change
affects and their dependents, and everything when the one file of dependency versions changes
([part 7][bat7]). The JVM monorepo's move to Bazel generated build files from the imports and
kept the old build runnable beside the new one until the end ([Bazel][bazel]). Gradle
convention plugins in the app itself are not documented in anything Airbnb has published.

**Here.** Five modules, each `build.gradle.kts` repeating its detekt block and its JVM
target by hand, with the same comment above each copy, and the three Compose modules their
compiler's stability settings. The
module graph is small and its one written rule — `:core:data` must not depend on `:widget` —
is held by Gradle itself, since `:widget` depends on `:core:data` and a cycle does not build.
`:app` holds 34 thousand lines and every screen; the decompositions of sub-project 4 split its
classes, not yet the module. Left: convention plugins (#630), then feature modules (#631).

### Large changes are a pipeline

**Airbnb.** Airbnb moved 3.5 thousand test files from Enzyme to React Testing Library in
six weeks against an estimate of a year and a half. Each file went through a fixed chain of
steps — refactor, tests, lint, types — and the model was called only when a step failed, with
that step's errors, up to about ten times; files were stamped with their step, and a *sample,
tune, sweep* loop over five to ten failing files at a time took the success rate from 75 to
97 per cent, the rest done by hand. The deciding factor was which related files went into the
context, not the wording of the prompt ([LLM test migration][llm]). Its TypeScript migration
left every remaining error suppressed with an explaining comment, so that what was left
could be counted ([ts-migrate][tsm]).

**Here.** The decompositions under `docs/specs/*-android-decomposition-*-plan.md` already run
task by task with a gate after each; the `large-refactor` skill writes the method down for
every change of that shape — a baseline burn-down, a rule ratcheted across a package, a rename
— so that the next one starts from it.

## Observability

**Airbnb.** Services emit standard metrics under one naming scheme into templated dashboards,
their alerts are generated from the IDL, and a production-readiness checklist precedes a
launch ([Building services, part 2][svc2]). Alerts are code: a pull request shows the alert
diff and backtests it against history, which cut alert noise by nine tenths
([alert development][alerts]). Incidents get a timeline, a blameless review and follow-ups
with deadlines ([incident management][incidents]).

**Here.** The server reports to Sentry where `SENTRY_DSN` is set, rebuilding every event from a
list of what may leave (152-ФЗ), and the tick's self-check tells the owner when a check changes.
**A crash on a phone reaches nobody unless its owner sends it**: `CrashReporter` keeps a dated
report on the phone, and only while the debug mode is on, and the bug-report sheet is the one
way off it — so nothing counts how often the app dies, on which version, or after which
release. Nothing states an objective — how many
ticks a day should arrive, how many requests may fail — so nothing can say that one is being
missed. Left: a decision on crash reports (#636), and the objectives (#647).

## Documentation and agents

**Airbnb.** Airbnb's Swift guide now ships an AI skill that carries only the rules that
cannot be autocorrected, because the rest are the formatter's ([Swift guide][swift]); the
iOS module types are documented in source control beside the code they govern
([iOS productivity][ios]). Review was never mandated and spread by example, with a member of
the owning team reviewing ([engineering culture][culture]).

**Here.** Designs are agreed before they are built and kept under `docs/specs/`, dated, with a
plan per sub-project; every defect gets an issue before its fix; and the agent configuration
is one agent per area that has produced a defect. The cost this repository pays that a
company does not is context: `CLAUDE.md` is read by every session and is long enough that a
third of it is the record of how a rule was learnt rather than the rule. Left: #648.

## What is deliberately not taken

- **Mavericks, Epoxy and the rest of Airbnb's Android libraries.** They solve a codebase with
  hundreds of screens and a decade of View-based UI; this one is Compose from the first line,
  with view models exposing one state each, which is the part of Mavericks that matters. A
  library would add a dependency whose versions nobody here has copied from a build.
- **Dependency injection by framework.** `Graph` is hand-written because the widget and the
  worker need repositories from entry points Hilt does not inject cleanly (`CLAUDE.md`,
  «Architecture»). Airbnb's scale is the reason for its DI; this project's scale is the reason
  against one.
- **Server-driven UI.** The bot is already the one screen the server draws, and the app's
  screens are few enough that shipping them is cheaper than a renderer for a server's
  description of them.
- **A service per domain.** The server is one process on purpose — one cold start, one
  database, one set of services both shells call. What Airbnb's move to services teaches that
  does transfer is the comparison of old and new on the same traffic, which is above.
- **Buck, Bazel or a monorepo build tool.** Two builds — Gradle and pip — and five modules.

## Sources

Read on 18 October 2026. Medium refuses direct fetches, so the Medium posts were
read through a mirror of their text and are cited at their canonical address. A practice
attributed to Airbnb that none of these supports is not attributed here.

| Source | What this document takes from it |
| --- | --- |
| Better Android Testing, parts [1][bat1], [2][bat2], [3][bat3], [4][bat4], [5][bat5], [6][bat6], [7][bat7] | mocks per screen, screenshots, interaction tests, view-model tests, flakiness, generation, affected-module CI |
| [Trio, part I][trio1] and [part II][trio2], [iOS productivity][ios], [Showkase][showkase] | one state per screen, module types and their dependency rules, generated permutations |
| [Integration tests][fastit], [accessibility][a11y] | flip rates; lint and accessibility checks as merge gates |
| Building services at Airbnb, parts [1][svc1], [2][svc2] and [4][svc4]; [the test pipeline][pipeline] | the IDL as the contract, standards built into the platform, schema compatibility, shared fixtures |
| [Ghost Platform][gp], [Viaduct][viaduct], [replay testing][replay] | app-store adoption as the problem; old and new side by side; replaying traffic against both |
| [Continuous delivery][cd], [ERF][erf], [safeguarding changes][safe], [Sitar][sitar], [Evergreen][evergreen], [engineering culture][culture] | checked deploys, switches, a main line that stays green, small batches |
| [LLM test migration][llm], [ts-migrate][tsm] | the per-file pipeline of the `large-refactor` skill; suppressions that can be counted |
| [Alert development][alerts], [incident management][incidents] | alerts as code; incidents with follow-ups |
| [Swift guide][swift], [JavaScript guide][js], [Mavericks' detekt setup][mvdetekt] | formatting by tool, autocorrect locally and check in CI, a skill for the rules a tool cannot fix |

[bat1]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-3f5b90b9c40a
[bat2]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-a77ac9531cab
[bat3]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-1d1e91e489b4
[bat4]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-part-4-testing-viewmodels-550d929126c8
[bat5]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-661a554a8c8b
[bat6]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-a11f6832773f
[bat7]: https://medium.com/airbnb-engineering/better-android-testing-at-airbnb-eacec3a8a72f
[fastit]: https://medium.com/airbnb-engineering/writing-fast-deterministic-and-accurate-android-integration-tests-c56811bd14e2
[a11y]: https://medium.com/airbnb-engineering/making-airbnbs-android-app-more-accessible-75618172be6
[trio1]: https://medium.com/airbnb-engineering/introducing-trio-part-i-7f5017a1a903
[trio2]: https://medium.com/airbnb-engineering/introducing-trio-part-ii-fe836013a798
[showkase]: https://github.com/airbnb/Showkase
[gp]: https://medium.com/airbnb-engineering/a-deep-dive-into-airbnbs-server-driven-ui-system-842244c5f5
[ios]: https://medium.com/airbnb-engineering/designing-for-productivity-in-a-large-scale-ios-application-9376a430a0bf
[svc1]: https://medium.com/airbnb-engineering/building-services-at-airbnb-part-1-c4c1d8fa811b
[svc2]: https://medium.com/airbnb-engineering/building-services-at-airbnb-part-2-142be1c5d506
[svc4]: https://medium.com/airbnb-engineering/building-services-at-airbnb-part-4-23c95e428064
[pipeline]: https://medium.com/airbnb-engineering/building-an-effective-test-pipeline-in-a-service-oriented-world-6968c513c6bd
[replay]: https://airbnb.tech/infrastructure/beyond-synthetic-testing-capturing-and-replaying-real-database-workloads-at-airbnb/
[viaduct]: https://airbnb.tech/infrastructure/viaduct-five-years-on-modernizing-the-data-oriented-service-mesh/
[cd]: https://medium.com/airbnb-engineering/continuous-delivery-at-airbnb-6ac042bc7876
[erf]: https://medium.com/airbnb-engineering/https-medium-com-jonathan-parks-scaling-erf-23fd17c91166
[safe]: https://medium.com/airbnb-engineering/how-airbnb-safeguards-changes-in-production-9fc9024f3446
[sitar]: https://airbnb.tech/infrastructure/safeguarding-dynamic-configuration-changes-at-scale/
[evergreen]: https://www.confluent.io/events/kafka-summit-london-2022/evergreen-building-airbnbs-merge-queue-with-kafka-streams/
[culture]: https://medium.com/airbnb-engineering/engineering-culture-at-airbnb-345797c17cbe
[bazel]: https://airbnb.tech/infrastructure/migrating-airbnbs-jvm-monorepo-to-bazel/
[llm]: https://airbnb.tech/infrastructure/accelerating-large-scale-test-migration-with-llms/
[tsm]: https://medium.com/airbnb-engineering/ts-migrate-a-tool-for-migrating-to-typescript-at-scale-cd23bfeb5cc
[alerts]: https://airbnb.tech/infrastructure/it-wasnt-a-culture-problem-upleveling-alert-development-at-airbnb/
[incidents]: https://medium.com/airbnb-engineering/incident-management-ae863dc5d47f
[swift]: https://github.com/airbnb/swift
[js]: https://github.com/airbnb/javascript
[mvdetekt]: https://github.com/airbnb/mavericks/blob/main/detekt.gradle
