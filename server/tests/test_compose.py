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

from app.config import Settings

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ROOT / "docker-compose.yml"

#: Settings the server container is deliberately not handed, and why. Each is
#: said in the compose file too; this is the list the test believes.
NOT_FORWARDED = {
    "VERCEL": "the platform's own; set here it would switch on Vercel's refusal to start",
    "HOST": "read only by `python -m app.main`; the image's command binds 0.0.0.0 itself",
    "PORT": "the same, and the published port is 8000 either way",
    "WEBHOOK_PATH": "read by nothing: the webhook's route is fixed in app/api/telegram.py",
}

#: ``${NAME:-default}`` — forwarded, with what an unset variable becomes.
_FORWARDED = re.compile(r"^\$\{(?P<name>[A-Z][A-Z0-9_]*):-(?P<default>[^}]*)\}$")


def _field_names() -> dict[str, str]:
    """Environment name → field name, for every setting `Settings` reads."""
    return {
        (field.alias or name).upper(): name for name, field in Settings.model_fields.items()
    }

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


def test_the_server_is_handed_every_setting_the_code_reads():
    """#191. A container sees only what the file lists, whatever `.env` holds,
    and the list stopped at `TIMEZONE`: a compose deployment had no diary, no
    tick and no calendar link however carefully it was configured, and the
    startup log said each was off without saying why."""
    handed = set(_services()["server"]["environment"])
    names = set(_field_names())

    missing = names - handed - set(NOT_FORWARDED)
    assert not missing, f"docker-compose.yml does not hand the server: {sorted(missing)}"
    # And the exceptions are real settings, not a list that outlived them.
    assert set(NOT_FORWARDED) <= names, sorted(set(NOT_FORWARDED) - names)
    assert not set(NOT_FORWARDED) & handed, sorted(set(NOT_FORWARDED) & handed)


def test_a_setting_nobody_set_reaches_the_server_as_its_own_default():
    """`${NAME:-}` does not leave a variable unset — it hands the container an
    empty string. For a string whose default is empty that is the same thing;
    for `RUN_BOT` it would be a value pydantic refuses, and the server would
    not start over a line nobody wrote. So every fallback written in the file
    has to parse to the default the setting already has."""
    fields = _field_names()
    forwarded = 0
    for name, value in _services()["server"]["environment"].items():
        match = _FORWARDED.match(str(value))
        if match is None:
            continue
        forwarded += 1
        assert match["name"] == name, f"{name} is filled from ${{{match['name']}}}"
        field = fields[name]
        parsed = getattr(Settings(**{field: match["default"]}), field)
        assert parsed == Settings.model_fields[field].default, (
            f"{name} unset becomes {match['default']!r}, which is {parsed!r}, "
            f"not the setting's own default {Settings.model_fields[field].default!r}"
        )
    assert forwarded, "no optional setting is forwarded at all"
