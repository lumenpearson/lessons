# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.
What every batch before the last two added is in [docs/history.md](docs/history.md),
newest first.

Last updated: **11 October 2026**. **PRs #63 through #85, #128, #129, #133, #134, #140,
#166, #186, #187, #189, #214, #218, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261,
#263, #267, #274, #277, #294, #296, #297, #300, #301, #303, #305, #306, #307, #308, #311,
#313, #319, #328, #329, #332, #333, #335, #342, #345, #346, #350, #356, #359, #363, #366, #372,
#376, #379, #380, #385, #388, #392, #394, #396, #398, #399, #403, #410, #413, #415, #417, #418
and #422 are merged**, and #344's commit went in with #366 although GitHub marks it closed
rather than merged; `main` is at `6354c3d`, the merge of #422, by the session at 13:48:33 UTC
on 11 October 2026. **Sub-project 3 is complete** with #396 (`80d1281`), **and its
live-verification gate has run**, as far as a session can take it: the condition the owner set
on 8 October before sub-project 4, checked on the development machine on the night of 10–11
October. **The
four designs of sub-projects 3 to 6 are approved and on `main`**: the owner answered every
question with its recommendation on 5 October (#301, #306, #307, #308), and #410 added a
seventh sub-project and six deployment scenarios to the programme. **Three things are open.**
- **#428, this pull request, carrying this paragraph**, from `fix/kind-ci-sentry` to `main`, on
  milestone 11, which closes #423, #424 and #425: an event's «covers the lesson» follows its
  kind until somebody sets it apart, one summary check «CI» for a ruleset to require, and the
  privacy policy naming Sentry (edition 4).
- **#430, a draft by another session**, from `agents/privacy-policy-eula-diary-d6b875`, on
  milestone 12, opened at 14:53 UTC on 11 October: edition 5 of the privacy policy and the
  terms, written from the owner's brief for a lawyer, which closes #429. It touches the same
  files as #428 (`legal.json`, `privacy.*.md`), so #428 merges first and #430 is rebased onto
  edition 4, keeping the Sentry point.
- **Branch `android/decomposition-a`**, sub-project 4's pull request A of three, the Android
  decomposition per `docs/specs/2026-10-05-android-decomposition-design.md`: in progress, with
  no pull request yet.

**The schema did not move**: the head is still `0019`, on production since 16:28 UTC on 6
October, and `EXPECTED_REVISION` did not move either. Production deployed `6354c3d`, #422's
merge, READY, and its `/api/v1/warmup` answered
`{"status":"ok","api_version":1,"schema":"0019","v2":true}`; `GET /api/v2/diary/capabilities`
answered `200` with `egress: DIARY_EGRESS_PROXY`. The production bot's «📊 Проект», read at
17:02 Moscow on 11 October, showed commit `6354c3d`, all four checks ✅, the proxy answering in
450 ms, no region line because no region is in use, one class and two accounts.

The issues filed since #396 merged:
- #402, closed by #403;
- #404, closed by #413;
- #405, open in the backlog: margins of 24 dp at medium and expanded widths, which the geometry
  batch left alone;
- #406–#409, filed with #410's deployment plan: #406 closed by #422, #407 and #408 open on
  milestone 11, #409 open in the backlog;
- #411, closed by #418: the server suite had only ever run on SQLite;
- #412, open in the backlog: the «Сетевой город» school search reads `addressString`, a key no
  live server sends. The gate found it by asking four regional servers, with no account;
- #414, closed by #415: a segment sat 4 dp from its tray's ends and 8 dp from its top and
  bottom;
- #416, closed by #417: «Календарь»'s header left an empty band between the year chip and the
  arrows;
- #419, closed by #422: the suite read a developer's proxy out of `server/.env`, because
  pydantic-settings lets the environment win only for a name it holds;
- #420, fixed by `279a369` and closed by #422: a warmup test's class-wide patch outlived the
  test whenever a new autouse fixture changed the teardown order;
- #421, filed by #422's whole-branch review and open in the backlog: a proxy that refuses the
  tunnel to a region is reported as the region's fault, with advice to move it to direct;
- #423, #424 and #425, on milestone 11, which #428 closes: a trip turned into a
  canteen break kept replacing the lessons; nothing made a CI gate required, and the «Host»
  matrix cannot be required by name; the privacy policy said the server sends no crash
  reports, and it sends them to Sentry;
- #426, open in the backlog: the owner's request of 11 October to open the privacy policy and
  the terms in the app's own WebView rather than another app, recorded for later;
- #427, on milestone 11, filed during #428's review: the legal tests read `docs/legal` outside
  Gradle's inputs, so a run after an edit can be restored from the build cache and read
  nothing, and CI's run on #428 was;
- #429, on milestone 12, opened from the owner's account by another session: the privacy
  policy and the terms do not say what 152-ФЗ asks an operator to publish, name two diary
  systems of the eighty-nine regions', and mis-state when the proxy is used. Draft #430
  closes it.

#395 closed with #396 and #397 with #399; #400 (backlog) and #401 (milestone 11), filed by 3c,
are open. #352, #355, #357, #365, #368, #371, #375, #377, #378, #384 and #391 stay as the last
close-out left them, with one update: #357, Connect GET answers carrying no `Cache-Control`, was
confirmed live on 10 October against a local server on PostgreSQL — `GetClass` over Connect GET
answered `200` with none, and its REST twin `private, no-store`. #365's diary reads are to move
onto the phone after sub-project 3. #118, what Preview is for, was closed by #350.

The section «What the last session added» below is #428, this pull request: #423, #424 and
#425, with #422's merge in its «After #422's merge»; «What the session before it added» is
#406, the diaries through the proxy by region (#422), with #418's merge in its «After #418's
merge». The live-verification gate's section (#411, #418) is in
[docs/history.md](docs/history.md) now.

The SHA of its own merge is for the next close-out to write.

**#267 closed #264, #265 and #266**, read back from GitHub on 3 October, and **#296 closed
#271, #272 and #275**, read back on 4 October. **#274 and #277 closed nothing.** Of the
defects the survey and the plan filed on milestone 11, #268–#270 are open, #276 closed with
#305, #309 and #310 with #311, #312 with #313, #314–#318 with #319, #320–#325 with #328, #331
with #332, and #273 is that milestone's epic. #336–#341, filed on #342's branch, closed with it.
**#235** closed at #335's merge on 5 October, and the proxy it asked for was verified in
production the same day («Outside the pull request, the same day», in the section on #342).
**#236**, a phone's sign-in showing nothing for over a minute, was closed as a duplicate of
#233, which #234 had already fixed.
Of the device epic **#109**, **#111** and **#113** stay open for what only a phone can say,
and **#112** (a macrobenchmark module) was not started.

**The code expects head `0019`, and production has it since 16:28 UTC on 6 October 2026, before #359's merge.**
`EXPECTED_REVISION` in `app/db.py` is `0019`, pinned to the real head by
`tests/test_schema_version.py`. `0019` creates `health_checks`, one row per check of the
tick's self-check, holding what it said last: additive, nothing destroyed and no row
rewritten. It went to the Neon branch `preview` at 15:35 UTC and to production at 16:28 UTC,
each as one transaction read first and read back after; production's `/api/v1/warmup`
answered `degraded` with «База впереди кода» in the window between, which #359's merge at
16:51:58 UTC closed: production served the new code from 16:52:21 UTC. `0018` adds the nullable `device_tokens.client_version`, the app version a phone last
sent to v2. `0015` adds the eight nullable columns the second diary
needs. `0016` creates `usage_counters`, the anonymous school directory's daily count of
DaData requests. `0017` files the diary corrections under the child rather than a login, and
it is data only. Those three went on through the Neon connector (the project named `lessons`)
**before** #140's merge, at 12:26 UTC on 26 September 2026, in one transaction; «Schema» in
the section on #140, in
[docs/history.md](docs/history.md) now, says what the database said. The `0014` chapter
still holds: #83's pinned table of the enum column
widths fails when a `SAEnum` member's **name** outgrows its `VARCHAR`, which SQLite cannot
see and production Postgres finds at the moment somebody marks a day.

**Production was read after #140 deployed, and the schema and the code met.** On 26 September
2026 at 17:05 UTC the production deployment of `bd7c816` answered `/api/v1/warmup` with
`{"status":"ok","api_version":1,"schema":"0017"}`. It was read through the Vercel connector's
`web_fetch_vercel_url`, which carries the owner's access through Deployment Protection — the
thing that made #83 call it unreachable from a session. The project's production alias
`lessons-ruddy-zeta.vercel.app` is **not** behind that protection (`/api/v1/health` answers
`200`), while the per-deployment URLs and `lessons-codeilluminators.vercel.app` redirect to
Vercel's sign-in; a phone is pointed at the first. The bot answered `/start` in the owner's
chat the same day. Both halves of #119 are therefore read, and it closes.

**Two things changed about how this project is tracked, and they are the reason to read on
before planning anything.**

**The work moves to a local machine.** Everything up to #85 was built with no emulator, no
`adb` and no device, and so were #133, #134 and #140; #186 is the first batch made with
one. The section «Where the work happens
from here» below says what that unlocks and what it still cannot do; **#109** is the epic.
#140 is the first batch since that decision to change screens, so nearly everything it adds
to section 5 is a question a device answers.

**The repository has issues now, and until #128 it had none at all.** Before that, the
milestones were the only grouping the history had. Forty-two issues were opened in one go:
**#86–#108**, closed, for what was built and what each bug sweep found, and **#109–#127**,
open, for the whole of what was left. Several of the open ones record a decision *not* to do
something, so that a later session does not re-discover it as an oversight. From #129 on, a
found defect becomes an issue before it becomes a fix: #131 and #132 were the first two
filed under that rule, and #140's branch filed #136–#138 and #145–#165.

## Where the work happens from here: a local machine

**#128 was meant to be the last batch built in a cloud session, and the change is one of
venue rather than of direction.** #133 and #134 were still written from one, because neither
needs a device, and #140 was written without one too, although its screens want one most.
Everything up to and including #85 was made with no emulator, no `adb` and no device. That
is what produced a project with 968 Android tests, 1634 server tests —
and a section 5 of this document that has only ever grown, because the things in it are not
things a JVM test can be asked.

From here: Android Studio, an emulator and a real phone, with Claude Code in the terminal
beside them. What that unlocks, in the order it is worth:

1. **Seeing the screen.** `adb exec-out screencap -p` makes a PNG and a local agent reads
   PNGs. Every «nobody has looked at this» item in section 5 becomes answerable.
2. **Instrumented tests.** Until #187 there was **no `androidTest` source set anywhere in
   this project**, which is why the reorder gesture was two touches until #181 and why its
   threshold was asserted only in arithmetic. There is one now, in `:core:designsystem`
   (#110): `ToolbarOnDeviceTest` drags the arranging gesture by fractions of a slot at the
   device's own density, with `./gradlew :core:designsystem:connectedDebugAndroidTest`. The
   next screen that wants one follows its pattern — the clock held, `settle()` sending the
   snapshot notification first — and its catalog pins (Espresso 3.7.0, runner 1.7.0,
   because the Compose BOM's own Espresso fails on API 34 and later). `uiautomator` is still
   absent: nothing here has needed a second app yet.
3. **Frame timing.** `dumpsys gfxinfo … framestats` costs nothing and works today; Perfetto
   plus `trace_processor` answers «which composable recomposed» in SQL; Macrobenchmark's
   `FrameTimingMetric` gives P50/P90/P99 and `frameOverrunMs`.

**What a local session still cannot do**, so that an evening is not spent finding out: an
emulator's GPU is emulated, so absolute frame numbers are not a phone's; a Glance widget is
drawn by the **launcher's** process, so the app's `gfxinfo` says nothing about it; haptics
cannot be felt; a release build under a trace needs `-dontobfuscate` or `retrace`; and
Macrobenchmark against a debuggable build measures the debugger.

**The handover is this repository and nothing else.** A local session shares no state with a
cloud one. It reads `CLAUDE.md`, this file, the agents in `.claude/agents/` and the
procedures in `.claude/skills/`, and so arrives knowing the traps that have cost a deploy
and a release each. `.claude/README.md` says why there is no `.mcp.json`; locally, Neon and
Vercel can go in one.

### And the work has a tracker now

**Until #128 there were no issues in this repository at all** — the milestones were
the only grouping the history had, and everything else lived in this document's prose.
Forty-two issues were opened in one go: **#86–#108 closed**, describing what was built and
what each bug sweep found, and **#109–#127 open**, which were then the whole of what was
left. Six of those have closed since: #114–#117 and #119 at #186's merge, and #110 at
#187's.

The forward half is worth reading before planning anything: **#109** is the epic this
section describes, **#118–#122** are the owner's alone, and **#126** collects the four small
things carried deliberately, so that a later session does not re-discover a decision as if
it were an oversight.

**Seventy-seven have been filed since.** #130 was the survey #134 closed, and #131 and #132
the two defects #133 closed. #135 is the owner's decision about the second diary; #136–#139
and #141 are the work #140 carries; #142–#144 are its deliberate follow-ups, on no
milestone; and #145–#165 are the defects found on its branch, each filed before its fix.
#167–#185 are what #186's walk on an emulator found and what the owner asked for that
night, and #188 what #189 found; all twenty are closed. #190–#211 are the external audit of
27 September 2026, on milestone 10, and #212 and #213 what merging its fixes found.
#268–#272 are the defects the survey behind milestone 11 found, #273 that milestone's
epic, and #275 and #276 what planning its first sub-project found. #293, on the same
milestone, is the stale tracker documents #294 closes, and #295 what writing #294 found: CI
skips the server job when only a document its tests read changes.

Labels are `type:` (feature, bug, chore, research, decision, epic), `area:`, `status:` (now,
next, someday, done) and `needs:` (device, owner). **The board is the owner's project 6,
«lessons»**, and it holds every issue and pull request. A remote session cannot reach it; a
local one fills it with `gh`, by the rule in the project's README, as «The board» in the
`github-pr` skill says.

## What the last session added: an event's kind carries «covers the lesson», one required check, and Sentry in the privacy policy (#423, #424, #425)

Open as #428, this pull request, from `fix/kind-ci-sentry` to `main`, on milestone 11. It
closes #423, #424 and #425. The branch was cut from `main` at `6354c3d`, the merge of #422, and
carries one commit per issue and this close-out. Written on 11 October 2026. No revision goes
with it: the schema stays at `0019`.

All three were filed on 11 October from what #422's review and the owner's questions of that
day left open. The owner left #423's rule to the session, asked for «Host (pyvoy)» and «Host
(hypercorn)» to become required, which #424 does another way, and wrote a brief for a lawyer
the same day, which #425 corrects edition 3 ahead of: another session wrote edition 5 from
that brief, as draft #430.

- **An event's «covers the lesson» follows its kind until somebody sets it apart** (#423,
  `92d93cb`). An `UpdateEvent` that turned a trip into a canteen break kept the trip's
  `covers_lesson`, so the break went on replacing the lessons it overlapped, while a break
  created as one would not (the 3b-5 list's Ruling 79). The rule now, the session's call of 11
  October: on an update that changes the kind and does not name `covers_lesson`, a stored value
  equal to what the **old** kind means becomes what the **new** kind means, and any other is
  kept, because somebody decided it for this event; a `covers_lesson` named in the same update
  always wins, and `None` there is what the new kind means, as before. It lives in
  `services/events.update`. Only v2 changes a kind: v1's `PUT /events` always creates and the
  bot's «📅 Событие» only creates, so neither needed a change. `event.proto`'s comment on
  `UpdateEvent` says the rule, Buf regenerated `event_connect.py`'s four docstrings and nothing
  else, and `docs/api.md` says it beside the masked-field rule.
- **One summary check, «CI», for a ruleset to require** (#424, `9883f2d`). `ci.yml` ends with a
  job that needs every other one, runs `if: always()`, and passes only when each succeeded or
  was skipped. The gates are not required by their own names because «Host» is a matrix: a job
  its `if:` skips is reported once, under the unexpanded `Host (${{ matrix.server }})`, so a
  required «Host (pyvoy)» would never arrive on a pull request that changes only `android/`.
  `server/tests/test_ci_required.py` holds the job's `needs` to every other job in the file and
  runs its step's script under bash. `CLAUDE.md`, `docs/build.md` («The check `main`
  requires»), `docs/README.md`, `CONTRIBUTING.md`, the `gates` skill and the `build-ci` agent
  say so, and say that `main` requires it through the ruleset that requires it, which the
  repository admin role can bypass. The ruleset is created on GitHub after this merge.
- **The privacy policy names Sentry, as edition 4** (#425, `f74cbbc`). Edition 3 said there was
  no automatic sending of crash reports from the app or the server, and did not name Sentry,
  which the server has used since #359. «Чего сервер не хранит» now says the app sends no crash
  report by itself and the server, if its operator set it up so, sends reports of its own errors
  to Sentry without personal data; «Третьи стороны» has a Sentry point after the hosting —
  the error's type, the place in the code, the route, the server's version and how long some
  requests took leave; requests' contents, headers and parameters, your data and your IP address
  never do; this operator's Sentry data is kept in Germany (`de.sentry.io`). Each claim was
  checked against `app/observability.py`, and the 5 % sample of timings is why the text says
  «на часть запросов». The English twin matches block for block. The FORK comment gains
  `[SENTRY]`. `legal.json` is edition 4, effective 2026-10-11, and `docs/deploy.md`'s Sentry
  section says the policy names it.
- **Two documents' nits from #422** (this close-out): «Петербург» in `docs/api.md`'s English
  prose is Petersburg, and the milestone table's backlog row names #421 and #426.

### Gates

- **The full suite on PostgreSQL 18**, at `f74cbbc`, the last commit of code, with
  `LESSONS_TEST_DATABASE_URL` and `pytest -q -n 4`: **3325 passed, 7 skipped**, in 3 min 59 s.
  That is **3332** collected, twelve more than #422's 3320 — five for #423 and seven for #424 —
  and the seven places the `handover` skill names say 3332. The SQLite suite is CI's, on this
  pull request.
- **ruff** clean; **mypy**, no issues in **240** source files.
- **The tests first, seen failing.** #423: the two service tests that change a kind alone and
  the v2 test through `UpdateEvent` failed on the old rule (`(200, True) == (200, False)`); the
  two that hold a value set apart and a value named beside the kind passed either way, as they
  should. #424: all seven of `test_ci_required.py` failed with `KeyError: 'ci'` before the job
  existed.
- **The focused runs.** `test_services_homework_events.py`, `test_v2_event_writes.py`,
  `test_v2_events.py`, `test_api_extended.py` and `test_contract.py`, **137 passed**;
  `test_contract.py`, `test_contract_json.py` and `test_contract_mirror.py`, **98 passed**;
  `test_ci_required.py`, `test_ci_paths.py`, `test_host_job.py`, `test_env_example.py` and
  `test_schema_version.py`, **33 passed, 1 skipped** (the host-live check); and
  `test_schema_version.py`, `test_corrections_per_child_revision.py`,
  `test_deployment_config.py` and `test_observability.py`, **82 passed**.
- **Buf 1.73.0**: `buf lint` clean; `buf generate` changed `event_connect.py`'s four docstrings
  and nothing else; `buf breaking` against `6354c3d` printed nothing. CI's «Contract» job runs
  all three on the push.
- **Android**: the legal tests alone, `./gradlew :core:data:testDebugUnitTest --rerun --tests
  'com.lumenpearson.lessons.core.data.legal.*'`: `LegalDocumentsTest` 11 and `LegalLinksTest`
  10, all passed, on edition 4. The first run without `--rerun` was restored from the build
  cache, because the task does not declare `docs/legal/` as an input, so it proved nothing
  (#427). **CI's run was restored from the cache as well**: #428's «Android» job (run
  38148509706) logged `:core:data:testDebugUnitTest FROM-CACHE`, because
  `android/gradle.properties` sets `org.gradle.caching=true` and `setup-gradle` restores the
  cache. So CI never ran the parity tests on edition 4, and edition 4's parity rests on this
  local `--rerun`. Nothing else under `android/` changed: the count stays **1730**.
- **CI on the head** is read before the merge.

### What was deliberately left alone

- **v1 and the bot's event flows**: neither changes an event's kind, so the rule has nowhere
  else to go. An event created with `covers_lesson` set to what its kind means anyway cannot be
  told from one that took the default, so it follows its kind like any other.
- **The ruleset itself**: it is created on GitHub by the session after the merge, and nothing in
  the repository can check that it exists. The summary job's `if: always()` also judges a run
  cancelled by a newer push, which then reads red on a superseded commit; that is a run nobody
  merges.
- **Sentry's retention period** is not stated in the policy. What the owner's brief for a
  lawyer asks for is draft #430's, edition 5.
- **The proxy point's «если из страны, где работает сервер, дневник не отвечает»**: the owner
  asked after #422 whether edition 4 should reword it. #425 did not touch it; #429 names it,
  and draft #430 answers it in edition 5.
- **`.claude/settings.json`**: the owner's two allow lines are in the session's working tree,
  not in #428, since a session does not edit its own permissions.

### What nobody has verified in this batch

- **The «CI» job on a pull request that skips «Host».** #428's own run, 38148509706, was its
  first and was green — it printed `gates: success success success success success` — but every
  gate ran there, so the case the job exists for, a matrix its `if:` skipped, has not been met
  on GitHub; that half rests on the test's run of the script under Git Bash.
- **The legal parity tests on CI** (#427): #428's «Android» job restored them from the build
  cache, so they did not run there on edition 4.
- **The event rule from a client**: no phone calls `UpdateEvent` yet (sub-project 5).
- **Edition 4 in the app**: nobody has opened the bundled sheet or the link on a phone.
- **An error event in Sentry**, as before: what edition 4 says leaves is checked against the
  code, not against an event Sentry received.

### After #422's merge: the merge, production, and the owner's answers

None of this is code in #428, and a close-out never gets a close-out of its own, so it is
written here. The source is the controller's notes of 11 October 2026.

- **#422 merged by the session** at 13:48:33 UTC on 11 October 2026 as `6354c3d`, after the
  five checks on head `f395971`: seven check runs and the Vercel status, all success;
  mergeable `CLEAN`; milestone 11; the only comment the Vercel bot's. It closed #406, #419 and
  #420. Its close-out named itself as the only thing open; this is the SHA it left for the next
  one.
- **Production deployed `6354c3d`: READY.** `/api/v1/warmup` answered
  `{"status":"ok","api_version":1,"schema":"0019","v2":true}`, and `GET
  /api/v2/diary/capabilities` answered `200` with `egress: DIARY_EGRESS_PROXY`: Petersburg
  through the proxy, and of «Сетевой город» zabaikalsky, kamchatka, leningrad, ingushetia, komi,
  mari-el, mordovia, yakutia, samara, sakhalin and ulyanovsk through it, amur, kchr, buryatia,
  ryazan and tomsk direct.
- **The production bot's «📊 Проект»**, read at 17:02 Moscow: commit `6354c3d`, all four checks
  ✅, the proxy answering in 450 ms, no region line because no region is in use, one class and
  two accounts. That settles the one look at «🩺 Состояние» section 7 asked for after the merge.
- **The board**: #406, #419, #420 and #422 are Done, and the issues are labelled `status:done`.
  #421 is in the backlog (Someday, P2, S).
- **The owner's answers of 11 October**, which section 7 now carries: Android Studio was
  restarted; the two `pytest` allow lines were added to `.claude/settings.json`, and the `deny`
  for `~/.gradle/gradle.properties` was not, by choice; rotating the release signing passwords
  is not important; #144 is decided, no default server, a server being chosen when the app is
  built, through the build console (sub-project 6); #423's rule was left to the session; and
  the required checks became #424. The owner also has a brief for a lawyer, written on 11
  October and kept outside the repository; another session wrote edition 5 of both texts from
  it, draft #430, which closes #429.
- **Left in place**: the plan workspace `.superpowers/sdd/2026-10-11-diary-routes-plan/`, which
  git ignores; its rulings are in #422's body and in the section below.

Section 7's «Next for the programme» says what follows.

## What the session before it added: the diaries through the proxy, by region (#406)

Merged as #422 (`6354c3d`, 11 October 2026), from `server/diary-routes`, on milestone 11. It
closes #406, #419 and #420. The branch was cut from `main` at `a28473e`, the merge of #418, and
carries the plan, four tasks of code and the documents. Written on 11 October 2026. No
revision goes with it: the schema stays at `0019`, and the region checks fit `health_checks`'
32-character names.

The owner decided four things on the morning of 11 October, before a line was written: widen
the RUVDS Squid ACL to every «Сетевой город» host («Расширить ACL, я разрешаю»), route **by
region** from what that proxy reaches («По регионам»), give a region's alert a hint on what to
do («С подсказкой, что делать»), and name the proxy in the privacy policy's «Третьи стороны».
The plan is `docs/specs/2026-10-11-diary-routes-plan.md` (`032a3c0`), five tasks, each built
and reviewed before the next.

- **`DIARY_EGRESS` is the deployment's choice of how it reaches the diaries** (`87ff938`,
  `config.py`): `direct`, `proxy` or `relay`, optional and never a reason to refuse to start.
  Unset, unusable or `proxy`, it is the proxy where `DIARY_PROXY_URL` is usable and direct
  otherwise, which is what production already did, so production sets nothing. `direct` sends
  nothing through the proxy, Petersburg included; `relay` is read as `direct` until the relay
  exists (#408). The startup log says only what does not take effect as written, and never
  repeats a value.
- **The route is a static table, the owner's probe of 11 October** (`87ff938`).
  `Region.via_proxy` is true for the twelve «Сетевой город» hosts whose school search answered
  through RUVDS, `Row.via_proxy` for Petersburg, and `registry.route` and `registry.proxy_for`
  are the one answer to «how does this deployment reach this server». The five password regions
  that refused the proxy or did not answer — Бурятия, Амурская, Томская, Рязанская, КЧР — stay
  direct, as every region was before.
- **#419: the suite no longer reads a developer's proxy** (`87ff938`). `conftest.py` sets
  `DIARY_PROXY_URL` and `DIARY_EGRESS` empty, because `Settings` reads `server/.env` too and
  the environment wins only for a name it holds; a proxy in a developer's `.env` would have
  carried the suite's requests to real regional servers.
- **Both diary clients take each server's route** (`c191974`). «Сетевой город» keeps two
  pools, one direct and one through the proxy, and `client_for(region)` picks one on every
  call — `_send`, `terms_search`, the keep-alive and the bot's school search all go through it;
  Petersburg's one pool is built with `proxy_for(PETERSBURG)`. `shared_client()` keeps its
  meaning, the direct pool, so every existing test fake still holds.
- **v2's `GetDiaryCapabilities` says the route** (`2082f1a`): `DiaryCapabilities.egress = 3`,
  the deployment's mode as it takes effect, and `ProviderCapabilities.routes = 5`, one
  `DiaryRoute` per server. Additive, v2 only; v1's answer keeps its shape. No phone reads them
  yet.
- **The self-check probes each «Сетевой город» region in use, by its route** (`91087b2`): one
  `diary_region:<key>` row per region a class is bound to or a live session is on, probed with
  `GET /webapi/logindata` and the diary client's headers. A refusal or no answer is `failing`;
  a proxied region that fails while `diary_proxy` fails reads `unknown`, so a dead tunnel is
  one alert; at most 17 diary probes a tick, all at once inside the checks' budget; a region
  that leaves use loses its row; `diary_proxy` reads `unknown` under `direct` or `relay`. A
  «down» and its reminder carry a hint by the route the probe took, and «🩺 Состояние» and
  `/health` draw a line per region.
- **#420: a warmup test's class-wide patch no longer depends on fixture teardown order**
  (`279a369`): Task 4's new autouse fixture flipped the order, and `no_stamp`'s cleanup met the
  `OperationalError` the test simulated. `80cce89` then made the region-probe fixture fail
  loudly if its seam ever moves.
- **The documents and the privacy policy** (`6dd0e74`). `docs/deploy.md` has the routes, the
  probe's results, the modes and the new monitoring row, including that **a region dropped
  from use while failing goes silent**, with no «up», which is not a recovery; `docs/diaries.md`,
  `architecture.md`, `api.md`, `bot.md`, `CLAUDE.md` and the README follow. «Третьи стороны»
  and «Third parties» each gain one point, the operator's intermediary server in Russia at
  RUVDS: an HTTP proxy and a TLS tunnel that sees the diary's address and the time, may log
  them, and never sees a login, a password, a session or a mark. `legal.json` is edition 3,
  effective 2026-10-11.

### Gates

- **The full suite on SQLite**, at the end of Task 4, `pytest -q -n 4`: **3312 passed, 6
  skipped**, in 36 min. That is **3318** collected, 78 more than #418's 3240, and the seven
  places the `handover` skill names say 3318.
- **The full suite then ran once on PostgreSQL 18**, at `7ee7fda`, with
  `LESSONS_TEST_DATABASE_URL` and `pytest -q -n 4`: **3311 passed, 7 skipped**, in 3 min 52 s —
  the seventh is the directory test that is SQLite-only since #418. It covers `80cce89`, the
  branch's last commit of code before this close-out, which the SQLite full run above also
  covers.
- **At this branch's head**, after `80cce89`: ruff clean; mypy, no issues in **240** source
  files; `test_health.py`, `test_health_tick.py`, `test_diary_egress.py`, `test_diary_proxy.py`
  and `test_project_screen.py`, **140 passed**; and the tests that read the documents this
  batch edits — `test_env_example.py`, `test_compose.py`, `test_schema_version.py`,
  `test_test_imports.py` and `test_ci_paths.py` **28 passed**, `test_deployment_config.py`,
  `test_rpc_errors.py`, `test_contract_json.py`, `test_roles.py` and `test_database_url.py`
  **140 passed**.
- **Android**: the legal tests alone, `./gradlew :core:data:testDebugUnitTest --tests
  'com.lumenpearson.lessons.core.data.legal.*'`: `LegalDocumentsTest` 11 and `LegalLinksTest`
  10, all passed, on the privacy texts of edition 3. `:core:data:test` refuses `--tests`,
  being the aggregate of the two variants. Nothing else under `android/` changed or ran: the
  count stays **1730**.
- **The contract**: `diary_pb.py` was re-derived by Buf after the crash below and matched the
  committed file byte for byte; CI's «Contract» job runs `buf lint`, `buf breaking` and the
  generated-code check on the push.
- **CI on the head** is read before the merge.

### What was deliberately left alone

- **The relay (#408)**: `relay` reads as `direct`, and the capabilities never answer
  `DIARY_EGRESS_RELAY`.
- **The Android side**: no phone reads `egress` or `routes` yet; that is sub-project 5.
- **A dynamic fallback**, trying one route and then the other: the owner chose a static table.
- **The `verified` flags and the APK's bundled region catalog**: the probe answered a school
  search, not a sign-in, so no region is marked verified.
- **Probing Petersburg directly under `direct`** (`diary_proxy` reads `unknown` there and
  nothing asks the city), and «📊 Проект» showing each healthy region's route.
- **The Squid ACL stays at all nineteen hosts**, the seven direct ones included, so a region
  can move onto the proxy by one line in `regions.py` with nothing changed on the VPS.
- **`docs/specs/2026-10-10-deployment-scenarios-design.md`**, a record, is not edited.

### What nobody has verified in this batch

- **A real call through the proxy for any of the eleven newly routed password regions.** Only
  the school search was asked, by hand, from the VPS; no sign-in, read or keep-alive has taken
  the route, and whether every path passes through Squid unchanged is unknown.
- **The region probe from a deployment.** It has run only against `httpx.MockTransport`; the
  first tick after the merge is its first live run, and which regions production has in use is
  unknown, possibly none, in which case it draws no region row at all.
- **The five direct password regions from Vercel's `fra1`**, as before; the new checks will say.
- **A `CONNECT`**: the tests tell the pools apart through httpx's private
  `_transport_for_url`, as `test_diary_proxy.py` already did.
- **The region alert in a real chat**: its hint and reason have been rendered by the tests'
  sender only.

### After #418's merge: the merge, the ACL, and two crashes

None of this is code in #422, and a close-out never gets a close-out of its own,
so it is written here. The source is the controller's notes of 11 October 2026.

- **#418 merged by the session** at 08:54 UTC on 11 October 2026 as `a28473e`, after the five
  checks on `3fc7f54`: CI green (What changed, Server, Host ×2, Vercel and Vercel Preview
  Comments, with Contract and Android skipped). It closed #411. Its close-out named itself as
  the only thing open; this is the SHA it left for the next one.
- **The RUVDS Squid ACL was widened** at 08:21 UTC, on the owner's grant, after a backup to
  `/etc/squid/squid.conf.before-netschool-20261011T082105Z`: the 19 «Сетевой город» hosts were
  added, Squid reconfigured, and Petersburg still answered 200 through it. Twelve hosts
  answered the school search; obr03, obramur, prim-edu, tomedu and edu71 answered a WAF's 403;
  `e-school.ryazan.gov.ru` and `sgo.kchgov.ru` did not answer.
- **Squid logs**: `access_log daemon:/var/log/squid/access.log`, line 27 of `squid.conf`,
  checked at 09:06 UTC. So the privacy text's «и может записать их в свой журнал» stays true.
- **The development machine crashed twice**: at 11:05 Moscow (`0x7F`, a double fault) and at
  12:49 Moscow (`0x1A` `0x41792`, PTE `0x4`, the faulty-RAM signature). The second zero-filled
  this worktree's git index and `COMMIT_EDITMSG` in the middle of Task 3's commit. The index
  was rebuilt with `git reset`, `git fsck` came back clean, and `diary_pb.py` was re-derived by
  Buf and matched byte for byte; the worktree, the git directory and `server/.venv` were
  scanned, and no other file was hit.
- **Issues filed**: #419 and #420, both on milestone 11, both closed by #422.

Section 7's «Next for the programme» says what follows.

## The milestones

**The milestones as they are now.** The owner renamed all nine on 25 September 2026, so that
every title names what its version delivered and every description names its pull requests
and issues, and created the tenth the same day, and the eleventh, twelfth and thirteenth on 3
October. The older batch sections, in `docs/history.md`
now, quote the titles of their own time, which a search no longer finds; the third column
maps them. The
`github-pr` skill carries the same table.

| # | Title | Called before 25 September | State | Covers |
| --- | --- | --- | --- | --- |
| 1 | `v0.1.0 — App, widget, admin bot and read API` | `v0.1.0 — First run on a phone` | closed | PRs #1–#14; issues #86, #88, #89, #278–#281 |
| 2 | `v0.2.0 — Petersburg e-diary, class run from bot and phone` | `v0.2.0 — The diary, and the class run from the bot` | closed | PRs #15–#17, #28–#31; issues #87, #90, #91, #102, #282, #283 |
| 3 | `v0.3.0 — School year, terms, school search, several classes` | `v0.3.0 — The school year` | closed | PRs #27, #32–#35, #43; issue #92 |
| 4 | `v0.4.0 — 67-defect sweep, first audit, app-wide correction mode` | `v0.4.0 — Nothing breaks in silence` | closed | PRs #44, #45, #50; issues #93, #94 |
| 5 | `v0.5.0 — Public repo: secrets audit, English docs, font licence` | `v0.5.0 — A public repository` | closed | PRs #46–#49, #51, #55–#57, #59; issue #95 |
| 6 | `v0.6.0 — Dishka DI, scrolling text, in-app guide from the repo` | `v0.6.0 — One container, and nothing cut off` | closed | PRs #60–#74; issues #96, #97, #99, #101, #103, #104, #284–#291 |
| 7 | `Dependencies — dependabot bumps` | `Dependencies` | open, for good | every dependabot bump; deliberately not a version |
| 8 | `v0.7.0 — School-year calendar, day ribbon, rearrangeable tabs` | `v0.7.0 — Оптимизация` | closed | PRs #75–#85, #128; issues #98, #100, #105–#108, #292 |
| 9 | `v0.8.0 — On-device checks, 89-region e-diary survey` | `v0.8.0 — On a device` | open | PRs #129, #133, #134, #186, #187, #189, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261, #263, #267 and #399; issues #109–#117, #119, #130–#132, #167–#185, #188, #219–#233, #237, #240, #242–#244, #246, #247, #249, #251, #253–#256, #258–#260, #262, #264–#266 and #397 — the first whose work needs an emulator or a phone, and #186 the first done on one |
| 10 | `v0.9.0 — NetSchool e-diary, onboarding via the school's diary` | none — proposed as «v0.9.0 — A second diary», never created under that name | open | PRs #140, #214, #218, #303, #335 (merged); issues #135–#139, #141, #145–#165, #190–#211 (the external audit of 27 September), #212, #213, #235, #236, #302, #334, #343 |
| 11 | `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console` | none — created on 3 October 2026 under this name | open | PRs #274, #277, #294, #296, #297, #300, #301, #305, #306, #307, #308, #311, #313, #319, #328, #332, #342, #350, #356, #372, #376, #379, #380, #385, #392, #394, #396, #410, #418 and #422 (merged) and #428, this pull request (open); issues #268–#273, #275, #276, #293, #295, #298, #299, #304, #309, #310, #312, #314–#318, #320–#325, #331, #336–#341, #347, #348, #351, #352, #353, #354, #355, #357, #367, #368, #369, #370, #373, #374, #381, #382, #383, #384, #389, #390, #393, #395, #401, #406, #407, #408, #411, #419, #420, #423, #424, #425 and #427 — the programme of `docs/specs/2026-10-03-one-contract-design.md` |
| 12 | `v1.0.0 — A build somebody else can install` | none — created on 3 October 2026 under this name | open | PRs #329, #333, #359, #363, #388, #398, #403, #413, #415 and #417 (merged) and #430 (a draft, open); issues #120–#122, #127, #142, #144, #326, #327, #330, #349, #358, #360–#365, #386, #387, #402, #404, #414, #416 and #429 — the steps epic #127 names between one class on one phone and a build a second family could use |
| 13 | `Backlog — not scheduled` | none — created on 3 October 2026 under this name | open | issues #118 (closed by #350), #123–#126, #143, #371, #375, #377, #378, #391, #400, #405, #409, #412, #421 and #426; deliberately not a version, like 7 — known gaps and decisions no release is waiting for |

**#142, #143 and #144**, two follow-ups and a decision that #140 left alone on purpose, were
on no milestone until 3 October: #142 and #144 are in the twelfth now, and #143 in the
thirteenth.

**Creating a milestone is the owner's decision**, and a session only attaches one — the owner
created the ninth on 22 September 2026, the tenth on 25 September and the eleventh, twelfth
and thirteenth on 3 October. **The ninth was the first
milestone that groups issues rather than pull requests**, and the first whose work cannot be
done without an emulator or a phone.

## Sections 1 to 4, and the history

Sections 1 to 4 — the state as of PR #51, what had been done by then, what had not, and the
defects closed before the tracker existed — are in [docs/history.md](docs/history.md) now,
after the oldest batch. Section 4 is worth reading before an audit files anything: it lists
defects already closed, each with what closed it. The four sections below kept their
numbers, because the documents, the skills and the history refer to them by number.

## 5. What nobody has verified

This is the main thing worth knowing: **all of this work is proved by tests and by nothing
else.**

**Most of this section is now also an issue**, which is where it should be worked from:
#113 the widget's sizes, #111 what the ribbon's shader costs and #121 the real diary are
open; #114 the arranging gesture, #115 the correction mode, #116 two classes on one phone
and #117 the upgrade path were closed by #186's walk on an emulator, and what only a phone
can add to them stays below. They are children of **#109**, the epic for moving this work to
a machine with a device on it. #140's additions below belong to #121 where they need a live
diary; #156, the backup, closed on the same emulator walk, and a phone's own backup is its
bullet below. The rest wait for an APK on a phone. The prose here is kept because it says
*why* each one is unverifiable, which an issue title cannot.

**#186 looked at a good part of this on an emulator** (26–27 September 2026; its section,
«…the first walk of the app on a device», in `docs/history.md` now, has the detail). The
bullets it answered say so in place, and what an emulator
cannot answer — a thumb, a haptic, a real GPU, a real launcher's corners — is still here.

- **#423, #424 and #425 have been seen only by their tests** (#428; its section above has the
  detail).
  - **The «CI» job has not met a skipped «Host».** #428's run, 38148509706, was green and
    printed `gates: success success success success success`, but every gate ran there; the
    case the job exists for rests on the test's run of its script under Git Bash, and the
    ruleset that requires it does not exist until the merge.
  - **CI did not run the legal parity tests on edition 4** (#427): its «Android» job restored
    `:core:data:testDebugUnitTest` from the build cache. Edition 4's parity rests on a local
    `--rerun` (11 and 10 passed).
  - **No client changes an event's kind yet.** The rule is v2's `UpdateEvent` and nothing calls
    it but the tests until sub-project 5.
  - **Edition 4 of the privacy policy has not been opened in the app**, and what it says leaves
    for Sentry is checked against `app/observability.py`, not against an event Sentry received.
- **#406's routes have been seen only as far as a school search** (#422; its section above has
  the detail).
  - **The twelve proxied regions answered a school search, not a sign-in or a read.** On 11
    October each was asked, by hand from the RUVDS VPS, for `/webapi/schools/search`; no
    sign-in, diary read, period read or keep-alive of the eleven newly routed password regions
    has gone through Squid, and whether every path passes through it unchanged is unknown.
  - **The five direct password regions from Vercel's `fra1`** are as unknown as before; the
    region checks will say, once a class or a family uses one.
  - **Production has no region in use, so the probe has asked none live.** «📊 Проект», read at
    17:02 Moscow on 11 October on `6354c3d`, drew no region line, which is what no class bound
    and no family signed in looks like rather than a sign that the checks are broken; the first
    class or family on a «Сетевой город» region is the probe's first live run.
  - **One address carries eleven served regions and Petersburg.** The RUVDS VPS is a single
    point of failure for all of them, and `diary_proxy` the one check that says it is the
    tunnel.
  - **No setting undoes only the «Сетевой город» half.** `DIARY_EGRESS=direct` takes Petersburg
    off the proxy too, so taking the regions alone off it is a change to `regions.py` and a
    deploy, not a setting.
  - **What a family sees when the proxy fails** is `UpstreamUnavailable`, which the bot words
    as the region's own outage; only `diary_proxy` tells the owner which it is — and the same
    is true the other way round: a proxy that refuses the tunnel to one region is reported as
    that region's own fault, with the hint pointing at `via_proxy` rather than at the proxy
    (#421, filed by the whole-branch review).
  - **The tests cannot make a `CONNECT`.** They tell the two pools apart through httpx's private
    `_transport_for_url`, as `test_diary_proxy.py` already did, so an httpx upgrade that renames
    it fails those tests rather than the route.
  - **The full suite ran on PostgreSQL on #422's branch, at `7ee7fda`**: 3311 passed, 7
    skipped, in 3 min 52 s, covering `80cce89`; its Gates section above has the figures.
  - **The region alert has not reached a real chat**: its hint and reason were rendered by the
    tests' sender only.
- **The geometry pass (#404), the tray (#415) and «Календарь»'s header (#417) have been seen on
  an emulator, not on a phone.** On 10–11 October the debug APK of `7ce4677`'s code ran on a
  Pixel 10 Pro XL image (API 37), in light and dark: the weekday strip fills the width and ends
  on the switcher's line, groups are 28 dp with 4 dp rows inside, month cells are 12 dp, and «О
  приложении» is a group. #415's tray read 8 dp all round there, and #417's header was seen at
  font scales 1 and 2; the owner was sent the shots. Not judged: the one-line rows, the chip
  rows and the small «День» FAB's circle. The FABs' shared radius and the four fields at 28 dp
  have no test, being Material defaults inside a component. Section 7 has the owner's look on a
  phone.
- **#357 is confirmed live, and not fixed.** On 10 October, against a local server on
  PostgreSQL, a linked owner's `ClassService/GetClass` answered `200` over Connect GET and
  Connect POST with no `Cache-Control`, while its REST twin, `GET /api/v2/class`, answered
  `private, no-store`: the defect exactly as filed. `GetCalendarFeed` answered `501` on every
  transport there, because no `PUBLIC_BASE_URL` was set.
- **The «Сетевой город» school search reads a key no live server sends (#412, backlog).** Every
  row the four servers asked on 10 October sent back carries `address`, `cityId`, `id`, `inn`,
  `name`, `ogrn`, `provinceId` and `shortName`, and the code reads `addressString`. `address`
  was null in all 407 rows, so nothing is lost today; `ogrn` and `inn` are in every row, the
  registry link the README says is missing.
- **The emulator's `system_server` aborted once during the gate's walk** (ART heap corruption in
  its GC, a SIGABRT in HeapTaskDaemon), and Android restarted. No crash of our package was
  logged. It is noted beside the development machine's known faulty RAM, and unexplained.
- **The launcher icon of #388 has run on no device and no emulator.** Every claim about it is a
  JVM test's, and the launcher that draws it is another app. Unseen:
  - any real launcher: how Pixel Launcher, One UI and MIUI place, re-index and clip the icon
    when the alias changes;
  - themed (monochrome) icons on Android 13 and later;
  - the edge-to-edge styles under a launcher mask smaller than the standard circle;
  - whether the app stays open, and stays in Recents, after «Применить» disables the alias it
    was started through;
  - whether «Значок приложения» stutters when it opens on a low-end phone, since its 64 tiles
    inflate vector drawables on the main thread;
  - whether the 112 dp preview is sharp, now that each drawn icon has its own drawable state;
  - the home-screen shortcut lost on an update from a build where `MainActivity` was the
    launcher entry. That one is expected, and only development installs meet it, because
    nothing has been released.

  Section 7 has the owner's pass on two emulators that starts on it.
- **The app's handling of a `503 disabled` on a diary read with a token has not been checked on
  a device** (#302). The app parses `X-Diary-Unavailable` for every diary call, but a read that
  carries a token and is still answered «disabled» is a new case for it; only the server's
  tests have seen it.
- **The new CI filter (#295) has been seen on a GitHub runner twice**: in #305's own CI, and in
  #311's, whose first commit (`66c7ba1`) touched only `CLAUDE.md`, `.claude/` and
  `docs/build.md` and started the server job with the Android and contract jobs skipped. Nobody
  has seen a commit confined to `proto/` start it on its own. Its guard, `test_ci_paths.py`, sees only the first part of a path, so a new file
  under a root already listed (`.github/dependabot.yml`, say) is caught only by adding it to
  the guard's written list.
- **Whether Telegram ever delivers a message that starts with whitespace is unverified**
  (#304). The form breakout now treats «  /week» as a command because aiogram's `Command`
  filter does, after `text.split()`; its clients are not known to keep plain spaces there, but
  a no-break space is whitespace to `str.split`, and nobody has sent one.
- **v2 as #342, #350, #356, #372, #376, #380, #385, #392, #394 and #396 serve it has been
  asked little outside the test client** (stages 3a, 3b-1, 3b-2, 3b-3, 3b-4, 3b-5, 3b-6, 3b-7,
  3b-8 and 3c; their sections above, or in `docs/history.md`, have the detail):
  - **on production, only after #342's promote**: on 5 October `/api/v1/warmup` reported
    `status` `ok`, schema `0017` and `"v2": true`; REST `/api/v2/diary/capabilities` answered
    `200` with `private, no-store`; Connect answered `200` in JSON and in binary; native gRPC
    got `415`; and `/api/v2/me` without a token answered `401 DEVICE_TOKEN_INVALID`. The nine
    methods of 3b-1 were asked once after #350's merge, without a token: REST
    `/class/auditEntries`, `/class/devices` and `/class/subjects` answered `401`
    `DEVICE_TOKEN_INVALID`, and Connect `SubjectService/ListSubjects` answered `401`
    `unauthenticated`. The fifteen of 3b-2 were asked once after #356's merge, without a
    token: REST `/class`, `/class/bellSchedules`, `/class/timetable` and `/class/terms`
    answered `401` `DEVICE_TOKEN_INVALID`, and Connect `BellService/ListBellSchedules`
    answered `401` `unauthenticated`. The five of 3b-3 were asked once after #372's merge,
    without a token: REST `/class/accessRequests` and `/schools` answered `401`
    `DEVICE_TOKEN_INVALID`, `/schoolRegions` with a query too short to be counted `400`
    `VALIDATION_FAILED`, and Connect `AccessRequestService/ListAccessRequests` `401`
    `unauthenticated`. The eleven of 3b-4 were asked once after #376's merge, without a
    token: REST `/me/tasks`, `/me/calendarFeed`, `/me/linkCodes` and
    `/me/homeworkTicks/1` answered `401` `DEVICE_TOKEN_INVALID`, and Connect
    `MeService/ListTasks` `401` `unauthenticated`. The ten of 3b-5 were asked once after
    #380's merge, without a token: REST `/class/homework` (`GET`), `/class/events` (`GET`),
    `POST /class/homework` and `DELETE /class/events/1` answered `401` `DEVICE_TOKEN_INVALID`,
    and Connect `HomeworkService/ListHomework` and `EventService/ListEvents` answered `401`
    `unauthenticated`. The seven of 3b-6 were asked once after #385's merge, without a token:
    REST `GET` and `PATCH ?allowMissing=true` on `/class/days/2026-09-14`, `GET` and `POST
    /class/substitutions`, and `DELETE /class/substitutions/1` answered `401`
    `DEVICE_TOKEN_INVALID`, and Connect `SubstitutionService/ListSubstitutions` `401`
    `unauthenticated`; the ten of 3b-7 are asked after #392's merge, once, without a token;
    the four of 3b-8 are asked after #394's merge, once, without a token;
  - **the diary over v2 against a real diary**: every 3b-7 test drives Petersburg's or
    «Сетевой город»'s fake upstream, or none; no session has been registered over v2, and
    «Сетевой город»'s week walk, periods and adoption have never met a live server through
    either version; and no correction has been written, reset or cleared over v2 from a
    phone;
  - **v2's notices to the class through Telegram itself, beyond the event's**: `CreateEvent`'s
    reached a real chat through the test bot on 10 October (#411's gate); the homework's, the
    days' and the substitutions' have met only the tests' bot that records what it was asked to
    send;
  - **the bot committing before it speaks, through Telegram itself**, for a task, a tick and
    the feed's address: the tests hand the handlers a chat that reads the database as they
    speak;
  - **`ListSchoolRegions` and `ListSchools` against DaData**: every v2 test replaces the
    search;
  - **`ListTerms` and `GetTermScheme` across 1 September, in a zone far from Moscow**:
    `current_year` reads the class's own clock, and every test ran on the day it ran;
  - **`x-vercel-forwarded-for` as Vercel's own proxy sets it**: on 10 October a local server with
    `VERCEL=1` was sent the header by hand and counted it into the right bucket over v1 and v2
    (#411's gate), but Vercel's proxy has not been watched setting it;
  - **a `client_version` from an APK**: none sends the header, so every one in production is
    null, and the live gate of 10–11 October could not change that;
  - **a segfault in a native extension at import**, which `mount_v2` cannot catch: it turns an
    import that raises into the `503`, and an interpreter that crashes raises nothing;
  - **the bot's new refusals through Telegram itself**: a cancellation of a lesson the
    template does not have, and a shortened day on bells that ring nothing, were pressed by
    the tests' fakes only;
  - **the host target outside CI**: `python -m app.host` has run on a CI runner, on the
    development machine on SQLite, and once, live, as its own image against a throwaway
    PostgreSQL, with the owner present (`lessons-host:3c` at `261450e`, torn down
    afterwards); still unseen: a TLS proxy in front of it, a real bot's webhook pointed at
    it, a stream held longer than a test's seconds, and a phone speaking native gRPC to it.
- **The monitoring of #359 has been seen working in production, and failing for real.** Its
  four checks read `ok` on the first tick, «📊 Проект» drew on the owner's screen, and the
  proxy's failures that evening were told to the owner with their recoveries («After #363's
  merge», in the section on #366). On 10 October a local server on PostgreSQL sent a schema
  alert and its recovery through the test bot, eight concurrent claims on an alert had one
  winner, and two concurrent ticks sent one digest (#411's gate). Still unseen: an error event
  reaching Sentry. A transaction has, sent by the SDK's background transport from a Vercel
  function; the errors dataset held no error event in the thirty days to 10 October, which a
  server without errors and a transport that never delivers one would both produce, and
  `SENTRY_DSN` cannot be read back from Vercel to send one locally. Section 7 asks the owner for
  one deliberate error.
- **Buf's unauthenticated rate limit has not been met.** CI's «Contract (Buf)» job fetches two
  remote plugins without a token; one run, 37230983817, was not throttled. If it ever is,
  either step can be the one that fails (the buf-action step fetches `googleapis` from the
  Schema Registry before generate runs, and says nothing of what to do), and the remedy is the
  same `BUF_TOKEN` (section 7).
- **`buf breaking` has refused a change only locally.**
  - Locally, it compared a scratch copy of the contract with `main` at `45f0680` over 25
    mutations (#300, which found #298).
  - In CI it has compared once, on #300 (run 37239214776), and found nothing breaking, as
    there was none. No CI run has yet refused a breaking change.
  - Buf reads no options (#299), so a binding, a credential or a role changed in `proto/` is
    caught by the resource map in `test_contract.py` and by nothing in Buf.
- **The Android generation of the contract was never compiled** (#300). The protocolbuffers
  java and kotlin (lite) and connectrpc/kotlin plugins were run once into a scratch directory
  with no clash and no warning; no Gradle module has seen the output, and what sub-project 5
  inherits is in the programme design's section 3.
- **The Postman smoke collection has never been run in Postman** (#300). It lives in the
  owner's personal workspace and is not in the repository; its anonymous requests were made
  with curl against production and answered as its tests expect, and its second folder, which
  needs a device token, has not run at all. The live gate of 10–11 October did not run it.
- **The cold-start fix (#275) has been read on Vercel, not measured.** The 736 aiogram modules
  were measured in a fresh interpreter on Windows under Vercel's settings, and
  `tests/test_cold_start.py` holds the answer there. On 10 October at 20:50 UTC the last 55
  minutes of production's runtime logs, on `1eb134e`, held two cold starts, both on a cron tick:
  the import-time «Telegram webhook mounted» came 0.04 s after the request id's own stamp in one
  and 4.7 s after it in the other. Logs alone cannot say whether that stamp marks the edge's
  receipt or the instance's start, so the import's real cost is not separable there.
- **The developer mode (#237) past its door has been seen by nobody.** #257's session saw
  the reveal on an API 37 emulator, along with the toast, the section in the settings, the
  signed-out page and hiding it again; that walk is what found #255 and #256. Everything
  behind the GitHub sign-in is still unseen: the Keystore round trip, the connectivity,
  notification, alarm and widget checks, the grid, the large text, the stretched strings.
  The GitHub permission call has not met a real account. An APK built without
  `LESSONS_GITHUB_CLIENT_ID` cannot get past the door, and every build here so far is one.
  The mode is also the first tool for most of this section: its checks run from the phone's
  own network, and its records name the leg a sign-in is stuck on.
- **Not one route in `docs/diaries/` has been seen answering.** #134's 1916 routes
  were read from client code and two official apps, and cross-checked against each other.
  No diary host answers a cloud session, so none was ever called. A route marked *current*
  means that a client active in 2025 or 2026 sends it, not that a server accepts it today.
  ТОР «Моя школа» rests on one app build, 5.0.0.454, and its first-wave list rests on one
  official post. The first live session on any platform will correct its page. Whoever has
  it should edit the page by hand, because the generator is not in the repository — and,
  where the edit touches `docs/diaries/regions.md`, regenerate the region catalog with
  `scripts/region_catalog.py`, which a test insists on.
- **The «Сетевой город» provider has met live servers only through its school search.** #140
  wrote the client, the mapper, the keep-alive and the school search from open-source clients
  and hand-written test payloads. On 10 October the server's own client,
  `NetSchoolClient.schools_search`, asked four regional servers for «школа» with no account and
  got their schools back (Амурская 33, Ленинградская 199, Коми 98, Алтайский край 77), which
  found #412, above; Primorye's did not answer from the development machine. Every other
  upstream shape — the salted-MD5 sign-in, `/webapi/context`, the weekly diary — is what a
  client sends, not what a server was seen to accept. The refresh-token grant (LoginType 9) and
  the Госуслуги refusal path (`SignInUnsupported`) are the least exercised of all. The sign-in
  needs an account, which the gate did not have, so the first live sign-in on it is still the
  owner's, #121's caveat a second time.
- **The phone's own sign-in has never met a live diary either — neither «Сетевой город» nor
  Петербург.** The Kotlin ports and the Python originals run one set of known-answer vectors
  (`server/tests/vectors/diary_protocol.json`), which proves the two agree and nothing about
  whether either is right. Whether a Петербург pupil's own account lists itself as a pupil,
  and how long its token lives, are unknown for the same reason. Петербург's sign-in met the
  live diary once, on 5 October, on an emulator: an account the owner supplied was answered
  `401`, which a made-up account gets too, so it says nothing about whether the port is right
  (the section on #342).
- **Nobody knows whether a diary accepts a session opened on a phone when our server replays
  it.** The phone signs in from the family's own address, and `POST /api/v1/diary/session`
  then reads with that session from Frankfurt. A diary that ties a session to its address, or
  refuses foreign ones, answers that read with a refusal, and the server turns it into a
  `409`, meaning the diary refused the session from our address — by design, with no automatic
  retry. If that is what the first live session gets, the phone-registered path does not work
  for that diary and only the password routes remain. Whether «Сетевой город»'s four bootstrap
  calls fit inside Vercel's 30-second ceiling is unmeasured too. **For Петербург the read gets
  that far since 5 October.** On 2 October the city's network did not answer Frankfurt at all
  (#235); production's diary requests now leave through a Russian proxy, and a made-up session
  was answered `409`, «Дневник не принял эту сессию с нашего сервера — дело не в пароле.»
  («Outside the pull request, the same day», in the section on #342). A session a phone opened
  is then replayed from the proxy's address rather than the family's, and whether Петербург
  accepts a real one that way is as unknown as for any diary. «Сетевой город»'s regions were
  not asked from Frankfurt. Since #406 a session in one of the twelve proxied regions is
  replayed from the RUVDS address too, as Петербург's is.
- **#140's phone half has run on an emulator, and only as far as a diary without a
  password goes.** Nothing composes `LessonsApp` in a test, so the gate between the three
  homes, the hold on the first run and the resume after the process dies are still proved by
  the pure rules and the view models only. #186 walked the first run both ways in, in both
  languages: the legal documents online and the bundled fallback offline, the region list, a
  Госуслуги-only region's hand-off, ТОР «Моя школа»'s, a live school search and the password
  form. Nobody has heard any of it through TalkBack, and `diary.db` has not been written,
  because no password was typed. The live gate of 10–11 October reached neither.
- **TLS against the regional servers is seen succeeding once, and its failures and Android's
  own windows-1251 are inferred.** #186's school search reached Амурская область's live
  «Сетевой город» server over TLS from the emulator and got its schools back. The failure
  shapes `UpstreamHttp` classifies are the ones Conscrypt and the JVM are known to throw, not
  ones seen on a device; the port carries its own windows-1251 table rather than
  trusting the device's ICU, and whether the device's would have agreed is unasked. A
  `Retry-After` given as a date is read as no wait.
- **The backup exclusion of crash reports (#156) is seen on an emulator, not on a phone.**
  `BackupRulesTest` reads both rule files; #186 ran `bmgr` through the local transport, plain
  and flagged as a device transfer, and the report stayed out while a probe file came back.
  Google's own transport to a real account has not been asked.
- **The widget's third sentence has never been on a launcher.** Whether «Дневник — в
  приложении» and the new sentence for a phone with neither fit their rungs without a clip is
  judged by length only, and the redraw when the mode changes rests on a broadcast no test
  composes. The live gate of 10–11 October did not reach it.
- **DaData's side of the school directory is assumed.** Whether its company rows carry
  `region_kladr_id` is unknown — the directory falls back to the region's name for exactly
  that reason — and so are the subject codes of the four regions admitted in 2022 (90, 93,
  94, 95), every DaData spelling in the catalog's overlay, and whether DaData's day turns at
  Moscow midnight, which is when `usage_counters` starts a new one. Two requests racing for
  the last unit of the day were checked on PostgreSQL on 10 October, and only one got it; the
  live gate asked no DaData.
- **The terms of use and the privacy policy have never been read by a lawyer.** They
  describe what the code does and claim compliance with nothing: whether naming a GitHub
  account as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line
  and parental consent are open. No gate a session runs can answer them. The owner wrote a
  brief for a lawyer on 11 October, kept outside the repository, and another session wrote
  edition 5 of both texts from it: draft #430, which closes #429 and is to be rebased onto
  edition 4 (#425) once #428 merges. A session wrote it, not a lawyer.
- **The tab arranging has been seen on an emulator only, and it is a gesture.** #84 is a
  long press, a wobble and a drag; #85 is the drag actually reporting where it landed. What
  the 39 tests prove is arithmetic and contracts: where a drag of so many pixels lands, that
  the gesture reports the slot it computed, that a move is a permutation, that the stored
  order repairs itself, that the shell's one back rule leaves the mode before it leaves the
  app. #186 saw it work on an emulator (#114), and made it one gesture (#181). What nobody
  has felt: whether the wobble is plausible, whether half a slot is the right threshold
  under a real thumb, whether a hold that picks the tab up at once is too eager for someone
  who only meant to look, and whether the haptic lands where the mode opens. **The APK built for #84 predates the fix and does not
  rearrange anything**; the first install worth an evening is one built after #186, as
  section 7 says, because #181 made the gesture one touch.
- **The drag is driven from a test now, and how far it goes is the whole reason it can be.**
  #84 left it undriven on the ground that Robolectric reports its own densities, so a
  synthetic swipe of «half a slot» measures the environment. True, and it hid a defect for a
  batch: a drag of ten thousand pixels parks at the end under any density, which is what
  `ToolbarDragTest` does and what caught it. Anything *between* two slots is still not asked
  of a composition, and `ToolbarReorderTest` asks it in pixels instead.
- **The year scrolling has been looked at once, on an emulator** (#186): the year picker,
  and 2025/26 fetched on demand under «Загружаю год» from a server on the same machine.
  What the tests prove is which year is asked for and when, which one is dropped, and that
  a request made while another was in flight comes back. What nobody has seen: how long a
  school year takes to arrive over a school's wifi, and what three years of day rows cost a
  `LazyColumn` that re-reads them on every write. The cap of three is a guess.
- **The calendar and the day screen have been looked at on an emulator only** (#186): the
  week, the month, the day's ribbon and its list, and «Подробно». What the tests prove is
  which accent a day resolves to, where a band of one reason ends, which row
  «вернуться к текущему» lands on, and which of three depth levels a given API level gets.
  What nobody has judged: the four accents beside one another, whether the summer's tint is
  faint enough across sixty cells, whether the perspective tilt reads as depth or as a
  wobble, and whether the AGSL band reads as light rather than as a smear. **What it costs
  on a phone is unmeasured too** — one shader layer and a per-frame clock on a mid-range
  phone is a frame budget nobody has looked at. #111's flings on the emulator put the shader
  below that emulator's own floor, which is relative only, and #111 stays open for a phone.
  **One qualification, earned in #79:** the ribbon *has* now been run on a real phone, and
  it crashed on opening. What that proves is that the three defects #79 fixes were the ones
  reported, and the bugreport says so in the platform's own words. It proves nothing about
  how any of it looks, because nobody got far enough to see it. #186 got that far on the
  emulator, and the ribbon opened without the crash.
- **No rule stops another composable reading `BuildConfig` directly**, which is how #81
  happened. Three tests hold `AboutCard`; nothing holds the next one. Such a defect is
  invisible on a pull request by construction — `ci.yml` does not set the build properties
  and should not, because it does not produce an APK anybody installs.
- **The server badge has been drawn against a real server once, on an emulator** — «Сервер:
  база и код разошлись» from a local `create_all` database, correctly — and not yet from a
  phone on a school's wifi. `ServerStatus` has four
  states and a test for each, all of them from a fake; nothing in the suite opens a socket.
  What that leaves unchecked is the shape of a real answer — whether `/api/v1/warmup` from
  a phone on a school's wifi resolves into `Ok`, `Degraded` or `Unreachable` as intended,
  and how long it takes to say so. It is also the first thing in this app that makes a
  network request from the settings page, so it is the first that can make that page wait.
- **#80's screens have been on an emulator, and nobody judged what this bullet asks.** #186
  opened the about card — «Схема unknown» on it was #172 — and the calendar. The tests
  compose the about card, the calendar header and both link buttons and assert their text
  is *displayed*. What nobody has judged: whether the concentric corner reads as
  concentric, whether a marquee that never stops is pleasant rather than merely readable,
  and whether stepping a month with the arrows surprises somebody who has just switched
  «День» into its list mode.
- **The widget's new corners have been on a launcher only on an emulator's, and nobody judged
  them there**: #186 and #189 had the widget on the Pixel launcher at a few sizes, and #113
  stays open for the corner radii. A launcher is the only place they exist. #82 derives every
  inner radius from the size class's own padding, and the five tests reproduce that
  arithmetic and nothing else — no test draws a widget. What nobody has
  seen: whether the gap now reads as even at 8 dp of padding *and* at 16, whether the 6 dp
  floor is tight enough to still look like a corner on the widest rungs, and how any of it
  sits against a launcher's own widget rounding. Below API 31 the question does not arise —
  `cornerRadius` is a no-op there and the launcher supplies square edges — so the check
  needs a phone on 31 or later and a widget resized twice.
- **#189 was seen on an emulator's launcher, and only as far as the day-off layout goes.**
  The 2×1, 2×2 and 2×5 after school, at font scale 1.0 and 2.0, in both languages. Not
  seen:
  - the one-line size during lessons at a large font, where «ПЕРЕМЕНА» is weighted beside a
    countdown the same way «ДЗ на завтра» was beside its count;
  - whether a change of the *system* font scale redraws the widget by itself — it decides
    the rung and the 2×1's count, it is a configuration change rather than one of the three
    app settings #188 now redraws on, and every render in that check was forced with a
    reinstall;
  - any launcher but the emulator's.
- **#83's widget work has been on a launcher only on an emulator's, and that is the surface
  #83 changed most.** #186 put the widget there at four sizes and two font scales, where the
  2×2 and, at the larger scale, the 2×1 still cut their text (#174, fixed by #189 and seen
  again there, above). The font-scale division, the two weights that stop the trailing
  detail starving the subject, and the outer box that gives the seven day chips their gaps
  are all proved by tests that reproduce the arithmetic and the budgets — no test draws a
  widget. Worse for confidence: **two of the claims are about what Glance does with a
  modifier chain, and they come from reading its translator rather than from pixels** — that
  `applyModifiers` folds every padding into one `setViewPadding` on the background's own
  view, and that a container keeps ten children. What nobody has seen: whether the largest
  system font fits the rung it lands on at the sizes #186 did not use, whether the chips read
  as seven days, and
  whether the subject keeps its width beside a long teacher's name. Below API 31 the corner
  half of this does not arise at all — `cornerRadius` is a no-op there.
- **`docker compose up` has come up once, and only as far as the warmup.** On the night of
  10–11 October, from `3b4aba4`, with a throwaway root `.env`: the image built from
  `server/Dockerfile`, `migrate` ran `alembic upgrade head` inside it to `0019`, and `server`
  answered `/api/v1/health` and `/api/v1/warmup` on port 8000 (#411's gate). `RUN_BOT` stayed
  `false`, so the bot has never polled from the stack, and nothing past the warmup was asked
  of it.
- **The animations #247 added have been seen only in two frames on an emulator.** Rows
  opening under their switches and the selected tab's label being revealed were caught with
  the animator scale at ten; the list items fading and sliding on «Сегодня» and «Задания»,
  and the lessons' cross-fade, have not been looked at, and what the extra fades cost a cheap
  phone's GPU is unmeasured.
- **Back in settings follows a path now (#243), and only its rules are tested.** Nothing
  composes `HomeShell`, so that its back handlers and the pill read the path rests on one
  walk on an emulator; a rotation or a process death in the middle of a path, which the
  saved names are meant to survive, has not been seen.
- **#201's Keystore seal and #202's https-only release have run on emulators, not on a
  phone** (2 October 2026, #241). Seen: an upgrade in place sealing a plain-text membership
  and the sealed token opening after a force-stop and a reboot; a release build refusing
  `http://` to anything but the loopback names at the address field, joining over
  `http://127.0.0.1`, and reading production's warmup over TLS. Not seen: a restore onto
  another phone or a wiped Keystore, which should read as no token; the diary's bearer,
  sealed the same way, because no diary session was opened; and a release build joined to a
  production class.
- **No Compose test was actually made to hang**, so `MarqueeClockTest` — the guard #83 added
  for the trap that costs a whole Gradle run — is reasoned from `MarqueeText`'s
  `Int.MAX_VALUE` iteration count and from the list of files that call `createComposeRule`.
  It is the honest status of a meta-test: what it proves is that thirteen files say in their
  own words why they cannot overflow, not that the fourteenth would have hung.
- **Eighteen `AdrenoVK-0: Shader compilation failed` lines in that bugreport are
  unexplained**, and no other app on that device logs them. They are `I`-level, carry no
  shader source and no reason, and are spread across screens rather than clustered on the
  ribbon, so nothing in the report ties them to a defect anybody saw. The honest status is
  «замечено, не объяснено»: it is a real signal, it is nobody's confirmed bug yet, and the
  first question is whether the AGSL sheen is even the source, since a `RuntimeShader` that
  would not compile normally throws in-process instead of logging from the render thread.
- **The reported term defect is fixed against tests, not against the class that reported
  it.** The server fix needs no new APK, so the first real check is that class's next sync
  after the deploy, and no batch since has recorded one. The cheapest sign that the
  migration and the code met is in: production's `/api/v1/warmup` answered
  `"schema":"0017"` on 26 September 2026, read through the Vercel connector.
- **There is one `androidTest` in the project, and it is about one gesture.** Until #187
  there was none, because until #186 no session had an emulator: the cloud containers have no
  `/dev/kvm`. `ToolbarOnDeviceTest` (#110) drags the tab bar's arranging gesture at a
  device's own density, on demand and never in CI; every other screen is still asked on the
  JVM only.
  **But "nobody has pressed it" is untrue for a good deal of the app by now.** Robolectric
  runs Compose's test harness on the JVM (`:core:designsystem` already lived this way), and
  `:app` has twelve files that compose a real screen and press it: the class group, the
  join-mode switch, the code screen's refusals, the bell rows, the schedule sheet, the term
  label, the onboarding reveal, the debug report, the translation session, the edge fade and
  the Telegram card's failure — 43 tests between them. It started at three screens and 21
  tests, which is what this paragraph said for several batches after it had stopped being
  true. Those are real presses on real strings rather than stubs: the locale is pinned to
  `ru-rRU`, or Robolectric takes `values-en/` and the test checks the translation instead of
  the source.
  What this does **not** prove: how it looks. Not the layout, not the dark theme, not the
  animations, not dynamic colours, and not the widget — about which what is proved is exactly
  that the size ladder is monotonic over real sizes.
- **Every card in the bot is now drawn**, and that found eight defects in one pass
  (section 4, in `docs/history.md` now). The hole that is left is exactly where it was: the
  tests assert what is
  written on a card rather than how it looks in a client. A live Telegram has shown these
  cards once, in #186's walk — `/start`, «📱 Подключить телефон» and the class «11А» driven
  from the owner's chat — which recorded what the bot did and not how a card looked.
  **Nobody draws the widget.** Glance has `glance-appwidget-testing` at the same version as
  the Glance in this project (`1.3.0-alpha02`) — the twelve rungs of the size ladder could be
  rendered and compared with no device. Not done, and the live gate of 10–11 October did not
  reach the widget either.
- **Nobody has made a correction over the diary on a screen.** They are covered by tests on
  both halves — the key when a class is split into groups, an empty correction, the diary
  moving out from under a correction, the "correct / reset / do nothing" decision, and that a
  correction survives signing out and back in. **Not one test opens the correction window.**
  Nobody has pressed a lesson, saved, reset, or seen the «Исправлено» mark. The test counts
  are deliberately not named: they go stale in one commit, and `./gradlew test` and
  `pytest -q` print them themselves.
- **The real dnevnik2 has still not been opened.** The corrections are proved against a
  hand-written stub — exactly like the rest of the integration, and exactly as far as it
  claims. In particular, nobody has seen a real day split into groups, which is what the
  refusal to apply a correction on a key collision was made for. The live gate of 10–11
  October had no account to open it with.
- **Nobody has held two classes on one phone.** Storing memberships and splitting the cache
  by class are covered from below; from above, `ClassRowsScreenTest` now presses the group
  itself: the tick stands next to the class being shown and it is not a button, another row
  calls the switch with the right id, and there are two ways out, each saying how far it
  goes. The last is the one that deletes data if you get it wrong: with one class, «Выйти»
  always meant "leave this one", and there used to be nothing to catch the day that row
  quietly started meaning "all of them".
  #186 held two on an emulator (#116): the switch was seen, and the widget's redraw after a
  second switch inside one Glance session was found broken and fixed (#168). Still seen by
  nobody: the re-planning of the alarms, which is inferred from code that is called rather
  than observed.
- **Nobody has switched the join mode on a live class** — but the switch is now pressed in
  tests: the confirmation appears only in the direction that takes something away, «Отмена»
  writes nothing, and restoring the class code writes straight away. It is covered lower down
  the stack too: the class code's refusal in `invite`, a personal code for one phone, a race
  between two requests for one code, a stale button, the sweeping, a `PATCH` in both
  directions, the log line, and that the column holds `OPEN` rather than `open`. The presses
  are `ClassJoinModeScreenTest`'s. #186 saw «📱 Подключить телефон» in the live bot, its
  code link a phone as «Владелец», the spent code answer `404`, and the invite-only class's
  own code refused in words; nobody has flipped the switch on a live class or seen the
  confirmation in the app.
- **The `429` on the code screen was seen once, on an emulator (#186):** «Слишком много
  попыток. Попробуйте через 15 минут.», with `Retry-After: 866`.
- **The upgrade path has been installed over once, on an emulator (#117)**: a build of
  `fa4fe0c` upgraded to `bd7c816` kept its class, its tab order and its first run done. What
  follows is what held it before. An old install
  kept its class in four flat keys; a test builds exactly those keys and makes sure the class
  is found and the "introduction shown" flag is not reset. Nobody has upgraded a real
  installation of the previous version. If that is broken, the user sees the code field
  instead of their class — and nothing in the logs. The live gate of 10–11 October could not
  try a second: the emulator's release install is `apk.yml` run #41, a branch build signed with
  the runner's throwaway debug key by design (#330), and a release build under the owner's key
  was refused over it with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. A real check needs two builds
  signed by one key.
- **The build-chain bumps have been looked at only as far as #186's walk goes.** AGP
  9.3.1 → 9.4.0 → 9.4.1, Gradle 9.5.0 → 9.7.1 and compose-bom 2026.06.01 → 2026.09.00 are
  proved by every test passing and both assembles building — locally and on the runner.
  The same goes for the two Python floors raised the same way, sqlalchemy 2.0.54 and
  pydantic 2.13.5: the suite passes on them and nobody read either changelog. The
  compose-bom is **the app's entire rendering**, and one of its effects already surfaced by
  itself (`OverlayLayerTest`, section 6). Both builds #186 walked on an emulator —
  `bd7c816`'s release and its own branch's debug — are on all three, and the one defect it
  filed that names any of them is #185, AGP 9.4's warnings about `srcDirs`: a build
  message, not a screen. Nobody has set a screen beside one from a build before them, so
  what the compose-bom changed where there is no test, nobody knows. On a phone, that is
  the first reason on the list to open the APK.
- **Both alarm fixes are unverifiable without a device, in principle.** They are inferred
  from the platform's contract and from neighbouring code that had already taken the same
  decision (`SchoolAlerts.arm` refused `setWindow` and explained why).
- **A live DaData has never been asked**: there is no `DADATA_TOKEN` in the development
  environment, and every test on both sides runs a stub. The owner reported setting the key
  on Vercel — **nothing has verified that.** #140's anonymous school directory rests on the
  same stub, and so does its daily count. The live gate of 10–11 October did not ask it
  either; a query is the owner's (#120).
- **The real dnevnik2 has never been opened**, neither the sign-in — the server's or, since
  #140, the phone's own — nor any of the four screens.
- **Not one server-side fix has been run against a live class.** `claim` is verified by
  claiming twice on one date, and two ticks at once ran on 10 October against a local class on
  PostgreSQL, where exactly one evening digest arrived through the test bot (#411's gate); the
  export's round trip is verified by computing both sides, which is all it claims.

- **#62, the batch that stopped text being cut off, has been seen moving only in the reports
  #80 answered**: on a phone, a line that scrolled beside one that had spent its three passes
  and parked, which #80 made endless. Robolectric composes the marquee but advances no
  animation and reads no frame, so what is proven is that a line which fits is laid out
  exactly as before, that a line which does not stays inside its box rather than pushing
  the row apart, and that the whole string is present either way. Whether it reads well
  scrolling needs the APK. The same for the other direction: nobody has seen what an uncapped block does with a long string. **A five-line
  lesson note now makes a five-line row**, and whether an uncapped heading pushes a sheet's
  first control too far down is a question only a screen can answer.
- **The fade widths are chosen, not measured.** 8 dp at each end, narrower on a segment
  because a segment is narrow; nobody has looked at them beside the Essentials original they
  came from.

- **The pull request flow has never reached GitHub.** Signing in was already exercised only
  as far as the token; opening a fork, cutting a branch on it, committing a file and opening
  the pull request are four calls that have never been made from this app to a real account.
  `StringsDocumentTest` proves what goes *into* the file, and nothing proves what happens to
  it afterwards. The failure modes that were closed — a repository merely named `lessons`, a
  branch cut from a stale fork, the owner's own 422 — were reasoned about, not observed.
  **The first press should be the owner's**, because the first press is also the first test.
  The live gate of 10–11 October did not reach it.

- **The guide has never been fetched.** The parser, the two languages' parity and the choice
  between a stored and a bundled copy are tested; the four steps between the app and
  `raw.githubusercontent.com` are written and have never run. Nor has the bundled fallback
  been read at runtime — the assets are proven only by listing the built APK, because
  `:core:data`'s tests have no `Context`. The pager, the loader on the guide, the banner
  naming its version and the arrow that leaves it have been looked at by nobody: one of
  #186's screenshots caught the guide open, where the owner had left it on the shared
  emulator, and what it showed — or whether it had come from GitHub or from the APK — was
  not recorded.

**The most useful next action is to install the APK on a phone and live with it for one
school day.** After that the only questions left are about runtime and layout, and those are
invisible from anywhere except a real screen.

---

## 6. What you need to know so as not to break things

Beyond what is already in `CLAUDE.md`.

Fourteen subsections that stood here until 27 September 2026 are in
[docs/history.md](docs/history.md), «Moved out of section 6». Eleven retold, in older words,
rules `CLAUDE.md` carries — escaping what comes from outside, callback data, two screens on
one press, the translation guard, the order a constraint migrates in, the end of the school
year, a lesson and its bell (twice), a list and its keyboard, `mypy`, a renderer and its
type; one described `.claude/`, which `.claude/README.md` describes; and two narrated batches
that are done — the move into English and the documents' sweep of 19 September. What those
two left alone on purpose is kept below, under «Two things left in Russian on purpose».

### CI and building the APK

`android-actions/setup-android` is **pinned to `v4.0.1`** and is given
`packages: platform-tools` — in `ci.yml` and in `apk.yml`. That is not cosmetic: a floating
`@v4` asks by default for the `tools` package, which Google removed, and `sdkmanager` now
returns a non-zero code for it. Without the pin, every Android run fails before Gradle
starts. **Do not remove the pin and do not drop `packages` without checking that the action
has stopped asking for `tools`.**

**A new job in `ci.yml` goes into «CI»'s `needs`** (#424). «CI» is the one check `main`
requires, so a gate it does not need is required by nothing; `server/tests/test_ci_required.py`
fails until the list names every other job in the file. Do not require a gate by its own name
instead: «Host» is a matrix, and a skipped matrix is reported under its unexpanded name.

### The cache is split by class

`school_day` carries a `class_id`, and **every read in `TimetableDao` requires it as a
parameter** — not by default but mandatorily. That is not pedantry: two classes' windows lie
side by side on the phone, both weeks look like a plausible school week, and a query that
lost the filter will draw somebody else's timetable, which on screen is indistinguishable
from your own. The compiler is the only thing here capable of catching a missing filter,
which is why the parameter is mandatory.

A sync writes under the class the **server** matched to the token
(`timetable.schoolClass.id`) rather than the one the phone thinks is active: there is a
second between those two answers during a switch, and taking the local one would mean filing
one class's window under another's name. `replaceAll` moves the `classId` from the class's
row onto the day records for the same reason.

`lessons.db` (`LessonsDatabase`) is at Room schema version 5, and #140 added a second
database, `diary.db` (`DiaryDatabase`), at version 1. Neither has a migration and neither needs
one — both are disposable (`fallbackToDestructiveMigration`): the first sync fills the one and
the next diary read the other.

The memberships live in `Memberships.kt`, separately from `LessonsPreferences`: that is also
where the **old format** is read — one session in four flat keys — and where the
"introduction shown" flag comes from. The four keys are deleted on the first write of the
list. **Do not bring writing to them back**, and do not touch the fallback read path without
reading `MembershipsTest`: it is about installations already sitting on people's phones.

### Corrections over the diary

A correction's key (`target`) is **built by the server alone**, and the client hands it back
verbatim. Two implementations of a key that has to match byte for byte agree exactly until
the first lesson with no number. The format is in `services/diary_overrides.py`, and it is
semantic rather than positional: this project has already shipped positional keys once (the
calendar's UIDs), and deleting one element moved every later one onto somebody else's
subject.

A lesson's key is the day, the number **and** the subject, and either half alone is not
enough: the number alone collides when a class is split into groups (the diary numbers
lessons within a day and is not shy about giving two the same number), and the subject alone
collides on a doubled lesson. Together they are no guarantee either: one subject taught to
two groups in the same hour gives one key for two lessons, and what tells them apart is the
room and the teacher — that is, exactly the correctable fields. So a key that landed on two
lessons is **applied to neither**, and the client says so. Do not "simplify" it down to one
number.

**Marks and the turnstile cannot be corrected, and that is not an unfinished feature.** A
mark is a statement about what happened; an app that lets you rewrite one produces a forged
record that looks official. The set of fields is closed in `FIELDS_BY_KIND`.

**The bot does not apply corrections** — deliberately. The bot draws a day as one block of
text with no button on a lesson, so a correction there would be indistinguishable from the
school's own and there would be nothing to take it off with, and that is the surface a parent
reads more often. There is a comment about this in `app/bot/handlers/diary.py`; if the bot
ever gets buttons on a lesson, apply them **and** mark them, but do not apply them silently.

Corrections belong to the child — the diary's server and the pupil's id on it — not to the
diary's session, which dies every few days and would take them with it before anybody
pressed "reset", and not to the login, which nothing upstream vouches for (#165). A child
the diary lists outside its own numbering can have none. Over v2 a batch of them is written
all or none, and every correction is checked before the first is written; a number in a
target must be spelled as the server spells it (#393).

The diary's `422` means two different things: on a read, a bad date range; on a correction, a
refusal. The caller tells them apart (`DiaryFailure.of(failure, unprocessable)`), because
nothing in the answer does except a Russian sentence in `detail`.

**A row with no key must not be made pressable.** The server builds `target`, and a row that
arrived without one (a server older than `0009`, or a lesson the server could not enclose in
a key) cannot be matched to any correction. Pressable, it sends every press into a `422`.
`DiaryLesson.correctable` / `DiaryHomework.correctable` are that very question, and the "press
a lesson" hint is not printed when there is nothing on the week to press.

### Who lets a phone in

A class has a `join_mode`: `open` (the class code lets in anybody who types it) or `invite`
(the class code lets nobody in, and a phone joins with a personal one-time code from the
bot). Both are typed into the same field of the same `POST /api/v1/join` — for the app that
is one screen and one error — and they do not collide because **the lengths differ**: a class
code is eight characters, a personal one is ten. `services/device_invites.CODE_LENGTH` is
written about exactly this; if you change the length, check that it is still not equal to
`JOIN_CODE_LENGTH`.

**Switching takes nothing away.** Neither `invite` nor a return to `open` touches the tokens
of phones that are already connected. That is promised on three screens in a row, and
breaking it means dropping the timetable for a whole class at once, with not one line in the
logs.

**An enum is stored by name, not by value.** `SAEnum(JoinMode)` puts `OPEN` in the column,
although `JoinMode.OPEN.value == "open"`; the API hands out that `value`. A draft of revision
`0010` set `server_default="open"` — that would have sat on every class, and the first ORM
read would have failed with a `LookupError`, that is, for the bot in the middleware, that is,
the whole bot at once. Caught before it was applied; two tests in `test_join_modes.py` hold
both sides. **Ask the same question of any future enum with a `server_default`.**

**`is_public` is gone.** It sat on the class card, printed as «публичный / закрытый» — and was
read by no code path at all. The column stayed in Postgres (dropping a column is not
additive) and is not mapped in the model; there is a comment above that spot in `models.py`
asking that nothing be hung on it again. A second sign of "who is let in" will drift from the
first sooner or later.

**A personal code is burnt by a conditional `UPDATE` rather than by an assignment.** "One
code, one phone" is a statement about two requests rather than one: two requests that passed
`find_live` in the same instant would both write `used_at` and both get a token.
`device_invites.burn` returns whether it won, and `/join` burns the code **before** it issues
a token.

**The switch button carries the mode it wants, not "the other one".** A keyboard is a
message, messages stay in the chat, and an admin with two «👥 Доступ» pages open would
otherwise press a button still captioned "invitation only" and give the class code back to
everybody who still had it. Pressing the mode that is already in force says "it already is"
and writes nothing to the log. **Do not "simplify" it back into a toggle** — the `is_public`
that was removed had exactly that shape, and it was harmless only because nobody read the
flag.

**What the bot promises and what it does not.** A phone's role is bounded by the role of
whoever issued the code, and is checked on every request. The number of phones is bounded by
nothing: any member can press the button and forward codes, so "by invitation" replaces one
shared secret with a named person who decides each time, rather than with a smaller number of
readers. Every phone that arrives carries the account that let it in — in «📱 Устройства» and
in the log (`device.link`, written in `/join` rather than in the bot: that is where the
connection happens). This is said out loud in `phone_code`'s docstring, because the previous
version of that docstring claimed the opposite.

**Revoking a member kills their unissued codes** (`device_invites.drop_for`). A code is
checked only by its hash — `find_live` does not re-read `BotUser` — so without this, somebody
excluded in the last fifteen minutes would still let a phone in. Used rows are left alone:
they are the record that a phone has already joined.

### The second way in: a phone that signs in to its own diary

`docs/architecture.md` says where the password, the upstream session and the two bearers
live during the first run, and where they never do. These are the parts that break quietly:

- **On the phone's path the password goes to the diary and nowhere else.**
  `POST /api/v1/diary/session` refuses a body with a `password` key (`extra="forbid"`), and
  every credential value passes the one "safe to put in a header" check in
  `providers/diary/http.py`, because cookie values go straight into `Cookie:`. Do not add a
  field there that could carry a password. `/diary/login` and the bot's `/diary/signin`
  still take one, and say so to the person typing it.
- **The phone's diary hosts come from the bundled catalog and nowhere else.** `OriginGuard`
  refuses any other origin; the catalog is `server/app/catalog/data/regions.json`, bundled
  in place rather than copied, and its «Сетевой город» origins are the server's allow-list
  byte for byte. Change the allow-list or `docs/diaries/regions.md` and regenerate with
  `scripts/region_catalog.py` — `--check` and
  `test_the_committed_catalog_is_what_the_generator_writes` fail otherwise.
- **The two sign-in implementations are held together by one file.**
  `server/tests/vectors/diary_protocol.json` runs in the Python tests and against the Kotlin
  port; a protocol change is a change to the vectors first, then to both.
- **Nothing reads the diary in the background** — not the sync worker, not the widget.
  `SyncWorkerSourceTest` scans them and every application, receiver and service the manifests
  declare. A background read would count as the family's activity and hold a «Сетевой город»
  session open for the keep-alive's thirty days with nobody behind it (#142).
- **Periodic class sync is armed only in class mode**, from the shell mode's arming in
  `LessonsApplication` rather than on every settings emission (#152).
- **The directory's share of DaData is its own.** `/api/v1/directory/school-regions` is
  charged to `usage_counters` under a cap of 4,000 a day; the bot's and `/manage/schools`'
  searches are never charged to it. Putting them on one counter is how onboarding would
  spend the search the bot's create-class step depends on.
- **The legal address is per fork.** `LESSONS_LEGAL_BASE_URL` compiles into
  `BuildConfig.LEGAL_BASE_URL`, and the build refuses a value that is not https; the texts
  carry `FORK:` comments naming what a fork rewrites, and `docs/build.md` says the rest.
- **CI runs a job when a file its tests read changes**, and `ci.yml`'s path filter names
  those files; a test that starts reading a document outside its half adds the document
  there (#159).

### The correction mode knows about all the text, and two imports hold it

The mode's coverage is not a list of places wrapped by hand. `:app` and `:core:designsystem`
have **one** `Text`, and it is their own: `core/designsystem/text/Text.kt` — Material's plus
`modifier.correctable(text)`. Strings are read through `correctedString` rather than through
`stringResource`: that both substitutes the correction and remembers which resource drew
these words, for as long as they are on the screen. A long press asks the registry rather
than `values/` — a key cannot be looked up by text, since 204 strings of 991 match another's
text, and another 100 are patterns with arguments.

Both original imports are still on the classpath and still compile, so a new screen written
out of habit would simply be a page the proofreader cannot touch, and nothing would say so.
`CorrectionReachTest` holds this: it reads every module's sources and fails on
`import androidx.compose.material3.Text` and on `import androidx.compose.ui.res.stringResource`.
There are three exceptions (`Corrections.kt` itself, the editor and the corrections sheet),
each named by path, and a separate test checks that an exception did not outlive its file.

`CorrectableTextTest` compares the shim's signature against Material's by reflection: an
undeclared parameter would silently stop working on every screen at once, while the call
still resolves. The signature grows — `autoSize` arrived in 1.4 — so checking it by eye once
would have been pointless.

And one line without which all of this quietly ceases to exist: `MainActivity` wraps the app
in `CorrectionHost`. Without it everything builds and draws, and the default implementation
does nothing. That is in a test too.

### The guide is fetched, and a debug-signed APK is not an update of another one

Two things about builds and documents that cost an afternoon each:

* **Every debug-signed build carries a different certificate.** `~/.android/debug.keystore`
  is generated on the machine that builds, the first time it is needed — constant alias,
  constant passwords, a fresh key pair. A CI runner is a fresh machine and the Gradle cache
  covers `~/.gradle`, not `~/.android`, so one APK run's build never updates another's, and
  Android says only «Приложение не установлено». `versionName` is never compared and a higher
  `versionCode` does not help. The cures are an uninstall, which takes the device's classes,
  token, diary session and corrections, or the four keystore secrets. `docs/build.md` has the
  long version.
* **The in-app guide is markdown in `docs/app/`, not `values/`.** `docs/app/` is
  `:core:data`'s asset folder rather than a copy of it, so a change to the guide is one edit
  in one place: the app fetches those same files from the repository and falls back to the
  ones in its APK. The parser understands `##`, `-`, `1.` with a bold lead, `>` and three
  inline marks, and **never throws** — anything that is not a guide parses to no pages and
  the stored copy stands. Correction mode cannot reach that text any more, which is the cost;
  `DocsGuideParityTest` reads the shipped files and holds the two languages level, the job
  `ResourceTranslationTest` does for everything still in `values/`.

### A correction leaves the phone as somebody's pull request, and the fork is not its name

The sheet that collects corrections can now open a pull request against this repository, and
it opens it from the reader's own account — the `public_repo` scope the sign-in already asks
for is enough for a fork and a pull request both. Four things about that path are easy to get
wrong and are written into the code rather than left to memory:

* **A fork is identified by its parent, never by its name.** `lessons` is not a rare word.
  Asking `/repos/{login}/lessons` and reading a 200 as "the fork is there" is how this app
  would cut a branch in a stranger's unrelated repository and commit to it. The check reads
  the body and requires `fork` with the parent's `full_name` equal to this project.
* **The branch is cut from the upstream commit, not from the fork's head.** GitHub allows a
  ref at any commit in the parent network, and a fork made once and never synced would offer
  everything merged since back as reverts.
* **The owner cannot fork their own repository** — GitHub answers 422 — so for them the
  branch goes straight to the upstream repository. That is the case the first press will hit,
  and it is the owner's press that should be first.
* **`POST /forks` answers 202,** not 201: the fork is created afterwards, so it is polled
  for. And GitHub wraps base64 contents at 60 characters, so the decode must ignore line
  breaks (`Base64.DEFAULT`) while the encode must not add them (`Base64.NO_WRAP`).

`StringsDocument` is the part of this with tests: it replaces one element's body and leaves
every other byte alone, refuses a key the file does not declare rather than appending it, and
does not confuse `settings_title` with `settings_titles_plural` or with a `<string-array>` of
the same name. Everything past that — fork, branch, commit, pull request — is written and has
never run.

### Idempotent is not the same as safe under a race

`/api/v1/bundle` is a read, and it writes: it seeds the terms and the subject dictionary for
a new class. Every phone in the class polls it on one timer, so "both found the year
unseeded" is the ordinary case rather than a rare one. Both insert index 1, `uq_term_slot`
refuses the second, and on Postgres an `IntegrityError` poisons the whole transaction — a 500
on a read, on exactly the first request the seeding exists for.

The shape this project already knows: the insert inside `session.begin_nested()`, and **the
loser yields** — rolls its rows back and returns the other's (`terms.ensure`,
`subjects._adopt`). The only one that must not yield is an explicit change of scheme:
quietly accepting quarters means answering "done" to an admin who asked for half-years.

The same rule about the sign-in ticket: `diary_link.claim` and `device_invites.burn` are both
one conditional `UPDATE`, because "one link, one sign-in" is a promise about **two** requests
rather than one.

**And separately: do not commit by a counter.** `sync_from_timetable` returns how many
dictionary entries were *created*, but it also links the timetable's rows.
`GET /api/v1/manage/subjects` committed `if ...:` — and for a class with a full dictionary and
unlinked lessons the work was done and thrown away on every read.

### A slot is one "every week" row or two halves

`uq_timetable_cell` is `(class, day, number, parity)`, and "every week" next to "numerator"
does not violate that key. Both rows pass the parity filter on an odd week, and `_load`
selects the template **with no `ORDER BY`** and keeps the last — so which subject the phone
sees is decided by the database, and the answer can differ between two reads. Three places
hold the rule, and all three are needed: `timetable_io._conflicts` on a paste, the editor's
handler, and — as of this commit — `timetable_edit.edit_lesson`, that is, the one entrance of
the second shell.

### The intercepting layer and the compose-bom

For two releases `OverlayLayerTest` asserted that a layer swallowing gestures to keep a touch
from reaching the pager beneath it cancels presses on its own rows with the same movement —
and that there is no fix. With **compose-bom 2026.09.00** that stopped being true: the same
layer over the same rows lets presses through. Narrowed to the bom itself (with navigation
2.10.1 and room 2.8.5, rolling back the bom alone restores the old behaviour). The test was
rewritten for the new order.

**We are not going back to the layer, though**, and that is the main thing to take away here:
the delivery order between two subtrees is promised nowhere, it moved under a dependency bump
silently, and a finger on a row would just as silently stop working again — and that bug has
already shipped twice. The tabs are still removed from the composition rather than covered.

### The dependency mirror

The root `requirements.txt` is a **lock** since #192: exact pins for the whole tree,
compiled by uv for CPython 3.12 on Linux from the root `requirements.in`, whose header names
the command. `requirements.in` and `server/pyproject.toml` have to carry the same floors.
Dependabot edits **the root files only** — which is how `main` once drifted
(`sqlalchemy>=2.0.52` against `>=2.0.30`, `pydantic-settings>=2.15.0` against `>=2.4`) — and
its `uv` entry raises the floor in `requirements.in` and recompiles the lock, so pyproject is
still raised by hand. Tests hold this rather than attentiveness:
`tests/test_requirements_mirror.py` checks the input against pyproject in both directions,
that every lock line is one pin inside its floor, that the lock was regenerated after the
input changed, and that it is closed on Linux/3.12; the three packages deliberately absent
from the bundle (`uvicorn`, `aiosqlite`, `alembic`) are listed there with their reasons.

### Week parity

`week_parity` counts weeks **from the start of the school year** rather than by the ISO
number. The first week of the year deliberately keeps the parity the old rule would have
given it: in 2024/25, 2025/26, 2027/28 and 2031/32 both rules agree day for day, and they
differ only inside the two years where the old one went wrong. **If you touch this place, do
not "simplify" it back to `isocalendar().week`.**

### Deleting a subject

A subject that stands in the timetable can no longer **be deleted** — the API answers `409`
and the bot shows how many lessons use it. That is a change of contract rather than a bug:
the dictionary fills itself from the timetable, so a deleted name came back on the very next
read with no colour and no teacher. The order is: take the lessons out of the timetable
first, then the subject out of the dictionary.

### The timetable paste grammar

The last field takes the rest of the line, so a teacher «Иванов И.И., к.п.н.» is written with
no syntax. A subject or a room containing a comma is wrapped in double quotes, and a `""`
inside is one literal quote. Quotes are used only where they are needed, so any line already
written parses as it did before.

### Two things left in Russian on purpose

* **The commit history keeps its Russian, by decision.** Of 242 commits, 41 carry Russian
  prose outside quoted product strings — about 2,055 characters, mostly in merge-commit
  bodies. Rewriting them means rewriting every hash from the first affected commit and force
  pushing `main` and `dev`: every existing clone breaks, the merge references in the pull
  requests stop resolving, and the release tags move. The owner decided not to. So a `git log`
  older than #51's move into English reads in two languages, and that is expected rather
  than missed.

**One thing the documents' sweep of 19 September 2026 found and did not touch.** Five test
functions on the server carry a Russian
word in their names — `test_a_day_with_one_maximum_length_задание_still_sends` and four like
it, in `test_bot_message_limits.py` and `test_bot_manage.py`. The rule says identifiers are
English; it also says a quotation of what the user sees keeps its Russian, and an identifier
has nowhere to put guillemets, so these sit between the two halves of it. Renaming them is a
code change and would have to pass the gates, which a documentation sweep does not run — so
it is written down here rather than done quietly. Nothing else in `server/` or `android/`
has a Cyrillic identifier: Kotlin has none at all.

## 7. Left to the owner

All of this is beyond an agent's reach: it needs a phone, a key or a live service.

**These are issues now**, so that they can be closed rather than re-read: #118 the Preview
environment (closed by #350), #120 the external cron and `DADATA_TOKEN`, #121 the real diary, #122 the
widget's tick cadence and the diary credential's bound, #135 the second diary, #144 a default
server for a fresh install (decided on 11 October 2026: none, and closed). Each carries the
label `needs:owner`. #119 (`/api/v1/warmup` and
`/start`) and #156 (the backup) were answered by #186 and closed at its merge on 26 September
2026.

**The release signing passwords stay as they are: rotating them is not important, by your
decision of 11 October 2026.** On 5 October 2026 an agent of the session that wrote #311 read
`~/.gradle/gradle.properties` and printed a signing password into that session's transcript.
It is in no file, issue, commit or pull request. If they are ever changed, `keytool` changes a
keystore's passwords without changing its key, so a phone that installed the APK still accepts
the next one; the new values then go into that file and into the Actions secrets —
`KEYSTORE_BASE64` as well, because the keystore file changes with its password.

**The release keystore is one key in two places, and a new password goes into both.** The
local release build and the APK workflow were both signed `CN=lumenpearson` on 2 October
(`docs/history.md`, «Moved out of section 7 on 2 October 2026»), so the Actions secrets hold
the same keystore as `~/.gradle/gradle.properties` names. On 5 October a session asked to do
the rotation was refused by Claude Code's own safety classifier («secret-store writes»), so a
rotation, if one is ever wanted, is yours.

**Make #330's rule a rule (optional).** `apk.yml` signs with the real key only on `main` or a
`v*` tag, but the guard binds the workflow as committed on the ref being run, and the four
secrets are repository secrets any branch's workflow can read. Moving them into a GitHub
Environment open only to `main` and `v*` tags, with a ruleset on `v*` tags, would make it
hold against somebody with push access too (`docs/build.md`, «A public repository»).

**Give the Vercel connector the project's scope again (optional).** On 5 October its token
answered `403` for the team «codeilluminators» the project lives under, so a session could
read neither the runtime logs (whether the external cron calls the tick, #120) nor the
environment's names.

**`.claude/settings.json` has the two `pytest` allow lines, and no `deny` for the passwords'
file, by your answer of 11 October 2026** (#314, #318). You added `Bash(pytest *)` and
`Bash(pytest)` under `allow`, so the bare command CI runs no longer asks each time; they are
in the working tree the session ran from and not yet on `main`, and a session may not commit
its own permissions, so committing them is yours. You chose not to add
`Read(~/.gradle/gradle.properties)` under `deny`: the instructions alone guard that file, in
`CLAUDE.md` and in what every agent is told, as they always guarded a shell `cat` of it.

A third line, narrowing `Read(./server/.env.*)` under `deny` so it no longer caught
`server/.env.example`, was your own edit during 3c, committed as `261450e` on `server-v2/3c` on
10 October 2026.

**Add a `BUF_TOKEN` repository secret if CI's «Contract (Buf)» job is ever throttled.** The
job reads no secret today. If its log says Buf refused an unauthenticated request (429,
resource exhausted), create a token at buf.build and pass it to the job as `BUF_TOKEN`;
`docs/build.md`, «The v2 contract and Buf», says where. Nothing is needed until then.

**Optional: give the Postman collection a device token.** The second folder of «lessons — API
smoke (read-only)», «With a device token», skips itself until the environment «lessons —
production» has a **current value** for `deviceToken`; type one there from a phone joined to
a class, and leave the initial value empty so that it stays on that machine. The collection has
never been run in Postman, so the first run is also its first test.

**The launcher icons were chosen (#388): done on 10 October 2026.** The owner kept «Классика» and
«AMOLED», eight palettes each, and the other six styles were taken out: forty-eight catalog lines,
aliases and 192 resources. `android/logo/export_android.py` now writes only the kept styles. A
phone that had chosen a removed icon is moved back to «Классика · Мята» by the reconcile on the
update. That has not yet been seen on a device.

**Walk the launcher icon once on a device or an emulator.** An API 31 and an API 34 emulator
are enough: switch the icon twice, press Home, open Recents, then tap the widget from a cold
start. Section 5 has the list it starts on.

**Look at #398 and #399 on a device.** Both merged from 3c's session without a close-out of
their own — the plate-free launcher icon and the pull-to-refresh loader laid out from under
the status bar — and nobody has seen either drawn.

**Look at the geometry pass (#404), the tray (#415) and «Календарь»'s header (#417) on a
phone.** The emulator was walked on 10–11 October and its shots were sent to you; what is left
is a phone, in light and dark: «Сегодня», «Календарь» (week, month and agenda, and its header at
a large font), «Задания», the diary, «Оформление», «Значок приложения», «О приложении» and the
first run. Section 5 has what is likeliest to look wrong. If the stretched weekday tiles or the
round small FAB read wrong, each goes back in one place: `WeekdayTileGap` and the fit check in
`CalendarGrids.kt`, and the FAB's `shape` in `DayRibbonView.kt`.

**Next for the programme: sub-project 4, then #407.** Sub-project 3 is complete (#396), its
live-verification gate ran on the night of 10–11 October, and #406 (#422) and #428 («What
the last session added») followed it. Sub-project 4, the Android decomposition, is in
progress on `android/decomposition-a`, pull request A of three, with no pull request yet, per
`docs/specs/2026-10-05-android-decomposition-design.md`; #407, the host's recipe on a Russian
VPS, comes after it.

**#406 needed nothing set, and production reads it back.** `DIARY_EGRESS` is best left unset
in Production and Preview alike: on `6354c3d`, `GetDiaryCapabilities` answered that Petersburg
and eleven «Сетевой город» regions go through RUVDS and five direct. «📊 Проект», read at
17:02 Moscow on 11 October, had all four checks ✅, the proxy answering in 450 ms, and no
region line, because no region is in use. When one is, a region that reads 🔴 says on its
second line what to do; one that leaves use while failing simply disappears, which is not a
recovery (`docs/deploy.md`, «Monitoring»).

**The privacy policy is at edition 4 with #428** (#425), which names Sentry beside the
intermediary server at RUVDS that edition 3 added (#422). `docs/legal/legal.json` says it takes
effect on 2026-10-11; if the merge lands on another day, that date is changed to the merge's
before it. **Edition 5 is a draft, #430**: your brief for a lawyer, written on 11 October 2026
and kept outside the repository, became a rewrite of both texts by another session, opened at
14:53 UTC on milestone 12, which closes #429 (what 152-ФЗ asks an operator to publish, the
eighty-nine regions' diary systems, and when the proxy is used). Your question after #422,
whether «если из страны, где работает сервер, дневник не отвечает» should be reworded, is being
answered there. The order is #428 first, then #430 rebased onto edition 4, keeping the Sentry
point. The RUVDS point says the proxy may log the diary's address and the time, which is true
while RUVDS's `squid.conf` keeps `access_log` on (line 27, read at 09:06 UTC on 11 October);
switching it off makes the clause «и может записать их в свой журнал», and its English twin,
worth taking out with another edition.

**The development machine's RAM is still faulty.** It crashed twice on 11 October — at 11:05
Moscow with a double fault (`0x7F`), and at 12:49 with `0x1A` `0x41792`, the faulty-RAM
signature, which zero-filled this worktree's git index in the middle of a commit (recovered;
«After #418's merge» has how). Your fix is to pull the unbranded DIMM pair and run a memtest
on what remains. Until then a session runs one heavy job at a time, and scans for zero-filled
files after a crash.

What the gate could not do is yours: the real diary sign-ins (#121, #343) and a DaData query
(#120), as before, and now one error sent to Sentry on purpose. Each has a paragraph below.

Where the host lives was decided by #410: Selectel or RUVDS, for the scenarios in which the
core runs on a VPS (`docs/specs/2026-10-10-deployment-scenarios-design.md`).

**Send one error to Sentry on purpose, with `SENTRY_DSN` set.** A transaction has reached
Sentry from production, and no error event has been seen there: the errors dataset held none in
the thirty days to 10 October, which a server without errors and a transport that never
delivers one would both produce. The DSN is a sensitive Vercel variable that cannot be read back, so no session can
send one from here. One deliberate error on Preview or production, wherever the DSN is set, and
a look at Sentry settles which it is.

**«CI» becomes the one required check on `main`** (#424, #428), in place of
«Host (pyvoy)» and «Host (hypercorn)» by name, which would have left every pull request that
changes only `android/` waiting for ever: a skipped matrix is reported under its unexpanded
name (`docs/build.md`, «The check `main` requires»). The job needs every gate and passes only
when each succeeded or was skipped. The ruleset that requires it, with the repository admin
role able to bypass, is created by the session once #428 merges.

**Measure one `GetScheduleWindow` on the host's single thread, and decide #400's keep-alive
before any host deployment.** pyvoy runs the app on one thread; nobody has timed a call under
load. #400 (a vanished phone's stream held for about fifteen minutes) is Backlog, and its
fix, if wanted, is cheaper to make before a real deployment than after one.

**Docker Desktop, grpcurl and an emulator are now on the owner's machine**, and the
worktree's `server/.venv` holds the host packages (pyvoy, envoy-server, hypercorn, grpcio)
again — a session building or checking the host target locally does not need to install
them first.

**Changing an event's kind carries «covers the lesson» along** (#423, #428), by
the session's call of 11 October 2026, which you left to it: `covers_lesson` follows the kind
for as long as nobody has set it apart, so a trip turned into a canteen break stops replacing
the lessons, as one created a break would, and an event somebody set apart keeps what they
set. If a class wants it otherwise, the rule is one branch in `services/events.update`.

**Set `MIN_CLIENT_VERSION` only after sub-project 5's APK is on the family's phones, and never
above the version they run.** #342 adds the setting, optional and empty. Set, it refuses a v2
request whose `X-Lessons-Client` is below it with `CLIENT_TOO_OLD` (`docs/api.md`), and
`docs/deploy.md` gives the same advice beside the setting. A request without the header is
never refused, and no APK sends one yet.

**The tenth milestone exists, and #140 is on it.** The owner created
`v0.9.0 — NetSchool e-diary, onboarding via the school's diary` on 25 September and renamed
the other nine the same day. Its description is behind: it names issues #145–#159, and
#160–#165 are on it too, as are the external audit's #190–#211 of 27 September. The ninth's
is further behind — it names PRs #129, #133 and #134 only. A milestone's description is the
owner's to edit; nothing in a session here can.

**Give the APK a GitHub client id, or the developer mode stays shut.** The mode (#237) opens
through «Войти через GitHub», which a build without `LESSONS_GITHUB_CLIENT_ID` hides
(`docs/build.md`). Then, on a phone: seven taps on the version, sign in as an account with
push to this repository, and run the checks once from a phone's own network — the first
answer to whether Petersburg's host answers a Russian mobile network at all.

**The Petersburg account needs a second factor (#343).** Its password is right, and the diary
asks for a code by SMS or MAX, which the app has no step for. Change the password once #343 is
decided.

**Sign in once, for real, from a phone — it is the one question #140 cannot answer about
itself.** Build an APK from `main`, which has carried #140 since 26 September, install it on
a phone in no class, and take the diary path twice: a «Сетевой город» region that takes a
password, and Петербург. Whether the import arrives, or the phone says the diary refused the
session from our server's address — the `409` — decides whether the phone-registered path
works for that diary at all. It is #121's first live session, for both diaries at once. For
Петербург the server reaches the diary through the proxy since 5 October, so the answer is no
longer «Дневник не отвечает» before the phone is picked up; try the account on the diary's
site first (above).

**Then walk the rest of the first run on that phone.** The class code as the other way in;
leaving the last class with a diary signed in, which should land on the diary as the home
with «Выйти из дневника» still in settings (#151's path). The legal links, the ТОР hand-off
and the backup were seen on an emulator in #186; a phone adds the thumb, not the answer.

**#144 is decided: a fresh install gets no default server**, by your answer of 11 October
2026. A server is chosen when the app is built, through the build console (sub-project 6);
until then a fresh install asks for the address where it is first needed, as it has since
#154.

**Have the terms of use and the privacy policy read before anybody relies on them.** They
describe what the code does and claim compliance with nothing: whether a GitHub account named
as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line and parental
consent are questions for a lawyer, and since edition 3 so is the intermediary server at RUVDS
that the diaries' requests pass through, and since edition 4 Sentry, whose data is kept in
Germany. Each passage about this deployment sits under a `FORK:` comment.
`docs/legal/legal.json` is edition 4 with #428 (#425), and draft #430 (#429), written from
your brief for a lawyer, makes it 5; every change to what the texts say raises it, as the
`release` skill says.

**`DADATA_TOKEN` now serves a second caller (#120).** The anonymous school directory spends at
most 4,000 of DaData's 10,000 daily requests. Without the key it answers `503` with
`X-Directory-Unavailable: disabled`, and a family still finds its region in the bundled
catalog — only the search by a school's name is gone.

**The external cron is connected** (cron-job.org, 5 October). Read its run history once: `200`
with JSON. `403` means its `X-Cron-Secret` is not Vercel's `CRON_SECRET`. Switch on its failure
email, after three failures in a row (the owner's choice), and its email when the job succeeds
again: it is the one alarm for a server that is down entirely (`docs/deploy.md`, «The external
cron»).

**A fine-grained `GITHUB_READ_TOKEN` expires.** When `/health` shows the `deploy` check ❔ with
«the token was refused», make a new read-only token and set it in Vercel
(`docs/deploy.md`, «Monitoring: what tells the owner something is wrong»).

**Keep the RUVDS proxy until the phone reads the diary itself (#365).** A second host, a
Selectel VDS, could not reach the diary: Selectel blocks the e-government's subnets from its
VDS servers and will not unblock them, and the owner deleted it on 8 October («After #372's
merge», in the section on #376). By the owner's order the diary's reads move onto the phone,
for both diaries, after sub-project 3. Until then production reads the Petersburg diary
through RUVDS, and since #406 the twelve «Сетевой город» regions that answer from it too, and
the self-check reports its drops and each region's. Nothing needs doing now; cancelling RUVDS
waits for the phone, and would now take those regions' route with it.

**#135 now has an answer in the code to each of its three questions, and closing it is the
owner's.** `docs/diaries.md` is the map:

- **Which platform** — «Сетевой город», built on the server in #140's first part: one route
  set, the largest group of regions, and a password still accepted outside the regions that
  allow Госуслуги only. The МЭШ family comes after it. Against a live server it has not been
  read.
- **How a family signs in** — with its password, typed into the app's own form and sent to
  the diary alone; the session, not the password, reaches our server. Госуслуги is not driven
  and no token is carried over from a browser, because a Госуслуги session is the person's
  whole state-services account. The three Госуслуги-only «Сетевой город» regions hand off to
  their own site.
- **Whether ТОР «Моя школа» may be used** — it is not read. The provider screen names it as
  the region's system and hands off to the official site. Nobody has read Госуслуги's terms,
  and nothing needs them read until somebody proposes reading ТОР.

Whichever platform comes next, the first step is one real session against it, as #121 is for
Петербург. Not one route in `docs/diaries/` has been seen answering.

**Install one built after #186 and long-press a tab on the home screen.** Since #181 the
long press picks the tab up in the same touch, and a tap anywhere closes the mode (#182).
#186 saw the gesture work on an emulator, but not by a hand — and the emulator was shared
with the owner at the time, so the drop was never watched undisturbed. The APK on #84's
merge cannot rearrange anything — the drag reported the order unchanged — so it is the wrong
build to judge the feature by. #84 and #85 together are the whole of a gesture and nothing in
a session here could see any of it: whether the wobble reads as «иконки на
iOS» or as a fault, whether a tab can actually be dragged where the finger means it to go,
whether the haptic lands at the moment the mode opens, and — the one that matters most —
whether the reader really does stay on the tab they were looking at rather than on the slot
it used to occupy. Then leave the app, come back, and check the order survived. A back press
should leave the arranging mode rather than the app.

**The widget wants resizing twice *and* the system font turned up, and that is the whole
check.** #82 makes every block inside it round itself to the padding of its size class, and
the twelve rungs differ by a factor of two, so that defect is invisible at one size and
obvious at another. #83 adds the second axis for the same reason: the ladder measures in dp
and its type sizes are in sp, so at «Настройки → Экран → Размер шрифта» turned to its
largest the old build chose a rung for about twice the content it could draw — the state
word came out «ПЕРЕ…» and the bottom rows ran off the edge. Drop the widget small, look at
the corners and at the seven day chips; drag it to four cells wide and tall and look again;
then turn the font up and repeat both. Nothing in the suite can do any of it, and the corner
half needs Android 31 or later to exist at all.

**Since 5 October Preview has its own variables** (#118, closed by #350). Its database is the
Neon branch `preview`, it has a second bot, and it has no `CRON_SECRET`. Every revision goes to
`preview` when its pull request is pushed, then to production before the merge. Previews write
to a copy of the real data, `RUN_BOT` must stay `false` there, and the webhook is safe only
because it is registered by hand (`docs/deploy.md` has the list).

**The APK's own badges are new in #80 and they are worth one press.** The about page now
names the server's state, the repository, the ref and the commit the build came from. Two
of those cannot be checked from here at all: whether the server badge says the right thing
about the real deployment, and whether the commit chip opens the right diff on a phone.
Both are a few seconds on the page that already exists.

**The hour ruler is gone.** «Календарь → День» is the ribbon now, and a reader who preferred
reading the day by position — a gap as a height rather than as a row — has lost that. It was
a deliberate replacement rather than an addition, because two views of one day is how the
bot's two timetable editors nearly drifted apart; if it turns out to be missed, the ruler is
in the history at `f5a8172^`.

**The first press of two network paths should be the owner's.** Neither the translation
pull request from #64 nor the guide's fetch from #67 has ever run against GitHub, and both
are written to be pressed by a reader. Opening the documentation once on a real phone, with
and without a network, checks the second of them in about a minute — and the first press of
«Отправить как pull request» checks the first.

**Two more are decisions rather than actions**, both from the second audit, both
deliberately not taken by the session that found them because they trade one real cost
against another and the trade is the owner's to make.

**A. The widget's tick cadence.** Every rung above 2×1 draws its countdown with a
`Chronometer` that ticks in the launcher's own process and is always exact; the 2×1 alone
draws a frozen string, so the whole refresh cadence buys freshness for that one size.
`BeforeSchool` is entered at midnight and held until the first bell, which costs about
**34 device-waking alarms a school night** — against zero for the symmetric `AfterSchool`,
which is documented as costing nothing precisely because it has no countdown. Two ways out,
and they trade against each other:

  * cap the look-ahead (treat "more than an hour away" as nothing to count, wake on the
    bell): 34 alarms → 1, and the 2×1 reads «8 ч 30 мин» all night until the bell nears,
    which is arguably the honest answer at 3 a.m.;
  * round the printed figure per tier («~8 ч» above the hour, so a quarter-hour of drift
    really is inside the rounding — which the code comment already claims and the
    `%d ч %d мин` format does not do): keeps all 34 wake-ups, costs the 2×1 its minutes.

  The tier table was left untouched either way. What *was* fixed is the disagreement between
  the colour and the figure: they rounded opposite ways, so «6 мин» was drawn in the error
  colour under a rule documented as the last five minutes.

**B. Whether the diary credential should carry a bound, and which.** A Fernet `ttl` is the
obvious answer and is probably the wrong one: the clock would run from when the *upstream*
last rotated its cookie, not from when the family last read their diary, because
`services/diary` re-seals only when the token comes back different. On the calls where it
does not rotate, a ttl would refuse a credential the upstream would still have accepted —
and since `unseal` returns `None` for both a rotated key and an expired blob, `find_session`
marks the row dead and sends the person back to a sign-in form needing a fresh ticket from
the bot. That is a real, user-visible expiry bought against a narrow threat, since the blob
is worthless to anyone holding the dump but not `DIARY_SECRET`. If a bound is wanted, the
cheaper one that cannot misfire is an age check on `last_used_at` in `find_session`: our own
clock over our own facts. **Today there is no bound at all except the row's own lifecycle**,
and that is the thing to decide rather than the ttl.

Items 1, 1a and 1b of this list are done — the last of them, reading `/api/v1/warmup` and
sending the bot `/start`, on 26 September 2026 — and so is item 5, the correction mode's walk,
which #186 made on an emulator (#115). All four are in [docs/history.md](docs/history.md),
«Moved out of section 7», with four paragraphs this section no longer needs.

2. **Build the APK from `main` and install it on a phone.** See section 5 — it is the only
   way to check what nothing currently checks, and doubly so after three build-chain bumps.
   Three things have been added to this: connect the phone to two classes and walk between
   them, looking at the widget after a switch; if a phone with the previous version is to
   hand, upgrade it in place and make sure the class is still there and the introduction is
   not shown again; and sign into a real diary, press a lesson, correct the room and reset
   it. And a fourth: put a class into "invitation only", make sure a connected phone goes on
   working, take a personal code with the «📱 Подключить телефон» button and connect a second
   phone with it. #186 did the first and the second on an emulator (#116, #117), and the
   fourth on a class that was invite-only already, so the switch itself is still unflipped
   (section 5). On a phone those are a thumb's check rather than an open question; the diary
   is still nobody's (#121).
3. **Make sure `DADATA_TOKEN` really works** on Vercel (Production; Preview has none, by design;
   it is the API key, not the secret one).
4. **Open «Настройки → О приложении → Лицензии» in the app** and look at the eighth row —
   Google Sans Flex. It is built and it compiles, but nobody has seen it with their eyes:
   what is of interest is how an eighth shade sits in a palette of six.

---

## 8. If you are starting new work

The order that paid off here:

1. Read `CLAUDE.md` in full, then `docs/architecture.md`. If you are an agent, look at
   whether `.claude/agents/` has a file for your area and `.claude/skills/` a procedure for
   the task: those record the traps that have already sprung in that area.
2. Read a file before editing it and **grep every caller** before changing a function. The
   audits in `docs/design.md` exist because a conclusion drawn from call sites turned out to
   be wrong.
3. Audits by reading subagents, split by area, work well and find real things: sixteen
   findings over four runs, several of them in code written an hour earlier in the same
   session. Give them a narrow area, the project's rules and a requirement for a concrete
   failure scenario, and make the edits yourself.
4. Do not commit without green gates on both halves.
5. Write "what is not covered" into the commit body and into the honest status in
   `README.md`. "Written, never run" is a legitimate status; a claim that something was
   verified when it was not is not.
6. Re-read your own diff before you push it, asking what would make it wrong rather than
   whether it looks right. Both defects in the pull request flow were found that way, in
   code written an hour earlier in the same session, and neither would have failed a test —
   there was no test that could have run.

### How to continue

`dev` remains the working branch, but after a merge it is restarted from `main`: a merged
pull request accepts no new commits. Which pull requests are merged and which one is open
is at the top of this file, not here — this paragraph is about the two commands under it.

```bash
git fetch origin
git checkout -B dev origin/main   # the same dev, a new starting point
```

The gates, both halves (`CLAUDE.md` requires running both if you touched both):

```bash
cd server  && ruff check app tests scripts migrations   # clean
cd server  && pytest -q -n auto                          # 3332 tests, ~12 min alone on Windows
cd server  && python -m mypy                             # clean, 240 modules
cd android && ./gradlew test                             # 1730 tests across the five modules
cd android && ./gradlew detekt                           # nothing beyond the five baselines
cd android && ./gradlew assembleDebug assembleRelease    # both assembles
```
