---
name: server-providers
description: The foreign services (the diary providers petersburg and netschool behind the diary seam, and dadata), plus config.py and crypto.py. Use when an upstream shape changes or a setting is added. Knows which settings are mandatory on Vercel and which refuse in view of whoever they concern.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/app/providers/`, `server/app/config.py` and `server/app/crypto.py`.

## The provider shape

Each foreign service is `client.py` / `mapper.py` / `models.py`, and **nothing above
`models.py` knows the upstream's vocabulary** — not `p_educations[]`, not `X-JWT-Token`, not
`at`. If a word from the upstream is leaking into a service or a handler, the mapper is
incomplete.

- **The diary seam** is `providers/diary/`: `models.py`, `errors.py` (the `DiaryError`
  family; `PetersburgError` is a re-export alias so old imports still resolve), a
  `DiaryProvider`/`DiaryConnection` Protocol contract in `base.py`, and `registry.py` whose
  `provider_for(key)` lazily imports the implementation and `binding(school_class)` is the
  **one** place that resolves a class's diary binding (bot, main menu, class card and web
  form all call it). A second diary is a value in `registry.KEYS`, never a rename.
- `petersburg/` is one electronic diary, behind that seam and byte-for-byte unchanged. Its
  session token is the one stored credential that cannot be a hash, because it is replayed
  upstream on every call, so it is sealed with Fernet — and without `DIARY_SECRET` the whole
  diary feature refuses at the door. A silent plaintext fallback is invisible and deployments
  stay in that state for years.
- `netschool/` is «Сетевой город. Образование» (ИРТех), the main diary of ~20 regions, one
  route set for all of them. `regions.py` is an **allow-list**: the only origins ever
  contacted, so nothing from a client or a DB row becomes a URL. Sign-in is a windows-1251
  salted-MD5 password; a region that answers with the Госуслуги-only refusal raises
  `SignInUnsupported` and is not offered. Sessions idle out in 15–60 min and are held open
  from the cron tick (`services/diary_keepalive.py`) with `GET /webapi/context`. **Nothing
  here has met a live server** — every shape is from open-source clients and hand-written
  payloads; the first real session is the owner's (#121/#135).
- `dadata/` is the school directory, a search over the ЕГРЮЛ company register, because no
  downloadable register of
  Russian schools exists. Without `DADATA_TOKEN` it refuses and the bot asks for the name to
  be typed.

## The settings rule, and it is a hard one

`get_settings()` raises `DeploymentNotConfigured` when `VERCEL` is set and any of
`DATABASE_URL`, `BOT_TOKEN`, `WEBHOOK_SECRET`, `RUN_BOT`, `OWNER_IDS`, `TIMEZONE` is missing
or unusable — and it lists every one of them at once, because finding the next costs another
deploy.

**Do not add an optional setting to that list.** `DIARY_SECRET`, `DADATA_TOKEN`,
`PUBLIC_BASE_URL`, `BOT_USERNAME` and `CRON_SECRET` are empty by design and each already
refuses in view of whoever it concerns. They are logged as switched off at startup
(`Settings.disabled_features`), which is a different decision from making them mandatory.

The check hangs on `VERCEL` because the platform sets it about itself. Guessing "this looks
like production" anywhere else would one day refuse to start on somebody's laptop.

## Secrets

Never into the repository, never into a log, never into an issue or a report. Redact as
`<redacted>`. `BOT_TOKEN`, `OWNER_IDS`, `WEBHOOK_SECRET`, `CRON_SECRET` live in
`server/.env` or the host environment; the production database endpoint stays out of the
tree too.

## Gates

`ruff check app tests scripts migrations`, `python -m mypy`, `python -m pytest -q -n auto`.
A provider change needs a test with a recorded upstream payload, not a live call.
