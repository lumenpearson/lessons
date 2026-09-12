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
| `401` | Missing, malformed, unknown or revoked token |
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
| `days` | `14` | 1–31 |

```json
{
  "api_version": 1,
  "school_class": { "id": 1, "name": "9А", "school": "Демо-школа", "city": "Санкт-Петербург", "timezone": "Europe/Moscow" },
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
| `200` | `{"morning": 1, "evening": 0, "tasks": 2, "failed": 0, "fsm_purged": 0, "join_attempts_purged": 3}` |

What is due is decided from each class's own clock and from what was already
sent today, never from when the last tick ran - so a tick that runs twice in
a minute or an hour late sends each digest once. The tick also sweeps
abandoned bot conversations and expired join-attempt counters.

## `GET /api/v1/health`

Unauthenticated. Returns `{"status": "ok", "api_version": 1}`. Use it for
container health checks. `GET /api/v1/warmup` is the same plus one database
round trip, for keeping a scaled-to-zero database awake (see `deploy.md`).
