"""#200: FastAPI's interactive docs and schema, locally and not on Vercel.

The production half is asked of the real module in a fresh interpreter, because
``app.main.app`` is built once, at import, from the settings of the process that
imported it — and this suite's process is deliberately not a Vercel one.
Reloading the module here instead would rebuild the app every later test in
the worker holds, under settings none of them expect.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import httpx
from httpx import ASGITransport

from app.main import app

SERVER = Path(__file__).resolve().parents[1]
DOC_PATHS = ("/openapi.json", "/docs", "/redoc")


async def test_a_local_server_serves_its_docs():
    transport = ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        for path in DOC_PATHS:
            assert (await client.get(path)).status_code == 200, path


#: What a Vercel deployment is started with: everything `get_settings` refuses
#: to start without, and the platform's own `VERCEL`. Nothing connects — the
#: engine is lazy and ``/api/v1/health`` opens no connection by design.
_VERCEL_ENV = {
    "VERCEL": "1",
    "DATABASE_URL": "postgresql+asyncpg://user:secret@db.invalid:5432/lessons",
    "BOT_TOKEN": "123456:not-a-real-token",
    "WEBHOOK_SECRET": "not-a-real-secret",
    "RUN_BOT": "false",
    "OWNER_IDS": "1000",
    "TIMEZONE": "Europe/Moscow",
}

_ASK_AS_VERCEL = """
import asyncio, json
import httpx
from app.main import app

async def ask():
    transport = httpx.ASGITransport(app=app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as client:
        paths = {paths!r} + ("/api/v1/health",)
        return {{path: (await client.get(path)).status_code for path in paths}}

print(json.dumps(asyncio.run(ask())))
"""


def test_a_vercel_deployment_serves_no_docs_and_still_answers():
    result = subprocess.run(
        [sys.executable, "-c", _ASK_AS_VERCEL.format(paths=DOC_PATHS)],
        cwd=str(SERVER),
        env={**os.environ, **_VERCEL_ENV},
        capture_output=True,
        text=True,
        timeout=120,
    )

    assert result.returncode == 0, result.stderr
    statuses = json.loads(result.stdout.strip().splitlines()[-1])
    # The control first: a 404 from an app that answers nothing proves nothing.
    assert statuses.pop("/api/v1/health") == 200, result.stdout
    assert statuses == dict.fromkeys(DOC_PATHS, 404), result.stdout
