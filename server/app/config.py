"""Application settings, loaded from environment / .env file."""

from __future__ import annotations

from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.timezones import resolve


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    bot_token: str = ""
    # Kept as a raw string rather than list[int]: pydantic-settings would try to
    # JSON-decode a complex field, which rejects the "1,2,3" form people
    # naturally write in a .env file.
    owner_ids: str = Field(default="", alias="OWNER_IDS")
    database_url: str = "sqlite+aiosqlite:///./lessons.db"
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


@lru_cache
def get_settings() -> Settings:
    return Settings()
