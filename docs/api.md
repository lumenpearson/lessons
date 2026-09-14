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

| Status | Meaning |
| --- | --- |
| `401` | Missing, malformed, unknown or revoked token — **the client must drop its session**, not merely report it |
| `403` | The device is not linked, or its account lacks the role (`detail` says which) |
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
| `days` | `14` | 1–280 — целый учебный год, см. ниже |

Потолок в 280 дней — это самый длинный учебный год (274 дня, 1 сентября или
ближайший будний после него по 31 мая) плюс запас. Прежний потолок в 31 день
был не ограничением сервера, а размером кэша клиента: приложение просило месяц,
и календарь за его границей рисовал «Нет данных» — что на экране неотличимо от
«в этот день нет уроков». Расширение почти ничего не стоит: `ScheduleResolver`
делает одно и то же число запросов на любой диапазон и разворачивает остальное
из недельного шаблона в памяти. Замер на демо-классе: 6.0 KiB на 31 день против
47.9 KiB на 273.

Класс несёт свой номер и периоды учебного года. Номер — число, а не разбор
названия: «9А» может оказаться «9 инж» или «5-й Б», и регулярное выражение по
такому молча ошибается на том одном классе, что записан иначе. Периоды приходят
строками, а не формулой, потому что даты — дело школы: каникулы сдвигаются,
регион раньше уходит на весенние, карантин съедает неделю.

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

### Conditional requests

The response carries an `ETag`. Send it back as `If-None-Match` and an
unchanged bundle answers `304` with no body. The widget polls on a timer and
nearly every poll finds nothing new, so nearly every poll should cost a hash
comparison rather than two weeks of JSON over mobile data.

The validator is a hash of the body with `generated_at` blanked out - that
field changes on every request and would otherwise defeat the whole point.
Weak validators (`W/"..."`) and comma-separated lists are accepted.

### Field notes

* Times are local wall time (`HH:MM:SS`) in `school_class.timezone`. A bell rings
  at 08:30 regardless of daylight saving, so there is deliberately no UTC offset.
* `school_class.timezone` belongs to the **class**, not to the deployment. Russia
  spans eleven zones, so one server routinely hosts classes ten hours apart, and
  `start` defaulting to "today" is resolved in the class's zone. Clients must
  derive "now" from this field rather than from the device clock.
* `kind` on a day is one of `normal`, `holiday`, `shortened`, `remote`.
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
pupils marking the same задание each see their own - and is always `false`
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
a задание - and is `422` otherwise. `remind_at` is class wall time; an aware
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
same lesson as the same entry and a new замена replaces it rather than
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

Upsert by (date, subject): one задание per subject per day, and sending it
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
of this class's bell schedules - `422` otherwise. Answers the stored row.

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
| `PATCH` | `/manage/class` | admin | Rename, re-home, change time zone |
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

Days, замены, events and homework are not on this surface. They are the
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
  "members": 12,
  "devices": 9,
  "pending_requests": 1,
  "bell_schedule_id": 3,
  "calendar_ready": true
}
```

`PATCH /manage/class` takes any of `name`, `school`, `city`, `timezone` and
answers the card. Only the fields present change; `null` clears `school` or
`city`. `name` and `timezone` may not be null or blank, and `timezone` must be
one of the eleven Russian zones the bot offers (`422 unknown timezone`
otherwise). Changing the zone moves no stored time - a bell rings at 08:30
whatever the zone says - it changes which instant the class calls "now".

`DELETE /manage/class` is the owner's alone and takes the confirmation the bot
asks for:

```json
{ "confirm_name": "9А" }
```

The name must match exactly, or `422`; a sheet in the app is not the check,
because the endpoint is reachable without the sheet. The class, its timetable,
homework, замены, events, log and every device token go with it - including
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
lesson whose own cell names nobody — both on ordinary lessons and on замены.
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
замены store the subject as **text** as well - deliberately, so a lesson keeps
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
keeps the name, so the class kept its расписание and lost only the colour and
the teacher. That stopped meaning anything once the dictionary began keeping
itself: the name is still in the template, so the next read adopts it straight
back, stripped of the colour, the short name and the teacher the deleted row
carried, and the admin was told nothing at all. Two halves of one list are
deleted in one order — out of the расписание, then out of the dictionary. A
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
{ "id": 3, "name": "Обычное", "is_default": true,
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
`starts_at`; an empty list is `422`, the same refusal as the bot's «пустой
присылкой звонки не стереть».

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
two that were typos rather than re-reading the whole paste. A paste with no
weekday header and no bells block in it is `422`. A `== Звонки ==` block
replaces the default schedule's rows outright, as it does in the bot: it is a
schedule, not a day, and nothing else points at it.

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
endpoint - `.github/workflows/reminders.yml` in this repository, every five
minutes, with the `X-Cron-Secret` header set to the deployment's
`CRON_SECRET`.

| Status | Meaning |
| --- | --- |
| `404` | `CRON_SECRET` is unset: the endpoint does not exist, like the webhook without its secret |
| `403` | Wrong or missing header (constant-time comparison) |
| `503` | `BOT_TOKEN` is unset: nothing to send with |
| `200` | `{"morning": 1, "evening": 0, "tasks": 2, "failed": 0, "fsm_purged": 0, "join_attempts_purged": 3, "diary_sessions_purged": 0, "device_tokens_purged": 0}` |

What is due is decided from each class's own clock and from what was already
sent today, never from when the last tick ran - so a tick that runs twice in
a minute or an hour late sends each digest once.

Этот же тик подметает таблицы, которые растут между вызовами — ничто другое
в этом развёртывании не работает между запросами:

| Что | Когда удаляется | Почему |
| --- | --- | --- |
| Брошенные диалоги бота (`fsm_states`) | спустя сутки | `fsm_purged` |
| Счётчики неудачных попыток входа (`join_attempts`) | через час | `join_attempts_purged` |
| Сессии дневника (`diary_sessions`) | через сутки после отказа сервера, через 30 дней без использования | внутри лежит живой токен чужого сервиса |
| Токены устройств (`device_tokens`) | через 180 дней молчания | каждый `POST /join` создаёт строку; переустановка приложения оставляет старую навсегда |

180 дней — это заведомо больше летних каникул: телефон, молчавший с конца мая,
в сентябре должен работать. Устройство, которым пользуются, отмечается не реже
раза в 15 минут при любом чтении.

## `GET /api/v1/health`

Unauthenticated. Returns `{"status": "ok", "api_version": 1}`. Use it for
container health checks. `GET /api/v1/warmup` is the same plus one database
round trip, for keeping a scaled-to-zero database awake (see `deploy.md`).

## Электронный дневник Петербурга

Отдельная поверхность под `/api/v1/diary`. Отдельная — потому что это другая
вещь: `/api/v1` отдаёт расписание класса, которое заполняет бот, а здесь —
аккаунт конкретной семьи в чужом сервисе `dnevnik2.petersburgedu.ru`. Общего у
них ничего, кроме сервера.

**Приложение никогда не обращается к dnevnik2.petersburgedu.ru напрямую.** Ни
один параметр этого сервиса — `p_educations[]`, `p_datetime_from`, `X-JWT-Token`,
`estimate_type_code`, `hash_uid` — не пересекает границу нашего API. Если
завтра там переименуют поле, чинится один каталог `app/providers/petersburg/`,
а приложение и этот документ не меняются.

### Вход

```
POST /api/v1/diary/login
{ "login": "parent@example.com", "password": "…" }
```

```json
{ "token": "…", "login": "parent@example.com" }
```

**Пароль не хранится.** Он нужен ровно на один запрос — логин в чужом сервисе, —
после чего забывается. Хранится только сессия самого дневника, и она обновляется
на лету: сервис отдаёт свежий токен почти на каждом ответе, и мы его
переписываем, поэтому живая сессия не умирает от времени. Когда она всё-таки
перестаёт приниматься, любой запрос отвечает `401` с заголовком
`X-Diary-Reauth: required` — это просьба спросить пароль заново, а не «токен
неверный».

Цена решения честная: фоновая синхронизация дневника живёт не дольше сессии.
Альтернатива — держать пароль каждой семьи в базе ради экономии одного экрана
входа раз в несколько дней — того не стоит.

Токен этой сессии — **не** тот же, что у устройства класса. Телефон может быть
подключён к классу без дневника и к дневнику без класса; один токен на оба
пришлось бы перевыпускать при изменении любой половины.

### Что можно спросить

| Endpoint | Ответ |
| --- | --- |
| `GET /api/v1/diary/students` | дети, которых видит аккаунт |
| `GET /api/v1/diary/students/{id}/schedule?from=&to=` | расписание |
| `GET /api/v1/diary/students/{id}/homework?from=&to=` | домашние задания |
| `GET /api/v1/diary/students/{id}/grades?from=&to=` | оценки |
| `GET /api/v1/diary/students/{id}/periods` | учебные периоды |
| `GET /api/v1/diary/students/{id}/subjects?period_id=` | предметы периода |
| `GET /api/v1/diary/students/{id}/teachers` | учителя |
| `GET /api/v1/diary/students/{id}/attendance` | проходы через турникет |
| `POST /api/v1/diary/logout` | забыть сессию |

`from` и `to` — ISO-даты; по умолчанию две недели вперёд, максимум 62 дня.
`{id}` — идентификатор ребёнка из `/students`, и он **проверяется по списку
доступных этому аккаунту**: чужой id отвечает 404, а не чужими данными.

### Оценка — это не всегда оценка

Сервис кладёт в один список оценки, пропуски, опоздания и замечания и
различает их числовым кодом. Мы различаем их за клиента:

```json
{ "subject": "Алгебра", "date": "2026-09-11", "value": "Н", "kind": "absence" }
```

`value` — то, что пишут в клетку журнала; `kind` — что это на самом деле:
`grade`, `absence`, `late`, `remark`, `other`. Клиенту не нужно знать, что
«отсутствовал» — это код 30000.

Домашнего задания как отдельной сущности в сервисе нет: это поле урока.
`/homework` вынимает его и отдаёт списком, потому что спрашивают именно так.

### Коды ошибок

| Код | Что случилось | Что делать приложению |
| --- | --- | --- |
| `401` | токен нашей сессии не подошёл | попросить войти |
| `401` + `X-Diary-Reauth: required` | сессия дневника истекла | попросить пароль заново |
| `404` | такого ребёнка у этого аккаунта нет | — |
| `422` | диапазон дат вывернут или шире 62 дней | — |
| `502` | дневник ответил непонятно | показать, что сервис изменился |
| `503` | дневник не отвечает | предложить повторить |

`502` — единственный код, который означает, что чинить надо нам: сервис не
документирован, и когда его ответ перестаёт читаться, это видно именно так.


## Четверти и полугодия

| Метод | Путь | Роль | Что делает |
| --- | --- | --- | --- |
| `GET` | `/api/v1/manage/terms` | админ | периоды класса; при первом обращении создаёт обычный набор |
| `PUT` | `/api/v1/manage/terms/scheme` | админ | `{"kind": "quarter"\|"semester"}` — пересоздаёт год по другой схеме |
| `PUT` | `/api/v1/manage/terms/{index}` | админ | `{"starts_on", "ends_on"}` — двигает края одного периода |

Схема по умолчанию следует номеру класса: 1–9 учатся четвертями, 10–11 —
полугодиями. Выбор хранится отдельно от номера, поэтому «никто не решал» и
«решили четверти для одиннадцатого» — разные состояния, и второе школа вправе
выбрать.

Смена схемы **пересоздаёт** периоды года, а не правит их: четыре четверти и два
полугодия друг на друга не ложатся, и оставшаяся третья четверть внутри года из
двух половин — не то состояние, которое стоит хранить.

Правка краёв отвечает `422` с русской фразой в `detail`, когда период
заканчивается раньше начала, вылезает за учебный год или пересекается с соседним.
Именно фразой, а не ошибкой по полю: нарушено отношение между этим периодом и
годом или соседями, и «Пересекается с периодом 2 (01.11 — 31.12)» — это то, что
стоит показать на экране.

## Справочник школ

| Метод | Путь | Роль | Что делает |
| --- | --- | --- | --- |
| `GET` | `/api/v1/manage/schools?q=&page=&page_size=&region=` | админ | ищет школу в реестре и отдаёт одну страницу |

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

Найденное **не хранится**. Клиент берёт `name` и отправляет его в
`PATCH /api/v1/manage/class` — в карточке класса лежит название школы, как
лежало всегда, а не ссылка на чью-то запись.

`truncated` — это не «есть ещё страницы», их считает `pages`. Это «сработал
потолок реестра»: источник (DaData, поиск по ЕГРЮЛ) рассчитан на подсказки при
вводе, отдаёт максимум двадцать строк за раз и смещения не имеет. Значит перед
вами первые двадцать из неизвестно скольких, и дойти до своей школы можно
только более длинным запросом — дописав город или номер. Клиент, который
покажет «найдено 20», соврёт про поиск, под который попали триста школ.

**Каждый вызов — один поиск наверху**, что бы ни стояло в `page`: смещения у
источника нет, возобновлять нечего. Клиент, который листает, должен спросить
один раз с `page_size=20` и резать ответ сам — так делают и бот, и приложение.
Четыре страницы по пять — это четыре поиска на один вопрос. `page_size` — от 1
до 20, по умолчанию 5 (размер инлайн-клавиатуры).

`region` — подсказка ранжирования, не фильтр: школа через дорогу от границы
города всё ещё та самая школа.

Коды: `422` — запрос короче трёх символов (фраза по-русски в `detail`);
`503` — справочник не настроен (`DADATA_TOKEN` пуст), не отвечает или ответил
непонятно. Ответ на `503` — дать ввести название руками, а не повторять запрос:
в боте ровно эта кнопка и появляется.

Админ, хотя ничего не пишет: каждый вызов тратит часть суточного лимита на
чужом сервисе, и тратить его есть основания только у своего же класса.
