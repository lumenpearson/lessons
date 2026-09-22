# Hosting

## In short

There are two honest options, and choosing between them is not about technology but about
whether you are willing to pay €4 a month.

| | Free: Vercel + Neon | ~€4/mo: a small VPS |
| --- | --- | --- |
| Changes to the code | webhook instead of polling, Postgres, FSM in the database | none |
| The bot's response | 1–3 s on a cold start | instant |
| Reliability | depends on two other people's free plans | depends on you |
| Complexity | noticeable | `docker compose up` |

If being free is a hard requirement, take the first. If four euros a month are acceptable,
the second is simpler, faster and requires rewriting nothing.

## Why Vercel does not work "as is"

Three obstacles, and all of them are real.

**The bot uses long polling.** It holds an open connection to Telegram and waits. On Vercel
there are no long-lived processes: a function wakes for a request and dies. Polling there is
impossible in principle. What is needed is a webhook — Telegram knocks on your URL for every
message.

**SQLite does not survive a deploy.** A function's file system is ephemeral. The database
has to be external; Neon gives free Postgres, and the app already knows how to work with it
through a `DATABASE_URL` with `asyncpg`.

**Dialogue state would be lost between messages.** By default aiogram keeps unfinished
dialogues in the process's memory. On serverless, each message may land in a new instance,
so the bot would forget the date you picked before you typed the assignment's text. Every
multi-step flow would break: homework, invitations, substitutions, events, the timetable.

All three are solved. The webhook is `app/api/telegram.py`, mounted only when a secret is
set. Postgres the app already handles. Dialogue state is `app/fsm_storage.py`, in the same
database as the data; the side benefit is that a redeploy no longer cuts off a dialogue in
progress. 24 tests cover both mechanisms.

## Option 1: Vercel + Neon

### What you need

1. A [Neon](https://neon.tech) account — free Postgres.
2. A [Vercel](https://vercel.com) account, the Hobby plan.
3. A bot token from [@BotFather](https://t.me/BotFather).
4. Your Telegram id from [@userinfobot](https://t.me/userinfobot).

### Secrets

**Never send a token in a conversation, a commit or an issue.** The token gives full control
of the bot: reading every message, answering in its name, changing it. A leaked token is
revoked in @BotFather with `/revoke`.

The right place is the host's environment variables. In Vercel:
**Settings → Environment Variables**, and be sure to tick `Sensitive`, or the value can be
read back in the interface.

| Variable | Value |
| --- | --- |
| `BOT_TOKEN` | the token from BotFather |
| `OWNER_IDS` | your Telegram id; several are comma-separated, see "Several owners" below |
| `DATABASE_URL` | the Neon connection string, with the `postgresql+asyncpg://` scheme |
| `TIMEZONE` | the default time zone for new classes |
| `WEBHOOK_SECRET` | a random string, see below |
| `RUN_BOT` | `false` — polling does not start on serverless |
| `CRON_SECRET` | a random string, see "The clock" below |
| `BOT_USERNAME` | the bot's @name without the "@" — for the phone-linking link |
| `PUBLIC_BASE_URL` | `https://your-project.vercel.app` — for the calendar and the diary sign-in page |
| `DIARY_SECRET` | a random string, see "The electronic diary" below |
| `DADATA_TOKEN` | a DaData key, see "The schools registry" below — without it the school search is simply off |

Vercel applies them **at deploy time**: changing a value without rebuilding changes
nothing, and the running deployment goes on holding the old one.

**Every variable is scoped to an environment, and today every one of them is Production
only.** That is a deliberate state rather than an oversight, and it has one visible
consequence worth knowing before you go looking for a fault: **every Preview deployment
answers `500` to every request.** A push to `dev` builds fine, Vercel comments on the pull
request with a «Ready» link, and following it gets a function that died while importing
`app.db` with the `DeploymentNotConfigured` above — `DATABASE_URL` unset, `BOT_TOKEN`
empty, `RUN_BOT` not `false`. The build is green because the refusal happens at *runtime*;
the check is doing exactly what the next section describes, and the preview is refusing
rather than quietly standing up on a SQLite file it has no disk for.

It does not affect anything: the process dies before it opens a connection or registers a
route, so an unconfigured preview touches neither the database nor the Telegram webhook,
and it turns no GitHub check red. If you want it to stop, there are two honest ways and
copying Production's values into Preview is **neither** — that would point every branch at
the real database and hand a throwaway deployment the real bot token, and Telegram gives
its updates to whichever consumer registered the webhook last.

- **Give Preview its own set**: a Neon branch for `DATABASE_URL`, a second bot from
  BotFather for `BOT_TOKEN` and `BOT_USERNAME`, its own `WEBHOOK_SECRET`, `DIARY_SECRET`
  and `PUBLIC_BASE_URL`. That is a staging environment, and it is worth it the day
  something has to be tried against a real database before it merges.
- **Turn Preview deployments off** in the project's Git settings. Nothing in this
  repository is a web page — the one server-rendered form is `/diary/signin`, which without
  a database and a diary account shows nothing — so a preview of it has nobody to serve.

Until one of those is done, the «Ready» link on a pull request is noise, and this paragraph
exists so that nobody exports the logs a second time to find that out.

### What is missing is said at the door

Six of that table are ones a deployment does not survive, and each missing one used to
surface far from its cause. Now the process refuses to start and lists **all of them at
once** — you do not pay another deploy to find the next:

| Variable | What happened without it |
| --- | --- |
| `DATABASE_URL` | the default `sqlite+aiosqlite:///./lessons.db` stood, and `aiosqlite` is deliberately not in `requirements.txt` — so everything died with `ModuleNotFoundError: No module named 'aiosqlite'` from inside SQLAlchemy, naming neither the variable nor the project |
| `BOT_TOKEN` | neither polling nor webhook: Telegram's requests arrive at a route that was never registered |
| `WEBHOOK_SECRET` | the webhook is not registered (an unauthenticated one would let anybody forge an update from any id) — the bot is deaf and looks like it is ignoring everyone |
| `RUN_BOT` | not `false` — polling on a cold start takes the updates away from the webhook they were meant for |
| `OWNER_IDS` | the value is there but no id is read from it: only a comma separates, a newline does not, and the field in Vercel is a textarea |
| `TIMEZONE` | a name that does not exist in this Python build: nothing fails, and `resolve` quietly gives every new class Moscow |

The check is switched on by the `VERCEL` variable, which the platform sets about itself.
Guessing "this looks like production" anywhere else would one day mean refusing to start on
somebody's laptop, and that is worse than what the check prevents. Your own server
(option 2) is configured by hand from the same table.

The rest are optional, and that is deliberate too. They do not fail the deployment; they are
written to the log at startup as switched off: `DIARY_SECRET` (the diary), `DADATA_TOKEN`
(the school search), `PUBLIC_BASE_URL` (the calendar and the sign-in page), `BOT_USERNAME`
(the phone-linking link), `CRON_SECRET` (the tick answers 404 and not a single digest goes
out). Each of them already refuses at its own door — in full view of whoever it concerns.

Invent `WEBHOOK_SECRET` yourself and show it to nobody:

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(32))"
```

Invent `CRON_SECRET` the same way. Without it the reminder endpoint answers 404: a tick
anybody can fire is a way to make the bot write to every subscriber whenever they feel like
it.

### Several owners

`OWNER_IDS` takes a list — on one line, comma-separated:

```
OWNER_IDS=111111111,222222222,333333333
```

Spaces around the numbers are trimmed, a semicolon is understood the same as a comma, and a
trailing comma does no harm. @userinfobot will tell you your own id.

**Only a comma (or a semicolon) counts as a separator.** The value field in Vercel's
interface is multi-line and positively invites one id per line — and then the list is not
read at all:

| What you typed | What was read |
| --- | --- |
| `111,222,333` | `111, 222, 333` |
| `111, 222, 333` | `111, 222, 333` |
| `111;222;333` | `111, 222, 333` |
| `111,222,` | `111, 222` |
| `111 222 333` | **nothing** |
| one per line | **nothing** |
| `111,222x,333` | **`111, 333`** |

The last three rows are where people get burnt. The mistake is announced nowhere: the bot
starts, writes nothing to the log, and simply nobody (or one particular person) has owner
rights. The parsing is in `app/config.py`, `Settings.owner_id_list`: anything that does not
look like a number is quietly dropped.

**The variable is read at build time.** An edit does not reach a deployment that is already
running — after changing the list you need a Redeploy, or the owners stay as they were.

A role from `OWNER_IDS` overrides a role in a class: such a person is the owner in **every**
class of that deployment, not in one. OWNER cannot be granted from inside the bot and cannot
be reached by escalation (`app/bot/roles.py`), so removing an owner likewise means editing
the variable and redeploying.

### The electronic diary

`DIARY_SECRET` is the key the electronic diary's session is encrypted with (`app/crypto.py`).
It is the only credential in the project that cannot be stored as a hash: the diary's token
is sent upstream on every request, so it is encrypted rather than hashed.

```bash
python3 -c "import secrets; print(secrets.token_urlsafe(48))"
```

Without it the diary is **switched off entirely** rather than running in plaintext: a silent
fallback to plaintext would be invisible — the feature answers, and the only difference is in
a column nobody looks at — and deployments live like that for years. "Too short" counts as
missing: anything under 32 characters is refused at the same door, and the startup log says
the diary is off rather than letting a set-but-unusable key look configured.

Three things worth knowing about this key:

* **It is needed only in Vercel's environment.** It is not a GitHub Actions repository
  secret: no workflow imports this code. The tests set their own key (`tests/conftest.py`),
  and `reminders.yml` only pokes an already-deployed tick over HTTP.
* **It has to match across every Vercel environment that talks to the same database.** A
  session sealed in Preview will not open in Production under a different key. Nothing
  breaks — an unreadable session counts as expired and the person signs in again — but it
  looks like a bug.
* **Changing the key signs everybody out.** That is a legitimate way to revoke every stored
  diary session at once, if you suspect the database has leaked.

The diary also needs `PUBLIC_BASE_URL`: without it the bot has nowhere to get the sign-in
page's address from, and it says so honestly rather than printing a link that would 404.

`BOT_USERNAME` and `PUBLIC_BASE_URL` are not secrets, but they are not guessable either. The
first is needed so the app can show a `t.me/<bot>?start=link_<code>` link; without it the
linking code still works, it is just typed by hand. The second is the address the bot hands
out the class's calendar link at; the server does not take it from the request, because
behind Vercel the function sees an internal host, and the bot builds the link with no
request at all.

Telegram sends the webhook secret in the `X-Telegram-Bot-Api-Secret-Token` header on every
request. Without checking that header your webhook address is an open endpoint anybody can
send forged updates to: make themselves an editor, rewrite the timetable, wipe the homework.
The check is mandatory.

### The schools registry

`DADATA_TOKEN` is the key to the school search. When a class is created the bot asks for the
school and looks it up by name; without a key that step stays what it always was — "type the
name by hand".

Why an external service rather than a table in the repository: **there is no downloadable
all-Russian register of schools.** Rosobrnadzor's open data — the very source everything
findable on GitHub for this points at — answers 404 today. What there is, is the ЕГРЮЛ
company register, where every school appears simply because every school is a legal entity,
and DaData, which is a search over it that people actually use.

The key is taken from https://dadata.ru/profile/#info — you want the "API key", not the
"secret key". The free plan is 10,000 requests a day; the bot makes one request per search
and pages through the results locally, so the pages cost nothing.

Worth knowing:

* **The key lives only in the server's environment.** It is not in the APK and must not be:
  the phone asks our server (`GET /api/v1/manage/schools`), and the server goes upstream.
* **Twenty is a ceiling, not a number of matches.** DaData's endpoint is built for
  type-ahead suggestions: it returns no more than twenty at a time and has no offset ("the
  next twenty"). So the answer honestly says «показаны первые 20» rather than «найдено 20»,
  and the only way to reach your school is to add the town or the number.
* **A missing key is not a breakage.** The search answers 503 with the words «введите
  название вручную», and a class is created as before. There is deliberately no fallback
  list inside the project: a snapshot of the register would confidently answer with last
  year's schools, and nothing on the screen would tell you which you were looking at.

### The Neon connection string

**Paste it as it is.** The app normalises it into what asyncpg understands by itself: it
adds the driver, turns `sslmode` into a connection parameter, and strips `channel_binding`
and anything else only libpq knows.

Neon offers two strings. **Take the one with `-pooler` in the address.** On serverless,
instances come and go and each opens its own connections; without the pooler you will hit
Postgres's limit before you notice.

There is one non-obvious price for that, and it is handled in the code. PgBouncer in
transaction mode hands each transaction whichever backend is free, while asyncpg prepares
statements on the connection it saw first. The mismatch surfaces under load rather than at
startup, as an error reading `prepared statement "__asyncpg_..." already exists`. So for a
pooler, prepared-statement caching is switched off automatically.

The direct string (without `-pooler`) works too, and is even marginally faster for a single
request — but only if there is one instance. On Vercel there is not.

### Creating and updating the schema

The schema is managed by Alembic. The same command serves an empty database and one that is
already running:

```bash
cd server
DATABASE_URL='postgresql+asyncpg://...' .venv/bin/alembic upgrade head
```

Revision `0001` is a `create_all` with `checkfirst`: on an empty database it creates
everything, on a live one only what is not there yet, and it brings that database under
Alembic's management without rewriting anything. Revision `0002` widens five Telegram-id
columns to 64 bits: `Integer` on Postgres is int4, and Telegram ids passed 2³¹ back in 2021,
so the first person with a modern id crashed the bot on overflow. Columns that are already
wide it leaves alone. Then the additive ones: `0003` — personal tasks, homework ticks,
reminders, access requests, the log, device linking and the calendar token; `0004` — the
Petersburg electronic diary's sessions, with no column for a password, because the password
is not stored. `0005` adds a column for which break the canteen falls on; `0006` encrypts
the diary credentials (and **deletes** the existing sessions — sealing them would mean
keeping keys that had already sat in backups in the clear) and adds the per-member diary
columns; `0007` adds the two foreign keys to `classes.id` that `0006` forgot to declare;
`0008` gives a class a number (1–11) and cuts its year into quarters or half-years; `0009`
creates the table of corrections laid over the diary; `0010` adds the class's join mode
(`classes.join_mode`) and the table of personal connect codes; `0011` brings two timestamps
in `diary_overrides` up to `NOT NULL`, the way the model builds them: `0009` declared them
without it, and on a database built by a real `alembic` run the schema would have diverged
from `create_all` — on ours it does not, because the DDL for `0009` came from the model.
`0012` does the same for eight more timestamps across seven tables, and there it was not a
no-op: all eight really were nullable, and all eight held no nulls, so it tightened them and
rewrote nothing. `0013` adds `uq_homework_per_subject_per_day`. `0014` widens
`day_overrides.kind` to `VARCHAR(10)`: `DayKind` gained `SELF_STUDY`, and a `SAEnum`
stores the member name, so the column is as wide as the longest of them.

Everything up to `0012` checks with an inspector what is not in the database yet and does
not rewrite existing tables, so those can be applied to a live class in the middle of a
school day. The head is `0014`.

### A migration goes BEFORE the deploy, not after

This is not a matter of taste. A merge to `main` deploys to Vercel by itself, while the
migration is run by hand — and between them is a window in which the code knows about a
column the database does not have. Production has already been taken down that way: code
with `classes.diary_provider` left before its revision, nothing failed at startup, and the
first ORM query for a class did — that is, the bot's middleware, that is, **every** update,
with an `UndefinedColumnError` forty lines into the traceback.

The right order is:

1. the migration on the production database;
2. only then the merge (which is the deploy).

That works precisely because the additive revisions add: a column the running code does not
know about yet is a column it does not read. The other way round is never safe.

**A constraint goes the other way.** A `UNIQUE` or a `NOT NULL` breaks the **old** code, not
the new: where a moment earlier a duplicate was allowed, there is now an `IntegrityError`
nobody catches. `0013` is that shape and says so in its own docstring — it went on *after*
the merge, and `services/homework.py` is written to be correct with or without it, so the
window in between behaved exactly as production did before.

That the database really has caught up with the code is shown by `alembic current` — it
should print the same revision as `alembic heads`. The same thing over HTTP, without going
into the database by hand:

```bash
curl -s https://<your-project>.vercel.app/api/v1/warmup
```

`{"status":"ok","schema":"0014"}` means it all lines up. `"status":"degraded"` together with
`expected_schema` names both revisions and says which way they diverged: «База отстала от
кода» — the database is behind the code — is an incident, while «База впереди кода» — the
database is ahead — is the normal window between steps 1 and 2, which the deploy closes.
`/api/v1/health` deliberately does not show this: it opens no database connection at all, or
a ping meant to warm things up would keep waking a sleeping Neon.

### What to apply them with

In practice this project's migrations are applied **through the Neon connector** rather than
with the `alembic` command — that is how `0005`–`0014` were applied. The Neon project is
called `lessons`; its identifier is not kept in the repository — anybody with access sees it
in the Neon console anyway, and in a public repository it is just the address of somebody
else's database.

It is not a run of alembic but the revision's DDL executed as one transaction together with
the `alembic_version` stamp. Three rules follow, and each of them has been paid for:

- **The DDL comes from the model, it is not written by hand.**
  `CreateTable(Term.__table__).compile(dialect=postgresql.dialect())` prints exactly what
  `create_all` would have built — that is, what the revision is obliged to produce. A column
  type invented by hand diverges from the model silently and surfaces a month later.
- **Read the state first; the stamp is the last action in the same transaction.** Otherwise
  a half-applied revision declares itself whole.
- **Say out loud what a revision destroys before running it.** `0006` deliberately deletes
  every row of `diary_sessions`; that sentence is needed by the owner *before* the
  transaction, not after.

The equivalent for anybody who has a working connection string is `alembic upgrade head`. It
is run locally, with a working connection string, and not from inside a function: a migration
takes an `ACCESS EXCLUSIVE` lock on a table, and doing that on a request's cold start is a
bad idea. To see the SQL before executing it:

```bash
DATABASE_URL='postgresql+asyncpg://...' .venv/bin/alembic upgrade head --sql
```

The database's current state is `alembic current`. Its history is `alembic history`.

A separate command rather than something automatic at startup: the tables are usually created
by FastAPI's lifespan, but a serverless platform may never call lifespan at all. Relying on
it means a first request that fails with "no such table" and no intelligible reason.

### Registering the webhook

Once, after the deploy:

```bash
curl -X POST "https://api.telegram.org/bot<TOKEN>/setWebhook" \
  -d "url=https://<your-project>.vercel.app/api/v1/telegram/webhook" \
  -d "secret_token=<WEBHOOK_SECRET>" \
  -d "drop_pending_updates=true"
```

To check: `https://api.telegram.org/bot<TOKEN>/getWebhookInfo`. The `last_error_message`
field will say why Telegram cannot reach you.

You run this command yourself, on your own machine — the token is in it in the clear.

### Why the secret is mandatory

Without it `webhook_enabled` returns `false` and the endpoint is not mounted at all. That is
not over-caution. Telegram signs nothing: it simply sends JSON, and the bot trusts the
`user_id` inside that JSON when deciding who may edit a timetable. With no secret check,
anybody who guesses the URL sends a forged update with somebody else's id — and that is not
spam, that is taking the bot over completely.

The comparison goes through `hmac.compare_digest`, so the secret cannot be recovered
character by character from how long the answer takes.

One more detail: the endpoint answers `200` even when the handler failed, and the error goes
to the log. Telegram retries failed deliveries with a growing delay and eventually switches
the webhook off — one bad message must not take the whole bot down.

### What to expect

The first message to the bot after an idle period takes 1–3 seconds to handle: the Python
function's cold start plus connecting to Neon. After that it is fast, while the instance is
alive. For a school diary that is tolerable, but it is not the instant response of an
ordinary bot.

Those are two independent delays rather than one, and they add up:

1. **The function's cold start.** Vercel does not keep an instance if no request has reached
   it for a while: the next request first starts the Python interpreter and imports FastAPI,
   aiogram and SQLAlchemy — and only then reaches the code.
2. **Neon goes to sleep separately from the function.** On the free plan Neon's compute node
   suspends itself on its own idle timeout, whether or not the Vercel function is alive. The
   first request after that waits for Neon to wake up — and that happens even when the
   function is already warm.

Both are shortened as far as the code can: the function is pinned to `fra1`, next to the
database (see "Why Vercel does not work 'as is'" above), and `create_all` at startup runs
only for local SQLite, never for Postgres.

### If even that is slow

Both delays are the architecture of the free path rather than a bug: serverless deliberately
keeps nothing permanently running, which is what you are not paying for. There are two ways
to remove them entirely.

**Do not let it sleep.** An external scheduler poking an endpoint every few minutes keeps
both the function and Neon awake. Vercel's own Cron on the Hobby plan will not do for this —
the minimum interval there is daily, so Neon sleeps many times between firings. You need an
external service (cron-job.org, UptimeRobot, healthchecks.io — all have a free plan with an
interval from one minute) or your own scheduled workflow in this same GitHub repository.

Where you knock matters. `/api/v1/health` does not touch the database at all — it keeps only
the function warm, while Neon still falls asleep on its own timer. For both endpoints at once
there is `/api/v1/warmup`: the same answer, with one `SELECT 1` along the way. Point the ping
at that one, every 4–5 minutes.

```bash
curl https://<your-project>.vercel.app/api/v1/warmup
```

That is extra function invocations — once every few minutes, round the clock — so it is worth
checking against Vercel's free-plan limit on invocations, if there is one when you read this.

**Do not try not to sleep.** A `~€4/mo VPS` (option 2 below) does not sleep at all, because
the process is not serverless — nothing to rewrite, the same code, just different hosting. If
the cold start bothers you more than the free plan is worth, that is a simpler answer than
maintaining an external ping for ever.

## Option 2: your own server

Nothing has to be rewritten — the code already works this way.

```bash
git clone https://github.com/lumenpearson/lessons && cd lessons
cp server/.env.example server/.env    # fill in BOT_TOKEN and OWNER_IDS
docker compose up -d --build
```

`docker-compose.yml` brings up Postgres, migrates it, and then starts the app. The bot works
through polling, so no external address and no webhook are needed at all — the server can sit
behind NAT with no public IP.

**The migration is a service of its own, and the server waits for it.** `migrate` runs
`alembic upgrade head` once against the same image and the same database and must finish
successfully (`condition: service_completed_successfully`) before `server` starts. On a
database already at the head it is a no-op that costs a second on every `up`; what it buys
is that the schema exists at all. Until this batch it did not: `app.main` calls `create_all`
only for SQLite, the image shipped neither `alembic.ini` nor `migrations/`, and so
`docker compose up` gave an empty schema, a green `/api/v1/health` — which opens no
connection and therefore cannot see this — and a failure on the first ORM read, which for
the bot is the middleware, i.e. every update at once. `server/Dockerfile` now copies both,
and `alembic` was already a runtime dependency.

A service rather than a line in the server's command, so that a failed migration stops the
stack instead of being buried in the API's log, and so that a migration can be run by hand
with `docker compose run --rm migrate`. It needs only `DATABASE_URL`: `get_settings()` is
never called there, so `BOT_TOKEN` and `OWNER_IDS` are not its business.
`GET /api/v1/warmup` is what confirms the result, and it is the endpoint that can: it says
`{"status": "ok", "schema": "0014"}` when the two agree and names both revisions when they
do not.

The app on the phone needs to reach the API. The options are a public IP with a forwarded
port, a tunnel such as Cloudflare Tunnel, or — if every pupil is on the same school
network — simply the local address.

Any VPS with 1 GB of memory will do. One class's load is a few hundred requests a day.

## The files Vercel needs

| File | What for |
| --- | --- |
| `api/index.py` | the entry point; a thin re-export of `app.main:app`, so that serverless and `uvicorn` run the same code |
| `vercel.json` | the region, the install command and the function's `maxDuration`. **There is no rewrite in it and there must not be:** with `"/(.*)" → "/api/index"` Vercel routes by the *rewritten* path, so FastAPI received a literal `/api/index`, and the health check, the client API and the Telegram webhook all answered 404 at once. Removed in PR #5 (`e99d8ca`); this line described something that was not in the file for a year — whoever puts it back will reproduce the same outage |
| `requirements.txt` | Vercel does not read `pyproject.toml` from a subdirectory; this holds `asyncpg` only, no `aiosqlite` |
| `.vercelignore` | keeps `android/`, the tests and the documentation out of the bundle |

## Which to choose

For one class and one person maintaining it, **a VPS is simpler**: fewer moving parts, no
cold starts, and no dependence on other people's free plans, which change.

**Vercel plus Neon** is justified if paying is not an option, or if there will be many
classes and you would rather not think about a server at all.

## The clock: the server has none, and GitHub's will not do

On serverless nothing runs between requests, so the morning and evening digests are sent by
whoever calls `/api/v1/cron/tick` with an `X-Cron-Secret` header. The only question is who
calls.

**The clock is an external cron, not GitHub Actions.** The `.github/workflows/reminders.yml`
workflow asked for a tick every five minutes — 288 a day — and over five days of measurement
got **6.7 a day**, in gaps of two to six and a half hours. GitHub runs schedules on a
best-effort basis, and on a private repository on the free plan that is what the effort came
to. The server survives that — it decides what to send from "what has not been sent yet
today" rather than "did the last tick fire", and it marks a digest sent before sending it, so
a failure halfway does not send it twice. What the server does not survive is the promise the
bot makes: that digests arrive within about five minutes of the stated time. On GitHub's
schedule that is six hours from true.

The workflow has not stopped asking for five minutes — on a public repository that costs
nothing, and the measurement was made on a private one, so how the schedules behave here is
yet to be seen. But its role is different now: a fallback that will notice the external clock
has stopped. The clock itself is set up by hand.

### The external cron

Anything that can do a GET with a header will do: [cron-job.org](https://cron-job.org) and
[UptimeRobot](https://uptimerobot.com) are free, so are Cloudflare Workers with
`[triggers] crons`, and on a VPS it is one line in a `systemd timer` or a `crontab`. One job,
every five minutes:

| Field | Value |
| --- | --- |
| URL | `https://your-project.vercel.app/api/v1/cron/tick` |
| Method | `GET` |
| Header | `X-Cron-Secret: <the same value as in the server's environment>` |
| Schedule | every 5 minutes |

To check by hand:

```bash
curl -fsS -H "X-Cron-Secret: $CRON_SECRET" \
  "https://your-project.vercel.app/api/v1/cron/tick"
```

The secret goes off to somebody else's service, and that is a deliberate price. `CRON_SECRET`
grants nothing but the right to call the tick: it neither reads nor changes a class's data,
and the worst an extra tick does is send what was due to be sent anyway. Keep it separate
from everything else, and change it in both places at once (the server's environment
variables and the cron job), or the digests will go silent silently.

### The fallback workflow

It needs the same two repository secrets (**Settings → Secrets and variables → Actions**):

| Secret | Value |
| --- | --- |
| `SERVER_URL` | `https://your-project.vercel.app` |
| `CRON_SECRET` | the same value as in the server's environment |

Without those secrets the workflow does not fail: it writes a notice and exits zero — or the
Actions tab would go red for everybody who cloned the repository.

The same tick also sweeps up abandoned bot dialogues and expired records of failed code
attempts — on serverless that is the only moment anything can be swept up at all. For the
sweeping, whatever GitHub deigns to run is enough; for the digests it is not, which is why
the external cron is not a nice-to-have but the condition of what the bot says.
