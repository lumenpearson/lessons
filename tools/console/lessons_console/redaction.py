"""Sanitize before crossing either the terminal or transcript boundary."""

import os
import re
from dataclasses import dataclass
from urllib.parse import quote, unquote, urlsplit

SECRET_FIELDS = frozenset({
    "bot_token", "owner_ids", "database_url", "webhook_secret", "cron_secret",
    "diary_secret", "dadata_token", "diary_proxy_url", "sentry_dsn", "github_read_token",
})
PUBLIC_FIELDS = frozenset({
    "timezone", "host", "port", "run_bot", "trusted_proxy_hops", "vercel", "webhook_path",
    "bot_username", "diary_egress", "public_base_url", "lessons_target", "lessons_streaming",
    "min_client_version",
})
SECRET_NAME = re.compile(
    r"TOKEN|SECRET|PASSWORD|PASSWD|API_KEY|CREDENTIAL|^LESSONS_KEY_|^LESSONS_KEYSTORE_|"
    r"^DATABASE_URL$|^OWNER_IDS$|^DIARY_PROXY_URL$|^SENTRY_DSN$", re.IGNORECASE
)
HEADER = re.compile(
    r"(?i)((?:Authorization\s*[:=]\s*Bearer|X-Cron-Secret\s*[:=]|"
    r"X-JWT-Token\s*[:=]|X-Telegram-Bot-Api-Secret-Token\s*[:=])\s*)[^\s,;]+"
)


@dataclass(repr=False)
class Secret:
    value: str

    def __repr__(self) -> str:
        return "<redacted>"


class Redactor:
    def __init__(self, environment: dict[str, str] | None = None):
        self._values: list[Secret] = []
        self.short_values = False
        for name, value in (os.environ if environment is None else environment).items():
            try:
                password = urlsplit(value).password if "://" in value else None
            except ValueError:
                password = None
            if SECRET_NAME.search(name) or password:
                self.register(value)
            if password:
                self.register(password)
                self.register(unquote(password))

    def register(self, value: str) -> None:
        if not value:
            return
        for form in {value, quote(value, safe=""), unquote(value)}:
            if len(form) < 8:
                self.short_values = True
            else:
                self._values.append(Secret(form))
        self._values.sort(key=lambda secret: len(secret.value), reverse=True)

    def __call__(self, line: str) -> str:
        for secret in self._values:
            line = line.replace(secret.value, "<redacted>")
        line = HEADER.sub(r"\1<redacted>", line)
        # Also cover credentials a tool reports from a source we never opened.
        return re.sub(r"(\b[a-zA-Z][a-zA-Z0-9+.-]*://[^\s/:@]+:)[^\s/@]+(@)",
                      r"\1<redacted>\2", line)
