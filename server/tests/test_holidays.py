"""The named dates, and which of them a timetable has to give way to.

Two rules, and the second is the one worth a file of its own: a statutory
non-working day stops the lessons, and an observance does not. «День учителя»
is a Monday with six lessons on it and a badge; 9 May is a Saturday-shaped
Monday with none. Getting that backwards removes a real day of teaching from
somebody's timetable, or claims one on a day nobody is at school, and both look
entirely plausible on a calendar.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import timedelta

from httpx import ASGITransport, AsyncClient

from app.main import app
from app.models import DayKind, DayOverride, Role, TermKind
from app.schedule import DayOffReason, ScheduleResolver
from app.services import holidays
from app.services import terms as terms_service


def test_a_statutory_day_stops_lessons_and_an_observance_does_not():
    # 9 May 2026 is a Saturday, 5 October 2026 a Monday; the weekday is not
    # what decides it, which is the whole point of asking both ways round.
    assert holidays.stops_lessons(Date(2026, 5, 9))
    assert not holidays.stops_lessons(Date(2026, 10, 5))

    teachers_day = holidays.holiday_on(Date(2026, 10, 5))
    assert teachers_day is not None
    assert teachers_day.title == "День учителя"
    assert not teachers_day.stops_lessons


def test_an_ordinary_date_is_not_a_holiday():
    assert holidays.holiday_on(Date(2026, 9, 14)) is None
    assert not holidays.stops_lessons(Date(2026, 9, 14))


def test_every_code_names_one_run_of_days_in_one_month():
    """A code is what a client matches on to write the name in its own words.

    Two unrelated dates sharing one would make «Новогодние каникулы» and
    something in April indistinguishable to it. The January run shares a code
    on purpose — it is one holiday several days long — so the rule is that a
    shared code is one stretch in one month.

    The stretch may be *interrupted* but not scattered, and writing this test
    the strict way found out why: 7 January is Рождество Христово, sitting in
    the middle of the six days before it and the one after, which all share
    `ru_new_year`. That is what the Labour Code says and it is not an error in
    the table — so what is checked is that every date between the first and
    the last of a code is itself a named day, rather than that they are
    consecutive.
    """
    everything = {**holidays.PUBLIC, **holidays.OBSERVANCES}
    seen: dict[str, list[tuple[int, int]]] = {}
    for key, holiday in everything.items():
        seen.setdefault(holiday.code, []).append(key)

    for code, keys in seen.items():
        months = {month for month, _ in keys}
        assert len(months) == 1, f"{code} spans more than one month: {sorted(keys)}"
        month = months.pop()
        days = sorted(day for _, day in keys)
        for day in range(days[0], days[-1] + 1):
            assert (month, day) in everything, (
                f"{code} runs from {days[0]} to {days[-1]} but {day} is not a named day, "
                "so this code is scattered rather than one stretch"
            )


async def test_a_public_holiday_inside_a_term_takes_the_lessons_off_it(session, school_class):
    """8 March 2027 is a Monday, which is the weekday this class teaches on.

    Picked for exactly that: a statutory day off that lands on a Saturday
    proves nothing, because the class has no lessons on Saturdays either.
    """
    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 3, 8), 8)}

    assert not days[Date(2027, 3, 8)].has_lessons
    assert days[Date(2027, 3, 8)].kind is DayKind.HOLIDAY
    assert days[Date(2027, 3, 8)].off_reason is DayOffReason.PUBLIC_HOLIDAY
    assert days[Date(2027, 3, 8)].holiday is not None
    assert days[Date(2027, 3, 8)].holiday.title == "Международный женский день"

    # The Monday a week later is an ordinary one, and proves the class really
    # does teach on Mondays — without which the assertion above means nothing.
    assert days[Date(2027, 3, 15)].has_lessons
    assert days[Date(2027, 3, 15)].off_reason is None


async def test_an_observance_keeps_its_lessons_and_still_says_what_the_day_is(
    session, school_class
):
    """5 October 2026 is a Monday — a full day of lessons with a badge on it."""
    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2026, 10, 5), 1)}

    day = days[Date(2026, 10, 5)]
    assert day.has_lessons
    assert day.kind is DayKind.NORMAL
    assert day.off_reason is None
    assert day.holiday is not None and day.holiday.code == "un_teachers_day"


async def test_the_summer_reads_as_the_summer_rather_than_as_a_holiday_in_it(
    session, school_class
):
    """12 June is «День России» and it is also the middle of the holidays.

    Both are true, and the one somebody scrolling through an empty June needs
    told is that the year is over — a single red day in the middle of it would
    say the other eleven were ordinary.
    """
    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 6, 12), 1)}

    day = days[Date(2027, 6, 12)]
    assert day.off_reason is DayOffReason.OUT_OF_YEAR
    # The name is still attached: the badge is right, the reason is the season.
    assert day.holiday is not None and day.holiday.code == "ru_russia_day"


async def test_the_gap_between_terms_is_told_apart_from_the_summer(session, school_class):
    """Two stretches with no lessons, and a calendar wants to accent them differently."""
    year = 2026
    await terms_service.set_scheme(session, school_class, TermKind.QUARTER, year)
    rows = await terms_service.read(session, school_class.id, year)
    await terms_service.set_bounds(
        session, school_class, year, 1, rows[0].starts_on, Date(2026, 10, 26)
    )
    await terms_service.set_bounds(
        session, school_class, year, 2, Date(2026, 11, 5), rows[1].ends_on
    )
    await session.commit()

    resolver = ScheduleResolver(session, school_class)
    autumn = {day.date: day for day in await resolver.resolve_range(Date(2026, 11, 2), 1)}
    assert autumn[Date(2026, 11, 2)].off_reason is DayOffReason.BETWEEN_TERMS

    resolver = ScheduleResolver(session, school_class)
    summer = {day.date: day for day in await resolver.resolve_range(Date(2027, 7, 5), 1)}
    assert summer[Date(2027, 7, 5)].off_reason is DayOffReason.OUT_OF_YEAR


async def test_a_day_marked_by_hand_on_a_public_holiday_keeps_what_it_was_given(
    session, school_class
):
    """A school that really does hold something on 8 March has said so."""
    session.add(
        DayOverride(
            class_id=school_class.id,
            date=Date(2027, 3, 8),
            kind=DayKind.REMOTE,
            note="Праздничный концерт",
        )
    )
    await session.commit()

    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 3, 8), 1)}

    day = days[Date(2027, 3, 8)]
    assert day.kind is DayKind.REMOTE
    assert day.note == "Праздничный концерт"
    # Still no lessons: the template is what the holiday takes away, and the
    # day itself is whatever its owner said it was.
    assert not day.has_lessons
    assert day.off_reason is DayOffReason.PUBLIC_HOLIDAY


async def test_the_bundle_carries_the_name_and_the_reason(school_class):
    transport = ASGITransport(app=app)
    async with AsyncClient(transport=transport, base_url="http://test") as client:
        joined = await client.post(
            "/api/v1/join", json={"code": "TEST42", "device_name": "pytest"}
        )
        assert joined.status_code == 200, joined.text
        token = joined.json()["token"]

        response = await client.get(
            "/api/v1/bundle",
            params={"start": "2026-10-05", "days": 1},
            headers={"Authorization": f"Bearer {token}"},
        )
    assert response.status_code == 200, response.text

    day = response.json()["days"][0]
    assert day["holiday"] == {
        "code": "un_teachers_day",
        "title": "День учителя",
        "stops_lessons": False,
    }
    assert day["off_reason"] is None


# --------------------------------------------------------------------------
# What the bot puts on the card
# --------------------------------------------------------------------------


async def test_the_day_card_names_the_holiday_and_says_why_it_is_empty(
    session, school_class
):
    """«Уроков нет» is true of four different days and useful on one.

    Somebody looking at a blank Monday in March wants to know whether it is
    the holidays, a public holiday, or a timetable with nothing on it — those
    are three different things to do next, and the card used to answer all of
    them with the same three words.
    """
    from app.bot.render import render_day

    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 3, 8), 1)}

    card = render_day(days[Date(2027, 3, 8)], today=Date(2027, 3, 1))
    assert "Международный женский день" in card
    assert "Праздничный день — уроков нет." in card


async def test_the_card_of_a_summer_day_says_the_year_is_over(session, school_class):
    from app.bot.render import render_day

    resolver = ScheduleResolver(session, school_class)
    days = {day.date: day for day in await resolver.resolve_range(Date(2027, 7, 5), 1)}

    card = render_day(days[Date(2027, 7, 5)], today=Date(2027, 7, 1))
    assert "Учебный год окончен — уроков нет." in card


async def test_an_ordinary_empty_day_still_just_says_there_are_no_lessons(
    session, school_class
):
    """A Sunday in term time. Nothing decided it but the timetable, and
    inventing a reason for it would be inventing one."""
    from app.bot.render import render_day

    resolver = ScheduleResolver(session, school_class)
    # 20 September 2026 is a Sunday, inside the year, not a named date.
    days = {day.date: day for day in await resolver.resolve_range(Date(2026, 9, 20), 1)}

    card = render_day(days[Date(2026, 9, 20)], today=Date(2026, 9, 14))
    assert "Уроков нет." in card
    assert "Праздничный" not in card
    assert "Каникулы" not in card


async def test_the_next_school_day_steps_over_a_public_holiday(session, school_class):
    """«Дальше» and the homework card both ask this, and both would be wrong.

    `next_school_day` walks forward until it finds a day with lessons on it,
    and it walks through `_resolve_day` — so the holiday rule reaches it for
    free. Free is not the same as checked: this is the one caller that asks
    the resolver a question about a date nobody requested, and a class whose
    Monday is 9 May should be pointed at the Monday after it rather than at a
    day the whole country is off.
    """
    # 8 March 2027 is a Monday and a statutory day off; this class teaches on
    # Mondays and nothing else, so the next one it has is the 15th.
    resolver = ScheduleResolver(session, school_class)
    following = await resolver.next_school_day(Date(2027, 3, 1))

    assert following is not None
    assert following.date == Date(2027, 3, 15)


# --------------------------------------------------------------------------
# Every kind has to be sayable, everywhere a kind is said
# --------------------------------------------------------------------------


def test_every_day_kind_can_be_said():
    """A kind with no words is a `KeyError` or the word «День».

    `DayKind` is read from five tables — the card's label, the list's label,
    the audit's sentence, the single-day picker's buttons and the period
    picker's — and adding a member touches none of them. Two of those reads are
    bare subscripts, so a gap is an exception out of a callback handler, which
    never reaches `callback.answer()` and leaves the button spinning until
    Telegram gives up. The other three fall back to «День», which is not a
    crash and is arguably worse: somebody who chose «самоподготовка» is shown a
    word they did not pick.

    This caught exactly that, one commit after `SELF_STUDY` and `DAY_OFF` were
    added and `_KIND_SUMMARY` was not.

    `NORMAL` is excluded throughout: the absence of an override is what an
    ordinary day is, so it never has a row, a button or a line of its own.
    """
    from app.bot.handlers.manage import _KIND_BY_TAG, _KIND_SUMMARY
    from app.bot.manage_keyboards import PERIOD_KINDS, day_kind_keyboard
    from app.bot.manage_render import KIND_LABELS
    from app.bot.render import DAY_KIND_LABELS

    special = [kind for kind in DayKind if kind is not DayKind.NORMAL]

    for name, table in (
        ("DAY_KIND_LABELS", DAY_KIND_LABELS),
        ("KIND_LABELS", KIND_LABELS),
        ("_KIND_SUMMARY", _KIND_SUMMARY),
    ):
        missing = [kind.value for kind in special if kind not in table]
        assert missing == [], f"{name} has no words for {missing}"

    # Every kind the picker can send back is one the handler can resolve, and
    # every kind that exists is one the picker offers — a kind nobody can
    # choose is a kind that exists only in the database.
    assert set(_KIND_BY_TAG.values()) == set(special)

    buttons = [
        button.callback_data.rsplit(":", 1)[-1]
        for row in day_kind_keyboard("2026-09-14").inline_keyboard
        for button in row
        if button.callback_data and button.callback_data.startswith("dk|kind|")
    ]
    assert "normal" in buttons, "the way back to an ordinary day is still offered"
    for kind in special:
        assert kind.value in buttons, f"the single-day picker cannot choose {kind.value}"

    # The period picker is allowed to offer fewer — `NORMAL` and `SHORTENED`
    # are excluded there on purpose, and that exclusion is its own test — but
    # everything it does offer has to be a real kind.
    assert {kind for kind, _ in PERIOD_KINDS} <= set(special)


# --------------------------------------------------------------------------
# Narrowing the bot's list of marked days
# --------------------------------------------------------------------------


async def test_the_bot_list_can_be_narrowed_to_one_kind(session, school_class):
    """Five kinds in one list is a list somebody scrolls rather than reads.

    The filter lives in the callback payload rather than in FSM state, the way
    the editor's pager already does: a card on a screen is a card somebody may
    come back to in an hour, and a filter held in state would have expired by
    then or be applied to whatever screen they are on instead.
    """
    from app.bot.handlers.manage import _holiday_view

    today = Date(2026, 9, 14)
    for offset, kind in enumerate((DayKind.HOLIDAY, DayKind.REMOTE, DayKind.SELF_STUDY)):
        session.add(
            DayOverride(
                class_id=school_class.id,
                date=today + timedelta(days=offset + 400),
                kind=kind,
            )
        )
    await session.commit()

    everything, _ = await _holiday_view(session, school_class, Role.ADMIN)
    for label in ("Каникулы", "Дистанционное", "Самоподготовка"):
        assert label in everything

    narrowed, _ = await _holiday_view(session, school_class, Role.ADMIN, DayKind.SELF_STUDY)
    assert "Самоподготовка" in narrowed
    assert "Дистанционное" not in narrowed


async def test_an_empty_filter_says_which_kind_of_empty_it_is(session, school_class):
    """«Нет вовсе» and «нет таких» are two different things to do next.

    Telling somebody who narrowed to «дистанционно» that there are no special
    days at all answers a question they did not ask, and sends them off to
    mark days that are already there under another kind.
    """
    from app.bot.handlers.manage import _holiday_view

    bare, _ = await _holiday_view(session, school_class, Role.ADMIN)
    assert "Впереди особых дней нет" in bare

    session.add(
        DayOverride(
            class_id=school_class.id, date=Date(2027, 4, 5), kind=DayKind.HOLIDAY
        )
    )
    await session.commit()

    filtered, _ = await _holiday_view(session, school_class, Role.ADMIN, DayKind.REMOTE)
    assert "Впереди особых дней нет" not in filtered
    assert "Впереди нет дней" in filtered
    assert "«Все» покажет остальные" in filtered


async def test_a_crafted_filter_narrows_to_nothing_rather_than_raising(
    session, school_class
):
    """Callback data is whatever the client sent.

    `DayKind(raw)` raises, and a raise here never reaches `callback.answer()`
    — the press keeps its spinner until Telegram gives up. The guard turns it
    into «no filter», which is the harmless reading of an unreadable one.
    """
    from app.bot.handlers.manage import _day_kind_or_none

    assert _day_kind_or_none("../../etc/passwd") is None
    assert _day_kind_or_none("") is None
    assert _day_kind_or_none(DayKind.SELF_STUDY.value) is DayKind.SELF_STUDY


async def test_the_filter_row_offers_every_kind_the_list_can_hold(session, school_class):
    """A kind that can be marked and cannot be filtered is one that hides in a
    list of five, which is the thing this row exists to prevent."""
    from app.bot.manage_keyboards import holiday_list_keyboard

    keyboard = holiday_list_keyboard([], can_edit=True, can_period=True)
    values = {
        button.callback_data.split("|", 2)[-1]
        for row in keyboard.inline_keyboard
        for button in row
        if button.callback_data and button.callback_data.startswith("dk|list")
    }

    for kind in DayKind:
        if kind is DayKind.NORMAL:
            continue
        assert kind.value in values, f"the filter row cannot narrow to {kind.value}"
    assert "" in values, "«Все» has to be there, or a filter cannot be taken off"
