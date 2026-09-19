---
name: server-migrations
description: Alembic revisions and the Neon protocol. Use whenever a model change needs a column, a constraint or a default in the live database. Knows which direction a revision migrates relative to the merge, and what each existing revision destroys.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/migrations/`. Production is at **`0013`**, which is the head.

## The chain, and what each one did

`0001` is a guarded `create_all`; **nothing after it may use `create_all`**. `0002` widens
Telegram ids to 64 bits. `0003` adds tasks/reminders/links. `0004` adds diary sessions.
`0005` adds `bell_schedules.canteen_after_index`. `0006` encrypts the diary credential —
and **deletes every row of `diary_sessions`**, on purpose — and adds the per-member diary
columns. `0007` adds the two class foreign keys `0006` left out. `0008` gives a class a
number (1–11) and cuts its year into quarters or half-years. `0009` adds the corrections a
family lays over the diary. `0010` gives a class its join mode and adds the personal connect
codes. `0011` tightens two `diary_overrides` timestamps. `0012` tightens eight more across
seven tables — and on this database it was **not** a no-op: all eight were nullable and all
eight held zero nulls. `0013` adds `uq_homework_per_subject_per_day`.

## Two rules that point in opposite directions

- **An additive revision runs BEFORE the merge that needs it.** A merge to `main` deploys
  itself; the migration is run by hand. Between them is a window where the code knows a
  column the database does not, and that window has already taken production down: the new
  columns on `classes` landed first, nothing failed at startup, and the first ORM read of a
  class did — which for the bot is the middleware, so every update died.
- **A `UNIQUE` or a `NOT NULL` runs AFTER the merge.** There it is the *old* code that
  breaks against the constraint, with an `IntegrityError` nobody catches where a moment
  earlier there was a duplicate. `0013` is that shape, says so in its own docstring, and
  `services/homework.py` is written to be correct with or without it so the window in
  between behaves exactly like production did before.

Read the database before either. `0012` turned out to be a real fix rather than the no-op
`0011` was, because the columns really were nullable there.

## How a revision is actually applied here

Through the Neon connector, from the session. The owner does not run `alembic upgrade head`
by hand, and a session has no `DATABASE_URL`. The project is the one **named `lessons`** on
the Neon MCP server — the account has two, so read the name rather than guessing an id, and
keep the id out of the repository. `0005` through `0013` were all applied that way.

It is not alembic running. It is the revision's DDL executed as one transaction with
`alembic_version` stamped in the same transaction, so:

1. **Take the DDL from the model**, do not write it out:
   `CreateTable(...).compile(dialect=postgresql.dialect())` prints exactly what `create_all`
   would build, which is what the revision is supposed to produce.
2. **Read the database's state first and stamp it last**, so a half-applied revision cannot
   claim to be whole.
3. **Say what the revision destroys before running it.** `0006` deletes every row of
   `diary_sessions`; that is a sentence the owner needs before the transaction, not after.

## The enum trap

`SAEnum(SomeStrEnum)` stores the member **name**. A `server_default` written as `.value` is
a string the ORM cannot read back — a `LookupError` in the bot's middleware, i.e. every
update at once. `0010` nearly shipped exactly that; `tests/test_join_modes.py` holds all
three sides of it.

## SQLite

SQLite has no `ALTER` for constraints. `0011`, `0012` and `0013` return early on
`op.get_bind().dialect.name == "sqlite"`; lifespan `create_all` covers local SQLite anyway.

## Gates

`tests/test_schema_version.py` pins `app/db.py:EXPECTED_REVISION` to the real head — a new
revision that does not move it fails there. Then the usual three.
