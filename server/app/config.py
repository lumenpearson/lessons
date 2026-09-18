"""Application settings, loaded from environment / .env file."""

from __future__ import annotations

from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.timezones import resolve

# The default that is right on a laptop and wrong everywhere else. Named so
# that the check below compares against the one value rather than a re-typed
# copy of it.
LOCAL_DATABASE_URL = "sqlite+aiosqlite:///./lessons.db"


class DeploymentNotConfigured(RuntimeError):
    """A deployed process is missing settings only a local run can do without.

    Raised at the door, before anything imports an engine or registers a
    route, because every one of these failures otherwise surfaces somewhere
    far from its cause: an unset ``DATABASE_URL`` dies inside SQLAlchemy with
    ``ModuleNotFoundError: aiosqlite``, an empty ``BOT_TOKEN`` makes Telegram's
    calls hit a route that was never registered, and an ``OWNER_IDS`` that
    parses to nothing looks configured and grants nobody anything. Each of
    those has cost a real deployment hours of looking in the wrong place.
    """


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    bot_token: str = ""
    # Kept as a raw string rather than list[int]: pydantic-settings would try to
    # JSON-decode a complex field, which rejects the "1,2,3" form people
    # naturally write in a .env file.
    owner_ids: str = Field(default="", alias="OWNER_IDS")
    database_url: str = LOCAL_DATABASE_URL
    timezone: str = "Europe/Moscow"
    host: str = "0.0.0.0"
    port: int = 8000
    run_bot: bool = True

    # How many reverse proxies sit in front of this app.
    #
    # Zero, the default, means none — and then forwarding headers are ignored
    # entirely. X-Forwarded-For is client-supplied text: believing it lets any
    # caller pick their own rate-limit bucket and rotate out of it on every
    # request, which is the limit not existing at all. Set this to the real
    # number of proxies and the entry that many places from the right (the one
    # the outermost trusted proxy appended) is used instead.
    trusted_proxy_hops: int = 0

    # Set by Vercel itself in every function environment. Its own
    # x-vercel-forwarded-for header is written by the platform and replaces
    # whatever the client sent, so a Vercel deployment needs no proxy
    # configuration of its own.
    vercel: str = Field(default="", alias="VERCEL")

    # Webhook mode. Serverless platforms cannot hold a long-polling loop open,
    # so there Telegram pushes updates to us instead of us pulling them.
    webhook_secret: str = ""
    webhook_path: str = "/api/v1/telegram/webhook"

    # Shared secret for the reminder tick. A serverless deployment has no
    # scheduler of its own, so an external cron calls the tick endpoint every
    # few minutes with this value in ``X-Cron-Secret``. Empty means the endpoint
    # refuses everyone: a tick anybody can trigger is a way to make the bot
    # message every subscriber on demand.
    cron_secret: str = ""

    # The bot's @username without the "@", for ``t.me/<bot>?start=link_<code>``
    # deep links. Not fetched from Telegram at runtime because that is one API
    # round trip per cold start for a value that never changes.
    bot_username: str = ""

    # Key for the one credential that has to be stored recoverably: the
    # Petersburg diary's upstream session token, which is replayed on every
    # call and so cannot be a hash like everything else here.
    #
    # Empty disables the diary outright rather than falling back to plaintext.
    # A fallback would be invisible — the feature keeps answering and the only
    # difference is a column nobody looks at — and deployments stay in that
    # state for years. Generate one with:
    #
    #     python -c "import secrets; print(secrets.token_urlsafe(48))"
    diary_secret: str = ""

    # Key for the school directory (DaData), which searches ЕГРЮЛ — the only
    # register that actually has every Russian school in it, because every
    # school is a legal entity. There is no official nationwide directory to
    # ship with the project: Рособрнадзор's open-data endpoints answer 404.
    #
    # Empty disables the search and leaves manual entry, which is the same
    # refusal-at-the-door as ``diary_secret``: a bundled snapshot would answer
    # confidently with last year's schools and nothing would say which it was.
    dadata_token: str = ""

    # Public origin of this deployment ("https://lessons.example.com"), for the
    # calendar feed URL the bot shows. Configured rather than read off a
    # request: behind Vercel the function sees an internal host, and the bot
    # builds the URL from inside a Telegram update where there is no request.
    public_base_url: str = ""

    @property
    def owner_id_list(self) -> list[int]:
        parts = self.owner_ids.replace(";", ",").split(",")
        return [int(part) for part in (p.strip() for p in parts) if part.lstrip("-").isdigit()]

    @property
    def behind_vercel(self) -> bool:
        return bool(self.vercel)

    @property
    def tz(self) -> ZoneInfo:
        """The deployment's own zone, for the handlers that have no class yet.

        Resolved rather than constructed, the same way ``SchoolClass.tz`` is.
        A typo in ``TIMEZONE`` used to be tolerated on a class's column and
        fatal in this setting: «/start» from somebody with no class raised
        ZoneInfoNotFoundError inside the handler, so the button did nothing and
        the only trace was in the server log — while a class with the same typo
        stored on it went on rendering its day. One zone name, one rule.
        """
        return resolve(self.timezone)

    @property
    def bot_enabled(self) -> bool:
        """Whether to start the long-polling loop. False on serverless."""
        return self.run_bot and bool(self.bot_token)

    @property
    def webhook_enabled(self) -> bool:
        """Whether to expose the webhook endpoint.

        Requires a secret on purpose. Without one the endpoint is unauthenticated
        and anyone who guesses the URL can forge updates from any Telegram user
        id - which, since the bot trusts that id for authorisation, means making
        themselves an editor and rewriting the timetable.
        """
        return bool(self.bot_token) and bool(self.webhook_secret)

    def deployment_problems(self) -> list[str]:
        """Settings whose default is right locally and silently wrong deployed.

        Only these. The other empty defaults in this file are deliberate and
        already refuse at their own door, in view of whoever they concern:
        ``diary_secret`` and ``dadata_token`` switch their feature off and say
        so, ``public_base_url`` makes the bot print the name of the variable
        instead of a dead link, ``bot_username`` leaves the deep link out of
        the payload, and ``cron_secret`` answers the tick with a 404 that the
        caller's own logs show in red. Turning any of those into a refusal
        would make an optional feature mandatory, which is a different
        decision than the one this method implements.

        What is listed here has no such door. Each one is a setting the
        deployment cannot work without, whose absence shows up as somebody
        else's error message, in a place that names neither the setting nor
        this project.
        """
        problems: list[str] = []

        if self.database_url == LOCAL_DATABASE_URL:
            problems.append(
                "DATABASE_URL is unset, so the local SQLite default stands. A deployment "
                "has no disk to keep that file on, and `aiosqlite` is deliberately absent "
                "from requirements.txt, so the process dies while importing app.db with "
                "`ModuleNotFoundError: No module named 'aiosqlite'` raised from inside "
                "SQLAlchemy - which names neither this setting nor this project."
            )

        if not self.bot_token:
            problems.append(
                "BOT_TOKEN is empty. The bot is this project's admin panel, and without a "
                "token there is neither polling nor a webhook: Telegram's calls reach a "
                "route that was never registered, and nothing on either side says why."
            )
        elif not self.webhook_secret:
            problems.append(
                "WEBHOOK_SECRET is empty, so the webhook is not registered - an "
                "unauthenticated one would let anyone forge an update from any Telegram "
                "id. On a deployment that is the only way updates arrive, so the bot is "
                "deaf and appears simply to ignore everyone."
            )

        if self.run_bot:
            problems.append(
                "RUN_BOT is not false. Long polling cannot outlive a response here, and "
                "Telegram hands updates to one consumer: a loop started on a cold start "
                "takes them away from the webhook that is supposed to receive them."
            )

        if self.owner_ids.strip() and not self.owner_id_list:
            problems.append(
                f"OWNER_IDS is set to {self.owner_ids.strip()!r} and no id can be read out "
                "of it. Only digits separated by commas count, and a newline is not a "
                "separator - which is exactly what Vercel's textarea invites - so the "
                "value looks configured and grants nobody anything."
            )

        try:
            ZoneInfo(self.timezone)
        except Exception:  # noqa: BLE001 - any zoneinfo failure means "not a zone"
            problems.append(
                f"TIMEZONE is {self.timezone!r}, which this build of Python has no data "
                "for. Nothing raises: `resolve` falls back on purpose, so every class "
                "created here quietly gets Europe/Moscow instead of the zone you meant."
            )

        return problems

    def disabled_features(self) -> list[str]:
        """What an empty optional setting has switched off, for the startup log.

        These are not faults - each is a documented way to run without a
        feature - but a deployment that is missing one usually did not mean to
        be, and the only other evidence is a screen in the bot that somebody
        has to reach first.
        """
        off: list[str] = []
        if not self.diary_secret:
            off.append("DIARY_SECRET is empty: the Petersburg diary is off")
        if not self.dadata_token:
            off.append("DADATA_TOKEN is empty: the school search is off, entry is manual")
        if not self.public_base_url:
            off.append(
                "PUBLIC_BASE_URL is empty: the calendar feed and the diary sign-in page "
                "have no address to offer"
            )
        if not self.bot_username:
            off.append("BOT_USERNAME is empty: the phone's «привязать» deep link is not built")
        if not self.cron_secret:
            off.append("CRON_SECRET is empty: the tick answers 404, so no digest is ever sent")
        return off


@lru_cache
def get_settings() -> Settings:
    settings = Settings()
    # Only where the platform says so about itself. Guessing "this looks like
    # production" anywhere else would eventually refuse to start on somebody's
    # laptop, which is a worse failure than the one this prevents. A VPS
    # deployment is configured by hand from docs/deploy.md and is not covered.
    if settings.behind_vercel:
        problems = settings.deployment_problems()
        if problems:
            raise DeploymentNotConfigured(
                "This deployment is not configured; see docs/deploy.md, «Переменные "
                "окружения». All of it, rather than one at a time, because finding the "
                "next one costs another deploy:\n"
                + "\n".join(f"  - {problem}" for problem in problems)
            )
    return settings
