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
├── api/           read-only client endpoints
├── bot/           aiogram routers, roles, keyboards, renderers
└── main.py        FastAPI app; its lifespan owns the bot's polling task
```

`schedule.py` is deliberately free of framework imports. It takes ORM rows and
returns plain dataclasses, which is why it is covered by fifteen tests that run
in two seconds with no HTTP client involved.

### The resolution model

The weekly timetable is a **template**. Nothing that happens on one date ever
mutates it:

| Concept | Table | Scope |
| --- | --- | --- |
| Weekly template | `timetable_entries` | weekday + lesson number + week parity |
| Bell times | `bell_schedules` / `bell_periods` | named sets, e.g. "Обычное", "Сокращённое" |
| Замена / отмена | `lesson_overrides` | one date, one lesson number |
| Holiday, shortened day | `day_overrides` | one date |
| Event | `day_events` | one date, a time range |
| Homework | `homework` | the date it is **due** |

`ScheduleResolver` loads a whole date range in a fixed number of queries and then
resolves each day in memory, so asking for two weeks is not fourteen round trips.

### Time

Every time is stored as **naive local wall time** for the school. A bell rings at
08:30 whether or not the clocks changed last night. Storing UTC would make that
statement false twice a year for no benefit.

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
run in milliseconds with no emulator and no Android SDK. Its fourteen tests walk
a full school day minute by minute.

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

After a successful sync, `SyncWorker` sends a package-internal broadcast
(`com.lumenpearson.lessons.action.DATA_SYNCED`) that the widget receiver listens
for. That is why `:core:data` does not depend on `:widget` — the dependency would
otherwise be circular.

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

## Testing

| Suite | What it covers | Runs where |
| --- | --- | --- |
| `server/tests/test_schedule.py` | template expansion, замены, cancellations, holidays, shortened bells, week parity, next-school-day lookahead | pytest |
| `server/tests/test_api.py` | join, bundle, auth failures, revoked tokens, parameter validation | pytest + httpx ASGI |
| `server/tests/test_roles.py` | the permission ladder, phone normalisation, invite claiming | pytest |
| `android/core/model/.../ScheduleEngineTest.kt` | every `DayState`, boundary conditions, event precedence, next-transition scheduling | JVM JUnit |

The Android UI and the Glance widget have no automated coverage yet. See the
"Known gaps" section of the README — that is the honest status, not an oversight.
