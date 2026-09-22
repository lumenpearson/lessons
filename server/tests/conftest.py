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

import json
import os
import tempfile
from collections.abc import AsyncIterator
from dataclasses import dataclass, field
from pathlib import Path
from types import SimpleNamespace
from typing import Any

import httpx
import pytest

_TMP_DIR = Path(tempfile.mkdtemp(prefix="lessons-tests-"))
os.environ["DATABASE_URL"] = f"sqlite+aiosqlite:///{_TMP_DIR / 'test.db'}"
os.environ["RUN_BOT"] = "false"
os.environ["BOT_TOKEN"] = ""
os.environ["OWNER_IDS"] = "1000"
os.environ["TIMEZONE"] = "Europe/Moscow"
# The diary refuses to run without a key, on purpose (app/crypto.py). Tests
# that exercise it therefore have to configure one; that the *absence* of one
# switches the feature off is itself a test, in test_diary_crypto.py.
os.environ["DIARY_SECRET"] = "test-secret-not-a-real-one-0123456789abcdef"

from app.db import Base, SessionLocal, engine  # noqa: E402
from app.models import (  # noqa: E402
    DEFAULT_BELLS,
    BellPeriod,
    BellSchedule,
    SchoolClass,
    TimetableEntry,
    WeekParity,
)


@pytest.fixture(autouse=True)
async def fresh_database() -> AsyncIterator[None]:
    async with engine.begin() as conn:
        await conn.run_sync(Base.metadata.drop_all)
        await conn.run_sync(Base.metadata.create_all)
    yield


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
