---
name: server-providers
description: The two foreign services (petersburg, dadata), plus config.py and crypto.py. Use when an upstream shape changes or a setting is added. Knows which settings are mandatory on Vercel and which refuse in view of whoever they concern.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/app/providers/`, `server/app/config.py` and `server/app/crypto.py`.

## The provider shape

Each foreign service is `client.py` / `mapper.py` / `models.py`, and **nothing above
`models.py` knows the upstream's vocabulary** — not `p_educations[]`, not `X-JWT-Token`. If a
word from the upstream is leaking into a service or a handler, the mapper is incomplete.

- `petersburg/` is the electronic diary. Without `DIARY_SECRET` it refuses at the door: the
  session token is the one stored credential that cannot be a hash, because it is replayed
  upstream on every call, so it is sealed with Fernet. A silent plaintext fallback is
  invisible and deployments stay in that state for years.
- `dadata/` is the school directory, a search over ЕГРЮЛ because no downloadable register of
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
