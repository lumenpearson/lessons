# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.
What every batch before the last two added is in [docs/history.md](docs/history.md),
newest first.

Last updated: **18 October 2026**. **PRs #63 through #85, #128, #129, #133, #134, #140,
#166, #186, #187, #189, #214, #218, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261,
#263, #267, #274, #277, #294, #296, #297, #300, #301, #303, #305, #306, #307, #308, #311,
#313, #319, #328, #329, #332, #333, #335, #342, #345, #346, #350, #356, #359, #363, #366, #372,
#376, #379, #380, #385, #388, #392, #394, #396, #398, #399, #403, #410, #413, #415, #417, #418,
#422, #428, #430, #446–#452, #472, #473, #539, #609, #610, #622, #625, #650, #651, #653 and
#655 are merged**, and #344's commit went in with #366 although GitHub marks it closed rather
than merged; `main` is at `ee1dce0`, the merge of #655 at 02:46:11 UTC on 18 October 2026: a
write whose row another writer deleted mid-write answers «not found» instead of a server error.
Before it, on the night of 17–18 October: #625, sub-project 4's pull request B and the close-out
that rode in it, merged at 22:41:34 UTC on 17 October as `81a6201`, pinned to head `2ba842e`, the
SHA that close-out left for this one to write; #650, an approval that says the role the member
holds after it, at 23:09:48 UTC as `94d95f8`; #651, «📱 Устройства» drawing a button for every
phone it lists, at 00:48:40 UTC on 18 October as `472ae6d`; and #653, every secret or personal
v2 answer kept out of caches on every transport, at 01:50:26 UTC as `112982e`. All five went in
with CI green on the exact head they were pinned to, on milestone 11, with their board items set
to Done and their remote branches deleted. Earlier, on 17 October: #622 merged at 18:54:47 UTC
as `1cc30fe`, #473 at 10:33:03 UTC as `a5fb054`, #539 at 12:05:22 UTC as `79af3d0`, #609 at
12:15:53 UTC as `8abf45f` and #610 at 12:16:03 UTC as `b167b73`; on 16 October #451 merged as
`8564b09`, #452 as `2d6fb4f` and #472 as `a1135d5`, and #446–#450, dependabot's five, went in
through #452's branch, which GitHub closed as merged by itself.
**Sub-project 3 is complete** with #396 (`80d1281`), **and its live-verification gate has
run**, as far as a session can take it: the condition the owner set on 8 October before
sub-project 4, checked on the development machine on the night of 10–11 October. **The four
designs of sub-projects 3 to 6 are approved and on `main`**: the owner answered every question
with its recommendation on 5 October (#301, #306, #307, #308), and #410 added a seventh
sub-project and six deployment scenarios to the programme. **Sub-project 4's pull requests A
(#451) and B (#625) are in `main`, and of this batch's work the only thing open is this file's
own pull request**, from `server/host-keepalive-400` to `main`, `Refs #273`: the host's Envoy
pinging a quiet HTTP/2 connection, so that a vanished phone's `WatchClass` stream is found in
tens of seconds (#400), and this close-out. Open beside it, and not this batch's: pull request
#649 and issues #626–#648, from another session's branch `agents/airbnb-practices`, which this
batch neither read nor touched.

**`dev` is not level, and it is not where the work happens**: it has stood at `8d779ef`, #342's
merge, since 5 October, 359 commits behind `main` and holding nothing `main` lacks, and no pull
request has come from it since #267 on 3 October. The `github-pr` skill still names it, so the
branch cleanup of 16 October kept it; «How to continue», at the end of section 8, says how a
batch starts either way. The remote holds `main`, `dev`, `pending/recovered-designs` and
`agents/airbnb-practices`, read on 18 October 2026, before this file's branch was pushed; the
branches of #625, #650, #651, #653 and #655 are gone.

**`main` requires one check now**, «CI», through ruleset 24883111, which the session created
after #428's merge with the owner's permission; the repository admin role may bypass it.
**And the owner ordered a 152-ФЗ track on 11 October**: after #451 and before sub-project 4's
pull requests B and C, the software is to let any deployment meet 152-ФЗ without changing the
code (epic #438, milestone 12). **On 17 October the owner put B first**, «продолжай с PR B
подпроекта 4» («continue with sub-project 4's PR B»), and B is #625. **After it, on 18 October,
the owner chose the open bugs**, «Баги status:next», out of four offers (the bugs, pull request
C, the 152-ФЗ work, or the bugs and then C), and this batch is the first of them. Section 7's
«Next for the programme» has the order.

**The schema did not move**: the head is still `0019`, on production since 16:28 UTC on 6
October, and `EXPECTED_REVISION` did not move either; no revision went with any of the night's
pull requests. The `/api/v1/warmup` of the deployment this repository's maintainer runs for
testing, read at 18:37 UTC on 17 October, before #622's merge, answered `ok` with schema `0019`
and `"v2": true`; it was not read again for this batch, and which commit it is serving was not
read.

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
- #423, #424 and #425, on milestone 11, closed by #428: a trip turned into a
  canteen break kept replacing the lessons; nothing made a CI gate required, and the «Host»
  matrix cannot be required by name; the privacy policy said the server sends no crash
  reports, and it sends them to Sentry;
- #426, open in the backlog: the owner's request of 11 October to open the privacy policy and
  the terms in the app's own WebView rather than another app, recorded for later;
- #427, on milestone 11, filed during #428's review: the legal tests read `docs/legal`
  outside Gradle's inputs, so a run after an edit can be restored from the build cache and read
  nothing, and CI's run on #428 was. #610 closed it on 17 October: every module's `Test` tasks
  now declare the files their tests read from the tree;
- #429, on milestone 12, opened from the owner's account by another session: the privacy
  policy and the terms did not say what 152-ФЗ asks an operator to publish, named two diary
  systems of the eighty-nine regions', and mis-stated when the proxy is used. #430 closed it,
  as edition 5;
- #431–#437 and their epic #438, on milestone 12, filed on 11 October from the owner's order of
  that day, the work that lets whoever deploys a server do what 152-ФЗ asks of an operator
  without changing the code: a consent to the processing of personal data, asked for and
  recorded apart from the terms (#431); a
  retention term for every kind of stored record, which a deployment can set and the tick
  sweeps (#432); an invited person's phone number kept only as a hash, and an invitation nobody
  answers dropped (#433); every diary signed in to on the diary's own side, closing the two ways
  a password still reaches the server (#434); a design for how a deployment keeps its data in
  Russia (#435, decided: where a deployment keeps its data is its operator's choice, and option C
  is the Russian option); the legal texts as a template every deployment fills in,
  naming no operator in the repository (#436); and a guide for whoever deploys a server (#437);
- #439–#445, filed on 11 October after #430's merge, from what the owner asked and reported
  that day: a recipe for running the server on a machine of one's own (#439, backlog); a beta
  in which the devices that join a server lend it their capacity as hosting, and a design that
  lets peers serve a class without being trusted (#440 and #441, research in the backlog),
  which the build console is to offer only as experimental and not recommended (#443,
  backlog); a class that admits only the devices its admins approve, each bound to a hardware
  key, with scoped and expiring access (#442, milestone 12, added to #438's track); the edge
  blur gone from the screen (#444), closed as not planned because battery saver switches it
  off by design; and its follow-up (#445, milestone 12), rewritten on 17 October as a feature
  built on #476: a settings category of its own for power saving, with a switch to follow
  battery saver or not and an economy mode that keeps the heavy effects off;
- #457–#471, fifteen issues found on 16 October while the Discussions were drafted, thirteen
  defects and two chores (#460 and #471): ten on milestone 12 and five on milestone 11.
  The batch of 16 October, in `docs/history.md` now, names each. #457 closed on 17 October,
  when private vulnerability reporting was switched on by the owner's word and read back
  `{"enabled":true}`; #459–#468 and #470 closed with #539 the same day; #458 and #469 closed
  with #622's merge; and #471, a chore, is open;
- #474 and #475, filed on 16 October by the audit behind #473, on milestone 11: comments
  and KDoc in the timetable cache still named `TimetableDao.replaceAll` (#474), and the
  `github-pr` skill's milestone table stopped at the pull requests of early October (#475).
  Both closed with #539;
- #476, filed on 17 October, on milestone 12: under battery saver «Размытие под шторкой»
  read on while no blur was drawn, nothing said why, and the other three heavy effects ignored
  battery saver. #609 closed it the same day, and #445 is built on it;
- #623, filed on 17 October by the independent review of pull request B's plan, on milestone
  11: `CLAUDE.md`, two skills and `AI_USAGE_POLICY.md` said this history carries a
  `Co-Authored-By` trailer, while 589 of its 906 non-merge commits do and none since `c83d532`
  of 6 October. Left to the session on 18 October, the owner answered «не важно, реши как лучше
  и исправь» («it does not matter, decide what is best and fix it»), and #625 closed it: all four
  now say a commit carries no attribution trailer;
- #624, filed on 17 October, on milestone 11: two of `:app`'s detekt baseline entries matched
  no code, and one could have forgiven a new finding. `eb005c3` dropped both, and #624 closed at
  #625's merge;
- #652, filed on 18 October by #653's security review, on milestone 11: four v2 answers that
  carry people's data — the three lists that name members and the diary's corrections — could
  be stored by a cache. #653 fixed it before its merge and closed it;
- #654, filed on 18 October, on milestone 11 and open, `status:next`, on the board as Ready, P2,
  size M, estimate 5: writes #655 did not reach still answer a server error on the race #384
  describes — v1's `/manage/terms` (two routes),
  `/manage/timetable/import`, the diary overrides and `/me/unlink`; v2's `UpdateTerm`,
  `UpdateTermScheme`, `ImportTimetable`, `BatchUpdateCorrections` and `UnlinkMe`; and the bot's
  homework, events, subjects, bells, tasks, the timetable's button editor, «🗓 Четверти», the
  class card and the diary corrections.

Most numbers from #477 to #621 are Discussions rather than issues or pull requests: 135
questions answered in «❓ Q&A», between #477 and #620, and their index, #621, in
«📣 Announcements» (the batch of 17 October, in `docs/history.md` now). The issues filed on 17
October are #476, #623 and #624, and this batch's are #652 and #654. #626–#648 and #649 are
another session's (above).

#395 closed with #396 and #397 with #399; #401 (milestone 11), filed by 3c, is open, and #400
(backlog), filed with it, closes with this file's pull request. Of #352, #355, #357, #365, #368,
#371, #375, #377, #378, #384 and #391, which the close-outs since 3c left as they were, four
closed on the night of 17–18 October: #368 with #650, #352 with #651, #357 with #653 and #384
with #655. The other seven stay as they were; #365's diary reads are to move onto the phone
after sub-project 3. #118, what Preview is for, was closed by #350.

The section «What the last session added» below is this batch: #625's merge, the four bug fixes
of the night (#650, #651, #653, #655), #400's keepalive on this file's own pull request, and
#654 filed for the writes #655 left. «What the session before it added» is sub-project 4's pull
request B, #625: `ManagementViewModel` and `SettingsViewModel` split into collaborators, #624's
two stale baseline entries, and #623's four documents brought level with the history. The batch
of 17 October — #473's merge, #539's thirteen document defects, #609's battery saver, #610's
test inputs, #622's repository and report line, the FAQ in Discussions, and #445 rewritten over
#476 — is in [docs/history.md](docs/history.md) now, at its top.

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
and a section 5 of this document that had only ever grown, because the things in it are not
things a JVM test can be asked. It has shrunk since 27 September 2026: what has been answered
is in the «Moved out of section 5» parts of `docs/history.md`.

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
left. Seven of those have closed since: #114–#117 and #119 at #186's merge, #110 at
#187's, and #118 at #350's on 5 October 2026.

The forward half is worth reading before planning anything: **#109** is the epic this
section describes, **#120–#122** are the owner's alone, and **#126** collects the four small
things carried deliberately, so that a later session does not re-discover a decision as if
it were an oversight.

**Two hundred and sixty-one have been filed since, to #476 on 17 October 2026**; the
early ones are named here, and the tracker has the rest. #130 was the survey #134 closed, and
#131 and #132 the two defects #133 closed. #135 is the owner's decision about the second
diary; #136–#139 and #141 are the work #140 carried; #142–#144 are its deliberate follow-ups,
on no milestone until 3 October, when #142 and #144 went to milestone 12 and #143 to
milestone 13; and #145–#165 are the defects found on its branch, each filed before its fix.
#167–#185 are what #186's walk on an emulator found and what the owner asked for that
night, and #188 what #189 found; all twenty are closed. #190–#211 are the external audit of
27 September 2026, on milestone 10, and #212 and #213 what merging its fixes found.
#268–#272 are the defects the survey behind milestone 11 found, #273 that milestone's
epic, and #275 and #276 what planning its first sub-project found. #293, on the same
milestone, is the stale tracker documents #294 closed, and #295 what writing #294 found: CI
skipped the server job when only a document its tests read changed, until #305 on 5 October.

Labels are `type:` (feature, bug, chore, research, decision, epic), `area:`, `status:` (now,
next, someday, done), `needs:` (device, owner) and `severity:` (production, only for what
broke or would have broken a running deployment). **The board is the owner's project 6,
«lessons»**, and it holds every issue and pull request. A remote session cannot reach it; a
local one fills it with `gh`, by the rule in the project's README, as «The board» in the
`github-pr` skill says.

## What the last session added: four bug fixes merged overnight (#650, #651, #653, #655), the host's keepalive (#400), and #625's merge

Written on 18 October 2026 and carried by this file's own pull request, from
`server/host-keepalive-400` to `main`, `Refs #273`, which holds #400's fix (`c5b989e`), the
merge of `main` at `ee1dce0` (`afe058d`) and this close-out. The batch began after #625's merge,
on the owner's word of 18 October, «Баги status:next», and ran through the night on their order,
«продолжай до утра, по крайней мере, пока машина не упадёт» («keep going till morning, at least
until the machine goes down»); the owner also allowed an automatic fix round on pull requests
(«можно»). Every fix is on the server; nothing under `android/`, `proto/` or
`server/migrations/` changed, so the schema stays at `0019`, `EXPECTED_REVISION` with it, and the
Android count stays at 1797.

- **Sub-project 4's pull request B is in `main`** (#625): merged at 22:41:34 UTC on 17 October as
  `81a6201`, pinned to head `2ba842e`, on milestone 11, closing #623 and #624. Its close-out rode
  in it and is «What the session before it added» below; `81a6201` is the SHA it left for this
  one to write.
- **An approval says the role the member holds after it, not the role asked for** (#650, `94d95f8`,
  from `server/approve-held-role-368`, closing #368): the bot's card and toast — «Роль не
  изменилась» when the approval changes nothing — v1's `role`, and the Telegram notice that the
  bot, v1 and v2 all send through `wording.approval_notice`; the journal line names the role asked
  for and the role granted apart. The bot's «✅ Одобрить» now goes through
  `services/manage/requests.approve`, as v1 and v2 already did.
- **«📱 Устройства» draws a button for every phone it lists** (#651, `472ae6d`, from
  `server/device-page-names-352`, closing #352): the owner's and the device's names are cut by
  their escaped length (`manage_render/_common.cut_escaped`, with `OWNER_NAME_MAX` and
  `DEVICE_NAME_MAX` both 50), so fifteen rows always fit; the worst case is 2742 characters of
  the page's 3900.
- **No cache may keep a secret or personal v2 answer, on any transport** (#653, `112982e`, from
  `server/connect-no-store-357`, closing #357 and #652): one table, `rpc/caching.py`'s
  `NEVER_STORED`, read by the REST transcoder and the Connect adapters alike, so a Connect answer,
  POST or GET, carries `Cache-Control: private, no-store` as its REST twin does, and gRPC-Web and
  native gRPC carry it as a header. The table holds every `DiaryService` answer and the three
  lists that name members, `ListClassDevices`, `ListAccessRequests` and `ListAuditEntries`, beside
  the class card, the calendar feed and the writes whose answer is a credential. #652 is what
  #653's security review found, and #653 fixed it before its merge.
- **A write whose row another writer deleted mid-write answers «not found», not a server error**
  (#655, `ee1dce0`, from `server/stale-write-not-found-384`, closing #384): v2 in `rpc/call.py`'s
  `invoke`, by its table `GONE`, as `RESOURCE_NOT_FOUND`; v1 in `api/routing.py`'s route class,
  which rolls back and answers `404` on the paths its own `GONE` names; and the bot through
  `services/gone.committed`, for substitutions, special days and «📆 Период», with two new
  sentences, `SUBSTITUTION_GONE` and `PERIOD_GONE`. Nothing is written in that case, and an upsert
  does not re-create the row: whoever deleted it meant it to be gone.
- **#654 is filed for the writes #655 did not reach** (opening above has the list): open, on
  milestone 11, `status:next`, on the board as Ready, P2, M, 5.
- **The host finds a vanished phone's `WatchClass` stream in tens of seconds** (#400, `c5b989e`,
  this file's pull request): `app.host.envoy_config` sets `connection_keepalive` on every HTTP
  connection manager it corrects, an interval of 30 s, the stream's own heartbeat, and a timeout
  of 10 s, so pyvoy's Envoy pings a quiet HTTP/2 connection instead of waiting about fifteen
  minutes for the kernel to give up on it. #395's guard, and the correction with it, now walk
  `default_filter_chain` as well as `filter_chains`. hypercorn, the fallback, has no such setting
  and keeps the gap; `docs/deploy.md` says so. Four new tests in `tests/test_host.py`.
- **This close-out**: 3366 server tests and 242 mypy modules in every place that carries them;
  `CLAUDE.md`'s `rpc/`, `services/` and `api/` bullets brought level with `rpc/caching.py`,
  `call.py`'s `GONE`, `services/gone.py` and the route class's `404`; and this file — this section
  on top, #625's batch second, the batch of 17 October moved to the top of `docs/history.md`, and
  the opening, the milestone table and sections 5 and 7 brought up to date.

### Gates

- **#650, #651, #653 and #655**: CI green on the exact head each was merged at, pinned with
  `--match-head-commit`. On #655's head the server job ran **3356 passed, 6 skipped**, which is
  **3362**.
- **This file's branch**, at `afe058d` with the documents edited: **3366** tests collected
  (`pytest --collect-only`), 3362 and #400's four, six of them skipped unless a host runs; the
  suite itself was not run for this close-out. **mypy**, no issues in **242** source files, two
  more than the 240 every document said, for `rpc/caching.py` and `services/gone.py`. **ruff**
  clean. From `server/`, `test_env_example.py`, `test_ci_paths.py` and `test_schema_version.py`,
  which read the documents, with `-p no:xdist`: **22 passed**, after this close-out was written.
  CI on this pull request's head is read before the merge.
- **Android**: nothing changed under `android/`, and nothing was run; the count stays **1797**.

### What was deliberately left alone

- **#654's writes.** #655 answers «not found» on the writes it names above; the rest are #654,
  open and `status:next`, so it is among the next bugs.
- **#270, the app matching English error text**: it belongs to sub-project 5, when the app moves
  to v2's `ErrorInfo.reason`.
- **#401, `watch.proto`'s comment on a newer stream ending the older one**: the fix needs
  `buf generate`, Buf 1.73.0 is not installed where this batch ran, and the generated code is never
  edited by hand. Fetching the binary waits on the owner (section 7).
- **#268 and #269 were not looked at**: whether either was left deliberately is to be read first.
- **hypercorn's gap** (#400): it has no keepalive setting, so `--server hypercorn` keeps finding a
  vanished stream only when the kernel does.
- **Pull request #649 and issues #626–#648**, another session's, not read.

### What nobody has verified in this batch

- **Nothing ran in Telegram or on a device**: the approval card and its toast, «📱 Устройства» at
  fifteen long names, and the «not found» answers of substitutions, special days and «📆 Период»
  have been seen by their tests only.
- **#655 ran on SQLite only**: the race against PostgreSQL's own `UPDATE` was not run.
- **#400 was measured on no live host**: the interval and timeout come from the heartbeat's
  cadence, not from a running deployment, because the host is deployed nowhere yet.
- **No test exercises gRPC-Web or native gRPC for #653's header**: Connect over HTTP is what the
  tests ask.
- **Nothing was deployed by hand**: Vercel deploys `main` itself, and neither the deployment's
  warmup nor which commit it serves was read for this batch.
- **CI on this pull request's head** had not been read when this was written.

### Left to the owner from this batch

**Let a session fetch Buf 1.73.0 for #401**, or run `buf generate` yourself (section 7). Optionally,
look at the approval card and «📱 Устройства» in a real chat, since nothing here has.

## What the session before it added: sub-project 4's second pull request, `ManagementViewModel` and `SettingsViewModel` split into collaborators

Merged as #625 (`81a6201`, at 22:41:34 UTC on 17 October 2026, already 18 October in Moscow),
pinned to head `2ba842e`, from
`android/decomposition-b`, on milestone 11, closing #623 and #624, with CI green on that head.
Written on 18 October 2026 and carried by #625 itself, `Refs #273`: the second of sub-project 4's
three pull requests, per `docs/specs/2026-10-05-android-decomposition-design.md`, with its plan
at `docs/specs/2026-10-18-android-decomposition-b-plan.md`. The branch was cut from `main` at
`1cc30fe`, #622's merge. It was built on the owner's word of 17 October 2026, «продолжай с PR B
подпроекта 4» («continue with sub-project 4's PR B»), which put it ahead of epic #438 (section
7). No revision goes with it: the schema stays at `0019`, `EXPECTED_REVISION` with it, and
nothing under `server/` changes. Every public name the screens call stays, so no screen changes.

- **The plan** (`5cab74d`): the second Android split, `ManagementViewModel` and
  `SettingsViewModel` into collaborators, written read-only against `1cc30fe`.
- **The plan, corrected after its independent review** (`65a1976`): nothing blocking, five
  things that would have misled whoever ran it, and nine of eleven smaller corrections; it
  records the owner's word on the order. The same review found what #623 says.
- **What the management sheets share is held before their work moves out of the view model**
  (`a2ba880`): `ManagementAcrossSheetsTest`, three tests — the page's one write at a time
  across sheets, the devices' switch read at the call, and a search still out when the view
  model is cleared not landing. Each was seen failing under a mutation of its own.
- **The management page's state types have a file of their own** (`3c3a0de`):
  `ManagementState.kt`, and `ManagementViewModel.kt` goes from 821 lines to 665.
- **The management page's sheets are seven classes over one plumbing, behind one-line
  delegates** (`3a8792c`): `ManagementPlumbing` holds the page's state and the four ways every
  sheet touches it, `read`, `write`, `finish` and `note`; `ClassCardActions`, `SubjectsActions`,
  `BellsActions`, `TimetableActions`, `DevicesActions`, `LogActions` and `RequestsActions` hold
  each sheet's work verbatim, over the view model's `viewModelScope`. The view model keeps its
  constructor, `init`, `recheckRole`, `consumeNotice`, `dismissWriteFailure` and the factory,
  and every other public method as a one-line delegate where it stood. The only widening is
  `state` and the plumbing's four functions, from private to members of an internal class.
- **Two of `:app`'s detekt baseline entries that no code matched any more are gone** (`eb005c3`,
  #624): `RegionStep`'s `MaxLineLength` and `AppCorrections.correctionOf`'s `ReturnCount`, both
  from the first baseline, `9181796`. The second mattered, because an entry is matched by its
  signature and would have forgiven a new `ReturnCount` in that function. `:app`'s baseline
  goes from 184 entries to 182, and the split's own entry did not move. The commit says
  «Closes #624», so #624 closes at this pull request's merge.
- **What the settings view model publishes and writes is held before it is split** (`2ab80b5`):
  `SettingsViewModelTest`, thirteen tests, where nothing had tested `SettingsViewModel` — the
  whole state it publishes while watched and nothing before, the message a failed sync and a
  refused bug report both write, the update check's switch read at the tap, the launch check's
  switch and its put-off tag, GitHub's account, sign-in and pull request, and the Telegram
  link's reset when the class changes, with an answer about the old class dropped. Four
  mutations each failed exactly the test named for it.
- **The settings page's state types have a file of their own** (`15edc9a`):
  `SettingsState.kt`, and `SettingsViewModel.kt` goes from 716 lines to 603.
- **The settings page's update check, GitHub work and Telegram link are classes of their own**
  (`4df9759`): `SettingsUpdates`, `SettingsGithub` and `SettingsDeviceLink`, over the view
  model's `viewModelScope`. The view model keeps both combines and both `stateIn`s, both `init`
  launches in their order, `message`, the sync, the setters and the calls that only forward to
  a repository; `SettingsGithub` is handed `message`, because the sync writes it too. The only
  widening is `releaseSheet`, `filingIssue` and `deviceLink`, from private to members of
  internal classes.
- **Why the settings collaborators are built where they are is said in words a reader can
  follow** (`8415fca`): comments only, from the whole-branch review — Kotlin initializes in
  textual order, so the three classes stand after `message` and before the combines that read
  them; `ManagementViewModel`'s header KDoc is rewrapped.
- **This close-out**: 1797 Android tests in the four places that carry the Android count, a
  row in `docs/architecture.md`'s test table for the two new test classes, `docs/build.md`'s
  baseline count at 491 after #624, and this file — this section on top, #622's batch second,
  the batch of 16 October moved to the top of `docs/history.md`, and the opening, the
  milestone table and sections 5 and 7 brought up to date.

### Gates

- **On `4df9759`, 18 October 2026**, every Gradle command with `--max-workers=2` and alone:
  - **`./gradlew test`**, `BUILD SUCCESSFUL` in 10s;
  - **each module's real test task forced** — `:core:model:test --rerun`, and
    `testDebugUnitTest --rerun` in `:core:data`, `:core:designsystem`, `:widget` and `:app` —
    `BUILD SUCCESSFUL` in 49s, all five executed. Counted from the JUnit XML: `:core:model` 125,
    `:core:data` 638, `:core:designsystem` 205, `:widget` 126, `:app` 703, **1797**, with no
    failure, error or skip: sixteen more than `main`'s 1781, three in
    `ManagementAcrossSheetsTest` and thirteen in `SettingsViewModelTest`;
  - **`assembleDebug`**, `BUILD SUCCESSFUL` in 14s, and **`assembleRelease`**, in 1m 48s;
  - **`detektBaseline`** for all five modules, no diff, so no other module holds a stale entry
    and #624 is whole with `:app`'s two; then **`detekt`**, `BUILD SUCCESSFUL`.
- **The two baseline readings in `:app`**: after `ManagementViewModel`'s split, two stale
  entries dropped (`eb005c3`) and the split's own entry unchanged; after `SettingsViewModel`'s
  split, no diff, so the plan's Task 8 committed nothing. The five baselines hold **491**
  entries, 493 less #624's two: `MagicNumber` 218, `ReturnCount` 80, `MaxLineLength` 64.
- **On `8415fca`**, comments only: `:app`'s tests and `:app:detekt` ran again, green, 703.
- **Server**: no code changed. From `server/`, `test_env_example.py`, `test_ci_paths.py` and
  `test_schema_version.py`, which read the documents, with `-p no:xdist`: **22 passed** before
  the documents were edited and again after this close-out was written, in a venv made with
  `uv`. CI on this pull request's head is read before the merge.

### What was deliberately left alone

- **Pull request C**: `OnboardingScreen`, with `OnboardingPromisesTest`'s path and anchor;
  `FloatingToolbar`'s split by concern; and `HomeShell`'s pure rules, with its body left
  inline. Section 7 has where it stands in the order.
- **What the classes do.** No class gets an interface, an injected dispatcher or a test seam of
  its own; each is reached through its view model, as the screens reach it.
- **`signInWithGithub`, `cancelGithubSignIn`, `signOutOfGithub`, `installedVersion` and
  `refreshNow` stay in `SettingsViewModel`.** The first four only forward to a repository, so
  moving them would add a second one-liner each and split nothing; `refreshNow` writes
  `refreshing`, which the view model's own combine reads, and `message`.
- **The view models' `TooManyFunctions` entries stay.** The public surface is the screens'
  contract, and a move keeps it.
- **What sub-project 5 rewrites**: the audit log's paging, the school search's transport and
  the failure classifiers, which `LogActions` and `ClassCardActions` carry verbatim.
- **A screen test over the real `SettingsViewModel`.** It would test the rows, not the split.

### Where it departs from its design and its plan

- **`message` is handed to one collaborator, `SettingsGithub`**, where the design said «both»:
  the sync writes it too, and the sync is not a collaborator.
- **`ManagementViewModel` got a test before its split too**, though the design asked for one
  only before `SettingsViewModel`'s.
- **The plan's Task 4 committed a baseline**, `eb005c3`, but for #624's two stale entries, not
  for the split; Task 8 committed nothing.
- **`loadDevices`' delegate is one statement over two lines**, wrapped where its signature was.
- **`3a8792c`'s body says 9 `plumbing.` references in the view model where there are 10**, 73
  in all; the commit is pushed and stays as written.
- **The settings test's commit was amended before it was pushed**, for a sentence about the
  fakes that was not accurate.

### What nobody has verified in this batch

- **No APK from `android/decomposition-b` was installed** on a device or an emulator.
- **No screen test composes a settings row over a real `SettingsViewModel`**: the rows' wiring
  to the delegates is held by the compile.
- **The scripts that moved the code in `3a8792c` and `4df9759`** were checked by the move
  checker and the counts; the plan's dry run of them was condensed.
- **Branches that moved verbatim with no test reaching them**: the launch check's once-a-day
  interval and its notification switch, the Telegram link's in-flight guard and its failure
  paths, and work still out in the update check or the Telegram link when the view model is
  cleared (`2ab80b5`'s «Not covered»).
- **CI on this pull request's head** had not been read when this was written.
- **The warmup was not read for this batch**: the last reading is 18:37 UTC on 17 October.

### Left to the owner from this batch

Nothing new. The merge of a session's own pull request now has an `autoMode.allow` rule in the
user settings (section 7).

## The milestones

**The milestones as they are now.** The owner renamed all nine on 25 September 2026, so that
every title names what its version delivered and every description names its pull requests
and issues, and created the tenth the same day, and the eleventh, twelfth and thirteenth on 3
October. The older batch sections, in `docs/history.md`
now, quote the titles of their own time, which a search no longer finds; the third column
maps them. The
`github-pr` skill carries a shorter table that says only what each milestone is and what
belongs in it, and points here for the lists (#475, closed by #539); this table is the one kept
current.

| # | Title | Called before 25 September | State | Covers |
| --- | --- | --- | --- | --- |
| 1 | `v0.1.0 — App, widget, admin bot and read API` | `v0.1.0 — First run on a phone` | closed | PRs #1–#14; issues #86, #88, #89, #278–#281 |
| 2 | `v0.2.0 — Petersburg e-diary, class run from bot and phone` | `v0.2.0 — The diary, and the class run from the bot` | closed | PRs #15–#17, #28–#31; issues #87, #90, #91, #102, #282, #283 |
| 3 | `v0.3.0 — School year, terms, school search, several classes` | `v0.3.0 — The school year` | closed | PRs #27, #32–#35, #43; issue #92 |
| 4 | `v0.4.0 — 67-defect sweep, first audit, app-wide correction mode` | `v0.4.0 — Nothing breaks in silence` | closed | PRs #44, #45, #50; issues #93, #94 |
| 5 | `v0.5.0 — Public repo: secrets audit, English docs, font licence` | `v0.5.0 — A public repository` | closed | PRs #46–#49, #51, #55–#57, #59; issue #95 |
| 6 | `v0.6.0 — Dishka DI, scrolling text, in-app guide from the repo` | `v0.6.0 — One container, and nothing cut off` | closed | PRs #60–#74; issues #96, #97, #99, #101, #103, #104, #284–#291 |
| 7 | `Dependencies — dependabot bumps` | `Dependencies` | open, for good | every dependabot bump, #452's fold of #446–#450 on 16 October 2026 the latest; deliberately not a version |
| 8 | `v0.7.0 — School-year calendar, day ribbon, rearrangeable tabs` | `v0.7.0 — Оптимизация` | closed | PRs #75–#85, #128; issues #98, #100, #105–#108, #292 |
| 9 | `v0.8.0 — On-device checks, 89-region e-diary survey` | `v0.8.0 — On a device` | open | PRs #129, #133, #134, #186, #187, #189, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261, #263, #267 and #399; issues #109–#117, #119, #130–#132, #167–#185, #188, #219–#233, #237, #240, #242–#244, #246, #247, #249, #251, #253–#256, #258–#260, #262, #264–#266 and #397 — the first whose work needs an emulator or a phone, and #186 the first done on one |
| 10 | `v0.9.0 — NetSchool e-diary, onboarding via the school's diary` | none — proposed as «v0.9.0 — A second diary», never created under that name | open | PRs #140, #214, #218, #303, #335 (merged); issues #135–#139, #141, #145–#165, #190–#211 (the external audit of 27 September), #212, #213, #235, #236, #302, #334, #343 |
| 11 | `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console` | none — created on 3 October 2026 under this name | open | PRs #274, #277, #294, #296, #297, #300, #301, #305, #306, #307, #308, #311, #313, #319, #328, #332, #342, #350, #356, #372, #376, #379, #380, #385, #392, #394, #396, #410, #418, #422, #428, #451, #473, #539, #610, #625, #650, #651, #653 and #655 (merged) and this pull request, from `server/host-keepalive-400` (open); issues #268–#273, #275, #276, #293, #295, #298, #299, #304, #309, #310, #312, #314–#318, #320–#325, #331, #336–#341, #347, #348, #351, #352, #353, #354, #355, #357, #367, #368, #369, #370, #373, #374, #381, #382, #383, #384, #389, #390, #393, #395, #401, #406, #407, #408, #411, #419, #420, #423, #424, #425, #427, #461, #463, #464, #466, #468, #474, #475, #623, #624, #640, #652 and #654 — the programme of `docs/specs/2026-10-03-one-contract-design.md` |
| 12 | `v1.0.0 — A build somebody else can install` | none — created on 3 October 2026 under this name | open | PRs #329, #333, #359, #363, #388, #398, #403, #413, #415, #417, #430, #472, #609 and #622 (merged); issues #120–#122, #127, #142, #144, #326, #327, #330, #349, #358, #360–#362, #364, #365, #386, #387, #402, #404, #414, #416, #429, #431–#438, #442, #444 (closed, not planned), #445, #457–#460, #462, #465, #467, #469–#471, #476, #636–#639 and #641 — the steps epic #127 names between one class on one phone and a build a second family could use |
| 13 | `Backlog — not scheduled` | none — created on 3 October 2026 under this name | open | issues #118 (closed by #350), #123–#126, #143, #371, #375, #377, #378, #391, #400, #405, #409, #412, #421, #426, #439, #440, #441, #443, #626–#635 and #642–#648, and PR #649 (open); deliberately not a version, like 7 — known gaps and decisions no release is waiting for |

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

- **The night's four bug fixes and #400 have been seen by their tests and CI only** («What the
  last session added» has the detail).
  - **Nothing ran in Telegram or on a device** (#650, #651, #655): nobody has pressed «✅
    Одобрить» on a request for a role the member already holds and read «Роль не изменилась»,
    drawn «📱 Устройства» with fifteen long names, or seen a substitution, a special day or
    «📆 Период» answer «not found» after its row was deleted under it.
  - **#655's race ran on SQLite only**: the `StaleDataError` it catches was raised by SQLite's
    `UPDATE` matching nothing, never by PostgreSQL's.
  - **No test exercises gRPC-Web or native gRPC for #653's `Cache-Control`**: the tests ask REST
    and Connect over HTTP.
  - **#400's keepalive was measured on no live host**: a 30 s interval and a 10 s timeout are
    chosen from the stream's heartbeat, and nobody has watched Envoy drop a phone that vanished.
    Under `--server hypercorn` the gap of about fifteen minutes stays.
- **Sub-project 4's pull request B, #625, merged as `81a6201`, has been seen by its tests and its
  gates only** («What the session before it added» has the detail).
  - **No APK from `android/decomposition-b` has been installed** on a device or an emulator;
    nobody has opened the management sheets or the settings page over the split view models.
  - **No screen test composes a settings row over a real `SettingsViewModel`**: the rows'
    wiring to the one-line delegates is held by the compile, and `SettingsViewModelTest` holds
    the view model, not the rows.
  - **Some branches moved verbatim with no test reaching them** (`2ab80b5`'s «Not covered»):
    the launch check's once-a-day interval and its notification switch, the Telegram link's
    in-flight guard and its failure paths, and work still out in the update check or the
    Telegram link when the view model is cleared.
  - **The scripts that moved the code in `3a8792c` and `4df9759`** were checked by the move
    checker, the counts and the tests; the plan's dry run of them was condensed.
- **#609, #610 and #622 have been seen by their tests and their gates only** (the batch of 17
  October, in `docs/history.md` now, has the detail). Nothing in any of the three ran on a device
  or an emulator.
  - **Battery saver has not been switched on under the app** (#609): nobody has watched the
    ripple, the four motion blurs, the progressive blur and the theme reveal go off, read
    «Выключено, пока включён режим энергосбережения» under the three rows, or seen «Размытие
    под шторкой» explain the missing blur — under battery saver, or on Samsung's Android 15 and
    earlier — while its edge wash goes on drawing.
  - **#610's inputs were seen re-running a task locally only**: on `a5fb054` a one-character
    edit of `privacy.en.md` left `:core:data:testDebugUnitTest` UP-TO-DATE, and after the fix the
    same edit re-ran it with Gradle naming the input. No CI log has been read for it.
  - **No APK has been built from a fork with `LESSONS_BUILD_REPOSITORY` set** (#622, #469):
    the releases it lists, the issue it files, the guide it fetches, the translation pull
    request it cuts and the developer mode's permission question are held by
    `BuildPropertyReachTest`, `GithubProjectTest`, `UpstreamNameTest` and the compile, and by
    nothing that reached GitHub.
  - **No report has been filed from the app since the label went** (#622, #458): nobody has
    seen «_Отправлено из приложения «Дневник» <version>_» end a real issue, through the API or
    the browser, or found one with `"Отправлено из приложения «Дневник»" in:body`.
- **The FAQ in Discussions describes `main` at `a1135d5`** wherever an answer's state line says
  «State: main at `a1135d5`, 16 October 2026 …» rather than «Applies to: any version», and #539,
  #609, #610 and #622 have already made some of those answers out of date: the
  battery-saver behaviour, for one, and the Hobby wording in the hosting answers, for another.
  The index, #621, says what a state line means; nothing has rewritten an answer since it was
  published.
- **#452's bumps and #472's form have been seen by their gates and CI only** (the batch of 16
  October, in `docs/history.md` now, has the detail).
  - **No APK from #452 has been installed**: Gradle 9.8.1 and Kotlin 2.4.21 built, and passed
    the tests, the assembles and detekt, and nothing more.
  - **FastAPI 0.143.0, SQLAlchemy 2.1.4 and asyncpg 0.32.0 have not met PostgreSQL**: CI and
    the local run are SQLite, and #411's PostgreSQL run was not repeated.
  - **The Discussions' Q&A form has not been seen rendered.** GitHub reads
    `.github/DISCUSSION_TEMPLATE/q-a.yml` from `main`, so from #472's merge on.
- **Sub-project 4's pull request A, #451, has been seen by its tests and its gates only** (merged
  as `8564b09`; its section, in `docs/history.md` now, has the detail).
  - **No test reaches the four GitHub classes.** `GithubApi`'s HTTP client and `API_BASE` are
    global, so nothing can point `GithubDeviceFlow`, `GithubPullRequests`, `GithubIssues` or
    `GithubPermissions` at a mock server; their check was the compile, `DeviceFlowPollTest`, a
    line-by-line move check and review.
  - **No APK from #451's branch has been installed** on a device or an emulator; the assembles are
    the whole of what was seen of the build.
  - **What the two-worker cap saves is unmeasured**: nobody has timed a build, or counted
    crashes, with and without it on the maintainer's machine.
- **#423 and #425 have been seen only by their tests** (#428; its section, in `docs/history.md`
  now, has the detail).
  - **CI did not run the legal parity tests on edition 4** (#427): its «Android» job restored
    `:core:data:testDebugUnitTest` from the build cache. Edition 4's parity rests on a local
    `--rerun` (11 and 10 passed). #610 closed #427 on 17 October, so an edit of `docs/legal`
    now re-runs the task; that was seen locally, not in a CI log.
  - **No client changes an event's kind yet.** The rule is v2's `UpdateEvent` and nothing calls
    it but the tests until sub-project 5.
  - **Neither edition 4 nor edition 5 (#430) of the privacy policy has been opened in the app.**
    Edition 5's parity was held by #430's local `--rerun` and again by #451's
    `test --rerun` on the merged tree, where `:core:data:testDebugUnitTest` ran rather than
    came from the cache: `LegalDocumentsTest` 11 and `LegalLinksTest` 10, all passed. What
    either says leaves for Sentry is checked against `app/observability.py`, not against an
    event Sentry received; no lawyer has read edition 5; and its counts of regions per diary
    platform are the survey's of September 2026, which will drift as regions move.
- **#406's routes have been seen only as far as a school search** (#422; its section, in
  `docs/history.md` now, has the detail).
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
    skipped, in 3 min 52 s, covering `80cce89`; its Gates section, in `docs/history.md` now,
    has the figures.
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
- **No record says the launcher icon of #388, sixteen variants since #403, was switched or
  looked at on a device or an emulator.** The debug APK of `7ce4677`'s code, which carries it,
  ran on an emulator for the geometry pass of 10–11 October. Every claim about it is a
  JVM test's, and the launcher that draws it is another app. Unseen:
  - any real launcher: how Pixel Launcher, One UI and MIUI place, re-index and clip the icon
    when the alias changes;
  - themed (monochrome) icons on Android 13 and later;
  - «AMOLED», the one edge-to-edge style left, under a launcher mask smaller than the standard
    circle;
  - whether the app stays open, and stays in Recents, after «Применить» disables the alias it
    was started through;
  - whether «Значок приложения» stutters when it opens on a low-end phone, since its 16 tiles
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
- **The new CI filter (#295) has been seen on GitHub's runners**: in #305's own CI; in #311's,
  whose first commit (`66c7ba1`) touched only `CLAUDE.md`, `.claude/` and `docs/build.md`; and
  in #472's, which touched only documents and `.github/` templates. The last two started the
  server job with the Android and contract jobs skipped. Nobody has seen a commit confined to
  `proto/` start it on its own. Its guard, `test_ci_paths.py`, sees only the first part of a
  path, so a new file under a root already listed (`.github/dependabot.yml`, say) is caught only
  by adding it to the guard's written list.
- **Whether Telegram ever delivers a message that starts with whitespace is unverified**
  (#304). The form breakout now treats «  /week» as a command because aiogram's `Command`
  filter does, after `text.split()`; its clients are not known to keep plain spaces there, but
  a no-break space is whitespace to `str.split`, and nobody has sent one.
- **v2 as #342, #350, #356, #372, #376, #380, #385, #392, #394 and #396 serve it has been
  asked little outside the test client** (stages 3a, 3b-1, 3b-2, 3b-3, 3b-4, 3b-5, 3b-6, 3b-7,
  3b-8 and 3c; their sections, in `docs/history.md`, have the detail):
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
    `unauthenticated`; the ten of 3b-7 were asked once after #392's merge, without a token:
    REST `POST /diary/sessions` with `{}` answered `400` `VALIDATION_FAILED`, `DELETE
    /diary/sessions/current`, `/diary/students`, `…/students/1/scheduleDays` and
    `…/students/1/turnstileEvents` `401` `DIARY_TOKEN_INVALID`, and Connect
    `DiaryService/ListStudents` `401` `unauthenticated`. The four of 3b-8, asked after #394's
    merge, answered `501` until the deployment this repository's maintainer runs for testing
    carried them, at 09:12 UTC on 10 October, and then
    `401` `DIARY_TOKEN_INVALID` on all four REST routes and all four Connect methods. Stage 3c's
    `WatchClass`, asked over Connect without a token after #396's merge, answered `200` ending
    `unauthenticated` with `DEVICE_TOKEN_INVALID`, and native gRPC `415` under `/api/rpc`;
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
  remote plugins without a token. It has run on every pull request that changed the contract,
  from its first run on #297 (37230983817) to #428 on 11 October, and no run is known to have
  been throttled. If it ever is, either step can be the one that fails (the buf-action step
  fetches `googleapis` from the Schema Registry before generate runs, and says nothing of what
  to do), and the remedy is the same `BUF_TOKEN` (section 7).
- **`buf breaking` has refused a change only locally.**
  - Locally, it compared a scratch copy of the contract with `main` at `45f0680` over 25
    mutations (#300, which found #298).
  - In CI it compared first on #300 (run 37239214776), and then on every pull request after it
    that started the contract job: the eleven that changed `proto/`, #350 to #428 (11 October),
    and #342, which changed `buf.gen.yaml` and the generated code; the merge records that name
    the job have it green, and GitHub no longer keeps those runs. No CI run is known to
    have refused a breaking change.
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
  `LESSONS_GITHUB_CLIENT_ID` cannot get past the door, but `apk.yml` reads that secret and it
  has been registered since 21 September 2026, so every «APK» run since should carry it: the
  twelve that succeeded on `main` to 1 October, one on `dev` (#23), and run #41, the branch
  build the gate of 10–11 October installed on the emulator. The runs of `main` have lost their logs and run #41 is no longer listed, so no
  run summary can show it any more, and nobody has signed in through the door (#461).
  The mode is also the first tool for most of this section: its checks run from the phone's
  own network, and its records name the leg a sign-in is stuck on.
- **Of the routes in `docs/diaries/`, only «Сетевой город»'s school search has been seen
  answering**, and no sign-in or diary read has, on any platform. #134's 1916 routes
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
  client sends, not what a server was seen to accept. The Госуслуги refusal path
  (`SignInUnsupported`) is the least exercised of all. The refresh-token grant (LoginType 9)
  that older notes name was never written: the server's client and the phone's port both sign in
  with `loginType` 1 alone. The sign-in needs an account, which the gate did not have, so the
  first live sign-in on it is still the owner's, #121's caveat a second time.
- **The phone's own sign-in has never met a live diary either — neither «Сетевой город» nor
  Петербург.** The Kotlin ports and the Python originals run one set of known-answer vectors
  (`server/tests/vectors/diary_protocol.json`), which proves the two agree and nothing about
  whether either is right. Whether a Петербург pupil's own account lists itself as a pupil,
  and how long its token lives, are unknown for the same reason. Петербург's sign-in met the
  live diary once, on 5 October, on an emulator: an account the owner supplied was answered
  `401`, which a made-up account gets too, so it says nothing about whether the port is right
  (the section on #342).
- **Nobody knows whether a diary accepts a session opened on a phone when the server replays
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
  edition 5 of both texts from it: #430, merged on 11 October as `014b1c0` over #428's edition 4
  (#425), closing #429. A session wrote it, not a lawyer.
- **The tab arranging has been seen on an emulator only, and it is a gesture.** #84 is a
  long press, a wobble and a drag; #85 is the drag actually reporting where it landed. What
  the JVM tests prove is arithmetic and contracts: where a drag of so many pixels lands, that
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
  `ToolbarDragTest` does and what caught it. Anything *between* two slots is asked of a
  composition only on a device: `ToolbarOnDeviceTest` (#110) carries a tab seven tenths and four
  tenths of a slot, on demand and never in CI. On the JVM `ToolbarReorderTest` asks it in pixels
  instead.
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
  phone on a school's wifi. `ServerStatus` has six
  states — `Checking`, `NotConfigured`, `NeedsHttps`, `Ok`, `Degraded` and `Unreachable` — and
  `AboutCardTest` draws each from a value it is handed; of what `serverStatus()` returns, tests
  read back only the two given with no answer from a server, `NotConfigured` and `NeedsHttps`.
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
- **The marquee hang was tried once and did not happen, and `MarqueeClockTest` does not claim
  it.** The guard #83 added for the trap that costs a whole Gradle run records in its own KDoc
  that taking the `@Before` out of `MarqueeTextTest` left all ten of its tests green in six
  seconds, on the toolchain of 22 September. What it holds is narrower: every Compose test that
  can draw a `MarqueeText` holds the clock or says why not, and nineteen files say so with its
  marker. Whether a later Compose version would hang has not been asked again.
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
  `:app` has thirty-two files that compose real UI, and most of them press it — among them
  the class group, the join-mode switch, the code screen's refusals, the bell rows, the
  schedule sheet, the term label, the onboarding, the debug report, the translation session,
  the edge fade, the Telegram card's failure, «Календарь»'s header and year picker, the
  diary's home, the server address sheet, the ribbon's settings, the about card, the app
  icon picker and the legal sheets — 145 tests between them on 16 October. It started at
  three screens and 21 tests, which is what this paragraph said for several batches after it
  had stopped being true. Those are real presses on real strings rather than stubs: the
  locale is pinned to `ru-rRU`, or Robolectric takes `values-en/` and the test checks the
  translation instead of the source.
  What this proves least is how it looks. Ten files run under Robolectric's native graphics,
  and most of them measure what is drawn: a row's height and a segment's gaps, «Календарь»'s
  right edge and header, the onboarding's fade, the tab bar's disc through its change in both
  themes, and a scrolling line's lead held still. Beyond those, the layout, the dark theme and
  the animations are unproved, and so are dynamic colours and the widget, which nothing draws
  (below).
- **Every card in the bot is now drawn**, and that found eight defects in one pass
  (section 4, in `docs/history.md` now). The hole that is left is exactly where it was: the
  tests assert what is
  written on a card rather than how it looks in a client. A live Telegram has shown some
  of them: in #186's walk, `/start`, «📱 Подключить телефон» and the class «11А», driven from
  the owner's chat; after #359's merge, «📊 Проект» on the owner's screen and the self-check's
  first real alerts with their recoveries, and «📊 Проект» again on 11 October; and on 10
  October, through the test bot, an evening digest, an event's notice and a schema alert with
  its recovery (#411's gate). Each recorded what the bot said, not how a card looked.
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
- **The build-chain bumps have been looked at only as far as #186's walk and the live gate of
  10–11 October go.** AGP 9.3.1 → 9.4.0 → 9.4.1, Gradle 9.5.0 → 9.7.1 and compose-bom 2026.06.01
  → 2026.09.00 are proved by every test passing and both assembles building — locally and on the
  runner. The same goes for the two Python floors raised the same way, sqlalchemy 2.0.54 and
  pydantic 2.13.5: the suite passes on them and nobody read either changelog. The bumps since
  are proved the same way. Gradle 9.8.0 (#218) built the debug APK the live gate of 10–11
  October walked on an emulator; Gradle 9.8.1 and Kotlin 2.4.21 (#452) are in no APK anybody has
  installed; and of sqlalchemy's floors at 2.1.1 (#218), 2.1.2 (#366) and 2.1.4 (#452), with
  FastAPI's at 0.142.2 (#366) and 0.143.0 and asyncpg's at 0.32.0 (#452), only sqlalchemy 2.1.2
  with FastAPI 0.142.2 has run the suite on PostgreSQL, in #411's gate and on #422's branch. The
  compose-bom is **the app's entire rendering**, and one of its effects already surfaced by
  itself (`OverlayLayerTest`, section 6). Both builds #186 walked on an emulator — `bd7c816`'s
  release and its own branch's debug — are on all three, and the one defect it filed that names
  any of them is #185, AGP 9.4's warnings about `srcDirs`: a build message, not a screen. Nobody
  has set a screen beside one from a build before them, so what the compose-bom changed where
  there is no test, nobody knows. On a phone, that is the first reason on the list to open the
  APK.
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

- **#62, the batch that stopped text being cut off, has been seen moving in the reports #80
  answered, and once on an emulator**:
  - on a phone, a line that scrolled beside one that had spent its three passes and parked,
    which #80 made endless;
  - on the API 37 emulator on 2 October (#252), «понедельник, 5 октября» scrolling beside a
    held «Домашнее задание на», filmed at two frames a second for eight seconds.

  Robolectric composes the marquee. Only `DataLineTest` advances one and compares two frames, to
  show that a lead stands still while its data scrolls. Beyond that, what is proven is that a
  line which fits is laid out exactly as before, that a line which does not stays inside its box
  rather than pushing the row apart, and that the whole string is present either way. Whether it
  reads well scrolling needs the APK. The same for the other direction: nobody has seen what an
  uncapped block does with a long string. **A five-line lesson note now makes a five-line row**,
  and whether an uncapped heading pushes a sheet's first control too far down is a question only
  a screen can answer.
- **The fade widths are chosen, not measured.** 8 dp at each end, on a segment's label as on
  every other scrolling line; nobody has looked at them beside the Essentials original they
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
  `:core:data`'s tests have no `Context`. Nobody has recorded how the pager, the loader on the
  guide or the banner naming its version look. The arrow that leaves the guide was seen once,
  on the API 37 emulator on 2 October (#245), landing on «О приложении»; before that, one of
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

`android-actions/setup-android` is **pinned to an exact tag**, `v4.0.4` since #166's bump of
26 September, and is given `packages: platform-tools` — in `ci.yml` and in `apk.yml`. That is
not cosmetic: a floating
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
one class's window under another's name. `replaceWindow` moves the `classId` from the class's
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
the whole bot at once. Caught before it was applied; three tests in `test_join_modes.py` hold
all three sides of it: the column, a row the default filled in, and the revision's own
spelling. **Ask the same question of any future enum with a `server_default`.**

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
than `values/` — a key cannot be looked up by text, since 286 strings of 1,318 match another's
text, and another 152 are patterns with arguments.

Both original imports are still on the classpath and still compile, so a new screen written
out of habit would simply be a page the proofreader cannot touch, and nothing would say so.
`CorrectionReachTest` holds this: it reads every module's sources and fails on
`import androidx.compose.material3.Text` (aliased too), on `BasicText`, on
`import androidx.compose.ui.res.stringResource`, and on a file of composables in `:app` or
`:core:designsystem` that calls `getString` or `getQuantityString` itself. There are four
exceptions (`Corrections.kt` itself, the shim `Text.kt`, the editor and the corrections sheet),
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
  covers `~/.gradle`, not `~/.android`, so one debug-signed APK run's build never updates
  another's, and Android says only «Приложение не установлено». `versionName` is never
  compared and a higher `versionCode` does not help. The cures are an uninstall, which takes
  the device's classes, token, diary session and corrections, or the release key: the four
  keystore secrets are set, so `apk.yml` signs the release APK of every run of `main` or a
  `v*` tag with it, and each of those updates the one before once a debug-signed install has
  been uninstalled one last time; a run of any other branch is debug-signed on purpose
  (#330). `docs/build.md` has the long version.
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
  the body and requires `fork` with the parent's `full_name` equal to this project — since
  #622, the repository the build names in `LESSONS_BUILD_REPOSITORY`, `lumenpearson/lessons`
  when it names none.
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
sees is decided by the database, and the answer can differ between two reads. The rule is held
in four places, and all of them are needed: `timetable_io._conflicts` on a week paste (the bot's,
and the phone's through `services/manage/timetable.import_paste`), its twin in
`bot/handlers/timetable.py` on the bot's single-day paste, the button editor's handler, and
`timetable_edit.edit_lesson`, where that editor meets the database. The phone has no lesson
editor: `edit_lesson`'s only caller is `bot/handlers/editor.py`.

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
input changed, and that it is closed on Linux/3.12; the four packages deliberately absent
from the bundle (`uvicorn`, `aiosqlite`, `alembic` and, since #170, `tzdata`) are listed there
with their reasons.

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

* **The commit history keeps its Russian, by decision.** Of the 242 commits `main` held
  before #51, 41 carry Russian prose outside quoted product strings — about 2,055 characters,
  mostly in merge-commit bodies. Rewriting them means rewriting every hash from the first
  affected commit and force pushing `main` and `dev`: every existing clone breaks, the merge
  references in the pull requests stop resolving, and the release tags move. The owner
  decided not to. So a `git log` older than #51's move into English reads in two languages,
  and that is expected rather than missed.

**One thing the documents' sweep of 19 September 2026 found and did not touch.** Five test
functions on the server carry a Russian
word in their names — `test_a_day_with_one_maximum_length_задание_still_sends` and four like
it, in `test_bot_message_limits.py` and `test_bot_manage.py`. The rule says identifiers are
English; it also says a quotation of what the user sees keeps its Russian, and an identifier
has nowhere to put guillemets, so these sit between the two halves of it. Renaming them is a
code change and would have to pass the gates, which a documentation sweep does not run — so
it is written down here rather than done quietly. Nothing else in `server/` has a Cyrillic
identifier. In `android/`, five Kotlin test names carry Russian, each a quotation of product
text in guillemets, which a backticked name can hold (`DiaryCatalogTest`,
`DiaryPickerViewModelTest`, `AppIconRowsTest`, `AppIconScreenTest`, `FormatLengthTest`); no
other Kotlin identifier has any.

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
hold against somebody with push access too (`docs/build.md`, «What else being public
changes»).

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

**Let a session fetch Buf 1.73.0, or regenerate the contract yourself, for #401.** #401 corrects
`watch.proto`'s comment on a newer stream ending the older one, and a proto edit has to be
committed with what `buf generate` writes into `server/app/contract/`, which is never edited by
hand. Buf is not installed where the batch of 17–18 October ran, and fetching a binary onto that
machine was left to you; `docs/build.md`, «The v2 contract and Buf», says how it is fetched.

**Optional: give the Postman collection a device token.** The second folder of «lessons — API
smoke (read-only)», «With a device token», skips itself until the environment «lessons —
production» has a **current value** for `deviceToken`; type one there from a phone joined to
a class, and leave the initial value empty so that it stays on that machine. The collection has
never been run in Postman, so the first run is also its first test.

**Walk the launcher icon once on a device or an emulator.** An API 31 and an API 34 emulator
are enough: switch the icon twice, press Home, open Recents, then tap the widget from a cold
start. Section 5 has the list it starts on. Look at one more thing on the way: install a build
from before #403 (`00fb20e`), choose one of the styles it took out, then update to a build of
`main` signed with the same key. The icon should come back as «Классика · Мята», by the
reconcile. Nobody has watched that happen yet.

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

**Next for the programme: the open bugs marked `status:next`, then the 152-ФЗ work and
sub-project 4's C, in the order you choose.** Sub-project 3 is complete (#396), its
live-verification gate ran on the night of 10–11 October, and #406 (#422), #428 and #430
followed it. Sub-project 4, the Android decomposition per
`docs/specs/2026-10-05-android-decomposition-design.md`, has two of its three pull requests in
`main`: A, #451, merged as `8564b09` on 16 October 2026, and B, #625, merged as `81a6201` on 17
October, `ManagementViewModel` and `SettingsViewModel` split into collaborators. Your order of 11
October put the work that lets any deployment meet 152-ФЗ without changing the code, epic #438
on milestone 12, between A and the rest. On 17 October 2026 you said «продолжай с PR B
подпроекта 4» («continue with sub-project 4's PR B»), which put B ahead of epic #438, and on 18
October, offered the bugs, pull request C, the 152-ФЗ work, or the bugs and then C, you chose
«Баги status:next». The night of 17–18 October closed four bugs (#368, #352, #357 and #384),
is closing #400 with this file's pull request, and filed #654, open and `status:next`. Neither
answer placed C, so the order
of 11 October still stands for C until you say otherwise. After the bugs, as it stands:
1. #435's design, option C, as `docs/specs/2026-10-11-data-in-russia-design.md` with your
   choices and the open questions for a lawyer, and its plan; then #407's recipe for it — the
   host target on RUVDS with PostgreSQL on the same VPS, and the bot's Telegram calls through
   an SSH tunnel to a machine abroad. #407 is open on milestone 11, with nothing written for it
   yet.
2. #431–#434 and #436–#437: the consent, the retention terms, the invited phones, the diary
   sign-in on the diary's own side, the legal texts as a template, and the guide for whoever
   deploys. Epic #438 and all seven are open, read on 18 October 2026.
3. Sub-project 4's pull request C — `OnboardingScreen`, with `OnboardingPromisesTest`'s path and
   anchor; `FloatingToolbar`'s split by concern; and `HomeShell`'s pure rules, with its body
   left inline. Nothing is written for it yet.

**Confirm or reject #609's one deviation: «Размытие под шторкой» stays pressable under battery
saver.** #609 (#476) turned the ripple, the four motion blurs, the progressive blur and the
theme reveal off under battery saver, without writing any stored setting, and three rows —
«Размытие при прокрутке», «Волна» and «Круговая смена темы» — read off, cannot be pressed, and
say «Выключено, пока включён режим энергосбережения». «Размытие под шторкой» does not, because
its switch also draws the edge wash (`showGradientOverlay = edgeBlur`), which goes on drawing:
it keeps its stored value, and while it is on it explains the missing blur, under battery saver
and on Samsung's Android 15 and earlier; while it is off it promises nothing. The reasoning is
in a comment on #476. If you would rather it read off like the other three, the wash would need
a switch of its own first.

**#445's wording is yours before it is built.** You asked for it on 11 October, after #444 turned
out to be Android's battery saver switching the edge blur off as designed. On 17 October it was
rewritten as a feature built on #476 («A feature, not a fix»), and #476 is done (#609): #445
gives power saving a category of its own, proposed as «Энергосбережение», with «Следовать режиму
энергосбережения», on by default, «Экономный режим всегда», off by default, and each effect's
state. It is on milestone 12, and the order above does not place it. The wording on #445 is a
proposal for you to adjust.

**A session merges its own pull requests in auto mode.** Your
standing instruction of 20 September is that a session merges its own pull request after the
five checks of the `github-pr` skill. Auto mode's classifier refuses that merge, and on 17
October it refused, as self-modification, the session's edit of the user settings that would
have added an `autoMode.allow` rule for it. You pasted the rule yourself the same day, so a
session merges its own pull request after the five checks, pinned to the head it checked.

**Delete three leftover worktree folders.** `lessons-476`, `lessons-427` and `lessons-deps`,
beside the repository's own clone, if they are still there. Git has already deregistered them as
worktrees, so they hold build output only, and nothing a session needs.

**Review the diary sign-in design on `pending/recovered-designs` before #434 is built.** The
branch holds one commit, `3c60612` of 8 October, and no pull request: the design for signing in
on the diary's own side, whose sections 1–5 you approved on 5 October and whose written text
waits for your review, beside the notes on the languages of Russia. It is the only copy of
both, which is why the branch cleanup of 16 October kept it rather than merging it as if agreed
or deleting it. Two things in it are reconciled when it is picked up: whether its new
`SIGN_IN_METHOD_PROVIDER_PAGE = 3`, the next free value, is needed beside
`SIGN_IN_METHOD_SESSION_ADOPT = 2` in `proto/lessons/v2/diary.proto`, which already names a
session the phone opened elsewhere; and its milestone, v0.9.0, against #434's, v1.0.0.

**The project is impersonal open source, by your position of 11 October**: the repository
names no operator, and whoever deploys a server is its operator. #436 makes the legal texts a
template every deployment fills in, and #437 is the guide its operator reads.

**#435 is decided: where a deployment keeps its data is its operator's choice, and option C is
the Russian one**, both recorded on the issue on 11 October. The issue does not choose one place
for everybody; it makes three available, and an operator picks one in the build console's Deploy
tab (sub-project 6):
- Vercel + Neon, as documented today, with the data abroad;
- C, for an operator who keeps the data in Russia;
- A, the database alone in Russia, as a stopgap.

The design and #407's recipe describe C without presenting it as the project's own deployment.
In C the core runs on a Russian VPS, RUVDS, with PostgreSQL on the same VPS, and the bot's calls
to Telegram go out through an SSH tunnel to a machine abroad, which sees only encrypted bytes;
option B is C with that egress setting left empty. On RUVDS the egress is required: the same
day, the VPS resolved `api.telegram.org` but could not open a TCP connection to it — three
HTTPS attempts each timed out after 15 s — while it reaches Petersburg's diary in about 0.2 s.

**#406 needed nothing set, and production reads it back.** `DIARY_EGRESS` is best left unset
in Production and Preview alike: on `6354c3d`, `GetDiaryCapabilities` answered that Petersburg
and eleven «Сетевой город» regions go through RUVDS and five direct. «📊 Проект», read at
17:02 Moscow on 11 October, had all four checks ✅, the proxy answering in 450 ms, and no
region line, because no region is in use. When one is, a region that reads 🔴 says on its
second line what to do; one that leaves use while failing simply disappears, which is not a
recovery (`docs/deploy.md`, «Monitoring»).

**The privacy policy and the terms are at edition 5** (#430, which closed #429), effective
2026-10-11, the day it merged. Your brief for a lawyer, written on 11 October 2026 and kept
outside the repository, became a rewrite of both texts by another session: the shape 152-ФЗ
asks a published policy to have, the eighty-nine regions' diary systems, the sign-in that
happens in the diary's own system, and the proxy's route stated as the fixed table it is. That
last answers your question after #422 about «если из страны, где работает сервер, дневник не
отвечает». Edition 5 keeps edition 4's Sentry point (#425). Two commitments in it are the
operator's rather than the code's — answering a request within the statutory ten working days,
and reporting a breach — and the header comment of `privacy.ru.md` names both, so a fork can
keep or strike them. #430 asked you to confirm both before its merge, and nothing in the
repository records whether you did. The RUVDS point now says the proxy writes the diary's
address and the time to its log, which is true while RUVDS's `squid.conf` keeps `access_log` on
(line 27, read at 09:06 UTC on 11 October); switching it off makes the clause «и записывает их
в свой журнал», and its English twin, untrue, to be taken out with another edition.

**The development machine's RAM is still faulty.** It crashed twice on 11 October — at 11:05
Moscow with a double fault (`0x7F`), and at 12:49 with `0x1A` `0x41792`, the faulty-RAM
signature, which zero-filled this worktree's git index in the middle of a commit (recovered;
«After #418's merge», in `docs/history.md` now, has how). Your fix is to pull the unbranded
DIMM pair and run a memtest on what remains. Until then a session runs one heavy job at a
time, and scans for zero-filled files after a crash; every Gradle run there carries
`--max-workers=2` since #451 wrote it down (`docs/build.md`, «Two workers, on the
maintainer's machine»).

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

**«CI» is the one required check on `main`** (#424, #428), in place of «Host (pyvoy)» and
«Host (hypercorn)» by name, which would have left every pull request that changes only
`android/` waiting for ever: a skipped matrix is reported under its unexpanded name
(`docs/build.md`, «The check `main` requires»). The job needs every gate and passes only when
each succeeded or was skipped. The ruleset that requires it, 24883111, «main: «CI» must pass»,
was created by the session right after #428's merge, with your permission («попытайся сделать
сам»): active on the default branch, the check «CI» from GitHub Actions, the repository admin
role able to bypass it, and strict mode off, so a branch need not be level with `main`.

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
the other nine the same day. Its description is behind: it names PR #140 and issues
#135–#139, #141 and #145–#159, while #160–#165, the external audit's #190–#211 of 27
September, #212, #213, #235, #236, #302, #334 and #343, and PRs #214, #218, #303 and #335 are
on it too. The ninth's is further behind — it names PRs #129, #133 and #134 only. A
milestone's description is the owner's to edit; nothing in a session here can.

**Build an APK with the developer mode, and open it once on a phone.** The mode (#237) opens
through «Войти через GitHub», which a build without `LESSONS_GITHUB_CLIENT_ID` hides
(`docs/build.md`). That secret and `LESSONS_CONTACT_EMAIL` have been registered since 21
September 2026 (#461), so every «APK» run since should carry the button. The last run of
`main`, on 1 October, built `eb0ab94`, the merge of #214 on 27 September, from before the mode
merged (#238, 2 October), so no APK from a run of `main` holds the mode yet; run #41, the
branch build the gate of 10–11 October installed on the emulator, is no longer listed. Nothing
records a run summary read since the secrets were set, the button seen on a phone, or the
device flow completed with a real account. Run «APK» from `main` and read its summary for
«on», then, on a phone: seven taps on the version, sign in as an account with
push to this repository, and run the checks once from a phone's own network — the first
answer to whether Petersburg's host answers a Russian mobile network at all.

**The Petersburg account needs a second factor (#343).** Its password is right, and the diary
asks for a code by SMS or MAX, which the app has no step for. Change the password once #343 is
decided.

**Sign in once, for real, from a phone — it is the one question #140 cannot answer about
itself.** Build an APK from `main`, which has carried #140 since 26 September, install it on
a phone in no class, and take the diary path twice: a «Сетевой город» region that takes a
password, and Петербург. Whether the import arrives, or the phone says the diary refused the
session from the server's address — the `409` — decides whether the phone-registered path
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
`docs/legal/legal.json` is edition 5 since #430 (#429), written from your brief for a lawyer,
and no lawyer has read it; the questions it leaves for one are listed in #430's description.
Every change to what the texts say raises the edition, as the `release` skill says.

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
  set, twenty regions — as many as ТОР «Моя школа» and more than any other platform — and a
  password still accepted outside the regions that allow Госуслуги only. The МЭШ family comes
  after it. Against a live server only its school search has run — the phone's on #186's
  emulator, and the server's own client against four regional servers in the live gate of
  10–11 October, which found #412; its sign-in and its diary have not.
- **How a family signs in** — with its password, typed into the app's own form and sent to
  the diary alone; on that path the session, not the password, reaches the server. Two older
  paths still pass a password through it — the bot's `/diary/signin` page and
  `POST /api/v1/diary/login` (#434). Госуслуги is not driven and no token is carried over
  from a browser, because a Госуслуги session is the person's whole state-services account.
  The three Госуслуги-only «Сетевой город» regions hand off to their own site. That is the
  code's answer, not the owner's latest: on 5 October the owner decided that every sign-in
  happens on the diary's own side, in an in-app browser that keeps only the diary's session
  and never a Госуслуги one — which would open those three regions too — with ТОР left a
  hand-off. The design waits for the owner's review on `pending/recovered-designs`, and #434
  is to build it.
- **Whether ТОР «Моя школа» may be used** — it is not read. The provider screen names it as
  the region's system and hands off to the official site. Nobody has read Госуслуги's terms,
  and nothing needs them read until somebody proposes reading ТОР.

Whichever platform comes next, the first step is one real session against it, as #121 is for
Петербург. Of the routes in `docs/diaries/`, only «Сетевой город»'s school search,
`/webapi/schools/search`, has been seen to work against a live server; no sign-in and no diary
read has, on any platform.

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
«Отправить в проект» checks the first.

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
marks the row dead and sends the person back to sign in again: in the app's own form for a
session the phone holds, or on a page that needs a fresh ticket from the bot for one the bot's
sign-in page opened. That is a real, user-visible expiry bought against a narrow threat, since
the blob is worthless to anyone holding the dump but not `DIARY_SECRET`. If a bound is wanted,
the cheaper one that cannot misfire is an age check on `last_used_at` in `find_session`: our
own clock over our own facts. The tick already applies a coarse one: `_purge_diary_sessions` in
`api/cron.py` deletes a session nobody has used for thirty days (`DIARY_SESSION_TTL`, on
`last_used_at`, which the keep-alive never touches). **Today that sweep and the row's own
lifecycle are the only bound**, and whether they are enough is the thing to decide rather than
the ttl.

Items 1, 1a and 1b of this list are done — the last of them, reading `/api/v1/warmup` and
sending the bot `/start`, on 26 September 2026 — and so is item 5, the correction mode's walk,
which #186 made on an emulator (#115). All four are in [docs/history.md](docs/history.md),
«Moved out of section 7 on 27 September 2026», with four paragraphs this section no longer
needs.

2. **Build the APK from `main` and install it on a phone.** See section 5 — it is the only
   way to check what nothing currently checks, and doubly so after the build-chain bumps: AGP,
   Gradle and compose-bom in September (section 5), and Gradle 9.8.0 and 9.8.1 and Kotlin 2.4.21
   since.
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
4. **Open «Настройки → О приложении → Лицензии и благодарности» in the app** and look at the
   eighth and ninth rows — Google Sans Flex and Onest (#326), the two faces the app is set in.
   They are built and they compile, but nobody has seen them with their eyes: what is of
   interest is how the four rows past the sixth sit on the hues they share with the rows above
   them (ten rows over six hues; the eighth takes Compose's).

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

`dev` is still the branch the `github-pr` skill names, but no pull request has come from it
since #267 on 3 October, and it has stood at #342's merge since 5 October: every batch since
has worked on a branch of its own, cut from `main` and named for its work (`server-v2/3b-3`,
`android/decomposition-a`, `deps/2026-10-16`). Either way a batch starts from a fresh `main`,
because a merged pull request accepts no new commits; the commands below do it for `dev`, and
`git checkout -b <area>/<topic> origin/main` does it for a branch of its own. Which pull
requests are merged and which one is open is at the top of this file, not here — this
paragraph is about the two commands under it.

```bash
git fetch origin
git checkout -B dev origin/main   # the same dev, a new starting point
```

The gates, both halves (`CLAUDE.md` requires running both if you touched both):

```bash
cd server  && ruff check app tests scripts migrations   # clean
cd server  && pytest -q -n auto                          # 3366 tests, ~26 min alone on Windows
cd server  && python -m mypy                             # clean, 242 modules
cd android && ./gradlew test                             # 1797 tests across the five modules
cd android && ./gradlew detekt                           # nothing beyond the five baselines
cd android && ./gradlew assembleDebug assembleRelease    # both assembles
```

On the maintainer's machine every Gradle command carries `--max-workers=2` and runs alone:
`docs/build.md`, «Two workers, on the maintainer's machine».
