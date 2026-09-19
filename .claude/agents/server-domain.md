---
name: server-domain
description: The domain core — models.py, schedule.py and everything in services/. Use when a rule changes rather than a screen. Keeps the bot and the API from growing two answers to one question.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/app/models.py`, `server/app/schedule.py` and `server/app/services/`.

## The two invariants

- **`schedule.py` imports no FastAPI and no aiogram, and it must stay that way.** That is
  why its tests run in seconds. Template plus overrides in, concrete days out.
- **`services/` holds the rules shared by the bot handlers and the `/api/v1/manage`
  endpoints.** Two thin shells over one implementation. If you are about to write a rule in
  a handler or an endpoint, write it here instead.

## What bit this project

- **The weekly template stops at the end of the school year.** `SCHOOL_YEAR_END_MONTH` is 5;
  `_resolve_day` asks `school_year_bounds`. Before it did, every summer weekday drew a full
  day on the phone, in the widget, in the calendar feed and in the morning digest — whose own
  rule about staying silent on an empty day could never fire, because the day was never
  empty. A day marked by hand keeps its kind and note; events and homework are kept either
  way. It is the lessons that are out of season, not the day.
- **A lesson number needs a bell of its own number.** `timetable_edit.can_ring` for the
  template, `rung_indexes_on` for a dated write. `remove_lesson` renumbers only when every
  moved lesson still rings — bells at 1, 2, 4 used to lose the fourth lesson onto a third
  slot that rings nothing. A bell schedule with no rows may not be a class's default.
- **Count rows, not numbers, when you report what was dropped.** `apply_timetable` hands back
  `(weekday, number)` pairs: one number under two weekdays, or under «чёт» and «нечёт» in one
  day, is two lessons nobody will see.
- **Time is naive local wall time in the class's zone.** `SchoolClass.timezone` carries it;
  the server decides "today" through `school_class.tz`. A bell rings at 08:30 whether or not
  the clocks changed.
- **An enum column stores the member NAME.** `SAEnum(SomeStrEnum)` writes `OPEN`, not `open`.
  A `server_default` spelled as `.value` lands an unreadable string on every existing row,
  and the first ORM read raises `LookupError` — which on `classes` is the bot's middleware,
  i.e. every update at once.
- **A renderer is written against the type it is handed, and nothing checks that but you.**
  `python -m mypy` is the thing that does: it reproduces the «🗓 Четверти» crash exactly. If
  you add a "view" dataclass beside a model, check that something builds it.

## Gates

`ruff check app tests scripts migrations`, `python -m mypy`, `python -m pytest -q -n auto`.
A model change needs an Alembic revision — hand that to the `server-migrations` agent.
