"""`docker-compose.yml`, read against what the server needs from it.

Nothing here has ever been run under Docker from this repository (there is no
Docker where it is developed), so the file is the only thing that can be
checked, and it is checked for the properties a wrong line would cost a
deployment silently rather than loudly.
"""

from __future__ import annotations

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker-compose.yml"

#: The one way the database password may appear: interpolated from the
#: environment, refusing to start without it.
_PASSWORD = re.compile(r"^\$\{POSTGRES_PASSWORD:\?[^}]*\}$")


def _services() -> dict:
    return yaml.safe_load(COMPOSE.read_text("utf-8"))["services"]


def test_the_database_password_comes_from_the_environment_and_nowhere_else():
    """#190. The file is public, so a password written into it is the password
    of every deployment that ran it as written — and `lessons:lessons` is the
    first thing anybody would try."""
    services = _services()

    assert _PASSWORD.match(services["db"]["environment"]["POSTGRES_PASSWORD"])

    urls = {
        name: service["environment"]["DATABASE_URL"]
        for name, service in services.items()
        if "DATABASE_URL" in service.get("environment", {})
    }
    assert set(urls) == {"migrate", "server"}, urls
    for name, url in urls.items():
        # Split by hand at the last `@`: the interpolation is not a password
        # `urlsplit` can parse, and that is the point — it is not a literal.
        userinfo, _, host = url.split("://", 1)[1].rpartition("@")
        user, _, password = userinfo.partition(":")
        assert user == services["db"]["environment"]["POSTGRES_USER"], name
        assert _PASSWORD.match(password), f"{name} carries a literal database password"
        assert host == "db:5432/lessons", name
