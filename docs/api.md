# Client API

Version `1`. Base path `/api/v1`. The client API is **read-only** — every write
goes through the Telegram bot. There is no user account, no password and no
personal data on this surface.

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
  "school": "Демо-школа"
}
```

The token is stored server-side only as a SHA-256 hash, so a database leak does
not yield working credentials. Every later request carries it:

```
Authorization: Bearer <token>
```

Codes are six characters from an alphabet with `O`, `0`, `I` and `1` removed,
because people type them off a whiteboard. Rotating a class code (owner-only,
`/code` in the bot) stops the old code from being redeemed again; devices that
already joined keep working, since their tokens are independent of it.

| Status | Meaning |
| --- | --- |
| `401` | Missing, malformed, unknown or revoked token |
| `404` | Join code does not exist, or the class was deleted |
| `422` | Parameter out of range, e.g. `days=400` |

## `GET /api/v1/bundle`

One request returns everything the app and the widget need.

| Parameter | Default | Notes |
| --- | --- | --- |
| `start` | today, in the school's timezone | `YYYY-MM-DD` |
| `days` | `14` | 1–31 |

```json
{
  "api_version": 1,
  "school_class": { "id": 1, "name": "9А", "school": "Демо-школа", "timezone": "Europe/Moscow" },
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
  "next_school_day": { "date": "2026-09-16", "...": "same shape as a day" }
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

### Field notes

* Times are local wall time (`HH:MM:SS`) in `school_class.timezone`. A bell rings
  at 08:30 regardless of daylight saving, so there is deliberately no UTC offset.
* `kind` on a day is one of `normal`, `holiday`, `shortened`, `remote`.
* `kind` on an event is one of `event`, `canteen`, `exam`, `trip`, `meeting`.
* A cancelled lesson stays in the array with `is_cancelled: true` rather than
  disappearing, so the UI can strike it through instead of silently renumbering
  the day.
* `covers_lesson` tells the client whether the event replaces a lesson or merely
  sits in a break. It is what stops "Обед" from hiding a running lesson.
* Unknown enum values must not crash a client — the Android mappers fall back to
  `normal` / `event`.

## `GET /api/v1/health`

Unauthenticated. Returns `{"status": "ok", "api_version": 1}`. Use it for
container health checks.
