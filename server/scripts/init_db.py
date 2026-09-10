"""Create the schema. Run once against a fresh database.

    python -m scripts.init_db

Why this exists as a command rather than only running at startup: FastAPI's
lifespan is what creates tables in a long-running deployment, and a serverless
platform may never invoke lifespan at all. Relying on it there produces a first
request that fails with "no such table" and no obvious cause.

Safe to re-run: it creates only what is missing and touches no data. It is not a
migration tool - the first incompatible schema change needs Alembic.
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
