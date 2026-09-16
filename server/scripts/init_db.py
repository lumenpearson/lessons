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
    # Never print the password: a connection string usually carries one.
    target = settings.database_url.split("@")[-1] if "@" in settings.database_url else "local"
    print(f"Creating schema on {target} ...")
    await init_db()
    found = await stamp()
    if found is None:
        print(f"Stamped alembic_version at {EXPECTED_REVISION}.")
    else:
        print(f"Left alembic_version as it was: {found}.")
    print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
