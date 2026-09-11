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

from app.config import get_settings
from app.db import init_db


async def main() -> None:
    settings = get_settings()
    # Never print the password: a connection string usually carries one.
    target = settings.database_url.split("@")[-1] if "@" in settings.database_url else "local"
    print(f"Creating schema on {target} ...")
    await init_db()
    print("Done.")


if __name__ == "__main__":
    asyncio.run(main())
