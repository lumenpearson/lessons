"""The diary in the bot, and the one property that matters about it.

The class's own data is shared by construction — everybody in the class sees
the same timetable. The diary is the opposite, and «is it private?» is not a
question a reviewer can answer by reading a screen. So it is answered here:
every route into somebody's diary is keyed on the presser's own Telegram id,
and a crafted payload reaches their own diary or nothing.
"""

from __future__ import annotations

from datetime import date, time

import pytest
from test_bot_calendar import FakeCallback

from app.bot import diary_render
from app.bot.handlers import diary as handlers
from app.crypto import seal
from app.models import DiarySession, Role, SchoolClass
from app.providers.petersburg.models import DiaryLesson, HomeworkItem, Mark
from app.security import hash_token

MINE = 42
SOMEBODY_ELSE = 777


async def _bind(session, school_class) -> SchoolClass:
    school_class.diary_provider = handlers.PETERSBURG
    await session.commit()
    return school_class


async def _open_session(session, *, telegram_id: int, class_id: int, student: int = 90210):
    row = DiarySession(
        token_hash=hash_token(f"token-{telegram_id}-{class_id}"),
        upstream_token=seal("upstream"),
        login=f"user{telegram_id}@example.com",
        telegram_id=telegram_id,
        class_id=class_id,
        student_id=student,
    )
    session.add(row)
    await session.commit()
    return row


# --------------------------------------------------------------------------
# Privacy
# --------------------------------------------------------------------------


async def test_a_session_is_found_by_the_presser_and_never_by_the_class(
    session, school_class
):
    """The whole confidentiality claim, as one assertion.

    Somebody else's session exists in the same class; the lookup for our id
    must not see it, and theirs must not see ours.
    """
    await _bind(session, school_class)
    theirs = await _open_session(session, telegram_id=SOMEBODY_ELSE, class_id=school_class.id)

    assert await handlers._session_for(session, MINE, school_class.id) is None

    mine = await _open_session(session, telegram_id=MINE, class_id=school_class.id)
    found = await handlers._session_for(session, MINE, school_class.id)
    assert found.id == mine.id
    assert found.id != theirs.id


async def test_a_session_does_not_cross_between_classes(session, school_class):
    """A parent in two classes must not read one child's diary from the other
    class's screen — the session is keyed on both halves, always."""
    other = SchoolClass(name="9Б", school="Школа № 1", join_code="OTHER42")
    session.add(other)
    await session.commit()

    await _open_session(session, telegram_id=MINE, class_id=other.id)

    assert await handlers._session_for(session, MINE, school_class.id) is None
    assert await handlers._session_for(session, MINE, other.id) is not None


async def test_a_session_whose_key_no_longer_opens_it_is_dead(session, school_class):
    row = await _open_session(session, telegram_id=MINE, class_id=school_class.id)
    row.upstream_token = "not-a-fernet-blob"
    await session.commit()

    assert await handlers._session_for(session, MINE, school_class.id) is None
    await session.refresh(row)
    assert row.expired_at is not None


# --------------------------------------------------------------------------
# The gates in front of it
# --------------------------------------------------------------------------


async def test_an_unbound_class_has_no_diary_to_offer(session, school_class):
    callback = FakeCallback(user_id=MINE)
    await handlers.diary_root(callback, session, school_class, Role.OWNER)

    assert callback.alerted
    assert "не привязан" in callback.answers[-1][0]


async def test_a_bound_class_with_no_session_offers_the_door_not_an_error(
    session, school_class
):
    await _bind(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.VIEWER)

    assert not callback.alerted
    assert "Пароль не вводится в чат" in callback.message.last


async def test_a_viewer_gets_the_diary_like_everybody_else(session, school_class):
    """No role in the class grants any of it: the button opens *your* diary,
    and a наблюдатель has as much right to their own child's marks as an owner
    has to theirs."""
    await _bind(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.VIEWER)

    assert not callback.alerted


async def test_the_sign_in_link_is_refused_when_there_is_nowhere_to_point_it(
    session, school_class, monkeypatch
):
    """A link built from an unset PUBLIC_BASE_URL would 404, and telling
    somebody to open a page that does not exist is worse than saying so."""
    from app.config import get_settings

    await _bind(session, school_class)
    monkeypatch.setattr(get_settings(), "public_base_url", "", raising=False)
    callback = FakeCallback(user_id=MINE)
    try:
        await handlers.diary_sign_in(callback, session, school_class, Role.VIEWER)
    finally:
        get_settings.cache_clear()

    assert "PUBLIC_BASE_URL" in callback.message.last
    assert "signin" not in callback.message.last


async def test_the_sign_in_link_is_one_ticket_pointed_at_the_form(
    session, school_class, monkeypatch
):
    from app.config import get_settings

    await _bind(session, school_class)
    monkeypatch.setattr(get_settings(), "public_base_url", "https://lessons.example.com/")
    callback = FakeCallback(user_id=MINE)
    try:
        await handlers.diary_sign_in(callback, session, school_class, Role.VIEWER)
    finally:
        get_settings.cache_clear()

    links = [
        button.url
        for row in callback.message.keyboard.inline_keyboard
        for button in row
        if button.url
    ]
    assert len(links) == 1
    assert links[0].startswith("https://lessons.example.com/diary/signin/")
    # Said in the message, not just true in the code: the person is about to
    # decide whether to forward it.
    assert "один раз" in callback.message.last
    assert "Не пересылайте" in callback.message.last


async def test_signing_out_drops_the_session_and_returns_the_door(session, school_class):
    await _bind(session, school_class)
    await _open_session(session, telegram_id=MINE, class_id=school_class.id)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_sign_out(callback, session, school_class)

    assert await handlers._session_for(session, MINE, school_class.id) is None
    assert "Пароль не вводится в чат" in callback.message.last


# --------------------------------------------------------------------------
# Rendering
# --------------------------------------------------------------------------


TODAY = date(2026, 9, 14)  # a Monday


def test_an_empty_day_says_which_kind_of_empty_it_might_be():
    """The upstream returns nothing for a holiday, for a day it has no data
    for, and for a journal a teacher has not filled — three situations one
    blank screen makes indistinguishable, and a parent refreshes four times."""
    text = diary_render.render_day([], TODAY, TODAY)

    assert "каникулы" in text and "незаполненный журнал" in text


def test_a_day_shows_the_lesson_its_room_and_what_was_set():
    lessons = [
        DiaryLesson(
            date=TODAY,
            number=1,
            subject="Алгебра",
            starts_at=time(8, 30),
            ends_at=time(9, 15),
            room="214",
            teacher="Иванова И.И.",
            homework="№ 12–15",
            topic="Квадратные уравнения",
        )
    ]
    text = diary_render.render_day(lessons, TODAY, TODAY)

    assert "Алгебра · 214 · Иванова И.И." in text
    assert "08:30–09:15" in text
    assert "📝 № 12–15" in text
    assert "Квадратные уравнения" in text


def test_marks_average_only_the_digits():
    """«Н» and «Б» are attendance codes sitting in the same column; averaging
    them in would drag every absence towards a two."""
    marks = [
        Mark(subject_name="Алгебра", value="5", date=TODAY),
        Mark(subject_name="Алгебра", value="4", date=TODAY),
        Mark(subject_name="Алгебра", value="Н", date=TODAY),
    ]
    text = diary_render.render_marks(marks, TODAY, TODAY)

    assert "4.50" in text
    assert "⚪Н" in text


@pytest.mark.parametrize(
    ("value", "icon"), [("5", "🟢"), ("3", "🟡"), ("2", "🔴"), ("Н", "⚪"), ("?", "⚫")]
)
def test_a_mark_is_found_by_colour_before_it_is_read(value, icon):
    assert diary_render.mark_icon(value) == icon


def test_homework_is_grouped_by_the_day_it_is_due():
    """Which is the question anybody actually asks of homework — «что на
    завтра», never «что задали во вторник»."""
    items = [
        HomeworkItem(due_date=date(2026, 9, 15), subject="Физика", text="§ 4"),
        HomeworkItem(due_date=date(2026, 9, 15), subject="Алгебра", text="№ 20"),
        HomeworkItem(due_date=date(2026, 9, 16), subject="История", text="конспект"),
    ]
    text = diary_render.render_homework(items, TODAY, date(2026, 9, 20), TODAY)

    assert text.index("Алгебра") < text.index("Физика")  # sorted inside a day
    assert text.index("Физика") < text.index("История")  # 15th before 16th


def test_a_week_names_the_days_that_are_empty_too():
    lessons = [DiaryLesson(date=TODAY, number=1, subject="Алгебра")]
    text = diary_render.render_week(lessons, TODAY, TODAY)

    assert "Понедельник" in text and "Алгебра" in text
    assert text.count("<i>нет</i>") == 5  # Tuesday to Saturday
