# Дневник — timetable, widget and bot

The repository is `lessons`; the product is called «Дневник», which is what a Russian
school calls the paper diary this replaces.

Lessons, substitutions, homework and class events — for schools in Russia. An Android app,
an admin panel that lives in Telegram, and a widget of any size that shows what is
happening right now.

It accounts for what makes a Russian school different from an abstract one: a six-day
week, numerator and denominator weeks, substitutions, shortened days, holidays, and eleven
time zones from Kaliningrad to Kamchatka.

> The design and some of the components come from
> [sameerasw/essentials](https://github.com/sameerasw/essentials).
> What exactly was taken and what was done differently is in [docs/design.md](docs/design.md).

---

## What is here

| Part | Stack | Where it is described |
| --- | --- | --- |
| **App** — three tabs, nine settings sections, several classes on one phone, Russian and English | Kotlin 2.4, Compose, Material 3 Expressive | [docs/design.md](docs/design.md) |
| **Widget** — twelve sizes, seven states, works offline | Glance | [docs/widget.md](docs/widget.md) |
| **Notifications** — the bell, the morning digest, tomorrow's homework, substitutions | local alarms, no push | [docs/design.md](docs/design.md#notifications) |
| **Bot** — and the admin panel: roles, timetable, bells, subjects, the log, who may connect a phone | aiogram 3 | [docs/bot.md](docs/bot.md) |
| **Server** — reads and writes over `/api/v1`, the reminder tick, the calendar | FastAPI, SQLAlchemy 2, Alembic | [docs/api.md](docs/api.md) |
| **Petersburg electronic diary** — the family's timetable, assignments and marks, with corrections laid over them | a separate account, behind one boundary | [docs/api.md](docs/api.md#the-petersburg-electronic-diary) |

One process and one database: the bot and the client-facing API live together, and the
rule by which something changes lives in one place for both —
[docs/architecture.md](docs/architecture.md).

## The point of it is the widget

The widget answers one question — "what now?" — and when the lessons end it switches by
itself to "what is set": for tomorrow, on Friday for Monday, and across the holidays too.

It stretches to any size and changes what it draws along with it — from one line with a
state and a countdown at 2×1 to the whole day together with its homework at 5×5 and
larger. There are twelve rungs rather than five, and that is not slack: the launcher picks
the **nearest** breakpoint, not the largest that fits, and with five rungs a wide tall
widget ended up closer to the narrow column. The whole table is in
[docs/widget.md](docs/widget.md).

There are seven states: **before lessons**, **lesson**, **break**, **event**, **after
lessons**, **day off**, **no data**.

Three things make it liveable:

* **It works offline.** Room is the single source of truth; the network only fills it. The
  countdown keeps running with no internet.
* **It does not wake the phone for nothing.** Instead of ticking once a minute, the widget
  sets one alarm for the moment the text will change, and only quickens in the last ten
  minutes of a lesson.
* **It knows the school's time, not the phone's.** "Now" is computed in the class's time
  zone, so a parent in Moscow sees a Novosibirsk school's bells.

## For Russian schools

| What is particular | How it is supported |
| --- | --- |
| A six-day week | the timetable is set for Monday to Saturday |
| Numerator / denominator | a lesson in the template carries the parity of its week |
| Substitutions and cancellations | separate rows on a date; the weekly template is untouched |
| Shortened days | their own bell schedule on a specific date |
| Holidays | the day is marked as a day off, and homework moves to the next school day |
| 11 time zones | the zone belongs to the class, not to the server |

The zone is chosen when the class is created and changed in «⚙️ Класс → 🕒 Часовой пояс».
The bells do not shift when it changes — only the clock by which "now" is decided does.

## Two languages

The interface is Russian with an English translation: the screens, the notifications and
the widget entirely. The language is chosen inside the app — on the very first screen of
the first run, and later under «Оформление» — rather than only in the phone's settings.

The completeness of the translation is held by a test, not by attentiveness: Android
substitutes a Russian string for a missing English one without a word, so
`ResourceTranslationTest` reads both resource folders and demands a twin for every name,
matching format arguments, and exactly `one` + `other` in the English plural forms.

The quality is held by whoever reads it. «Настройки → Перевод → Режим исправления»
outlines **all** of the app's text and opens an editor on a long press on any of it; what
accumulates comes out as a ready piece of `values/` that only has to be sent in. The
coverage is not a list of places wrapped by hand: the app has one `Text`, and it is its
own.

## Quick start

### The server

```bash
cd server
python3 -m venv .venv && .venv/bin/pip install -e ".[dev]"
cp .env.example .env          # fill in BOT_TOKEN and OWNER_IDS
.venv/bin/python -m scripts.seed_demo        # demo class, code DEMO24
.venv/bin/python -m uvicorn app.main:app --reload
```

To check it:

```bash
curl -s localhost:8000/api/v1/health
TOKEN=$(curl -s -X POST localhost:8000/api/v1/join \
  -H 'Content-Type: application/json' -d '{"code":"DEMO24"}' | jq -r .token)
curl -s "localhost:8000/api/v1/bundle?days=7" -H "Authorization: Bearer $TOKEN" | jq
```

The checks CI runs:

```bash
ruff check app tests scripts migrations
python -m pytest -q
```

The schema is Alembic: `alembic upgrade head` with a working `DATABASE_URL`, from a
working copy rather than from inside a function. How to deploy on Vercel plus Neon, or on
your own server, is in [docs/deploy.md](docs/deploy.md).

### The bot

1. A token from [@BotFather](https://t.me/BotFather), your own id from [@userinfobot](https://t.me/userinfobot).
2. `BOT_TOKEN` and `OWNER_IDS` into `server/.env`.
3. `/start` in the bot → it offers to create the first class and hands out a code for the
   app.

The roles are **observer → editor → administrator → owner**. You can add somebody as an
editor by phone number: an admin sends the number, the person presses «Поделиться
номером» in the bot, and the role is granted automatically. In detail —
[docs/bot.md](docs/bot.md).

### The app

You do not have to build the APK by hand: **Actions → APK → Run workflow**, and a few
minutes later it is in the run's artifacts. A `v*` tag additionally creates a GitHub
Release with the APK attached, and the app itself can check those releases and offer an
update.

Locally:

```bash
cd android
./gradlew test              # every JVM test across the five modules
./gradlew assembleDebug     # or assembleRelease
```

The app is set in two bundled faces — Google Sans Flex for Latin and digits, Onest for
Cyrillic — chained so that each draws what it can. Android 8.0 and 9.0 cannot express a
custom chain and are set in Onest alone.

With no keystore configured, the release build is signed with the debug key: it installs
on a phone, but it must not be published. How to plug in your own key and how to tell the app where the server is, is in
[docs/build.md](docs/build.md).

On first run the app walks through four screens and asks for a class code — the one the
bot hands out with `/code`. A class can be switched to a mode where the class code lets
nobody in and a phone joins with a personal one-time code from the bot (the «📱 Подключить
телефон» button); the field for the code is the same one either way —
[docs/bot.md](docs/bot.md#who-lets-a-phone-in).

### A phone that does more than read

By default the app reads. Linking it to Telegram («Настройки → Класс → Telegram», a code,
`/link <code>` to the bot) makes it an editor exactly as far as you are an editor
yourself: the rights are checked on the server against the account's role **at the moment
of the request**, so there is no second permission system on the phone and nothing to go
stale. An administrator gets the class management page in the same place.

## Documentation

The index is [docs/README.md](docs/README.md). In short:

* [docs/guide.md](docs/guide.md) — how to use it: first run, widget, notifications, bot, diary
* [docs/architecture.md](docs/architecture.md) — how it is all put together and why exactly so
* [docs/api.md](docs/api.md) — the `/api/v1` contract, including the Petersburg diary
* [docs/bot.md](docs/bot.md) — roles, invitations by phone number, editing the timetable
* [docs/widget.md](docs/widget.md) — sizes, states, the update schedule
* [docs/build.md](docs/build.md) — building the APK, signing, releases, pointing it at a server
* [docs/deploy.md](docs/deploy.md) — Vercel plus Neon, or your own server
* [docs/design.md](docs/design.md) — the design system and the reasoning behind the interface

For anybody about to write code: [CONTRIBUTING.md](CONTRIBUTING.md) and
[CLAUDE.md](CLAUDE.md) — the commands, the boundaries, and what will bite anybody who does
not know them.

## Honest status

Read this before planning a release.

**What is checked automatically.** On a clean clone, in CI and locally:

| Check | Result |
| --- | --- |
| `ruff check app tests scripts migrations` | clean |
| `python -m mypy` | clean, 84 modules — asks whether anything reaches for an attribute that does not exist |
| `pytest -q -n auto` | 1630 tests, green, about four minutes — the command CI runs |
| `./gradlew test` | 968 tests, green, all five modules |
| `./gradlew assembleDebug` | the APK builds |
| `./gradlew assembleRelease` | the APK builds; R8 and resource shrinking pass |

The release build is checked in the same run as the debug one: R8 and resource shrinking
are the classic source of "it worked in debug and broke in the APK", and catching that on
a pull request is cheaper than catching it on a release.

The server was checked live: brought up under uvicorn, answering `join` and `bundle`,
handing back substitutions, events, homework and `next_school_day`. The state engine was
walked through all 1440 minutes of a school day and through all eleven Russian time zones.

**What nothing checks.** There is no `androidTest` directory in this project: not one test
has run on a device or an emulator. Twenty of the app's screens, sheets and rows — the class
list, the join mode, the connection errors, the first-run reveal, the crash-report sheet,
the calendar's header, its two sheets, its year picker and its day list, and the rest — are
composed in JVM tests under Robolectric, with a Russian locale and a phone's width, and
those are real presses and real rotations on real strings; the design system's components
are exercised the same way. Nobody has pressed the
rest of the interface or the widget: compilation proves that the types line up and says
nothing about what happens on the screen. Covered by nothing:

* the widget drawn at each of its twelve sizes;
* the accuracy of the `TickCadence` alarms in real Doze;
* the layout of the other screens, the dark theme, dynamic colours;
* the behaviour when the network drops and on the first sync;
* runtime crashes the compiler cannot see.

Material 3 Expressive and Glance 1.3.0-alpha02 are alphas. They compile, but nobody has
measured how they behave across Android versions.

The electronic diary in the bot has never once been opened against the real
dnevnik2.petersburgedu.ru — neither the sign-in page nor any of the four screens. The
tests run a hand-written stub in place of the diary: that is the only honest way to check
an integration with an undocumented service, but it says nothing about what that service
actually answers. No browser has ever opened the sign-in page.

**Week parity was computed from the ISO week number, and that broke once every five or six
years.** In an ISO year with 53 weeks, week 53 and week 1 stand next to each other and are
both odd: Monday 28 December 2026 and Monday 4 January 2027 both came out as numerator,
and from January the whole denominator of the current school year slid by a week — in the
API's output, in the widget, in the digests and in the calendar all at once, with nothing
anywhere saying so. Weeks are now counted from the start of the school year: the count
cannot drift, because it alternates by itself. The first week of the year keeps its
previous parity, so no class had its numerator and denominator swapped — in 2024/25,
2025/26, 2027/28 and 2031/32 both rules agree day for day, and they differ only inside the
two years the old one got wrong.

**The UIDs in the calendar were positional.** A subscriber holds `homework-…-1..3`;
deleting the first assignment shifted the remaining two into somebody else's UIDs, so for
everybody subscribed two entries quietly turned into different subjects — including ones
already ticked off — and the third disappeared. A UID is now a row's identifier.

**The notification chain could die for good.** `SchoolAlerts.fire` publishes first and
arms the next alarm second, and the publishing is not protected all the way through: the
channel, the locale and the declension all throw freely. One exception and `armNext` never
ran, the receiver only wrote to the log, and there were no more notifications until a
sync, a reboot or the app being opened. Each publication is now wrapped separately. The
widget had the same thing next door: its inexact alarms were set through `setWindow`,
which Doze holds until the next maintenance window, and the exact alarm for a bell is
armed by the previous tick — a phone face down since seven in the morning meant 8:30 was
never armed at all. `SchoolAlerts` refuses `setWindow` for exactly this reason; so does
the widget now.

**A digest could be sent twice.** The tick selects everything that is due and marks each
row as it gets to it — so a second tick that starts while the first is still running
selects the same rows and sends them again. Marking before sending protects against a tick
that dies halfway, which is a different failure; both are here, because `reminders.yml`
cuts `curl` off on a timeout while the serverless call it started keeps running. The right
to send is now granted by a conditional `UPDATE`: the database decides, and exactly one
gets it.

**A "shortened lessons" day with no bell schedule of its own** showed the usual times: the
resolver fell back to the class's default schedule. That is worse than not marking the day
at all — people read the mark and plan around it. The bot always asks such a day which
bells to use; the API could fail to ask, and now refuses.

**Exporting a timetable did not escape commas.** «Иностранный язык, второй» was written
out as it stood and read back on import as the subject «Иностранный язык» in the room
«второй» — nothing was rejected, nothing was logged, the row simply became two other
things. The module promises in its own documentation that a paste from an export survives
an import unchanged, and it did not. Now the last field takes the remainder (a teacher
«Иванов И.И., к.п.н.» is written with no syntax at all), and a subject or room containing
a comma is quoted. Verified by a round trip over 144 combinations of subject, room,
teacher and parity.

**A lesson numbered past the last bell was accepted and shown nowhere.** The resolver
builds a day out of the bell schedule's rows, so such a row was stored, counted in
"lessons added", and drawn neither in the bot, nor in the app, nor in the widget, nor in
the digest. `MAX_INDEX` was the constant 20 and had nothing to do with the bells, even
though the comment above it admitted that "the bells only go as far as their own rows do
anyway". The ceiling is now however many bells the class actually rings: the editor
refuses and says to add a bell under «🔔 Звонки», the import does not write such rows and
lists them in its answer. If the paste carries its own `== Звонки ==` block, the lessons
are checked against it, so a ninth bell and a ninth lesson legitimately arrive in one
message.

**Going through that same knot found seven more defects, and they are fixed.** The
uniqueness check on a subject compared names case-sensitively while the lookup did not:
«ФИЗИКА» walked straight past it, and the healing read then rewrote every «Физика» lesson
onto whichever row happened to survive. Deleting a subject was undone by the very next
read. Homework and substitutions were written past the dictionary, so «алгебра» from a
phone created a second assignment next to «Алгебра» and both went into the evening digest.
Changing a class's number from 9 to 10 made the first request from any phone delete the
terms whose dates had been edited by hand. Seeding the dictionary on the read path could
drop `/bundle` into a 500 when thirty phones polled a class with an empty dictionary at
once. The day number in the editor's callback was not checked, and `day=0` saved a lesson
no phone can see. All seven are closed by tests; none of it was checked on a live class.

The link between subjects and the timetable is checked only by tests and on one live
class — its database held 35 lessons under 20 names and an empty subject list, exactly the
case this was written for. What the coloured timetable looks like on a phone afterwards,
nobody has seen.

The schools registry has never once been asked of a live DaData: there is no key
(`DADATA_TOKEN`) in this environment, and every test — the server's and the phone's — runs
a stub in place of the registry. Something else was verified: with no key, the search
answers `503` with the words «введите название вручную», and a class is created as before.
What the registry actually answers to «гимназия 3 Казань», nobody has checked.

**A flag that meant nothing.** The class card in the bot had a «🔓 Открыть класс / 🔒
Закрыть класс» button and a «Тип класса: публичный / закрытый» line. **Not one** code path
read the flag behind them: an administrator who "closed" a class closed nothing, and the
screen claimed otherwise. It was removed rather than renamed, and what it was pretending
to be is now done by the join mode (`join_mode`): either the class code lets in anybody who
types it, or it lets in nobody and a phone joins with a personal one-time code from the
bot. The `classes.is_public` column stayed in the database — dropping a column is not
additive — and nothing reads it any more.

**Revision `0010` nearly repeated the very outage the section on migration order was
written for.** The `join_mode` column had a default of `open`, while SQLAlchemy stores an
enum by the member's **name**, not its value: every class would have held `open` in the
database, and the first ORM read of a class would have raised `LookupError` — which for
the bot is a read in the middleware, that is, every update at once. Caught before it was
applied; the model and the revision now say `OPEN`, and three tests hold it: one reads the
column with raw SQL, the second inserts a row **without** the column and reads it back
through the ORM — checking the default specifically — and the third reads the revision file
and demands `server_default="OPEN"` in it.

The bot's coloured buttons (`app/bot/button_style.py`) are checked only by tests: they
assert which style sits on which button and say nothing about how it looks. Nobody has
opened a live client with those keyboards, and on a client older than Bot API 9.3 a button
is drawn plain — the colour carries no meaning that is not in the caption anyway, but it is
worth checking with your eyes on the first run.

**What was known and is now fixed.** A review of already-merged code found six defects in
the interface and the widget, and they sat on the list for several releases. Five are
closed: the theme-change circle now waits for the theme it is showing; the system-bar icons
wait for the end of the wave; the sheet's morph plays; a wide widget no longer hides the
homework a narrow one shows; and at `NoData` the widget stays silent instead of printing
statements made out of absent data. The sixth — taking a screenshot on the main thread —
turned out to be a limitation rather than a defect: `View.draw` has to run on the UI
thread. The details and the reasons are in
[docs/design.md](docs/design.md#what-of-this-list-is-already-fixed).

None of the five has been checked on a screen. All of them are about what the eye sees,
and there is no `androidTest` in this project, so what is proved here is only that the size
ladder's logic became monotonic over real sizes rather than over rungs.

**The app crashed wherever a line of text was drawn, and had done since the marquee
arrived.** A crash reported as «вылет через несколько секунд после привязки Telegram» had
nothing to do with linking: the component that scrolls a line instead of cutting it — drawn
at two dozen call sites, every group row, every lesson row, every heading, the toolbar, the
pickers — could not answer a question Material asks its own rows, and took the process down
on the main thread. It is fixed, a test holds the rule, and the reasoning is in
[docs/design.md](docs/design.md#nothing-is-cut-off-and-nothing-subcomposes-to-find-out-how-wide-it-is).
Two things belong here rather than there. **Which Material
component asks was never identified**, so this is a fix at the leaf rather than at the
caller. And it is the one crash a real phone has reported in this project: it was in a
component that compiles, draws and is covered by tests — which is what the rest of this
section keeps saying, and what an hour with an APK would have said sooner.

**Two buttons were in no APK this repository ever built.** «Войти через GitHub» is drawn
only when the build passes a client id, and the APK workflow passed none — so signing in,
filing a bug report from inside the app and sending a translation correction as a pull
request were all absent from every build, with nothing failing and nothing warning, because
an unset property is an empty string. The same for the contact address behind «Отправить
письмом». The wire is held by a test now, and each build reports on its summary whether the
two are on; nothing proves a secret is actually set, so until one is registered the honest
answer there is «off».

**What the nine-area audit leaves uncovered.** Six commits closed defects across the
server, the sync layer, the calendar, the widget and the self-hosted deployment, and every
one of them is proved by a test rather than by a screen.

* **None of the widget's fixes has been seen on a launcher.** The three defects behind them
  were read off one build on a phone, which is the only pixel evidence in this section; the
  fixes themselves — the height divided by the font scale, the two weights that stop a room
  number starving the subject, the week strip's gaps — are held by tests that reproduce the
  arithmetic, not the rendering. The two claims about what Glance does with a modifier chain
  come from reading its translator.
* **The server badge on Настройки → О приложении has never been drawn against a real
  server.** It reads `GET /api/v1/warmup` and tells «Сервер на связи», «Сервер: база
  и код разошлись», «Сервер не отвечает» and «Адрес сервера не задан» apart in
  Robolectric, against a fake. Which of the four a phone shows when pointed at production,
  nobody has watched.
* **`/api/v1/warmup` itself has not been read since #61**, when it answered
  `{"status":"ok","api_version":1,"schema":"0013"}`. Production is at `0014` now, so it
  should say so, and that one request is the cheapest check of whether the migration and
  the code that needs it actually met — but the deployment previews sit behind Vercel's
  protection, and nobody has made the check against production either.
* **`docker compose up` has not been run.** There is no Docker in this environment: the
  compose file was parsed and its dependency conditions asserted. The `migrate` service and
  the revisions now in the image are written and never watched coming up.

**What does not exist at all.**

* Attachments to homework. The `attachment_url` field is in the schema; there is no upload.
* A link between a class and a registry record. A school can now be found by search — in
  the bot and in the app — but only its name lands on the class: no OGRN, no link to the
  record is stored, so a school renamed in the registry passes the class unnoticed.
* The widget stays on the system font: Glance passes `fontFamily` as the name of a system
  family rather than as a resource.

**What to do first.** Download the APK from the CI artifacts or build it yourself, install
it on a phone, add the widget and live with it for one school day. After that the only
questions left are about runtime and layout, and those are invisible from anywhere except
a real screen.

## Licence

MIT — see [LICENSE](LICENSE).

The design system and some of the components were carried over from
[sameerasw/essentials](https://github.com/sameerasw/essentials) (MIT, © sameerasw.com);
what exactly was taken and what was done differently is in
[docs/design.md](docs/design.md).

The **Google Sans Flex** typeface has its own licence and its own rights holder: SIL Open
Font License 1.1, © 2015 Google LLC. The licence text travels with the typeface, inside the
APK (`core/designsystem/src/main/assets/licenses/google_sans_flex_OFL.txt`), as OFL
requires, and is named in the app: **Настройки → О приложении → Лицензии**.
