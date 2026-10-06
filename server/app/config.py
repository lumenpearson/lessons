"""Application settings, loaded from environment / .env file."""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, datetime
from functools import lru_cache
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo

from pydantic import Field, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

from app.timezones import resolve

# The default that is right on a laptop and wrong everywhere else. Named so
# that the check below compares against the one value rather than a re-typed
# copy of it.
LOCAL_DATABASE_URL = "sqlite+aiosqlite:///./lessons.db"


#: Shortest ``DIARY_SECRET`` the diary will run on.
#:
#: `crypto` hashes the secret rather than using it raw, so any string of
#: sufficient length works — and «sufficient» has to be stated somewhere both
#: the cipher and the startup log can read it.
MIN_DIARY_SECRET_LENGTH = 32


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


def _describe_owner_ids(raw: str) -> str:
    """``OWNER_IDS`` in the terms that debug it, without reproducing it.

    Separate from the message so that the one rule this obeys - say the shape,
    never the value - is in one place and can be read at a glance. Nothing it
    returns can be assembled back into an id: a length, a count of pieces and
    two yes/no answers.
    """
    value = raw.strip()
    pieces = [piece for piece in value.replace(";", ",").split(",") if piece.strip()]
    # Commas and semicolons both, because `owner_id_list` folds the one into
    # the other before it splits - describing the value by a rule the parser
    # does not use would send the reader looking in the wrong place.
    described = f"{len(value)} characters in {len(pieces)} piece(s) between separators"
    if "\n" in value or "\r" in value:
        described += ", with a line break inside it"
    if not any(character.isdigit() for character in value):
        described += ", and not a digit anywhere in it"
    return described


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

    # Key for the school directory (DaData), which searches the ЕГРЮЛ company
    # register — the only
    # register that actually has every Russian school in it, because every
    # school is a legal entity. There is no official nationwide directory to
    # ship with the project: the education watchdog's open-data endpoints answer 404.
    #
    # Empty disables the search and leaves manual entry, which is the same
    # refusal-at-the-door as ``diary_secret``: a bundled snapshot would answer
    # confidently with last year's schools and nothing would say which it was.
    dadata_token: str = ""

    # An HTTP proxy for the Petersburg diary's requests, and only theirs (#334).
    # The city's network drops connections from outside Russia (#235), and the
    # whole server cannot move there, because Telegram is blocked from Russian
    # data centres. So the API stays where it is and this one upstream goes
    # through a small Russian VPS, as a ``CONNECT`` tunnel. TLS stays end to
    # end, so the proxy sees the diary's host name and never the credential.
    #
    # ``http://user:password@host:port``. It may carry a password, so it is
    # never logged, and the startup line names the setting without quoting it.
    # Empty means direct, which is what it always was.
    diary_proxy_url: str = ""

    # Public origin of this deployment ("https://lessons.example.com"), for the
    # calendar feed URL the bot shows. Configured rather than read off a
    # request: behind Vercel the function sees an internal host, and the bot
    # builds the URL from inside a Telegram update where there is no request.
    public_base_url: str = ""

    # The DSN of a Sentry project, for errors with their place and a sample of
    # timings (app/observability.py). Empty sends nothing and imports nothing
    # of Sentry, and the startup log says it is off; it is never a reason to
    # refuse to start. It names a project anybody could send events to, so it
    # lives where the other secrets do.
    sentry_dsn: str = ""

    # A fine-grained GitHub token with read-only access to public repositories
    # and no other permission, for the deploy check's read of `main`'s head
    # (services/health.py). With it the request is authorized: an unchanged
    # `main`'s 304 costs nothing against GitHub's rate limit, and the ceiling
    # is 5,000 requests an hour instead of the sixty shared by every anonymous
    # caller on Vercel's egress address. Empty means GitHub is asked
    # anonymously, which the deploy check already treats as merely unknown
    # rather than failing.
    #
    # Not GITHUB_TOKEN: GitHub Actions and plenty of tools reserve that name
    # for a token with write access, and this one must never be that.
    github_read_token: str = ""

    # The oldest v2 client this server still answers, as the APK's versionCode
    # in its `X-Lessons-Client` header (docs/specs/2026-10-05-server-v2-design.md,
    # decision 9). Empty, or 0, means no minimum. With one set, a present
    # version below it is refused with CLIENT_TOO_OLD and the app asks to be
    # updated; a request with no header is never refused, so a third-party
    # client or a `curl` is not an old APK. Optional on purpose: not in
    # `deployment_problems`, because no feature is off without it.
    min_client_version: int = Field(default=0, ge=0)

    @field_validator("min_client_version", mode="before")
    @classmethod
    def _empty_is_no_minimum(cls, value: object) -> object:
        """``MIN_CLIENT_VERSION=`` is how `.env.example` shows a setting left
        off, and pydantic would refuse an empty string for an integer — at
        import, taking v1 down with it. Empty means zero, as the comment says."""
        if isinstance(value, str) and not value.strip():
            return 0
        return value

    @property
    def owner_id_list(self) -> list[int]:
        # `isdecimal`, not `isdigit`: they differ on exactly the characters
        # `int()` refuses. «²» is a digit by `isdigit` and not a decimal, so the
        # filter waved it through and `int('²')` raised — out of the property
        # that `deployment_problems` calls, which is the mechanism whose whole
        # job is to answer with a tidy list of what is wrong instead of
        # throwing. `OWNER_IDS=²` therefore took down the one thing meant to
        # explain it.
        parts = self.owner_ids.replace(";", ",").split(",")
        return [
            int(part) for part in (p.strip() for p in parts) if part.lstrip("-").isdecimal()
        ]

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

        # Stripped before asking, because «set» and «usable» have to be the
        # same question - the same rule the optional settings below already
        # follow. A value of one space is truthy and not equal to the default,
        # so it used to walk straight past this gate and die at import inside
        # SQLAlchemy with `Could not parse SQLAlchemy URL`, which names neither
        # this setting nor this project. That is the shape of the outage this
        # whole method exists to prevent, one step to the left.
        if self.database_url.strip() in ("", LOCAL_DATABASE_URL):
            problems.append(
                "DATABASE_URL is unset or empty, so the local SQLite default stands. A "
                "deployment has no disk to keep that file on, and `aiosqlite` is "
                "deliberately absent from requirements.txt, so the process dies while "
                "importing app.db with `ModuleNotFoundError: No module named 'aiosqlite'` "
                "raised from inside SQLAlchemy - which names neither this setting nor "
                "this project."
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
            # The value is described, never quoted. This message is raised
            # before the first route is registered, so where it lands is the
            # platform's build log - and OWNER_IDS is one of the values that
            # never belongs in one. What a reader actually needs in order to
            # fix it is not the string, it is the shape of the string: how
            # long it is, how many pieces the separators cut it into, and
            # whether there is a newline in it, which is the whole diagnosis
            # nearly every time.
            problems.append(
                f"OWNER_IDS is set to <redacted> ({_describe_owner_ids(self.owner_ids)}) "
                "and no id can be read out of it. Only digits separated by commas count, "
                "and a newline is not a separator - which is exactly what Vercel's "
                "textarea invites - so the value looks configured and grants nobody "
                "anything. The value itself is deliberately not repeated here: it would "
                "be repeated into the build log."
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

    @property
    def diary_secret_value(self) -> str:
        """``DIARY_SECRET`` as it will actually be used.

        Trimmed, because a value pasted into a host's environment form arrives
        with a trailing newline often enough that «set» and «usable» have to be
        the same question.
        """
        return self.diary_secret.strip()

    @property
    def diary_configured(self) -> bool:
        """Whether the diary can run — the question `crypto.cipher` asks.

        It lives here, and not only in `crypto`, because `disabled_features`
        has to ask the same one. While it asked a *different* one — plain
        truthiness — a `DIARY_SECRET` of thirteen characters was announced as
        configured and refused at the door: `/diary/signin` answered 503, the
        login answered 503, and the startup log, which is the operator's only
        diagnostic, said nothing at all about the setting that was set.
        """
        return len(self.diary_secret_value) >= MIN_DIARY_SECRET_LENGTH

    @property
    def dadata_token_value(self) -> str:
        """``DADATA_TOKEN`` as it will actually be sent; see above."""
        return self.dadata_token.strip()

    @property
    def github_read_token_value(self) -> str:
        """``GITHUB_READ_TOKEN`` as it will actually be sent; see above."""
        return self.github_read_token.strip()

    @property
    def dadata_configured(self) -> bool:
        """Whether the school search has a key — the question `dadata` asks."""
        return bool(self.dadata_token_value)

    @property
    def diary_proxy(self) -> str | None:
        """``DIARY_PROXY_URL`` if the Petersburg client can use it, else ``None``.

        An unusable value is treated as unset rather than raised: httpx refuses
        an unknown scheme when the client is built, and that would take the
        whole diary down where today it merely cannot reach its upstream. The
        startup log says which of the two it is (``disabled_features``).
        """
        value = self.diary_proxy_url.strip()
        if not value:
            return None
        parts = urlsplit(value)
        try:
            # Reading the port is what checks it: ``urlsplit`` parses lazily,
            # and «host:port» with a word for a port raises only here.
            _ = parts.port
        except ValueError:
            return None
        if parts.scheme not in ("http", "https") or not parts.hostname:
            return None
        return value

    @property
    def sentry_configured(self) -> bool:
        """Whether errors go to Sentry - the question ``app.main`` and the
        bot's error handler ask before they import anything of it."""
        return bool(self.sentry_dsn.strip())

    def disabled_features(self) -> list[str]:
        """What an empty optional setting has switched off, for the startup log.

        These are not faults - each is a documented way to run without a
        feature - but a deployment that is missing one usually did not mean to
        be, and the only other evidence is a screen in the bot that somebody
        has to reach first.
        """
        off: list[str] = []
        if not self.diary_configured:
            off.append("DIARY_SECRET is unusable: the Petersburg diary is off")
        if not self.dadata_configured:
            off.append("DADATA_TOKEN is unusable: the school search is off, entry is manual")
        # Only an unusable value is announced. Empty is not a feature switched
        # off but a route: direct is right for a deployment inside Russia, and
        # a fully configured one must still announce nothing. Never the value:
        # it may carry the proxy's password.
        if self.diary_proxy is None and self.diary_proxy_url.strip():
            off.append(
                "DIARY_PROXY_URL is set but unusable (it must be an http:// or https:// "
                "address with a host): the Petersburg diary is called directly"
            )
        if not self.public_base_url:
            off.append(
                "PUBLIC_BASE_URL is empty: the calendar feed and the diary sign-in page "
                "have no address to offer"
            )
        if not self.bot_username:
            off.append("BOT_USERNAME is empty: the phone's «привязать» deep link is not built")
        if not self.cron_secret:
            off.append("CRON_SECRET is empty: the tick answers 404, so no digest is ever sent")
        # Only on Vercel: off this platform nothing calls the deploy check at
        # all, so an empty token here is not a deployment that forgot
        # anything. Never in `deployment_problems` either way - the check
        # already reads an anonymous GitHub as merely unknown, never failing.
        if self.behind_vercel and not self.github_read_token_value:
            off.append(
                "GITHUB_READ_TOKEN is empty: the deploy check asks GitHub anonymously, "
                "sharing sixty requests an hour"
            )
        if not self.sentry_configured:
            off.append("SENTRY_DSN is empty: errors and request timings are not sent to Sentry")
        return off


#: When this process first loaded its settings, which every entry point does
#: before anything else: on Vercel, the cold start of this function instance.
#: «📊 Проект» shows how long ago that was, so a cold start reads as minutes.
STARTED_AT = datetime.now(UTC).replace(tzinfo=None)


@dataclass(frozen=True)
class Deployment:
    """What Vercel says about the deployment this process belongs to.

    Its System Environment Variables, read from the process's environment and
    never from ``.env``: none of them is configuration. A value the platform
    states about itself has no business in the file people copy, for the
    reason ``VERCEL`` is not there (``server/.env.example``), and off Vercel
    every field is empty. They reach a function only while the project's
    «Automatically expose System Environment Variables» is on, which is
    Vercel's default (``docs/deploy.md``).
    """

    #: ``VERCEL_ENV``: ``production``, ``preview`` or ``development``.
    environment: str
    #: ``VERCEL_GIT_COMMIT_SHA``: the commit this deployment was built from.
    commit: str
    #: ``owner/slug``, from ``VERCEL_GIT_REPO_OWNER`` and
    #: ``VERCEL_GIT_REPO_SLUG``; empty unless both are there. Read rather than
    #: written down, so that a fork's deployment asks about its own ``main``.
    repository: str
    #: ``VERCEL_REGION``: where this function runs, ``fra1`` here.
    region: str


def deployment(environ: Mapping[str, str] | None = None) -> Deployment:
    """The running deployment as Vercel describes it; see :class:`Deployment`."""
    env = os.environ if environ is None else environ

    def read(name: str) -> str:
        return env.get(name, "").strip()

    owner, slug = read("VERCEL_GIT_REPO_OWNER"), read("VERCEL_GIT_REPO_SLUG")
    return Deployment(
        environment=read("VERCEL_ENV"),
        commit=read("VERCEL_GIT_COMMIT_SHA"),
        repository=f"{owner}/{slug}" if owner and slug else "",
        region=read("VERCEL_REGION"),
    )


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
                "This deployment is not configured; see docs/deploy.md, «Secrets». "
                "All of it, rather than one at a time, because finding the "
                "next one costs another deploy:\n"
                + "\n".join(f"  - {problem}" for problem in problems)
            )
    return settings
