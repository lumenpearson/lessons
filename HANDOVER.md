# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.

Last updated: **21 September 2026**. **PRs #63 through #74 are merged**; `main` is at
`9920a9a`. **The only thing open is PR #75**, which carries this batch and the paragraph you
are reading. Once it merges, `dev` is level with `main` again and the next batch starts from
a clean one, and the SHA of that merge is for the next close-out to write.
The database is at head `0013` and `EXPECTED_REVISION` did not move: **no model has changed
since, so none of them needed a migration**, which is the cheapest thing to check and the
most expensive to get wrong.

Production was read after #60 and again after #61, rather than assumed: `/api/v1/health`
answers `{"status":"ok","api_version":1}` and `/api/v1/warmup` — which opens a real
connection, so it answers for the database as well as the code — answers
`{"status":"ok","api_version":1,"schema":"0013"}`. That is the dishka container serving a
real request on a real cold start, which is the one thing about it the branch could not
check before it merged. **It has not been re-read since, and did not need to be:** everything
from #62 onwards touched no server code at all — Android, its tests, the build and the
documents.

## What the last session added: the typeface is compressed by the build

One commit in `dev`, open as PR #75. **The milestone does not exist yet** — the owner was
asked to create `v0.7.0` and this pull request gets its number the moment they hand it over;
the command is in the `github-pr` skill and the text is in the request. Until then it is the
one bare pull request in this repository, and that is a known debt rather than an oversight.

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
| — | `v0.7.0`, asked for and not yet created | #75 |
| 7 | `Dependencies` | every dependabot bump; deliberately not a version |

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

**Section 3.1 is closed entirely.** No requested work is left untaken.

```
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
`0013`.

**The five dependabot pull requests can now be closed without regret** — their bumps arrived
in `main` together with `dev`. If it managed to recreate them before the merge, they will
turn empty by themselves.

### How to continue

`dev` remains the working branch, but after a merge it is restarted from `main`: a merged
pull request accepts no new commits, and every branch that has been merged — #45 through
#66 — is in `main` already, and #67 is the one still open.

```bash
git fetch origin
git checkout -B dev origin/main   # the same dev, a new starting point
```

The gates, both halves (`CLAUDE.md` requires running both if you touched both):

```bash
cd server  && ruff check app tests scripts migrations   # clean
cd server  && python -m pytest -q -n auto                # 1559 tests, ~1.5 min
cd server  && python -m mypy                             # clean, 83 modules
cd android && ./gradlew test                             # 770 tests
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

**Migrations:** production is at `0013`, and that is the last revision written. `0007`–`0012`
were applied **before** the merge through the Neon connector and `0013` after it; the details
and what each one did are in section 7, item 1. **There is nothing left to do to the database
before a merge.**

---

## 3. What has NOT been done

### 3.1. The features that were requested — all three are done

**No requested work is left untaken.** The section is kept struck through rather than
deleted: it shows what exactly was asked for and what it turned out to be.

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

---

## 5. What nobody has verified

This is the main thing worth knowing: **all of this work is proved by tests and by nothing
else.**

- **There is still no `androidTest` in the project**, and no emulator is available here: the
  container has no `/dev/kvm` and no virtualisation flags, so the system could only be
  started by full software emulation, that is, not at all.
  **But "nobody has pressed it" is already untrue for three screens.** Robolectric runs
  Compose's test harness on the JVM (`:core:designsystem` already lived this way), and the
  same now exists in `:app`: the class group, the join-mode switch and the refusal text on
  the code screen are composed, pressed and checked against their strings — 21 tests. Those
  are real presses on real strings rather than stubs: the locale is pinned to `ru-rRU`, or
  Robolectric takes `values-en/` and the test checks the translation instead of the source.
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

**The milestones were the newest of these, and that one is done.** Milestones 1 to 5 cover
versions that are finished and the owner has closed all five; `v0.6.0` and `Dependencies`
stay open on purpose, the first because it is the version being worked on and the second
because it takes every future bump. The reason it had to be asked for stands for next time:
no tool in a session here changes a milestone's state or creates one — `issue_write` only
assigns an existing one by number — and there is no `gh` CLI.

**The first press of two network paths should be the owner's.** Neither the translation
pull request from #64 nor the guide's fetch from #67 has ever run against GitHub, and both
are written to be pressed by a reader. Opening the documentation once on a real phone, with
and without a network, checks the second of them in about a minute — and the first press of
«Отправить как pull request» checks the first.

**What is left of it is one line of text.** Milestone 6 describes itself as «PRs #60–#62»
and now holds #63 and #64 as well. Editing that description needs the same access closing
them did.

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
1b. **Open `/api/v1/warmup` and make sure the deploy arrived** —
   `{"status":"ok","schema":"0013"}` — and then send the bot `/start`: the very first message
   goes through the middleware that reads a class, and that is the fastest check that the
   schema and the code agree. That is all that is left of item 1 and it needs a live service.
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
