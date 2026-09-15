"""The diary in the bot, and the one property that matters about it.

The class's own data is shared by construction — everybody in the class sees
the same timetable. The diary is the opposite, and «is it private?» is not a
question a reviewer can answer by reading a screen. So it is answered here:
every route into somebody's diary is keyed on the presser's own Telegram id,
and a crafted payload reaches their own diary or nothing.
"""

from __future__ import annotations

from datetime import date, datetime, time, timedelta

import pytest
from test_bot_calendar import FakeCallback

from app.bot import diary_render
from app.bot.handlers import diary as handlers
from app.crypto import seal
from app.models import DiarySession, Role, SchoolClass
from app.providers.petersburg import (
    PetersburgError,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)
from app.providers.petersburg.models import DiaryLesson, HomeworkItem, Mark, Student
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


# --------------------------------------------------------------------------
# The four views, drawn through the handler that fetches them
# --------------------------------------------------------------------------


PUPIL = Student(
    id=1,
    first_name="Пётр",
    last_name="Иванов",
    middle_name="Петрович",
    education_id=90210,
    class_name="9А",
    school="Школа № 1",
)
SIBLING = Student(id=2, first_name="Аня", last_name="Иванова", education_id=90211)


class FakeDiary:
    """Stands in for ``DiaryService``: the upstream, minus the network.

    The handler builds one per press, so the class carries what it will answer
    and records what it was asked - the ranges are half the behaviour under
    test («Оценки» is thirty days back, «Задания» fourteen forward).
    """

    students_answer: list = [PUPIL]
    lessons_answer: list = []
    homework_answer: list = []
    marks_answer: list = []
    raises: Exception | None = None
    asked: list = []

    def __init__(self, session, row) -> None:
        self.row = row

    async def students(self):
        if isinstance(FakeDiary.raises, PetersburgError):
            raise FakeDiary.raises
        return FakeDiary.students_answer

    async def schedule(self, education_id, date_from, date_to):
        FakeDiary.asked.append(("schedule", education_id, date_from, date_to))
        if FakeDiary.raises is not None:
            raise FakeDiary.raises
        return FakeDiary.lessons_answer

    async def homework(self, education_id, date_from, date_to):
        FakeDiary.asked.append(("homework", education_id, date_from, date_to))
        if FakeDiary.raises is not None:
            raise FakeDiary.raises
        return FakeDiary.homework_answer

    async def marks(self, education_id, date_from, date_to):
        FakeDiary.asked.append(("marks", education_id, date_from, date_to))
        if FakeDiary.raises is not None:
            raise FakeDiary.raises
        return FakeDiary.marks_answer


@pytest.fixture
def upstream(monkeypatch):
    FakeDiary.students_answer = [PUPIL]
    FakeDiary.lessons_answer = []
    FakeDiary.homework_answer = []
    FakeDiary.marks_answer = []
    FakeDiary.raises = None
    FakeDiary.asked = []
    monkeypatch.setattr(handlers.diary_service, "DiaryService", FakeDiary)
    return FakeDiary


def labels(keyboard) -> list[str]:
    return [button.text for row in keyboard.inline_keyboard for button in row]


def alert(callback) -> str:
    """The alert the handler raised.

    Not ``answers[-1]``: every one of these handlers closes with a bare
    ``callback.answer()``, so the last entry is the spinner being stopped and
    the sentence sits one before it.
    """
    return next(text for text, shown in reversed(callback.answers) if shown)


def today_of(school_class):
    """«Today» in the class's zone, which is what the handler works from —
    on a server an hour or two behind it, ``date.today()`` is yesterday."""
    return datetime.now(school_class.tz).date()


async def _ready(session, school_class):
    await _bind(session, school_class)
    return await _open_session(session, telegram_id=MINE, class_id=school_class.id)


async def test_the_diary_opens_on_today_and_wears_the_frame(session, school_class, upstream):
    await _ready(session, school_class)
    upstream.lessons_answer = [
        DiaryLesson(
            date=today_of(school_class),
            number=1,
            subject="Алгебра",
            room="214",
            homework="№ 12",
        )
    ]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.VIEWER)

    assert "Алгебра · 214" in callback.message.last
    assert "📝 № 12" in callback.message.last
    shown = labels(callback.message.keyboard)
    assert "• 📅 День" in shown  # the tab you are on is a label, not a button
    assert "🗓 Неделя" in shown
    # One child: nothing to pick between, so no picker button.
    assert "👥 Ребёнок" not in shown


async def test_the_day_view_asks_for_the_day_the_arrow_points_at(
    session, school_class, upstream
):
    await _ready(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day", offset=-3), session, school_class
    )

    kind, education_id, start, end = upstream.asked[-1]
    assert kind == "schedule"
    assert education_id == PUPIL.education_id
    assert start == end == today_of(school_class) - timedelta(days=3)


async def test_the_week_view_asks_monday_to_saturday_of_that_week(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.lessons_answer = [
        DiaryLesson(date=today_of(school_class), number=1, subject="Алгебра"),
    ]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="week", offset=0), session, school_class
    )

    _, _, start, end = upstream.asked[-1]
    assert start.weekday() == 0 and (end - start).days == 5
    assert "📒 <b>Неделя с " in callback.message.last
    # Five of the six days came back empty and each of them says so.
    assert callback.message.last.count("<i>нет</i>") == 5


async def test_the_homework_view_looks_a_fortnight_ahead(session, school_class, upstream):
    await _ready(session, school_class)
    upstream.homework_answer = [
        HomeworkItem(
            due_date=today_of(school_class) + timedelta(days=1), subject="Физика", text="§ 4"
        )
    ]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="homework"), session, school_class
    )

    kind, _, start, end = upstream.asked[-1]
    assert kind == "homework"
    assert (end - start).days == handlers.HOMEWORK_DAYS
    assert "• <b>Физика</b> — § 4" in callback.message.last


async def test_the_marks_view_looks_a_month_back_and_averages_what_it_finds(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.marks_answer = [
        Mark(subject_name="Алгебра", value="5", date=today_of(school_class)),
        Mark(subject_name="Алгебра", value="4", date=today_of(school_class)),
    ]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="marks"), session, school_class
    )

    kind, _, start, end = upstream.asked[-1]
    assert kind == "marks"
    assert (end - start).days == 30
    assert "<b>Алгебра</b> · <b>4.50</b>" in callback.message.last
    assert "🟢5 🟢4" in callback.message.last
    # A range, not a day: no arrows to walk it with.
    assert "‹" not in labels(callback.message.keyboard)


async def test_an_empty_marks_range_names_the_range_rather_than_showing_a_blank(
    session, school_class, upstream
):
    await _ready(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="marks"), session, school_class
    )

    assert "оценок нет" in callback.message.last
    assert "📊 <b>Оценки</b>" in callback.message.last


async def test_an_empty_homework_range_names_the_two_dates_it_asked_about(
    session, school_class, upstream
):
    await _ready(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="homework"), session, school_class
    )

    today = today_of(school_class)
    end = today + timedelta(days=handlers.HOMEWORK_DAYS)
    assert f"С {today:%d.%m} по {end:%d.%m} дневник ничего не вернул." in (
        callback.message.last
    )


async def test_the_arrows_cannot_walk_past_the_year_they_are_bounded_to(
    session, school_class, upstream
):
    """Every press is a call to somebody else's service; an arrow held down is
    how an address gets blocked."""
    await _ready(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day", offset=10_000), session, school_class
    )

    _, _, start, _ = upstream.asked[-1]
    assert start == today_of(school_class) + timedelta(days=handlers.MAX_OFFSET)


# --------------------------------------------------------------------------
# Which child
# --------------------------------------------------------------------------


async def test_a_second_child_puts_the_picker_on_the_frame(session, school_class, upstream):
    await _ready(session, school_class)
    upstream.students_answer = [PUPIL, SIBLING]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.VIEWER)

    assert "👥 Ребёнок" in labels(callback.message.keyboard)


async def test_the_picker_ticks_the_child_being_read(session, school_class, upstream):
    await _ready(session, school_class)
    upstream.students_answer = [PUPIL, SIBLING]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_students(callback, session, school_class)

    assert "👥 <b>Чей дневник смотрим</b>" in callback.message.last
    assert "✓ Иванов Пётр Петрович" in labels(callback.message.keyboard)
    assert "Иванова Аня" in labels(callback.message.keyboard)


async def test_a_payload_naming_a_child_this_account_cannot_see_falls_back(
    session, school_class, upstream
):
    """The list came from the upstream for *this* session, so it is the
    authority on what may be read — not the number in the button."""
    row = await _ready(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback,
        handlers.DiaryAction(view="day", student=999_999),
        session,
        school_class,
    )

    _, education_id, _, _ = upstream.asked[-1]
    assert education_id == PUPIL.education_id
    await session.refresh(row)
    assert row.student_id == PUPIL.education_id


async def test_choosing_a_child_is_remembered_on_the_session(
    session, school_class, upstream
):
    row = await _ready(session, school_class)
    upstream.students_answer = [PUPIL, SIBLING]
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback,
        handlers.DiaryAction(view="day", student=SIBLING.education_id),
        session,
        school_class,
    )

    await session.refresh(row)
    assert row.student_id == SIBLING.education_id


async def test_an_account_with_no_children_says_so_instead_of_an_empty_day(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.students_answer = []
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.VIEWER)

    assert "не привязан ни один ученик" in callback.message.last


# --------------------------------------------------------------------------
# When the upstream will not answer
# --------------------------------------------------------------------------


async def test_an_expired_session_shows_the_door_again_and_says_why(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.raises = SessionExpired("gone")
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day"), session, school_class
    )

    assert "Пароль не вводится в чат" in callback.message.last
    assert callback.alerted
    assert "войдите снова" in alert(callback)


async def test_an_upstream_that_is_down_is_named_as_theirs_not_ours(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.raises = UpstreamUnavailable("502")
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day"), session, school_class
    )

    assert callback.alerted
    assert "на их стороне" in alert(callback)
    assert callback.message.texts == []  # the screen is left as it was


async def test_any_other_refusal_sends_the_person_to_sign_in_again(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.raises = UnexpectedResponse("нечто")
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day"), session, school_class
    )

    assert callback.alerted
    assert "Попробуйте войти заново" in alert(callback)


async def test_a_shape_the_mapper_did_not_expect_never_reaches_the_chat(
    session, school_class, upstream
):
    """The bare ``except`` in ``_show`` is the whole point: the upstream is
    undocumented, and a traceback in a parent's chat is not a diary."""
    await _ready(session, school_class)
    upstream.raises = TypeError("'NoneType' object is not subscriptable")
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="day"), session, school_class
    )

    assert callback.alerted
    assert "чем-то неожиданным" in alert(callback)
    assert "NoneType" not in alert(callback)


async def test_a_view_pressed_without_a_session_offers_the_door(
    session, school_class, upstream
):
    await _bind(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_view(
        callback, handlers.DiaryAction(view="marks"), session, school_class
    )

    assert "Пароль не вводится в чат" in callback.message.last


async def test_a_deployment_without_a_key_refuses_at_the_door(
    session, school_class, monkeypatch
):
    """No ``DIARY_SECRET`` means the feature is off, and says so — the silent
    fallback is the thing ``app/crypto.py`` exists to prevent."""
    await _bind(session, school_class)
    monkeypatch.setattr(handlers, "diary_enabled", lambda: False)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_root(callback, session, school_class, Role.OWNER)

    assert callback.alerted
    assert "DIARY_SECRET" in callback.answers[-1][0]


# --------------------------------------------------------------------------
# Whatever the upstream sends is somebody else's text
# --------------------------------------------------------------------------


def test_a_teachers_angle_bracket_does_not_take_the_whole_message_down():
    """«реши § 4 при a<b» is a homework text a maths teacher writes, and the
    bot sends HTML: unescaped it does not lose the «<», it makes Telegram
    refuse the entire message and the parent sees no diary at all."""
    text = diary_render.render_day(
        [
            DiaryLesson(
                date=TODAY,
                number=1,
                subject="Алгебра <7>",
                room="2<14",
                teacher="Иванова & Co",
                topic="<неравенства>",
                homework="реши § 4 при a<b",
            )
        ],
        TODAY,
        TODAY,
    )

    assert "Алгебра &lt;7&gt;" in text
    assert "2&lt;14 · Иванова &amp; Co" in text
    assert "<i>&lt;неравенства&gt;</i>" in text
    assert "📝 реши § 4 при a&lt;b" in text
    assert "a<b" not in text


def test_the_other_three_views_escape_the_upstream_too():
    week = diary_render.render_week(
        [DiaryLesson(date=TODAY, number=1, subject="Алгебра <7>")], TODAY, TODAY
    )
    homework = diary_render.render_homework(
        [HomeworkItem(due_date=TODAY, subject="Физика <б>", text="п. 3 <= 5")],
        TODAY,
        TODAY,
        TODAY,
    )
    marks = diary_render.render_marks(
        [Mark(subject_name="ОБЖ <мал>", value="5", date=TODAY)], TODAY, TODAY
    )
    student = diary_render.student_line(
        Student(id=1, first_name="Пётр", last_name="Иванов <мл>", education_id=7,
                class_name="9А & 9Б")
    )

    assert "Алгебра &lt;7&gt;" in week
    assert "<b>Физика &lt;б&gt;</b> — п. 3 &lt;= 5" in homework
    assert "<b>ОБЖ &lt;мал&gt;</b>" in marks
    assert "<b>Иванов &lt;мл&gt; Пётр</b>" in student
    assert "9А &amp; 9Б" in student


# --------------------------------------------------------------------------
# The door itself
# --------------------------------------------------------------------------


def test_the_signed_out_card_promises_the_password_never_enters_the_chat():
    """The one sentence the whole ``diary_web`` sign-in form exists for."""
    text = diary_render.render_signed_out()

    assert "📒 <b>Электронный дневник</b>" in text
    assert "<b>Пароль не вводится в чат.</b>" in text
    assert "никто из класса не видит его за вас" in text
    assert "нигде не сохраняется" in text


def test_the_signed_out_card_offers_a_way_in_only_when_there_is_one():
    from app.bot.diary_keyboard import signed_out_keyboard

    assert "🔐 Войти в дневник" in labels(signed_out_keyboard(can_sign_in=True))
    assert "🔐 Войти в дневник" not in labels(signed_out_keyboard(can_sign_in=False))


def test_an_empty_diary_week_still_names_all_six_days():
    text = diary_render.render_week([], TODAY, TODAY)

    for name in ("Понедельник", "Вторник", "Среда", "Четверг", "Пятница", "Суббота"):
        assert f"{name}" in text
    assert text.count("— <i>нет</i>") == 6
    assert "Воскресенье" not in text


def test_marks_with_nothing_numeric_in_them_show_no_average():
    """Averaging «Н» would drag every absence towards a two; printing an
    average of nothing would be worse."""
    text = diary_render.render_marks(
        [Mark(subject_name="Алгебра", value="Н", date=TODAY)], TODAY, TODAY
    )

    assert "<b>Алгебра</b>\n" in text
    assert "·" not in text.split("<b>Алгебра</b>")[1].split("\n")[0]
    assert "⚪Н" in text


# --------------------------------------------------------------------------
# The guards, one per door
# --------------------------------------------------------------------------


async def test_every_diary_door_is_shut_to_somebody_with_no_class(session):
    root = FakeCallback(user_id=MINE)
    await handlers.diary_root(root, session, None, None)
    assert "Сначала присоединитесь к классу" in alert(root)

    signin = FakeCallback(user_id=MINE)
    await handlers.diary_sign_in(signin, session, None, None)
    assert "Дневник недоступен" in alert(signin)

    signout = FakeCallback(user_id=MINE)
    await handlers.diary_sign_out(signout, session, None)
    assert "Нет доступа" in alert(signout)

    students = FakeCallback(user_id=MINE)
    await handlers.diary_students(students, session, None)
    assert "Нет доступа" in alert(students)

    view = FakeCallback(user_id=MINE)
    await handlers.diary_view(view, handlers.DiaryAction(view="day"), session, None)
    assert "Нет доступа" in alert(view)


async def test_the_sign_in_link_is_not_minted_while_the_feature_is_off(
    session, school_class, monkeypatch
):
    await _bind(session, school_class)
    monkeypatch.setattr(handlers, "diary_enabled", lambda: False)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_sign_in(callback, session, school_class, Role.VIEWER)

    assert "выключен" in alert(callback)
    assert callback.message.texts == []


async def test_the_picker_offered_without_a_session_shows_the_door(session, school_class):
    await _bind(session, school_class)
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_students(callback, session, school_class)

    assert "Пароль не вводится в чат" in callback.message.last


async def test_the_picker_says_so_when_the_upstream_will_not_list_the_children(
    session, school_class, upstream
):
    await _ready(session, school_class)
    upstream.raises = UpstreamUnavailable("502")
    callback = FakeCallback(user_id=MINE)

    await handlers.diary_students(callback, session, school_class)

    assert "на их стороне" in alert(callback)
    assert callback.message.texts == []
