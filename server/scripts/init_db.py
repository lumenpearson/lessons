"""Create the schema on a brand-new database.

    python -m scripts.init_db

Prefer ``alembic upgrade head``, which does everything this does and also
records what the database has been through. This is kept for a throwaway local
database where a version table is more ceremony than the situation deserves.

It creates only what is missing and touches no data, but it cannot *change*
anything: widening a column, renaming one, adding a constraint are all invisible
to it, and a database it has quietly left half-correct looks exactly like one
that is right. That is what the migrations exist for.
"""

from __future__ import annotations

import asyncio

from sqlalchemy import text

from app.config import get_settings
from app.db import EXPECTED_REVISION, engine, init_db
from scripts import target

#: Why this refuses a database it cannot reach with a filename.
#:
#: ``create_all`` is DDL, and this is the one path in the project that runs it
#: outside a local SQLite file — the deploy path deliberately stopped, and
#: ``CLAUDE.md`` says nothing after ``0001`` may use it. It also stamps
#: ``alembic_version``, which on a database that has no row yet is a claim
#: about a history it did not witness. Its sibling ``seed_demo`` refused this
#: and it did not, which was the wrong way round: that one only adds rows.
WHAT_THIS_WOULD_DO = (
    "create_all runs DDL, and this stamps alembic_version at the revision the "
    "models happen to describe. On a real deployment the schema is alembic's, "
    "and `alembic upgrade head` is the only thing that may change it."
)

OVERRIDE = "INIT_DB_I_MEAN_IT"


async def stamp() -> str | None:
    """Record that a database built from the models is at the current head.

    ``create_all`` builds the schema the models describe, which is the schema
    the last revision produces - so a database it made is *at* that revision,
    and the only thing missing is the row saying so. Without it two things
    both lie: ``/api/v1/warmup`` calls a brand-new database «отстающей» for
    ever, and a later ``alembic upgrade head`` starts at the beginning and
    fails on the first column it is told to add to a table that has it.

    An existing row is left alone. If the version table already says
    something, this script is being run against a database that has a history,
    and overwriting it would replace what is known with what is assumed.
    """
    async with engine.begin() as conn:
        await conn.execute(
            text(
                "CREATE TABLE IF NOT EXISTS alembic_version ("
                "version_num VARCHAR(32) NOT NULL, "
                "CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num))"
            )
        )
        found = await conn.scalar(text("SELECT version_num FROM alembic_version"))
        if found is not None:
            return str(found)
        await conn.execute(
            text("INSERT INTO alembic_version (version_num) VALUES (:revision)"),
            {"revision": EXPECTED_REVISION},
        )
    return None


async def main() -> None:
    settings = get_settings()
    target.refuse_unless_local(
        settings.database_url, variable=OVERRIDE, because=WHAT_THIS_WOULD_DO
    )
    # Through `target.where`, which drops the password by splitting at the last
    # «@». This used to do it here, by hand, and print the word «local» for any
    # URL that carried no password at all — so a Neon DSN authenticating some
    # other way was announced as the developer's own laptop, by the one line
    # whose whole job is to say where the DDL is about to land.
    print(f"Creating schema on {target.where(settings.database_url)} ...")
    await init_db()
    found = await stamp()
    if found is None:
        print(f"Stamped alembic_version at {EXPECTED_REVISION}.")
    else:
        print(f"Left alembic_version as it was: {found}.")
    print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
