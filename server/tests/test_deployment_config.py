"""A deployment refuses to start rather than substituting a local default.

The expensive version of this happened in production: `DATABASE_URL` was set
for one Vercel environment and not the other, so the function fell back to the
SQLite default, and the only thing anybody saw was

    ModuleNotFoundError: No module named 'aiosqlite'

raised from inside SQLAlchemy's sqlite dialect — a message naming neither the
setting, nor the environment it was missing from, nor this project. Every case
below is a setting of that shape: right on a laptop, silently wrong deployed.

What is deliberately *not* here is as much the point. `DIARY_SECRET`,
`DADATA_TOKEN`, `PUBLIC_BASE_URL`, `BOT_USERNAME` and `CRON_SECRET` are empty
by design on a deployment that does not want those features, and each already
refuses in view of whoever it concerns. Making them fatal would turn optional
features mandatory.
"""

from __future__ import annotations

import pytest

from app.config import (
    LOCAL_DATABASE_URL,
    DeploymentNotConfigured,
    Settings,
    get_settings,
)

DEPLOYED = {
    "VERCEL": "1",
    "DATABASE_URL": "postgresql+asyncpg://u:p@host/db",
    "BOT_TOKEN": "123456:ABC",
    "WEBHOOK_SECRET": "a-secret",
    "RUN_BOT": "false",
    "OWNER_IDS": "111111111",
    "TIMEZONE": "Europe/Moscow",
}


def deployed(**overrides: str) -> Settings:
    """A correctly configured deployment, with one thing changed."""
    return Settings(**{**DEPLOYED, **overrides})


def test_a_configured_deployment_has_nothing_to_report():
    assert deployed().deployment_problems() == []


def test_an_unset_database_url_is_named_rather_than_left_to_sqlalchemy():
    """The case that cost the outage: the default is a file, and there is no disk."""
    settings = deployed(DATABASE_URL=LOCAL_DATABASE_URL)
    (problem,) = settings.deployment_problems()
    assert "DATABASE_URL" in problem
    assert "aiosqlite" in problem


def test_an_empty_bot_token_is_a_refusal_not_a_quiet_absence():
    settings = deployed(BOT_TOKEN="")
    problems = settings.deployment_problems()
    assert any("BOT_TOKEN" in problem for problem in problems)
    # And the reason it is fatal rather than merely odd: nothing else notices.
    assert settings.bot_enabled is False
    assert settings.webhook_enabled is False


def test_a_token_without_a_webhook_secret_leaves_the_bot_deaf():
    settings = deployed(WEBHOOK_SECRET="")
    (problem,) = settings.deployment_problems()
    assert "WEBHOOK_SECRET" in problem
    assert settings.webhook_enabled is False


def test_the_missing_webhook_secret_is_not_reported_twice_over_a_missing_token():
    """One cause, one line. Without a token the secret is not the reader's problem."""
    problems = deployed(BOT_TOKEN="", WEBHOOK_SECRET="").deployment_problems()
    assert sum("WEBHOOK_SECRET" in problem for problem in problems) == 0


def test_polling_left_on_is_reported_because_it_steals_the_webhook_s_updates():
    (problem,) = deployed(RUN_BOT="true").deployment_problems()
    assert "RUN_BOT" in problem


@pytest.mark.parametrize("value", ["111111111\n222222222", "abc", "  ,  ", "не число"])
def test_owner_ids_that_parse_to_nobody_are_not_mistaken_for_configured(value):
    """Vercel's value field is a textarea, and a newline separates nothing."""
    settings = deployed(OWNER_IDS=value)
    assert settings.owner_id_list == []
    (problem,) = settings.deployment_problems()
    assert "OWNER_IDS" in problem


def test_the_unreadable_owner_ids_is_described_rather_than_reproduced():
    """This message is raised before the first route is registered, so where it
    lands is the platform's build log — and `CLAUDE.md` lists `OWNER_IDS` among
    the values that never belong in one. It used to be quoted into the message
    verbatim.

    Redacting it must not cost the diagnosis, which is the reason the value was
    there in the first place: a newline is what is wrong nearly every time, and
    saying *that* is more use than printing the string it is hiding in.
    """
    value = "111111111\n222222222"
    (problem,) = deployed(OWNER_IDS=value).deployment_problems()

    assert "OWNER_IDS" in problem
    assert "<redacted>" in problem
    for id_ in ("111111111", "222222222"):
        assert id_ not in problem
    # Still enough to fix it without a second deploy.
    assert "line break" in problem


def test_an_empty_owner_ids_is_left_alone():
    """Empty is a choice: after the first owner, roles live in the database."""
    assert deployed(OWNER_IDS="").deployment_problems() == []


def test_a_timezone_python_cannot_resolve_is_reported_although_nothing_raises():
    settings = deployed(TIMEZONE="Mars/Olympus")
    (problem,) = settings.deployment_problems()
    assert "TIMEZONE" in problem
    # `resolve` swallows it on purpose, which is why this has to be caught here.
    assert settings.tz.key == "Europe/Moscow"


def test_every_problem_is_reported_in_one_go():
    """Because finding the next one costs another deploy.

    Spelled out rather than left to absence: `tests/conftest.py` puts a
    `DATABASE_URL` and `RUN_BOT` in the environment before anything imports
    the app, so an empty `Settings()` here is not the empty one a deployment
    would build.
    """
    settings = Settings(
        VERCEL="1", DATABASE_URL=LOCAL_DATABASE_URL, BOT_TOKEN="", RUN_BOT="true"
    )
    problems = settings.deployment_problems()
    assert len(problems) >= 3
    assert any("DATABASE_URL" in problem for problem in problems)
    assert any("BOT_TOKEN" in problem for problem in problems)
    assert any("RUN_BOT" in problem for problem in problems)


def test_a_local_run_is_not_a_deployment_and_keeps_its_defaults():
    """The whole check hangs on VERCEL, which the platform sets about itself.

    The default is read off the field rather than off an instance, because the
    test environment sets `DATABASE_URL` to a temporary file of its own.

    `deployment_problems` itself answers "what would be wrong if this were a
    deployment" and does not consult `VERCEL`: the gate is `get_settings`, one
    place, which is what the next test holds.
    """
    assert Settings().behind_vercel is False
    assert Settings.model_fields["database_url"].default == LOCAL_DATABASE_URL


def test_get_settings_refuses_a_misconfigured_deployment(monkeypatch):
    monkeypatch.setenv("VERCEL", "1")
    monkeypatch.setenv("DATABASE_URL", LOCAL_DATABASE_URL)
    get_settings.cache_clear()
    try:
        with pytest.raises(DeploymentNotConfigured) as raised:
            get_settings()
        assert "DATABASE_URL" in str(raised.value)
        assert "docs/deploy.md" in str(raised.value)
    finally:
        get_settings.cache_clear()


def test_get_settings_lets_a_local_run_through(monkeypatch):
    monkeypatch.delenv("VERCEL", raising=False)
    get_settings.cache_clear()
    try:
        assert get_settings().behind_vercel is False
    finally:
        get_settings.cache_clear()


@pytest.mark.parametrize(
    ("setting", "named"),
    [
        ("DIARY_SECRET", "DIARY_SECRET"),
        ("DADATA_TOKEN", "DADATA_TOKEN"),
        ("PUBLIC_BASE_URL", "PUBLIC_BASE_URL"),
        ("BOT_USERNAME", "BOT_USERNAME"),
        ("CRON_SECRET", "CRON_SECRET"),
    ],
)
def test_an_optional_setting_is_announced_rather_than_fatal(setting, named):
    settings = deployed(**{setting: ""})
    assert settings.deployment_problems() == []
    assert any(named in line for line in settings.disabled_features())


def test_a_fully_configured_deployment_announces_nothing():
    settings = deployed(
        DIARY_SECRET="k" * 32,
        DADATA_TOKEN="k",
        PUBLIC_BASE_URL="https://example.com",
        BOT_USERNAME="lessons_bot",
        CRON_SECRET="k",
    )
    assert settings.disabled_features() == []


def test_a_setting_that_is_set_but_unusable_is_still_announced_as_off():
    """This test used to assert the opposite, with `DIARY_SECRET="k"`.

    «Set» and «usable» were two different questions: the startup log asked
    plain truthiness, `crypto.cipher` asks for 32 characters after stripping
    and `dadata` asks for anything after stripping. A `DIARY_SECRET` of
    thirteen characters and a `DADATA_TOKEN` of two spaces — the exact value a
    host's environment form produces from a fat-fingered paste — therefore
    switched both features off at runtime while the log said nothing, and the
    operator, whose only diagnostic is that log, went looking somewhere else.

    That is the failure `crypto`'s own docstring says the refusal exists to
    avoid: invisible, and it stays that way for years.
    """
    settings = deployed(DIARY_SECRET="lessons-diary", DADATA_TOKEN="  ")

    # Not faults — a deployment may legitimately run without either.
    assert settings.deployment_problems() == []
    announced = settings.disabled_features()
    assert any("DIARY_SECRET" in line for line in announced)
    assert any("DADATA_TOKEN" in line for line in announced)
    # And the announcement is the truth: both features really are off.
    assert settings.diary_configured is False
    assert settings.dadata_configured is False


def test_the_shortest_usable_diary_secret_is_announced_as_on():
    """The boundary, from both sides, because this is where the two questions
    used to differ."""
    from app.config import MIN_DIARY_SECRET_LENGTH

    just_short = deployed(DIARY_SECRET="k" * (MIN_DIARY_SECRET_LENGTH - 1))
    assert just_short.diary_configured is False

    exact = deployed(DIARY_SECRET="k" * MIN_DIARY_SECRET_LENGTH)
    assert exact.diary_configured is True
    assert not any("DIARY_SECRET" in line for line in exact.disabled_features())

    # Trimmed first: padding does not buy length.
    padded = deployed(DIARY_SECRET="  " + "k" * (MIN_DIARY_SECRET_LENGTH - 1) + "  ")
    assert padded.diary_configured is False
