---
name: server-api
description: FastAPI surface of the server — public.py, edit.py, manage.py, diary.py, diary_web.py, cron.py, telegram.py and deps.py. Use when an endpoint is added, its auth changes, or a response shape moves. Knows which of the two bearer tokens an endpoint family depends on.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/app/api/` and nothing else. Read the file before you edit it and grep
every caller before you change a signature.

## What is actually here

- `public.py` reads, `edit.py` and `manage.py` write, `diary.py` is the electronic diary,
  `diary_web.py` is the one server-rendered page in the project, `cron.py` is the clock the
  server does not have, `telegram.py` is the webhook, `deps.py` is device-token auth.
- The write endpoints are a thin shell over `app/services/`. The bot handlers are the other
  shell over the same functions. If you implement a rule here rather than in a service, the
  bot and the API disagree about it within a month — that is the whole reason `services/`
  exists.

## Traps that have already cost this project

- **Two independent bearer tokens.** The device token (`deps.current_device`, minted by
  `POST /api/v1/join`) and the diary session token (`diary.current_diary`, minted by
  `POST /api/v1/diary/login`) travel in the same `Authorization: Bearer` header on
  different endpoint families. A phone can hold either without the other. Check which
  dependency an endpoint declares before you move it between routers.
- **`/join` takes two code shapes.** Eight characters is the class code (anonymous,
  read-only, gated by `SchoolClass.join_mode`); ten is a personal connect code (fifteen
  minutes, one phone, carries the minting account's role, re-checked per request and never
  cached). The class code is looked up first; the lengths are what keeps them apart. A
  `403` is deliberately not counted against the throttle — that caller had a real code.
- **A lesson number needs a bell of its own number.** `edit.day_put` and the substitution path
  must check `timetable_edit.can_ring`, and a dated write must use `rung_indexes_on`,
  because a shortened day points at a shorter schedule. A row at a number the day does
  not ring is stored and drawn nowhere. `day_put` also refuses a bell schedule with no rows.
- **`/api/v1/health` opens no connection on purpose**; `/api/v1/warmup` is the one that can
  say the database is behind or ahead of the code. Do not make `health` clever.
- **The server has no clock.** Nothing runs between requests on Vercel. Anything you want
  scheduled belongs in `GET /api/v1/cron/tick`, which marks a digest sent before sending it
  and works from "due and not yet sent today", so a late or half-dead tick is safe.
- **`diary_web` spends the sign-in ticket before the attempt.** Only `UpstreamUnavailable`
  hands it back; a 200 of HTML must not, because a Yii login form refuses a password with
  the same bytes a captcha arrives in.

## Before you hand anything back

From `server/`: `ruff check app tests scripts migrations`, `python -m mypy`,
`python -m pytest -q -n auto`. A new endpoint without a test that exercises its auth
dependency is not finished.
