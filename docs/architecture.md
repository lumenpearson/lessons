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
├── catalog/       the region catalog — generated data, never edited by hand
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
at the door rather than on the first query, and the provider HTTP clients,
which are already process-wide singletons closed in the lifespan.

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
:core:data           Room (two databases), Retrofit, repositories, sync,
                     and the one client that talks to a diary directly
:widget              Glance home-screen widget
:app                 screens, navigation, ViewModels, the first run
```

`:core:model` being a plain JVM library is the load-bearing decision: the state
engine is the most logic-dense part of the product, and this makes its test suite
run in milliseconds with no emulator and no Android SDK. Its 125 tests, in ten
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
keeps counting down in a dead zone. There are two databases, not one: `lessons.db`,
the class timetable this section is about, and `diary.db`, the family's own diary,
which is filled by a different rule and is described under
[the phone's half](#the-phones-half) of the diary.

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

### Three homes: a class, a diary, or the way in

A phone is in a class, reads its own diary without one, or has come in neither way yet,
and `ShellMode` (`NONE`, `CLASS`, `DIARY`) is that answer, decided by one rule in
`shellModeOf`: a class outranks a diary, so a phone with both lives in the class's app and
keeps the diary in settings. What makes `DIARY` is a stored diary **target** — which diary,
region, school and login — rather than a live session, because a bare `401` drops the bearer
and keeps the target, and a family whose session lapsed belongs on its own diary's sign-in
form rather than back at the start. The same rule is what sends a phone that leaves its last
class while signed in to a diary to the diary home, where the diary and its sign-out are,
instead of to a join screen that could reach neither (#151).

`ShellModeSource` computes the mode, the first run's hold and the sync arming from **one**
read of the preferences file each time, never by combining a class flow with a diary flow:
two flows can disagree for an emission in between, and a gate that flickered to the wrong
home for a frame would reset the state of the one it left. The gate itself is a pure
function, `rootScreen` in `navigation/RootGate.kt`: nothing read yet is the splash, a hold or
`NONE` is the first run, anything else is a home, and `LessonsApp` keys the home on which one
it is, so moving between the class's home and the diary's resets the shell rather than
landing in a section of the old one. `AppShellViewModel` settles a cold start once per
process before the gate first reads: a hold left over in `NONE` means the credential it was
waiting for never landed, and it is dropped.

What each home offers is one rule too, `SettingsSection.listedOn(mode, manager)`: the diary
home drops the sections that are about a class — content, alerts and the class account,
whose requests need a class token this phone does not have — and keeps the rest.

**The periodic class sync is armed in a class and nowhere else** (#152). The application
used to schedule `SyncWorker` on every emission of the settings, whatever was stored, so a
phone that had just left its last class — whose sign-out had cancelled the worker — was woken
every fifteen minutes again from its next launch, for a run that found no class and returned;
a diary-only phone would have been woken that way for ever. `LessonsApplication` now collects
`shellMode.syncArming`, which is `syncArmingFor(mode, interval)`: armed at the interval in
`CLASS`, cancelled in `NONE` and `DIARY`. `SessionEffects` still arms on a join and cancels
on the last sign-out, and agrees with the rule. `SyncArmingTest` holds it, including a source
check that the application collects the arming and not the interval alone.

The widget reads the mode as well — it has a sentence for each
([widget.md](widget.md#when-there-is-nothing-to-draw)) — and never the diary: the reason is
the diary's, under [the phone's half](#the-phones-half).

### The first run holds the screen

The first run is `ui/onboarding/`, and its rules are pure: `OnboardingFlow` turns a state —
the steps walked as a **path**, a floor below which back cannot go, and the answers
collected — into the next state, the dots and the step to draw, with no Compose and no
Android, so every route through it is a JVM test. Why a path and what the dots count is a
design decision and is written down in [design.md](design.md#first-run-is-a-path-not-one-field).

`OnboardingViewModel` is activity-scoped and owns a handful of plain holders, each tested on
its own: `SignInStep`, `ImportRunner`, `RegionFinder` and `SchoolFinder`. What lives where is
the security of the flow, and it is deliberate:

| What | Where it lives | Where it never is |
| --- | --- | --- |
| the path and the answers (region, school, system) | the view model's `SavedStateHandle`, so a recreate and a process death bring the step back | — |
| the login | the sign-in step's memory | the saved state |
| the password | the form's text field, and then one argument to `submit` | any field of any holder, the saved state, the disk |
| the diary's own session, between the diary's answer and ours | a private field of `SignInStep`, behind an opaque `HeldUpstream` | the saved state, the disk, anything the screen can read a token out of |
| our diary bearer, once registered | the preferences store, like the class bearer | — |

The held session exists for one case: our server could not be reached after the diary let the
phone in. Then «Повторить без пароля» registers the same session again, and nobody types the
password twice; any other failure has already discarded it, and leaving the step discards it
upstream. `OnboardingViewModelTest` checks every value in the saved state for the login and
the password, and `OnboardingStepsTest` checks by reflection that the password is never a
field of the sign-in step.

**The hold.** A join and a registration both write their credential before the flow is over —
an import or a summary is still to come — and either write turns the phone from `NONE` into a
class phone or a diary phone at once. So the flow calls `shellMode.hold()` on entering the
class-code or the sign-in step and `release()` in `finish()`, the gate keeps the first run on
screen while the hold is set, and the hold is persisted: after a process death,
`OnboardingFlow.resume` picks up at the import for a registered diary, at the class's diary
sign-in for a joined class whose diary this build can sign in to, and otherwise finishes. The
introduction counts as seen when the flow **reaches the chooser**, and `LessonsApp` latches
that flag on its first composition, so writing it cannot restart the flow under the finger.

Nothing composes `LessonsApp` in a test: the gate, the hold through a real join, the swap to
the right home and the resume after a process death are held by the pure rules and the view
models only.

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

`server/app/services/` is twenty-two modules of pure async functions over a session, and
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

An electronic diary is a family's account in a service this project does not run, does not
document and cannot change. There are two of them now, and the shape they meet is the point
of the whole integration. `server/app/providers/diary/` holds what every provider shares:
the models the rest of the application reads, the error family a route turns into a status
code, the `DiaryProvider` / `DiaryConnection` contract a provider implements, an HTTP client
whose cookie jar keeps nothing (so two families' sessions can never meet), and a `registry`.
`services/diary.py` opens whichever provider a session row names and calls it through that
contract; it never learns which upstream is behind it.

- **`app/providers/petersburg/`** — «Петербургское образование» (`dnevnik2.petersburgedu.ru`),
  the first provider and still the only one that signs in with an email and a password alone.
- **`app/providers/netschool/`** — «Сетевой город. Образование», the diary of about twenty
  regions on one route set (`/webapi`) served from a different regional host in each. Its
  reads are the server's alone — the phone speaks only its sign-in and its school search
  ([the phone's half](#the-phones-half)) — and **it has never met a live server**: every
  upstream shape is read from open-source clients and hand-written payloads, exactly as
  Petersburg's was (#121, #135).

What every region of the country runs, and the routes of each platform as its open clients
call them, is surveyed in [diaries.md](diaries.md); [diaries/netschool.md](diaries/netschool.md)
is the reference page «Сетевой город» was written from.

Petersburg pre-dates the seam and went behind it **byte-for-byte**. Every public name it had
— `PetersburgError`, the mapper functions, `today` — is now a re-export of the object defined
in `diary/`, so `PetersburgError` *is* `DiaryError` and nothing that imported the old names
broke; a thin `petersburg/provider.py` wraps the existing client and mapper behind the
contract. Rewriting the one provider a real account has been promised against, for behaviour
no reader would see, was the change deliberately not made.

`registry.py` answers two questions in one place. `provider_for(key)` maps a stored key to an
implementation, importing the provider module lazily so neither upstream's HTTP client lands
on the cold-start path of a request that does not use it. `binding(school_class)` resolves
what a class is bound to; the bot menu, the class card, the web form and `POST /join`'s
`diary` field all call it, so "is this class bound, and to what" has a single answer, and a
class bound to a «Сетевой город» region since dropped from the allow-list reads as unbound
rather than as a dead name. Its mirror is `services/diary.reads_binding`, the one clause for
"which sessions does this binding read": the bot's lookup filters by it, and binding a class
to another diary or another region expires, in the same commit, the members' sessions it no
longer reads — a session is an account on one regional server, and one left live on the
server the class moved away from would go on being pinged by the keep-alive for nobody
(#145). Another school in the same region keeps them; unbinding expires nothing, because it
takes the door away and the sessions stay their owners' until they sign out.

The boundary each provider draws is three files, and it holds them:

| File | Knows |
| --- | --- |
| `client.py` | the addresses, the parameters, the session token, the date formats |
| `mapper.py` | what the fields in the answers are called, and what code 30000 means |
| the shared `diary/models.py` | none of the above — these are our own application's models |

Above the models, nobody knows the words `p_educations[]`, `X-JWT-Token`, `weekDays` or the
`at` bearer. When a field is renamed upstream, that provider's `mapper.py` is what gets
fixed; when an endpoint moves, its `client.py` is. The public API and the Android app do not
change, and that is what the whole arrangement was for.

**Nothing turns free text into a host.** «Сетевой город» is one route set proxied to a server
chosen by region, so its `regions.py` is an allow-list first and a directory second: a region
key that reaches sign-in — from a client body, a class row or a sealed credential — is looked
up there, and one not in the table is refused before any network call. That is the whole SSRF
guard. The shared client follows no redirect and never turns TLS verification off, so the
session bearer cannot ride a cross-origin 30x to another host.

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

The password is not stored, for either diary. What lives on is the upstream's own session.
Petersburg refreshes it from its own answers and we simply keep the newest; «Сетевой город»
idles a session out in 15–60 minutes, so the cron tick keeps each live one alive with
`GET /webapi/context` (the mechanism is in
[deploy.md](deploy.md#the-clock-the-server-has-none-and-githubs-will-not-do), the counters in
[api.md](api.md)). When a session dies either way, the request answers `401` with
`X-Diary-Reauth: required` and the person signs in again. The price is that nothing reads
the diary once its session is gone — and the phone never reads it in the background at all;
the price of the alternative is every family's password in the database.

### Two ways a session arrives, and only one of them sees the password

A session of ours used to be opened one way: the password came to this server — from the
app's `POST /api/v1/diary/login`, or from the bot's `/diary/signin` page — and the server
signed in. "Not stored" was true; "never seen" was not. So there is now a second door,
`POST /api/v1/diary/session`: a client signs in with the diary itself, straight at the
allow-listed origin, and hands over **the session** the diary gave it, never the password.
The server does not take that session on trust. It reads with it once, from its own address
— Petersburg's pupils, or «Сетевой город»'s four bootstrap calls, which also fill the year
and the type map a sealed session cannot read without — and only then seals it, through the
same `_open_row` a password sign-in goes through, so "what a session holds" has one answer
whichever door it came in by. The read is the whole check: a session the upstream will not
take from this address is worth nothing to keep, and a registration it refuses is a `409`
rather than a request for the password, because the password was never the problem and
asking for it again would loop. Whether either diary does take a session opened in Russia
and replayed from Frankfurt is exactly what has never been seen; the contract, the statuses
and the shared limiter are in [api.md](api.md#the-electronic-diary).

The cost is that the sign-in protocol now lives on both sides — this app speaks it to the
diary, and the server still speaks it for the bot and for older apps — and
two implementations of «Сетевой город»'s salted hash over a windows-1251 password agree
exactly until they do not. The known-answer
vectors in `server/tests/vectors/diary_protocol.json` are the one set of bytes both are
tested against; the Python half is driven over `httpx.MockTransport`, the Kotlin half over
MockWebServer, and #147 and #148 —
a sign-in that sent the word `None` upstream, and malformed answers that escaped as `500` —
were fixed in the same change.

The other doors stay. `/diary/login` is unchanged for the APKs built before registration;
this app stopped calling it in the same batch that made the phone sign in itself. The bot's
page still relays the password — it posts to this server, which passes it to the diary once
and writes it nowhere — and since #150 **it says so**: the page and the two cards before it
used to promise the password went «прямо в дневник», which it never did. A registered
session carries no Telegram account and no class, so the bot does not see it and a family
signed in on the phone signs in again in the bot. Linking the two is deliberately deferred
(#143). It would need the class device token on a `/diary` request — the one place the two
bearer families would meet — and a linked session would inherit the bot's rules: the bot's
«Выйти» would sign the phone out too, deleting the class would take the phone's session with
it, and rebinding the class would expire it. Those are the owner's to weigh first.

### The phone's half

The app signs in to a diary itself and hands our server only the session. That puts part of
the diary on the phone, and it is fenced exactly as the server's is: everything that knows a
diary's routes lives in `core/data/.../upstream/`, and what comes out of it is an opaque
`UpstreamSession` or a failure with a name.

**One client, and it talks to diaries only.** `UpstreamHttp.client` shares nothing with the
client that talks to our server, which rewrites every host to the configured server and signs
requests with our bearers by path; a diary request through that one would go to our server,
or carry our bearer to somebody else's. This one has no cookie jar (the session travels per
request, so two sign-ins cannot meet), follows no redirect (a `302` to another host is the
one way around an allow-list), does not retry a failed connection (`getdata`'s salt is
one-shot, and a silent re-POST reads as a wrong password), and carries exactly two
interceptors: `OriginGuard`, which refuses any origin not on the allow-list before a byte is
sent, and one that refuses an `Authorization` header outright. The failures are classified
once: only a failure that examined the certificate is "untrusted" — a handshake the network
broke is "unavailable", with a retry.

**The bundled catalog is the only source of a diary host.** The app bundles
`server/app/catalog/data/regions.json` in place as an asset, never a copy, and
`CatalogUpstreamDirectory` builds the allow-list from it, dropping any origin that is not
`https` or carries a path, a query, a fragment or credentials. Nothing typed and nothing
received ever becomes a host: a region is picked by its key and the key finds an origin; the
one piece of user input that reaches a diary, a school search, travels as a query parameter.
The same file is the phone's region search — names in both languages, aliases, cities, codes,
the wrong keyboard layout and transliteration, all read from the catalog's `search` block
([diaries.md](diaries.md#the-region-catalog)) so that no Russian word has to be written in
Kotlin. The ports are Kotlin twins of the server's: Petersburg's sign-in, «Сетевой город»'s
salted hash with its own windows-1251 table (carried in the code rather than trusted to the
device's ICU), the server's login cleaning (`DiaryLogin.clean`, character for character, so a
login pasted with an invisible character is not a wrong password on one side only), and a
JSON reader that refuses what Python's `json.loads` refuses. `DiaryProtocolVectorsTest` runs
every case of `server/tests/vectors/diary_protocol.json` through them, read in place from
the test classpath and over real sockets where the case is a request, so the two
implementations cannot drift apart silently. `upstream/UpstreamMarkers.kt` is the one Kotlin
file allowed Cyrillic: the diaries' own words, matched in their answers.

**Registration.** `DiarySignIn` is four steps, and the first run's sign-in step drives them
one at a time. *Preflight* checks the catalog row — region known, password sign-in, school
present — and then asks `GET /api/v1/diary/capabilities`, before the password field means
anything. *Open* cleans the login, refuses one too short and an empty password without a
request, and signs in at the diary. *Register* refuses a session too old to hand over and one
the server's schema would refuse, posts it as `credential` to `POST /api/v1/diary/session`,
and keeps our bearer and a `DiaryTarget` — provider, region, school, login and the diary's
zone, one JSON key in the preferences. A different account than the stored one empties the
phone's copy of the diary and the chosen pupil **before** the new session is written.
*Discard* signs a «Сетевой город» session that will not be registered out upstream
(Petersburg has no logout outside a browser); a session is spent once, by a registration or
a discard. A bare `401` later clears our bearer only and keeps the
target, which is what keeps the phone on its diary's sign-in form; signing out forgets both.
Every failure on the way is a `DiarySignInProblem`, and every diary screen turns one into
words through one mapping, `ui/diary/DiaryProblemText.kt` — too many attempts, a switched-off
diary, a diary that refuses our server's address and a timeout each have their own, naming
the host where there is one, and «Повторить» is offered only where a retry can help (#153).
Our diary bearer is kept off `/diary/login`, `/diary/session` and `/diary/capabilities`, and
the class bearer off `/api/v1/directory/` and `/api/v1/diary/`, each held by a test.

**`diary.db` keeps what was read, and has one writer.** It is a second Room database with an
exported schema: pupils, the terms, lessons, homework and marks, and a row per pupil per week
whose stamps tell "fetched and empty" from "never fetched" — the same distinction
`synced_window` draws for the class. Nothing writes it but `DiaryRepositoryImpl`'s own
successful reads (`remembered`): the pupils, the terms, any window of marks, and a timetable
or homework range of whole Monday-to-Sunday weeks, each written through as it arrives; a
write that fails costs the copy, never the answer. **A generation guards it:** a sign-out's
`clear()` bumps the generation under the same lock every write takes, a read takes the
generation before its request, and a read that was in flight across a sign-out or another
account's sign-in is dropped rather than written back after the clear. The registration's
own list of pupils is written by `SeedingDiarySignIn`, so the import need not ask again. The
database sits under the backup rules' existing exclusion of every database.

**The import** fills it once, after the first registration: the pupils, the choice of pupil
(asked only when the account has several and none was chosen before), the terms, this week's
and next week's timetable, the homework and the marks of the current term — sequential, with
a weight per phase for the progress bar (rough costs, not measured ones). The pupils and the
timetable stop it; the terms, the homework and the marks are skipped and named, and a session
the diary ended stops it with a way back through the sign-in. It resumes from the phase that
failed, reading the earlier ones from the cache, and "today" is taken in the diary's zone,
not the phone's.

**The only diary read nobody pressed for is `refreshIfStale`**, and it runs in one place: the
diary home's `LifecycleStartEffect`, each time the app comes to the foreground. When the
current week's copy is older than thirty minutes it re-reads this week's and next week's
timetable and homework; while an import holds the lock it does nothing. It goes through
`DiaryViewModel.refreshOnStart`, so the screen's own load waits for it rather than reading
the same week a second time. **There is no background diary read at all** — not in the
worker, not in the widget, not in the alerts, not in any application class, receiver or
service a manifest declares — and `SyncWorkerSourceTest` scans all of those and fails on a
reference to the diary's repository, cache or import. The reason is the keep-alive: a
«Сетевой город» session is kept open for up to thirty days after the family last used it, and
a read from the background would count as use with nobody there. The screens read the saved
copy first, skip the network while it is fresh, and keep it on the screen, saying when it was
saved, when the diary or the network cannot be reached.

**The school search is two lookups.** «Which region is this school in» asks our server's
anonymous directory, `GET /api/v1/directory/school-regions`, and only for a query that looks
like a school's name and is not too common to place, so the day's allowance is spent on
questions it can answer. «Which school in this region» asks the region's own diary server
from the phone, like the sign-in.

**Not verified.** None of this has met a live diary or run on a device. Whether a diary
accepts a session opened on a phone when our server in Frankfurt replays it is open — the
`409` is the designed failure. TLS against the regional servers (the tests run plain HTTP,
and the certificate failures are the shapes Conscrypt and the JVM are known to throw), Room's
invalidation of `diary.db` on a device, and a `Retry-After` given as a date (read as no wait)
are unverified too; the device's own windows-1251 is never asked, on purpose.

### The region catalog is generated, not written

«Which region is this family in, and what can it do there» has one answer, and it is the
survey's. `server/app/catalog/data/regions.json` is generated from
[diaries/regions.md](diaries/regions.md), a hand-written overlay and the server's own
allow-list by `python -m scripts.region_catalog`, and committed; a test fails when the
committed file and its inputs disagree. Generated, because the alternative was prose parsed
at run time, or a second hand-kept list of eighty-nine regions that would disagree with the
survey within a month; committed, because the server reads it at run time and the app
bundles the same file, and neither should run a generator to start. The decisions it carries — which systems are a sign-in,
which a hand-off to the browser, why there is no Госуслуги sign-in anywhere — are written
down beside the survey, in [diaries.md](diaries.md#the-region-catalog). The server reads four
things from it: a region's key, its subject code, its names and DaData's spellings of it,
which is what placing a school from the company register needs; `app/catalog/` loads it on
first use rather than at import, so no other cold start pays for parsing it.

### A daily allowance, counted in the database

The school directory has two doors onto one DaData key and its ten thousand requests a day:
the bot and `/manage/schools`, behind a class admin, and
`GET /api/v1/directory/school-regions`, which a phone asks **anonymously** before it belongs
to anything. Without a cap on the second, anybody with a loop could spend the day and leave an
admin creating a class with «Лимит запросов исчерпан». So the anonymous door gets a fixed
share, 4,000, and stops there; the admins' side is not metered at all.

The count is `usage_counters` (revision `0016`), one row per allowance per day, and not the
obvious alternatives. Not memory, for the reason every limiter here gives: on Vercel each
concurrent invocation is its own process. Not `join_attempts`, although the per-caller
throttle lives there: that table is pruned to fifteen minutes on every recorded attempt, and a
day's count kept in it would silently become a quarter of an hour's. The spend is a single
`INSERT … ON CONFLICT … DO UPDATE … WHERE used + n <= cap RETURNING`, so two invocations
reaching for the last unit cannot both have it — there is no read-then-write to race through
(checked on SQLite; on Postgres by construction, not by a test). A unit is spent **before** the
request it pays for, and the empty-answer retry asks for its own unit first, because DaData
counts a request whatever comes of it.

The day is Moscow's (`services/quota.py:QUOTA_ZONE`), not UTC and not the server's. DaData is a
Moscow company and its allowance is assumed to turn at Moscow midnight — assumed, because
nothing in its documentation says which clock it counts by. If the assumption is wrong and it
counts in UTC, the worst case is two anonymous days inside one of DaData's, 8,000, which still
leaves the admins two thousand. The server's zone was never a candidate, because it moves
with the host.

## Testing

1944 tests on the server, 1371 on Android; `pytest -q -n auto` and `./gradlew test`, both
offline, both in CI. On Android that is `:core:model` 125, `:core:data` 532,
`:core:designsystem` 99, `:widget` 102, `:app` 513.

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
| `server/tests/test_diary_session.py` | registering a session a client opened: both body shapes, a `password` key refused, no refused value echoed, every status and which of them the shared limiter counts | pytest + a fake upstream |
| `server/tests/test_diary_protocol_vectors.py` | both diaries' sign-in protocols against the known-answer vectors in `tests/vectors/diary_protocol.json`, the bytes the phone's port is tested against too | pytest + `httpx.MockTransport` |
| `server/tests/test_region_catalog.py` | that the committed catalog is what the generator writes, and that only Петербург and the sixteen password «Сетевой город» regions are a sign-in | pytest |
| `server/tests/test_directory.py`, `test_quota.py` | the anonymous school directory's order of refusals, the per-caller throttle, the daily share and its atomic spend, and `0016`'s DDL against the model | pytest |
| `android/core/model/.../ScheduleEngineTest.kt` | every `DayState`, boundary conditions, event precedence, next-transition scheduling | JVM JUnit |
| `android/widget/.../WidgetSizeClassTest.kt` | the launcher's nearest-breakpoint rule over real sizes, and that the ladder is monotonic | JVM JUnit |
| `android/app/.../ResourceTranslationTest.kt` | every Russian string has an English twin, in every module that ships strings | JVM JUnit |
| `server/tests/test_vercel_entry.py` | that `api/index.py` re-exports the very app the server runs, and can find it from where Vercel starts it | pytest + a subprocess |
| `server/tests/test_scripts.py` | that both one-shot scripts refuse a database that is not a local file, and say truthfully where they are about to write | pytest |
| `server/tests/test_announcements.py` | that every place pushing to the class is bounded, measured at the call site rather than on the helper | pytest |
| `android/core/model/.../StabilityPromiseTest.kt` | that nothing in the domain module is a `var`, which is what `compose-stability.conf` promises the Compose compiler | JVM JUnit |
| `android/core/data/.../upstream/DiaryProtocolVectorsTest.kt` | the phone's sign-in ports against the same known-answer vectors as the server's, over MockWebServer | JVM JUnit |
| `android/core/data/.../diary/DiaryCacheTest.kt`, `DiaryImportTest.kt` | `diary.db`'s write-through, the generation guard across a sign-out, the import's phases, resume and zone, and `refreshIfStale` | JVM JUnit |
| `android/core/data/.../sync/SyncWorkerSourceTest.kt` | that the worker, the widget, the alerts and every declared application, receiver and service never name the diary | JVM JUnit, reading the sources |
| `android/app/.../RootGateTest.kt`, `SyncArmingTest.kt` | which home each mode opens on, and that the class sync is armed in a class only | JVM JUnit |
| `android/app/.../ui/onboarding/OnboardingFlowTest.kt`, `OnboardingViewModelTest.kt` | the first run's routes, dots and resume, and that the saved state never carries a credential | JVM JUnit |

What nothing covers is a device: there is no `androidTest` directory, so not one test has
run on hardware or an emulator. About two dozen of the app's screens, sheets and rows are
composed under Robolectric with a Russian locale and a phone's width — among them the class
list, the join mode, the connection errors, the first-run reveal and its legal line, the
diary home, the crash-report sheet, the two sheets the calendar reopens after a rotation, its
header and its day list — and the design
system's components are exercised the same way; for the rest of the interface, compilation
proves the types line up and says nothing about the screen. The "Honest status" section of the README keeps the full
list — that is the honest status, not an oversight.
