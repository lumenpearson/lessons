---
name: migration
description: Write and apply an Alembic revision for this project — including which side of the merge it goes on and how it is actually applied through the Neon connector. Use whenever a model change needs the live database to change.
---

# A migration, start to finish

Head is **`0016`**. Nothing after `0001` may use `create_all`.

## 1. Decide the direction

- **Additive** (a new column, table, index, nullable default) → run it **before** the merge.
  A merge to `main` deploys itself; between the deploy and a hand-run migration is a window
  where the code knows a column the database does not. That window has taken production down
  once: the first ORM read of a class raised, and for the bot that is the middleware, so
  every update died at once.
- **A `UNIQUE` or a `NOT NULL`** → run it **after** the merge. There it is the old code that
  breaks against the constraint, with an `IntegrityError` nobody catches. Write the service
  so it is correct with or without the constraint, so the window behaves like production did
  before. `0013` is this shape and says so in its own docstring.

## 2. Read the database first

Do not assume a revision is a no-op. `0011` was one; `0012` was not — all eight columns
really were nullable, and all eight held zero nulls, so it tightened them and rewrote
nothing.

## 3. Write the revision

Take the DDL **from the model**:

```python
from sqlalchemy.schema import CreateTable
from sqlalchemy.dialects import postgresql
print(CreateTable(SomeModel.__table__).compile(dialect=postgresql.dialect()))
```

That prints exactly what `create_all` would build, which is what the revision is supposed to
produce.

Guard anything SQLite cannot do:

```python
if op.get_bind().dialect.name == "sqlite":
    return
```

**The enum trap:** `SAEnum(SomeStrEnum)` stores the member **name**, not `.value`. A
`server_default` written as `.value` lands an unreadable string on every existing row and
the first ORM read raises `LookupError`. `0010` nearly shipped that.

## 4. Move `EXPECTED_REVISION`

`app/db.py:EXPECTED_REVISION` is pinned to the real head by `tests/test_schema_version.py`.
A revision that does not move it fails there.

## 5. Apply it

Through the **Neon connector**, from the session. The owner does not run
`alembic upgrade head` by hand and a session has no `DATABASE_URL`. The project is the one
**named `lessons`** — the account has two, so read the name rather than guessing an id, and
keep the id out of the repository.

It is not alembic running: it is the revision's DDL executed as **one transaction**, with
`alembic_version` stamped **in the same transaction**, last — so a half-applied revision
cannot claim to be whole.

**Say what the revision destroys before you run it.** `0006` deletes every row of
`diary_sessions` on purpose, and that is a sentence the owner needs before the transaction,
not after.

## 6. Check

`GET /api/v1/warmup` names both revisions and says whether the database is behind the code
or ahead of it. `/api/v1/health` opens no connection and cannot tell you this.
