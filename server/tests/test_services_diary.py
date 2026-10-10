"""The diary's session and read rules, in ``services/diary``: one set for v1 and v2.

v1's ``api/diary.py`` held them in its routes: where a session goes and which
region it may, how what a phone hands over is sealed, how an attempt is
counted on each outcome, the window a read covers, and which pupil an id
names. v2's ``DiaryService`` answers the same questions, so they moved
(``docs/specs/2026-10-05-server-v2-design.md``, decision 2), as facts each
shell words. ``test_diary_api.py``, ``test_diary_session.py`` and
``test_diary_web.py``, untouched, are the proof that v1's answers did not
move; these hold the rules a fact at a time, that the new writes leave their
commit to the caller, and that v1 never repeats a password it refuses (#390).
"""

from __future__ import annotations

from datetime import date, timedelta
from typing import Any

import httpx
import pytest
from httpx import ASGITransport
from sqlalchemy import func, select

from app import wording
from app.crypto import seal
from app.db import SessionLocal
from app.main import app
from app.models import DiaryOverride, DiarySession, JoinAttempt
from app.providers.diary.base import Adopted
from app.providers.diary.errors import (
    AddressRefused,
    BadCredentials,
    NoStudents,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.diary.models import AcademicPeriod, DiaryLesson, Student, Subject
from app.providers.petersburg.provider import PetersburgProvider
from app.schemas import NetSchoolCredentialIn
from app.security import Throttled, diary_login_limiter, hash_token
from app.services import clock
from app.services import diary as service

JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiI0MDIxIn0.c2lnbmF0dXJlLWZyb20tdGhlLXBob25l"
TODAY = date(2026, 9, 14)
PUPIL = Student(id=4021, first_name="Пётр", last_name="Иванов", education_id=90210, group_id=771)
#: The two buckets one caller's attempts are counted in.
KEYS = {"failures_key": "diary:caller", "opened_key": "diary-open:caller"}


async def _counted() -> tuple[int, int]:
    """The rows each diary limiter holds for the caller: (failures, opened)."""
    async with SessionLocal() as fresh:
        found = []
        for key in KEYS.values():
            found.append(
                await fresh.scalar(
                    select(func.count())
                    .select_from(JoinAttempt)
                    .where(JoinAttempt.client_key == key)
                )
            )
        return found[0], found[1]


async def _committed_sessions() -> int:
    async with SessionLocal() as fresh:
        return await fresh.scalar(select(func.count()).select_from(DiarySession)) or 0


def _adopting(monkeypatch, outcome: Any) -> list[Any]:
    """Petersburg's ``adopt``, answering ``outcome``: an exception to raise, or
    the pupils of a session that opened. Every request it was handed is kept."""
    handed: list[Any] = []

    async def adopt(self, request) -> Adopted:
        handed.append(request)
        if isinstance(outcome, BaseException):
            raise outcome
        return Adopted(credential=request.credential, students=tuple(outcome))

    monkeypatch.setattr(PetersburgProvider, "adopt", adopt)
    return handed


async def _register(session, **over: Any) -> service.Registered:
    sent: dict[str, Any] = {
        "provider": "petersburg",
        "login": "parent@example.com",
        "handed": {"token": JWT},
        "region": None,
        "school_id": None,
        **KEYS,
    }
    return await service.register(session, **(sent | over))


class _Connection:
    """A connection answering what a test gives it, and counting what it was asked."""

    credential = "unchanged"

    def __init__(self, **answers: Any) -> None:
        self.answers = answers
        self.asked: list[str] = []

    def __getattr__(self, name: str) -> Any:
        if name not in ("students", "periods", "subjects", "schedule", "homework"):
            raise AttributeError(name)

        async def read(*_: Any) -> Any:
            self.asked.append(name)
            return self.answers.get(name, [])

        return read

    def today(self) -> date:
        return TODAY


async def _service_over(session, connection: _Connection) -> service.DiaryService:
    row = DiarySession(
        token_hash=hash_token("rules"),
        upstream_token=seal("an-upstream-session"),
        login="parent@example.com",
        provider="petersburg",
    )
    session.add(row)
    await session.commit()
    svc = service.DiaryService(session, row)
    svc.connection = connection  # type: ignore[assignment]
    return svc


def test_a_target_is_checked_against_its_row_before_anything_is_sent() -> None:
    assert service.target("petersburg", "samara", 7) == service.Target("petersburg", None, None)
    assert service.target(None, None, None) == service.Target("petersburg", None, None)
    assert service.target("netschool", "zabaikalsky", 42) == service.Target(
        "netschool", "zabaikalsky", 42
    )
    # A region off the allow-list, one that takes only Госуслуги, and none.
    for region in ("moscow", "tula", None):
        with pytest.raises(service.RegionNotServed):
            service.target("netschool", region, 42)
    with pytest.raises(service.SchoolRequired):
        service.target("netschool", "zabaikalsky", None)
    with pytest.raises(service.UnknownProvider) as unknown:
        service.target("dnevnik-ru", "zabaikalsky", 42)
    assert unknown.value.key == "dnevnik-ru"


def test_what_a_phone_hands_over_is_sealed_in_its_provider_s_own_form() -> None:
    """Byte for byte what v1's route sealed: Petersburg's bare token, and the
    JSON pydantic wrote of «Сетевой город»'s credential, nulls left out."""
    assert service.sealed_form("petersburg", {"token": JWT}) == JWT
    handed = NetSchoolCredentialIn(
        at="56574745368264517434263",
        cookies={"NSSESSIONID": "sess-phone"},
        ver="639",
    )
    assert service.sealed_form(
        "netschool", handed.model_dump(exclude_none=True)
    ) == handed.model_dump_json(exclude_none=True)
    with pytest.raises(service.UnknownProvider):
        service.sealed_form("dnevnik-ru", {"token": JWT})


@pytest.mark.parametrize(
    ("outcome", "raised", "counted"),
    [
        ([PUPIL], None, (0, 1)),
        (SessionExpired(), service.SessionRefused, (1, 0)),
        (BadCredentials(), service.SessionRefused, (1, 0)),
        (NoStudents(), NoStudents, (1, 0)),
        (UnexpectedResponse(), UnexpectedResponse, (1, 0)),
        (UpstreamUnavailable(), UpstreamUnavailable, (0, 0)),
        (AddressRefused(), AddressRefused, (0, 0)),
    ],
)
async def test_each_outcome_is_counted_as_v1_counted_it(
    session, monkeypatch, outcome, raised, counted
) -> None:
    """A session opened is one of the twenty; a session the diary judged and
    refused is a failure; nothing having judged it is neither."""
    handed = _adopting(monkeypatch, outcome)
    if raised is None:
        registered = await _register(session)
        assert [student.id for student in registered.students] == [4021]
        assert (registered.row.provider, registered.zone) == ("petersburg", "Europe/Moscow")
    else:
        with pytest.raises(raised) as caught:
            await _register(session)
        if raised is service.SessionRefused:
            # Its own sentence, not BadCredentials's «Неверный логин или
            # пароль» — a session is not a password (decision 2).
            assert caught.value.message == wording.DIARY_SESSION_REFUSED_DETAIL
    assert [request.credential for request in handed] == [JWT]
    assert await _counted() == counted


async def test_without_the_secret_nothing_is_sent_and_nothing_is_counted(
    session, monkeypatch
) -> None:
    handed = _adopting(monkeypatch, [PUPIL])
    monkeypatch.setattr(service, "diary_enabled", lambda: False)
    with pytest.raises(service.DiaryDisabled):
        await _register(session)
    assert handed == []
    assert await _counted() == (0, 0)


async def test_a_region_this_server_does_not_serve_is_refused_before_anything_is_counted(
    session, monkeypatch
) -> None:
    handed = _adopting(monkeypatch, [PUPIL])
    with pytest.raises(service.RegionNotServed):
        await _register(session, provider="netschool", region="tula", school_id=42)
    assert handed == []
    assert await _counted() == (0, 0)


async def test_a_spent_budget_is_throttled_before_the_diary_is_asked(session, monkeypatch) -> None:
    handed = _adopting(monkeypatch, SessionExpired())
    for _ in range(diary_login_limiter.limit):
        with pytest.raises(service.SessionRefused):
            await _register(session)
    with pytest.raises(Throttled) as throttled:
        await _register(session)
    assert throttled.value.seconds >= 1
    assert len(handed) == diary_login_limiter.limit


async def test_a_new_session_is_committed_with_its_outcome_and_never_apart(
    session, monkeypatch
) -> None:
    """``register`` commits its row with the attempt's outcome, which commits on
    purpose; ``sign_in`` and ``sign_out`` leave theirs to the caller, as v1's
    ``/login`` and ``/logout`` and the sign-in page now commit."""
    _adopting(monkeypatch, [PUPIL])
    await _register(session)
    assert await _committed_sessions() == 1

    async def signed_in(self, request) -> str:
        return JWT

    monkeypatch.setattr(PetersburgProvider, "sign_in", signed_in)
    _token, row = await service.sign_in(session, "parent@example.com", "hunter2")
    assert await _committed_sessions() == 1
    await session.commit()
    assert await _committed_sessions() == 2

    await service.sign_out(session, row)
    assert await _committed_sessions() == 2
    await session.commit()
    assert await _committed_sessions() == 1


def test_the_window_is_v1_s_from_the_diary_s_own_today() -> None:
    window = service.window
    assert window(None, None, TODAY) == (TODAY, TODAY + timedelta(days=14))
    assert window(date(2026, 9, 1), None, TODAY) == (date(2026, 9, 1), date(2026, 9, 15))
    assert window(None, date(2026, 9, 20), TODAY) == (TODAY, date(2026, 9, 20))
    assert window(date(2026, 9, 1), date(2026, 11, 2), TODAY)[1] == date(2026, 11, 2)
    for start, end, edge, why in (
        (date(9999, 12, 31), None, "start", clock.OUT_OF_BOUNDS),
        (date(2026, 9, 1), date(9999, 12, 31), "end", clock.OUT_OF_BOUNDS),
        (None, date(1999, 12, 31), "end", clock.OUT_OF_BOUNDS),
        (date(2026, 9, 20), date(2026, 9, 1), "end", clock.BACKWARDS),
        (date(2026, 1, 1), date(2026, 12, 31), "end", clock.TOO_WIDE),
    ):
        with pytest.raises(clock.WindowRefused) as refused:
            window(start, end, TODAY)
        assert (refused.value.edge, refused.value.why) == (edge, why)


async def test_a_pupil_is_resolved_from_the_session_s_own_diary(session) -> None:
    elsewhere = PUPIL.model_copy(update={"id": 7, "id_space": "relation"})
    connection = _Connection(students=[PUPIL, elsewhere])
    svc = await _service_over(session, connection)
    assert (await svc.student(4021)).education_id == 90210
    with pytest.raises(service.UnknownStudent) as unknown:
        await svc.student(999)
    assert unknown.value.student_id == 999
    assert await svc.child(4021) == (PUPIL, "CHILD:petersburg")
    # A pupil listed outside the provider's own numbering can have no corrections.
    assert await svc.child(7) == (elsewhere, None)
    assert connection.asked == ["students"] * 4


async def test_the_reads_lay_the_child_s_corrections_over_what_came_down(session) -> None:
    lesson = DiaryLesson(date=TODAY, number=2, subject="Алгебра", room="12")
    connection = _Connection(schedule=[lesson])
    svc = await _service_over(session, connection)
    session.add(
        DiaryOverride(
            login="CHILD:petersburg",
            student_id=4021,
            target="lesson:2026-09-14:n2:Алгебра",
            field="room",
            value="301",
            original="12",
        )
    )
    await session.commit()
    (overlaid,) = await svc.schedule_of(PUPIL, "CHILD:petersburg", TODAY, TODAY)
    assert (overlaid.lesson.room, overlaid.target) == ("301", "lesson:2026-09-14:n2:Алгебра")
    assert [(edit.field, edit.value, edit.original) for edit in overlaid.edits] == [
        ("room", "301", "12")
    ]
    # Another child's corrections, and a child who can have none, are not laid over.
    (other,) = await svc.schedule_of(
        PUPIL.model_copy(update={"id": 5}), "CHILD:petersburg", TODAY, TODAY
    )
    (none,) = await svc.schedule_of(PUPIL, None, TODAY, TODAY)
    assert other.edits == none.edits == ()
    assert other.lesson.room == none.lesson.room == "12"


async def test_periods_and_subjects_of_a_pupil_with_no_class_ask_the_diary_nothing(
    session,
) -> None:
    connection = _Connection()
    svc = await _service_over(session, connection)
    classless = PUPIL.model_copy(update={"group_id": None})
    assert await svc.periods_of(classless) == []
    assert await svc.subjects_of(classless, None) == []
    assert connection.asked == []


async def test_subjects_are_the_current_period_s_when_none_is_named(session) -> None:
    periods = [
        AcademicPeriod(id=1, name="1 четверть", is_current=False),
        AcademicPeriod(id=2, name="2 четверть", is_current=True),
    ]
    connection = _Connection(periods=periods, subjects=[Subject(id=3, name="Алгебра")])
    svc = await _service_over(session, connection)
    assert [subject.name for subject in await svc.subjects_of(PUPIL, None)] == ["Алгебра"]
    assert connection.asked == ["periods", "subjects"]
    assert [subject.name for subject in await svc.subjects_of(PUPIL, 1)] == ["Алгебра"]
    assert connection.asked == ["periods", "subjects", "subjects"]
    connection.answers["periods"] = [periods[0]]
    assert await svc.subjects_of(PUPIL, None) == []


async def test_v1_s_login_refuses_a_target_in_v1_s_words_before_counting() -> None:
    """``/login``'s three refusals of a target, now ``target``'s facts, in the
    words the route always used and before anything is counted or sent. No v1
    test held them; this one holds them across the move."""
    cases = (
        ({"provider": "dnevnik-ru"}, "Unknown diary provider 'dnevnik-ru'"),
        (
            {"provider": "netschool", "region": "tula", "school_id": 42},
            "Unknown or unsupported region for «Сетевой город»",
        ),
        (
            {"provider": "netschool", "region": "zabaikalsky"},
            "A school id is required for «Сетевой город»",
        ),
    )
    async with httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as v1:
        for sent, detail in cases:
            body = {"login": "parent@example.com", "password": "hunter2", **sent}
            refused = await v1.post("/api/v1/diary/login", json=body)
            assert (refused.status_code, refused.json()["detail"]) == (422, detail), sent
    async with SessionLocal() as fresh:
        assert await fresh.scalar(select(func.count()).select_from(JoinAttempt)) == 0


async def test_v1_never_repeats_a_password_it_refuses() -> None:
    """#390. ``/login``'s 422 carried each refused value back as ``input``, so a
    password over two hundred characters came back in the answer, to whatever
    logs the phone keeps. It names the field now, as ``/session`` always did."""
    secret = "Pa55w0rd-s3cr3t-Hunter2-" * 10
    async with httpx.AsyncClient(transport=ASGITransport(app=app), base_url="http://test") as v1:
        refused = await v1.post(
            "/api/v1/diary/login", json={"login": "parent@example.com", "password": secret}
        )
    assert refused.status_code == 422
    assert secret not in refused.text
    assert [error["loc"] for error in refused.json()["detail"]] == [["body", "password"]]
