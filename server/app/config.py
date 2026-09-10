"""Application settings, loaded from environment / .env file."""

from __future__ import annotations

from functools import lru_cache
from zoneinfo import ZoneInfo

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


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

    # Webhook mode. Serverless platforms cannot hold a long-polling loop open,
    # so there Telegram pushes updates to us instead of us pulling them.
    webhook_secret: str = ""
    webhook_path: str = "/api/v1/telegram/webhook"

    @property
    def owner_id_list(self) -> list[int]:
        parts = self.owner_ids.replace(";", ",").split(",")
        return [int(part) for part in (p.strip() for p in parts) if part.lstrip("-").isdigit()]

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)

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
