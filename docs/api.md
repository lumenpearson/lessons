# Client API

Version `1`. Base path `/api/v1`. Every change since the first release is
additive - new endpoints, new optional fields - so the version has not moved
and a client built against the original `/bundle` keeps working unchanged.

There is no user account and no password. A device holds a bearer token; a
device that has been **linked** to a Telegram account through the bot acts
with whatever role that account has in the class. Everything a device can do
without linking is read-only.

## Authentication

A device exchanges a class **join code** for a long-lived bearer token once, at
first launch.

```
POST /api/v1/join
Content-Type: application/json

{ "code": "DEMO24", "device_name": "Pixel 8" }
```

```json
{
  "token": "s6mZ...43-char-url-safe-string",
  "class_id": 1,
  "class_name": "9А",
  "school": "Демо-школа",
  "timezone": "Europe/Moscow"
}
```

The token is stored server-side only as a SHA-256 hash, so a database leak does
not yield working credentials. Every later request carries it:

```
Authorization: Bearer <token>
```

Codes are eight characters from an alphabet with `O`, `0`, `I` and `1` removed,
because people type them off a whiteboard. Rotating a class code (owner-only,
`/code` in the bot) stops the old code from being redeemed again; devices that
already joined keep working, since their tokens are independent of it.

Failed joins are rate-limited per client address (thirty per fifteen minutes,
counted in the database so the limit survives serverless cold starts); a
blocked client gets `429` with `Retry-After`.

### Who lets a phone in: the class code or the bot

A class has a join mode — `join_mode`, either `open` or `invite` — and it decides what the
class code is worth.

`open` is what a class always was: the class code lets in anybody who types it. That is
sensible exactly as far as handing the timetable to all comers is sensible, and entirely
unsensible for a class that does not see it that way: a code read aloud and forwarded is
worth as much as the least careful person holding it, and rotating it throws everybody out
at once rather than the one it leaked through.

`invite` means the class code stops letting anything in, and `POST /join` answers it with a
`403` carrying a Russian sentence in `detail`. A phone joins with a **personal** code: ten
characters, fifteen minutes, one phone, one Telegram account. The bot hands it out only to
somebody it already knows as a member of the class — the «📱 Подключить телефон» button in
the menu — so the question "who let this phone in" always has an answer with a name on it.

A personal code is typed into the same `code` field and goes to the same `POST /join`: for
the app that is one screen and one error, not two ways to sign in. It works in `open` too —
the modes do not exclude each other — and it **links the device immediately** to the account
that took it. In `open` a phone joins anonymously and is linked afterwards with a second
code from the bot, which almost nobody does, and the device list fills up with rows nobody
can attribute.

That `403` is **not counted** as a failed attempt by the rate limiter. The limiter stands
against guessing codes, and this caller has already found one; counting it would mean that a
class switching to invitations locks out everybody still holding the old code.

**Switching takes nothing away.** `invite` does not revoke a phone that joined with the
class code, exactly as rotating the code does not — and switching back gives the class code
its force again. It is changed by `PATCH /api/v1/manage/class` with the `join_mode` field
(admin) or by a button under «👥 Доступ» in the bot; both write an `access.join_mode` line
to the log.

| Status | Meaning |
| --- | --- |
| `401` | Missing, malformed, unknown or revoked token — **the client must drop its session**, not merely report it |
| `403` | The device is not linked, or its account lacks the role (`detail` says which). On `/join` it is the one refusal below that is not about the code being wrong: the class takes personal invites only |
| `404` | Join code, class, homework, task or event does not exist - or is not this class's |
| `422` | Parameter out of range or body invalid |
| `429` | Too many failed join attempts |

A `404` for somebody else's task is deliberate: an id must not reveal that a
classmate has a list.

## `GET /api/v1/bundle`

One request returns everything the app and the widget need.

| Parameter | Default | Notes |
| --- | --- | --- |
| `start` | today, in the school's timezone | `YYYY-MM-DD`, 2000–2100 |
| `days` | `14` | 1–280 — a whole school year, see below |

The ceiling of 280 days is the longest school year (274 days, from 1 September or the next
weekday after it through to 31 May) plus some slack. The old ceiling of 31 days was not a
limit of the server but the size of the client's cache: the app asked for a month, and past
that boundary the calendar drew «Нет данных» — which on a screen is indistinguishable from
"there are no lessons that day". Widening it costs almost nothing: `ScheduleResolver` makes
the same number of queries for any range and expands the rest from the weekly template in
memory. Measured on the demo class: 6.0 KiB for 31 days against 47.9 KiB for 273.

A class carries its own number and the periods of its school year. The number is a number
rather than something parsed out of a name: «9А» may turn out to be «9 инж» or «5-й Б», and
a regular expression over that is quietly wrong for the one class written differently.
The periods arrive as rows rather than as a formula, because the dates are the school's
business: the holidays shift, a region goes on its spring break earlier, a quarantine eats a
week.

**They also decide which days carry lessons at all.** A day inside none of the class's
periods is answered as `holiday` with an empty `lessons`, whatever the weekly template
says — so a half-year ending on 28 May ends the lessons on 28 May, and the gap between two
quarters is the holidays in it. The conventional periods are contiguous, so a class that
has never edited them behaves exactly as before, and a class with no periods for that year
falls back to «1 September to 31 May». This is what the dates were always for; until
recently they named the period and nothing more, and the calendar went on drawing a full
day through to 31 May.

```json
{
  "api_version": 1,
  "school_class": {
    "id": 1, "name": "9А", "grade": 9, "letter": "А",
    "school": "Демо-школа", "city": "Санкт-Петербург", "timezone": "Europe/Moscow",
    "term_kind": "quarter",
    "terms": [
      { "index": 1, "kind": "quarter", "starts_on": "2026-09-01", "ends_on": "2026-10-31" }
    ]
  },
  "generated_at": "2026-09-09T11:15:38.433+03:00",
  "days": [
    {
      "date": "2026-09-10",
      "weekday": 4,
      "kind": "normal",
      "off_reason": null,
      "holiday": null,
      "lessons": [
        {
          "index": 2,
          "subject": "Астрономия",
          "starts_at": "09:25:00",
          "ends_at": "10:10:00",
          "room": "305",
          "teacher": null,
          "color": null,
          "is_replaced": true,
          "is_cancelled": false,
          "note": "Замена: учитель на конференции"
        }
      ],
      "events": [
        {
          "title": "Обед",
          "kind": "canteen",
          "starts_at": "11:10:00",
          "ends_at": "11:25:00",
          "location": "Столовая",
          "covers_lesson": false
        }
      ],
      "homework": [
        { "subject": "Геометрия", "text": "№ 12–15", "attachment_url": null }
      ],
      "note": null
    }
  ],
  "next_school_day": { "date": "2026-09-16", "...": "same shape as a day" },
  "device": { "linked": true, "role": "editor", "can_edit": true }
}
```

### Why one big response

The widget has to render a correct countdown with no network, on a device that
may be in a dead zone all day. So the server resolves the weekly template,
per-date replacements, holidays, bell schedules and week parity into concrete
days, and the client caches the result. The client never sees the template.

`next_school_day` is resolved by looking **past** the requested window, up to
three weeks ahead. That is what makes "homework for Monday" work when you ask on
a Friday for a single day, and what makes it survive the winter holidays.

Those three weeks are counted from the last lesson *inside* the window, so a
window that already runs to the end of the school year gets `null`: everything
within three weeks of the last day of May is June, and June is out of season for
every class — a term cannot be typed past the year's end. The Android client
asks for exactly that window, so it never receives one; it answers the same
question from its own cache instead. A client that asks for a fortnight is the
one this field is for.

### Conditional requests

The response carries an `ETag`. Send it back as `If-None-Match` and an
unchanged bundle answers `304` with no body. The widget polls on a timer and
nearly every poll finds nothing new, so nearly every poll should cost a hash
comparison rather than two weeks of JSON over mobile data.

The validator is a hash of the body with `generated_at` blanked out - that
field changes on every request and would otherwise defeat the whole point.
Weak validators (`W/"..."`) and comma-separated lists are accepted.

The Android client does this: it keeps the last tag in its preferences under a
*signature* of the request it belongs to (class, `start`, `days`), so 1
September and a class switch simply stop matching and the next sync asks for
the whole window. A `304` writes nothing to Room and does not wake the widget —
nothing it draws has changed — but it **does** move the «обновлено N назад»
mark, because that is a claim about the check rather than about the payload.

### Field notes

* Times are local wall time (`HH:MM:SS`) in `school_class.timezone`. A bell rings
  at 08:30 regardless of daylight saving, so there is deliberately no UTC offset.
* `school_class.timezone` belongs to the **class**, not to the deployment. Russia
  spans eleven zones, so one server routinely hosts classes ten hours apart, and
  `start` defaulting to "today" is resolved in the class's zone. Clients must
  derive "now" from this field rather than from the device clock.
* `kind` on a day is one of `normal`, `holiday`, `shortened`, `remote`, `self_study`
  or `day_off`. **Two of them empty the day** (`schedule.KINDS_WITHOUT_LESSONS`):
  `holiday` is «nobody is at school» and `day_off` is «we are not» — a difference in
  what the label means to a reader, not in what happens, and only the first of them
  ever behaved. A class given «🌿 Отгул» for a Monday got the label printed and then
  all six lessons listed underneath it, on the day card, on the phone, in the widget,
  in the calendar feed and in a morning digest that was therefore not silent either.
  `remote` and `self_study` keep their lessons on purpose: remote teaching is the
  same lessons at the same times somewhere else, and set work is plausibly «these
  lessons, but at home» — dropping them would answer a question nobody asked.
  `PUT /api/v1/days` still accepts only the first four; the other two are marked in
  the bot.
* **Out of season a day carries no lessons.** The weekly template is not repeated
  outside the class's own terms — over June, July and August, over the days before
  a year's first teaching day, and over the gaps an admin left between two terms —
  so those days come back with an empty `lessons` array and, unless somebody marked
  them by hand in which case their kind and note stand, `kind: "holiday"`. A
  statutory non-working day is the same shape. Events and homework are returned
  either way: an excursion in June is a real thing, and it is the lessons that are
  out of season, not the day.
* **`off_reason` says which of those it is**, because `kind` cannot: all four
  arrive as `holiday` and a calendar wants to draw them differently. It is
  `out_of_year`, `between_terms`, `public_holiday`, or absent. Absent on an
  ordinary day, including an ordinary empty one — «nobody put lessons on a Sunday»
  is not a reason, it is the absence of one. A client that has never heard of a
  value it is sent should treat it as absent rather than guess.
* **`holiday` names the date**, whether or not it teaches: `{"code", "title",
  "stops_lessons"}`. `stops_lessons` is true only for a statutory non-working day
  — «День учителя» is a full Wednesday with a badge on it, «День Победы» is not.
  `title` is Russian, like every other string this server puts on a screen;
  `code` is stable, so a client can write the name in its own language where it
  knows the date and fall back to `title` where it does not. The list is in
  `server/app/services/holidays.py`, and the yearly transfers are deliberately not
  in it: those are set by decree each December, and a wrong non-working day removes
  a real day of lessons and looks exactly like a correct one.
* `kind` on an event is one of `event`, `canteen`, `exam`, `trip`, `meeting`.
* A cancelled lesson stays in the array with `is_cancelled: true` rather than
  disappearing, so the UI can strike it through instead of silently renumbering
  the day.
* `covers_lesson` tells the client whether the event replaces a lesson or merely
  sits in a break. It is what stops "Обед" from hiding a running lesson.
* `device` describes what this device may do (see below). It is absent or
  `null` for nothing: a client that predates it ignores it. The Telegram id
  the device is linked to is never on the wire.
* Unknown enum values must not crash a client - the Android mappers fall back to
  `normal` / `event`.

## Linking a device

A token on its own reads. To write - tick homework, keep a to-do list, and with
an editor's role change the class's data - the device's owner tells the bot
which phone is theirs.

### `GET /api/v1/me`

```json
{
  "device_name": "Pixel 8",
  "linked": false,
  "role": null,
  "can_edit": false,
  "link_code": "K7PM3X",
  "bot_deep_link": "https://t.me/lessons_bot?start=link_K7PM3X"
}
```

While the device is unlinked the response carries a six-character `link_code`.
The app shows it; the person types `/link K7PM3X` into the bot, or taps
`bot_deep_link`, which opens the bot with the code filled in (`null` when the
deployment has not configured `BOT_USERNAME`). The code is stable across
polls and single-use: once claimed it is cleared, and the next `/me` shows
`linked: true`, the account's `role` in the class (`viewer`, `editor`,
`admin`, `owner`, or `null` for an account the bot does not know) and
`can_edit` (role of editor or above).

The role is looked up on every request, never stored on the device. Revoking
someone in the bot revokes their phone in the same instant; there is no
second permission system to keep in step.

### `POST /api/v1/me/unlink`

Back to read-only. Answers `{"linked": false}`; the next `/me` issues a fresh
code. Idempotent.

## Homework

### `GET /api/v1/homework`

| Parameter | Default | Notes |
| --- | --- | --- |
| `from` | today (class zone) | `YYYY-MM-DD` |
| `to` | `from` + 21 days | at most 62 days after `from` |

```json
[
  { "id": 17, "due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 12–15", "attachment_url": null, "done": true }
]
```

Ordered by date, then subject. `done` is **this person's** tick - thirty
pupils marking the same assignment each see their own - and is always `false`
for an unlinked device.

### `POST /api/v1/homework/{id}/done`

Body `{"done": true}` or `{"done": false}`; answers `{"id": 17, "done": true}`.
Sets rather than toggles, so a retried request lands on the same answer.
Requires a linked device (`403 device is not linked`); `404` for homework
outside this class. The same tick shows up in the bot's «📚 ДЗ» view.

## `GET /api/v1/subjects`

The class's subject dictionary, sorted by name:

```json
[ { "name": "Алгебра", "short_name": null, "teacher": "Иванова А. П.", "color": "#5B6ABF" } ]
```

## `GET /api/v1/now`

What is happening at this moment, computed on the server in the class's zone.
The widget normally works this out from the bundle itself; the endpoint is for
clients that would rather ask, and it is what the bundle's own arithmetic is
checked against.

```json
{
  "date": "2026-09-07",
  "time": "08:40:30",
  "state": "lesson",
  "current": { "index": 1, "subject": "Алгебра", "starts_at": "08:30:00", "ends_at": "09:15:00", "...": "LessonOut" },
  "next": { "index": 2, "subject": "Физика", "...": "LessonOut" },
  "until_next_seconds": 2070,
  "next_school_day": "2026-09-14"
}
```

| `state` | Meaning |
| --- | --- |
| `before_school` | Today has lessons and the first has not started; `next` is that lesson |
| `lesson` | `current` is running; `next` is the one after it, or `null` for the last |
| `break` | Between two lessons; `next` is the one about to start |
| `after_school` | The last lesson has ended |
| `day_off` | No lessons today (weekend, holiday, a fully cancelled day) |
| `no_data` | The class has no timetable at all |

`until_next_seconds` counts to the next boundary: the end of `current` during
a lesson, the start of `next` before school and in a break, `null` otherwise.
Cancelled lessons are skipped - a cancelled first lesson means the day starts
with the second. Events never change the state: a canteen break sits *in* a
break, and the countdown follows the bells. `next_school_day` is the first day
with lessons strictly after today.

## Personal tasks

A to-do list per person: «купить тетрадь», «сдать реферат до пятницы». The
same list the bot shows under «✅ Мои дела». All five endpoints require a
linked device and are scoped to the linked account; a task belonging to
anybody else is a `404`.

```
GET    /api/v1/tasks?include_done=false
POST   /api/v1/tasks                  → 201
PATCH  /api/v1/tasks/{id}
DELETE /api/v1/tasks/{id}             → 204
POST   /api/v1/tasks/{id}/done        {"done": true}
```

A task:

```json
{
  "id": 3,
  "title": "Купить тетрадь",
  "notes": "в клетку",
  "subject_name": "Алгебра",
  "due_date": "2026-09-15",
  "due_time": "18:00:00",
  "priority": 2,
  "done": false,
  "done_at": null,
  "homework_id": null,
  "remind_at": "2026-09-15T08:00:00",
  "created_at": "2026-09-12T10:41:07",
  "updated_at": "2026-09-12T10:41:07"
}
```

`POST` takes `title` (required, up to 200 characters) and any of the others.
`priority` is `0` (low), `1` (normal, the default) or `2` (high).
`homework_id` may point at a homework row of this class - a task created from
an assignment - and is `422` otherwise. `remind_at` is class wall time; an aware
timestamp (`...Z`, `...+03:00`) is converted, a naive one is taken as is. The
bot sends one message at that moment and clears the field.

`PATCH` changes only the fields present in the body; `null` clears a nullable
one, so absent and `null` are different things. `done` may be set here too.
Lists come back undone first, then by deadline with undated tasks last, urgent
first.

## Calendar feed

### `GET /api/v1/calendar`

```json
{ "url": "https://lessons.example.com/api/v1/calendar/hk3G...JqQ.ics" }
```

The class's iCalendar subscription URL, minting the secret on first request
and stable after. The origin is `PUBLIC_BASE_URL` when configured (behind
Vercel the function sees an internal host), otherwise the request's own.

### `GET /api/v1/calendar/{token}.ics`

No bearer - calendar apps cannot send one. The token in the path *is* the
authentication, which is why it is a secret of its own rather than the join
code: a subscription URL ends up in Google Calendar's settings, on a family
laptop and in the odd screenshot, and none of those should be able to mint
device tokens. An admin rotates it from the bot; `404` on an unknown token.

`text/calendar`, `Cache-Control: private, max-age=900`. Sixty days ahead: one
`VEVENT` per lesson (cancelled ones omitted) and per event, one `VTODO` per
homework with `DUE;VALUE=DATE`. Times are `DTSTART;TZID=<zone>` local wall
time with no `VTIMEZONE` block - every calendar that matters resolves IANA
names itself. UIDs derive from (class, date, slot), so a re-fetch sees the
same lesson as the same entry and a new substitution replaces it rather than
appearing twice. The feed is the class's and shared, so nobody's personal
tasks are in it.

## Writing class data

For a linked device whose account is an **editor** or above. Each write does
exactly what the matching bot flow does: the same upsert rules, an entry in the
class's audit log («⚙️ Класс → 📜 Журнал») under the linked account, and a
message to every subscriber who asked to hear about that kind of change -
minus the author. Two distinct `403`s: `device is not linked` and
`editor role required`; the app shows a different screen for each.

Dates must fall between 2000 and 2100 (`422` otherwise), like `start` on the
bundle.

### `PUT /api/v1/homework`

```json
{ "due_date": "2026-09-14", "subject": "Алгебра", "text": "№ 12–15", "attachment_url": null }
```

Upsert by (date, subject): one assignment per subject per day, and sending it
again replaces the text. Answers the row in the `GET /homework` shape
(`id`, `due_date`, `subject`, `text`, `attachment_url`, `done`). Subscribers
with «ДЗ» notifications on are told.

### `DELETE /api/v1/homework/{id}`

`{"id": 17, "deleted": true}`; `404` outside this class.

### `PUT /api/v1/overrides`

```json
{ "date": "2026-09-14", "index": 2, "action": "replace", "subject": "Химия", "room": "118", "teacher": null, "note": "учитель на конференции" }
```

One row per (date, lesson number). `action` is `replace` (needs at least one
of `subject`, `room`, `teacher`; an omitted `subject` keeps the template's
and changes the room or teacher), `cancel`, or `clear`, which deletes the row
and puts the lesson back on the timetable. Answers the stored row with the
`action` echoed. Subscribers with «замены и события» on are told.

Every `422` here is about one thing: a row that would be stored, written to the
log, announced to every subscriber — and drawn on no phone, in no widget and in
no calendar feed. There are three of them.

* **On create**, a number the day rings no bell at (`нет звонка для урока №N в
  этот день`): the day view builds its times out of the bell rows. Only on
  create — an existing row at such a number has to stay editable, because
  `clear` is how a class gets out of one.
* **On create and on update**, a `cancel` or a `replace` naming no subject at a
  number the **weekly template** puts nothing on (`отменять нечего`, `замене без
  предмета нечего заменять`): such a row has no subject to inherit and the
  resolver drops it. The question goes to the template rather than to the
  resolved day, because on update that day already contains the row being
  edited — a `replace` that added «Астрономия» to an empty number would
  otherwise vouch for a second write that cleared the subject again.
* **On any date the resolver draws no lessons on at all**: outside the school
  year — with its own sentence for the summer and for a date before the year
  has opened — and on a day already marked «выходной» by hand.

`replace` with a subject at an empty number is fine — that is how a lesson is
*added* to a day — and `clear` is always allowed, because it is how a class gets
out of a row it should not have.

### `PUT /api/v1/events` → `201 {"id": 42}`

```json
{ "date": "2026-09-14", "starts_at": "12:30", "ends_at": "13:00", "title": "Экскурсия", "kind": "trip", "location": "Эрмитаж", "covers_lesson": true }
```

Always creates: events have no natural key, and two «Обед» rows on one day
are two breaks. `ends_at` must be after `starts_at`. `covers_lesson` defaults
the way the bot does - `true` for `event` and `trip`, `false` otherwise.

### `DELETE /api/v1/events/{id}`

`{"id": 42, "deleted": true}`; `404` outside this class.

### `PUT /api/v1/days`

```json
{ "date": "2026-09-14", "kind": "holiday", "note": "День учителя", "bell_schedule_id": null }
```

Marks a whole date. `kind: "normal"` deletes the mark, so a day never carries a
row that says nothing. `bell_schedule_id` (for `shortened` days) must be one
of this class's bell schedules - `422` otherwise, and it must have rows: a day
pointed at an empty schedule draws nothing at all under a card saying
«⏱ Сокращённые уроки». Answers the stored row. Four kinds are accepted here
— `normal`, `holiday`, `shortened`, `remote` — while a day in the bundle can come
back as any of six; `self_study` and `day_off` are marked from the bot only.

## Managing the class

Everything the bot's `/class`, `/subjects`, `/bells`, `/devices`, `/log`,
`/stats`, `/export`, `/import` and access requests do, under
`/api/v1/manage`, so a class admin can run the class from the phone. The bot
is the authority: the same minimum role guards the same operation on both
surfaces, the same rows move when a subject is renamed, and the same line
lands in the audit log. An admin who changes something in the app and then
opens the bot finds the class in the state the app said it was in.

Authorisation is server-side and per request: the bearer token names a device,
the device names the Telegram account it was linked to, and that account's
role in the class *right now* decides. Nothing the client sends about itself
is consulted, and revoking somebody in the bot revokes their phone in the same
instant.

| Method | Path | Role | Does |
| --- | --- | --- | --- |
| `GET` | `/manage/class` | admin | The class card |
| `PATCH` | `/manage/class` | admin | Rename, move its year, re-home, change time zone or join mode |
| `DELETE` | `/manage/class` | owner | Delete the class and everything in it |
| `GET` | `/manage/subjects` | editor | The dictionary, with ids |
| `POST` | `/manage/subjects` | admin | Add a subject → `201` |
| `PATCH` | `/manage/subjects/{id}` | admin | Rename / short name / teacher / colour |
| `DELETE` | `/manage/subjects/{id}` | admin | Remove it; `409` while the timetable uses it |
| `GET` | `/manage/bells` | admin | Every bell schedule, default marked |
| `POST` | `/manage/bells` | admin | New schedule → `201` |
| `PATCH` | `/manage/bells/{id}` | admin | Rename, or make it the class default |
| `PUT` | `/manage/bells/{id}/periods` | admin | Replace its rows |
| `DELETE` | `/manage/bells/{id}` | admin | Remove a schedule nothing uses |
| `GET` | `/manage/timetable` | admin | Export the template as text |
| `POST` | `/manage/timetable/import` | admin | Import the same text |
| `GET` | `/manage/devices` | admin | Linked phones |
| `POST` | `/manage/devices/{id}/revoke` | admin | Switch a phone off |
| `POST` | `/manage/devices/{id}/unlink` | admin | Back to read-only |
| `GET` | `/manage/log` | admin | The audit log, paginated |
| `GET` | `/manage/stats` | editor | The numbers `/stats` shows |
| `GET` | `/manage/requests` | admin | Who is waiting for a role |
| `POST` | `/manage/requests/{id}/approve` | admin | Grant the role |
| `POST` | `/manage/requests/{id}/decline` | admin | Say no |

Refusals are the two the app already knows, with the role named:
`403 device is not linked` and `403 editor|admin|owner role required`. A
`404` is a row that is not this class's - ids are re-scoped by the query that
reads them, so one naming another class's subject finds nothing rather than
editing it. A `409` is a request that is well formed and refused by the
class's own state: a subject name already taken, a bell schedule days still
point at, a device that is not linked.

Every mutation writes one audit line per field changed, under the linked
account, with the same tags the bot writes (`subject.rename`, `bells.edit`,
`class.name`, `timetable.import`, …). Timestamps in responses here are **class
wall time**, like every other clock on this API: an admin in Vladivostok
reading a Moscow server's log should not see yesterday evening against this
morning's change.

Days, substitutions, events and homework are not on this surface. They are the
day-to-day writes and they already live under [writing class
data](#writing-class-data) at the editor's role - `PUT /api/v1/days` is the
API's «🏖 Особые дни».

### The class card

`GET /manage/class`:

```json
{
  "id": 1,
  "name": "9А",
  "school": "Демо-школа",
  "city": "Санкт-Петербург",
  "timezone": "Europe/Moscow",
  "timezone_label": "МСК (UTC+3) · Москва, Санкт-Петербург",
  "join_code": "DEMO24",
  "join_mode": "open",
  "members": 12,
  "devices": 9,
  "pending_requests": 1,
  "bell_schedule_id": 3,
  "calendar_ready": true
}
```

`PATCH /manage/class` takes any of `name`, `grade`, `letter`, `school`, `city`,
`timezone`, `join_mode` and answers the card. Only the fields present change; `null` clears
`school` or `city`. Moving `grade` or `letter` also **recomposes the name** from the two,
because that is where the name came from when the class was created: a class that went from
9 to 10 and stayed called «9А» showed the wrong class on every screen that prints one. A
request that names the class as well wins over both — an admin who typed a name has said
what they want — and the `class.name` audit line is written only when the name actually
changed. `name` and `timezone` may not be null or blank, and
`timezone` must be one of the eleven Russian zones the bot offers (`422 unknown
timezone` otherwise). Changing the zone moves no stored time - a bell rings at
08:30 whatever the zone says - it changes which instant the class calls "now".
`join_mode` is `open` or `invite` and anything else is a `422` naming the field;
what the two mean is under «Кто пускает телефон» above.

`DELETE /manage/class` is the owner's alone and takes the confirmation the bot
asks for:

```json
{ "confirm_name": "9А" }
```

The name must match exactly, or `422`; a sheet in the app is not the check,
because the endpoint is reachable without the sheet. The class, its timetable,
homework, substitutions, events, the log and every device token go with it - including
the caller's own, so the next request from that phone is a `401`. Answers
`{"id": 1, "deleted": true}`.

A client that does not act on that `401` keeps a cached timetable of a class
that no longer exists, and — because the wipe happens on the way *in* to a new
class — carries it into the next one. The Android app drops its token and its
cache on any `401` from this API family, on the delete it made itself and on
one made from the bot alike; see `TokenRejectedTest`.

### Subjects

The dictionary is what keeps «Алгебра», «алгебра» and «Алг.» from being three
subjects in the timetable, the homework and the app's colours. `GET` answers
`[{ "id": 4, "name": "Алгебра", "short_name": "Алг", "teacher": "Иванова А. П.", "color": "#5B6ABF" }]`,
sorted by name; an editor may read it.

**It is not a list somebody has to keep up by hand.** Every write that names a
subject in the weekly template goes through the dictionary: the entry is found
(ignoring case) or created, the lesson is linked to it by
`timetable_entries.subject_id`, and the *dictionary's* spelling is what gets
stored — so «АЛГЕБРА» in a paste joins the class's «Алгебра» instead of
founding a second subject with its own colour. Reading this list, or the
bundle, adopts anything a class typed before the link existed; that costs a
count once the two agree.

Which is why the dictionary is worth filling in. A colour set here reaches
every lesson of that subject, and a teacher set here is the fallback for every
lesson whose own cell names nobody — both on ordinary lessons and on substitutions.
Before, an empty dictionary meant a timetable with no colours at all, whatever
was typed into the template.

`POST` takes `name` (required) and any of `short_name`, `teacher`, `color`.
`PATCH` takes the same fields, changing only those present; `null` clears
`short_name`, `teacher` and `color`. Colours are written as `#RRGGBB` in any
case, with or without the `#`, and stored upper-case; anything else is `422`.
Both answer:

```json
{ "subject": { "id": 4, "name": "Алгебра и начала анализа", "short_name": null, "teacher": null, "color": null }, "moved": 12 }
```

`moved` is the point of `PATCH name`. The timetable, the homework and the
substitutions store the subject as **text** as well - deliberately, so a lesson keeps
its name when a subject is deleted - so a rename is still a cascade, and all of
it happens in one transaction. `moved` is how many of those rows went with it.
The template is matched on the link *and* on the old spelling: the link is the
half that survives a row whose name drifted, the name is the half that catches
rows written before the link existed. Renaming onto a name the class already uses is `409`: merging
two subjects is a different operation, and doing it by accident cannot be
undone.

`DELETE` is **refused with `409` while the weekly template still uses the
subject**, and the detail says how many lessons do (`"12 lesson(s) still use
this subject"`). It used to succeed and leave the lessons alone — the timetable
keeps the name, so the class kept its timetable and lost only the colour and
the teacher. That stopped meaning anything once the dictionary began keeping
itself: the name is still in the template, so the next read adopts it straight
back, stripped of the colour, the short name and the teacher the deleted row
carried, and the admin was told nothing at all. Two halves of one list are
deleted in one order — out of the timetable, then out of the dictionary. A
subject nothing teaches still deletes in one call, which is what this endpoint
was really for.

Uniqueness ignores case everywhere it is checked — `POST`, `PATCH name` and the
bot — because the matcher does. When it did not, «ФИЗИКА» was allowed in beside
«Физика», and the healing read then saw one subject where the dictionary had
two and rewrote every «Физика» lesson onto whichever row it happened to keep:
a read renaming a subject and dropping the colour of the one it abandoned.

### Bell schedules

A class keeps several - «Обычное», «Сокращённое», «Суббота» - and one of them
is the default the day view uses when nothing says otherwise.

```json
{ "id": 3, "name": "Обычное", "is_default": true, "silenced_lessons": 0,
  "periods": [ { "index": 1, "starts_at": "08:30:00", "ends_at": "09:15:00" } ] }
```

`POST /manage/bells` takes `name` and an optional `periods`; a new schedule is
never made the default, because one is created in order to be pointed at by
particular days. `PATCH` takes `name` and `is_default`; `is_default: false` is
`422` - a class with no default has no times for an ordinary day, so the way
to stop using one is to make another the default.

`PUT /manage/bells/{id}/periods` replaces the rows wholesale, because that is
what editing bells is: move one lesson and every lesson after it shifts.
Between one and twenty rows, `index` 1-20 and unique, `ends_at` after
`starts_at`; an empty list is `422`, the same refusal the bot gives: bells are not
erased by sending nothing.

Both writes answer with `silenced_lessons`: how many weekday rows this request
stopped ringing. Shrinking a schedule, or pointing the class at a shorter one,
leaves every lesson at a number past the new last rung stored in the database
and drawn on no phone, in no widget, in no calendar feed and in no digest —
nothing is deleted and everything is gone. It is counted in rows rather than in
numbers, because one number under two weekdays is two lessons nobody will see.
Zero on a read, on a rename, and on any write that silenced nothing; a client
that ignores it will report a clean save over a class that just lost six
lessons, which is what the bot says out loud in an alert.

`DELETE` refuses the class default and any schedule a special day still points
at (`409`, with the count). Both are a `SET NULL` in the database, which would
silently move those days onto the default schedule - a change nobody asked
for, on dates an admin is not looking at.

### Timetable export and import

`GET /manage/timetable` answers `{ "text": "== Понедельник ==\n1. Алгебра, 214\n…", "lessons": 18 }`
- byte for byte what «📤 Экспорт» sends, so a text saved out of the bot can be
imported by the app and the other way round. An empty timetable is an empty
string, not a `404`.

`POST /manage/timetable/import`:

```json
{ "text": "== Понедельник ==\n1. Химия, 118\n2. Биология\n\n== Звонки ==\n1. 09:00-09:40", "replace": false }
```

```json
{
  "applied": false,
  "days": [1],
  "lessons": 2,
  "bells": 1,
  "conflicts": [ { "weekday": 1, "existing": 3, "incoming": 2 } ],
  "rejected": ["не строка"]
}
```

The bot shows a preview and asks «Применить». An API has no screen to show one
on, so **the preview is the refusal**: if a weekday in the paste already has
lessons, nothing is written and the response says what stands to be
overwritten. Sending it again with `replace: true` is the second tap, and then
`applied` is `true` and `lessons` counts what was written.

Only the weekdays the paste names are touched, so a Tuesday block against a
Monday-only timetable is not a conflict at all, and a day named with nothing
under it is emptied - that is how a paste says «в четверг уроков нет».
`rejected` echoes the lines the parser could not read, so an admin can fix the
two that were typos rather than re-reading the whole paste. It also carries
`"понедельник, урок N: нет такого звонка в расписании звонков"` for every
lesson numbered past the last bell: the day view builds its times out of the
bell rows, so such a lesson would be stored, counted in `lessons` and then
drawn nowhere at all. One line per dropped row, named by its weekday — the
ceiling is class-wide, so the same number under two weekdays is two lessons
gone, and one line for both admitted to one of them.
Those lines are not written, and `lessons` counts only what was. When the same
paste brings a `== Звонки ==` block, the lessons are checked against **those**,
so one request can legitimately add a ninth bell and a ninth lesson together.

A paste with no weekday header and no bells block in it is `422`. A
`== Звонки ==` block replaces the default schedule's rows outright, as it does
in the bot: it is a schedule, not a day, and nothing else points at it.

**Commas inside a field.** The last field takes everything that is left, so a
teacher `Иванов И.И., к.п.н.` needs no syntax. A subject or a room containing a
comma is wrapped in double quotes — `1. "Иностранный язык, второй", 305` — and
that is what the export writes; a doubled `""` inside such a field is one
literal quote. Without this the export produced a line the import read back as
a different subject in a room called «второй», which is the round trip the two
endpoints exist to promise each other.

### Devices

`GET /manage/devices?include_revoked=false`:

```json
[ { "id": 7, "device_name": "Pixel 8", "linked": true, "owner": "@anna", "role": "editor",
    "revoked": false, "created_at": "2026-09-01T18:22:04", "last_seen_at": "2026-09-12T07:55:10",
    "linked_at": "2026-09-01T18:24:31" } ]
```

Oldest first. `role` is a lookup, not a stored field - a device acts with
whatever role its owner holds right now - and `owner` is a display name; the
Telegram id a device is linked to is never on the wire.

`POST /manage/devices/{id}/revoke` switches a phone off: revoked, not deleted,
because the row is what a token is checked against and keeping it is what makes
the refusal instant and permanent. It is idempotent and logs once. An admin may
revoke the phone they are holding, and then the next request from it is a `401`
- that is how a lost phone is dealt with from the one still in a pocket.

`POST /manage/devices/{id}/unlink` puts a phone back to read-only without
taking it off the class: it keeps reading the timetable and loses the role it
borrowed. A device that is not linked is `409`.

### The audit log

`GET /manage/log?limit=30&offset=0` (`limit` 1-100):

```json
{
  "entries": [ { "id": 412, "action": "subject.rename", "summary": "предмет «Алгебра» → «Алгебра и начала анализа», строк обновлено: 12", "who": "@anna", "at": "2026-09-12T14:05:33" } ],
  "limit": 30,
  "offset": 0,
  "has_more": true
}
```

Newest first. `has_more` rather than a total: the log is append-only and
unbounded, and counting it would be a full scan on every page turn. `who` is a
display name, `null` for a line the system wrote. `summary` is plain text -
the bot escapes it at render time, and so must a client that puts it in HTML.

### Stats

`GET /manage/stats`, readable by an editor, because it says whether the
timetable is complete and whether homework is being entered - which is what
the person entering it wants to know.

```json
{
  "today": "2026-09-12",
  "lessons_per_week": 32.5,
  "subjects_count": 14,
  "subjects": [ { "name": "Алгебра", "hours": 4.0 } ],
  "homework_open": 6,
  "homework_total": 141,
  "members_by_role": { "owner": 1, "admin": 2, "editor": 4, "viewer": 5 },
  "devices_active": 9,
  "overrides_upcoming": 3,
  "events_upcoming": 2
}
```

A lesson that alternates weeks counts as half, which is how a school's own
paperwork writes «часов в неделю». `subjects` is ordered by hours, then name.

### Access requests

Someone with a read-only phone asks for a role with `/request` in the bot;
the admins answer here or there.

`GET /manage/requests` lists the pending ones, oldest first:

```json
[ { "id": 5, "who": "@petya", "requested_role": "editor", "message": "я староста", "created_at": "2026-09-11T19:02:44" } ]
```

`POST /manage/requests/{id}/approve` grants it. The body is optional;
`{ "role": "admin" }` grants something other than what was asked for. Answers
`{ "id": 5, "status": "approved", "role": "editor", "who": "@petya" }`.

Two guards, the bot's own: nobody may grant a role at or above their own
(`403 cannot grant a role at or above your own`, and `owner` is granted by the
deployment's `OWNER_IDS` alone, never through this endpoint), and nobody may
change the role of a peer or a senior (`403 cannot change this member's
role`). Without both, an admin could promote a friend to admin and be demoted
by them a moment later.

`POST /manage/requests/{id}/decline` closes it with `status: "declined"` and
`role: null`. Either way the requester is told in Telegram, because that is
where they asked; a decision stands whether or not the message was delivered.
A request that has already been answered is a `404`, so two admins tapping at
once cannot grant twice.

## `GET|POST /api/v1/cron/tick`

Not for clients. A serverless deployment has no scheduler, so the morning and
evening digests and one-off task reminders are sent by whoever calls this
endpoint, every five minutes, with the `X-Cron-Secret` header set to the
deployment's `CRON_SECRET`. That caller is an external cron service;
`.github/workflows/reminders.yml` in this repository asks for the same five
minutes but is only the fallback, for the measured reason in
[deploy.md](deploy.md#the-clock-the-server-has-none-and-githubs-will-not-do).

| Status | Meaning |
| --- | --- |
| `404` | `CRON_SECRET` is unset: the endpoint does not exist, like the webhook without its secret |
| `403` | Wrong or missing header (constant-time comparison) |
| `503` | `BOT_TOKEN` is unset: nothing to send with |
| `200` | `{"morning": 1, "evening": 0, "tasks": 2, "failed": 0, "fsm_purged": 0, "join_attempts_purged": 3, "diary_sessions_purged": 0, "device_tokens_purged": 0, "diary_links_purged": 0, "device_invites_purged": 0, "diary_sessions_kept_alive": 4, "diary_sessions_lost": 1, "diary_keepalive_failed": false}` |

What is due is decided from each class's own clock and from what was already
sent today, never from when the last tick ran - so a tick that runs twice in
a minute or an hour late sends each digest once.

The same tick sweeps the tables that grow between calls — nothing else in this deployment
runs between requests:

| What | When it is deleted | Why |
| --- | --- | --- |
| Abandoned bot dialogues (`fsm_states`) | after two days | `fsm_purged`; the «current class» preference sits in the same table and is exempt, because it is written once and only read afterwards |
| Failed-join counters (`join_attempts`) | after an hour | `join_attempts_purged` |
| Diary sessions (`diary_sessions`) | a day after the service refuses one, 30 days after it was last used | a live token for somebody else's service is inside |
| Device tokens (`device_tokens`) | after 180 days of silence | every `POST /join` creates a row, and reinstalling the app leaves the old one for ever |
| Diary sign-in tickets (`diary_link_codes`) | on expiry | `diary_links_purged` |
| Personal connect codes (`device_invites`) | a day after they expire | `device_invites_purged`; a day rather than immediately, because "my code does not work" is asked within minutes, while who connected a phone is remembered by the device token itself |

180 days is comfortably longer than the summer holidays: a phone silent since the end of May
has to work in September. A device in use is marked no less than once every 15 minutes, on
any read.

The same tick also **keeps «Сетевой город» sessions alive**. That diary idles a session out
in 15–60 minutes and this project stores no password to sign back in with, so the tick pings
each live one with `GET /webapi/context`, within a batch cap and a time budget, and never
touches `last_used_at` — a ping must not look like the family using the session, or the
30-day purge above would never fire. `diary_sessions_kept_alive` is how many were pinged
alive, `diary_sessions_lost` how many the upstream had already dropped (they are expired, and
the app is told to sign in again), and `diary_keepalive_failed` is `true` when the keep-alive
itself raised — isolated so a diary fault never fails the whole tick and reddens the fallback
clock. Petersburg sessions are not pinged; they refresh from their own answers. This depends
on the external cron, not GitHub's fallback clock, which is too sparse to keep a session
alive; [deploy.md](deploy.md#the-clock-the-server-has-none-and-githubs-will-not-do) says why.

## `GET /api/v1/health` and `GET /api/v1/warmup`

Both unauthenticated. `/health` returns `{"status": "ok", "api_version": 1}` and
deliberately opens no database connection at all — a ping meant to keep the function
warm would otherwise keep waking a Neon compute that has scaled to zero. Use it for
container health checks.

`/api/v1/warmup` is the same plus one round trip, which is what actually wakes the
database (see `deploy.md`), and since the connection is open anyway it also reports
whether the schema is the one this code was written against. Three answers:

| | Body | Meaning |
| --- | --- | --- |
| `200` | `{"status": "ok", "api_version": 1, "schema": "0015"}` | the database is reachable and at the revision the code expects |
| `200` | `{"status": "degraded", …, "schema": …, "expected_schema": …, "detail": …}` | both revisions named, and `detail` says **which way** they diverge |
| `503` | `{"status": "down", "api_version": 1, "detail": "База недоступна."}` | the database could not be reached |

The two directions of `degraded` are opposites and the text says so: «База отстала от
кода…» is the outage — a deploy landed before its migration, and the first ORM read of
a column that is not there kills the bot's middleware, i.e. every update at once — while
«База впереди кода…» is the documented procedure caught between its two steps, and it
closes on its own. A third wording covers a database with no `alembic_version` table.

`503` rather than a `500` with a traceback: the service cannot serve and it is not the
caller's fault, which is the thing a monitor already knows how to page on. The driver's
own message is logged rather than answered, because a connection error prints the host,
the user and sometimes the password out of the URL, and this endpoint is unauthenticated.

The app draws all of this as a badge on **Настройки → О приложении** — «Сервер на связи»,
«Сервер: база и код разошлись», «Сервер не отвечает», «Адрес сервера не задан» — which is
the one place in the interface that tells those four apart. See
[design.md](design.md#the-about-footer).

## The electronic diary

A separate surface under `/api/v1/diary`. Separate because it is a different thing:
`/api/v1` hands out the class's timetable, which the bot fills in, while this is one
family's account in a service this project does not run. They have nothing in common but the
server.

There are two such services behind this one surface — «Петербургское образование»
(`dnevnik2.petersburgedu.ru`) and «Сетевой город. Образование», the diary of about twenty
regions — and **the API does not change between them.** Which one a session is with is
decided at sign-in and stored on the session; every endpoint below is provider-neutral, and
the client reads the same shapes whichever diary answered. «Сетевой город» is server-side
only and has never been tried against a live server (see the README's honest status).

**The app never talks to either service directly.** None of an upstream's parameters —
Petersburg's `p_educations[]`, `X-JWT-Token`, `estimate_type_code`, or «Сетевой город»'s `at`
bearer and `scid` — crosses the boundary of our API. If a field is renamed up there tomorrow,
one directory is what gets fixed (`app/providers/petersburg/` or `app/providers/netschool/`),
and neither the app nor this document changes.

### Signing in

```
POST /api/v1/diary/login
{ "login": "parent@example.com", "password": "…" }
```

```json
{ "token": "…", "login": "parent@example.com" }
```

`provider`, `region` and `school_id` are optional. Absent `provider` means Petersburg, so a
phone that sends only a login and a password signs in there exactly as before. For «Сетевой
город», `provider` is `"netschool"`, `region` is a key into the server's own allow-list and
`school_id` the upstream's school id; both are validated in the route before any upstream
call, so an unknown region or a missing school is a `422`, and a region that takes only
Госуслуги — or one whose server refuses this deployment's address — answers `503`, which the
sign-in limiter does not count because nothing there looked at the password. The bot already
binds a class to a region and a school for its own web sign-in form; these fields are for a
phone that will one day sign in to «Сетевой город» directly.

**The password is not stored.** It is needed for exactly one request — the login to
somebody else's service — after which it is forgotten. Only the diary's own session is
stored, and it is refreshed on the fly: the service hands back a fresh token on almost every
answer and we overwrite ours with it, so a session in use does not die of age. When it does
finally stop being accepted, any request answers `401` with an `X-Diary-Reauth: required`
header — which is a request to ask for the password again, not "the token is wrong".

The price of that decision is honest: a background sync of the diary lives no longer than
the session. The alternative — keeping every family's password in the database to save one
sign-in screen every few days — is not worth it.

This session's token is **not** the same as the class device's. A phone can be connected to
a class without a diary and to a diary without a class; one token meaning both would have to
be re-minted whenever either half changed.

Failed sign-ins are rate-limited per client address, ten per fifteen minutes, in a bucket of
their own: ten wrong diary passwords must not spend a phone's thirty `/join` attempts. Past
that the endpoint answers `429` with `Retry-After`, and it is counted in the database, like
the join limiter, because nothing in this deployment survives between requests. What the
limit is for is that this server's address must not become a way of guessing passwords
against somebody else's school diary.

### What can be asked

| Endpoint | Answer |
| --- | --- |
| `GET /api/v1/diary/students` | the children this account can see |
| `GET /api/v1/diary/students/{id}/schedule?from=&to=` | the timetable |
| `GET /api/v1/diary/students/{id}/homework?from=&to=` | the assignments |
| `GET /api/v1/diary/students/{id}/grades?from=&to=` | the marks |
| `GET /api/v1/diary/students/{id}/periods` | the school periods |
| `GET /api/v1/diary/students/{id}/subjects?period_id=` | a period's subjects |
| `GET /api/v1/diary/students/{id}/teachers` | the teachers |
| `GET /api/v1/diary/students/{id}/attendance` | the turnstile passages |
| `GET /api/v1/diary/students/{id}/overrides` | the family's corrections over the diary |
| `PUT /api/v1/diary/students/{id}/overrides` | write a correction |
| `POST /api/v1/diary/students/{id}/overrides/reset` | reset one |
| `DELETE /api/v1/diary/students/{id}/overrides/all` | reset them all |
| `POST /api/v1/diary/logout` | forget the session |

`from` and `to` are ISO dates; the default is two weeks ahead and the maximum is 62 days.
`{id}` is a child's identifier from `/students`, and it is **checked against the list this
account may see**: somebody else's id answers 404 rather than somebody else's data.

### A mark is not always a mark

The service puts marks, absences, late arrivals and remarks into one list and tells them
apart by a numeric code. We tell them apart on the client's behalf:

```json
{ "subject": "Алгебра", "date": "2026-09-11", "value": "Н", "kind": "absence" }
```

`value` is what gets written into the register's cell; `kind` is what it actually is:
`grade`, `absence`, `late`, `remark`, `other`. A client does not need to know that
"was absent" is code 30000.

Homework is not a separate entity in the service: it is a field of a lesson. `/homework`
takes it out and hands it back as a list, because that is how people ask for it.

### Corrections over the diary

The diary is **read-only** for us, and that does not change: nothing goes upstream. A
correction is a value laid **over** what came from above, on the way out, and a reset takes
it off. Nothing changes in dnevnik2, and the teacher does not see the correction.

A lesson and an assignment arrive with a key and a list of corrections:

```json
{
  "date": "2026-09-15", "number": 1, "subject": "Алгебра", "room": "204",
  "target": "lesson:2026-09-15:n1:Алгебра",
  "edits": [
    { "field": "room", "value": "204", "original": "12", "changed_upstream": false }
  ],
  "ambiguous": false
}
```

`target` is **built by the server alone**, and the client hands it back verbatim. Two
implementations of a key that has to match byte for byte agree exactly until the first
lesson with no number.

`original` is what the diary says **now**, not what it said when the correction was written.
`changed_upstream` is raised when those two have diverged: a teacher who has finally filled
the homework in must not end up hidden behind a correction written when it was empty. The
correction itself is not reset — a person wrote it, and the code will not throw it away on
their behalf.

`ambiguous` means that two lessons that day produced one key. That happens when a class is
split into groups: a lesson's number is not unique within a day in the diary, and what tells
such lessons apart is the room and the teacher — that is, exactly the fields being
corrected. The correction is then applied to neither of them, and the client says so.

**Not everything can be corrected.** On a lesson: `homework`, `room`, `teacher`, `topic`; on
an assignment: `text`. Marks and the turnstile are not in that list and will not be: a mark
and a passage are statements about what happened, and an app that lets you rewrite one
produces a forged record that looks official. `subject` is not correctable because it is
half of the key.

Corrections live on the **account's login** rather than on the session: a diary session dies
every few days, and a correction that went with it would disappear by itself, silently,
before anybody pressed "reset".

Resetting one correction is `POST .../overrides/reset` with a body of
`{"target": …, "field": …}`, not a `DELETE` with the same fields in the query. `target` is a
string glued together with colons, and a subject's name is inside it: «ОБЖ и экология»
breaks on `&`, and a long key gets cut by a proxy's log. A body survives both. The reset is
idempotent: taking off something that was never corrected answers 204 rather than 404 — the
requested state has been reached either way.

`GET /overrides` lists the corrections for the screen that takes them off, and every row has
`original_when_written` — what the diary said **then**. That is deliberately a different
word from the `original` in a lesson's `edits`: that one is what the diary says **now**, that
is, the value the correction is covering right at this moment. The same field is named
differently because it means opposite things, and a client that confused them would show
"before / after" back to front.

**Corrections are not applied in the bot.** The bot draws a day as one block of text with no
button on a lesson, so a correction there would be indistinguishable from what the school
wrote and there would be nothing to take it off with — and that is the surface a parent reads
more often. The bot stays an unaltered mirror of the diary; the corrections live in the app,
where they are visible as corrections and are reversible.

### Error codes

| Code | What happened | What the app should do |
| --- | --- | --- |
| `401` | our session's token did not fit | ask them to sign in |
| `401` + `X-Diary-Reauth: required` | the diary's session expired | ask for the password again |
| `404` | this account has no such child | — |
| `422` | on reads, the date range is inverted or wider than 62 days; on `PUT .../overrides`, the correction was refused: an unknown `target`, an uncorrectable field, or an empty value where empty is not allowed | on a read, fix the range; on a correction, show `detail` |
| `429` | on `/diary/login`, ten failed sign-ins from this address inside fifteen minutes | wait out `Retry-After`, and do not blame the password |
| `502` | the diary answered incomprehensibly | say that the service has changed |
| `503` | the diary is not answering | offer to retry |

One code for two different things is the price of both the range and the correction being
checked as a request body. What tells them apart is the **call**, not the answer: there is
nothing in the body that separates one from the other, so the client decides by which
request it made (`android/core/data/.../DiaryModels.kt` does exactly that).

`502` is the only code that means we are the ones who have to fix something: the service is
undocumented, and when its answer stops being readable, this is how it shows.


## Quarters and half-years

| Method | Path | Role | What it does |
| --- | --- | --- | --- |
| `GET` | `/api/v1/manage/terms` | admin | the class's periods; the first call creates the usual set |
| `PUT` | `/api/v1/manage/terms/scheme` | admin | `{"kind": "quarter"\|"semester"}` — recreates the year on another scheme |
| `PUT` | `/api/v1/manage/terms/{index}` | admin | `{"starts_on", "ends_on"}` — moves one period's edges |

The default scheme follows the class's number: years 1–9 are taught in четверти (quarters),
10–11 in полугодия (half-years). The choice is stored separately from the number, so
"nobody decided" and "quarters were chosen for year eleven" are different states, and a
school is entitled to choose the second.

Changing the scheme **recreates** the year's periods rather than editing them: four quarters
and two half-years do not lie on top of each other, and a leftover third quarter inside a
year made of two halves is not a state worth keeping.

Editing the edges answers `422` with a Russian sentence in `detail` when a period ends before
it starts, runs outside the school year or overlaps its neighbour. A sentence rather than a
per-field error, because what is broken is the relation between this period and the year or
its neighbours, and «Пересекается с периодом 2 (01.11 — 31.12)» is what is worth putting on
the screen.

## The schools registry

| Method | Path | Role | What it does |
| --- | --- | --- | --- |
| `GET` | `/api/v1/manage/schools?q=&page=&page_size=&region=` | admin | searches the register for a school and returns one page |

```json
{
  "items": [
    {
      "name": "МБОУ \"Гимназия № 3\"",
      "full_name": "МУНИЦИПАЛЬНОЕ БЮДЖЕТНОЕ ОБЩЕОБРАЗОВАТЕЛЬНОЕ УЧРЕЖДЕНИЕ \"ГИМНАЗИЯ № 3\"",
      "ogrn": "1027800000001",
      "inn": "7801234567",
      "address": "190000, г Санкт-Петербург, ул Восстания, д 8",
      "city": "Санкт-Петербург",
      "region": "г Санкт-Петербург",
      "active": true
    }
  ],
  "page": 1,
  "pages": 4,
  "total": 20,
  "truncated": true
}
```

What is found is **not stored**. The client takes `name` and sends it to
`PATCH /api/v1/manage/class` — the class card holds the school's name, as it always did,
rather than a link to somebody's record.

`truncated` does not mean "there are more pages" — `pages` counts those. It means "the
register's ceiling was hit": the source (DaData, a search over the ЕГРЮЛ company register) is
built for type-ahead suggestions, returns at most twenty rows at a time, and has no offset.
So what you have is the first twenty of an unknown number, and the only way to reach your
school is a longer query — adding the town or the number. A client that shows "20 found"
would lie about a search that matched three hundred schools.

**Every call is one search upstream**, whatever `page` says: the source has no offset and
there is nothing to resume. A client that pages should ask once with `page_size=20` and cut
the answer itself — which is what both the bot and the app do. Four pages of five is four
searches for one question. `page_size` runs from 1 to 20, and the default is 5 (the size of
an inline keyboard).

`region` is a ranking hint, not a filter: a school across the road from the city boundary is
still that school.

The codes: `422` for a query shorter than three characters (with a Russian sentence in
`detail`); `503` when the registry is not configured (`DADATA_TOKEN` is empty), is not
answering, or answered incomprehensibly. The answer to a `503` is to let the name be typed
by hand rather than retrying the request: in the bot, that is exactly the button that
appears.

Admin, even though it writes nothing: every call spends part of a daily limit on somebody
else's service, and only your own class is reason enough to spend it.
