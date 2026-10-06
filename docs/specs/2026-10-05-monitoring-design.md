# Monitoring: a self-check that reports to the owner, an external clock, error tracking, and a project screen

Status: **approved by the owner on 5 October 2026.**
- The owner chose all three parts, and the statistics as a bot screen plus the ready-made
  dashboards, not a web page.
- They answered the three questions below with the recommendation: six hours; owner only;
  5 %.
- Not yet built. It is its own pull request, after stage 3b-2 (#356), and its plan is
  `docs/specs/2026-10-06-monitoring-plan.md`, written on 6 October 2026 against that tree.
- Milestone: `v1.0.0 — A build somebody else can install`.

## Why

On 5 October three things went wrong in production, and nobody was told:
- Vercel did not deploy a merge, so production ran the old code for half an hour;
- the diary's proxy VPS answered nothing for forty minutes;
- the external cron that drives the digests (#120) has never been set up, so the morning and
  evening digests come when GitHub's scheduler gets round to them.

The owner found each one by asking. This design makes the system say so itself, and gives the
owner one place to see how the whole project stands.

What the owner asked for, in their words: «аналитику в бэкенд для профилактики на постоянке»,
and then «больше информации о серверах, бэкенде и запросах, отдельную вкладку статистики всего
проекта на деплое».

## What it is not

- **Not Vercel Web Analytics or Speed Insights.** Both count page views and measure page loading
  in a browser, and this project has no web pages. Installed, they would show empty charts.
- **Not a web dashboard.** `CLAUDE.md` keeps the bot as the only admin surface («anything the bot
  can express belongs in the bot»). The owner chose the bot screen.
- **Not request counting by the server.** Counting requests in the database would add a write to
  every request. On serverless with Neon that is real latency and load, and it would break
  «reads stop writing» (the v2 design's decision 10). The request graphs come from Vercel's
  Observability tab and from Sentry, which already see every request.

## The four parts

### 1. The self-check, in every cron tick

`services/health.py` runs a small set of checks at the end of every
`GET|POST /api/v1/cron/tick`. They run inside their own guard and their own time budget, like
the diary keep-alive, so a failing check never fails the tick.

| Check | OK when | Notes |
| --- | --- | --- |
| `schema` | the database's Alembic revision equals `db.EXPECTED_REVISION` | the same reading `/api/v1/warmup` makes |
| `v2` | `app.state.v2_mounted` is true | the fail-safe mount's own record |
| `diary_proxy` | one `HEAD https://dnevnik2.petersburgedu.ru/` through `DIARY_PROXY_URL` answers with any HTTP status within 5 s | only when the setting is set; any status means the tunnel and the diary both answered |
| `deploy` | the running deployment's commit (`VERCEL_GIT_COMMIT_SHA`) is `main`'s head, or `main` moved less than 15 minutes ago | `main`'s head is read from GitHub's public API with an `ETag`, so an unchanged answer costs no rate limit; off Vercel the check is skipped |

Each check ends in one of three states:
- **ok**;
- **failing**, with a short reason in English for the log;
- **unknown**, which means the check itself could not run (GitHub not answering, say). Unknown
  never alerts.

**State and alerts.** A new table `health_checks` (revision `0019`, additive) holds one row per
check:

| Column | Holds |
| --- | --- |
| `name` | the check's name |
| `status` | `ok`, `failing` or `unknown` |
| `reason` | the short reason |
| `since` | when the status last changed |
| `last_alert_at` | when the owner was last told |
| `checked_at` | when the check last ran |

The bot writes to every `OWNER_IDS` account:
- when a check goes from ok to failing: «🔴 Прокси дневника не отвечает»;
- when it comes back: «🟢 Прокси дневника снова работает, простой 40 мин»;
- every six hours while a check stays failing, as a reminder.

There is never one message per tick. The text names the infrastructure only, never a class, a
family or a child.

**Where the sending lives.** It goes through a new `server/app/telegram_send.py`: the one-shot
bot build and send, out of `app.bot`, which decision 2 of the v2 design already plans for stage
3b-3. This design writes it first, and 3b-3 reuses it.

### 2. The external clock (cron-job.org)

An external scheduler calls the tick every five minutes, with the `X-Cron-Secret` header:
- **#120:** the digests come on time, which closes it.
- **When the server is down entirely:** cron-job.org emails the owner after a few failures in a
  row. Nothing inside the server can report that case, because the bot is down with it.
- **The service:** cron-job.org, free. It sends custom headers, runs every minute or less often,
  and emails on failure.
- **Setup:** it is configured by the owner in the browser. `docs/deploy.md` gets the steps.
  `CRON_SECRET` must be set in Vercel for Production.
- **The fallback clock:** `.github/workflows/reminders.yml` stays, as it is now.

### 3. Sentry: errors with their place, and a sample of timings

- **The settings:**
  - `SENTRY_DSN`: optional. Empty means off, and the startup log says so in
    `Settings.disabled_features`. It is never added to the deployment refusal's list.
  - `VERCEL_ENV`, which Vercel sets itself, becomes Sentry's environment (`production`,
    `preview`), so preview errors stay apart.
- **The SDK:** `sentry-sdk` with its FastAPI integration.
  - It is imported and initialised only when `SENTRY_DSN` is set, so the cold start is unchanged
    when it is off. `tests/test_cold_start.py` holds that.
  - It is added to `server/pyproject.toml` and `requirements.in`, and the lock
    `requirements.txt` is recompiled by the command in its own header, never edited by hand.
- **Bot errors** reach Sentry from the dispatcher's error handler.
- **What is sent, for each error:** the exception's type and its stack (file, function, line),
  the route template (`/api/v1/diary/students/{student_id}/schedule`, never the values), the
  release (the commit) and the environment.
- **Timings:** a sample of 5 % of requests, named by route template. These are Sentry's
  request graphs.
- **What is never sent (152-ФЗ):**
  - request and response bodies;
  - headers, cookies, query strings;
  - the exception's message text, which can carry a name or a value;
  - local variables;
  - log lines and breadcrumbs.

  A `before_send` hook removes them, `send_default_pii` is off, `include_local_variables` is off,
  and breadcrumbs are off. A test asserts that an event built from a request carrying a diary
  token and a child's name leaves with neither.
- **What the owner does:**
  - create a free Sentry account in the **EU** data region (sentry.io → Data Storage Location:
    EU) and a project of type «Python / FastAPI»;
  - put its DSN into Vercel as `SENTRY_DSN` for Production, and for Preview if wanted. The free
    tier is 5,000 errors a month.

### 4. «📊 Проект», the owner's screen in the bot

- **Who sees it:** a command `/project`, and a button under «⚙️ Класс» → owner only. It answers
  only an account in `OWNER_IDS`; anybody else gets the bot's ordinary «unknown command»
  silence. Under `/health`, the same screen's first block alone.
- **What it shows**, one message within Telegram's 4096 characters, `render.clamp` as everywhere:

| Block | What it shows |
| --- | --- |
| **Состояние** | each check of part 1, with ✅/🔴/❔, its reason when failing, and since when |
| **Сервер** | the running commit (short), whether it is `main`'s head, the region (`fra1`), how long this function instance has been alive (a cold start shows as minutes), Python's version |
| **База** | the schema revision and the expected one; on Postgres also the database's size and its number of connections (`pg_database_size`, `pg_stat_activity`) |
| **Прокси дневника** | up or down, the check's own round trip in ms, since when |
| **Часы** | the last tick and its gap from the one before (from `health_checks.checked_at`), and digests sent today |
| **Проект** | classes; accounts; phones seen in the last 24 hours and 7 days; phones by app build (`device_tokens.client_version` from stage 3b-1); diary sessions by provider; v2 on or off |
| **Где графики** | one line naming the dashboards: Vercel → Observability (requests, errors, durations by route), Sentry → Issues and Performance, Neon → Monitoring |

- **What it is made of:** reads only, a handful of `count(*)` and two Postgres catalogue
  queries. Nothing is written by looking at it.
- **The links:** the dashboard links are not built into the bot, because they name the owner's
  accounts. `docs/deploy.md` lists them.

## Where things live

```
server/app/telegram_send.py          build_bot() and send(), neutral; app.bot.bot re-exports build_bot
server/app/services/health.py        the checks, their states, the alert rule
server/app/services/project_stats.py the screen's numbers, reads only
server/app/observability.py          Sentry's init and its scrubbing hook, imported only with SENTRY_DSN
server/app/api/cron.py               calls health.run() last, in its own guard
server/app/bot/handlers/project.py   /project and /health, owner only
server/app/bot/project_render.py     the screen's text, escaped, within budget
server/migrations/versions/0019_health_checks.py
```

`services/` imports nothing from `app.bot`, as everywhere (`tests/test_service_layering.py`).

## Testing

- **Each check** gets a unit test for ok, failing and unknown, with the outside world faked:
  the proxy, GitHub, and the Vercel environment.
- **The alert rule** is tested for:
  - one message on a change, both ways;
  - none per tick;
  - a reminder after six hours;
  - unknown never alerts;
  - a failing send that does not fail the tick.
- **The tick** answers as before when every check raises.
- **The screen** gets a renderer test within the budget, an escaping test, and a test that an
  account not in `OWNER_IDS` gets no answer.
- **Sentry:**
  - the scrubbing test (no body, header, query, message or local variable leaves);
  - with no DSN, `sentry_sdk` is never imported;
  - the setting is announced as off and is not in the refusal list.
- **The migration:** `0019`'s DDL is compared with the model, as the others are.

## What only the owner can do

1. Set up cron-job.org: the URL, the header, every 5 minutes, failure emails on. Check that
   `CRON_SECRET` is set in Vercel.
2. Create the Sentry account in the EU region, and put `SENTRY_DSN` into Vercel.
3. Nothing to do for Vercel Observability: the tab is in the project's dashboard already.
   Longer retention is Observability Plus, a paid add-on, not needed now.

## Questions for the owner

1. **The reminder while a check stays failing:** every six hours? *Recommended: yes.* Often
   enough not to be forgotten, rarely enough not to be muted.
2. **Should `/project` also go to admins of a class,** not only to `OWNER_IDS`? *Recommended:
   no.* It shows the whole deployment, every class's numbers summed, and the infrastructure,
   which is the owner's.
3. **Sentry's sample of timings at 5 %?** *Recommended: yes.* That stays well inside the free tier
   at this project's traffic, and still draws the request graphs.

## Risks

- **GitHub's public API rate limit** (60 an hour per address) is shared by every Vercel function
  on the same egress address. The `ETag` makes an unchanged answer free, and a refusal reads as
  unknown, never as failing.
- **A proxy check from Frankfurt costs one request to the diary every five minutes.** That is 288
  a day, a `HEAD` of its front page. That is less than one family opening the app.
- **Sentry is a third party holding stack traces.** The scrubbing is the guard, and its test is
  the proof. If the owner later wants none of it, deleting `SENTRY_DSN` turns it off with no
  code change.
