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

The one alert with no clock behind it is "расписание изменилось": each sync
fingerprints the next seven days — index, subject, start, room, cancelled,
replaced — and compares it against the previous one. The first sync sets the
baseline and says nothing.

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

## Слой сервисов, и почему телефон не логинится

`server/app/services/` — восемь модулей чистых async-функций над сессией, и
существуют они ровно потому, что у каждой функции продукта теперь два входа:
бот и приложение. Домашка добавляется командой в чате и кнопкой на телефоне;
замена, событие, особый день — тоже. Две реализации одного правила разошлись бы
в первый же месяц, поэтому правило одно, а handler'ы и endpoint'ы — две тонкие
оболочки над ним: журнал изменений, привязка устройств, личные задачи, отметки
о сделанной домашке, напоминания и их идемпотентность, рассылка подписчикам,
лента календаря, статистика, экспорт и импорт расписания.

Прав у телефона своих нет — и это главное решение этого слоя. Устройство
получает от сервера шестисимвольный код, человек отправляет его боту, и с этого
момента токен устройства привязан к Telegram-аккаунту. Любая запись с телефона
проверяется так: найти аккаунт по токену, спросить его роль **в этом классе в
момент запроса** (`linking.effective_role`), сравнить с EDITOR. Ни роли, ни
срока действия на устройстве не хранится, потому что хранить нечего: отозвали
человека в боте — следующее нажатие в приложении получает 403. Отвязка
устройства не трогает сам токен: приложение продолжает читать расписание, как
читало до привязки.

У сервера нет своих часов: на serverless между запросами не выполняется ничего.
Поэтому сводки шлёт внешний тик (`/api/v1/cron/tick`, workflow каждые пять
минут), который спрашивает у базы «что созрело по часам своего класса и ещё не
отправлено сегодня». Отметка о отправке ставится **до** отправки: тик, упавший
посередине, не рассылает сводку дважды, а опоздавший на десять минут — досылает.

## Testing

| Suite | What it covers | Runs where |
| --- | --- | --- |
| `server/tests/test_schedule.py` | template expansion, замены, cancellations, holidays, shortened bells, week parity, next-school-day lookahead | pytest |
| `server/tests/test_api.py` | join, bundle, auth failures, revoked tokens, parameter validation | pytest + httpx ASGI |
| `server/tests/test_roles.py` | the permission ladder, phone normalisation, invite claiming | pytest |
| `server/tests/test_bot_handlers.py` | timetable and bell parsing, homework upsert, замена parsing, role-grant guards | pytest |
| `server/tests/test_services.py` | task grammar, reminder idempotence across zones, ICS folding and escaping, timetable import/export, stats | pytest |
| `server/tests/test_api_extended.py` | ETag and 304, device linking, write endpoints under every role, tasks scoped to their owner, the calendar feed, the cron tick | pytest + httpx ASGI |
| `server/tests/test_bot_views.py` | week and "what next" rendering at fixed times, task grouping, reminder settings | pytest |
| `server/tests/test_timezones.py` | all eleven Russian zones, ordering, bad-input fallback | pytest |
| `android/core/model/.../ScheduleEngineTest.kt` | every `DayState`, boundary conditions, event precedence, next-transition scheduling | JVM JUnit |

The Android UI and the Glance widget have no automated coverage yet. See the
"Known gaps" section of the README — that is the honest status, not an oversight.
