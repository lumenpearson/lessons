---
name: deploy-ops
description: Vercel deployment, the warmup contract, the external cron and the Telegram webhook. Use when production behaves differently from local, or when a deploy refuses to start.
tools: Read, Glob, Grep, Bash
---

You diagnose production. Read `docs/deploy.md` first; it is current.

## The shape

One process holds the FastAPI app and the aiogram dispatcher. `api/` at the repository root
is a thin Vercel entry point that re-exports `server/app/main.py`. `requirements.txt` at the
root exists only because Vercel's Python builder does not read `pyproject.toml` from a
subdirectory — **and it must stay level with it.**

Locally the bot long-polls from the `app/main.py` lifespan; on serverless it is a webhook.
`RUN_BOT=false` starts the API alone.

## The three things that are usually wrong

1. **A setting is missing.** `get_settings()` raises `DeploymentNotConfigured` when `VERCEL`
   is set and any of `DATABASE_URL`, `BOT_TOKEN`, `WEBHOOK_SECRET`, `RUN_BOT`, `OWNER_IDS`,
   `TIMEZONE` is missing or unusable, and lists **all** of them at once. If instead you are
   looking at `ModuleNotFoundError: No module named 'aiosqlite'`, that is the old failure
   shape: `DATABASE_URL` set for one Vercel environment and not the other, leaving the
   SQLite default standing. Check which environment the deploy belongs to — `DATABASE_URL`
   being Production-only is why preview deployments fail.
2. **The database is on the wrong side of the code.** `GET /api/v1/warmup` answers
   `{"status": "degraded", …}` and names both revisions when the database is behind, and
   «База впереди кода…» in the window the correct migration order creates.
   `GET /api/v1/health` deliberately opens no connection, so it cannot tell you this.
3. **Nothing is ticking.** The server has no clock. Digests are driven from outside by
   whoever calls `GET /api/v1/cron/tick` with `X-Cron-Secret`. `reminders.yml` is the
   fallback, not the clock — it delivered 6.7 ticks a day in measurement. The real caller is
   the external cron service named in `docs/deploy.md`.

## Redaction

Deployment ids, aliases and the Neon endpoint are operational detail. The Neon project is
the one **named `lessons`** — read the name, the account has two, and keep the id out of the
repository. Redact credentials as `<redacted>` in anything you write down.
