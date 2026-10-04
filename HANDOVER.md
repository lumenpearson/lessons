# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.
What every batch before the last two added is in [docs/history.md](docs/history.md),
newest first.

Last updated: **4 October 2026**. **PRs #63 through #85, #128, #129, #133, #134, #140,
#166, #186, #187, #189, #214, #218, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261,
#263, #267, #274 and #277 are merged**; `main` is at `746acc3`, the merge of #277, on 4
October 2026; `dev` is still at `b327eb0`, the merge of #267, so it is behind `main`: #274 and
#277 came from branches of their own. **Two pull requests are open: #296, the one carrying
this paragraph, and #294, another session's.** #296 is from `server-decomposition`, on
milestone 11, `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console`. It
is the first sub-project of that milestone's programme
([docs/specs/2026-10-03-one-contract-design.md](docs/specs/2026-10-03-one-contract-design.md)),
the plan of which (#277,
[docs/specs/2026-10-03-server-decomposition-plan.md](docs/specs/2026-10-03-server-decomposition-plan.md))
it carries out: the bot's long modules become feature modules inside their layers, and
the cold start stops importing aiogram. It will close #271, #272 and #275 and refers to #276
and #273. **#294**, «Tell a session where the tracker stands now: thirteen milestones, and a
board it can fill», is not this session's; milestones 12 and 13 exist, and #294 describes them.

The section «What the last session added» below is #296's batch.

The SHA of its own merge is for the next close-out to write.

**#267 closed #264, #265 and #266**, read back from GitHub on 3 October. **#274 and #277
closed nothing**: neither fixed any of the defects filed on milestone 11 (#268–#272, #275,
#276), and #273 is that milestone's epic. #296 is the first to close three of them.
**#235** is open: the production server cannot reach Petersburg's diary at all, and the fix
is the owner's choice of a Russian egress (section 7). **#236**, a phone's sign-in showing
nothing for over a minute, was closed as a duplicate of #233, which #234 had already fixed.
Of the device epic **#109**, **#111** and **#113** stay open for what only a phone can say,
and **#112** (a macrobenchmark module) was not started.

**The code expects head `0017`, and production is at `0017` since 26 September 2026 at 12:26 UTC.**
`EXPECTED_REVISION` in `app/db.py` is `0017`, pinned to the real head by
`tests/test_schema_version.py`. `0015` adds the eight nullable columns the second diary
needs. `0016` creates `usage_counters`, the anonymous school directory's daily count of
DaData requests. `0017` files the diary corrections under the child rather than a login, and
it is data only. All three went on through the Neon connector (the project named `lessons`)
**before** #140's merge, in one transaction; «Schema» in the section on #140, in
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
epic, and #275 and #276 what planning its first sub-project found. #293 and #295, on the
same milestone, are another session's.

Labels are `type:` (feature, bug, chore, research, decision, epic), `area:`, `status:` (now,
next, someday, done) and `needs:` (device, owner). **A session cannot create a GitHub
Project board** — Projects v2 is GraphQL-only and the toolset here is REST — so the board is
the owner's to make, and these labels are what its views filter on.

## What the last session added: the bot's long modules split into feature modules, and the cold start that imported aiogram (#271, #272, #275)

Opened as #296, from `server-decomposition`, on milestone 11, open. It is the first
sub-project of the milestone's programme, the one the plan merged as #277 described. It will
close #271, #272 and #275, and it refers to #276 and #273.

- **The callback census covers all of `app.bot`.** It is the prefix-collision check — no two
  `CallbackData` classes share a prefix — plus the check that every button the class menu
  draws is a packed payload, and it never looked at `editor_keyboard` or `diary_keyboard`; it now walks every module of
  the package and counts 30 payloads where it counted 27 (#271).
- **A fresh interpreter proves aiogram stays off the API's cold start**, in both
  configurations, the bot switched off and on (#272).
- **Every Vercel cold start imported 736 aiogram modules, and no longer does.**
  `app/api/telegram.py` imported aiogram at its top and Vercel always mounts the webhook; it
  now imports it where an update is handled. The comment above the mount had said the
  opposite (#275).
- **The announcement and throttle tests are keyed by object, not by file path**, so a module
  can move without the test going quiet, and five handler-order pins hold the pairs of
  handlers that only registration order tells apart.
- **The corrections laid over the diary live in `services/diary_corrections.py`**, beside
  `diary_overrides.py`, and no longer in `services/diary.py`.
- **Keyboards and renderers are one module per feature.** Each feature outside «⚙️ Класс» has
  its own `*_render.py` and `*_keyboard.py` beside `render.py` (which still re-exports
  `app/wording.py`) and `keyboards.py`.
- **`handlers/content/` and `handlers/start/` are packages**, one module per concern,
  included in one written-down order like `handlers/manage/`.
- **The homework ticks sit beside the homework**, on a router of their own at the tasks'
  old position in the dispatch order, because of #276, which is filed and not fixed here.
- **`manage_render/` and `manage_keyboards/` hold one module per screen**, as the handlers
  they serve do.
- **A final wording pass** made the comments the moves carried say where things are now, and
  pinned each cold-start case to its configuration. In `docs/bot.md`, `clamp` and
  `more_line` are now said to live in `app/wording.py`, which `render.py` re-exports.
- **How it was done.** One fresh implementer and one fresh reviewer per task, thirteen tasks
  and a wording pass. Every move was checked by a syntax-tree comparison and by a diff of the
  177-handler dispatch order. The machine switched itself off once, during Task 1; a scan of
  10,409 files found nothing zero-filled. Task 11's first implementer was stopped by the
  owner mid-task and a second finished from the working tree.

### Gates

At the head before this paragraph: `ruff check app tests scripts migrations` clean;
`python -m mypy` clean, 197 source files; `pytest -q -n auto` 2075 passed in 9 minutes 51 seconds on
this machine on 4 October, against the documented four minutes (`2065` before the batch, ten
more tests since: one for the census, three for the cold start, five order pins and one
more in the ticks' task). The dispatch order, 177 handlers, differs from the first baseline in
exactly the two homework handlers relabelled from `tasks:` to `content:` at the same
positions and the move of the `start:back_root` callback within the start package.

### What was deliberately left alone

- **`api/public.py`, `api/diary.py`, `api/edit.py`, `models.py`, `schedule.py`, the providers
  and Android are not split**: v2 replaces the API's three, and the others are long but
  cohesive.
- **#276 is filed and not fixed**: aiogram's `Command` reads `/week@` as a command and
  `CommandBreakoutMiddleware` does not. The ticks keep their place in the dispatch order
  for it; once it is fixed they can join `content`'s router.
- **Revision `0017`'s text stays as the record** of the key it files corrections under.
- **No old name is re-exported**, except `render`'s wording names. `ruff format` would
  reformat seven files in the new packages; it is not a gate and it was not run.
- **Milestones 12 and 13 exist**, created by another session; its pull request, #294,
  describes them, and this file's milestone table does not.

### What nobody has verified in this batch

- **The cold-start fix on Vercel itself.** The 736 modules were measured on Windows, in a
  fresh interpreter, under Vercel's settings; no deployment has been read.
- **Anything on a device.** Nothing here touches the phone.
- **The whole-branch review has run.** The final review, on the most capable model over all 13
  commits, approved the branch with three small fixes (two stale test counts, three HANDOVER
  sentences, one comment in `manage_render/__init__.py`), which the last commit makes. It
  re-checked the dispatch order against the first baseline, that every moved function binds
  the same objects, the import graph, the monkeypatches' reach, the census and the cold start
  (736 → 0 aiogram modules).

## What the session before it added: the spike's answers, and the plan for the server decomposition (#275, #276)

Merged as #277 (`746acc3`, 4 October 2026), from `plan/server-decomposition`, on milestone 11, after the owner's review
of the plan. The same session as #274, after the owner approved the design and created the
milestone: «майлстоун создал, утверждаю».

- **The spike ran and was thrown away.** On the owner's machine and the API 37 emulator, from
  the local branch `spike/connect-grpc` (never pushed; the worktree is
  `.claude/worktrees/agent-ab1fed3b13df7822c`). Everything it answered was run: Buf's remote
  plugins without a login, `connectrpc` mounted inside the real FastAPI app under Uvicorn's
  HTTP/1.1 with every existing route unchanged and 61 ms more cold import, the
  `google.api.http` annotation read at runtime by a sixty-line transcoder, native gRPC from the
  same app under `pyvoy` and `hypercorn`, and `connect-kotlin` 0.9.0 under AGP 9.4.1 and
  Kotlin 2.4.20 calling all three protocols from the emulator, minified included. The design
  gains «What the spike found», and every passage that waited on it now says what it decided.
- **Three things the spike found that the design now answers**: `connectrpc` turns a native
  gRPC request under HTTP/1.1 and an undecodable body into a `500` with a traceback; the
  minified app fails at runtime without a keep rule for `GeneratedMessageLite`; and the release
  APK grows by 611 KB, +13.8 %, most of it `kotlin-reflect` at 2.2.21 against a 2.4.20
  standard library.
- **The plan for sub-project 1**, `docs/specs/2026-10-03-server-decomposition-plan.md`:
  thirteen tasks, guards first — the callback census over all of `app.bot` (#271), a
  fresh-interpreter cold-start test in both configurations (#272), the path-keyed tests made
  symbol-keyed, the three pairs of handlers only registration order tells apart pinned — then
  the moves, each checked by a syntax-tree comparison and a diff of the dispatcher's
  177-handler order. Drafted by a planning agent and reviewed here; its two placeholder issue
  numbers were replaced by the real ones.
- **#275, filed before its fix, which the plan's Task 2 is: every Vercel cold start imports
  aiogram.** `app/main.py` imports `app.api.telegram` whenever the webhook is enabled, which on
  Vercel it always is, and that module imports aiogram at its top. Re-measured here before it
  was filed: 736 aiogram modules and 3.07 s for `import app.main` under Vercel's settings. The
  comment above the mount says the opposite.
- **#276, filed, not fixed in this sub-project**: aiogram's `Command` reads `/week@` as the
  command `week`, and `CommandBreakoutMiddleware` does not, so a form step takes it as its
  answer. Checked in the venv before it was filed.
- **On the emulator, the spike's agent made one mistake, which it reported.** It tried to
  install over the owner's `com.lumenpearson.lessons` (versionCode 32); the install was refused
  as a downgrade, so nothing changed, but it then launched that app three times and
  force-stopped it each time. It also restarted `Pixel_10_Pro_XL` with `-memory 3072
  -no-snapshot` after emulator-5554 had gone, uninstalled its own packages, removed its
  `adb reverse` rules and shut the emulator down. The design's console now says it never
  installs over the owner's app.

### Gates

No code changed. The eight server test modules that read the documents pass against this
branch; the full gates are CI's on #277.

### What was deliberately left alone

- **The plan is not started.** It waits on the owner's review and on the choice of how it is
  run.
- **#276 is not in the plan's scope**; the plan keeps the homework ticks' place in the dispatch
  order because of it, so fixing it later changes nothing the plan moves.

### What nobody has verified in this batch

- The plan as a whole: its commands were checked inline while it was drafted (the census, 30
  payloads against 27; the walker, 12 functions; the order dump, 177 handlers; the cold-start
  probe, 736 modules), not run end to end.
- Vercel's own proxy in front of a deployment, and Buf's rate limits in CI — the spike could not
  try either.

## The milestones

**The milestones as they are now.** The owner renamed all nine on 25 September 2026, so that
every title names what its version delivered and every description names its pull requests
and issues, and created the tenth the same day. The older batch sections, in `docs/history.md`
now, quote the titles of their own time, which a search no longer finds; the third column
maps them. The
`github-pr` skill carries the same table.

| # | Title | Called before 25 September | State | Covers |
| --- | --- | --- | --- | --- |
| 1 | `v0.1.0 — App, widget, admin bot and read API` | `v0.1.0 — First run on a phone` | closed | PRs #1–#14; issues #86, #88, #89 |
| 2 | `v0.2.0 — Petersburg e-diary, class run from bot and phone` | `v0.2.0 — The diary, and the class run from the bot` | closed | PRs #15–#17, #28–#31; issues #87, #90, #91, #102 |
| 3 | `v0.3.0 — School year, terms, school search, several classes` | `v0.3.0 — The school year` | closed | PRs #27, #32–#35, #43; issue #92 |
| 4 | `v0.4.0 — 67-defect sweep, first audit, app-wide correction mode` | `v0.4.0 — Nothing breaks in silence` | closed | PRs #44, #45, #50; issues #93, #94 |
| 5 | `v0.5.0 — Public repo: secrets audit, English docs, font licence` | `v0.5.0 — A public repository` | closed | PRs #46–#49, #51, #55–#57, #59; issue #95 |
| 6 | `v0.6.0 — Dishka DI, scrolling text, in-app guide from the repo` | `v0.6.0 — One container, and nothing cut off` | closed | PRs #60–#74; issues #96, #97, #99, #101, #103, #104 |
| 7 | `Dependencies — dependabot bumps` | `Dependencies` | open, for good | every dependabot bump; deliberately not a version |
| 8 | `v0.7.0 — School-year calendar, day ribbon, rearrangeable tabs` | `v0.7.0 — Оптимизация` | closed | PRs #75–#85, #128; issues #98, #100, #105–#108 |
| 9 | `v0.8.0 — On-device checks, 89-region e-diary survey` | `v0.8.0 — On a device` | open | PRs #129, #133, #134, #186, #187, #189, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261, #263, #267; issues #109–#117, #130–#132, #167–#185, #188, #219–#233, #237, #240, #242–#244, #246, #247, #249, #251, #253–#266 — the first whose work needs an emulator or a phone, and #186 the first done on one |
| 10 | `v0.9.0 — NetSchool e-diary, onboarding via the school's diary` | none — proposed as «v0.9.0 — A second diary», never created under that name | open | PRs #140, #214 and #218 (merged); issues #135–#139, #141, #145–#165, #190–#211 (the external audit of 27 September), #212, #213, #235, #236 |
| 11 | `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console` | none — created on 3 October 2026 under this name | open | PRs #274, #277, #296 (and #294, another session's, open); issues #268–#273, #275, #276, and #293, #295 (another session's) — the programme of `docs/specs/2026-10-03-one-contract-design.md` |

**#142, #143 and #144 are on no milestone, deliberately**: two follow-ups and a decision that
#140 left alone on purpose, which belong to whichever version takes them up.

**Nothing in a session here can create a milestone**, only attach one — the owner created
the ninth on 22 September 2026, the tenth on 25 September and the eleventh on 3 October. **The ninth was the first
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

- **The cold-start fix (#275) has not been read on Vercel.** The 736 aiogram modules were
  measured in a fresh interpreter on Windows under Vercel's settings, and
  `tests/test_cold_start.py` holds the answer there; no deployment of this branch has been
  asked how long its first request takes.
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
- **The «Сетевой город» provider has never met a live server.** #140 wrote the client, the
  mapper, the keep-alive and the school search from open-source clients and hand-written test
  payloads; no ИРТех server answers a cloud session, so none was called. Every upstream shape
  — the salted-MD5 sign-in, `/webapi/context`, the weekly diary, the school search — is what a
  client sends, not what a server was seen to accept. The refresh-token grant (LoginType 9)
  and the Госуслуги refusal path (`SignInUnsupported`) are the least exercised of all. The
  first live sign-in on it is the owner's, #121's caveat a second time.
- **The phone's own sign-in has never met a live diary either — neither «Сетевой город» nor
  Петербург.** The Kotlin ports and the Python originals run one set of known-answer vectors
  (`server/tests/vectors/diary_protocol.json`), which proves the two agree and nothing about
  whether either is right. Whether a Петербург pupil's own account lists itself as a pupil,
  and how long its token lives, are unknown for the same reason.
- **Nobody knows whether a diary accepts a session opened on a phone when our server replays
  it.** The phone signs in from the family's own address, and `POST /api/v1/diary/session`
  then reads with that session from Frankfurt. A diary that ties a session to its address, or
  refuses foreign ones, answers that read with a refusal, and the server turns it into a
  `409`, meaning the diary refused the session from our address — by design, with no automatic
  retry. If that is what the first live session gets, the phone-registered path does not work
  for that diary and only the password routes remain. Whether «Сетевой город»'s four bootstrap
  calls fit inside Vercel's 30-second ceiling is unmeasured too. **For Петербург it is worse
  than a refusal, and it was measured on 2 October 2026 (#235):** the city's network does not
  answer Frankfurt at all, so the read never gets as far as judging the session. The server's
  connect timeout turns it into a `503` «upstream» in about six seconds — which the phone
  words as «Дневник не отвечает» — for every family, every time, until the diary traffic
  leaves from a Russian address. «Сетевой город»'s regions were not asked from Frankfurt.
- **#140's phone half has run on an emulator, and only as far as a diary without a
  password goes.** Nothing composes `LessonsApp` in a test, so the gate between the three
  homes, the hold on the first run and the resume after the process dies are still proved by
  the pure rules and the view models only. #186 walked the first run both ways in, in both
  languages: the legal documents online and the bundled fallback offline, the region list, a
  Госуслуги-only region's hand-off, ТОР «Моя школа»'s, a live school search and the password
  form. Nobody has heard any of it through TalkBack, and `diary.db` has not been written,
  because no password was typed.
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
  composes.
- **DaData's side of the school directory is assumed.** Whether its company rows carry
  `region_kladr_id` is unknown — the directory falls back to the region's name for exactly
  that reason — and so are the subject codes of the four regions admitted in 2022 (90, 93,
  94, 95), every DaData spelling in the catalog's overlay, and whether DaData's day turns at
  Moscow midnight, which is when `usage_counters` starts a new one. Two requests racing for
  the last unit of the day were checked on SQLite only.
- **The terms of use and the privacy policy have never been read by a lawyer.** They
  describe what the code does and claim compliance with nothing: whether naming a GitHub
  account as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line
  and parental consent are open.
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
- **`docker compose up` has never been run.** There is no Docker in this environment, so
  what #83 proves about the new `migrate` service is that the compose file parses and that
  its dependency conditions are what they claim — `service_completed_successfully` before
  the API starts. Nobody has watched the stack come up, nobody has seen
  `alembic upgrade head` run inside the image, and nobody has confirmed that the revisions
  the Dockerfile now copies are the ones it needs. #190, #191 and #195 changed the file and
  the image again — a password the stack refuses to start without, every setting passed
  through, a non-root user — and are read by `test_compose.py` and `test_dockerfile.py`,
  not by a container.
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
- **Dependabot's `uv` entry (#192) has not opened a pull request yet**, so the way a bump
  regenerates the lock is read from dependabot-core rather than seen. The lock itself was
  installed on Linux and Python 3.12 by #214's CI, which ran the suite on it, and Vercel
  built #214's preview from it; the local runs were all Windows and Python 3.13.
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
  rendered and compared with no device. Not done.
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
  refusal to apply a correction on a key collision was made for.
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
  instead of their class — and nothing in the logs.
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
  same stub, and so does its daily count.
- **The real dnevnik2 has never been opened**, neither the sign-in — the server's or, since
  #140, the phone's own — nor any of the four screens.
- **Not one server-side fix has been run against a live class.** `claim` is verified by
  claiming twice on one date, but nobody has run two ticks at once; the export's round trip
  is verified by computing both sides, which is all it claims.

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

Corrections live on the account's login rather than on the diary's session: the session dies
every few days, and a correction that went with it would disappear by itself before anybody
pressed "reset".

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
environment, #120 the external cron and `DADATA_TOKEN`, #121 the real diary, #122 the
widget's tick cadence and the diary credential's bound, #135 the second diary, #144 a default
server for a fresh install. Each carries the label `needs:owner`. #119 (`/api/v1/warmup` and
`/start`) and #156 (the backup) were answered by #186 and closed at its merge on 26 September
2026.

**Keep some space on C:.** It had about 1.3 GB left early on 27 September, and 14 GB when #187
began, the same night; its builds and one emulator boot left 12 GB, and #189's left 9.4 GB,
so each batch with a device in it costs two or three. The emulator refused to
start once below 2 GB, and the AVD's Quick Boot image alone is 8.5 GB. The worktrees under
`.claude/worktrees/` each carry their own Gradle build directories. On 2 October it had
4.5 GB at the start of #241's session and fell to 3.0 GB by #245, and #245 gave it room:
8 GB moved to `F:\MovedFromC` or deleted as a rebuildable cache, listed in #245's section,
for 11.3 GB free. Both AVDs live on F: (`Pixel_10_Pro_XL` 12 GB, `Release_Check` 5 GB).
`F:\MovedFromC` is yours to keep, put back or delete.

**Restart Android Studio once, when it is free.** Three changes wait for it, because the IDE
rewrites those files on exit: `server/.venv` as the Python SDK, the root module as a Python
one (which quiets «Unsupported Modules Detected»), and the third-party «Python Portable»
plugin disabled, since it fails to load on every start. Everything else in the IDE's set-up
is already in place (see «…the first walk of the app on a device», the section on #186, in
`docs/history.md` now).

**The tenth milestone exists, and #140 is on it.** The owner created
`v0.9.0 — NetSchool e-diary, onboarding via the school's diary` on 25 September and renamed
the other nine the same day. Its description is behind: it names issues #145–#159, and
#160–#165 are on it too, as are the external audit's #190–#211 of 27 September. The ninth's
is further behind — it names PRs #129, #133 and #134 only. A milestone's description is the
owner's to edit; nothing in a session here can.

**Decide #235: where the Petersburg diary's traffic leaves from.** The city's network does not
answer the server in Frankfurt (section 5), so no family can use Петербург's diary through
this project until the server's diary requests leave from a Russian address. The smallest fix
is a password-protected HTTP proxy on a small Russian VPS and an optional `DIARY_PROXY_URL`
that only the diary clients use; the code is a session's work once a host exists, and the
host is the owner's to rent. Moving the whole server to Russian hosting would also settle the
152-ФЗ question below. The issue has the three options.

**Decide where milestone 11's second host lives, when it is needed.** The design
(`docs/specs/2026-10-03-one-contract-design.md`, section 2) adds a long-running target beside
Vercel for native gRPC and the streaming beta, packaged as a `Dockerfile` for Cloud Run,
Fly.io or a VPS, and deliberately leaves the place open. It is not needed before sub-project
3, and until then that target is only ever run locally. A host inside Russia would also be
the egress #235 is waiting for, so the two decisions may be one.

**Give the APK a GitHub client id, or the developer mode stays shut.** The mode (#237) opens
through «Войти через GitHub», which a build without `LESSONS_GITHUB_CLIENT_ID` hides
(`docs/build.md`). Then, on a phone: seven taps on the version, sign in as an account with
push to this repository, and run the checks once from a phone's own network — the first
answer to whether Petersburg's host answers a Russian mobile network at all.

**Sign in once, for real, from a phone — it is the one question #140 cannot answer about
itself.** Build an APK from `main`, which has carried #140 since 26 September, install it on
a phone in no class, and take the diary path twice: a «Сетевой город» region that takes a
password, and Петербург. Whether the import arrives, or the phone says the diary refused the
session from our server's address — the `409` — decides whether the phone-registered path
works for that diary at all. It is #121's first live session, for both diaries at once. For
Петербург the answer is known before the phone is picked up: «Дневник не отвечает», from the
server, until #235 is fixed.

**Then walk the rest of the first run on that phone.** The class code as the other way in;
leaving the last class with a diary signed in, which should land on the diary as the home
with «Выйти из дневника» still in settings (#151's path). The legal links, the ТОР hand-off
and the backup were seen on an emulator in #186; a phone adds the thumb, not the answer.

**Decide #144: whether a fresh install gets a default server.** The diary path needs our
server to register the session, and today a fresh install asks for its address where it is
first needed, because no address is compiled in and none is promised any more (#154). A
default is a build property and a decision about who that server serves; it is the owner's.

**Have the terms of use and the privacy policy read before anybody relies on them.** They
describe what the code does and claim compliance with nothing: whether a GitHub account named
as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line and parental
consent are questions for a lawyer. Each passage about this deployment sits under a `FORK:`
comment. Nothing has been published, so `docs/legal/legal.json` is still edition 1; once
the texts are out, every change to them raises it, as the `release` skill says.

**`DADATA_TOKEN` now serves a second caller (#120).** The anonymous school directory spends at
most 4,000 of DaData's 10,000 daily requests. Without the key it answers `503` with
`X-Directory-Unavailable: disabled`, and a family still finds its region in the bundled
catalog — only the search by a school's name is gone.

**The keep-alive is only as alive as the cron (#120).** «Сетевой город» sessions are held
open from `GET /api/v1/cron/tick`, so they lapse if the external cron does not tick;
`.github/workflows/reminders.yml` is the fallback, not the clock, exactly as for the digests.

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

**Decide what the Preview environment is for, because today it is a red herring.** All
eleven of the project's variables on Vercel are scoped to **Production only**, so every
preview deployment — one per push to `dev`, which is one per pull request — dies while
importing `app.db` and answers `500` to every request. The build is green, Vercel comments
«Ready» on the pull request, and the link leads to a function that refused to start. That
refusal is `DeploymentNotConfigured` doing precisely its job: nothing is touched, no
connection is opened, no webhook is registered, and no GitHub check turns red. It cost an
export of the runtime logs to establish, which is why `docs/deploy.md` now says it in the
variables section. Two honest ways out, and **copying Production's values across is not one
of them** — that points every branch at the real database and hands a throwaway deployment
the real bot token, and Telegram gives its updates to whoever registered the webhook last.
Either give Preview its own set (a Neon branch, a second BotFather bot, its own secrets) or
turn Preview deployments off in the project's Git settings; nothing here is a web page, so
there is nothing for a preview to show. Only the owner can do either — this session can read
which keys exist per environment but must not create them.

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
3. **Make sure `DADATA_TOKEN` really works** on Vercel (Production and Preview; it is the API
   key, not the secret one).
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
cd server  && pytest -q -n auto                          # 2075 tests, ~4 min on CI, ~10 on Windows
cd server  && python -m mypy                             # clean, 197 modules
cd android && ./gradlew test                             # 1635 tests across the five modules
cd android && ./gradlew detekt                           # nothing beyond the five baselines
cd android && ./gradlew assembleDebug assembleRelease    # both assembles
```
