# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.

Last updated: **24 September 2026**. **PRs #63 through #85, #128, #129, #133 and #134 are
merged**; `main` is at `fa4fe0c`, the merge of #134. `dev` is still at `a43c6e8`, the merge
of #129, because #133 and #134 each went in from a branch of their own. It is behind `main`
and has nothing `main` lacks, so bringing it level is a fast-forward. **The only thing open
is the pull request carrying this paragraph, #140**, a **draft** from
`claude/school-diary-api-routes-cc4o1n`. It closes #136, #137, #138 and the feature epic
#139. It **cannot merge yet**: the owner chose a new — tenth — milestone for it, e.g.
«v0.9.0 — A second diary», and nothing in a session here can create a milestone, so it stays
a draft with **no milestone** until the owner makes it. The SHA of its own merge is for the
next close-out to write.

**Code moves again, after four documentation-only batches.** #128, #129, #133 and #134
changed documentation and agent configuration only, so «nothing in the code has moved since
#85» stood through all of them. #140 ends that: it adds a second electronic diary, «Сетевой
город» (ИРТех NetSchool), behind a provider-neutral seam under `server/app/providers/diary/`,
and Петербург goes behind the same seam byte-for-byte unchanged. Two new provider packages
joined and server files across the api, bot, services and models changed, so the server gates
were **run**, not quoted (below), and a migration was written (`0015`) that is **not yet
applied**. The Android app was **not** touched — it stays Petersburg-only until batch 2, the
owner's «server first» decision.

**Once #140 merges, four issues close (#136–#139) and twenty stay open.** **#109** is the
epic and **#110–#117** are its children on `v0.8.0 — On a device`. **#118–#122** are the
owner's alone, and #123–#127 are `status:someday`. **#135** — the owner's decision about
which diary comes second, how a family signs in, and whether ТОР may be used — **stays
open**: this batch answers its first part (it is «Сетевой город», and it takes a password)
but leaves the Госуслуги and ТОР questions to the owner. #136, #137 and #138 are the three
diary defects this batch fixed, and #139 is the feature epic it delivers.

**Two things changed about how this project is tracked, and they are the reason to read on
before planning anything.**

**The work moves to a local machine.** Everything up to #85 was built in a cloud session
with no emulator, no `adb` and no device. The section «Where the work happens from here»
below says what that unlocks and what it still cannot do; **#109** is the epic.

**The repository has issues now, and until #128 it had none at all.** Before that, the
milestones were the only grouping the history had. Forty-two issues were opened in one go:
**#86–#108**, closed, for what was built and what each bug sweep found, and **#109–#127**,
open, for the whole of what was left. Several of the open ones record a decision *not* to do
something, so that a later session does not re-discover it as an oversight. From #129 on, a
found defect becomes an issue before it becomes a fix. #131 and #132 are the first two filed
under that rule.

**The code now expects head `0015`; the production database is still at `0014`, and `0015`
has not been applied.** `EXPECTED_REVISION` in `app/db.py` moved to `0015` (revision
`0015_diary_provider_columns`, pinned to the real head by `tests/test_schema_version.py`).
It is **additive** — five nullable columns on `diary_sessions` (`provider`, `region`,
`kept_alive_at`, `keepalive_attempted_at`, `upstream_ok_at`) and three on `classes`
(`diary_region`, `diary_school_id`, `diary_school_name`), a `NULL` provider meaning
Петербург — so like every additive revision in this chain it is applied to Neon **before**
the merge. **This is the outstanding pre-merge action:** apply `0015` through the Neon
connector (the project named `lessons`, read the name rather than guessing an id) before
#140 merges; it destroys nothing. The server gates were run rather than quoted: `ruff`
clean, `pytest -q -n auto` **1668 passed** (was 1634), `python -m mypy` clean across 97
modules (`files = ["app"]`, 84 before the two new provider packages). The `0014` chapter
still holds: #83's pinned table of the ten enum column widths fails when a `SAEnum` member's
**name** outgrows its `VARCHAR`, which SQLite cannot see and production Postgres finds at the
moment somebody marks a day.

Production was read after #60 and again after #61, rather than assumed: `/api/v1/health`
answered `{"status":"ok","api_version":1}` and `/api/v1/warmup` — which opens a real
connection, so it answers for the database as well as the code — answered
`{"status":"ok","api_version":1,"schema":"0013"}`. **It has not been re-read since, and #83
found out why.** The deployments sit behind Vercel's Deployment Protection and answer a
redirect to a login page, so `/api/v1/warmup` cannot be read from a session here at all —
this is not an omission anybody can close with one more `curl`. It still wants reading, by
the owner's browser or with a bypass token: #77 moved server code and the schema together,
so the answer should read `"schema":"0014"` today — and `"schema":"0015"` once #140's
migration is applied and its code deployed — and that single line is the cheapest check that
the migration and the code actually met. It is outstanding since #61.

## Where the work happens from here: a local machine

**#128 was meant to be the last batch built in a cloud session, and the change is one of
venue rather than of direction.** #133 and #134 were still written from one, because neither
needs a device. Everything up to and including #85 was made with no emulator, no `adb`
and no device. That is what produced a project with 968 Android tests, 1634 server tests —
and a section 5 of this document that has only ever grown, because the things in it are not
things a JVM test can be asked.

From here: Android Studio, an emulator and a real phone, with Claude Code in the terminal
beside them. What that unlocks, in the order it is worth:

1. **Seeing the screen.** `adb exec-out screencap -p` makes a PNG and a local agent reads
   PNGs. Every «nobody has looked at this» item in section 5 becomes answerable.
2. **Instrumented tests.** There is **no `androidTest` source set anywhere in this
   project** — not in any of the five modules, and no `espresso`, `uiautomator` or
   `androidx.test` in the version catalog. That is why the reorder gesture is two touches
   rather than one, and why its threshold is asserted in arithmetic rather than under a
   finger.
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
what each bug sweep found, and **#109–#127 open**, which are the whole of what is left.

The forward half is worth reading before planning anything: **#109** is the epic this
section describes, **#118–#122** are the owner's alone, and **#126** collects the four small
things carried deliberately, so that a later session does not re-discover a decision as if
it were an oversight.

Labels are `type:` (feature, bug, chore, research, decision, epic), `area:`, `status:` (now,
next, someday, done) and `needs:` (device, owner). **A session cannot create a GitHub
Project board** — Projects v2 is GraphQL-only and the toolset here is REST — so the board is
the owner's to make, and these labels are what its views filter on.

## What the last session added: a second electronic diary, «Сетевой город», behind a provider-neutral seam

Open as the **draft** PR #140, from `claude/school-diary-api-routes-cc4o1n`, closing #136,
#137, #138 and #139. It **has no milestone yet** and cannot merge until the owner creates one
(e.g. «v0.9.0 — A second diary»). Seven commits on `fa4fe0c`, `4c34cb5` through `9ceef0c`;
the server gates were run, not quoted. **The Android app was not touched.**

The project read one diary, Петербург's, wired straight into `services/diary.py` and
`/diary/signin`. This batch puts a seam between the service and the provider, adds a second
provider behind it, and reads either from the same bot and API.

- **A provider-neutral diary seam** lives under `server/app/providers/diary/`: the shared
  models, a `DiaryError` family (`PetersburgError` re-exported as an alias so nothing that
  caught it breaks), a `DiaryProvider` / `DiaryConnection` `Protocol` contract, and a
  `registry.py` whose `binding()` is the single resolver of a class's diary binding.
  Петербург goes behind it **byte-for-byte unchanged**.
- **«Сетевой город»** (`server/app/providers/netschool/`) — ИРТех NetSchool, the main diary
  of about twenty regions, one route set for all. It carries a region **allow-list** (the
  only origins ever contacted — the SSRF guard), windows-1251 salted-MD5 password sign-in,
  the weekly diary mapped onto the existing models (homework is assignment type 3),
  keep-alive, and school search for binding.
- **The service dispatches by provider.** `POST /api/v1/diary/login` now accepts optional
  `provider` / `region` / `school_id` (for the phone's future batch), and
  `services/diary_keepalive.py` keeps every live session alive from `GET /api/v1/cron/tick`
  with `GET /webapi/context`, within a batch cap and a time budget, never touching
  `last_used_at`. `TickOut` gained `diary_sessions_kept_alive`, `diary_sessions_lost` and
  `diary_keepalive_failed`.
- **The bot binds and reads either diary.** An admin binds a class to «Сетевой город» through
  a provider chooser → region → school-name search; the read path (`bot/handlers/diary.py`)
  is provider-neutral — the sign-in card names the bound diary in the genitive, and a session
  is keyed on the class's current provider.
- **Three diary defects fixed (#136, #137, #138).** #136: revoking a member now drops their
  diary sessions and links, not just invites and subscriptions, so a revoked member no longer
  holds a live credential for thirty days. #137: `/diary/signin` re-checks the class binding
  at submit and refuses **before** spending the ticket if the diary was unbound or rebound
  after the ticket was issued. #138: four false diary comments corrected and two dead type
  names removed.

### Schema

`EXPECTED_REVISION` is now `0015` (revision `0015_diary_provider_columns`), which adds the
eight nullable columns named in the opening — five on `diary_sessions`, three on `classes`. It
is **additive** and **must be applied to Neon before the merge**; it has **not** been applied
yet, and the production database is still at `0014`. A `NULL` provider means Петербург, so the
running code is happy without the columns and the code that needs them is #140's.

### What was deliberately left alone

- **Госуслуги (ЕСИА) sign-in.** Some regions have switched passwords off and answer with a
  clear refusal, which the client surfaces as `SignInUnsupported`. Driving ЕСИА ourselves is
  #135's decision, not this batch's.
- **The refresh-token grant (LoginType 9).** It exists in the client but is unverified live.
- **SignalR reports** — final marks, attendance letters — are not read.
- **Everything on the phone.** The Android app stays Petersburg-only until batch 2 (region and
  school at sign-in, the zone from the server, neutral strings). That «server first» order was
  the owner's decision.

### Gates

Run on the final head, not quoted: `ruff check app tests scripts migrations` clean;
`pytest -q -n auto` **1668 passed** (was 1634); `python -m mypy` **Success** across 97 modules
(`files = ["app"]`, 84 before the two provider packages joined). Android was not touched, so
`./gradlew test` was not re-run and stands at **968**.

### What nobody has verified in this batch

**No code here has met a live «Сетевой город» server with a real account.** Every upstream
shape — the salted-MD5 sign-in dance, `/webapi/context`, the weekly diary, the school search —
is from open-source clients and hand-written test payloads, never from a server seen
answering. The first live sign-in is the owner's (#121, #135), the same caveat that stands
for Петербург.

## What the session before it added: the electronic diary of every Russian region, and the routes each platform exposes

Open as PR #134, from `claude/school-diary-api-routes-cc4o1n`, in the milestone
`v0.8.0 — On a device`; it closes #130. **Documentation only.** No file under `server/`,
`android/` or `.github/` changed, and there is no migration.

The project reads one diary, Петербург's. Until this batch nothing in it said what a school
anywhere else keeps its marks in, how a client signs in there, or which routes it exposes.
So the question #127 names, «что делать, если у нас не Петербург», could not even be scoped.

- **`docs/diaries.md`** maps all 89 federal subjects to the platform their schools use in
  September 2026. It gives what each region left behind and where it is moving, and a table
  of the platforms: how a client signs in today and which open client to read first. It
  describes the sign-in pattern across the country, what the survey means for this project,
  and what it does not cover. The mandatory regions are marked and had a deep check of their
  own: Санкт-Петербург, Москва and the eleven of the «Моя Школа» region picker.
- **`docs/diaries/`** holds one page per platform, nineteen in all, with 1916 routes
  between them. Each row gives the method, path, authentication, parameters and response,
  the client it was read from, and whether it is *current*, *legacy* or *uncertain*.
  `regions.md` beside them carries the quoted, dated evidence for every region.
- **The index, `CLAUDE.md`, `README.md`, `docs/architecture.md` and the `docs-keeper`
  agent** now count nine documents and link the survey.

### How it was made, and how far it can be trusted

It is the method `providers/petersburg/` was written by, at the scale of the country:

- **Routes.** Every open client found on GitHub, GitLab, Codeberg, PyPI and npm was cloned
  and read. A second reader grepped each route back to its source, and a third dated it.
- **Regions.** Every region was researched, then re-checked by an independent pass told to
  refute the first. A completeness critique listed what both passes had missed, and a second
  round went after that list.
- **Official apps.** Two platforms were read from their official apps: ТОР «Моя школа» from
  «Госуслуги Моя школа» 5.0.0.454, and ЭПОС.Школа from «ЭПОС» 1.53. ТОР has no open client;
  ЭПОС.Школа's only one dates from 2022.

**Nothing was tried against a live diary.** Every diary host refuses a connection from a
cloud session. So a route in these pages is what a client's code sends, never what a
server was seen to answer. #121 records the same caveat for the one provider that exists,
and each page dates its clients for this reason.

**The generator is not in the repository.** The pages were rendered from the research data
by scripts that stayed in the session's scratch space, because they read agent transcripts
that exist nowhere else. A later correction is an edit to the Markdown.

**Other people's credentials were redacted.** Several public clients hard-code the OAuth
`client_id` / `client_secret` pairs of Дневник.ру and «Сетевой город». Every one is
`<redacted>` in the pages, and the assembly refused to write a page that still held one.

### What it found that decides the next provider

- **A password is now the exception.** `/diary/signin` is built around Петербург's email
  and password, and that is unusual. In most regions Госуслуги is the only door, and the
  clients that last keep a token a person brought from a browser. A second provider cannot
  reuse `/diary/signin` as it stands.
- **Twenty regions moved to ТОР «Моя школа» on 1 September 2026**, by the official list of
  its first wave. They share one API, and one batched call carries the whole diary. But
  the sign-in is Госуслуги's own and its `client_secret` is signed on Госуслуги's side.
  Nobody outside has got in except through a person signing in inside a WebView. Whether
  that is acceptable under Госуслуги's terms was not reviewed.
- **«Сетевой город» is the largest group that still takes a password.** It is one route set
  on each region's own server, and it is the main diary of 20 regions, though
  some of them allow Госуслуги only. It is the obvious first candidate, and the МЭШ family
  comes after it.

### What was deliberately left alone

- **Provider code.** #130 asked for the map and nothing else. Which provider comes second,
  and how it signs in, is the owner's decision. It is filed as #135 and set out in
  section 7.
- **The teacher's side, and a parent with several children.** The open clients barely touch
  either, and the overview says so rather than guessing.
- **The generator**, for the reason above.

### Gates

No file the gates read changed, so `ruff`, `pytest` and `./gradlew test` were not re-run.
CI's path filter skipped the Server and Android jobs on every head, and «What changed»
passed. What was checked instead, on the final head:

- every relative link and anchor in the new and touched documents resolves;
- every table row has its header's column count;
- the redaction scan finds no credential.

## What the batch before added: the tracker (#128), its rule (#129), and their close-out (#133)

Documentation and agent configuration only. No model, endpoint, screen or test changed, so
the gates stand exactly where #85 measured them.

- **#128** opened the issue tracker: forty-two issues, #86–#108 closed and #109–#127 open,
  labelled `type:`, `area:`, `status:` and `needs:`. It also moved the work to a local
  machine with an emulator and a phone, and wrote down what that unlocks and what it still
  cannot do. That is the section «Where the work happens from here» above.
- **#129** made it a standing rule that a found defect becomes an issue before it becomes a
  fix, with one issue per finding, `Closes #NN` in the pull request and both numbers in the
  release notes. The rule is in `CLAUDE.md` and in the `audit`, `github-pr` and `release`
  skills. The owner created the ninth milestone, `v0.8.0 — On a device`, and #129 put its
  number into the tables.
- **#130** was opened by the owner on 23 September: map every region's diary platform and
  the API its open clients use, as a document in `docs/`, with no code. It is `status:now`
  and needs no device, so it is the natural first task for a session that has none; #134
  is that task.
- **#133**, the close-out, fixes two defects that were found while writing it, and each got
  its issue first. **#131**: #129 left the milestone table's `Dependencies` row stranded below
  two paragraphs, so GitHub drew a table without it. **#132**: `CLAUDE.md` still quoted 1610
  server tests, while the other three places say 1634. `CLAUDE.md` is not on the `handover`
  skill's list of places where the count lives, which is how it drifted. The list is left as
  it is: the `Commands` line is the only count in `CLAUDE.md`, and one more place to remember
  is what this issue now records.

**Nothing new has been verified.** Everything in sections 5 and 7 still stands, and both
already point at their issues. `/api/v1/warmup` has still not been read since #61 (#119).

## What the batch before added: the gesture #84 shipped did nothing, and the variables nobody could find

Open as PR #85, in the milestone `v0.7.0 — Оптимизация`. It is one defect, and it is the
defect that the feature merged an hour earlier did not work: **a tab dragged to another slot
went back where it came from and the order was never stored.** On the screen it looked
right the whole way — the icons slid, the row opened a gap, the neighbours moved — because
all of that is recomputed every frame. Only the drop was wrong, and the drop is the part
that is remembered.

### Why it survived #84's tests

`Modifier.pointerInput` keyed on `Unit` starts its coroutine once and never restarts it, and
its element compares equal on the key alone — so the node is never even handed a newer
lambda. Whatever `detectHorizontalDragGestures` closed over on the composition that opened
the mode is what it still calls minutes later. `held` and `dragPx` survived that, being
state reads through a delegate; **the landing slot did not**, being a plain `val` computed
in the caller's composition. Every drop therefore asked where the tab had been *before* the
finger moved, which is no slot at all: `moveItem` refused the out-of-range index, the order
came back unchanged, and the preferences were written with the order they already held.

Nothing in #84 could see it. `ToolbarReorderTest` asks the arithmetic in pixels with no
composition, and it was right; `ToolbarReorderModeTest` asks which callback a press raises,
and those were right too. **Between the two sat the one question neither asked — whether
the number the gesture computes is the number the gesture reports.** #84 wrote down that the
drag was driven from no test, and gave a good reason (a synthetic swipe of «half a slot»
measures Robolectric's densities rather than the gesture). The reason was sound and the
conclusion was not: a drag of ten thousand pixels lands on the last slot under *any*
density, because `dropIndex` parks a finger that has left the bar at the end. That is what
`ToolbarDragTest` does. Two of its first three went red on the shipped code straight away;
the third passed, and passed for the wrong reason — see the trap below — which is worth more
than the two that failed, because a test that is right by accident is how a defect survives
a batch in the first place.

The fix is `rememberUpdatedState` on the four gesture callbacks — the same pattern
`Corrections.kt` in this module already uses for exactly this reason, which is the part that
stings. Restarting the coroutine instead, by keying `pointerInput` on something that
changes, would cancel the gesture under the finger every time the drag moved a pixel.

### And the frame #84 left deliberately is closed

That close-out recorded a one-frame flicker at the end of the gesture and said the proper
fix — the component dropping its permutation by the identity of what it is handed rather
than by the mode closing — risked silently double-applying a drag. It does the opposite:
`working` is now keyed on the order of the labels it permutes, so a list swapped under a
live permutation replaces both in one step instead of two. A caller that hands the committed
order straight back is now drawn correctly, which is the case that would have double-applied
before, and the shell's latch is no longer load-bearing for that — it stays because it keeps
two drags of one session describing permutations of the same list.

The test for it hands the bar its own reported order mid-mode. On the code without the fix it
drew «Задания, Сегодня, Календарь» for a drag that asked for «Календарь, Задания, Сегодня» —
the permutation applied twice, which is what the comment in #84 predicted and priced as too
risky to close.

One trap in the test itself is worth carrying: `translationX` here is a `graphicsLayer`, and
`positionInRoot` — which every bounds assertion goes through — counts that layer in. Read six
frames after the finger lifts, a tab that had not moved at all sorts into the place the drag
had merely carried it, and the assertion passes against the very defect it was written for.
The springs are given 120 frames now.

### The configuration nobody could find in one place

Two questions came out of a screenshot of the Vercel variables, and the answer to the first
was «yes, in `docs/deploy.md`», which is only two thirds of an answer. **`server/.env.example`
— the file every set of instructions says to copy — was missing three of the eleven**:
`WEBHOOK_SECRET`, `CRON_SECRET` and `BOT_USERNAME`. Two of those three are ones a deployment
refuses to start without, so the cost of each was a deploy to find out. It was also missing
`TRUSTED_PROXY_HOPS` and `WEBHOOK_PATH`, which are real knobs nothing outside `config.py`
mentioned. It now carries all fifteen settings the code reads, each with what empty means,
and says in prose why the sixteenth — `VERCEL` — is deliberately not a line in it.

Nothing could have caught that. **A missing variable is not a missing field:** every setting
has a default, which is the whole reason the refusal-to-start check exists, so the server
runs perfectly well against an example file half a year behind. `tests/test_env_example.py`
is the guard — four tests, pinning the file to `Settings` in both directions, pinning the
prose about `VERCEL`, and pinning that every secret a workflow reads is named in
`docs/build.md`. Each was watched going red: the first against the file as it was this
morning, the last against a ninth secret added to a workflow.

Two documents grew rather than one, because the configuration lives in three places and no
page said so. `docs/build.md` opens with **«From a clone to a working pair»** — four steps,
each one saying how much of `.env` it needs, from «the API alone, no Telegram account at
all» to the school search — and then **«The other place variables live»**, a table of the
eight secrets GitHub Actions holds with what reads each and what happens without it. The
three facts worth more than that table: `ci.yml` reads no secret at all, which is why a
fork's pull request runs the whole gate; `CRON_SECRET` is one value in two places and
**nothing checks that they match**, so a mistype is a `403` on a schedule with both halves
looking configured; and `DIARY_SECRET` is deliberately *not* an Actions secret, because
nothing there imports the server's code. `docs/README.md` gained the row that sends a reader
to the right one of the three.

### Gates, measured on this branch

`./gradlew test` **968** (was 964) and both assembles. The four new Android tests are
`ToolbarDragTest`: what a drag reports, where the row is drawn afterwards, what a second drag
reports, and what happens when the list is swapped under a live permutation. On the server,
`ruff` clean, `python -m mypy` clean across 84 modules, `pytest -q -n auto` **1634** (was
1630) — the four are `test_env_example.py`. Database at `0014`.

### What nobody has verified in this batch

**Nothing of the configuration work is proved against a real deployment.** The example file
and the two document sections are checked against `config.py` and against the workflows by
tests, which is the half that can be checked; that the eleven on Vercel are the eleven that
should be there, and that `CRON_SECRET` matches what the external cron sends, is the half
nothing here can see.

**Still nothing on a phone.** The drag now reports the right slot in a JVM test with
Robolectric's densities and the clock held by hand; whether half a slot is the right
threshold under a real thumb, and whether the wobble is plausible, are the same two
questions #84 left open and neither has been asked of a device. The APK for #84 was built
against the code that did not work, so it is worth nobody's time — the first useful install
is the one built after this merges.

## What the batch before added: the home screen's tabs, arranged by the person using them

Merged as PR #84 (`1b32623`), in the milestone `v0.7.0 — Оптимизация`, three commits —
`bf80a9d` the two halves, `14f5374` the shell wiring, `05e0c47` the close-out. **Read the
batch above before trusting anything here**: the drag it describes did not report what it
computed, and the section that follows was written believing it did. Asked for
in one sentence: «сделай так, чтобы при зажатии кнопки вкладки на главной, можно было
переставить, включался режим перестановки и кнопки потрясывало как иконки на ios».
Long-press a tab on the home screen; it and its neighbours wobble; any of them can be dragged
somewhere else; the order is the reader's from then on and it is remembered. **Nothing
outside `android/` was touched**, which is why CI's server job is skipped rather than green.

### The risky half was not the gesture

**Everything downstream stopped holding a position and started holding a tab.** The pager's
index used to mean one destination for the life of an install; it now means whichever tab is
in that slot today, and every place that had quietly relied on the old meaning had to be
found. The scroll-offset holders are keyed by tab rather than by index — an index-keyed one
hands an arriving screen the fade depth of the one that left. The pager carries a key per
tab, so each keeps its own saved scroll instead of inheriting whatever had been filed under
«page 1». And the default tab is latched as a **tab** rather than as an index, which after a
reorder would have sent predictive-back home to a screen nobody chose.

The shell's four back handlers became one rule in one function (`navigation/ShellBack.kt`),
which is what makes «a back press leaves the arranging mode» a thing a test can ask at all;
without it the press would have been taken by the predictive gesture and left the app.

The bar is handed a **latched** copy of the order for the life of the mode. It was thought
load-bearing — that feeding the committed order back mid-gesture would apply the drag a
second time — and #85 made that false: the component now drops its permutation when the list
it permutes changes. The latch stays for the other reason, which is that both drags of one
session then describe permutations of the same list.

### Two deliberate departures from iOS, each a decision rather than a shortfall

- **The long press does not flow into the drag.** One gesture would mean a tap detector and
  a long-press-drag detector on one node, and which of them sees an event turns on which
  claims the press — behaviour that cannot be settled by reading, and that nothing in this
  module can exercise, there being no instrumentation here.
- **A tap inside the mode closes it** rather than navigating. There is no wallpaper under a
  floating bar to tap instead.

### Three things the tests found and the code did not

- **`IconButton` cannot carry a long press.** Material's has no `onLongClick`, and a
  detector added to the modifier handed to it never fires, because it applies its own
  `clickable` *after* that modifier and the press is claimed before anything passed in can
  see it. It was written with an `IconButton` first, the long press simply never arrived, and
  a test said so. The tab is a `Surface` with one `combinedClickable` now.
- **The drag threshold was asymmetric by a pixel.** `roundToInt` breaks a tie towards
  positive infinity, so exactly half a slot rightwards rounded to one slot and exactly half a
  slot leftwards to none. `dropIndex` rounds away from zero.
- **The lesson of #83's floor test decided the shape of this one.** That replacement test
  kept its own copy of the arithmetic and stayed green against the very mutation it was
  written for, so the drag's arithmetic is a file of its own here —
  `component/ToolbarReorder.kt`, pure, pixel-facing, knowing nothing about a pointer — and
  the tests call the production expression rather than a second copy of it.

### The jiggle runs only when it is allowed to

It runs **only while the mode is open** and **only when the reader has animation on**. The
first is not a nicety: an infinite animation means Compose's clock is never idle, so every
test that composed the bar would hang rather than fail — the trap this project has now paid
for twice. The second is that somebody who turned animation off said that about their phone,
not about this bar. Each icon is out of phase with its neighbours, because a row rotating in
lockstep reads as the bar itself flexing rather than as several things each loose in its own
right.

### Also, and deliberately

- **The settings default-tab picker lists the tabs in the reader's own order.** It is a
  picture of the bar it is about, and two orders on one screen is a mapping exercise to
  answer one question.
- **A stored order repairs itself rather than being trusted.** It is a list of member names,
  and every way it can be wrong has an answer: an unknown name is dropped, a member the
  string does not mention is appended in declaration order, a duplicate collapses, whitespace
  is ignored, and nothing stored at all is the declared order. The appending is the clause
  that matters — the day a fourth tab is added, every installed phone holds a three-name
  string, and the new tab has to appear rather than the bar quietly losing it.
- **`SELF_STUDY` keeping its lessons, and the other non-decisions from #83, still stand** —
  the lookahead row, `DayKindName`'s four kinds against `/bundle`'s six, the missing rule
  against a composable reading `BuildConfig`, and `CONTRIBUTING.md`'s `python -m pytest`.
  None of them was touched here and none of them has moved.

### Gates, measured on this branch

`./gradlew test` **964** (was 929) and both assembles — and see #85, which found that four
of those tests covered every part of the gesture except the one that was broken. The
thirty-five new tests are 10 on
the drag arithmetic, 5 on the reorder mode's contract, 8 on the stored order's repair, 11 on
the shell's index arithmetic and its back rule, and one pinning the default order — the bar
somebody who has never opened the setting sees, which is also what every screen gets while
the preferences file is still being read. The server half was not touched at all and
its gates stand at #83: `ruff` clean, `pytest -q -n auto` **1630**, `python -m mypy` clean
across 84 modules. The database stays at `0014`.

The count is corrected in all three places it lives — this file's cheat-sheet, the README's
«Honest status» table and `docs/architecture.md`.

### What nobody has verified in this batch

**Nothing here has been seen on a phone.** Whether the wobble is plausible, whether half a
slot is the right threshold under a real thumb, and whether the two-step gesture reads as
deliberate are all unmeasured — and none of the three is a question a JVM test can be asked.
**The drag is driven from no test at all**: Robolectric reports its own densities, so a
synthetic swipe of «half a slot» would measure the environment rather than the gesture, which
is exactly why `ToolbarReorderTest` asks the arithmetic directly, in pixels, with no
composition in it. — That paragraph is the one #85 came out of. The reason is still true and
the conclusion was wrong: a drag does not have to be «half a slot» to be worth driving, and
the one that goes clean off the end of the bar answers the same under any density.

~~**One frame at the end of the gesture can draw the pre-drag order.**~~ Closed by #85, and
by exactly the fix this paragraph priced as too risky: the component now keys its permutation
to the order it permutes, which replaces both in one step. The risk named here — silently
double-applying a drag — is the thing the key *prevents*, and there is a test that showed the
old code doing it.

There is a second window of a frame or two, and it is inherent rather than a defect: the new
order goes out through the preferences and comes back later, so between the drag ending and
the preferences answering the pager's index still names the old order. A `LaunchedEffect` on
the arrived order closes it by putting the pager back — without an animation, which here
would be a page visibly sliding to a place it never left.

## And before that: an audit of nine areas, and the twenty-four defects it found

Merged as PR #83 (`3a4a924`), in the milestone `v0.7.0 — Оптимизация`, eight commits.
Eleven read-only passes first — the nine areas of `.claude/skills/audit/SKILL.md` plus
strings, documentation and a security sweep — and then the fixes. **Every one of the
twenty-four was re-verified by hand here before it was fixed, and every one is closed by
a test that was watched going red** on the code without the fix. Nothing in it is a model
change, so there is no revision and the database stays at `0014`.

### Three a user would have hit

**A substitution could be written onto a date that draws nothing.** The write check asked
`school_year_bounds` and the resolver asked the class's own terms, its holidays and the gaps
between them, so three kinds of date passed the check and resolved empty: past the date the
class's own «🗓 Четверти» ends on, inside the holidays between two terms, and on a public
holiday. The bot answered «Готово», the audit log recorded it, every subscriber with
`notify_changes` was told, and the row appeared on no phone, in no widget, in no calendar
feed and in no digest. **The second copy of the rule *was* the defect**, so the fix is not a
third: `off_reason_for` is module-level now and the resolver and the write check both ask
it, and the refusal names which of the three it is, because the way out differs — move a
date in «🗓 Четверти», or put an event on the day instead.

**«🌿 Отгул» was a label with no behaviour.** It printed the word and then listed all six
lessons under it, everywhere. `DAY_OFF` clears the day exactly as `HOLIDAY` does now — the
same early return, so the kind, the note, the events and the homework stay and only the
lessons go. Three of the auditors found this independently.

**`/find` with a long query answered nothing at all.** The needle was escaped and never cut
and the «ничего не нашлось» branch had no budget, so about four thousand characters made a
message Telegram refuses whole — and `cmd_find` is a `Message` handler, with no callback to
apologise on. With hits it was worse in a quieter way: `clamp` cuts from the end and the
oversized heading is line 0, so a search that found fifteen assignments rendered as «… и ещё
17 строк» and showed none of them. The realistic way in is the obvious one — pasting an
assignment back into `/find` to locate it. The school-search prompt had the same shape.

### The widget, from three photographs

**The ladder measures in dp and its type sizes are in sp**, which at the system's largest
font are about twice apart. The box still measured its full size, so the largest rung was
still chosen, and the widget drew eight timeline rows, a week strip, two homework blocks and
the next school day into the room for about half of that: «ПЕРЕ…» for the state word, chips
over a third of the surface, the last rows off the bottom edge. `of()` divides by the font
scale now — **the height, and only the height**, because height is what larger type spends
and spends in proportion, while the ladder uses width for structure. Dividing the width as
well was written first and the tests rejected it: a four-cell widget at the largest font
fell past the narrow column onto a rung with no timeline at all, so a reader who asked for
bigger type lost the rest of their school day.

**The trailing detail starved the subject to zero width.** A `LinearLayout` measures its
unweighted children first and hands the remainder to the weighted ones, so a room number or
a teacher's name took what it wanted and the subject, which carries the weight, got what was
left. One row drew «10:50 Надежда Петро.» with no lesson on it. Two weights split the
remainder now, with the detail end-aligned in its own share.

**The week strip's chips had no gaps, and the code said they did.** `padding` before
`background` is margin in Compose and nothing of the sort in Glance: `applyModifiers` folds
every padding in a modifier chain into one `setViewPadding` on the view the background lands
on and throws the order away, so the 1 dp meant to separate the seven days was drawn inside
their own colour and they read as one grey bar. The gap is an outer box. A `Spacer` between
them is the Compose answer and the wrong one here — seven chips and six spacers is thirteen
children of a container that keeps ten, so Saturday and Sunday would have gone missing in
silence.

Two smaller ones from the same pass: `ellipsize` collapses whitespace before it counts,
because a Glance `Text` at one line hard-clips at the first newline and an event title typed
on two lines in Telegram reached the home screen cut with no «…» to show for it; and the
launcher's placeholder layout rounded at 20 dp against a live surface of 24, a visible step
on a cold start its own comment says can take most of a minute.

### Three of this project's own guards did not hold what they claimed

This is the half worth carrying forward, and one of the three was written by the batch
below.

- **`test_test_imports.py` matched one of two spellings.** It refused `from tests.x import
  …` and not the bare `from x import …`; six live test modules used the bare one and the
  guard stayed green. They pass today only because pytest's default import mode puts
  `tests/` on `sys.path` — under the mode pytest now recommends, three of them die at
  collection with the exact error the guard exists to prevent. The shared fakes have moved
  into `conftest.py`, which is where the guard's own docstring said they belonged.
- **`DeviceClockTest` listed module paths and filtered them by `isDirectory`**, so a
  renamed or moved module drops out of the scan in silence while the test stays green on the
  rest. Its own KDoc records that `:core:designsystem` was left out by accident once, that
  it is the module where the last clock-shaped defect landed, and that
  `ResourceTranslationTest` had the same hole for the same module — and the recurrence was
  unguarded by the very `filter` that would do the dropping. It discovers modules now.
- **`NarrowLabelBudgetTest` allowed two characters more than the column holds**, with no
  derivation, and `+ 2` was exactly the current data: «Homework today» is fourteen against a
  budget of twelve, on the ceiling with nothing to spare, under a comment saying the title
  should be *shorter* than the column. Glance cannot ellipsize, which is that file's whole
  premise. The English string is what moved — a test tightened onto a string that does not
  fit is a test reporting the defect it was written to prevent.
- **`test_the_entry_point_exists_where_vercel_json_says_it_does` never read `vercel.json`.**
  It checked a hardcoded path, so the file going missing was caught and the two drifting
  apart was not, which would silently drop `maxDuration: 30` under a name promising
  otherwise.
- **And my own floor test from #82 was wrong twice over.** The 6 dp floor is unreachable
  from any rung, so walking the ladder cannot tell the floored expression from the unfloored
  one — and the first replacement kept its own copy of the arithmetic and stayed green
  against the very mutation it was written for. `innerCornerFor` exists so a test can call
  the production expression with a padding the enum does not have.

**Plus the guard that did not exist.** `MarqueeText` runs `Int.MAX_VALUE` iterations, so
Compose's clock is never idle and a test that composes an overflowing line **hangs rather
than fails**, taking the whole Gradle run with it. Twenty-two of twenty-five Compose test
classes compose a component that can draw one, and the rule was held by a KDoc. They pass
today only because Robolectric lays text out without fonts at about a pixel a glyph, so
nothing overflows; the day a label grows, CI stops for forty-five minutes and says nothing.
`MarqueeClockTest` walks every file that calls `createComposeRule`, and each of the thirteen
that neither holds the clock nor could overflow says in its own words why not — per file,
because an exemption in bulk is a KDoc again.

### The rest of the server half, and the sync layer's three false promises

Four more on the server. **One superscript digit could take down the whole «Дневник»
screen**: `mapper.number()` did a bare `int()` on a string it had only checked with
`isdigit()`, and a `ValueError` is not a `PetersburgError`, so it walked past the guard that
exists to drop one unreadable row and the phone got a bare 500 while the bot's button spun
for ever. **The schools registry could not say its own shape had moved** — an unreadable
suggestion was logged at `debug` against a logger at `info`, so a DaData restructure looks
exactly like a school that is genuinely not in ЕГРЮЛ. And two comments in applied revisions
were wrong about the library rather than about this project: `sa.Enum(native_enum=False)`
emits no CHECK on SQLAlchemy 2.0, so the lower-case value lists in `0003` and `0008` are
inert and could never have rejected the upper-case names the ORM writes. **No DDL changed.**

In `:core:data`, three of the four findings are a written claim that stopped being true
rather than a wrong screen — nothing was broken for a user today and a reader of the code
would have been told the wrong thing four times. **A bundle `ETag` outlived the class it
described** (`forget` had one caller, eviction, so leaving a class dropped its rows and kept
its tags for the life of the install), fixed structurally: `SessionRepositoryImpl` no longer
holds the DAO, it takes a narrow `TimetableCache` implemented by the repository that owns
the tag store, so joining, leaving, signing out and the worker's sweep all go through the
one object that can drop rows and tags together. **`syncedAtEpochMillis`'s KDoc named it as
the eviction rule** while eviction sorts by distance from the current year — two documents
in one repository giving opposite rules for one decision. And **`deleteLookaheadOf` was the
one delete in the DAO leaning on `PRAGMA foreign_keys`**, against a file whose `clear` says
in so many words that the wipe must not, with the in-memory fake *more* thorough than the
real query so no unit test could see it; a source-reading test now parses every `DELETE FROM
school_day` out of the DAO and demands the matching child statements.

### The calendar, and the deployment that could not create its own schema

Four findings in `:app`. **«День» → «Список» blamed a filter nobody had set**: on a year this
phone has not fetched it said «Ничего не подходит», with no chip selected, and said the same
thing while the year was still arriving — the exact distinction `synced_window` exists for,
and the other three surfaces of that tab already made it. **The list scrolled something
nothing reported**, so switching modes left the status-bar blur wearing the ribbon's stale
value over a list at its top. **The period arrows announced «неделя» in four of the five
modes** — the header text was right and only the content descriptions were left behind, so
TalkBack said «Следующая неделя» over a button that steps a day, or a month;
`ScheduleView.stepOf(dayMode)` is one answer now, and the test pins the moved date and the
spoken unit *separately*, because a test that only asked whether they agree would stay green
for ever once they share a function. And **«О приложении» drew the schema and not the
expectation**, so «база отстала» and «база впереди кода» — opposite mistakes with opposite
fixes, in the type's own words — looked identical. Both chips now; `detail` stays undrawn on
purpose, because it is the server's Russian sentence and that card is read in two languages.

**`docker compose up -d --build` brought up a Postgres with nothing in it.** It is three
commands in `docs/deploy.md`; `app.main` calls `create_all` only for SQLite, the image
carried neither `migrations/` nor `alembic.ini`, and the one in-image alternative,
`python -m scripts.init_db`, refuses a non-local URL by design. What the reader saw was
worse than a failure to start: the container came up, `/api/v1/health` answered green
because it opens no connection, and the first ORM read failed — which for the bot is the
middleware, so every update died at once. The revisions ship in the image now and a one-shot
`migrate` service runs `alembic upgrade head` before the server is allowed to start: a
service rather than a line in the server's command, so a failed migration stops the stack
instead of being buried in the API's log and `docker compose run --rm migrate` is something
a person can do by hand. It takes only `DATABASE_URL`.

One more that cost several thousand untracked files: **`.gitignore` spelled the virtualenv
`.venv/` exactly**, so an audit pass building `.venv_audit` beside it offered the lot for
commit. This repository is public and an environment committed by accident is expensive to
take back out of the history.

### The documents, brought level

Fourteen files, every number re-measured rather than copied. The Android total was 849
against a real 929, `:core:designsystem` 74 against 80, `:widget` 74 against 90, `:app` 305
against 357, `:core:model` «ninety-four tests in eight classes» against 117 in ten, and the
server count was quoted as 1559 in one document and 1610 in two others while it is 1630.
Two documents disagreed with themselves: `docs/build.md` said at the top that no workflow
asks for an artifact retention and thirty lines later that «artifacts live a week» — the
fourth place to quote the wish rather than the setting, in the file already corrected once —
and `architecture.md` gave `:core:model` two different test counts nine lines apart. The
descriptions that had gone false: «День» and «Лента» are one tab, and four documents still
described «Лента» as a separate view, one of them the guide the app itself serves. Two rules
had been written in code comments and nowhere else, and both span three modules, so
`docs/design.md` is where they now live: the concentric-rounding rule with the four places
it deliberately does not apply, and the day's two readings with the arrows stepping a day in
one and a month in the other.

### What was deliberately left alone, and each is a decision rather than an oversight

- **`SELF_STUDY` keeps its lessons.** «Эти уроки, но дома» is a reading nobody has ruled
  out, so `DAY_OFF` was brought into line with `HOLIDAY` and self-study was not — and a test
  records that as a decision, so the next person has to change a test that explains why
  rather than a line that looks like an omission.
- **The lookahead row is neither fixed nor removed.** The server resolves `next_school_day`
  at most 21 days past the last lesson *inside the window asked for*, and since the window
  became a whole school year that lands in June — out of season for every class, so the
  field comes back null on every bundle this app asks for. Making it work means resolving
  from the window's end and about a hundred days of lookahead, paid by every phone on every
  sync, for a row that matters in the last days of May; removing it means a Room version
  bump under `fallbackToDestructiveMigration`, which wipes the cache on every installed
  phone. All five places that described it now say so, and what answers «what is next»
  across a gap is `firstTeachingDayAfter` over the cached year.
- **`DayKindName` accepts four day kinds while `/bundle` can return six**, so a phone cannot
  write back the value the server just sent it. Found, verified and written into
  `docs/api.md` out loud; not fixed, because widening the accepted set is a contract change
  and belongs with whoever decides what a phone may set a day to.
- **Four composables read `BuildConfig` directly and nothing stops a fifth**, which is
  exactly how #81 happened. The guard is a source-reading test and belongs with the rest of
  the meta-tests; this batch added the marquee one and stopped there.
- **`CONTRIBUTING.md` still recommends `python -m pytest -q`**, which `CLAUDE.md` warns
  against by name — the `-m` form puts the current directory on `sys.path` and is the reason
  a `from tests.… import` passed locally and failed at collection on CI. Its numbers were
  corrected in this batch and its command was not, because that line is a contributor-facing
  choice rather than a stale fact.
- Also left: the day sheet ignoring `isFetched`, and a widget day-tap landing on a month
  when the stored mode is «Список».

### Gates, measured on this branch

`ruff check` clean, `python -m mypy` clean across 84 modules, `pytest -q -n auto` **1630
passed** (was 1610), `./gradlew test` **929** (was 896) — `:core:model` 117,
`:core:designsystem` 80, `:core:data` 285, `:widget` 90, `:app` 357 — and both assembles.
CI was green on `3c7a2f5`; the two commits after it are the documents and this close-out.
The database stays at `0014`: **no migration was needed and none was applied.**

### What nobody has verified in this batch

**Nothing of the widget's fixes has been seen on a launcher**, and that is the surface this
batch changed most — the tests reproduce the arithmetic and the budgets, not the rendering,
and the two claims about what Glance does with a modifier chain come from reading its
translator rather than from pixels. **No Compose test was actually made to hang**, so
`MarqueeClockTest` is reasoned from the marquee's iteration count and the call-site list.
**`docker compose up` has never been run** — there is no Docker here — so the compose file
is parsed and its dependency conditions asserted rather than watched. **`/api/v1/warmup` is
unreadable from here**, because the deployments answer a redirect to a Vercel login page.
The diary has still never been opened for real, so the mapper fix is proved against a
fixture rather than against the upstream that would send such a row; and the tag sweep's
prefix arithmetic is written and not run, because `LessonsPreferences` needs a `Context` and
`:core:data` has no Robolectric.

## What the batch before added: the rounding rule, applied everywhere it is actually true

Merged as PR #82 (`c7767c8`), in the milestone `v0.7.0 — Оптимизация`. One sentence of a
request — «"Подложка вкладок — зазор скруглений одинаковый по периметру." — исправь все
подложки по приложению» — and most of the work was deciding where it does *not* apply.

**The rule.** Two nested rounded rectangles look right for exactly one pair of radii: the
inner radius plus the padding equals the outer one. Any other pair leaves the gap wider at
the corner than along the edge, which reads as a wonky corner rather than as a wrong
radius — easy to see, hard to name. #80 applied it to the segmented picker and stopped
there.

**Where it applies is narrower than «все подложки».** It needs two rounded rectangles
nesting *with a gap*. Every shape in `:app` and `:core:designsystem` was read against that
test and exactly one more place in the product qualifies — and it is the one where the rule
matters most, because there the gap is not one number.

### The widget was wrong by a different amount on every phone

`WidgetCard` was a flat `18.dp` and the pill behind the current lesson a flat `12.dp`, both
drawn straight onto a surface of `24.dp`. What separates them is `WidgetSizeClass.paddingDp`,
which runs from 8 dp on the smallest rung of the twelve-rung ladder to 16 dp on the largest.
So the gap was even at **no size at all**, and worst where the widget is biggest: at 16 dp of
padding a concentric block wants 8 dp and it drew 18, more than twice as round as the
surface around it can carry.

`WidgetSizeClass.innerCorner()` derives it per size class now, with a 6 dp floor. The floor
is the interesting half: square is the *honest* answer for a rectangle inset past the curve,
and wrong here, because the rows beside it are rounded and one square corner among them
reads as a rendering fault rather than as a decision. `WidgetSurfaceCorner` moved in beside
it — two numbers that have to agree belong in one place. Glance takes a `Dp` and nothing
else, so this is `concentricCorner`, the arithmetic form, next to `ConcentricShape`, which is
the same rule deferred to draw time when the inner radius is a percentage of a height nobody
has measured yet.

### What was deliberately left alone, and it is most of the survey

Each carries its reason in the file, because the next reader will ask exactly this.

- **The seven weekday chips.** The strip sits in the middle of the layout, so its corners
  are next to other rows rather than in the surface's rounding: no corner nested in a
  corner. A chip is also about as tall as the radius the rule would hand it, which is a
  pill, not a chip.
- **`RoundedCardContainer` and its rows.** The rows are *clipped* by the container rather
  than padded inside it. No gap, no rule — and that flush clip is exactly what makes a group
  read as one slab instead of a pile of cards.
- **The hero card's tile and every `Pill`.** `RoundedCornerShape(percent = 50)` is a circle,
  not a nested rectangle.
- **«О приложении».** It opens and closes with centred text, so nothing it holds has a
  corner in a corner — and its horizontal and vertical paddings differ on purpose, while a
  concentric corner is only defined for one gap.

**Verified, and not.** `./gradlew test` **896 tests**, up from 891, and both assembles.
`WidgetInnerCornerTest` is five, and two were shown red against the old flat 18 dp before
being trusted. One of those two was written `<=` at first and passed against the very
constant it was meant to catch; it asserts a **strict** inequality now, between every
adjacent pair of size classes whose paddings actually differ — and that the ladder really
carries more than one padding is itself a test. **Nothing here has been seen on a launcher**:
the tests reproduce the arithmetic, not the rendering, and `cornerRadius` is a no-op below
API 31 in any case.

## What the batch before added: a test that asserted its own build's configuration

Merged as PR #81 (`28f6ea3`), in the milestone `v0.7.0 — Оптимизация`. One defect, found
the way this kind is always found — by the thing it broke rather than by anything reading
the diff.

**The APK workflow could not build an APK.** The first `apk.yml` run after #80 merged died
at `:app:testDebugUnitTest`, on a test #80 had added: `AboutCardTest > a build that was
told nothing about itself says that rather than nothing`. It read
`BuildProvenance.current()`, which answers with whatever *this* build was told — so it
asserted a property of the build configuration rather than of the card.

It passed everywhere it was run and failed exactly where it mattered. `ci.yml` sets none of
the five `LESSONS_BUILD_*` properties, so `current()` came back empty, the card drew
«Собрано вручную» and the assertion held — on every pull request, including the one that
introduced it. `apk.yml` sets all five, because naming the commit an APK came from is what
they are *for*, so the badge the test demanded was correctly absent.

**The workflow whose entire job is to produce an APK was the only thing that could see it,
and it saw it as a failing string on a settings page.**

The provenance is a parameter now, defaulted to `current()` at the single call site, and
`AboutCard` is `internal` with it. The ordinary fix — and it buys what the old shape could
not: the badges are testable at all. Three tests replace one, including the contradiction
that was previously unreachable (a card claiming both a commit and «собрано вручную»).

**Deliberately left alone, and it is the part worth carrying forward.** Nothing enforces
this shape. Another composable reading `BuildConfig` directly would reintroduce exactly
this, would again be green on every pull request, and would again be red only in the
workflow nobody runs on a pull request. A rule forbidding `BuildConfig` outside a
provenance type would be the real guard; it is not written, and writing it was not worth
widening a one-defect batch. The gap in the gate is structural: **`ci.yml` cannot catch
this class and should not try** — it does not build an APK anybody installs, so the
difference only exists in a workflow run by hand.

**Verified, and not.** `./gradlew test` **891 tests** in *both* environments — with the
five properties exported and without them, because only running both tells them apart —
and both assembles. The red case was reproduced before the fix and shown green after.

## What the batch before added: six things that drifted on a real screen

Merged as PR #80 (`9b8eba7`), in the milestone `v0.7.0 — Оптимизация`. Six reports from one build on a
phone, plus a lie the previous batch's own CI log had been printing all along and nobody
had read.

### The retention was wrong in four places at once

The repository's artifact retention is **one day**. `apk.yml` asked for ninety, `ci.yml` for
a week, `docs/build.md` promised both numbers and `CLAUDE.md` a third. Every run reduced
them and said so — `##[warning]Retention days cannot be greater than the maximum allowed
retention set within the repository. Using 1 instead.` — in a job nobody opens when it is
green, in a sentence that reads like a build problem.

No workflow asks for a number any more: the repository setting is the only thing that
decides, so there is nothing left for a workflow to disagree with, and the summary says
where to raise it. Worth keeping as a shape: **a wish written next to the thing that
overrides it is not a setting**, and three documents had been quoting the wish.

### A marquee that stopped after three passes

Reported as «на некоторых лейблах не двигается, а на некоторых двигается, даже на одном
экране», and the diagnosis that invites is wrong. The line *is* a marquee — it is clipped
mid-glyph with no «…», which is `TextOverflow.Clip`, which this component only sets when it
has decided to scroll. It had simply spent `MarqueeDefaults.Iterations`, three, and parked
itself back at the start. The budget is per composition, so a row just scrolled into view
moves and a row that has been there a while does not.

**The cost of fixing it is a perpetual animation, and that is not free in tests.** Compose's
clock is then never idle, so `waitForIdle` — which every assertion calls into — hangs rather
than fails. It is the same trap `runTest` against `WeekViewModel`'s clock cost a whole
Gradle run for. Three suites now hold the clock and advance it by hand; a new test that
composes an overflowing line and forgets to will hang, and the note is in `MarqueeText` and
in each of them.

### «Календарь» wrapped after «Календар»

The title shared one `Row` with a year chip and three icon buttons under `weight(1f)` —
about eight characters at 411 dp — so the «ь» went to a second line and the subtitle to a
third. Nothing about it looks wrong in the code: `weight(1f)` is the correct way to share a
row, and the row was asked to hold more than it has. Title on its own row now, controls on
theirs.

The second half was movement rather than wrapping: «сегодня» is offered only when it would
do something, and while the *button* was what appeared, both arrows slid 48 dp sideways
every time the reader stepped into or out of the current week. The slot is kept whether or
not the button is in it.

### The picker's tray was rounded by a token rather than by its buttons

24 dp — `LessonsShapeTokens.Group`, the radius of a group of *rows* — around Material's
connected button shapes with 4 dp of padding. Three numbers chosen in three places, so the
gap between the two curves was wider at the corners than along the edges, which reads as a
wonky corner rather than as a wrong radius. `ConcentricShape` computes it: inner radius plus
padding, the one outer radius that keeps the gap even. A `Shape` rather than a computed
`RoundedCornerShape`, because Material's connected corners are a percentage of the button's
own height and the answer is only known once the tray has been measured — which also makes
it a pure function of a size and a density, and therefore testable.

### «День» and «Лента» are one tab

Asked for, and asked about first: the literal reading of the request removed the immersive
ribbon entirely, which is a feature the owner had commissioned in detail two batches
earlier, so it was worth one question. The answer was to merge rather than replace.

They were never two views — both answer «что идёт», one for the day in front of you and one
for the month around it — and four four-letter labels across a 360 dp phone was most of what
the picker's marquee was doing its work for. `DayMode` switches them on the screen itself
and is stored like the ribbon's other three settings. **The two modes cover different
spans on purpose**: the ribbon is one date, because it draws the breaks between lessons; the
list is the month, because «в какой день больше всего» is a question about a stretch. So the
arrows step a day in one and a month in the other, which is what each mode already implies.

### The about card, and where a build came from

It was padded twice — once by the settings list's `contentPadding`, once by itself — so it
came out 32 dp narrower on each side than every group above it, and its two link buttons
broke «Essentials» into «Essent / ials». Full width now, one button per row.

The badges say two things nothing else in the app can. **How the bound server answered**,
including the state that is invisible from everywhere else: API up, database at a different
Alembic revision, which is the window between a merge deploying itself and the migration
being applied by hand — `/health` is green all through it. And **which repository, ref and
commit this APK came from**, with the commit chip opening that exact diff.

That needed five build properties, because **a build keeps no memory of its own checkout**:
an APK cannot work out its repository, branch or commit unless whatever ran the build says
so. `apk.yml` fills them from the runner's own `github.*` rather than from repository
settings, so a build of a fork describes itself honestly. `BuildPropertyReachTest` already
existed for exactly this class of mistake and earned its keep in the same hour: it caught
`LESSONS_BUILD_TIME` being `export`ed inside the run script, where nothing outside the step
can see it.

**Deliberately left alone.** Material's three iterations exist so a screen is not in
perpetual motion, and this batch chose readability over stillness for lines that cannot be
read any other way. That is a trade rather than a fix, and if a list of long titles turns
out to be unpleasant the answer is probably to shorten the titles.

**Verified, and not.** `./gradlew test` **889 tests**, both assembles. The card, the header
and both link buttons are asserted **displayed** rather than merely composed, which is the
lesson of the batch before. The server half was not touched; its gates were last green at
#77 — `ruff` clean, `pytest -q -n auto` 1610, `python -m mypy` clean across 84 modules.
**Nothing here has been seen on a screen**: whether the concentric corner looks concentric,
and whether a marquee that never stops is pleasant, is unmeasured. **The server badge has
never been drawn against a real server** — `ServerStatus` is exercised from a fake and no
test opens a socket.

One note for whoever writes the next Compose test here: **`performScrollTo` drives the
scrollable's animation.** With the clock held for the marquees it moves nothing, and every
node below the fold reports «not displayed» — which is indistinguishable from the element
being absent, and cost two rounds here. Give the test a tall window instead.

## What the batch before added: the three defects the first real build had in it

Merged as PR #79 (`a1f2392`), in the milestone `v0.7.0 — Оптимизация`. Not a feature. #77
shipped a screen
that crashed on opening, and this is the batch that reads the report and closes it — all
three defects are mine, all in `DayRibbonView.kt`, all introduced in #77. **None of them
would have fixed itself**: #78 changed the cache and the calendar and never touched that
view, which is what the report assumed it would.

### A key that was never interpolated

`RibbonEntry.identity()` read `"lesson/${'$'}startsAt/${'$'}{lesson.index}"` — Kotlin's
escape for a *literal* dollar — so every lesson row carried the same constant string, every
event row another, every break row a third. `LazyColumn` throws the moment it measures the
second row of a kind, which is every school day there has ever been. The line was written
through a shell heredoc that escaped the dollars, and nothing ever read the result back.

### The rows were unreadable because the fade was sized for a screen

The ribbon is handed the height left under the header and the picker, and it put a fixed
48 dp `progressiveBlur` fade at each end of it — on a phone held sideways, 96 dp of about
150. Worse, `progressiveBlur` paints a 65 % wash of the surface colour under each fade and
**the shell already does exactly that to the whole page**, so the ribbon stacked a second
one inside the first. The fade is a share of *this* view now (`ribbonEdgeHeight`, 12 %,
capped at 48 dp) and carries no tint of its own. «Ничего не было видно, из-за соотношения»
is precisely the shape of that bug: the shorter the view, the more of it was curtain.

### And the camera distance was multiplied by the density

Which is the trap `SchoolBell.kt` already carries a paragraph about: the unit goes straight
to `RenderNode` and is density-independent, so scaling it put the camera several times
further away than the default and flattened the tilt by a different amount on every phone.
A second use written without reading the first — the one thing `CLAUDE.md` asks for by name.

### What the logs settled

The owner installed the APK of #77 (`versionCode` 24, Room at v4) and sent back a full
Android bugreport — **the first time any of this code has been looked at running on a phone
rather than on a runner**. It is worth knowing what it did and did not contain.

Six fatal exceptions are in it. Three are SwiftKey's; the other three are ours, and all
three are the same `IllegalArgumentException: Key "lesson/$startsAt/${lesson.index}" was
already used` — the platform quoting the defect back with the dollars still in it. Two on
opening the day, and **the third is the rotation**: `WindowManager` logs
`changed={CONFIG_ORIENTATION}, not-handles={}`, so `MainActivity` handled the change
without being recreated, and what rotation did was re-measure at the new height — the same
duplicate key, at the same place. That closes the question #79's body left open: rotation
is not a fourth defect, it is the third occurrence of the first one.

Nothing else of ours is in it. No ANR, no exception out of `:core:data`, no SQLite or network
error, and no crash dialog of our own — «снова предлагало сбросить кэш» is the platform's
prompt after a crash, not a screen this app has. The `SQLiteOpenHelper: DB version upgrading
from 3 to 4` line is the cache being dropped and refilled, which is what
`fallbackToDestructiveMigration(dropAllTables = true)` is there for.

**One thing in it is unexplained and is written down rather than guessed at.** Eighteen
times over seven minutes the app logged `AdrenoVK-0: Shader compilation failed for
shaderType: 4` followed by `Pipeline create failed`, on the render thread, spread across
screens rather than clustered on the ribbon — and **no other app on that device logs it**.
At Android's `I` level, with no shader source and no reason attached, and with nothing the
owner reported that maps to it, there is no fix to write from here; it is a real signal and
it is unclosed. Whoever looks next should start by asking whether the AGSL sheen is the
source at all, since a failure in `RuntimeShader` would normally throw in-process instead.

### The test that was missing, and the two screens nobody had drawn

`DayRibbonLayoutTest` composes the ribbon for real, inside the `Column` with a `weight(1f)`
that `RibbonPage` puts it in, at a portrait height, at a landscape one, and at one shorter
than the fades themselves — and asserts the rows are **displayed**, not merely that nothing
threw. It fails on the first run against the shipped code, which is the only reason to
believe it.

That distinction is the lesson of this batch. The day ribbon shipped with three test suites,
all passing, and **not one of them ever drew it**: `DayRibbonTest` covers its arithmetic,
`RibbonFocusTest` where the focus button lands, `RibbonDepthLevelTest` which devices get the
shader. A screen that is built and shows nothing passes every test that only asks whether it
was built. So the other two screens of that batch are drawn here too, before somebody else
has to find them — the **year picker** and the ribbon's **settings sheet**. Both turn out to
be fine, which is worth writing down rather than assuming.

**Deliberately left alone.** The 12 % share is a judgement and not a measurement: it keeps
both fades under a quarter of the view at every height, which is the rule that was missing,
but nobody has looked at whether 48 dp is still the right maximum on a tall screen.

**Verified, and not.** `./gradlew test` **863 tests**, both assembles; two of the three
fixes proved red first — restoring the constant key fails `DayRibbonLayoutTest`, restoring
the fixed 48 dp fade fails two of `RibbonEdgeHeightTest`. **The camera distance has no
test**, and that is not an oversight to close later: what it changes is how deep the tilt
looks, `cameraDistance` is written straight onto a `RenderNode`, and nothing on this side
can read it back. It is held by the comment in both places that set it, which is a weaker
guard than a test and is the honest description of it. The server half was not touched and its gates were
last green at #77: `ruff` clean, `pytest -q -n auto` 1610, `python -m mypy` clean across 84
modules. **What the fixes look like is still unseen** — the log proves the crash was what
this batch says it was, and proves nothing about the tilt, the blur or the shader. The next
APK is also the first to carry a Room schema of 5, so its first run on that phone drops the
cache and re-syncs a year.

One note for whoever writes the next Robolectric test against a screen in a `weight(1f)`:
compose it **inside the box the real page gives it**, not at `fillMaxSize()`. Every defect
in this batch is a measurement, and a test that hands the view the whole window reproduces
none of them.

## What the batch before added: a cache that holds more than one school year

Merged as PR #78 (`559b306`), in the milestone `v0.7.0 — Оптимизация`. One item, and it is
the one #77 wrote down as a decision the owner had to make: **scrolling the calendar by
years**. They
chose the honest option — a sync windowed on demand — over binding a picker to the year
already synced, which would have scrolled to a year with no days in it.

### The invariant that had to go

The cache held exactly one window per class, and `replaceAll` wiped that class's rows on
every sync. So scrolling into another school year could not work at all: arriving would
have destroyed the year being left, and coming back would have destroyed the one just
fetched. Every date outside the one window read «Нет данных» — on screen the same thing as
a week with no lessons in it.

A window is a school year now, named by the year it opens in, and `replaceWindow` replaces
one of them. `synced_window` is a table rather than something counted off the days present,
because **a year fetched and genuinely empty has to be tellable from one never fetched** —
a class made in March has no rows before it either way, and counting would leave the
calendar on a spinner that never stops.

**The server needed nothing.** `/api/v1/bundle` has always taken an arbitrary `start` and
up to 280 days, and a school year is 274. The whole batch is Android.

### What fell out rather than being added

The Monday anchor is gone with the whole-class wipe that was its only reason, and its
summer bug with it: applied in July it reached back far enough to ask for some 320 days,
past what the server accepts, and the window came back silently clipped in April.
`refresh` lost its `days` — a parameter nothing could honour, threaded through
WorkManager's input data to be ignored. And the `ETag` store keeps a tag per window,
bounded by the cache's own cap rather than a second rule.

### Three defects the tests found and the code did not

1. **Eviction by fetch time was not a rule.** It is not «least recently used» — a year
   revisited and unchanged answers `304`, which touches the class row rather than the
   window's — and it ties, so four years fetched inside one millisecond left the order to
   the query, which returns the newest year first, so the year just asked for was thrown
   away. It goes by distance from the year holding today now, which is clock-independent.
2. **A year asked for while another was in flight was dropped and never retried**, because
   the period had not changed since. It works because the collector *awaits* each fetch:
   one coroutine, so a request in flight parks the collector, and a `StateFlow` conflates,
   so what it resumes on is where the reader ended up. Launching each fetch instead is the
   obvious-looking change, and it is what the test now fails on.
3. **An «already in flight» guard that could never fire**, for the same reason — removed
   rather than left as a claim about the code that stops being true silently. That is the
   second unreachable guard this session found; the first was in the day ribbon.

### On screen

A «2026/27» chip in the header opens a picker of five school years, each marked «загружен»
or «не загружен». Nothing has to be pressed: the calendar fetches the year it is scrolled
into, whether it was stepped to, tapped to, picked or opened from the widget — one
collector watching the period actually drawn, both ends of it, because a week can straddle
31 May. And a day now says which kind of empty it is: «нет уроков», «загружаю год» and
«год не загружен» were one sentence before.

**Deliberately left alone.** Three years per class, which is a number rather than a
measurement: the question a calendar is asked reaches about one year either side of the
one it is in. Nobody has watched what four or five costs on a phone, and the constant is
one line (`MAX_YEARS`).

**Verified, and not.** `./gradlew test` **849 tests**, both assembles, and every guard
proved red first — the old whole-class wipe fails two, letting any year write the lookahead
fails a third, dropping the lookahead exclusion fails a fourth. The server half was not
touched and its gates were last green at #77: `ruff` clean, `pytest -q -n auto` 1610,
`python -m mypy` clean across 84 modules. **Nothing has been seen on a screen** — not the
chip, not the picker, not what a school year takes to arrive over a school's wifi, and not
what three years of rows cost a `LazyColumn` that re-reads them on every write.

One note for whoever writes the next test against `WeekViewModel`: **do not use `runTest`.**
It shares its scheduler with `Dispatchers.Main` when Main is a test dispatcher and then
drains every pending delay by advancing virtual time, which against this view model never
finishes — its clock is `while (true) { emit(now); delay(30s) }`. It hung a whole Gradle
run once.

## What the batch before added: the school's own dates, the calendar, and a day you can scroll

Merged as PR #77 (`ddfd305`), in the milestone `v0.7.0 — Оптимизация`, eighteen commits. Three parts: a
defect reported from a real class, the calendar the owner asked for on top of it, and the
day screen that calendar leads to.

### The dates an admin typed decided a term's name and nothing else

«2 полугодие» was given an end of 28 May 2027 in «🗓 Четверти». The phone, scrolled to May
2027, still drew a full day of lessons on the 29th, the 30th and the 31st — after a manual
refresh, and after leaving the class and re-joining it with a fresh code. Neither could have
helped: **the server was sending exactly what it meant to.** `_resolve_day` asked
`school_year_bounds`, which is `SCHOOL_YEAR_END_MONTH` — 31 May, and always will be.

It was mis-diagnosed twice here before it was found, and the way it was found is worth
keeping: the transport was proved correct end to end (the bot's write, the `ETag`, the
phone's `replaceAll`, the flow) and the app turned out to have **no screen that draws a term's
dates at all**. Only the owner's answer — «судя по трём точкам, прокрутив календарь до мая
2027» — said what was actually being looked at. The two round-trip tests written during the
wrong diagnosis are kept as regression guards and labelled as guards rather than as the fix.

The horizon now comes from the class's own `Term` rows, and the gaps **between** terms are
out of season by the same rule — which is how the autumn holidays are described by moving
two dates rather than by marking nine days.

### The calendar

`services/holidays.py` holds fourteen statutory non-working days and twenty-five
observances, apart because they behave differently: a statutory day stops the lessons,
«День учителя» is a full Wednesday with a badge. **The yearly transfers are deliberately
absent** — they are set by decree each December, and a wrong non-working day removes a real
day of teaching while looking exactly like a correct one.

Every day carries `off_reason` (`out_of_year`, `between_terms`, `public_holiday`, or
nothing) **beside** `kind` rather than inside it, so an older client draws what it drew
before. The month grid gives each reason its own accent, joins consecutive days of one
reason into a single band, and writes «Летние каникулы» across a month with no teaching in
it. A range can be marked self-study, remote or a day off, not only as holidays. The
calendar filters and the order are in both shells — the chips and «по загруженности» in the
app, the same narrowing in the bot's list of marked days — and the month can be read as a
list, which is the one view an order can apply to at all.

### «Календарь → День» is a ribbon now

The hour ruler is **gone**, not kept beside it. It drew the day by position, so a forty-minute
break was forty minutes of blank screen: nothing to read, nothing to press, two clocks to
subtract. The day is flattened into a `Ribbon` in `:core:model` — lessons, events, and the
gaps as rows of their own — and every row says when it runs, how long for, and where the
clock is inside it. The widget's `remainingTimeline` is that ribbon filtered now rather than
a second copy of the merge and its tie-break, which nothing had ever tested.

The view owns the page's height instead of adding to its length, which is what the magnetism
and the tilt both need: a list can only snap, and a card can only lean away from the middle
of the screen, if the list *is* the viewport. Three settings are the reader's, stored rather
than remembered — which way the progress runs, whether the scroll settles on a whole row,
whether the cards have depth — and they live in a sheet on the screen itself because all
three show what they do the moment they are pressed.

The depth is a ladder of three: AGSL lights the running row where its clock has got to on
Android 13 and above, everything from `minSdk` 26 up to Android 12 keeps the gradient and
the perspective tilt, and off is off at every version. `ribbonDepthLevel` is a function of
the API level rather than an `if` at the call site, so all ten versions this app installs on
are checked by a test instead of whichever one the runner emulates.

### What CI caught that the local gate could not

`CLAUDE.md` documented the server gate as `python -m pytest`; **CI runs the bare `pytest`.**
The `-m` form puts the current directory on `sys.path` and the bare one does not, so
`from tests.test_api import _token` passed here and failed at *collection* on CI. Fixed in
three layers: the import is gone, a test now refuses any test module importing another, and
the documented command is the one CI runs.

**Deliberately left alone.** Scrolling the calendar **by years** is not in this batch and is
not a refusal — it is blocked on a decision only the owner can make. The phone's cache holds
**one school year** by construction: `SchoolYear.boundsAt(today)` picks the window,
`TimetableDao.replaceAll` wipes the class's rows on every sync, and the server caps a bundle
at `MAX_BUNDLE_DAYS = 280`. So either the year picker is bound to the synced year (cheap,
and very nearly useless — it would scroll to a year with no days in it), or the sync becomes
windowed on demand, which breaks the one-window-per-class invariant and touches `replaceAll`,
the Room schema and the `ETag` signature. The second is a batch of its own and the wrong
thing to start without being asked.

**Verified, and not.** Both halves of the gates are green: `ruff` clean, `pytest -q -n auto`
**1610 passed**, `python -m mypy` clean across 84 modules, `./gradlew test` **830 tests**,
both assembles. Every fix was proved red first by mutating the implementation. **Nothing in
the calendar or the day screen has been seen on a screen** — not the four accents beside one
another, not the summer's tint across sixty cells, not the tilt, and not the shader, whose
cost on a mid-range phone is unmeasured. The new day kinds have not been pressed in a real
Telegram. The Vercel preview is behind SSO, so nothing could be checked against a running
server either.

## What the batch before added: an audit of the whole project, and a typeface that can draw it

Open as PR #76, in the milestone `v0.7.0 — Оптимизация`. Twelve agents swept the nine areas
of `.claude/skills/audit/SKILL.md` plus documentation, strings and access, read-only; every
finding below was then re-verified by hand here and closed by a test proved red without its
fix. What was found is larger than what is fixed, and the rest is listed at the end rather
than quietly dropped.

### The app shipped a typeface that cannot draw Russian

**Google Sans Flex has no Cyrillic. Not a dropped subset — none.** Its coverage metadata on
Google Fonts lists latin, latin-ext, vietnamese, math, symbols and five scripts nobody here
writes, and zero code points in U+0400–U+04FF; `&subset=cyrillic` returns an empty answer.
This app's product language is Russian. So every Russian word was drawn by the device's
fallback face while the digits and Latin beside it came from the bundled file — two
typefaces in one row, at different x-heights — and «системный шрифт» was close to a no-op
for the only language on the screen. It had been that way since the design system was taken
from Essentials, which is an English app and had no way to notice. Nothing failed and
nothing was logged.

**The app now bundles both faces and chains them by coverage.** Google Sans Flex draws
Latin and digits, Onest (193 056 bytes, 162 Cyrillic code points) draws Cyrillic, and
`FallbackTypeface.kt` puts them in one `Typeface.CustomFallbackBuilder` — Latin, then
Cyrillic, then the system — handed to Compose through `AndroidFont`, one chain per weight.
That construction is the only one that chooses by coverage: a Compose `FontFamily` picks by
weight and style, and so does a font-family XML. **`CustomFallbackBuilder` is API 29 and
this app's floor is 26**, so Android 8.0 and 9.0 are set in Onest alone — correct, and
merely less like itself.

Google Sans Flex is committed frozen from six axes to `wght` alone, 3.81 MB to 0.26 MB; the
command that produced it is in `docs/build.md`. Both files now carry one axis and the app
varies it, so **the build-time instancer from the batch before is gone**: there is nothing
left to freeze, and `./gradlew` needs no Python again. The guard stayed where the fix was —
`FontAxisTest` fails if the pair cannot draw the Russian alphabet, if either file carries an
axis `Type.kt` never varies, or if a bundled file is named by nothing, and each was proved
red by breaking it.

`GoogleSansFlexRounded` went too. It had three callers asking it for SemiBold and Bold, and
it was registered at one weight, so Compose synthesised what they asked for rather than the
font drawing it — and neither file now has a rounded axis to offer instead.

### Two rules that existed in one shell and not the other

**«🔔 Звонки» in the editor silenced lessons and told nobody.** It wrote the bell rows by
hand instead of going through `structure.write_bell_periods`, which is the half that answers
«which lessons stop ringing». A five-row paste took lessons 6 and 7 off every phone, out of
the widget, the calendar feed and the digests, under «✅ Звонки сохранены: 5 уроков» and not
a word more — while the *same* paste through «⚙️ Класс» warned properly. Two of the twelve
agents found this independently. It now goes through the service, the warning sentence lives
once in `render.silenced_lessons` so the two editors cannot drift again, and the handler's
private copy of `BELL_LINE` is gone in favour of the shared grammar.

**A substitution could be written on a day the resolver never draws.** `_resolve_day`
returns before the override loop out of the school year and on a day marked «выходной»;
`api/edit.py` had refused both since it was written, and the bot had neither check. It was
believed safe because its lesson picker offers nothing on such a day — true of the picker,
not of the flow, because the bot's calendar bounds the year 1 September to 1 August under
the same function name as `schedule.school_year_bounds`, which means something else. So June
is two taps away, and the row was stored, logged and pushed to every subscriber. The rule
and its three sentences now live in `services/timetable_edit.why_no_lesson_can_be_drawn` and
both shells call it.

Also: «✅ Звонки сохранены: 1 уроков» and «Расписание создано: 1 уроков» now count in Russian.

### Gates

`ruff check` clean, `python -m mypy` clean across 83 modules, `python -m pytest -q -n auto`
**1565 passed** (was 1559). `./gradlew test assembleDebug assembleRelease` green: **770 tests
across 106 classes**. The release APK is **3 852 053 bytes** — 80 KB more than the 3 771 298
it started the day at, which is the second typeface bought with the alphabet. No model changed, so no migration: the database stays at `0013`.

### Found and not fixed — the list is the deliverable

None of these is closed; all are verified enough to act on.

- **Cleartext HTTP is permitted for every host** (`network_security_config.xml`), and the
  comment justifying it says «No credentials, no personal data». That stopped being true
  when the app grew its own diary sign-in: `DiaryApi.login` posts a dnevnik2 password
  through the same OkHttp client at whatever address the reader typed. Nothing checks the
  scheme.
- **`POST /api/v1/diary/login` counts only a 401 against its limiter.** A 200 of HTML from
  the upstream becomes a 502 and costs nothing, which `api/diary_web.py` already decided the
  other way at length for the same exception type.
- **419 of `:app`'s 779 strings can never be corrected**: the translation pull request writes
  to `values/strings.xml` only, and `:app` splits its resources across eight files.
- **Twelve callback handlers have no fallback**, so a press on a card whose FSM state is gone
  answers nothing and the button spins — reachable through the command breakout.
- **«Мои задачи» draws up to forty rows over ten buttons**; the tail cannot be ticked or
  deleted.
- **`DATABASE_URL` set to blank or with a leading space** walks past `deployment_problems()`.
- Four rotation defects in `:app` (correction editor, diary correction sheet), the widget's
  narrow-column labels, `docs/widget.md`'s size ladder disagreeing with `docs/design.md`,
  stale counts in `docs/architecture.md` and sections 1, 5 and 7 of this file, `docs/app/`
  missing from the CI path filter, and a dozen string findings — three names for the app,
  two Russian names for `VIEWER`, a nominative weekday where the sentence needs accusative.

### Where the seam actually falls

Traced after the pull request was opened, against both files' `cmap` rather than
guessed. The chain resolves per character, and the base wins every code point it
covers — **464 of them are in both files**, so on API 29+ Onest never draws Latin,
digits or punctuation even though it can.

| | drawn by |
| --- | --- |
| `Algebra`, `08:30–09:15`, `«»`, `—`, `…`, `·` | Google Sans Flex |
| `Алгебра`, `ё`, and **`№`** | Onest |
| `каб. 214` | `каб` Onest, `. 214` Google Sans Flex |
| `9А` | `9` Google Sans Flex, `А` Onest |
| emoji | the system, third in the chain |

`№` is the one worth knowing: Google Sans Flex does not cover it, so «Урок №3»
breaks between the `№` and the `3` rather than between the word and the number.
That and «каб. 214» are the two most frequent mixed strings in the app, and they
are where to look first for a visible seam.

### What nobody has verified

Onest has not been drawn on a device: what is checked is that it declares the axis the app
varies, covers the Russian alphabet, carries its own OFL notice, and renders under
Robolectric. The twelve agents' reports are in this session's transcript, not in the
repository.

## What the batch before added: the typeface is compressed by the build

Four commits in `dev`, open as PR #75, in the milestone `v0.7.0 — Оптимизация` (number 8),
which the owner created for this batch because none of the seven fitted. Its number was not
handed over and nothing here lists a milestone, so it was assigned as 8 — the next after the
seven — and then **read back off the pull request** rather than assumed, which is the only
check available and is what the `github-pr` skill means by reading a number back.

Its title is Russian where the other seven are English, which is the one place the project's
own rule — Russian on the screen, English in everything written about the project — is not
kept today. It is the owner's milestone and theirs to rename; nothing here touched it.

**The instanced font was committed, and that was the arrangement that could not survive an
update.** The batch below took the file from 3.81 MB to 0.29 MB with `fonttools` and
committed the result. It works exactly until somebody updates the typeface, because the
obvious way to update a font is to download it again — and the download is the six-axis
file, so two megabytes come back into the APK with nothing failing. `FontAxisTest` would
have caught it; the point is that it should not have to.

So the font is a **source** now. `core/designsystem/fonts/google_sans_flex.ttf` is the file
as it was downloaded, and `instance<Variant>Font` writes the compressed copy into the
variant's generated resources on every build. What is in the repository is the thing you
would download.

**The compression is a setting, which is what was actually asked for.**
`-Plessons.font.axes` takes a list of axes to keep, or `all`. Measured on one tree, three
builds:

| `lessons.font.axes` | the font | release APK | needs |
| --- | --- | --- | --- |
| `wght,ROND` — the default | 0.29 MB | 3.60 MB | Python 3.10+ with `fonttools` |
| `wght` | 0.27 MB | 3.58 MB | the same |
| `all` | 3.81 MB | 5.71 MB | nothing |

`wght` is not the default although it is smaller: it bakes `ROND` in at 100 and the code
goes on asking for an axis that is gone, which Android answers by drawing the default and
logging nothing. Fifteen kilobytes is not worth that. `all` is the escape hatch for a
machine with no Python, and it is a correct build — the file that ships is the file that was
downloaded, to the byte.

**The cost is that Gradle now needs Python.** Without `fonttools` every Android task stops,
with a message naming `-Plessons.font.axes=all` rather than a stack trace out of a missing
module — proved by pointing the task at interpreters that do not exist. Both Android
workflows install `fonttools==4.65.0`, pinned because that is the one the sizes above were
measured with.

**Two things AGP 9 decides for you here.** `sourceSets["main"].res.srcDir(provider)` is
refused outright — a static directory carries no task dependency, so the font would be
merged before it was written — and the supported way, `addGeneratedSourceDirectory`, is
per variant and chooses the output path itself. Hence one task per variant (the second is a
build-cache hit) and the tests being handed the path rather than guessing it.

**`FontAxisTest` was rewritten around the setting**, and every guard was proved red on its
own side: an axis the app asks for that the shipped font neither declares nor freezes; an
axis the shipped font carries that the build was not told to keep (reproduced by building
`all` and running the tests at the default); a `fontAxisPins` value that disagrees with
`Type.kt`; and the file not actually shrinking. `instance.py` refuses a pin for an axis the
font does not declare, because that is the one hole a test cannot see — the tag would still
be in the build file's map and the test would pass while nothing had been frozen.

**The licence claim was wrong and is now right.** `Type.kt`, `docs/design.md` and the OFL
notice inside the APK all said the file is not modified here. It is: freezing an axis
rewrites the outlines. The OFL permits it outright, and its rename requirement applies only
to a Reserved Font Name, which this typeface declares none of; the `name` table is left
alone, so the copyright and the licence entry in the shipped file are the downloaded file's
own and `FontLicenceTest` still reads them out of what ships.

### Gates

`./gradlew test assembleDebug assembleRelease` green: **770 tests across 106 classes** (was
768 across 106). The server was not touched, so its gates were not re-run — `ruff`, `mypy`
and the 1559 tests were last green on the batch below. No model changed, so no migration:
the database stays at `0013`.

### What nobody has verified

Nothing in this batch has been drawn on a device; what is checked is that the shipped font
declares what the code asks for, that it is smaller than the source, and that it renders
under Robolectric like any other resource. The `all` setting is exercised here by hand and
**not in CI** — CI builds the default, because a CI run exists to build what ships.

The Android workflows' `pip install fonttools==4.65.0` **has** now run on a GitHub runner,
which was the one thing the local gates could not answer: CI was green on `b6f0869` and
again on `3126597`, and neither job could have built at the default setting without the
instancer.

What it costs, read off the job log rather than off the job totals: **4.2 s** for the pip
install, and 0.1 s for `setup-python`, because 3.12 is already on the runner image. The two
`instance<Variant>Font` tasks span 2.4 s inside a two-and-a-half-minute Gradle build, beside
everything else it is doing. The three Android jobs came in at 4 min 14 s without any of
this, 5 min 43 s with it and 3 min 09 s with it again — that spread is Gradle's cache being
cold or warm, and nothing about this step can be read off it.

## What the batch before added: a signed build, two megabytes off it, and the documents

Three commits in `dev`, merged as PR #74 (`9920a9a`), in the milestone `v0.6.0`. This batch
is different from the ones below it: most of it came out of the owner configuring the
release keystore and the two optional secrets **for the first time**, which turned up what
the documents did not say and what the workflow did not tell you.

### The APK is signed now, and the first one cost three attempts

All four keystore secrets and both optional ones are set, and run #21 produced a release APK
signed with the owner's own key — the first this repository has ever made. What it cost is
the useful part. The first attempt died at «Decode keystore» with `base64: invalid input`,
which names neither the secret nor this project; the value had lost its last two characters
to a selection in a phone terminal that stopped one gesture early. PR #73 replaced that
message: it reports the value's length **modulo four**, because base64 always comes in
groups of four and the remainder is the whole diagnosis, and `keytool` now opens the rebuilt
file before the build starts rather than letting a wrong password surface eight minutes
later inside Gradle.

**The next install is an uninstall.** The build on the owner's phone is debug-signed; this
one is not, and Android compares certificates before versions. It takes the classes, the
device token, the diary session and the corrections with it. It is the last time.

### Two megabytes, and not one of them was a dependency

The release APK was **5 989 554 bytes** and is **3 771 086** — `res/fU.ttf` was 2296 KB of
it, 40% of everything shipped and more than all the code.

Nothing about dependencies was the problem, and the batch is worth reading for that as much
as for the saving. The debug APK is 27 MB against the release's 5.7, so R8 already removes
about eighty per cent; `material-icons-extended` declares thousands of icons, 112 are used,
and the rest were gone already. Compose with Material3 is a couple of megabytes of dex on
its own and no pruning reaches it.

The font was all of it, for a reason that is not about character coverage. It ships with six
variation axes, and a variable font pays for an axis in `gvar` — outline deltas per axis per
glyph — which was **3411 KB of a 3811 KB file** while the outlines themselves were 31 KB.
The app touches two: it varies `wght` and sets `ROND` to 100. The four that never move are
frozen now; all 657 glyphs are kept and the file is 0.29 MB. `ROND` stays a real axis rather
than being baked in, which costs 0.02 MB and keeps the code's request meaning something.

`FontAxisTest` holds both directions, each proved red on its own side: an axis the code asks
for and the font does not declare (Android ignores it silently and draws the default), and an
axis the font carries that nothing asks for — which is the saving coming back through the
obvious way to update a typeface. The `name` table is untouched, so `FontLicenceTest` passes
and the OFL travels with the file.

### The documents were read end to end, and sixteen things were wrong

Not stale — wrong. `docs/widget.md` said alarms go through `setWindow`, which was removed
because Doze held it, and that `SCHEDULE_EXACT_ALARM` is not requested, while the manifest
declares it and `USE_EXACT_ALARM`. `docs/api.md` described refusals `PUT /overrides` no
longer makes, gave `POST /diary/login` no throttle although it finally has one that works,
named `reminders.yml` as the cron's caller against what `deploy.md` and `CLAUDE.md` say, and
put the FSM sweep at a day where `STALE_AFTER` is two. `docs/bot.md`'s `/find` was wrong
twice. `docs/design.md` gained the section the `MarqueeText` crash deserved. The index,
`docs/README.md`, was accurate end to end and was left alone.

`docs/build.md` gained what the first configuration turned up: the OAuth App registration as
a table over the form's own fields, and **«Expire user access tokens» must be unticked** —
`AccessTokenDto` has no `refresh_token` field anywhere in `:core:data`, so with that box
ticked the token dies after eight hours, `/user` answers 401, the app forgets it, and the
reader is back at «Войти через GitHub» every day with nothing logged.

### Gates

`ruff check` clean, `python -m mypy` clean across 83 modules, `python -m pytest -q -n auto`
**1559 passed**. `./gradlew test assembleDebug assembleRelease` green: **768 tests across 106
classes** (was 765 across 105). No model changed, so no migration: the database stays at
`0013`.

### What nobody has verified

The instanced font has not been drawn on a device — what is checked is that it declares what
the code asks for and renders under Robolectric like any other resource. Nothing in the
GitHub sign-in has run against live GitHub either; the secrets are set and the button is in
the build, and the first press will be the owner's.

## What the last session added: the two loose ends

One commit in `dev`, open as PR #72, in the milestone `v0.6.0`. Both were named by the batch
below as found-and-not-taken, and both are now taken.

**A command the bot does not have answers.** Telegram stays silent on one, and for a bot with
a single screen that is fine — but once a command started breaking out of a half-finished
form, silence became misleading: «/wek», a typo for «/week», dropped what somebody was
filling in, said so, and then nothing happened. It says «🤔 Не знаю такой команды. Наберите
/help, чтобы увидеть список.»

The handler is a router included **last** in `build_router()`, because it matches any command
at all and so everything that answers one has to be asked first. That contract is invisible,
so a test walks the whole `COMMANDS` list — the one BotFather shows — and fails if any of
them reaches the catch-all, turning a comment that had stood over that list since it was
written into something checked. Proved red both ways: unregistering the router fails the two
unknown-command tests, and moving it to the front fails all twenty-four menu commands.
Private chats only, because in a group Telegram hands «/start@otherbot» to every bot that can
see it.

**«Отладка» keeps the report being read when the phone is turned.** It is the screen where a
rotation costs most — the report is open while its important line is being copied into a
message to whoever can fix it — and a rotation dropped the reader back to five
identical-looking timestamps. The name is saved rather than the file, and the file is looked
up in the list again, the same shape as the calendar's lesson sheet.

**One thing a test here does not prove, and says so in its own words.** The second case, a
report whose file has gone, does not discriminate between looking the name up in the list and
building a `File` from it blind: the body is read with `runCatching`, so a deleted file is an
empty string either way and the test passes against both. That was checked by running the
second implementation, not assumed. The lookup is still right for a reason no test here
reaches — the name comes out of a bundle another build wrote, and the lookup can only ever
yield a file this app listed.

### Gates

`ruff check` clean, `python -m mypy` clean across 83 modules, `python -m pytest -q -n auto`
**1559 passed** (was 1533). `./gradlew test assembleDebug assembleRelease` green: **765 tests
across 105 classes** (was 763 across 104). No model changed, so no migration: the database
stays at `0013`. `docs/bot.md` gained the unknown-command rule beside the refusal it already
described, and the test counts moved in all four places.

### What is left

Nothing here has run on a device or against a live Telegram, as with everything above it.
**What only the owner can do** is unchanged and still outstanding: register an OAuth App with
**Enable Device Flow** ticked, put its client id in the repository secret
`LESSONS_GITHUB_CLIENT_ID` and an address in `LESSONS_CONTACT_EMAIL`. Until then «Войти через
GitHub» is in no build and the APK run summary says «off».

## What the last session added: the four things the sweep would not decide

Three commits in `dev`, open as PR #71, in the milestone `v0.6.0`. The batch below found
these four and deliberately left every one of them, because each is a decision rather than a
correction. The owner took all four; what follows is what each decision *was*, because the
code is the easy half.

**A command wins over a half-finished form.** `/week` typed at «Теперь пришлите текст
задания:» was committed as an assignment whose text was «/week», audited, and pushed to every
subscriber — and which commands escaped was an accident of which router was included first.
The decision could have gone the other way (refuse the command, keep the form); it did not,
because somebody who types a command mid-form wants to be somewhere else. So the state is
dropped, the bot says «✖️ Форма отменена: вы отправили команду.» and the command runs as
though the form had never been open.

The mechanism matters more than the behaviour. It is **one outer middleware on the message
observer**, not a check in each step: the state has to be *cleared*, which no filter can do —
a filter would let the command run and leave the form sitting there to eat the next plain
message — and clearing `raw_state` with it means no state-filtered handler can match a
command, whoever writes the twenty-sixth step. The rule-holder test is parametrised over every
`State` discovered in the two state modules, so a new form step is a new case with nothing to
remember. Unregistering the middleware fails 45 of its 48 tests and reproduces the production
defect exactly; the three that stay green are the three that must be green both ways.

**`request_approve` has one home.** Forty lines carried twice, in `api/manage.py` and
`bot/handlers/manage.py`. `services/access.py` holds them; both shells find the request, call
it, translate the refusal into a 403 or a Russian alert, commit and notify. The API keeps its
extra freedom to name a different role in the body, which `can_grant` still gates inside the
service. An anti-drift test watches both shells call it.

**A class that moves from 9 to 10 stops being called «9А».** Moving `grade` or `letter`
recomposes the name — **unless the same request also sets `name`**, because an admin who names
the class has said what they want. The audit line is written only when the name actually
changed. The bot has no grade editor, so there is no twin to keep in step today; if «⚙️ Класс»
ever grows one, the rule belongs in `services/terms.py` rather than in the endpoint.

**The calendar's two sheets survive a rotation.** `Lesson` cannot be saved — it comes from
`:core:model`, which is pure JVM and must stay that way — so what is saved is what the sheet
is *about*: the date and the lesson's number, which name one lesson, because the server
resolves a date into at most one lesson per number and nothing between there and the screen
adds a row. The lesson is looked up in the week on every composition, and one that is no
longer there opens no sheet. That lookup closed a second defect the rotation only made
visible: the sheet held the object it was handed, so withdrawing a substitution while its
sheet was open left the room, the teacher and the homework of a lesson that had stopped
existing on screen, with no rotation needed.

### Gates

`ruff check` clean, `python -m mypy` clean across 82 modules, `python -m pytest -q -n auto`
**1533 passed** (was 1474). `./gradlew test assembleDebug assembleRelease` green: **763 tests
across 104 classes** (was 760 across 103). No model changed, so no migration: the database
stays at `0013`. `docs/bot.md` gained the command rule beside its FSM paragraph, and the test
counts moved in all four places that carry them — `CLAUDE.md`'s was two batches stale.

### What is left, and what nobody has verified

Nothing here has run on a device or against a live Telegram. Two loose ends were found and
not taken: «/notacommand» now gets the «Форма отменена» line and then silence, because this
bot has no unknown-command handler — a separate card if it is wanted; and «Отладка» holds the
crash report it is reading in a plain `remember`, so a rotation drops the reader back to the
list. That one is a file name and saves cheaply.

**What only the owner can do** is unchanged from the batch below and still outstanding:
register an OAuth App with **Enable Device Flow** ticked and put its client id in the
repository secret `LESSONS_GITHUB_CLIENT_ID`, and an address in `LESSONS_CONTACT_EMAIL`.
Until then «Войти через GitHub» is in no build, and the APK run summary says «off».

## What the last session added: the crash, the button that was never built, and twelve defects

Seven commits in `dev`, open as PR #70, in the milestone `v0.6.0`. Three parts: the crash the
batch below could not explain, a feature that was missing from every APK ever built, and a
five-agent sweep of the whole tree.

### The crash after the link, which was a line of layout

It closes the report the batch below could not answer — «приложение вылетает через несколько
секунд после привязки Telegram», and then «предлагает очистить кэш» — and **it was not the
link.**

The bug report the owner sent from the phone carries the fatal exception, on the main
thread, once at 14:36 and then in a loop for seventy seconds:

```
java.lang.IllegalStateException: Asking for intrinsic measurements of SubcomposeLayout
layouts is not supported. This includes components that are built on top of
SubcomposeLayout, such as lazy lists, BoxWithConstraints, TabRow, etc.
```

The stack is R8-obfuscated and names neither a file of ours nor a caller of one. What it did
not have to name: **the app contained exactly one `SubcomposeLayout`**, the
`BoxWithConstraints` inside `MarqueeText`, and that component is drawn at two dozen call
sites — every group row, every lesson row, every section header, the toolbar, the pickers.
A `SubcomposeLayout` cannot answer «how tall would you be at this width», and nothing in this
repository spells `IntrinsicSize`, which is exactly why it looked safe: Material spells it
inside its own rows, so the question arrives at a call site that never mentions it. **Which
Material component asked was not identified and did not need to be** — the component that
could not answer now can, wherever it is placed.

It came in with #62 and has been in every build since, including the one the owner is
running. The link had nothing to do with it beyond redrawing the screen.

The fix is the width without the subcomposition: `Modifier.onSizeChanged`, outermost of the
three modifiers so that it reports the window rather than the string the marquee scrolls
through it. The price is one frame — until the line has been measured once its width is
`Constraints.Infinity`, nothing can overflow it, and a line that will scroll is drawn still,
which is what the first frame of a marquee looks like anyway.

Three things about the tests are worth carrying forward:

* **The reproduction is one line.** `Row(Modifier.height(IntrinsicSize.Min))` is the
  question, and it throws that exact exception on the old component.
* **Three of the component's own tests were named for a line that scrolls and never reached
  one.** Robolectric lays text out with no fonts — every glyph costs about a pixel — so
  «По этому предмету ничего не задано» measures 35 px and sits inside a 120 dp box with room
  to spare. They use a string twenty times as long now. One of them, «stays inside its box»,
  was also reading a number that says nothing: a scrolling line's text node really *is* as
  wide as the string. What holds is that the row is not pushed apart, so what the test looks
  at is a neighbour placed after it.
* **`NoSubcomposedLeafTest` holds the rule rather than review.** It reads
  `:core:designsystem`'s source and fails on a `BoxWithConstraints`, a `SubcomposeLayout`, a
  `TabRow` or a lazy list, with the same `//`-above opt-out `NoEllipsisedLineTest` uses.
  `:app` and `:widget` are outside it for a reason and not for convenience: a lazy list on a
  screen is the root of its own layout and nothing above it asks it to predict a size — seven
  screens hold one on those terms. The rule is for a component written to be placed inside a
  layout it does not own.

Each of the three guards was proven red first — the intrinsic query against the old
component, the scrolling test with the width feed cut, and the source rule with the word put
back into a component.

### «Войти через GitHub» has never been in an APK

The row is shown only when the client id is non-empty, `app/build.gradle.kts` reads it from
`LESSONS_GITHUB_CLIENT_ID`, and **`apk.yml` passed that property on no day of its life**. So
every build the workflow ever made had it blank: the row hidden, and with it the only way to
file a bug report from inside the app and the only way to send a correction as a pull
request. `LESSONS_CONTACT_EMAIL` was the same story one button along, which is «Отправить
письмом». Nothing failed and nothing warned — an unset property is an empty string, and
downstream that is a feature switched off.

Hiding the row stays: a row that opens a sheet saying «не настроено» is a row about the build
rather than about the reader, and that is written down in `docs/design.md`. What it costs is
that the whole weight then rests on the build actually passing the property, so the run
summary reports each one as on or off. `BuildPropertyReachTest` reads the build script and
the workflow together and fails on a property that is read and never assigned; its first
version passed on the name appearing in a comment, and was caught by breaking the wire on
purpose. Both files are declared inputs of the test task — without that, editing the workflow
left the task `UP-TO-DATE` and the check unrun.

### Twelve defects, from five agents, each closed with a test proven red

On the server: **the diary sign-in throttle did not exist and a wrong password answered
500** — `JoinAttempt.client_key` is `VARCHAR(64)`, a SHA-256 digest fills it exactly, and the
diary's key was that digest with «diary:» in front, so the insert raised out of the `except`
that was re-raising the 401; SQLite ignores a `VARCHAR` width, which is why the test named
after that limit passed all along. **A substitution could be stripped of the subject that
made it visible** — the guard ran only on create, and the row is reachable in two writes.
In the bot: a typed year one digit too long left the handler through `OverflowError`; «²»
passes `isdigit()` and `int()` refuses it, in two callback handlers as well as the date
parser; a lesson edit whose slot had gone was audited as though it had happened; the canteen
could be marked on a break the bells no longer ring.

On the phone: **the widget's loading layout drew its message white on near-white for Android
8 to 12** — `DeviceDefault` below API 29 is the dark variant and the surface comes from
`values/`, so the layout drew exactly the blank rectangle it exists to prevent. **The guide
froze in one language for ever once refreshed in the other** — one manifest version for two
files, recorded under language-less keys. **One dropped request ended a GitHub sign-in that
GitHub had already granted.** **The edge wash was drawn on every phone below Android 13 while
the only switch that names it said it was off and could not be pressed** — the blur is gated
on API 33, the gradient tint is not, and the row gated the whole switch on shaders. **Turning
the phone re-opened a pull request already opened** — a `LaunchedEffect` key survives a
recomposition, not a configuration change.

**Reported and deliberately not fixed**, because each is a decision rather than a correction:
a command typed into an open bot form is saved as the answer, so «/week» at «пришлите текст
задания» becomes homework called «/week» and goes to every subscriber, and which commands
escape is an accident of router order; `request_approve` is forty duplicated lines across the
API and the bot; `PATCH /manage/class` can move a class from 9 to 10 without its name ceasing
to say «9А»; `WeekScreen`'s two sheets close on a rotation, and `Lesson` cannot be made
parcelable without breaking `:core:model`'s purity.

### Gates

`ruff check` clean, `python -m mypy` clean across 81 modules, `python -m pytest -q -n auto`
**1474 passed** (was 1465). `./gradlew test assembleDebug assembleRelease` green: **760 tests
across 103 classes** (was 738 across 96). No model changed, so no migration: the database
stays at `0013`.

**What only the owner can do.** Register an OAuth App — Settings → Developer settings → OAuth
Apps → New OAuth App, with **Enable Device Flow** ticked — and put its client id in the
repository secret `LESSONS_GITHUB_CLIENT_ID`, and an address in `LESSONS_CONTACT_EMAIL`.
There is no client secret to register. Until then the run summary says «off», which is the
honest answer rather than a silent one. And install a build carrying all of this and link a
phone again: none of it has run on a device, and the uninstall-first caveat below still
applies to a debug-signed APK.

## What the last session added: a crash report that its own phone can read

One commit in `dev`, open as PR #69, in the milestone `v0.6.0`. It is the answer to a
question that could not be answered: **«приложение вылетает через несколько секунд после
привязки Telegram» — and there was no way to get the stack trace off the phone.**

The switch that records crash reports has always been on «О приложении», where everybody can
reach it. The two ways to *read* one were the administrator's — the bug button beside the
toolbar's pill and the management page's own row. So anybody who is not an administrator
could turn the feature on, crash, and then have nothing: the file is in the app's external
files directory, which Android 11 stopped file managers from opening, and no screen they
could reach would show it. On a Samsung, with no computer, that is a dead end.

The row is now directly under the switch that writes the reports — the same row the
management page had, moved to `ui/debug/DebugRow.kt` and shared rather than copied, with its
strings losing the `admin_` prefix along with the gate. Two tests: one composes the row,
presses it and asserts the sheet opens; the other reads `SettingsScreen.kt` and fails if the
«О приложении» group stops calling it or starts gating it on the role, because a composition
test cannot ask whether a row is on the page everybody has. Proven red against the code
without the fix.

**The crash itself was not fixed by this batch — the one above does that**, and it was none
of the things ruled out here. What was ruled out, by reading and
by one throwaway Robolectric reproduction: the account section's composition survives the
link landing under it (`Ready(unlinked)` → `Loading` → `Ready(linked, ADMIN)` →
`isRefreshing` both ways); the exact-alarm path catches its `SecurityException` and asks
`canScheduleExactAlarms()` first; nothing polls `/me`; and linking triggers no sync at all —
`syncNow` has two callers, `SessionEffects` and the widget. So the sync whose state updates
in the report is the periodic one, landing near the link by coincidence rather than because
of it. The next step needs the stack trace this batch makes reachable.

`./gradlew test assembleDebug assembleRelease` green: **738 tests across 96 classes**, up
from 736 across 95.

**What only the owner can do.** Install a build carrying this row — which on the current
debug-signed APK means uninstalling first, and that takes the device's classes, token, diary
session and corrections with it. And the reports stay off until the switch is on, by the
earlier deliberate decision about what may land on disk: this batch changes who can read what
is written, not when it is written.

---

## What the last session added: the guide is fetched, and a debug-signed APK explained

Four commits in `dev`, merged as PR #67 (`0957628`), in the milestone `v0.6.0`. Two
unrelated things that arrived in one batch because the first was a question about the
second's build.

**Installing 0.6.0 over an older build failed, and the repository was wrong about why.**
«Приложение не установлено», after offering to update, with the version raised. The cause is
that **the debug keystore is generated on the machine that builds and thrown away with it**:
the alias and both passwords are constants, the key pair is not, a runner is a fresh machine
every run, and the Gradle cache covers `~/.gradle` rather than `~/.android`. So every APK run
signs with a certificate that has never existed before, and Android compares the certificate
before it looks at any version. Measured rather than reasoned — the APK from run 15 carries
`CN=Android Debug` with `validFrom` two minutes into that run's own build step.

Two statements in this repository said otherwise. `docs/build.md` said the debug key "is the
same for everybody"; `apk.yml` said twice it is "the same certificate on every machine on
Earth, so anybody could then build an update Android would accept". **Nobody can** — the key
is gone, which is the real cost, because that includes whoever published it. The second real
cost is authorship: `CN=Android Debug` is what every debug build says. The gate that refuses
to publish an unsigned release is unchanged; only its reason is. `docs/build.md` now has the
section a reader meets this in, including that the only cures are an uninstall — which takes
the device's classes, token, diary session and corrections with it — or the four secrets.

**`apk.yml` also discarded any `versionCode` it was given**, overriding it with the run
number and offering no input for it. There is a `version_code` input now; blank still means
the run number, and it is validated before anything else runs — empty, `0`, `-3`, `1.2`,
`abc`, `"12 34"` and a command substitution are all refused rather than evaluated.

**The in-app guide stopped being 103 string resources.** It is two markdown files in
`docs/app/` — `guide.ru.md`, the source, `guide.en.md`, its translation — plus a manifest
naming the documentation's version and the app version it describes. The app fetches them
from `raw.githubusercontent.com`, stores them in its own files, and falls back to the copy in
its assets; `docs/app/` **is** `:core:data`'s asset folder rather than a copy of it, so the
bytes in the APK are the bytes in the repository. Verified by listing the assets of both
built APKs.

The format is a deliberately small subset — `##` a page with its metadata comment, `-` a
list, `1.` with a bold lead a step, `>` an aside, and `**bold**`, `` `code` ``, `[text](url)`
inside a line. **The parser never throws**: an HTML error page, a JSON body or a truncated
file parses to no pages, and the repository keeps what it already had rather than replacing a
working guide with an empty screen.

**Every page now states which documentation it is** — version, date, and the app version it
was written for — and adds a second line only when there is something to say about the copy:
no network, an answer that was not a guide, or the copy the app shipped with.

**The sections stopped being screens.** They are peers, so they are a `HorizontalPager`
swiped like the three home tabs, and the toolbar scrolls it rather than pushing a screen.
Back therefore has one meaning here, which is what the arrow beside the pill does: leave the
documentation. `DocsHistory`, the page enum and the depth-per-section that existed to animate
pushes between peers are gone. Pull to refresh uses `LessonsPullToRefreshBox`, the same
expressive loader the two other refreshing screens use, and it shows while the automatic
check runs as the guide opens.

**One defect found by re-reading the diff rather than by a test.** `storedOrBundled`
preferred a fetched copy over the bundled one unconditionally, so a phone that fetched
version 3 a year ago and then installed an APK carrying version 5 would have been shown the
**older** guide — offline, for as long as the network stayed down, which is exactly the case
the fallback exists for. The rule is a named function of two numbers now, `preferStoredCopy`,
and it is tested; proven red against the code without the fix.

**What it gives up, and it is a real cost.** The guide's text is no longer a resource, so
correction mode cannot touch it and `ResourceTranslationTest` no longer guards it. A wrong
sentence in the documentation is now a pull request against `docs/app/` — the same place the
app reads it from. `DocsGuideParityTest` took over what could be kept: both languages parse to
the same pages, in the same order, with the same ids, from the same blocks, with no list of
one point, no step numbered out of sequence and no paragraph written twice. It reads the
shipped files rather than a fixture.

`./gradlew test assembleDebug assembleRelease` green: **736 tests across 95 classes**, up
from 725 across 93. The README, `docs/architecture.md` and this file's cheat-sheet carry that
number; `architecture.md` gained the section describing the pipeline and `design.md`'s line
about the documentation's longest page is corrected.

**What nothing has verified.** Not one line of the fetch has run against GitHub — the parser,
the parity of the two files and the choice between two stored copies are tested, the network
path is written and never executed, exactly like the translation flow in #64. Nothing has
been pressed on a device: the pager, the loader, the banner and the arrow are laid out by
code and seen by nobody. The bundled assets are proven only by the APK's contents, because
`:core:data`'s tests have no `Context`. And the new `version_code` input has never been run.

**The chain of close-outs stops by rule now, and merging no longer waits for a sentence.**
Every batch ends in a close-out, whose own merge is then a thing no close-out describes —
and writing one for that needs another, for ever. The rule: a close-out rides inside its
batch's own pull request while that pull request is open, it names itself as the only thing
open, and **the SHA of its own merge is written by the next batch** rather than by a pull
request about it. Separately, the owner asked for a green pull request of this session's own
work to be merged without being asked each time; that is recorded in the `github-pr` skill
as their standing instruction, with the five things to check first and the cases it does not
cover — an unsettled migration, somebody else's pull request, and «I want to look at this
one».

**And the hook added in #65 was wrong, in the one case the rule prefers.** It fired after
#67 merged, saying the close-out was missing — while `HANDOVER.md` had described that batch
since `6caca07`, inside the pull request, which is exactly what the rule asks for. A
close-out written that way is landed **by** the merge commit, so it is always older than it,
and a hook comparing only timestamps calls it missing every time the rule is followed
properly. It now asks first whether the merge's own diff touched `HANDOVER.md`; the
timestamp is the fallback for a close-out committed after a merge instead. Tested in all
three directions in a throwaway repository — carried by the merge, not carried, committed
afterwards — and silent on this tree, where it had just spoken.

---

## What the session before it added: a correction goes out as a pull request

Three commits in `dev`, merged as PR #64 (`e4361a0`), in the milestone `v0.6.0`. Before them,
PR #63 (`ec0d976`) carried the previous batch's close-out in this file and nothing else, and
went into `main` on the owner's instruction.

**Correction mode used to end in a fragment somebody else had to paste.** It produced a
`<string>` element, and that was the whole delivery: the reader copied it, or shared it, and
the work reached the project only if a second person carried it. The session sheet now also
offers to open a pull request, and it is opened **from the account the reader signed in
with**, not from the project's.

**The sign-in row moved to where it is needed.** It sat in the group about GitHub under
«О приложении»; it now sits directly above the correction-mode switch, which is where
somebody looking for it will be. Signing out stays in the old group beside the bug report
the account is otherwise for — two rows on one page showing the same account state is one
row too many. **Signing in is not required to make corrections:** the mode, the editor and
the session are local and work offline, so only the button that *sends* them goes dark
without an account. The anonymous update check was left alone for the same reason — it needs
no token, and a sign-in wall there would break something that works.

**Putting a corrected string back is its own file, with its own test.** `StringsDocument`
replaces one element's body and leaves every other byte of `strings.xml` where it was. Seven
tests hold the parts that would go wrong quietly: `name="settings_title"` must not match
`settings_titles_plural`, a `<string-array>` of that name is a different resource, an
attribute written before `name` must not hide the element, a `>` inside a value must not cut
the body short, and a key the file does not declare is refused rather than appended.

**What was taken from Essentials, and what was not.** Taken: where the row sits, the shape
of the flow, the two states of the account row. Not taken, because each is a defect rather
than a decision — their submit button is enabled while signed out and bounces the press to a
prompt; they clear the session on a *posted comment*, which is not a delivered correction, so
if the workflow behind it fails the reader's work is gone and nothing says so; their
`triggerWorkflowDispatch` passes the user's OAuth token as a workflow input, where it is
visible in the Actions UI and kept in the run record; and their translation feature carries
hard-coded English literals beside `stringResource` calls, which `ResourceTranslationTest`
would refuse here.

**The transport is different on purpose.** Essentials' public path is a comment on a
hard-coded discussion thread, turned into a pull request by a workflow holding
`contents: write`; the contributor survives only as the commit author. That needs a
discussion, a workflow and a write permission this project does not want — and it does not
do what was asked, because the pull request is not the reader's. A fork and a pull request
are, and the `public_repo` scope the sign-in already requests covers both, so nobody has to
re-authorise.

**Two defects in this batch's own code, found by re-reading it rather than by a failure.**
A repository merely *named* `lessons` was taken for the fork — the check asked for
`/repos/{login}/lessons` and read any 200 as "the fork is there", so a reader who already
owned an unrelated repository of that name would have had a branch cut in it and a commit
written to it, over a corrected string; it now reads the body and requires a fork whose
parent is this project. And the submit ran on `rememberCoroutineScope()`, which dies with the
composition, while the comment above it claimed the view model owned it and it survived
dismissal — closing the sheet cancelled the work. The launch moved to `viewModelScope`, which
fixed the comment's honesty as well as the behaviour. A third was caught before it shipped:
acknowledging the outcome inside the `LaunchedEffect` that opens the browser would have
cleared the message in the same frame it appeared.

**Two things in that code that are easy to get wrong.** The branch is cut from the
**upstream** commit, not from the fork's own head: a fork made once and never synced is
behind by everything merged since, and would offer all of it back as reverts. And the
**owner of a repository cannot fork it** — GitHub answers 422 — so their branch goes straight
to the upstream repository, which is the case the first person to try this will hit.

**Three places said 718 tests across 92 classes; the suite is 725 across 93.** This file's
cheat-sheet, the README's «Honest status» table and `docs/architecture.md` are corrected.
`docs/architecture.md` was wrong in a second way that the total had hidden: its per-module
breakdown still summed to 709, because the previous batch moved the total and not the five
numbers under it. They now read `:core:model` 94, `:core:data` 234, `:core:designsystem` 54,
`:widget` 68, `:app` 275 — counted from the test XML of a real run, and they add up.

**What nothing has verified, and it is the whole point of the batch.** Not one line of the
fork, the branch, the commit or the pull request has executed against GitHub. There is no
test for it and no way to write one here — it needs an account, a token and a real
repository. The two defects above were found by reading, and the same reading cannot prove
there is not a third. **The first press should be the owner's, not a reader's.** Nothing has
been pressed on a device either: the row, the dark button, the spinner and the message after
it are laid out by code and seen by nobody.

**Updating this file is now a written rule rather than a request (PR #65).** It had to be
asked for twice — once after #62 and once after #64 — and each time a batch had been called
done while the document a new session starts from still described the batch before it. The trigger is
the merge, and it is written in `CLAUDE.md`, in `AGENTS.md`, in the `handover` skill (which
also lists what goes stale mechanically, because reconstructing that list by hand is most of
the work) and in `github-pr`'s new «After it merges» section. `/where-are-we` now compares
the document's claimed state against the commits and says when it is behind. And a second
hook, `.claude/hooks/handover-behind.sh`, says one sentence on `Stop` when a merge commit is
newer than the last commit touching this file — which is true only in the window after a
merge and before the close-out, and stops being true the moment the file is committed.

**The hook has since fired for real, and it was right.** It was proved in a throwaway
repository before it was committed, and the pull request that carried it said in as many
words that nothing had seen it fire in a session. #65 merged, `dev` was fast-forwarded, and
it spoke — about #65 itself, which this file did not yet describe. That is what the sentences
above and the paragraph at the top of this file are. **The regress ends the same way #63
ended it:** the close-out describes its own pull request while that pull request is open, so
the file is already true when it merges. A close-out does not get a close-out of its own.

**Deliberately left alone.** Milestone 6's description on GitHub still reads «PRs #60–#62»
though it now holds #63 and #64 as well — no tool here edits a milestone, so that is the
owner's line to change. The update check stays anonymous: it asks GitHub for the latest
release, needs no token, and putting it behind the sign-in would take a working feature away
from everybody who never signs in.

---

## And before that: nothing on a screen is cut off

Five commits in `dev`, merged as PR #62 (`f13d60e`), in the milestone `v0.6.0`. Three pieces,
and the second and third are consequences of the first rather than separate work.

**A line that does not fit now scrolls instead of ending in «…».** «По этому предмету ничего
не задано» was drawn as «По этому предмету ничего н…» in a sheet with a screenful of room
under it. The app already had the better answer in one place — the segmented picker's labels
scroll and fade at both ends, because on a picker the whole word *is* the button — and that
behaviour is `MarqueeText` in `:core:designsystem` now, on 21 call sites. It measures the
string against the width the box actually has before deciding, because the layout cannot be
asked: `basicMarquee` hands the text unbounded width, so the node never reports overflow and
`onTextLayout` answers `false` for ever. A label that fits is left exactly as it was.

**The nine blocks that cannot scroll stopped being capped instead.** A `maxLines = 2` block
has no single line to move sideways, and stopping there would have left the ellipsis
standing — only the shape of the answer had to change. Every one of the nine sits inside
something that scrolls, so the cap is simply gone: a sheet heading, a screen header and its
subtitle, the supporting line of both row shapes, `RowText`'s subtitle, a lesson's hand-typed
note, the hero card's detail. The row grows and nothing is lost. The one with least excuse
was `TimetableSheet`, which echoes the lines the parser refused so the typo in them can be
found and was cutting them at two. `TimelineBlock` on the week ruler went the other way: its
height *is* the lesson's duration, floored at 30 dp, so it is the one block in the app that
genuinely cannot grow, and its title marquees.

**Then the test found a silent clip nobody was looking for.** A bare `maxLines = 1` with no
`overflow` does not ellipsize — it clips, with nothing to show that anything was cut, which
is the same refusal with the warning removed. The hero card's countdown row had one: the row
wraps its content and shares no weight, so at a large font scale the number takes the width
and «до конца» loses its end in silence. That label marquees with a weight now. The countdown
beside it deliberately does not — it is rebuilt every tick, so a marquee would be handed a
new string each second and restart from the left for ever.

`NoEllipsisedLineTest` holds all of it, and it reads **every** module rather than the one it
lives in — the same correction `ResourceTranslationTest` once needed, for the same reason. It
refuses any `TextOverflow.Ellipsis` and any bare `maxLines = 1`, with one opt-out: a `//`
line saying why, which six places carry. Proven red twice, and the second time against real
code rather than a planted fault — the comment explaining one cap sat above the `Text(`
instead of above the cap itself.

**Three documents said 709 Android tests across 90 classes; the suite is 718 across 92.**
The README's «Honest status» table, this file's command cheat-sheet and
`docs/architecture.md` are corrected. A fourth mention is deliberately left standing: this
file records the gates «on the whole tree at `d330d68`, the last commit before the merge»,
and at that commit there really were 709 across 90. Rewriting it to today's number would
turn a true record of a named commit into a false one.

**Every pull request now carries a milestone, and the rule is written down.** Sixty-two had
gone in without one, and they have one only because somebody went back and did it by hand.
There are no issues in this repository — not one has ever been opened — so the milestones are
the only grouping its history has. Seven exist and they are **retrospective**: the boundaries
were read off the history rather than declared, and nothing here has ever been tagged or
released, so `versionName` is still the `0.1.0` default.

| # | Milestone | Covers |
| --- | --- | --- |
| 1 | `v0.1.0 — First run on a phone` | #1–#14 |
| 2 | `v0.2.0 — The diary, and the class run from the bot` | #15–#17, #28–#31 |
| 3 | `v0.3.0 — The school year` | #27, #32–#35, #43 |
| 4 | `v0.4.0 — Nothing breaks in silence` | #44, #45, #50 |
| 5 | `v0.5.0 — A public repository` | #46–#49, #51, #55–#57, #59 |
| 6 | `v0.6.0 — One container, and nothing cut off` | #60–#74 |
| 8 | `v0.7.0 — Оптимизация` | #75–#85, #128 — the one Russian title |
| 9 | `v0.8.0 — On a device` | issues #109–#117, #130–#132; #129, #133 and #134 — the latest that **exists** |
| 7 | `Dependencies` | every dependabot bump; deliberately not a version |

**A tenth milestone does not exist yet, and #140 waits on it.** The second-diary work
belongs to none of the nine — it is neither «On a device» nor a dependency bump — so the
owner was asked to create a tenth, e.g. «v0.9.0 — A second diary», with the title and
description already written. Until they do, #140 is a draft with no milestone.

**Nothing in a session here can create a milestone**, only attach one — the owner created
the ninth on 22 September 2026 and issues #109–#117 are on it. **It is the first milestone
that groups issues rather than pull requests**, and the first whose work cannot be done
without an emulator or a phone.

From here the rule is in `CLAUDE.md` and in the `audit`, `github-pr` and `release` skills:
a found defect becomes an issue before it becomes a fix, the pull request ties itself to it
with `Closes #NN`, and a release explains every change with both numbers beside it.

**What that rule had to record is what a session cannot do.** Nothing here creates a
milestone or even lists one — no tool, no `gh` CLI, and `issue_write` accepts only a number
that already exists. So when none fits, ask the owner with the title and the description
already written, rather than inventing a version or leaving the pull request bare. Two traps
are written down beside it: a milestone's number can be read back by assigning it and
searching `milestone:"<title>"`, whose result embeds the milestone object; and GitHub's
search index lags the write by up to a minute, so `is:pr no:milestone` reported two bare
pull requests that already carried one. The procedure is in the `github-pr` skill, with a
pointing line each in `CLAUDE.md` and `AGENTS.md`.

**Deliberately left alone.** `:widget` is outside the no-ellipsis rule and cannot be brought
in: Glance has no `TextOverflow` at all, and no marquee either, because RemoteViews has no
frame loop and a widget cannot animate anything. `WidgetStrings.ellipsize` writes the «…»
into the string by hand because the platform leaves no other answer, and the test says so
where it excludes the module rather than letting a vacuous pass look like coverage.
`.github/copilot-instructions.md` did not get the milestone rule: it is short on purpose
because it is read on every request, and every line in it is about what not to suggest
inside code.

---

## Earlier still, on top of the audit

Twenty-five commits after `13348d5`, in five pieces. The first two finished the audit batch;
the last three are things that batch left behind, and one of them was found by re-reading
the session's own work rather than the project's.

**A re-check of the whole batch, and then the remainder of it.** The server half: both bells
writes now answer the caller with `silenced_lessons`, because shrinking a schedule leaves
every lesson past its new last rung stored and drawn nowhere and only the bot was saying so;
a substitution on «1 сентября» is no longer refused as a summer date (`school_year_bounds`
files both June and the first days of a September whose 1st falls at a weekend before
`year_start` — the next four are 2029, 2030, 2035 and 2040 — and one sentence covered both);
`render_import_preview` stopped writing its cap
twice; and `MESSAGE_LIMIT`'s comment now names the one direction in which a raw character
count reads *lower* than Telegram's, which is emoji outside the BMP. The Android half reads
that new field and says «2 урока перестали звонить» under «Сохранено». `docs/bot.md` gained
the budget rules, which were true in six renderers and written down nowhere, and `docs/api.md`
documents the field.

**Three Android gaps closed.** A phone that signed out and rejoined in the same process got
one refresh and then nothing until the next cold start — `schedulePeriodic`'s only caller
watches the interval, and a re-join moves no interval, so the re-arm moved into
`SessionEffects`. The `304` path stopped re-reading the whole cached year to re-derive an
alarm it almost always finds already armed, and asks `FLAG_NO_CREATE` instead; what that
gives up is written where the decision is. And the alarm chain, the change fingerprint and
both of the widget's reads — the redraw and the tick scheduler that arms it, which is the most
frequent read there is — now take a fortnight rather than a year, through `snapshotAroundToday`,
the bounded read that
`docs/` said could not be written, because `schoolDayAfter` has to reach September from July;
it is resolved in SQL beyond the bound and handed over as `nextSchoolDay`. Separately,
`PeriodsForm` keeps its six bell times through a rotation, and `StabilityPromiseTest` is no
longer one regex for `var`.

**Dependency injection with dishka** (`server/app/di.py`). A session was made in three
places — a FastAPI dependency, `SessionLocal()` in the bot's middleware, and `session_scope`
in `scripts/seed_demo` — each with its own view of whether the caller or the maker commits.
`session_scope` is the one still standing, because a script is not a request and has no scope
to take a session from. It is
now one container: `Settings` and the session factory at app scope, one `AsyncSession` per
HTTP request or Telegram update. All sixty-four endpoints ask with
`session: FromDishka[AsyncSession]` and `db.get_session` is gone; the bot's
`ContextMiddleware` opens the update's scope itself and takes the session from the same
provider, and still commits there. Dishka rather than FastAPI's `Depends`, which is
already a DI system, for one reason: `Depends` cannot serve an aiogram handler. Three things
worth knowing before adding to it are written in the module: it must not import
`dishka.integrations.aiogram` (that puts aiogram on every cold-start path), the container is
built with `STRICT_VALIDATION` so a duplicate provider is an error rather than silent
shadowing, and `app/api/routing.py` exists because dishka's compiled wrapper carries its own
globals, so under postponed annotations FastAPI took an endpoint's `-> Response` for a
response model.

**An adversarial re-pass over the container work itself**, which found seven things — most
of them latent, one of them behaviour, and several of them defects in what the session had
just written rather than in the project.

* `write_bell_periods` answered «перестали звонить уроков: N» with a *state* — which rows
  these bells do not cover — while the field it fills is documented as an *event*. Nothing
  deletes an orphaned row, so the same write repeated said it again, and so did nudging one
  bell by five minutes. It asks `lessons_silenced_by` now, which is what the other way of
  losing lessons already asked.
* `close_container()` forgets the container, but `setup_dishka` had put a *reference* on
  `app.state` at import and a reference does not update itself — so a second lifespan in one
  process, which `tests/test_startup.py` is, served every request from a container that had
  shut its app scope. The lifespan re-reads `container()` on the way in now.
* A closed dishka container goes on answering, and asking it for an app-scoped object
  **builds a new one** in a scope whose exit stack has already run — so nothing will ever
  close it. With an app-scoped HTTP client that is a leaked socket per ask. Invisible today
  because nothing app-scoped here owns a resource, which is exactly why it is pinned now.
* `setup_dishka` registers one middleware on every observer, each opening a scope on the
  *root* container — so a message got two **sibling** scopes, not nested ones, and anything
  resolving a session at update level would have had its own that nothing commits. A comment
  claimed the opposite and claimed it had been verified; the verification had tested
  something else. `ContextMiddleware` opens the one scope itself now, from `container()` read
  per update — which also stops the dispatcher `api/telegram.py` caches for the life of the
  process serving webhooks from a container closed at shutdown.
* Three documentation defects: `session_scope` named as the cron tick's, the September
  comment missing 2030, and three documents still describing the `setup_dishka` arrangement
  after it had been replaced.

The pass also confirmed four things that would otherwise have stayed assumptions: one session
per request across every `@inject`ed dependency including the sync `_service`; the
`MissingGreenlet` branch in `_touch_last_seen` still answering 200 after a failed commit; all
68 routes carrying real response models with `app.openapi()` generating clean; and
`/api/v1/health` still opening zero connections under the new ASGI middleware.

**One defect found by the clock.** «📒 Неделя» in the diary took its Monday from
`today - today.weekday()`, so on a Sunday it showed the six days that had just ended. It
surfaced as a test that had been wrong since it was written and went red for the first time
after midnight Moscow time on a Sunday.

That batch is two pieces. The first took two things from
[GMS Flags Reborn](https://github.com/polodarb/GMS-Flags-Reborn) (Apache 2.0, © polodarb) —
the expressive loader and the transformation between first-run steps — and then, on a second
pass over that repository, the two practices this project was actually missing: lazy-list
content types where a list holds mixed shapes (`DocsScreen` alone), and a Compose stability
configuration. The stability one was measured rather than guessed: the compiler's own report
said 36 of 94 classes in `:app` were unstable, almost always for one `LocalDate`, and
`compose-stability.conf` takes that to 29 with every screen state that holds a date now
stable. What is deliberately **not** promised is written at length in that file. Their module
layout, Koin and MVI were deliberately not taken, and their Gradle configuration and CI are
behind this project's rather than ahead — the detail is in `docs/design.md`.

The second piece is **the nine-area audit protocol run a second time**, by area, each finding
re-verified by hand before it was fixed and each closed by a test proven red without the fix:
**23 defects**, and then a ninth area — the integrity of the test suite itself — found eight
places where CI was green about things it does not check, `api/index.py` among them: nothing
in the repository read the file Vercel routes every request to, so renaming the symbol left
both gates green and every request a 500. Tests: 1391 → 1450 on the server, 605 → 668 on
Android. Three of the seven
batches reported tests that passed on the broken code when first written and rewrote them
rather than shipping them, and **a fourth found a pre-existing test asserting a defect as
correct behaviour** — `test_the_star_moves_when_another_schedule_is_made_the_default` built a
bell schedule with no rows and asserted the star moved onto it. The fixture was given rows;
the fix was not relaxed. That is the **third** time this project has found a test holding a
defect in place.

The ones that would actually have been felt, one line each:

* **nobody could sign in to the diary** — `login` read the session cookie with
  `response.cookies.get`, which raises `CookieConflict` on the two-cookie answer, and that is
  not an `httpx.HTTPError`, so it escaped as a 500 and spent the sign-in ticket every time;
  `_session_cookie` was written for exactly that failure and sat one line away;
* eight bot renderers could still send a message Telegram refuses whole — «🗓 Неделя»
  measured at 4648 characters, «👥 Доступ» at 4623, and that is the only screen a class on
  «по приглашению» can reopen its door from;
* two screens echoed back text the database had truncated, so the confirmation claimed a
  subject or a title that was not saved;
* substitutions announced to the whole class and drawn nowhere, from two independent causes —
  a replacement with no subject over an empty number, and the summer rule reaching one screen
  further than anybody had checked;
* re-pointing a class at a shorter bell schedule took every later lesson off every weekday
  with nothing anywhere naming them, and from the bot an **empty** schedule could still be
  made the class default, which blanks the phone, the widget, the calendar feed and the
  digest at once *and* disables `can_ring`;
* below API 33 the app recreated itself on every cold start for anyone who had chosen a
  language, and the recreation ate home-screen widget day-taps;
* the alert chain cancelled itself across any break longer than the planner's eight-day
  horizon, with only a cold start to revive it;
* the widget's «Дальше» column listed the lesson an assembly on screen had replaced.

**What only the owner can do.** Three decisions were deliberately left rather than taken, two
of them in section 7 — the widget's tick cadence, and whether the diary credential should
carry a bound — and the third named in the merged pull request: whether the four API
announcements that interpolate a person's text should carry `shorten`, which is a decision
about what a notification ought to say rather than a defect. Beyond those, an APK on a real
phone is the only thing that can settle the list below, and only the owner has one.

Gates on the whole tree at `d330d68`, the last commit before the merge: `ruff` clean,
`python -m mypy` clean across all 81 modules, `pytest -q -n auto` 1465 passed;
`./gradlew test assembleDebug assembleRelease` successful with 709 Android tests across 90
classes and 0 failures. CI was green on that head and on the four before it.

**What nothing has verified**, and it is now on `main` rather than proposed:

* **None of the Android work has run on a real phone.** The `304` path and the widget's
  bounded read are claims about battery that only a device can confirm; what the first gives
  up is written where the decision is — it trusts a `PendingIntent`'s existence as proof an
  alarm stands, so a vendor battery manager that drops one and keeps the other is not healed
  there. `BellRowsRotationTest` uses `StateRestorationTester`, which re-composes rather than
  killing the process, so it proves the saver round-trips and not that the bundle survives a
  real low-memory kill. «2 урока перестали звонить» has never been drawn.
* **`BoundedSnapshotParityTest` reproduces the bound rather than driving the real
  repository.** `CachedWindow` and the in-memory DAO are `internal` to `:core:data` and
  Kotlin `internal` does not cross a Gradle module, so driving them from `:widget` would mean
  adding `testFixtures` to another module's surface for one test. It is reproduced locally
  the way `WidgetSizeClassTest` reproduces the launcher's rule; `CachedWindowTest` owns the
  other half — that the repository implements that bound against the real DAO.
* **The dishka wiring has now run on Vercel** — see the warmup read above, which is one
  request and not a measurement. The 26 ms it adds to a cold start is still measured on a
  development machine rather than there, and that the webhook path still defers aiogram is
  read off the imports rather than timed there.
* **The bot has not been driven end to end since the container moved under it.** Every
  handler is covered by tests that call it directly with a session; nothing in the suite
  feeds a real update through `build_dispatcher()`, so `ContextMiddleware` opening its own
  scope is proven by unit tests and by reading, not by an update arriving from Telegram.

Before this batch, after PRs #47, #48, #49, #50 and #51 were merged. The
last of them is the agent configuration in `.claude/` and the whole written layer of the
project moved into English (see "Everything written about the project is English" in
section 6). It landed as `85064cd`, and `main` and `dev` are level. The database is at
head `0013` and `EXPECTED_REVISION` did not move, so that merge needed no migration. The
production deployment it triggered is `READY` on `85064cd`, and `/api/v1/warmup` was read
after it: `{"status":"ok","api_version":1,"schema":"0013"}`. That endpoint opens a
connection, so it answers for the database too, not only for the code.

After that batch, two small ones: #55 and #56 corrected this file's own header — it said
PR #51 was open minutes after it had been merged — and a sweep over every document
re-measured the numbers they quote. What that sweep found is at the end of section 6.

Two batches earlier: publishing the repository and everything that followed from it (the
history reviewed for secrets, `pytest` in CI spread across cores, artifacts living a
week), a deployment that refuses to start rather than quietly taking a local default, and
the Google Sans Flex licence — it is OFL 1.1 rather than MIT, as the comment and the
documentation claimed: the licence text now travels inside the APK, stands as the eighth
row under «Лицензии», and `FontLicenceTest` reads the typeface's own `name` table so that
what is claimed and what lies beside it cannot drift apart.

**PR #50 is merged**, five commits; it needed no migration — the model did not change and the
head stayed `0013`. The first two commits are the translation-correction mode across all of
the app's text (see "The correction mode knows about all the text" in section 6 and the new
section of `docs/design.md`). The other three are **an audit of the whole project by nine
subagents, split by area**: the API, the bot, `services` + `schedule` + `models`, the
providers + config + migrations, `:core:model` + `:core:data`, the design system + the
widget, `:app`, the correction mode itself, and separately the build, CI, the scripts and
the integrity of the test suite.

Thirty-one findings, every one verified by hand before it was fixed; each closed by a test
that fails on the code without it. Tests: 1362 → 1391 on the server, 578 → 605 on Android.
The most serious, one sentence each:

* five of the bot's renderers and the evening digest went past Telegram's 4096 characters —
  the message is refused whole, and `/today` (a plain `answer`) replied with nothing;
* an empty bell schedule could be made a class's default: `/bundle` handed back zero
  lessons, `/now` said «выходной» on a Monday, and the timetable lay untouched throughout;
* renaming a subject onto a name that exists only in the homework failed with a 500 and lost
  the rename entirely;
* shortening the bells silently took every lesson above the new ceiling off the timetable;
* a `401` on `/bundle` never reached `onTokenRejected` — a `Response<T>` in Retrofit does not
  throw — and a revoked device kept a class and a year of somebody else's data;
* a `304` against an empty cache answered "success" for ever: the app stayed empty;
* a duplicated session cookie dropped the whole diary into a 500 on every call;
* `POST /diary/login` was an unlimited password oracle against somebody else's service;
* a release tag could publish an APK signed with the debug key — the gate checked one secret
  of four;
* `seed_demo` wrote to wherever `DATABASE_URL` pointed, saying nothing about it.

**Three tests asserted the defect as correct behaviour** and were replaced. The full list of
findings and what was done with each is in the bodies of commits `5ec62a6`, `afba3c5`,
`b8d8deb` and `3997b29`.

What is still open:

1. **The external cron is not set up.** Until it is, the digests go out whenever GitHub
   deigns to run the schedule — measured at 6.7 times a day instead of 288. The bot's promise
   that they arrive "within about five minutes" is untrue until then. What exactly to set up
   is in `docs/deploy.md`, "The clock".
2. **The production Neon endpoint's host and the project id stayed in the history.** They are
   out of the working tree, but the repository is public and that does not change the
   history. The owner changed the role's password and updated `DATABASE_URL` in Vercel, so
   the address in the history is no longer enough on its own. **The variable is set for
   Production only:** Preview fails with `ModuleNotFoundError: No module named 'aiosqlite'` —
   which is the refusal at the door, just from before it learned to name itself. Either set
   it for Preview too, or do not look at preview deployments.

If you are an agent: `CLAUDE.md` first (the project's rules), then this file (what is already
done and what is left), and the agent configuration is in `.claude/` (see section 6, "The
agent configuration lives in `.claude/`"). Treat everything below as verified fact as of the
date above, but **re-check the branches and CI before your first action** — they live their
own lives, and this file goes stale the moment it stops being updated.

---

## 1. In one paragraph

**There are exactly two branches, `main` and `dev`,** and after PR #43 they met. Everything
that had piled up in `dev` is now in `main`: the former working branch, five dependabot
branches — cryptography, asyncpg, AGP, the androidx group and the Gradle wrapper — and eight
substantive commits on top. The six original branches are deleted and their pull requests
(#35, #36, #38, #39, #41, #42) are closed with the status **Closed rather than Merged**:
GitHub counts a pull request as merged only when its commits reached its own base, and the
base for all six was `main`. The code was not lost: it arrived through #43.

On top of that, ten substantive commits landed in `dev`:

* `8ec2e31` — a phone holds several classes at once and switches between them;
* `569bab8` — six defects that four audits found in `8ec2e31`. The first is serious: a `401`
  on one class threw the device out of **all** of them;
* `ce02348` — corrections laid over the diary, with an undo, and revision `0009`;
* `c6ba66c` — fifteen findings from two audits of `ce02348`;
* `59c7a77` — the class's join mode, the personal connect codes, revisions `0010` and `0011`,
  and seven findings from one more audit;
* `98c8018` — the database brought up to `0011` through the Neon connector, **before** the
  merge;
* `70b9dcb` — a `429` on the code screen says how long to wait rather than "HTTP 429";
* `ab488ad` — twenty-one findings from three audits: Android, the bot and the documentation;
* `8aa4ecd` — three of the app's screens are pressed in the build (Robolectric in `:app`);
* `bf74233` — «🗓 Четверти» crashed in production on every press; fixed;
* `a58d2be` — the "does the code reach for an attribute that does not exist" check across the
  whole server; `rows_affected`; thirteen tests on the flows in `content.py`;
* `33deca3` — the rest of the bot's cards are drawn in tests, and eight of them turned out to
  be broken;
* `a9be1c4` — every drawn row has a button, the alert is cut at Telegram's ceiling, a dead
  renderer is deleted;
* `6a1dded` — a forged press gets a refusal rather than an endless spinner;
* `f3839c8` — a date that is not in the calendar is rejected rather than crashing the handler;
* `c2038e4` — an event's kind and a lesson's number are checked where they are picked;
* `1d84d89` — a far-away date answers 422 rather than 500, and `PATCH /tasks` does not put
  NULL into a NOT NULL;
* `ae8e3b8` — the countdown and the quarter speak the phone's language; the translation guard
  is extended from one module to all four;
* `8cba40b` — seven findings in the adapters and the plumbing: the diary no longer loses a
  lesson's time or an only child;
* `94ea9b5` — a bell schedule is read by its numbers rather than by its row count; four
  findings in the shared rules;
* `4f5471d` — the template stopped expanding all summer; a substitution and a pasted day
  check the bell; one assignment per subject per day; revisions `0012` and `0013`;
* `3a592c9` — the diary sign-in page stopped blaming the password; `httpx` pinned; the
  turnstile, a token made of spaces, a rolled-back transaction in `current_revision`;
* `69bf4ba` — one link, one sign-in; `/bundle` does not fail after losing the race to seed
  the terms; the subjects screen does not throw away its own link.

The fourth pass (four subagents over four non-overlapping zones, every finding re-checked
here by reverting the fix rather than taken on trust):

* `ea3afc8` — "N skipped, no bell" counts rows rather than numbers;
* `24b4841` — the diary notices a changed response shape instead of an empty week;
* `7712dc7` — `scripts.init_db` stamps `alembic_version`; `requirements.txt` is kept level
  with `pyproject.toml` by tests rather than by attentiveness;
* `8bec2ff` — the selected class survived the sweeping of abandoned dialogues;
* `0c99993` — revoked access takes the class's subscriptions with it;
* `8995fa6` — the role in the menu, the time of the first digest, the length of the homework
  digest;
* `38e8aec` — a day cannot be hung on a bell schedule with no rows;
* `bf2338d` — signing into the diary with no `DIARY_SECRET` answers 503 rather than 500;
* `591b3a4` — «Выйти» from the diary leaves no second live session behind;
* `7c99652` — every notification has its own press target; the widget does not hide the room
  for the sake of a teacher who is not there;
* `cc3935e` — deleting a lesson does not shift its neighbour onto a number with no bell; the
  "collect from the timetable" counter stopped counting other classes' rows as its own.

The fifth pass — what the fourth named but did not do:

* `a47d58c` — on a failed read the widget does not push you to "enter a class code"; the one
  unverifiable minute setting is brought into a range;
* `1676915` — the webhook checks the secret before parsing the body, and the sign-in form's
  body ceiling is measured on the way rather than after everything is already in memory;
* `a0fe48b` — one calendar link per class (a conditional `UPDATE` and a read back), and the
  digest assembled inside a savepoint, so that one failing class does not take the whole tick
  down on Postgres;
* `060f385` — cancelling a lesson that is not in that day is rejected: it used to go into the
  log and out to every subscriber, and be drawn nowhere.

The sixth pass — both of the owner's decisions taken and done:

* `f0ba014` — a date outside the school year reads as `DayOff(HOLIDAY)` rather than "no
  data": in summer the widget says «Каникулы» and shows the homework for 1 September instead
  of asking to be pulled down for something that was never coming. A date **inside** the year
  with an empty cache stayed `NoData` — there, pulling down is exactly what helps;
* `5b10815` — the client sends `If-None-Match`. The tag sits in DataStore under the request's
  signature (class, `start`, `days`), so 1 September and a change of class simply stop
  matching and the next sync asks for the whole window. A `304` does not write to Room and
  does **not** wake the widget, but it does move "updated N ago": that line is about the
  check rather than about the data.

**Section 3.1 was closed entirely when this was written.** One requested item has been
added since and is open: scrolling the calendar by years, which is a decision rather than
work — see section 7.

The block below is **the state on the day this section was written**, kept because the
migration order it records is the thing worth reading twice. It is not today's: the
branches, the open pull request and the two test counts have all moved since, and the
paragraph at the very top of this file is the one that is kept current.

```
As of PR #51 — not current; see the top of this file
Merged:  PR #43, PR #44, PR #45 (six passes, 38 commits, merge 26ad184),
         then PR #47, #48, #49 and #50 — main at 22399e9
Open:    PR #51 (dev → main) — the agent configuration, and this translation
Branches: main and dev; dev runs from 22399e9, that is, from the merge of #50
Gates:   ruff clean, mypy clean, 1391 server tests, 605 Android, both assembles
Database: production at 0013, which is the head. 0010–0012 were applied through
         the Neon connector BEFORE the merge, 0013 (a UNIQUE) AFTER, as its
         shape requires. No separate database action is outstanding
```

**A merge to `main` is a deploy.** Vercel builds `main` by itself. The order was mixed this
time, and deliberately so: `0010`–`0012` went into the database in advance, so the window in
which the code knows a column the database does not have never opened for a second, while
`0013` — a constraint that the **old** code breaks against — went on immediately after the
merge. Between them `services/homework.py` behaved exactly as production did before it. One
request checks that it all lines up:

```bash
curl -s https://<project>.vercel.app/api/v1/warmup   # {"status":"ok","schema":"0013"}
```

A `"degraded"` here would mean the deploy had not arrived; before `0013` was applied, the
same request honestly called the database behind, because `EXPECTED_REVISION` was already
`0013`. The head is `0014` today — see the top of this file — and the same request reads it
the other way round while a revision waits for its merge: the database is *ahead* of the
code, and `/warmup` says so in as many words, «База впереди кода…».

**The five dependabot pull requests can now be closed without regret** — their bumps arrived
in `main` together with `dev`. If it managed to recreate them before the merge, they will
turn empty by themselves.

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
cd server  && pytest -q -n auto                          # 1668 tests, ~4 min (CI runs this)
cd server  && python -m mypy                             # clean, 97 modules
cd android && ./gradlew test                             # 968 tests across the five modules
cd android && ./gradlew assembleDebug assembleRelease    # both assembles
```

---

## 2. What has been done

Bottom up. The details of each are in its commit body and in the description of PR #43; this
is only so that you do not have to go looking.

| Commit | What it did |
| --- | --- |
| `b7c82d1` | The bottom fade starts where Essentials' does (130 dp) |
| `aacf886` | Three defects found by comparing against Essentials: `CrashReporter`, `AppLocale`, `derivedStateOf` |
| `478861e` | The timetable's horizon is a school year, not a month from Monday |
| `cf01b96` | A class by its number and letter; quarters and half-years; revision `0008` |
| `d2d8d23` | `CLAUDE.md`: migrations are applied through the Neon connector |
| `9b7b907` | The schools registry behind `providers/dadata/`, searchable in the bot and the app |
| `af20180` | A `401` on a class token drops the session and the cache |
| `fc9989f` | The subject dictionary and the timetable are one list |
| `92518c5` | Twelve defects from two audits of fresh code |
| `bd4ce41` | Week parity, the calendar's UIDs, the two alarm chains |
| `ef6e530` | One tick per digest; a shortened day with no bells is refused |
| `c35ebd3` | CI: `setup-android` pinned to `v4.0.1` + `packages: platform-tools` |
| `236b259` | Quoting in the timetable export; a lesson with no bell is not written |
| `e5cc939` | `pyproject.toml` level with `requirements.txt` after the pip bumps |
| `bfa1689` | What compose-bom 2026.09.00 did to the intercepting layer |
| `e55ea2f` | This file, on `dev` rather than on one working branch |
| `a1bbeb5` | This file, brought to the facts: six branches closed, one live pull request |
| `8ec2e31` | Several classes on one phone, and switching between them |
| `569bab8` | Six defects from the audit of `8ec2e31`; a `401` drops one class, not all |
| `ce02348` | Corrections over the diary, with an undo; revision `0009` |
| `c6ba66c` | Fifteen findings from two audits of `ce02348` |
| `59c7a77` | The class's join mode and the personal codes; revisions `0010` and `0011` |
| `98c8018` | The database brought up to `0011` |
| `70b9dcb` | The `429` on the code screen, in minutes rather than "HTTP 429 Too Many Requests" |
| `8aa4ecd` | Three of the app's screens are pressed in the build: Robolectric in `:app` |
| `bf74233` | «🗓 Четверти» crashed in production on every press; the rendering is covered |
| `a58d2be` | The "attribute that does not exist" check across the whole server; `rows_affected` |
| `33deca3` | The bot's cards drawn in tests; eight turned out to be broken |
| `a9be1c4` | Every row of a list is reachable by a button; the alert is cut at 200 |
| `6a1dded` | A forged press gets a refusal; the order of memberships is fixed |
| `f3839c8` | A day offset out of callback data does not overflow the date |
| `c2038e4` | An event's kind and a lesson's number are checked where they are picked |
| `1d84d89` | 422 instead of 500 on a far-away date; NULL into a NOT NULL refused |
| `ae8e3b8` | The countdown and the quarter localised; the translation guard across all modules |
| `8cba40b` | Seven findings in the providers, the config, the FSM and the database URL parsing |
| `94ea9b5` | Bells by number; a slot does not double; a quarter on the diary's clock |
| `4f5471d` | A summer with no lessons; the bell under a substitution and a paste; revisions `0012`/`0013` |
| `3a592c9` | The diary sign-in does not blame the password; `httpx` with an upper bound |
| `69bf4ba` | Two races closed with a conditional UPDATE and a savepoint |
| `ea3afc8` | The import counts dropped rows rather than their numbers |
| `24b4841` | The diary notices a changed response shape instead of answering with an empty week |
| `7712dc7` | The revision stamp after `init_db`; the `requirements.txt` mirror under test |
| `8bec2ff` | The selected class is not swept up along with the dialogues |
| `0c99993` | Revoked access takes the class's subscriptions with it |
| `8995fa6` | The role in the menu, the time of the first digest, the length of the homework digest |
| `38e8aec` | A day is not hung on a bell schedule with no rows |
| `bf2338d` | Signing into the diary with no key is a 503, not a 500 |
| `591b3a4` | «Выйти» from the diary leaves no second session |
| `7c99652` | Each notification has its own press target; the room in the widget |
| `cc3935e` | Deleting a lesson does not shift its neighbour onto a number with no bell |

**There were thirteen audits on this branch.** Four of the early work (sixteen findings),
four of `8ec2e31` (six), two of `ce02348` (fifteen) and one of `59c7a77` (seven); three
covering Android, the bot and the documentation (`ab488ad`), and the last two, which drew the
bot's cards and found nine defects (`bf74233`, `33deca3`). The heaviest of the lot: week
parity was computed from the ISO week number, which does not alternate in a 53-week year — so
from January 2027 the whole denominator of the **current** school year slid by a week,
simultaneously in the API, the widget, the digests and the calendar.

**Migrations:** production is at `0014`, and that is the last revision written. `0007`–`0012`
were applied **before** the merge through the Neon connector, `0013` after it (it adds a
`UNIQUE`, and a constraint migrates in the opposite direction from a column), and `0014`
before it again. The details and what each one did are in section 7, item 1. **There is
nothing left to do to the database before a merge.**

---

## 3. What has NOT been done

### 3.1. The features that were requested — all of them are done

**No requested work is left untaken.** The one item that was open here — scrolling the
calendar by years — was a decision rather than work, the owner made it on 21 September
2026 (the windowed sync, not the picker bound to the synced year), and it is done. The
section is kept struck through rather than deleted: it shows what exactly was asked for
and what it turned out to be.

~~1. **A pupil choosing their own class.**~~ Done in `8ec2e31`. The phone keeps a list of
   memberships, shows one of them and switches instantly; the cache is split by class, so
   switching works with no network too. What exactly is unverified is in section 5.
~~2. **Corrections over dnevnik2's data, with a reset.**~~ Done in `ce02348`. Pressing a
   lesson or an assignment opens a window where the fields can be rewritten; under each it
   says what the diary actually holds. Nothing goes upstream, marks and the turnstile cannot
   be corrected, and the bot does not show corrections (section 6).
~~3. **Two modes on invitation, and a reversible switch between them.**~~ The statement was
   clarified with the owner: this is the **class's join mode**. Done — `join_mode` on a
   class, `open` or `invite`; in `invite` the class code lets nobody in and a phone joins
   with a personal one-time code from the bot. The switch is reversible and disconnects not
   one already-connected phone. The details are in section 6, "Who lets a phone in".

### 3.2. What does not exist at all (long-standing gaps, not regressions)

The same as the "What does not exist at all" section of `README.md`:

- Attachments to homework: the `attachment_url` field is in the schema, there is no upload.
- A link between a class and a registry record: only the school's name is stored, so a rename
  in the company register passes the class unnoticed.
- The widget stays on the system font: Glance passes `fontFamily` as the name of a system
  family rather than as a resource.

### 3.3. The typeface's licence — closed, and not the way this said

**`google_sans_flex.ttf` is under SIL Open Font License 1.1**, Copyright 2015 Google LLC. The
typeface declares that itself, in its `name` table, records 13 and 14. This section and the
comment in `theme/Type.kt` said "MIT, from Essentials" and concluded that the typeface was
non-free and had to be removed before publication; neither held up once the file was finally
opened and read.

`FontLicenceTest` in `:core:designsystem` holds this: it parses the `name` table of every
font in the tree and requires a notice under `assets/licenses/` with the same copyright and
the same licence, and that it be the text rather than a link. The link between the file and
the notice used to rest on nothing — move one and the build would stay green.

There is nothing to remove. What was done is what the licence actually requires: the OFL text
is placed next to the font in `assets/licenses/` — so it travels inside the APK, as "a copy
of the licence accompanies a copy of the font" — the licence is named in the app on the
«Лицензии» sheet, and neither the comment nor `docs/design.md` asserts something untrue any
more. No Reserved Font Name is declared, the file is not modified, and the "Google Sans"
trademark stays a trademark: bundling is allowed, naming a product after it is not.

---

## 4. There are no open defects

All sixteen findings of the first four audits are closed, as are all six findings of the four
audits of `8ec2e31` (`569bab8`), the fifteen of `ce02348` (`c6ba66c`), the seven of `59c7a77`
and every finding of the last three audits — Android, the bot and the documentation. And five
of the six defects `docs/design.md` carried under "known and not yet fixed". The sixth — the
screenshot on the main thread — turned out not to be a defect but a platform limitation
(`View.draw` is obliged to run on the UI thread) and was rewritten into "Limitations".

**Do not file these again as bugs.** If a new session's audit names something from the list
below again, check the code first for whether it is already closed:

- week parity from the ISO number → now counted from the start of the school year;
- positional UIDs in the calendar → now a row's identifier;
- `setWindow` in the widget's alarms → now `setAndAllowWhileIdle`;
- `SchoolAlerts.fire` with unprotected publishing → every publication in a `runCatching`;
- `mark_sent` after the selection → now `claim`, a conditional `UPDATE`;
- `MAX_INDEX = 20` as a day's ceiling → now a ceiling from the class's bell count;
- the timetable export without escaping commas → quoting, plus the last field taking the
  remainder;
- `LARGE` hid the homework → the `LARGE_TALL` threshold lowered from 400 to 300 dp;
- `OverlayLayerTest` "failing" on the intercepting layer → **not a defect**: the test was
  rewritten for compose-bom 2026.09.00, which changed Compose's behaviour. It was red exactly
  once, at the merge; see section 6.

The six multi-class findings closed in `569bab8` — do not file those again either:

- `onTokenRejected` called `signOut` → now `leaveActive`, and a `401` on one class drops only
  that one;
- the membership list was decoded as a whole → now per entry, so one broken row does not take
  the rest with it;
- `syncNow` with `KEEP` swallowed the sync after a switch → the switch now uses
  `APPEND_OR_REPLACE`;
- leaving a class that was **not** on screen cleared the timetable fingerprint → now only
  when the active class actually changed;
- a notification from the previous class stayed in the shade → switching removes it;
- an in-flight sync resurrected rows of a class that had been left → `retainOnly` sweeps them
  at the start of every sync.

The findings of the last three audits are closed too, and these are the ones easiest to find
a second time:

- an enum's `server_default` written through `.value` → now `.name`; the column holds `OPEN`,
  and three tests check it (section 6);
- `burn` assigned an attribute → now a conditional `UPDATE`, and `/join` burns the code
  before it issues a token;
- the mode button was a toggle → it now carries the mode it wants;
- joining with a personal code was not written to the log → `device.link` is now written in
  `/join`;
- revoking a member did not kill their unissued codes → now `drop_for`;
- `session.refresh` after a swallowed rollback stood outside the `try` and could replace a
  `401` with `X-Diary-Reauth` → now `_refresh_quietly`, and the same in `api/deps.py`;
- `mint` deleted spent codes along with live ones → now live ones only;
- `_is_iso_date` accepted `20260915` and `2026-W38-1` → now `YYYY-MM-DD` only;
- the `429` on the code screen was drawn as "HTTP 429 Too Many Requests" → now a Russian
  sentence with the number of minutes from `Retry-After`;
- `join_error_generic` became unreachable and an English sentence went out in its place →
  `JoinError.of` reads the classified refusal rather than the raw one;
- a row with no `target` was pressable and led to a `422` → now `correctable`;
- the correction window could stay over the sign-in form → `applyFailure` closes it together
  with any request for a password;
- four comments asserted things the code does not do → rewritten to the facts.

And nine came not from an audit but from the bot's cards finally being **drawn in a test**.
The first came from production: the owner pressed «🗓 Четверти» and got an error dialog.

- `_terms_card` printed `term.days`, and `Term` has no such attribute: `days` belonged to
  `TermView` — "a flattened copy of a term for a renderer with no session" — which **no code
  path ever created**. The line was written against a type that never reaches it, and the
  only way to find out was to press the button. `days` moved onto the model, the dead twin
  was deleted, and the rendering is covered by tests.

The other eight are from `33deca3`, and two of them took the whole screen:

- **the diary escaped nothing** that the external service sent: the subject, the room, the
  teacher, the topic, the assignment's text. Telegram refuses **the whole message** on one
  angle bracket, so «реши § 4 при a<b» left a parent with no timetable at all;
- **the editor did not escape a subject's name**, while the paste grammar accepts «Алгебра
  <7>» whole — one bracket, and the editor does not draw the day it is editing;
- a slot's card showed its own tags to a viewer: `answerCallbackQuery` has no parse mode →
  `editor_render.as_alert`;
- `plural` prints the number itself, and three places printed it again («перемена · 10 10
  минут»). One of the three **had a test**, and it passed: «10 минут» is a substring of
  «10 10 минут»;
- the import preview put the line about the bells under its own footer, so a paste of nothing
  but bells read as "not one day recognised" above a button that was about to replace those
  bells;
- the import's result counted what was parsed rather than what was written, and contradicted
  itself on adjacent lines;
- `render_bells` silently lost the tail of the list past `LIST_MAX`;
- `_next_day_line` indexed an empty list;
- three list pages out of four drew more rows than there were buttons beneath them: forty
  subjects over thirty ✏️, twenty bell schedules over ten, twenty phones over fifteen. The
  tail was visible, unreachable and unexplained; «Особые дни» agreed with itself by accident —
  both sides said 20. Each page now has one number for both
  (`manage_render.SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX`, `LIST_MAX`), and
  `test_no_list_page_draws_a_row_the_keyboard_cannot_reach` checks it: it asks the keyboard,
  by row id, which of the drawn rows it carries;
- the alert from a lesson's card measured 284 characters on a doubled lesson —
  `answerCallbackQuery` returns 400 past 200, that is, an endless spinner;
- `diary_render.student_line` was called by nobody: a dead renderer of the same shape as the
  one that crashed «🗓 Четверти». Deleted.

**The second pass over defects** (four agents over non-overlapping areas plus a slice of my
own; every finding has a test that fails with a real error if the fix is removed):

- **two role-picking screens caught one press.** The invite-by-number handler stood on
  `RolePick.filter()` — on *any* role press while its state was live. An admin who had
  started "invite by number" and then pressed a role on an older «Новая роль» card created a
  **phone invitation** and got a success message about a number rather than about a person;
- three bare conversions from callback data in `access.py` (`int(target)`, `int(value)`,
  `Role(role)`) — the one file of the bot the check that closed this in `manage.py` never
  reached. A bare `int()` does not refuse a press, it **escapes the handler**:
  `callback.answer()` is never called and the button spins until Telegram gives up;
- `timedelta(days=…)` from callback data in three paging screens (day, week, diary) — an
  `OverflowError` rather than a far-away day;
- an event's kind and a lesson's number in `content.py` were stored raw and turned into an
  `EventKind` / an `int` three questions later — exactly what the comment a line above in the
  same file warns about;
- the order of memberships was undefined while three places read it as meaningful, including
  **the order of the «🔀 Сменить класс» buttons**;
- `GET /homework?from=9999-12-31` answered 500 (the window arithmetic ran before the bounds
  check); the same in the diary's `_range`; `PATCH /tasks` with `{"title": null}` put NULL
  into a NOT NULL column;
- the diary lost **a lesson's time** when the upstream answers in ISO, and **a whole child**
  when the first `educations` entry is unreadable; a failed sign-in was reported to the phone
  as "the session expired", so the person retyped their password endlessly;
- «МБОУ "СОШ № 197"» was drawn as «МБОУ "Сош № 197"»; a typo in `TIMEZONE` crashed `/start`;
  `state.clear()` wrote an empty string to the database (113 such places in the bot);
  `describe()` leaked the tail of a password containing an `@`;
- on the phone: the countdown — the largest digits on the home screen — was Russian under an
  English caption, the calendar's heading read «October 2026 · 1 четверть», and a `401` in
  the diary **crashed the app** if the write to disk failed.
- **the lesson ceiling was counted from the number of bells rather than from their numbers.**
  The paste grammar allows a gap, and a class with bells at 1, 2, 4 got both errors at once:
  «4. Химия» was rejected with the words "there is no such bell" — about a bell from the same
  paste — while the editor would have written a third lesson the class does not ring;
- **a timetable slot could get a second row shadowing the first.** `uq_timetable_cell` does
  not forbid "every week" and "numerator" on one lesson, both pass the parity filter, and
  `_load` selects with no `ORDER BY` — the database decided what the phone would draw, and
  the answer could differ between two reads;
- **a task's reminder was claimed by an attribute rather than by the database.** The digests
  obey the rule in the module's docstring, the tasks did not, and two overlapping ticks sent
  «⏰ Напоминание» twice;
- the diary's "current quarter" was read on the server's clock rather than the diary's: on
  the evening of a quarter's first day the subjects screen arrived empty.

**Not a finding but an honest caveat.** The order of memberships has no test that fails
before the fix: the tests run on SQLite, which returns insertion order both with and without
an `ORDER BY`. The test left there is a guard, not a reproduction.

**The third pass — what the owner named, item by item** (`4f5471d`, `3a592c9`):

- **the template expanded in June, July and August.** `SCHOOL_YEAR_END_MONTH` is 5 and the
  comment beside it says the template must not repeat from June — and `_resolve_day` never
  read that constant. A summer weekday arrived full on the phone, in the widget, in the
  calendar feed and in the morning digest, whose **own** rule about staying silent on an
  empty day could never fire: the day was not empty;
- **a substitution could be written onto a number with no bell** — it went into the log and
  into a «🔁 Замена … урок №8» notification, and was drawn by nobody. The check is now in both
  shells and **against that day's bells** (`rung_indexes_on`), because a shortened day rings
  shorter than an ordinary one;
- **the bot's single-day paste went round `apply_timetable`** — the one entrance into the
  template without all of its checking. Eight lessons into a class that rings seven gave
  "lessons saved — 8" and a list of eight;
- **two people saving one assignment at the same time got two.** Both shells promised "one
  assignment per subject per day" in their own docstrings; it is now one
  `services/homework.py`;
- **the diary sign-in page blamed the password for any of somebody else's errors** — a person
  retyped a correct password until they gave up;
- `httpx` had no upper bound while depending on a deprecated capability; the turnstile read
  an unrecognised direction as "exit"; a token made of spaces looked like an exhausted quota;
  `current_revision` left the Postgres transaction aborted.

- **two read-then-write races.** `diary_link.claim` read the ticket, checked it in Python and
  then assigned `used_at`: two simultaneous sign-ins got two diary sessions for one account.
  And `GET /api/v1/bundle` seeds the terms for a new class — while every phone in the class
  polls it on one timer, so "both found the year unseeded" is the ordinary case, and on
  Postgres the second got an `IntegrityError`, that is, **a 500 on a read**;
- `GET /api/v1/manage/subjects` linked lessons to the dictionary and **committed only if it
  had created something**: for a class with a full dictionary and unlinked lessons the
  UPDATEs ran and were thrown away on every read. It was only cured by `/bundle` committing
  unconditionally.

**Where I disagreed with an agent.** It proposed handing the sign-in ticket back on an
"incomprehensible answer" from the diary too. We cannot tell a captcha from a wrong password:
a Yii form answers a wrong password with the same "200 with some HTML". Handing the ticket
back on such an answer would make the link an unlimited oracle for guessing a password from
our address — precisely what spending the ticket in advance is for. The hand-back is narrowed
to "the diary did not answer at all"; the message stopped blaming the password in both cases,
and that was the defect.

**The three the first real build found** (#79) are closed too, and are worth knowing about
because none of them is the kind an audit finds by reading: a `LazyColumn` key that was
never interpolated, a fixed-height fade inside a view whose height is whatever is left, and
`cameraDistance` multiplied by the density. All three are measurements. All three passed
every test that asked whether the screen was *built*, and all three fail the one that asks
whether its rows are **displayed**. If an agent proposes any of them again, check
`DayRibbonView.kt` first.

**And the six from the build after it** (#80), which are the same family one step wider —
every one is a layout or a default that nothing in the code reads as wrong:

- a marquee left on `MarqueeDefaults.Iterations`, so a title stops scrolling after three
  passes and sits clipped — per composition, so two rows of one list disagree;
- a screen title sharing a `Row` with four controls under `weight(1f)`, which is the
  correct way to share a row and the wrong amount of room;
- a button that appears and disappears without its slot being kept, so everything beside it
  moves 48 dp between two presses;
- a container radius picked as a token rather than derived from what is inside it;
- an item that pads itself inside a list that already pads every item;
- `retention-days:` asking for more than the repository allows, which is silently reduced.

The last one is not an Android defect and is listed with them on purpose: it is the same
shape. **A number written next to the thing that overrides it is a wish, not a setting**,
and three documents had been quoting the wish for months.

**And one from #81, which is the family's sharpest member**: a test that asserted how its
own build was configured. `AboutCardTest` read `BuildProvenance.current()` and asserted the
badge that a build told nothing about itself draws — so it passed under `ci.yml`, which
sets no build properties, and failed under `apk.yml`, which sets them all. Green on every
pull request; red only in the workflow whose job is to produce an APK. **A test that reads
a global is a test of the environment**, and the environment a test runs in is not the one
the code ships in.

---

## 5. What nobody has verified

This is the main thing worth knowing: **all of this work is proved by tests and by nothing
else.**

**Most of this section is now also an issue**, which is where it should be worked from:
#113 the widget's sizes, #114 the arranging gesture, #115 the correction mode, #116 two
classes on one phone, #117 the upgrade path, #111 what the ribbon's shader costs, #121 the
real diary. They are children of **#109**, the epic for moving this work to a machine with a
device on it. The prose here is kept because it says *why* each one is unverifiable, which
an issue title cannot.

- **Not one route in `docs/diaries/` has been seen answering.** #134's 1916 routes
  were read from client code and two official apps, and cross-checked against each other.
  No diary host answers a cloud session, so none was ever called. A route marked *current*
  means that a client active in 2025 or 2026 sends it, not that a server accepts it today.
  ТОР «Моя школа» rests on one app build, 5.0.0.454, and its first-wave list rests on one
  official post. The first live session on any platform will correct its page. Whoever has
  it should edit the page by hand, because the generator is not in the repository.
- **The «Сетевой город» provider has never met a live server.** #140 wrote the client, the
  mapper, the keep-alive and the school search from open-source clients and hand-written test
  payloads; no ИРТех server answers a cloud session, so none was called. Every upstream shape
  — the salted-MD5 sign-in, `/webapi/context`, the weekly diary, the school search — is what a
  client sends, not what a server was seen to accept. The refresh-token grant (LoginType 9)
  and the Госуслуги refusal path (`SignInUnsupported`) are the least exercised of all. The
  first live sign-in on it is the owner's, #121's caveat a second time; #135 is the decision
  that precedes it.
- **Nothing of the tab arranging has been seen on a phone, and it is a gesture.** #84 is a
  long press, a wobble and a drag; #85 is the drag actually reporting where it landed. What
  the 39 tests prove is arithmetic and contracts: where a drag of so many pixels lands, that
  the gesture reports the slot it computed, that a move is a permutation, that the stored
  order repairs itself, that the shell's one back rule leaves the mode before it leaves the
  app. What nobody has felt: whether the wobble is plausible, whether half a slot is the
  right threshold under a real thumb, whether the two-step gesture — press, then pick up —
  reads as deliberate rather than as a gesture that failed the first time, and whether the
  haptic lands where the mode opens. **The APK built for #84 predates the fix and does not
  rearrange anything**; the first install worth an evening is one built after #85 merges.
- **The drag is driven from a test now, and how far it goes is the whole reason it can be.**
  #84 left it undriven on the ground that Robolectric reports its own densities, so a
  synthetic swipe of «half a slot» measures the environment. True, and it hid a defect for a
  batch: a drag of ten thousand pixels parks at the end under any density, which is what
  `ToolbarDragTest` does and what caught it. Anything *between* two slots is still not asked
  of a composition, and `ToolbarReorderTest` asks it in pixels instead.
- **There is a window of a frame or two after the drag, and it is inherent rather than a
  defect:** between the drag ending and the preferences answering, the pager's index still
  names the old order, and a `LaunchedEffect` on the arrived order is what closes it. The
  other one — a frame of the pre-drag order at the moment the mode closes — was written down
  here as deliberate and is closed by #85.
- **Nothing of the year scrolling has been looked at either.** What the tests prove is
  which year is asked for and when, which one is dropped, and that a request made while
  another was in flight comes back. What nobody has seen: the year chip, the picker, how
  long a school year takes to arrive over a school's wifi, and what three years of day
  rows cost a `LazyColumn` that re-reads them on every write. The cap of three is a guess.
- **Nothing of the calendar or the day screen has been looked at.** What the tests prove is
  which accent a day resolves to, where a band of one reason ends, which row
  «вернуться к текущему» lands on, and which of three depth levels a given API level gets.
  What nobody has seen: the four accents beside one another, whether the summer's tint is
  faint enough across sixty cells, whether the perspective tilt reads as depth or as a
  wobble, and whether the AGSL band reads as light rather than as a smear. **What it costs
  is unmeasured too** — one shader layer and a per-frame clock on a mid-range phone is a
  frame budget nobody has looked at.
  **One qualification, earned in #79:** the ribbon *has* now been run on a real phone, and
  it crashed on opening. What that proves is that the three defects #79 fixes were the ones
  reported, and the bugreport says so in the platform's own words. It proves nothing about
  how any of it looks, because nobody got far enough to see it.
- **No rule stops another composable reading `BuildConfig` directly**, which is how #81
  happened. Three tests hold `AboutCard`; nothing holds the next one. Such a defect is
  invisible on a pull request by construction — `ci.yml` does not set the build properties
  and should not, because it does not produce an APK anybody installs.
- **The server badge has never been drawn against a real server.** `ServerStatus` has four
  states and a test for each, all of them from a fake; nothing in the suite opens a socket.
  What that leaves unchecked is the shape of a real answer — whether `/api/v1/warmup` from
  a phone on a school's wifi resolves into `Ok`, `Degraded` or `Unreachable` as intended,
  and how long it takes to say so. It is also the first thing in this app that makes a
  network request from the settings page, so it is the first that can make that page wait.
- **Nothing of #80 has been looked at either.** The tests compose the about card, the
  calendar header and both link buttons and assert their text is *displayed*. What nobody
  has seen: whether the concentric corner reads as concentric, whether a marquee that never
  stops is pleasant rather than merely readable, and whether stepping a month with the
  arrows surprises somebody who has just switched «День» into its list mode.
- **The widget's new corners have never been on a launcher**, which is the only place they
  exist. #82 derives every inner radius from the size class's own padding, and the five
  tests reproduce that arithmetic and nothing else — no test draws a widget. What nobody has
  seen: whether the gap now reads as even at 8 dp of padding *and* at 16, whether the 6 dp
  floor is tight enough to still look like a corner on the widest rungs, and how any of it
  sits against a launcher's own widget rounding. Below API 31 the question does not arise —
  `cornerRadius` is a no-op there and the launcher supplies square edges — so the check
  needs a phone on 31 or later and a widget resized twice.
- **Nothing of #83's widget work has been seen on a launcher either, and that is the surface
  this batch changed most.** The font-scale division, the two weights that stop the trailing
  detail starving the subject, and the outer box that gives the seven day chips their gaps
  are all proved by tests that reproduce the arithmetic and the budgets — no test draws a
  widget. Worse for confidence: **two of the claims are about what Glance does with a
  modifier chain, and they come from reading its translator rather than from pixels** — that
  `applyModifiers` folds every padding into one `setViewPadding` on the background's own
  view, and that a container keeps ten children. What nobody has seen: whether the largest
  system font now fits the rung it lands on, whether the chips read as seven days, and
  whether the subject keeps its width beside a long teacher's name. Below API 31 the corner
  half of this does not arise at all — `cornerRadius` is a no-op there.
- **`/api/v1/warmup` has still not been read, and it is now known to be unreachable from a
  session rather than merely not done.** The deployments are behind Vercel's Deployment
  Protection and answer a redirect to a login page, so no `curl` from here can settle
  whether `0014` and the code that needs it met. It needs the owner's browser or a bypass
  token; it has been outstanding since #61 and it is in section 7 for that reason.
- **`docker compose up` has never been run.** There is no Docker in this environment, so
  what #83 proves about the new `migrate` service is that the compose file parses and that
  its dependency conditions are what they claim — `service_completed_successfully` before
  the API starts. Nobody has watched the stack come up, nobody has seen
  `alembic upgrade head` run inside the image, and nobody has confirmed that the revisions
  the Dockerfile now copies are the ones it needs.
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
  after the deploy, and `/api/v1/warmup` answering `"schema":"0014"` is the cheapest sign
  that the migration and the code actually met.
- **There is still no `androidTest` in the project**, and no emulator is available here: the
  container has no `/dev/kvm` and no virtualisation flags, so the system could only be
  started by full software emulation, that is, not at all.
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
  (section 4). The hole that is left is exactly where it was: the tests assert what is
  written on a card rather than how it looks in a client, and nobody has opened a live
  Telegram with these changes.
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
  Still seen by nobody: the switch with your eyes, the widget's redraw after it, and the
  re-planning of the alarms — those are inferred from code that is called rather than
  observed.
- **Nobody has switched the join mode on a live class** — but the switch is now pressed in
  tests: the confirmation appears only in the direction that takes something away, «Отмена»
  writes nothing, and restoring the class code writes straight away. It is covered lower down
  the stack too: the class code's refusal in `invite`, a personal code for one phone, a race
  between two requests for one code, a stale button, the sweeping, a `PATCH` in both
  directions, the log line, and that the column holds `OPEN` rather than `open`. **Not one
  test presses the button.** Nobody has seen «📱 Подключить телефон» in a live bot, nor the
  confirmation in the app, nor a screen with a code that has stopped working.
- **Nobody has ever seen the `429` on the code screen** — neither in its new form nor its
  old. The limiter fires after thirty failures in fifteen minutes, and nobody in this
  environment has got that far.
- **The upgrade path is verified by keys rather than by an installation.** An old install
  kept its class in four flat keys; a test builds exactly those keys and makes sure the class
  is found and the "introduction shown" flag is not reset. Nobody has upgraded a real
  installation of the previous version. If that is broken, the user sees the code field
  instead of their class — and nothing in the logs.
- **Nobody has looked at the build-chain bumps.** AGP 9.3.1 → 9.4.0 → 9.4.1, Gradle
  9.5.0 → 9.7.1 and compose-bom 2026.06.01 → 2026.09.00 are proved by every test passing and
  both assembles building — locally and on the runner. The same goes for the two Python
  floors raised the same way, sqlalchemy 2.0.54 and pydantic 2.13.5: the suite passes on
  them and nobody read either changelog. The compose-bom is **the app's entire
  rendering**, and one of its effects already surfaced by itself (`OverlayLayerTest`,
  section 6); what it changed where there is no test, nobody knows. That is the first reason
  on the list to open the APK.
- **Both alarm fixes are unverifiable without a device, in principle.** They are inferred
  from the platform's contract and from neighbouring code that had already taken the same
  decision (`SchoolAlerts.arm` refused `setWindow` and explained why).
- **A live DaData has never been asked**: there is no `DADATA_TOKEN` in the development
  environment, and every test on both sides runs a stub. The owner reported setting the key
  on Vercel — **nothing has verified that.**
- **The real dnevnik2 has never been opened**, neither the sign-in nor any of the four
  screens.
- **Not one server-side fix has been run against a live class.** `claim` is verified by
  claiming twice on one date, but nobody has run two ticks at once; the export's round trip
  is verified by computing both sides, which is all it claims.

- **Nothing from the last batch has been seen moving, or wrapping.** Robolectric composes
  the marquee but advances no animation and reads no frame, so what is proven is that a line
  which fits is laid out exactly as before, that a line which does not stays inside its box
  rather than pushing the row apart, and that the whole string is present either way. That it
  actually scrolls — and reads well doing so — needs the APK. The same for the other
  direction: nobody has seen what an uncapped block does with a long string. **A five-line
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
  naming its version and the arrow that leaves it have been seen by nobody.

**The most useful next action is to install the APK on a phone and live with it for one
school day.** After that the only questions left are about runtime and layout, and those are
invisible from anywhere except a real screen.

---

## 6. What you need to know so as not to break things

Beyond what is already in `CLAUDE.md`.

### CI and building the APK

`android-actions/setup-android` is **pinned to `v4.0.1`** and is given
`packages: platform-tools` — in `ci.yml` and in `apk.yml`. That is not cosmetic: a floating
`@v4` asks by default for the `tools` package, which Google removed, and `sdkmanager` now
returns a non-zero code for it. Without the pin, every Android run fails before Gradle
starts. **Do not remove the pin and do not drop `packages` without checking that the action
has stopped asking for `tools`.**

The APK's "Run workflow" takes `main` by default, and after the merge that is finally the
right branch. The last build sitting in the artifacts —
[run 34948610365](https://github.com/lumenpearson/lessons/actions/runs/34948610365), the
`lessons-apk` artifact — was made from the former working branch, that is, **without** AGP
9.4.0, Gradle 9.7.1, the new compose-bom and everything described in this file. Build it
again: that combination has gone through on the runner many times, but nobody has held an
APK with it in their hands.

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

The Room schema is at version 3. There is no migration and none is needed — the cache is
disposable (`fallbackToDestructiveMigration`) and the first sync fills it.

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

### Everything that came from outside is escaped before it is sent

The bot sends HTML, and on one angle bracket Telegram refuses **the whole message** rather
than spoiling a line. So an unescaped string does not produce a broken layout, it produces
**a blank screen and not one error anybody will see**.

What comes from outside is: everything from the Petersburg diary (subject, room, teacher,
topic, homework), everything typed into the bot and pasted into the paste grammar (a subject
really can be called «Алгебра <7>»), and everything from the schools registry. `render.py`
always did this; `diary_render.py` and `editor_render.py` never did, and both shipped that
way.

Two traps of the same kind next door. `plural(n, …)` **already contains the number**, so
`f"{n} {plural(n, …)}"` prints «10 10 минут» — it was in three places, and one had a test
that passed because «10 минут» is a substring. And `answerCallbackQuery` has **no parse
mode**: a card assembled for a message shows its own tags in an alert, which is what
`editor_render.as_alert` is for — it also cuts at 200 characters, because past that Telegram
answers 400 and the press answers with nothing at all.

### Callback data is whatever the client sent, and this is not caution for its own sake

A client is free to send any string as callback data. The difference between `int(x)` and
"check and refuse" is not tidiness: a bare `int()` **escapes the handler**, which means
`callback.answer()` is never called and the button spins until Telegram gives up. `manage.py`
and `tasks.py` have `_int_or_none` for this, `calendar.py` and `content.py` have
`_date_or_none`, `access.py` now has `_int_or_none` and `_role_or_none`, and `content.py` also
has `_kind_or_none` and `_index_or_none`. The rule is one: **check where the value is
picked**, not where it is finally read — otherwise it travels through three questions and
fails in front of somebody who has already typed a time and a title.

The same goes for arithmetic: `shift_days` / `shift_weeks` in `keyboards.py` build the date
and let `date` say whether it is one. `timedelta(days=999999999)` is an `OverflowError`, not
a far-away day.

### Two screens can catch one press

Both flows send a `RolePick` — inviting by number and changing a member's role — and the only
thing that tells them apart is that the first leaves `target` empty. While the invite's
filter was simply `RolePick.filter()`, it took both, because it stood higher in the file. If
you add a second screen on an existing payload, **separate the filters by a field** rather
than by registration order; `test_the_two_role_pickers_never_match_the_same_press` holds
this.

### The translation is guarded in every module that ships strings

`ResourceTranslationTest` used to read `:app` and nothing else, while there are strings in
`:core:data`, `:core:designsystem` and `:widget` too — including the countdown on the home
screen. It now finds every module with a `values/strings.xml` by itself, names the module in
every error, and a separate test holds the list itself, so that the walk cannot narrow
silently. A `<plurals>`' arguments are counted **per form**: Russian has four and English
two, and over the concatenation they can never agree (it never showed in `:app`, because
everything there is `%1$d` rather than `%d`).

Russian text hard-coded into Kotlin is not caught by this at all — those words are in neither
folder. Check it this way: `grep -rnP '"[^"]*[\x{0400}-\x{04FF}]'` over `src/main`. Today
what is left is only `@Preview`, the maintainer-facing report bodies, and the timezone list,
where that decision is recorded in the file itself.

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

### A constraint migrates in the opposite direction from a column

The rule "migration before the merge" is about code that knows a column the database does
not. For this chain it is right everywhere but one case: **a `UNIQUE` and a `NOT NULL` break
the old code, not the new.** Before `services/homework.py` reaches `main`, the losing side of
a race does an ordinary INSERT and gets an `IntegrityError` nobody catches — that is, a 500
where today there is a duplicate. So `0013` is applied **after** the merge, and that is
written in the revision itself; `services/homework.py` is deliberately correct without the
constraint too, so that the window between the merge and the application behaves as things do
today.

`0012` is the safe shape (eight timestamps that already hold no NULLs) and was applied the
usual way, before the merge. **The database was read before both**, and that paid off: unlike
`0011`, `0012` turned out not to be a no-op — all eight columns in production really were
nullable.

**The database's state right now:** Neon's head is `0013`, and it agrees with
`EXPECTED_REVISION`. The revision was applied as one transaction through the connector right
after PR #45 was merged, with the stamp last inside it; `homework` was empty, so its
deduplication deleted no rows, and `uq_homework_per_subject_per_day` now stands as exactly
what the model builds.

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

### The school year ends, and the template with it

`SCHOOL_YEAR_END_MONTH` is 5, and the comment beside it explains why the template must not
repeat from June: it "would show lessons nobody is going to". Only `school_year_bounds` read
that constant. `_resolve_day` now asks it too. A day marked by hand keeps its kind and its
note; events and homework are kept either way — it is the lessons that are out of season, not
the day.

The rule is about a **date** rather than about the quarters. A school that really does teach
in June enters those days as events.

### A lesson's ceiling is a set of numbers, not a count of them

The resolver takes a lesson's time from the bell row **with the same number** (`schedule.py`,
`period = bells.get(entry.index)`). So the question "may lesson 4 be written" is "is there a
bell 4", not "how many bells are there in total". The paste grammar allows a gap
(`parse_bells_block` refuses only on `index < 1` and on duplicates), so for a class with bells
at 1, 2, 4 a count is wrong in both directions at once. `timetable_edit.rung_indexes` answers
the right question, `can_ring` asks it, and `rings` stayed a counter because it is printed as
"there are N lessons in the bell schedule".

Every entrance is closed: a substitution (`api/edit.py`, `bot/handlers/content.py`) and the
single-day paste check the bell, the day paste goes through `apply_timetable`, deleting a
lesson closes the gap in the numbering **only** if every lesson it moves lands on a number
that rings (otherwise a class with bells at 1, 2, 4 lost its fourth lesson by shifting it
onto the third), and a «⏱ Сокращённый день» cannot be hung on a bell schedule with no rows —
neither from the API nor from the bot: such a day draws nothing at all.

**And count rows, not numbers.** `apply_timetable` returns a `TimetableImport` with (day,
number) pairs: «⚠️ Не добавлены уроки № …» names a number once — that is one thing to fix —
while "N skipped, no bell" and `rejected` in the API count the rows that were dropped,
because one number under two days (or under «чёт»/«нечёт» in one day) is two lessons.

### A slot is one "every week" row or two halves

`uq_timetable_cell` is `(class, day, number, parity)`, and "every week" next to "numerator"
does not violate that key. Both rows pass the parity filter on an odd week, and `_load`
selects the template **with no `ORDER BY`** and keeps the last — so which subject the phone
sees is decided by the database, and the answer can differ between two reads. Three places
hold the rule, and all three are needed: `timetable_io._conflicts` on a paste, the editor's
handler, and — as of this commit — `timetable_edit.edit_lesson`, that is, the one entrance of
the second shell.

### A list's length and its keyboard's length are one number, not two

`manage_render` declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX` and `LIST_MAX` (special
days), and `manage_keyboards` builds its rows **from those same ones**. While there were two
numbers, three pages of four drew rows nothing could reach, and «… и ещё N» said nothing about
them, because it counted from its own number. Do not "bring them to one value for tidiness":
the numbers differ on purpose — a bell schedule's row carries three buttons and twelve lines
of times, a subject's one of each. The rule is one: what is drawn is what can be pressed, and
`test_no_list_page_draws_a_row_the_keyboard_cannot_reach` holds it.

The pages **do not paginate** — none of them. Past the cap a row is only a number in
«… и ещё N»; for a class with forty subjects the last ten are unreachable from the bot
entirely. Raising the caps is a wall of buttons; the real fix is pagination, and nobody has
written it.

### `python -m mypy` answers one question, and that is enough

Not "type everything" but "does the code reach for an attribute the type does not have". That
is exactly what crashed «🗓 Четверти», and the check reproduces it word for word if the
property is put back. It is configured in `pyproject.toml`, where **every other code is
switched off by name, with a count and a reason** — a check whose output cannot be read is a
check nobody runs.

It is currently clean across all 78 modules. The one thing that stood between the project and
cleanliness was `Result.rowcount`: `AsyncSession.execute` is typed as returning a
`Result[Any]`, which has no such field, while DML returns a `CursorResult`, which does.
Eleven places wrote `result.rowcount or 0` and meant one thing; that is now
`db.rows_affected`, and the cast lives in one place.

**It is not in CI.** `CLAUDE.md` asks that the workflows not be edited casually, and adding a
job is the owner's decision, which nobody asked for. Run it by hand before pushing server
code.

### A renderer is written against the type it will be handed

«🗓 Четверти» crashed in production on every press, because the card read `term.days`, while
`days` belonged to `TermView` — a dataclass nobody ever constructed. Two types for one entity
is a way to write a renderer against the one that never reaches it; there is no compiler
here, and an `AttributeError` is visible only from the button.

The moral is not "add type hints" but something more specific: **if a model's "flattened copy
for the renderer" sits beside it, check that somebody creates it.** And remember where the
tests' boundary runs: `tests/test_terms.py` opens with the words "the rules, not the
rendering", and that is an honest split right up to the day nobody checks the rendering.
The terms card is now drawn by a test (`test_the_terms_card_draws`), and it asserts the
numbers rather than only that the call returned: a card with «0 дн.» is as wrong as one that
crashed.

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

The root `requirements.txt` and `server/pyproject.toml` have to carry the same floors.
Dependabot edits **the root file only** — which is how `main` drifted
(`sqlalchemy>=2.0.52` against `>=2.0.30`, `pydantic-settings>=2.15.0` against `>=2.4`). Tests
hold this now rather than attentiveness: `tests/test_requirements_mirror.py` checks both
directions and the versions themselves, and the three packages deliberately absent from the
bundle (`uvicorn`, `aiosqlite`, `alembic`) are listed there with their reasons. A floor
raised in one file only is green CI and a function that installs the old one.

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

### The ceiling on lessons in a day

Not `MAX_INDEX` but the class's bell count. A lesson with a number that has no row in the
bell schedule cannot be placed by the resolver and used to be dropped silently. On import the
ceiling is computed from what the class will ring **after** the import — a paste with its own
`== Звонки ==` block legitimately brings a ninth bell and a ninth lesson in one message.

### The timetable paste grammar

The last field takes the rest of the line, so a teacher «Иванов И.И., к.п.н.» is written with
no syntax. A subject or a room containing a comma is wrapped in double quotes, and a `""`
inside is one literal quote. Quotes are used only where they are needed, so any line already
written parses as it did before.

---

### The agent configuration lives in `.claude/`

There was none at all — no settings, no agents, no skills. There is now, and it is arranged
so as not to retell `CLAUDE.md` but to describe the shape: who owns what, which checks are
real, and which commands are never run from a session.

```
.claude/settings.json   permissions, two hooks, the marketplace this project knows about
.claude/agents/         eighteen agents by area
.claude/skills/         nine procedures
.claude/commands/       /where-are-we and /pre-push
.claude/hooks/          the one hook too long to live inline
.claude/README.md       what is here and what is deliberately absent
AGENTS.md               a pointer for agents that read something other than `CLAUDE.md`
.github/copilot-instructions.md   a short file: it is read on every request
```

Eighteen agents rather than a hundred, because each file exists for a trap that has already
sprung in that area, and carries the fact that would have prevented it. An agent that retells
`CLAUDE.md` would not survive the same review that lets no comment through that retells the
line below it, and a stub agent is worse than no agent: it answers confidently from nothing.
The nine-way split in `skills/audit/SKILL.md` is the one that found thirty-one findings in a
pass; the other nine are areas that have since grown traps of their own.

Three things in `settings.json` worth knowing:

* **`deny` compares the start of the command string.** `DATABASE_URL=… .venv/bin/alembic
  upgrade head` walks past the `alembic upgrade *` rule. Those are guard rails, not a fence;
  the reason not to run it by hand is in `skills/migration/SKILL.md`.
* **There are two hooks and both only print.** On a write to any module's
  `values/strings*.xml`, the first mentions the twin in `values-en/`. The second,
  `hooks/handover-behind.sh`, runs on `Stop` and speaks only when a merge commit on `HEAD`
  **neither carried the close-out nor predates it** — a state that exists only after a pull
  request has merged and `dev` has been fast-forwarded onto it, which is exactly when the
  close-out is the work that is left. The first half of that condition is the correction:
  a close-out written inside its own pull request, which is what the rule asks for while
  that pull request is open, is landed *by* the merge and is therefore always older than
  it. Comparing timestamps alone called that missing, and did so the first time the rule
  was followed properly — on #67. A committed hook runs on the machine of everybody
  who cloned the repository, which is why both are this small and why neither can fail:
  each exits quietly on anything unexpected.
* **`extraKnownMarketplaces` registers `anthropics/skills`**, but no plugin is enabled:
  enabling one is a decision for everybody who clones the repository, not for the session
  that added the file.

**There is deliberately no `.mcp.json`.** The Neon and Vercel connections need credentials,
and secrets do not enter this repository. The Neon project is the one **named `lessons`**
(the account has two), and that is all that can safely be written down.

### Everything written about the project is English

The product speaks Russian; everything written *about* the project is English. User-facing
strings stay where they were — `values/` is Russian and is the source, `values-en/` is the
translation, and the reader picks the language in the app — and every string the bot sends is
untouched. What moved: `README.md`, all of `docs/`, this file, the community documents, the
issue and pull request templates, and every comment and docstring under `server/` and
`android/`.

Where any of those quotes a button, a menu path or an error the reader will see, it quotes it
in Russian, in guillemets, because that is what is on the screen. «🔔 Звонки» is a quotation,
not prose.

Two things about the edges of that rule:

* **The pull requests were rewritten on GitHub**, titles, descriptions and the eight
  Russian comments under #16, #22, #34, #35 and #47. Those are GitHub objects, not repository
  content: nothing in git moved, and no hash changed. The bots' comments are not ours and were
  left alone.
* **The commit history keeps its Russian, by decision.** Of 242 commits, 41 carry Russian
  prose outside quoted product strings — about 2,055 characters, mostly in merge-commit
  bodies. Rewriting them means rewriting every hash from the first affected commit and force
  pushing `main` and `dev`: every existing clone breaks, the merge references in the pull
  requests stop resolving, and the release tags move. The owner decided not to. So a `git log`
  older than this batch reads in two languages, and that is expected rather than missed.

The rule itself is written into `CLAUDE.md`, `AGENTS.md`, `.github/copilot-instructions.md`
and the `.claude/` agents and skills that touch strings or releases. Two of them had been
missed and told the next session to write **this file** in Russian — the `handover` skill
and the `handover-keeper` agent. Both now say English, and quote the README's "Written,
never run" instead of its Russian ancestor.

### What the documents claimed, and what was actually true

A number is the part of a document that rots first, so every one of them was re-measured on
19 September 2026 rather than copied forward:

| Measured | Now |
| --- | --- |
| server tests | 1391, over 38 files |
| Android tests | 605, over 73 classes — `:core:model` 89, `:core:data` 186, `:core:designsystem` 45, `:widget` 45, `:app` 240 |
| modules under `mypy` | 79 |
| commits | 258 |
| schema head | `0013` |

What was wrong and is now right:

* `CLAUDE.md` said 1362 tests and 178 commits.
* `docs/architecture.md` gave `schedule.py` fifteen tests (it has twenty-two) and
  `:core:model` fourteen (it has eighty-nine), listed eleven of the thirty-eight server
  suites as though that were all of them, and ended with "the Android UI and the Glance
  widget have no automated coverage yet" — which stopped being true when three screens went
  under Robolectric and the size ladder got a test that walks real sizes.
* `CONTRIBUTING.md` said the same thing about screens and the widget.
* `docs/build.md` quoted 1340 tests without saying that was the count at the time of that
  measurement, so it read as the current one.

Everything else was checked and left alone: `README.md`'s "Honest status", `docs/README.md`,
the `0013` head wherever it is named, the twelve rungs, and the eight documents plus an
index. The sweep changed no code and ran no gate — there was nothing to run.

**One thing it found and did not touch.** Five test functions on the server carry a Russian
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
environment, #119 `/api/v1/warmup` and the bot's `/start`, #120 the external cron and
`DADATA_TOKEN`, #121 the real diary, #122 the widget's tick cadence and the diary
credential's bound, #135 the second diary. Each carries the label `needs:owner`.

**Create the tenth milestone so #140 can leave draft.** #140 adds the second diary and closes
#136–#139, but it belongs to no existing milestone; nothing in a session here can create one,
so it stays a draft until the owner makes a tenth (e.g. «v0.9.0 — A second diary») and
attaches it, with the title and description already handed over.

**The keep-alive is only as alive as the cron (#120).** «Сетевой город» sessions are held
open from `GET /api/v1/cron/tick`, so they lapse if the external cron does not tick;
`.github/workflows/reminders.yml` is the fallback, not the clock, exactly as for the digests.

**#140 answered the first of #135's three questions; the other two remain.** `docs/diaries.md`
is the map:

- **Which platform — settled.** #140 built «Сетевой город» server-side: one route set, the
  largest group of regions, and a password still accepted outside the regions that allow
  Госуслуги only. The МЭШ family comes after it. Against a live server it has not been read.
- **How a family signs in**, now that Госуслуги is the only door in most regions. Either
  the server accepts a token a person brought from a browser, which is what every durable
  client does, or it drives ЕСИА itself and breaks whenever Госуслуги changes its login.
  The first makes `/diary/signin` a page with a second job, and `CLAUDE.md` guards that page
  closely.
- **Whether ТОР «Моя школа» may be used at all.** It is the diary of twenty regions since
  1 September 2026. Its only known way in is a person signing in to Госуслуги inside a
  WebView, and nobody has read Госуслуги's terms.

Whichever platform it is, the first step is one real session against it, as #121 is for
Петербург. Not one route in `docs/diaries/` has been seen answering.

**Install one built after #85 and long-press a tab on the home screen.** The APK on #84's
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

**Open `/api/v1/warmup` in a browser — it is the one thing that settles whether `0014` and
the code that needs it actually met**, and it is outstanding since #61. It cannot be closed
from a session here at any effort: the deployments are behind Vercel's Deployment Protection
and answer a redirect to a login page, so this needs the owner's own browser or a bypass
token. The answer wanted is `{"status":"ok","api_version":1,"schema":"0014"}`; a
`"degraded"` naming two revisions is the honest report of a migration and a deploy that have
not met, and which way round it is, is in the `detail`.

**The APK's own badges are new in #80 and they are worth one press.** The about page now
names the server's state, the repository, the ref and the commit the build came from. Two
of those cannot be checked from here at all: whether the server badge says the right thing
about the real deployment, and whether the commit chip opens the right diff on a phone.
Both are a few seconds on the page that already exists.

**One of these stopped being theoretical in #79, and it is the most useful thing that has
happened to this project.** The owner installed an APK, used it, and sent back both the
symptom and the full Android bugreport. That found three defects in a screen with three
passing test suites over it, and settled in one line a question the branch could only
speculate about — whether the crash on rotation was a fourth defect. It was not.
**Every batch that changes a screen is worth an APK and five minutes of the owner's
thumb**, and the bugreport is worth more than the description, because the description
cannot contain `not-handles={}`.

**The calendar year-scroll was a decision and it has been made.** The owner chose (b), the
windowed sync, on 21 September 2026, over (a), a picker bound to the year already synced —
which would have scrolled to a year with no days in it. It is done, in #78. What is left of
it for the owner is one number: the cache keeps three school years per class, which is a
guess rather than a measurement, and nobody has watched what four costs on a real phone.

**The hour ruler is gone.** «Календарь → День» is the ribbon now, and a reader who preferred
reading the day by position — a gap as a height rather than as a row — has lost that. It was
a deliberate replacement rather than an addition, because two views of one day is how the
bot's two timetable editors nearly drifted apart; if it turns out to be missed, the ruler is
in the history at `f5a8172^`.

**The milestones were the newest of these, and that one is done.** Milestones 1 to 5 cover
versions that are finished and the owner has closed all five. Three stay open on purpose:
`v0.6.0`, which is finished but not yet closed; **`v0.7.0 — Оптимизация`, number 8**, which
the owner created when none of the earlier ones fitted and which every batch from #75 to
#84 has gone in; and `Dependencies`, which takes every future bump. There is no
number 7 — the numbering is GitHub's and it skips. The reason a new one has to be asked for
stands for next time: no tool in a session here changes a milestone's state or creates one —
`issue_write` only assigns an existing one by number — and there is no `gh` CLI.

**The first press of two network paths should be the owner's.** Neither the translation
pull request from #64 nor the guide's fetch from #67 has ever run against GitHub, and both
are written to be pressed by a reader. Opening the documentation once on a real phone, with
and without a network, checks the second of them in about a minute — and the first press of
«Отправить как pull request» checks the first.

**What is left of it is one line of text.** Milestone 6 describes itself as «PRs #60–#62»
and now holds everything up to #74. Editing that description needs the same access closing
it does.

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

~~1. **Decide the fate of PR #43.**~~ Merged. The order was kept: `0010` (the join mode and
   the personal-codes table) and `0011` (two timestamps in `diary_overrides` brought to `NOT
   NULL`) were applied through the Neon connector **before** the merge. Neither destroyed
   anything: `0010` added a column with a default preserving the current behaviour, plus an
   empty table; `0011` changed nothing at all on this database — the columns were already
   `NOT NULL`, because the DDL for `0009` came from the model rather than from the revision's
   text. Verified afterwards: head `0011`, the single class reads `OPEN`, `device_invites`
   matches the model column for column, three indexes.
~~1a. **Apply `0013` after PR #45 is merged.**~~ Applied: head `0013`, the constraint in
   place, zero rows deleted (`homework` had none).
1b. **Open `/api/v1/warmup` and make sure the deploy arrived** — today that is
   `{"status":"ok","schema":"0014"}` — and then send the bot `/start`: the very first message
   goes through the middleware that reads a class, and that is the fastest check that the
   schema and the code agree. That is all that is left of item 1; it needs a live service,
   and since the deployments sit behind Deployment Protection it needs a browser or a bypass
   token rather than a `curl` from a session.
2. **Build the APK from `main` and install it on a phone.** See section 5 — it is the only
   way to check what nothing currently checks, and doubly so after three build-chain bumps.
   Three things have been added to this: connect the phone to two classes and walk between
   them, looking at the widget after a switch; if a phone with the previous version is to
   hand, upgrade it in place and make sure the class is still there and the introduction is
   not shown again; and sign into a real diary, press a lesson, correct the room and reset
   it. And a fourth: put a class into "invitation only", make sure a connected phone goes on
   working, take a personal code with the «📱 Подключить телефон» button and connect a second
   phone with it.
3. **Make sure `DADATA_TOKEN` really works** on Vercel (Production and Preview; it is the API
   key, not the secret one).
4. **Open «Настройки → О приложении → Лицензии» in the app** and look at the eighth row —
   Google Sans Flex. It is built and it compiles, but nobody has seen it with their eyes:
   what is of interest is how an eighth shade sits in a palette of six.
5. **Switch on «Настройки → Перевод → Режим исправления» and walk the screens.** What is
   being checked is what a JVM test cannot see: that the outline appears around labels rather
   than around subject names; that a long press on a settings row opens the editor rather
   than toggling the row (the gesture is read on `PointerEventPass.Initial` precisely for
   this); that pressing a sentence with a number in it — «12,4 МБ» under updates — shows the
   pattern `%1$s МБ` in the editor rather than the sum; and that inside the editor itself a
   long press does nothing.

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
