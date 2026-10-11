"""Test fixtures, and the doubles more than one test module needs.

The environment is configured *before* any app module is imported, because
``app.db`` builds its engine at import time from the cached settings.

**This is the only place a helper may be shared from.** A test module cannot
import another one — ``test_test_imports.py`` says why at length — and it
cannot import this file either: pytest loads ``conftest.py`` by path, so
``import conftest`` resolves under the default ``prepend`` import mode and
raises ``ModuleNotFoundError`` under ``importlib``, exactly like the sibling
import it would be replacing. So the doubles below are handed out as
*fixtures*, which pytest injects by name and which no import mode can break.

The fixtures that return a class are named in CapWords on purpose. They are
classes, a test body constructs them as classes, and naming them anything else
would have meant editing a thousand call sites to say `fakes.Callback(...)`.
"""

from __future__ import annotations

import contextlib
import json
import os
import re
import tempfile
from collections.abc import AsyncIterator, Awaitable, Callable, Iterator
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest
from sqlalchemy import event

_TMP_DIR = Path(tempfile.mkdtemp(prefix="lessons-tests-"))

# The suite runs on SQLite, and production runs on PostgreSQL, so everything
# the two disagree about — row locks, savepoints, cascades, how a timestamp
# compares — was tested on the one nobody deploys. LESSONS_TEST_DATABASE_URL
# names a PostgreSQL server for the whole run instead (docs/build.md, «The
# suite on PostgreSQL»). Every test begins by emptying every table, so a URL
# that reaches anything but this machine is refused before a single import:
# a typo'd production DSN would otherwise be wiped once per test.
_LOCAL_HOSTS = frozenset({"localhost", "127.0.0.1", "::1"})


def _postgres_for_this_worker(raw: str) -> str:
    """This worker's own database on a local PostgreSQL, made if missing.

    One database per xdist worker, because two workers truncating one set of
    tables would empty each other's rows mid-test.
    """
    import asyncio

    import asyncpg
    from sqlalchemy.engine import make_url

    url = make_url(raw)
    if not url.drivername.startswith("postgresql"):
        raise RuntimeError("LESSONS_TEST_DATABASE_URL must be a postgresql:// URL")
    if url.host not in _LOCAL_HOSTS:
        raise RuntimeError(
            "LESSONS_TEST_DATABASE_URL must point at this machine (localhost, "
            "127.0.0.1 or ::1): the suite empties every table before every test"
        )
    worker = os.environ.get("PYTEST_XDIST_WORKER", "main")
    name = f"{url.database}_{worker}"

    async def create() -> None:
        conn = await asyncpg.connect(
            user=url.username,
            password=url.password,
            host=url.host,
            port=url.port or 5432,
            database=url.database,
        )
        try:
            if not await conn.fetchval("SELECT 1 FROM pg_database WHERE datname = $1", name):
                await conn.execute(f'CREATE DATABASE "{name}"')
        finally:
            await conn.close()

    asyncio.run(create())
    return url.set(drivername="postgresql+asyncpg", database=name).render_as_string(
        hide_password=False
    )


_POSTGRES = os.environ.get("LESSONS_TEST_DATABASE_URL", "")
os.environ["DATABASE_URL"] = (
    _postgres_for_this_worker(_POSTGRES)
    if _POSTGRES
    else f"sqlite+aiosqlite:///{_TMP_DIR / 'test.db'}"
)
os.environ["RUN_BOT"] = "false"
os.environ["BOT_TOKEN"] = ""
os.environ["OWNER_IDS"] = "1000"
os.environ["TIMEZONE"] = "Europe/Moscow"
# The diary refuses to run without a key, on purpose (app/crypto.py). Tests
# that exercise it therefore have to configure one; that the *absence* of one
# switches the feature off is itself a test, in test_diary_crypto.py.
os.environ["DIARY_SECRET"] = "test-secret-not-a-real-one-0123456789abcdef"
# Whatever the shell holds: the suite reports to nobody's Sentry. A test that
# wants it on starts it in a fresh interpreter (test_observability.py).
os.environ["SENTRY_DSN"] = ""
# Whatever the shell holds: the suite asks nobody's DaData. v2's gate test and
# no-echo sweep call every served method, the school searches included, and a
# key exported in the shell would send a password-shaped query to the real
# directory. A test that wants the directory configures one and replaces it.
os.environ["DADATA_TOKEN"] = ""
# Whatever the shell holds: the suite is no host and streams nothing. The build
# console runs the host and this suite on one machine, and a LESSONS_STREAMING
# exported for the one would attach the bus here, so that every WatchClass the
# gate test opens through httpx's ASGI transport — which returns only when the
# answer ends — would wait for ever; a LESSONS_TARGET would make the suite a
# deployment, which get_settings refuses on the first import. Set empty rather
# than removed, because Settings reads server/.env as well and the environment
# wins over the file: a marker written there would otherwise still count. A
# test that wants the bus attaches it (test_watch.py, test_v2_watch.py).
os.environ["LESSONS_STREAMING"] = "false"
os.environ["LESSONS_TARGET"] = ""

from app.db import Base, SessionLocal, engine  # noqa: E402
from app.models import (  # noqa: E402
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)


def pytest_configure(config: pytest.Config) -> None:
    """Refuse to test another checkout's code (#312).

    `tests/` has no `__init__.py`, so the default import mode puts `tests/` on
    the path rather than `server/`, and the bare `pytest` CI runs adds no
    current directory either. `app` therefore comes from whatever the venv has
    installed — and the setup installs it editable, from the checkout the venv
    was made in. A worktree borrowing the main checkout's venv then runs its own
    tests against the main checkout's `app`: a new test fails and is caught, but
    a test saying nothing changed passes against the old code, and «green
    locally» is about another tree. Raised here, before any test runs, rather
    than at import, so the message is pytest's own «ERROR:» line.

    Two questions, because either alone lets one case through. `app` must be
    this tree's `server/app` exactly, not merely somewhere under `server/`: a
    non-editable install into `server/.venv` lives under it too, and is a copy
    that no edit reaches. And when the package is installed editable, the
    install must be this tree's: under `python -m pytest` the current directory
    supplies `app`, but a borrowed install's finder still answers for any module
    of `app` or `scripts` this tree lacks, so a module deleted on a branch would
    be found in the other checkout and its last caller would still pass.
    """
    import json
    from importlib import metadata
    from urllib.parse import urlparse
    from urllib.request import url2pathname

    import app

    tree = Path(__file__).resolve().parents[1]
    imported = Path(app.__file__).resolve().parent
    problem = None
    if imported != tree / "app":
        problem = f"`app` was imported from {imported}"
    else:
        # Every record, not the first: under `python -m pytest` the current
        # directory comes first on the path, and the build leaves
        # `lessons_server.egg-info` there with no install record in it, which
        # would hide the venv's real one.
        for found in metadata.distributions(name="lessons-server"):
            record = found.read_text("direct_url.json")
            if not record:
                continue
            install = json.loads(record)
            if not install.get("dir_info", {}).get("editable"):
                continue
            source = Path(url2pathname(urlparse(install["url"]).path)).resolve()
            if source != tree:
                problem = f"the venv's editable install of lessons-server is {source}"
                break
    if problem is not None:
        raise pytest.UsageError(
            f"These tests are in {tree}, but {problem}, so they would run against another "
            "checkout's code. Make a venv in this tree's server/ (python -m venv .venv, then "
            'pip install -r ../requirements.txt -e ".[dev]") and run pytest from it, and '
            "unset a PYTHONPATH that points elsewhere."
        )


#: Why a test marked ``host`` did not run.
HOST_ABSENT = (
    "needs a running host target: LESSONS_HOST_URL is unset, as everywhere but "
    "CI's «Host» job (docs/specs/2026-10-05-server-v2-design.md, decision 14)"
)


def pytest_collection_modifyitems(config: pytest.Config, items: list[pytest.Item]) -> None:
    """Skip the tests marked ``host`` unless a host is running to be asked.

    They talk to ``python -m app.host`` over the network (``test_host_live.py``):
    with no address there is nothing to ask, and a test that failed for that
    would only teach people to ignore it. Skipped rather than deselected, so
    the ordinary run's summary says they exist and why they did not run.
    """
    if os.environ.get("LESSONS_HOST_URL"):
        return
    absent = pytest.mark.skip(reason=HOST_ABSENT)
    for item in items:
        if item.get_closest_marker("host") is not None:
            item.add_marker(absent)


# The tables the PostgreSQL run has built so far. A module imported later can
# register one more (the FSM storage's table lives beside its storage, not in
# models.py), so the set is compared before every test rather than assumed.
_built_tables: frozenset[str] = frozenset()


@pytest.fixture(autouse=True)
async def fresh_database() -> AsyncIterator[None]:
    global _built_tables
    if engine.dialect.name == "postgresql" and _built_tables == frozenset(Base.metadata.tables):
        # Building twenty-odd tables over a socket before each of three
        # thousand tests is the slowest part of a run; emptying them is not.
        # The lock timeout turns a session a previous test leaked, still
        # holding a row, into that test's error rather than a run that hangs.
        tables = ", ".join(f'"{table.name}"' for table in Base.metadata.sorted_tables)
        async with engine.begin() as conn:
            await conn.exec_driver_sql("SET LOCAL lock_timeout = '10s'")
            await conn.exec_driver_sql(f"TRUNCATE {tables} RESTART IDENTITY CASCADE")
    else:
        async with engine.begin() as conn:
            await conn.run_sync(Base.metadata.drop_all)
            await conn.run_sync(Base.metadata.create_all)
        _built_tables = frozenset(Base.metadata.tables)
    yield
    if engine.dialect.name == "postgresql":
        # Each test runs on an event loop of its own, and an asyncpg connection
        # belongs to the loop that opened it: one pooled past its test is a
        # «Future attached to a different loop» in the next. Closing the pool
        # here, still on the test's loop, closes them properly.
        await engine.dispose()


@pytest.fixture
async def session() -> AsyncIterator:
    async with SessionLocal() as db:
        yield db


@pytest.fixture
async def school_class(session) -> SchoolClass:
    """A class with the default bells and a three-lesson Monday."""
    bells = BellSchedule(class_id=0, name="Обычное")
    klass = SchoolClass(name="9А", school="Школа № 1", join_code="TEST42")
    session.add(klass)
    await session.flush()

    bells.class_id = klass.id
    session.add(bells)
    await session.flush()

    for index, starts_at, ends_at in DEFAULT_BELLS:
        session.add(
            BellPeriod(schedule_id=bells.id, index=index, starts_at=starts_at, ends_at=ends_at)
        )
    klass.bell_schedule_id = bells.id

    # Monday (weekday 1)
    for index, subject, room in [(1, "Алгебра", "214"), (2, "Физика", "305"), (3, "История", None)]:
        session.add(
            TimetableEntry(
                class_id=klass.id,
                weekday=1,
                index=index,
                subject_name=subject,
                room=room,
                parity=WeekParity.ANY,
            )
        )
    await session.commit()
    return klass


# --------------------------------------------------------------------------
# The bot's doubles
#
# Two families, and they are not interchangeable — which is why they were two
# classes under one name in two files before they came here, and why they keep
# two names now.
#
# The *reply* family stands in for a handler that answers with a fresh message:
# what it records is the text of every reply, in order. The *card* family
# stands in for a handler that edits one card in place: what it records is the
# text **and the keyboard** of every draw, because on those screens the two
# disagreeing is the defect worth catching.
# --------------------------------------------------------------------------


@dataclass
class _ReplyState:
    """Stand-in for aiogram's FSMContext."""

    data: dict[str, Any] = field(default_factory=dict)
    state: Any = None
    cleared: bool = False

    async def get_data(self) -> dict[str, Any]:
        return dict(self.data)

    async def update_data(self, **kwargs: Any) -> dict[str, Any]:
        self.data.update(kwargs)
        return dict(self.data)

    async def set_state(self, state: Any) -> None:
        self.state = state

    async def clear(self) -> None:
        self.cleared = True
        self.data.clear()
        self.state = None


@dataclass
class _ReplyMessage:
    text: str | None = ""
    user_id: int = 42
    replies: list[str] = field(default_factory=list)

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str, **_: Any) -> None:
        self.replies.append(text)

    @property
    def last(self) -> str:
        return self.replies[-1]


@dataclass
class _ReplyCallback:
    user_id: int = 42
    message: _ReplyMessage = field(default_factory=_ReplyMessage)
    answers: list[tuple[str | None, bool]] = field(default_factory=list)

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str | None = None, show_alert: bool = False, **_: Any) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


class _ReplyEditable(_ReplyMessage):
    async def edit_text(self, text: str, **_: Any) -> None:
        self.replies.append(text)


class _MarkupEditable(_ReplyEditable):
    """Keeps the keyboard as well as the text.

    The access page says which mode the class is in twice — in a sentence and
    on the button that changes it — and the two disagreeing is exactly the bug
    worth catching.
    """

    markup: Any = None

    async def edit_text(self, text: str, **kwargs: Any) -> None:
        self.markup = kwargs.get("reply_markup")
        self.replies.append(text)


class _ContactMessage(_ReplyMessage):
    """A shared contact, and the keyboard each reply was sent with."""

    def __init__(self, phone: str, user_id: int = 555) -> None:
        super().__init__(text=None, user_id=user_id)
        self.contact = SimpleNamespace(user_id=user_id, phone_number=phone)
        self.markups: list[object] = []

    async def answer(self, text: str, reply_markup=None, **_) -> None:
        self.replies.append(text)
        self.markups.append(reply_markup)


class _CardEditable:
    """A message that remembers the last text and keyboard it was given."""

    def __init__(self) -> None:
        self.texts: list[str] = []
        self.markups: list[Any] = []

    async def answer(self, text: str, reply_markup: Any = None, **_: Any) -> None:
        self.texts.append(text)
        self.markups.append(reply_markup)

    edit_text = answer

    async def edit_reply_markup(self, reply_markup: Any = None, **_: Any) -> None:
        self.texts.append(self.texts[-1] if self.texts else "")
        self.markups.append(reply_markup)

    @property
    def last(self) -> str:
        return self.texts[-1]

    @property
    def keyboard(self) -> Any:
        return self.markups[-1]


class _CardCallback:
    def __init__(self, message: _CardEditable | None = None, user_id: int = 42) -> None:
        self.message = message or _CardEditable()
        self.user_id = user_id
        self.answers: list[tuple[str | None, bool]] = []

    @property
    def from_user(self):
        return SimpleNamespace(id=self.user_id, username="tester", full_name="Тестер")

    async def answer(self, text: str | None = None, show_alert: bool = False, **_: Any) -> None:
        self.answers.append((text, show_alert))

    @property
    def alerted(self) -> bool:
        return any(alert for _, alert in self.answers)


@pytest.fixture
def FakeState() -> type[_ReplyState]:
    return _ReplyState


@pytest.fixture
def FakeMessage() -> type[_ReplyMessage]:
    return _ReplyMessage


@pytest.fixture
def FakeCallback() -> type[_ReplyCallback]:
    return _ReplyCallback


@pytest.fixture
def FakeEditable() -> type[_ReplyEditable]:
    return _ReplyEditable


@pytest.fixture
def MarkupEditable() -> type[_MarkupEditable]:
    return _MarkupEditable


@pytest.fixture
def FakeContactMessage() -> type[_ContactMessage]:
    return _ContactMessage


@pytest.fixture
def CardEditable() -> type[_CardEditable]:
    return _CardEditable


@pytest.fixture
def CardCallback() -> type[_CardCallback]:
    return _CardCallback


# --------------------------------------------------------------------------
# The documents that can name the migration head
#
# test_schema_version reads them and fails on one that names an old head;
# test_ci_paths holds that a change to any of them runs that test on CI (#295).
# One list, so the two cannot come to mean different documents.
# --------------------------------------------------------------------------

_REPOSITORY = Path(__file__).resolve().parents[2]

#: The record of past batches, moved verbatim out of HANDOVER.md: it quotes the
#: head of every commit it describes, true of that commit, and is never
#: brought up to date.
_HISTORY = Path("docs") / "history.md"


def _head_documents(repository: Path = _REPOSITORY) -> list[Path]:
    """Everything a person or an agent reads to learn the head — not
    HANDOVER.md or docs/history.md, which between them hold every head
    there has been, and not `.claude/worktrees/`, which holds other
    branches' checkouts rather than this one's."""
    found = [repository / name for name in ("README.md", "CLAUDE.md", "AGENTS.md")]
    found.append(repository / ".github" / "copilot-instructions.md")
    found += sorted((repository / "docs").rglob("*.md"))
    worktrees = repository / ".claude" / "worktrees"
    found += sorted(document for document in (repository / ".claude").rglob("*.md")
                    if not document.is_relative_to(worktrees))
    return [document for document in found
            if document.is_file() and document != repository / _HISTORY]


@pytest.fixture
def head_documents():
    """The function rather than its answer: one test calls it on a tree it
    builds in `tmp_path`."""
    return _head_documents


# --------------------------------------------------------------------------
# The diary's fake upstream
#
# Shared by the JSON API's tests and the sign-in page's, which drive the same
# upstream through two different front doors.
# --------------------------------------------------------------------------

_LOGIN_PATH = "/api/user/auth/login"


class _FakeUpstream:
    """Answers the upstream's paths, and records what it was asked.

    @param routes path -> either a dict (sent as ``{"data": ...}``) or a
        callable taking the request and returning a full ``httpx.Response``.
    """

    def __init__(self, routes: dict[str, object]) -> None:
        self.routes = routes
        self.seen: list[httpx.Request] = []

    def handler(self, request: httpx.Request) -> httpx.Response:
        self.seen.append(request)
        route = self.routes.get(request.url.path)
        if route is None:
            return httpx.Response(404, json={"message": "no such path"})
        if callable(route):
            return route(request)
        return httpx.Response(200, json={"data": route})

    def query(self, path: str) -> dict[str, str]:
        for request in self.seen:
            if request.url.path == path:
                return dict(request.url.params)
        raise AssertionError(f"{path} was never called")


def _with_token(request: httpx.Request) -> httpx.Response:
    """The login answer, with the session in a cookie as the upstream sends it."""
    body = json.loads(request.content)
    if body.get("password") != "correct":
        return httpx.Response(401, json={"message": "Неверный логин или пароль"})
    response = httpx.Response(200, json={"data": {"token": "body-token"}})
    response.headers["set-cookie"] = "X-JWT-Token=cookie-token; Path=/"
    return response


@pytest.fixture
def LOGIN_PATH() -> str:
    return _LOGIN_PATH


@pytest.fixture
def FakeUpstream() -> type[_FakeUpstream]:
    return _FakeUpstream


@pytest.fixture
def with_token():
    return _with_token


@pytest.fixture
def diary_offline(monkeypatch) -> list[httpx.Request]:
    """Both diaries' pooled clients, dropping every request as a connection
    that failed, and recording it.

    A v2 call that reaches a provider with no upstream of its test's own meets
    ``UpstreamUnavailable`` here, never the real diary. The gate test and the
    no-echo sweep call every served method with ``v2_tokens``' diary session,
    and from 3b-7 a diary read asks the diary for the session's pupils.
    """
    from app.providers.netschool import client as nsclient
    from app.providers.petersburg import client as pbclient

    asked: list[httpx.Request] = []

    def drop(request: httpx.Request) -> httpx.Response:
        asked.append(request)
        raise httpx.ConnectError("offline", request=request)

    async def petersburg() -> httpx.AsyncClient:
        return httpx.AsyncClient(base_url=pbclient.BASE_URL, transport=httpx.MockTransport(drop))

    async def netschool() -> httpx.AsyncClient:
        return httpx.AsyncClient(transport=httpx.MockTransport(drop))

    monkeypatch.setattr(pbclient, "shared_client", petersburg)
    monkeypatch.setattr(nsclient, "shared_client", netschool)
    return asked


@pytest.fixture
async def v2_tokens(session, school_class) -> dict[str, str]:
    """A bearer for each caller the gate tells apart, in ``school_class``.

    ``unlinked`` is the class code's anonymous phone; ``viewer`` to ``owner``
    are phones linked to members of those roles; ``stranger`` is linked to
    an account that is no member; ``diary`` is a live diary session.
    Telegram ids start at 2001, clear of the ``OWNER_IDS`` this file sets.
    """
    from datetime import datetime

    from app.crypto import seal
    from app.models import BotUser, DeviceToken, DiarySession, Role
    from app.security import hash_token

    tokens: dict[str, str] = {}
    for name, telegram_id, role in (
        ("unlinked", None, None),
        ("viewer", 2001, Role.VIEWER),
        ("editor", 2002, Role.EDITOR),
        ("admin", 2003, Role.ADMIN),
        ("owner", 2004, Role.OWNER),
        ("stranger", 2005, None),
    ):
        token = f"v2-{name}-token"
        session.add(
            DeviceToken(
                token_hash=hash_token(token),
                class_id=school_class.id,
                device_name=f"{name} phone",
                telegram_id=telegram_id,
                linked_at=datetime(2026, 9, 1) if telegram_id is not None else None,
            )
        )
        if role is not None:
            session.add(BotUser(telegram_id=telegram_id, class_id=school_class.id, role=role))
        tokens[name] = token
    tokens["diary"] = "v2-diary-token"
    session.add(
        DiarySession(
            token_hash=hash_token(tokens["diary"]),
            upstream_token=seal("an-upstream-session"),
            login="parent@example.com",
            provider="petersburg",
        )
    )
    await session.commit()
    return tokens




# --------------------------------------------------------------------------
# v2, called both ways (docs/specs/2026-10-05-server-v2-design.md, decision 14)
#
# `v2` calls one method over REST, through the app on httpx's ASGI transport,
# and over Connect, as the plain HTTP POST of canonical JSON (or binary) that
# Connect's unary protocol is — no client library, so what is tested is the
# wire. `both` asserts the two answers are one outcome, which is how «REST
# and RPC cannot disagree» is held rather than hoped. The REST request is
# built from the method's own `google.api.http` rule, here, in the client's
# direction, independently of the transcoder that reads it back.
# --------------------------------------------------------------------------

_DOMAIN = "lessons.app"


@dataclass
class _V2Answer:
    """One transport's answer, read into what both transports carry."""

    transport: str
    status: int
    headers: httpx.Headers
    body: bytes
    #: The response message on success; a REST 304 reads as `not_modified`.
    message: Any = None
    #: The canonical code's name, "PERMISSION_DENIED", on a refusal.
    code: str | None = None
    reason: str | None = None
    metadata: dict[str, str] = field(default_factory=dict)
    #: The refusal's sentence.
    error: str | None = None
    violations: list[tuple[str, str]] = field(default_factory=list)
    retry_seconds: int | None = None

    def outcome(self) -> tuple[Any, ...]:
        if self.code is None and self.message is None:
            # An answer the harness could not read is no success, and it is
            # never the same as another transport's: the status and the
            # transport are part of it, so ``both`` fails on the pair.
            return ("unreadable", self.transport, self.status)
        if self.code is None:
            return ("ok", self.message)
        return (
            self.code,
            self.reason,
            self.metadata,
            self.error,
            self.violations,
            self.retry_seconds,
        )


def _v2_details(answer: _V2Answer, details: list[tuple[str, Any]]) -> None:
    """Fill ``answer`` from ``(type name, message)`` pairs of either transport."""
    for type_name, message in details:
        if type_name == "google.rpc.ErrorInfo":
            assert message.domain == _DOMAIN
            answer.reason = message.reason
            answer.metadata = dict(message.metadata)
        elif type_name == "google.rpc.BadRequest":
            answer.violations = [(v.field, v.description) for v in message.field_violations]
        elif type_name == "google.rpc.RetryInfo":
            answer.retry_seconds = message.retry_delay.seconds


def _v2_cleared(message: Any, dotted: str) -> Any:
    """A copy of ``message`` with the field at ``dotted`` cleared, where it has one."""
    copy = message.from_binary(message.to_binary())
    target, parts = copy, dotted.split(".")
    for part in parts:
        field_desc = next((f for f in target.desc().fields if f.name == part), None)
        if field_desc is None or not target.has_field(part):
            return copy
        if part == parts[-1]:
            target.clear_field(part)
        else:
            target = target[field_desc]
    return copy


def _v2_json(response: httpx.Response) -> dict[str, Any]:
    """A refusal's JSON body, or ``{}`` when the answer is not one: an empty
    404 or a text 415 must fail the test's own assertion on ``status``, not
    this reader."""
    if "json" not in response.headers.get("content-type", "") or not response.content:
        return {}
    try:
        parsed = response.json()
    except ValueError:
        return {}
    return parsed if isinstance(parsed, dict) else {}


class _V2:
    """`await v2.both("MeService/GetMe", GetMeRequest(), token=…)`, and its halves."""

    def __init__(self, http: httpx.AsyncClient) -> None:
        self.http = http

    @staticmethod
    def method(name: str) -> Any:
        from app.rpc.methods import METHODS

        return METHODS[f"lessons.v2.{name}"]

    @staticmethod
    def _headers(token: str | None, headers: dict[str, str] | None) -> dict[str, str]:
        sent = dict(headers or {})
        if token is not None:
            sent["Authorization"] = f"Bearer {token}"
        return sent

    async def rest(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> _V2Answer:
        from urllib.parse import quote, urlencode

        from protobuf import message_to_json_value

        from app.rpc.methods import message_class

        method = self.method(name)
        request = request if request is not None else method.input()
        binding = method.binding
        assert binding is not None, f"{name} has no REST binding"
        sent = self._headers(token, headers)
        value = message_to_json_value(request)
        path = binding.path
        for variable in binding.variables:
            leaf: Any = request
            container = value
            parts = variable.split(".")
            for index, part in enumerate(parts):
                field_desc = next(f for f in leaf.desc().fields if f.name == part)
                nested = leaf[field_desc]
                if nested is None:
                    # An unset message on the way to a path variable, as in an
                    # empty `UpdateSubjectRequest`: the variable is its field's
                    # default, the URL a client writes from an empty resource.
                    nested = message_class(field_desc.value.message)()
                leaf = nested
                if index < len(parts) - 1:
                    container = container.get(field_desc.json_name, {})
                else:
                    container.pop(field_desc.json_name, None)
            path = path.replace("{" + variable + "}", quote(str(leaf), safe=""))
        if "ifNoneMatch" in value:
            sent["If-None-Match"] = value.pop("ifNoneMatch")

        body: Any = None
        rest_of: dict[str, Any] = value
        if binding.body == "*":
            body, rest_of = value, {}
        elif binding.body:
            body_field = next(f for f in method.input.desc().fields if f.name == binding.body)
            body = value.pop(body_field.json_name, {})

        query: list[tuple[str, str]] = []

        def flatten(prefix: str, item: Any) -> None:
            if isinstance(item, dict):
                for key, nested in item.items():
                    flatten(f"{prefix}{key}.", nested)
            elif isinstance(item, list):
                for element in item:
                    flatten(prefix, element)
            elif isinstance(item, bool):
                query.append((prefix.rstrip("."), "true" if item else "false"))
            else:
                query.append((prefix.rstrip("."), str(item)))

        flatten("", rest_of)
        url = "/api" + path + (f"?{urlencode(query)}" if query else "")
        response = await self.http.request(
            binding.verb.upper(),
            url,
            content=None if body is None else json.dumps(body).encode(),
            headers={**sent, "Content-Type": "application/json"} if body is not None else sent,
        )
        answer = _V2Answer("rest", response.status_code, response.headers, response.content)
        if response.status_code == 304:
            if "etag" in response.headers:
                answer.message = method.output.from_json(
                    json.dumps({"notModified": True, "etag": response.headers["etag"]})
                )
        elif response.status_code < 300:
            answer.message = method.output.from_json(response.content)
        else:
            from app.contract.google.rpc import error_details_pb

            error = _v2_json(response).get("error", {})
            assert error.get("code", response.status_code) == response.status_code
            answer.code, answer.error = error.get("status"), error.get("message")
            details = []
            for detail in error.get("details", []):
                type_name = str(detail.get("@type", "")).removeprefix("type.googleapis.com/")
                cls = getattr(error_details_pb, type_name.rpartition(".")[2], None)
                if cls is None:
                    continue
                fields = {key: item for key, item in detail.items() if key != "@type"}
                details.append((type_name, cls.from_json(json.dumps(fields))))
            _v2_details(answer, details)
        return answer

    async def connect(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
        binary: bool = False,
    ) -> _V2Answer:
        method = self.method(name)
        request = request if request is not None else method.input()
        sent = self._headers(token, headers)
        sent["Content-Type"] = "application/proto" if binary else "application/json"
        sent["Connect-Protocol-Version"] = "1"
        response = await self.http.post(
            f"/api/rpc/{method.key}",
            content=request.to_binary() if binary else request.to_json().encode(),
            headers=sent,
        )
        answer = _V2Answer("connect", response.status_code, response.headers, response.content)
        if response.status_code == 200:
            answer.message = (
                method.output.from_binary(response.content)
                if binary
                else method.output.from_json(response.content)
            )
        else:
            self._connect_error(answer, _v2_json(response))
        return answer

    async def stream(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
    ) -> _V2Answer:
        """A server-streaming call over Connect: its end message's error, if any."""
        import struct

        method = self.method(name)
        request = request if request is not None else method.input()
        payload = request.to_json().encode()
        sent = self._headers(token, headers)
        sent["Content-Type"] = "application/connect+json"
        response = await self.http.post(
            f"/api/rpc/{method.key}",
            content=struct.pack(">BI", 0, len(payload)) + payload,
            headers=sent,
        )
        answer = _V2Answer("stream", response.status_code, response.headers, response.content)
        data = b""
        if response.status_code == 200:
            data = response.content
        else:
            self._connect_error(answer, _v2_json(response))
        while data:
            flags, length = struct.unpack(">BI", data[:5])
            frame, data = data[5 : 5 + length], data[5 + length :]
            if flags & 0x02:
                end = json.loads(frame)
                if "error" in end:
                    self._connect_error(answer, end["error"])
        return answer

    @staticmethod
    def _connect_error(answer: _V2Answer, error: dict[str, Any]) -> None:
        import base64

        from app.contract.google.rpc import error_details_pb

        if not isinstance(error.get("code"), str):
            return
        answer.code, answer.error = error["code"].upper(), error.get("message")
        details = []
        for detail in error.get("details", []):
            cls = getattr(error_details_pb, str(detail.get("type", "")).rpartition(".")[2], None)
            if cls is None:
                continue
            raw = base64.b64decode(detail["value"] + "=" * (-len(detail["value"]) % 4))
            details.append((detail["type"], cls.from_binary(raw)))
        _v2_details(answer, details)

    async def both(
        self,
        name: str,
        request: Any = None,
        *,
        token: str | None = None,
        headers: dict[str, str] | None = None,
        differ: tuple[str, ...] = (),
    ) -> _V2Answer:
        """Call ``name`` over REST, then over Connect, and assert one outcome.

        ``differ`` names response fields, dotted, that two calls may answer
        differently by nature — a new token, a ``generated_at`` — and that are
        compared as absent. Returns the REST answer.
        """
        rest = await self.rest(name, request, token=token, headers=headers)
        connect = await self.connect(name, request, token=token, headers=headers)
        for answer in (rest, connect):
            if answer.message is not None:
                for dotted in differ:
                    answer.message = _v2_cleared(answer.message, dotted)
        assert rest.outcome() == connect.outcome(), (rest, connect)
        return rest


@pytest.fixture
async def v2() -> AsyncIterator[_V2]:
    """The app, reached over REST and Connect from one peer address."""
    from app.main import app

    transport = httpx.ASGITransport(app=app, client=("203.0.113.9", 52144))
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as http:
        yield _V2(http)


@pytest.fixture
async def served_settings():
    """The ``Settings`` the served app reads, to patch for a call through ``v2``.

    ``get_settings()`` is not it: a test module that clears that cache builds a
    second instance, while v2's ``invoke`` goes on reading the one the process's
    dishka container resolved once, so a patch on the copy changes nothing the
    gate sees and the test fails only after whichever module cleared the cache.
    """
    from app.config import Settings
    from app.di import container

    return await container().get(Settings)


@pytest.fixture
async def settings_cache_cleared():
    """Leave ``get_settings()`` building a different ``Settings`` from the one
    the served app holds, as eight modules do when they clear its cache.

    The container's instance is resolved first so that it exists to diverge
    from; without that, a test would see the two agree whenever nothing had
    cleared the cache before it, and would guard against the bug only by the
    luck of its position in the run. Request it before ``served_settings``.
    """
    from app.config import Settings, get_settings
    from app.di import container

    await container().get(Settings)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


#: asyncpg's placeholder with the cast SQLAlchemy writes beside it
#: (``$1::TIMESTAMP WITHOUT TIME ZONE``), which on SQLite is a bare ``?``.
#: Folded to the ``?`` the rules below are written in, so they read one
#: statement the same way on either database. The casts are named rather than
#: matched as «capitals and spaces», which would swallow the ``WHERE`` after
#: them.
_PLACEHOLDER = re.compile(
    r"\$\d+(?:::(?:TIMESTAMP WITH(?:OUT)? TIME ZONE|DOUBLE PRECISION|[A-Z]+(?:\(\d+\))?))?"
)


@pytest.fixture
def statement_writes() -> Callable[[], contextlib.AbstractContextManager[list[str]]]:
    """``with statement_writes() as seen:`` — every INSERT, UPDATE and DELETE
    the engine sends while the block runs, whitespace folded.

    Counts statements, not rows: a link code, a feed secret and a relinked
    subject are updates, which a row count cannot see. The listener is scoped
    to the block, so a test cannot leak it into the next.
    """

    @contextlib.contextmanager
    def writes() -> Iterator[list[str]]:
        seen: list[str] = []

        def record(conn, cursor, statement, parameters, context, executemany) -> None:
            if re.match(r"\s*(?:INSERT|UPDATE|DELETE)\b", statement, re.IGNORECASE):
                seen.append(_PLACEHOLDER.sub("?", " ".join(statement.split())))

        event.listen(engine.sync_engine, "before_cursor_execute", record)
        try:
            yield seen
        finally:
            event.remove(engine.sync_engine, "before_cursor_execute", record)

    return writes


#: The writes a read may make, on purpose (decision 10 of the server-v2
#: design): telemetry no client observes, a diary credential the upstream
#: rotated and when it was used, and the throttles' and the directory
#: counter's own rows. A fixture hands the rule out, because a test module may
#: not import another one.
ALLOWED_WRITES = (
    # The phone's last call, and the app version it sent with it: one
    # statement, on one fifteen-minute clock (decision 15).
    re.compile(r"^UPDATE device_tokens SET last_seen_at=\?(?:, client_version=\?)? WHERE"),
    re.compile(r"^UPDATE diary_sessions SET (?:(?:upstream_token|last_used_at)=\?(?:, )?)+ WHERE"),
    re.compile(r"^(?:INSERT INTO|UPDATE|DELETE FROM) (?:join_attempts|usage_counters)\b"),
)


@pytest.fixture
def unexpected_writes() -> Callable[[list[str]], list[str]]:
    """The statements among those given that a read may not make."""

    def unexpected(statements: list[str]) -> list[str]:
        return [s for s in statements if not any(rule.match(s) for rule in ALLOWED_WRITES)]

    return unexpected


@pytest.fixture
def last_seen_rule() -> re.Pattern[str]:
    """The one write a plain authenticated read makes: the device's last call."""
    return ALLOWED_WRITES[0]


# --------------------------------------------------------------------------
# v2's notices to the class (stage 3b-5)
#
# `notices` is the bot `telegram_send.build_bot` hands out, and `subscribers`
# the class's people who asked to hear about a kind of change. A test that
# wants to know whether a change was committed before the class was told sets
# `notices.looks` to a read from a session of its own: it runs as each message
# is sent, and what it saw is kept beside the message.
# --------------------------------------------------------------------------

#: Who asked to hear about what, as ``(notify_changes, notify_homework)``: one
#: classmate both, one homework only, one changes only, one neither — and
#: ``v2_tokens``' editor, 2002, who asked for both and is never told of a
#: change of their own.
SUBSCRIBERS = {
    7001: (True, True),
    7002: (False, True),
    7003: (True, False),
    7004: (False, False),
    2002: (True, True),
}


@dataclass
class _Notices:
    """What a bot built for a notice was asked to send, what ``looks`` read at
    each send, and how often the bot was built and closed."""

    sent: list[tuple[int, str]] = field(default_factory=list)
    saw: list[Any] = field(default_factory=list)
    built: int = 0
    closed: int = 0
    looks: Callable[[], Awaitable[Any]] | None = None

    async def send_message(self, chat_id: int, text: str, **_: Any) -> None:
        if self.looks is not None:
            self.saw.append(await self.looks())
        self.sent.append((chat_id, text))

    @property
    def session(self) -> _Notices:
        return self

    async def close(self) -> None:
        self.closed += 1


@pytest.fixture
def notices(monkeypatch) -> _Notices:
    """A deployment with a bot, every send of which the test reads."""
    from app import telegram_send
    from app.config import get_settings

    bot = _Notices()
    monkeypatch.setattr(get_settings(), "bot_token", "123456:TEST")

    def build() -> _Notices:
        bot.built += 1
        return bot

    monkeypatch.setattr(telegram_send, "build_bot", build)
    return bot


@pytest.fixture
async def subscribers(session, school_class) -> dict[int, tuple[bool, bool]]:
    """``SUBSCRIBERS``, in ``school_class``."""
    from app.models import ReminderSettings

    for telegram_id, (changes, homework) in SUBSCRIBERS.items():
        session.add(
            ReminderSettings(
                class_id=school_class.id,
                telegram_id=telegram_id,
                notify_changes=changes,
                notify_homework=homework,
            )
        )
    await session.commit()
    # A copy: a test that adds a recipient to what it was handed must not add
    # it to every later test in the same worker.
    return dict(SUBSCRIBERS)
