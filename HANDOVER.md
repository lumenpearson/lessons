# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.

Last updated: **19 September 2026**, after PRs #47, #48, #49, #50 and #51 were merged. The
last of them is the agent configuration in `.claude/` and the whole written layer of the
project moved into English (see "Everything written about the project is English" in
section 6). `main` and `dev` met at `85064cd`. The database is at head `0013` and
`EXPECTED_REVISION` did not move, so that merge needed no migration. The production
deployment it triggered is `READY` on `85064cd`; `/api/v1/warmup` has not been read since,
and it is the only thing that will say whether the schema and the code still agree.

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
pull request accepts no new commits, and the whole history of #45 is in `main`.

```bash
git fetch origin
git checkout -B dev origin/main   # the same dev, a new starting point
```

The gates, both halves (`CLAUDE.md` requires running both if you touched both):

```bash
cd server  && ruff check app tests scripts migrations   # clean
cd server  && python -m pytest -q -n auto                # 1391 tests, ~1.5 min
cd server  && python -m mypy                             # clean, 79 modules
cd android && ./gradlew test                             # 605 tests
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
- **Nobody has looked at the three build-chain bumps.** AGP 9.3.1 → 9.4.0, Gradle
  9.5.0 → 9.7.1 and compose-bom 2026.06.01 → 2026.09.00 are proved by every test passing and
  both assembles building — locally and on the runner. The compose-bom is **the app's entire
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
.claude/settings.json   permissions, one hook, the marketplace this project knows about
.claude/agents/         eighteen agents by area
.claude/skills/         nine procedures
.claude/commands/       /where-are-we and /pre-push
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
* **There is exactly one hook**, and it only prints: on a write to any module's
  `values/strings*.xml` it mentions the twin in `values-en/`. A committed hook runs on the
  machine of everybody who cloned the repository — which is why there is one and why it
  cannot fail.
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
and the `.claude/` agents and skills that touch strings or releases.

## 7. Left to the owner

All of this is beyond an agent's reach: it needs a phone, a key or a live service.

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
