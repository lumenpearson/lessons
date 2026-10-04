"""The API's cold start does not import aiogram (#272).

A serverless deployment imports `app.main` on every cold start, before the
first response, whatever the request. aiogram costs about two and a half
seconds of that (`app/di.py` measured it), so `app/main.py`, `app/di.py`,
`app/api/cron.py` and `app/api/telegram.py` each keep the bot's import out of
their top level. Nothing held that: one top-level `from app.bot…` in an
endpoint, or a service reaching the bot through a neutral module, would put
the seconds back, and every other test would still pass, because this suite's
own process imports aiogram for the bot tests anyway.

So it is asked of a fresh interpreter — the only place the answer means
anything — in both configurations a deployment can be in: the webhook
unmounted, as this suite and a local `RUN_BOT=false` run; and mounted, as
every Vercel deployment is, because `get_settings` refuses to start one
without `BOT_TOKEN` and `WEBHOOK_SECRET`. A failure names the `app` modules
holding something of aiogram's, which is where to look.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path

import pytest

SERVER = Path(__file__).resolve().parents[1]

#: Imports one module and prints, as JSON, every aiogram module left loaded
#: and every `app` module holding an aiogram object or module.
_PROBE = """
import json, sys, types
import MODULE


def from_aiogram(value):
    try:
        name = value.__name__ if isinstance(value, types.ModuleType) else value.__module__
    except Exception:
        return False
    return isinstance(name, str) and name.partition(".")[0] == "aiogram"


loaded = sorted(name for name in sys.modules if name.partition(".")[0] == "aiogram")
holders = sorted(
    name
    for name, module in list(sys.modules.items())
    if module is not None
    and name.partition(".")[0] == "app"
    and any(from_aiogram(value) for value in vars(module).values())
)
print(json.dumps({"aiogram": loaded, "holders": holders}))
"""

#: The suite's own settings (`conftest.py`): no token, so no webhook and no bot.
_LOCAL = {"BOT_TOKEN": "", "WEBHOOK_SECRET": "", "RUN_BOT": "false"}

#: What a Vercel deployment is started with — the set `tests/test_api_docs.py`
#: uses, written out again because a test module may not import another
#: (`test_test_imports.py`). Nothing connects: the engine is lazy.
_VERCEL = {
    "VERCEL": "1",
    "DATABASE_URL": "postgresql+asyncpg://user:secret@db.invalid:5432/lessons",
    "BOT_TOKEN": "123456:not-a-real-token",
    "WEBHOOK_SECRET": "not-a-real-secret",
    "RUN_BOT": "false",
    "OWNER_IDS": "1000",
    "TIMEZONE": "Europe/Moscow",
}


def _import_in_a_fresh_interpreter(module: str, settings: dict[str, str]) -> dict[str, list[str]]:
    # `VERCEL` dropped first, so the local case is local even in a shell that
    # happens to carry it; the Vercel case sets it again.
    env = {name: value for name, value in os.environ.items() if name != "VERCEL"}
    result = subprocess.run(
        [sys.executable, "-c", _PROBE.replace("MODULE", module)],
        cwd=str(SERVER),
        env={**env, **settings},
        capture_output=True,
        text=True,
        timeout=120,
    )
    assert result.returncode == 0, result.stderr
    return json.loads(result.stdout.strip().splitlines()[-1])


@pytest.mark.parametrize("settings", [_LOCAL, _VERCEL], ids=["webhook-unmounted", "vercel"])
def test_importing_the_api_leaves_aiogram_out(settings):
    found = _import_in_a_fresh_interpreter("app.main", settings)

    assert found["aiogram"] == [], (
        f"importing app.main loaded {len(found['aiogram'])} aiogram modules; "
        f"held by {found['holders']}"
    )


def test_the_probe_sees_aiogram_where_it_is():
    """Held here rather than trusted: a probe that could not see aiogram would
    pass the test above for ever. `app.bot.keyboards` imports it at the top."""
    found = _import_in_a_fresh_interpreter("app.bot.keyboards", _LOCAL)

    assert "aiogram" in found["aiogram"]
    assert "app.bot.keyboards" in found["holders"]
