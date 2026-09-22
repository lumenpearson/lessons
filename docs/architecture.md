# Architecture

Three deliverables, one repository:

```
lessons/
├── server/     FastAPI read API + aiogram admin bot, one process, one database
├── android/    Kotlin / Compose app, five Gradle modules
└── docs/
```

## Why the bot is the backend

The requirement was "admin panel in Telegram". Rather than build a web panel and
bolt a bot onto it, the bot writes directly to the database and the HTTP API only
reads. That collapses a whole tier: no admin auth, no session cookies, no CSRF,
no password reset, no second deployment unit. Telegram already knows who someone
is, and the phone-invite flow uses that identity as proof.

The cost is that anything the bot cannot express does not exist. That is a real
constraint and it is the reason the timetable editor takes a pasted block of text
instead of a grid.

## The server

```
app/
├── models.py      SQLAlchemy 2.0 ORM — the whole domain in one file
├── schedule.py    template + overrides -> concrete days   (no FastAPI, no aiogram)
├── schemas.py     the wire contract
├── security.py    tokens, join codes, phone normalisation
├── di.py          the container both shells take a session from
├── api/           read-only client endpoints
├── bot/           aiogram routers, roles, keyboards, renderers
└── main.py        FastAPI app; its lifespan owns the bot's polling task
```

### One container, two shells

A session used to be made in three places: a FastAPI dependency for an
endpoint, `SessionLocal()` inside the bot's middleware, and `session_scope()`
for the cron tick — three answers to one question, each with its own view of
whether the caller or the maker commits. `app/di.py` is a
[dishka](https://github.com/reagento/dishka) container holding what has a
lifetime: the settings and the session factory for as long as the process runs,
and one `AsyncSession` for as long as one HTTP request or one Telegram update.

Dishka rather than FastAPI's own `Depends` for one reason: `Depends` cannot
serve a bot handler, and this project is deliberately two thin shells over one
implementation. An endpoint writes `session: FromDishka[AsyncSession]`; the
bot's `ContextMiddleware` opens the update's scope and takes the session from
the same provider. Same provider, one definition.

It opens that scope itself rather than calling `setup_dishka`, and the reason
is written where the decision is: that helper registers a single middleware on
*every* observer, so a message opened two scopes that were siblings on the root
container rather than parent and child — one session per update only while
nothing asked at update level. It also reads the process container per update
instead of capturing one, because `api/telegram.py` caches the dispatcher for
the life of the process and a container closed at shutdown would otherwise go
on serving every webhook.

What it deliberately does *not* hold is written out at the top of the module:
the engine, which is built at import so that an unusable `DATABASE_URL` fails
at the door rather than on the first query, and the two providers' HTTP
clients, which are already process-wide singletons closed in the lifespan.

Two things to know before adding to it. The container is built with
`STRICT_VALIDATION`, so declaring a second provider for a type that already has
one is an error rather than a silent shadowing — a test that rigs a session
writes `override=True` and says why. And `app/api/routing.py` exists because
every module here uses postponed annotations: dishka's wrapper is compiled and
carries its own globals, so FastAPI could not resolve an endpoint's `->
Response` and took the name for a response model. The route class resolves the
annotation before the wrapping.

`schedule.py` is deliberately free of framework imports. It takes ORM rows and
returns plain dataclasses, which is why its twenty-two tests run in two seconds
with no HTTP client involved.

### The resolution model

The weekly timetable is a **template**. Nothing that happens on one date ever
mutates it:

| Concept | Table | Scope |
| --- | --- | --- |
| Weekly template | `timetable_entries` | weekday + lesson number + week parity |
| Bell times | `bell_schedules` / `bell_periods` | named sets, e.g. "Обычное", "Сокращённое" |
| Substitution or cancellation («замена») | `lesson_overrides` | one date, one lesson number |
| Holiday, shortened day | `day_overrides` | one date |
| Event | `day_events` | one date, a time range |
| Homework | `homework` | the date it is **due** |

`ScheduleResolver` loads a whole date range in a fixed number of queries and then
resolves each day in memory, so asking for two weeks is not fourteen round trips.

### Time

Every time is stored as **naive local wall time** for the school. A bell rings at
08:30 whether or not the clocks changed last night. Storing UTC would make that
statement false twice a year for no benefit.

Which wall clock, though, is a property of the **class**, not of the server.
The project targets schools across Russia, which is eleven zones wide, so one
deployment routinely holds a Kaliningrad class and a Kamchatka class ten hours
apart. `SchoolClass.timezone` carries it, `app/timezones.py` is the list the bot
offers, and every "what is today" decision on both sides goes through it —
`school_class.tz` on the server, `Timetable.nowAtSchool()` on the client.

## The Android app

Five Gradle modules, split along the lines that actually pay for themselves:

```
:core:model          pure JVM Kotlin — domain types + the state engine
:core:designsystem   theme + Compose components
:core:data           Room, Retrofit, repositories, sync
:widget              Glance home-screen widget
:app                 screens, navigation, ViewModels
```

`:core:model` being a plain JVM library is the load-bearing decision: the state
engine is the most logic-dense part of the product, and this makes its test suite
run in milliseconds with no emulator and no Android SDK. Its 117 tests, in ten
classes, walk a full school day minute by minute.

The widget module exists so the home-screen widget can reach the cached timetable
without dragging the app's entire UI graph into its process.

### Data flow

```
Telegram bot ──writes──▶ Postgres/SQLite ──/api/v1/bundle──▶ Room ──▶ UI
                                                              │
                                                              └──▶ Glance widget
```

Room is the single source of truth. The network only fills it. Every screen and
the widget read from Room, so the app is fully usable offline and the widget
keeps counting down in a dead zone.

**One device, several classes.** A join code buys one read-only token for one
class, so a pupil in two classes is a phone holding two tokens — that was always
the server's model, and the app used to throw the previous token away. It now
keeps a list of memberships and shows one at a time; `SessionRepository` owns
the list and which one is current.

The cache follows: `school_day` carries a `class_id` and every read is filtered
by the class on screen, so two windows sit side by side and switching is instant
and works with no network. That filter is the load-bearing part. Two classes'
weeks are both plausible school weeks, so a read that lost it would draw a
timetable that looks entirely correct and belongs to somebody else — which is
why the DAO takes the class id as a required parameter on every query rather
than defaulting it, and why a sync writes under the class the **server**
resolved the token to rather than the one the device believes is current.

**One class, several school years.** A window is a school year — 1 September, or
the Monday after, to 31 May — and `synced_window` holds one row per year the
phone has actually fetched, for each class. A sync replaces the rows of *that*
year and leaves the others standing, which is what lets the calendar be
scrolled into next September at all: while the cache held exactly one window
and a sync wiped the class, arriving in another year would have destroyed the
one being left.

`synced_window` is a table rather than something counted off the days present,
because **a year fetched and genuinely empty has to be tellable from one never
fetched**. A class made in March has no rows before it either way; counting
would report its first autumn as «ещё не загружено» for ever, and the calendar
would sit on a spinner that never stops. The screens read `syncedYears` to
choose between «нет уроков», «загружаю год» and «год не загружен».

Three years are kept per class. What goes is the year *furthest* from the one
holding today, never that year itself, and the `ETag` of a dropped year goes
with its rows — a tag kept for data the phone no longer holds is a `304` over
nothing. Fetch time was the first rule tried and it was wrong twice: a year
revisited and unchanged answers `304`, which touches the class row rather than
the window's, so it was never «least recently used»; and it ties, so years
fetched inside one millisecond left the order to the query.

Two rules are easy to break. Only the sync of the year holding today writes the
lookahead row — it answers «what is the next school day» from *now*, and a
fetch of 2029 rewriting it points the widget at a Monday three years out. And
the ranged deletes exclude that row, because its date falls inside some other
window by construction and replacing that year would sweep it up.

**Nothing writes that row today, and the year-wide window is why.** The server
resolves `next_school_day` at most `MAX_LOOKAHEAD_DAYS = 21` days past the last
lesson *inside the window it was asked for*; the phone asks for 1 September to
31 May, and a term cannot be typed past the year's end, so those three weeks
are all out of season and the field comes back `null` on every bundle this app
receives. What answers «what is next» across a gap instead is
`TimetableDao.firstTeachingDayAfter`, searching the cached year, which a
fortnight-long holiday sits comfortably inside. The residue is the last days of
May: there the cache runs out, and «дальше» has no answer until something has
fetched the next year — the calendar scrolled into it, or 1 June rolling the
current year over. The search is by class rather than by year, so once that
year is in the cache September answers from it like any other date. The write path and the
delete exclusion are kept because `next_school_day` is in every bundle and any
window narrower than the year fills it — making the row *arrive* again would be
a server change (resolve past the window's end rather than past its last
lesson, and look far enough to cross the summer), not a client one.

The server needed nothing for any of this. `/api/v1/bundle` has always taken an
arbitrary `start` and up to `MAX_BUNDLE_DAYS = 280`, and a school year is 274.

After a successful sync, `SyncWorker` sends a package-internal broadcast
(`com.lumenpearson.lessons.action.DATA_SYNCED`) that the widget receiver listens
for. That is why `:core:data` does not depend on `:widget` — the dependency would
otherwise be circular.

### Notifications

Split the same way the widget's state is: `AlertPlanner` in `:core:model` turns a
cached timetable plus the user's preferences plus a clock into "what is due in
this window" and "when is the next thing", with no Android on the classpath and
a test suite that walks a school week in a loop. `:core:data` owns the Android
half — the channels, the sentences, and one self-propelling alarm.

```
alarm fires ──▶ SchoolAlerts.fire
                  ├─ AlertPlanner.due(now ± 90 s)  ──▶ AlertNotifier.post
                  └─ AlertPlanner.next(now + 90 s) ──▶ arm one alarm
```

One alarm at a time, not a repeating one: a school day has half a dozen moments
worth interrupting for and a minute-by-minute alarm is four hundred wake-ups to
find them. It is the same shape as the widget's tick chain, and a separate
receiver from it, because a `BroadcastReceiver` may hand out its `PendingResult`
only once.

Nothing remembers what it has already posted. It does not need to: the planner is
asked for a window around now and then for the next moment strictly after that
window, so posting twice needs the clock to go backwards — and a clock change
re-arms the chain from scratch anyway. The chain is re-armed on boot, on
`MY_PACKAGE_REPLACED`, on a clock or timezone change, after every sync, whenever
the alert preferences change, and once per cold start, because a missed broadcast
should cost one launch rather than a reinstall.

The one alert with no clock behind it is «расписание изменилось» — the timetable has
changed. Each sync fingerprints the next seven days — index, subject, start, room,
cancelled, replaced — and compares that against the previous one. The first sync sets the
baseline and says nothing.

### The guide is data, not code

The documentation inside the app used to be 103 string resources and a Kotlin file naming
which of them made which page. It is now two markdown files in [`docs/app/`](app/) — the
Russian source and its English translation — and a manifest saying which version they are and
which version of the app they describe.

```
docs/app/*.md ──▶ raw.githubusercontent.com ──▶ files/docs/guide.<lang>.md
       │                                                    │
       └────────▶ assets/ (built into the APK) ─────────────┴──▶ DocsMarkdown.parse ──▶ pages
```

Three sources, tried in that order: what was fetched, what is stored, what shipped. What the
phone remembers about a fetched copy is keyed by **language**, because the manifest names one
version for two files while a refresh brings back one of them: under language-less keys,
reading the English guide after a new release recorded that version against a Russian copy
still on the old one, and every later refresh then answered "unchanged" about a file that
never arrived. The bundled copy is why the other two are optional — an install on a train has
a guide, and a fetch that fails changes nothing but the line at the top of every page, which
states the version, the date and the app version it was written for. `docs/app/` is the
module's asset folder rather than a copy of it, so the bytes in the APK are the bytes in the
repository.

Why fetching at all, for a document that ships with the app: a guide is wrong the moment a
screen moves, and the app it describes updates through an APK somebody has to install by
hand. The parser understands a deliberately small subset of Markdown and never throws —
anything that is not a guide parses to no pages, and the repository keeps what it already
had rather than replacing a working guide with an empty screen.

What this gave up is the correction mode: the guide's text is no longer a resource, so it
cannot be long-pressed and corrected like every other string in the app. A correction to the
documentation is a pull request against `docs/app/`, which is the same place the app reads
it from.

## What we took from Essentials, and what we changed

The design language is modelled on
[sameerasw/essentials](https://github.com/sameerasw/essentials): large rounded
cards, grouped sections, dynamic colour, an OLED "pitch black" option. Four
things are done differently:

| Essentials | Here | Why |
| --- | --- | --- |
| One module, 456 Kotlin files | Five modules | Build times, and the widget needs the data layer without the UI |
| `MaterialTheme` | `MaterialExpressiveTheme` + `MotionScheme.expressive()` | The brief asked for M3 Expressive; the reference app does not actually use it |
| DataStore only | Room as the source of truth | A widget that shows a countdown cannot depend on a network call |
| Widget fetches data inside the composable | Data resolved before `provideContent` | The composable stays a pure function of its state, and can be previewed and tested |

Neither project uses Hilt. That is a deliberate choice here rather than an
inherited one: the Glance widget and the WorkManager worker both need
repositories from entry points Hilt does not inject cleanly, and Room already
costs one KSP processor. `Graph` is a small hand-written container that a test
can swap wholesale.

## The service layer, and why the phone does not log in

`server/app/services/` is nineteen modules of pure async functions over a session, and
they exist for exactly one reason: every feature of the product now has two entrances, the
bot and the app. Homework is added by a command in a chat and by a button on a phone; so
are a substitution, an event and a special day. Two implementations of one rule would have
drifted apart inside a month, so there is one rule and the handlers and endpoints are two
thin shells over it — the change log, device linking, personal tasks, ticking homework off,
reminders and their idempotence, the broadcast to subscribers, the calendar feed, the
statistics, the timetable export and import, and granting the role somebody asked for, which
was forty lines of permission rules carried in both shells until it was not.

**A phone has no rights of its own**, and that is the central decision of this layer. The
device gets a six-character code from the server, the person sends it to the bot, and from
that moment the device token is bound to a Telegram account. A class that lets phones in
only by personal invitation (`join_mode`) does not have that step at all: the bot hands the
join code to somebody it already recognises, so the phone is bound at the very moment it
connects. Every write from a phone is checked like this: find the account by the token, ask
for its role **in this class at the moment of the request** (`linking.effective_role`),
compare it against EDITOR. Neither a role nor an expiry is stored on the device, because
there is nothing to store: revoke somebody in the bot and the next press in the app gets a
403. Unlinking a device does not touch the token itself — the app goes on reading the
timetable exactly as it did before it was linked.

The server has no clock of its own: on serverless, nothing runs between requests. So the
digests are sent by an external tick (`/api/v1/cron/tick` every five minutes from a
third-party scheduler), which asks the database "what is due by its own class's clock and
has not been sent yet today". The sent mark is written **before** the send: a tick that
dies halfway does not send a digest twice, and one that is ten minutes late still sends it.
A GitHub Actions schedule is not good enough for this role, and that has been measured:
instead of 288 ticks a day it delivered 6.7. The workflow stayed as a fallback; the details
are in `deploy.md`.

That same tick is the only place where anything is deleted on a timer. Abandoned bot
dialogues, failed-login counters, diary sessions, one-time codes — both for signing into
the diary and for connecting a phone — and silent device tokens all grow between calls, and
there is nowhere else to sweep them: no background task survives a response here. The
lifetimes and the reasoning behind them are in `api.md`, in the section about
`/api/v1/cron/tick`.

## Somebody else's service behind one door

`server/app/providers/petersburg/` is the integration with St Petersburg's electronic
diary. The service is undocumented: its addresses, its parameter names and the shapes of
its answers were established from the open clients that talk to it, and used as a map
rather than copied. Everything else about how this directory is arranged follows from that.

The boundary is drawn in three files and held by them:

| File | Knows |
| --- | --- |
| `client.py` | the addresses, the parameters, the session cookie, the date formats |
| `mapper.py` | what the fields in the answers are called, and what code 30000 means |
| `models.py` | none of the above — these are our own application's models |

Above `models.py`, nobody knows the words `p_educations[]`, `estimate_value_name` or
`X-JWT-Token`. When a field is renamed upstream, `mapper.py` is what gets fixed; when an
endpoint moves, `client.py` is. The public API and the Android app do not change, and that
is what the whole arrangement was for.

The mapper is deliberately lenient: a field is looked for under every name it has ever
appeared under, and a row that could not be read is dropped rather than failing the
request. A week of marks with one unreadable entry is more useful than a page with an
error. The strictness is on the way out: a lesson with no date is not a lesson, and it does
not exist.

The leniency extends to shape as well: if `"subject": "Физика"` upstream becomes
`"subject": {"id": 7, "name": "Физика"}`, the scalar is taken out of the object
(`mapper.unwrap`, one level deep and scalars only — going deeper means guessing which of
the nested strings was meant). But when the **whole** batch turns out to be unreadable,
that is not a bad row any more, it is a changed response shape, and a `WARNING` is logged
with the list of keys that arrived: an empty week caused by a renamed field and an empty
week caused by the holidays look identical from above otherwise.

The password is not stored. A login is needed for one request, after which the service's own
session is what lives on, refreshed from its own answers. When it dies, the request answers
`401` with `X-Diary-Reauth: required`, and the app asks for the password again. The price is
that a background sync of the diary does not outlive the session; the price of the
alternative is every family's password in the database.

## Testing

1630 tests on the server, 929 on Android; `pytest -q -n auto` and `./gradlew test`, both
offline, both in CI. On Android that is `:core:model` 117, `:core:data` 285,
`:core:designsystem` 80, `:widget` 90, `:app` 357.

The table below is the load-bearing part of that rather than the whole of it:

| Suite | What it covers | Runs where |
| --- | --- | --- |
| `server/tests/test_schedule.py` | template expansion, substitutions, cancellations, holidays, shortened bells, week parity, next-school-day lookahead | pytest |
| `server/tests/test_api.py` | join, bundle, auth failures, revoked tokens, parameter validation | pytest + httpx ASGI |
| `server/tests/test_roles.py` | the permission ladder, phone normalisation, invite claiming | pytest |
| `server/tests/test_bot_handlers.py` | timetable and bell parsing, homework upsert, substitution parsing, role-grant guards | pytest |
| `server/tests/test_services.py` | task grammar, reminder idempotence across zones, ICS folding and escaping, timetable import/export, stats | pytest |
| `server/tests/test_api_extended.py` | ETag and 304, device linking, write endpoints under every role, tasks scoped to their owner, the calendar feed, the cron tick | pytest + httpx ASGI |
| `server/tests/test_bot_views.py` | week and "what next" rendering at fixed times, task grouping, reminder settings | pytest |
| `server/tests/test_diary_mapper.py` | upstream shapes → domain models, the absence code, tolerant field names, unreadable rows dropped | pytest |
| `server/tests/test_diary_api.py` | login, the password never stored, session refresh and expiry, another family's id refused, date formats | pytest + a fake upstream |
| `server/tests/test_timezones.py` | all eleven Russian zones, ordering, bad-input fallback | pytest |
| `server/tests/test_bot_message_limits.py` | that no renderer builds a message Telegram refuses at 4096 characters | pytest |
| `server/tests/test_schema_version.py` | `EXPECTED_REVISION` equals the real Alembic head, and there is exactly one head | pytest |
| `android/core/model/.../ScheduleEngineTest.kt` | every `DayState`, boundary conditions, event precedence, next-transition scheduling | JVM JUnit |
| `android/widget/.../WidgetSizeClassTest.kt` | the launcher's nearest-breakpoint rule over real sizes, and that the ladder is monotonic | JVM JUnit |
| `android/app/.../ResourceTranslationTest.kt` | every Russian string has an English twin, in every module that ships strings | JVM JUnit |
| `server/tests/test_vercel_entry.py` | that `api/index.py` re-exports the very app the server runs, and can find it from where Vercel starts it | pytest + a subprocess |
| `server/tests/test_scripts.py` | that both one-shot scripts refuse a database that is not a local file, and say truthfully where they are about to write | pytest |
| `server/tests/test_announcements.py` | that every place pushing to the class is bounded, measured at the call site rather than on the helper | pytest |
| `android/core/model/.../StabilityPromiseTest.kt` | that nothing in the domain module is a `var`, which is what `compose-stability.conf` promises the Compose compiler | JVM JUnit |

What nothing covers is a device: there is no `androidTest` directory, so not one test has
run on hardware or an emulator. Twenty of the app's screens, sheets and rows are composed
under Robolectric with a Russian locale and a phone's width — among them the class list, the
join mode, the connection errors, the first-run reveal, the crash-report sheet, the two
sheets the calendar reopens after a rotation, its header and its day list — and the design
system's components are exercised the same way; for the rest of the interface, compilation
proves the types line up and says nothing about the screen. The "Honest status" section of the README keeps the full
list — that is the honest status, not an oversight.
