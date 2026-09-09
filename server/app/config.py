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

    @property
    def owner_id_list(self) -> list[int]:
        parts = self.owner_ids.replace(";", ",").split(",")
        return [int(part) for part in (p.strip() for p in parts) if part.lstrip("-").isdigit()]

    @property
    def tz(self) -> ZoneInfo:
        return ZoneInfo(self.timezone)

    @property
    def bot_enabled(self) -> bool:
        return self.run_bot and bool(self.bot_token)


@lru_cache
def get_settings() -> Settings:
    return Settings()
