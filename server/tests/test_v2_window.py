"""``GetScheduleWindow``: one school year, v1's days in v2's shape, writing nothing.

The window is asked against v1's ``/bundle`` for the same class over the same
dates, day by day, because «the same answer v1 gives» is what decision 10
claims for terms computed rather than seeded. Its tag is asked in both
directions on both paths, and the dense year of the design's risks is held
under 2 MB (``docs/specs/2026-10-05-server-v2-design.md``).
"""

from __future__ import annotations

import re
from datetime import UTC, date, datetime, time, timedelta

import pytest
from sqlalchemy import select

from app.contract.lessons.v2.common_pb import TermKind
from app.contract.lessons.v2.options_pb import Role as ProtoRole
from app.contract.lessons.v2.schedule_pb import DayOffReason, GetScheduleWindowRequest
from app.models import (
    BellPeriod,
    BotUser,
    DayEvent,
    EventKind,
    Homework,
    LessonOverride,
    OverrideAction,
    Role,
    SchoolClass,
    Term,
    TimetableEntry,
    WeekParity,
)
from app.services import terms as terms_service

#: The school year 2026/27: Tuesday 1 September 2026 to Monday 31 May 2027.
YEAR = 2026
FIRST, LAST = date(2026, 9, 1), date(2027, 5, 31)
EVERY_DAY = ("window.generated_at",)


def _window(year: int = YEAR, **kw) -> GetScheduleWindowRequest:
    return GetScheduleWindowRequest(year=year, **kw)


def _as_v1(day) -> dict:
    """A v2 day in v1's ``DayOut`` spelling, to compare the two field for field."""
    return {
        "date": day.date,
        "weekday": day.weekday,
        "kind": day.kind.name.lower(),
        "note": day.note if day.has_field("note") else None,
        "holiday": {
            "code": day.holiday.code,
            "title": day.holiday.title,
            "stops_lessons": day.holiday.stops_lessons,
        }
        if day.has_field("holiday")
        else None,
        "off_reason": None
        if day.off_reason is DayOffReason.UNSPECIFIED
        else day.off_reason.name.lower(),
        "lessons": [
            {
                "index": lesson.index,
                "subject": lesson.subject,
                "starts_at": lesson.starts_at,
                "ends_at": lesson.ends_at,
                "room": lesson.room if lesson.has_field("room") else None,
                "teacher": lesson.teacher if lesson.has_field("teacher") else None,
                "color": lesson.color if lesson.has_field("color") else None,
                "is_replaced": lesson.is_replaced,
                "is_cancelled": lesson.is_cancelled,
                "note": lesson.note if lesson.has_field("note") else None,
            }
            for lesson in day.lessons
        ],
        "events": [
            {
                "title": event.title,
                "kind": event.kind.name.lower(),
                "starts_at": event.starts_at,
                "ends_at": event.ends_at,
                "location": event.location if event.has_field("location") else None,
                "covers_lesson": event.covers_lesson,
            }
            for event in day.events
        ],
        "homework": [
            {
                "subject": item.subject,
                "text": item.text,
                "attachment_url": item.attachment_url if item.has_field("attachment_url") else None,
            }
            for item in day.homework
        ],
    }


def _v1_day(day: dict) -> dict:
    """v1's day with its times cut to v2's ``HH:MM``, the one difference the
    contract makes on purpose."""
    for lesson in day["lessons"]:
        lesson["starts_at"], lesson["ends_at"] = lesson["starts_at"][:5], lesson["ends_at"][:5]
    for event in day["events"]:
        event["starts_at"], event["ends_at"] = event["starts_at"][:5], event["ends_at"][:5]
    return day


async def test_the_window_is_the_whole_school_year(v2, v2_tokens) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(),
        token=v2_tokens["unlinked"],
        differ=EVERY_DAY,
    )
    days = answer.message.window.days
    assert (days[0].date, days[-1].date) == (FIRST.isoformat(), LAST.isoformat())
    assert len(days) == (LAST - FIRST).days + 1
    monday = next(day for day in days if day.date == "2026-09-07")
    assert [(x.index, x.subject, x.starts_at, x.ends_at) for x in monday.lessons] == [
        (1, "Алгебра", "08:30", "09:15"),
        (2, "Физика", "09:25", "10:10"),
        (3, "История", "10:25", "11:10"),
    ]


async def test_every_day_is_v1_s_day_and_the_terms_v1_would_have_seeded(
    v2, v2_tokens, session, school_class
) -> None:
    """Asked of v2 first, so that v1's own seeding on its read cannot help it."""
    session.add(
        Homework(
            class_id=school_class.id,
            due_date=date(2026, 9, 7),
            subject_name="Алгебра",
            text="№ 1–5",
        )
    )
    session.add(
        DayEvent(
            class_id=school_class.id,
            date=date(2026, 9, 8),
            starts_at=time(12, 0),
            ends_at=time(13, 0),
            title="Экскурсия",
            kind=EventKind.TRIP,
            covers_lesson=True,
        )
    )
    await session.commit()
    token = v2_tokens["editor"]
    v2_answer = (await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)).message
    v1 = (
        await v2.http.get(
            "/api/v1/bundle",
            params={"start": FIRST.isoformat(), "days": (LAST - FIRST).days + 1},
            headers={"Authorization": f"Bearer {token}"},
        )
    ).json()

    assert [_as_v1(day) for day in v2_answer.window.days] == [_v1_day(day) for day in v1["days"]]
    summary = v2_answer.window.school_class
    assert summary.term_kind is TermKind.QUARTER
    assert [(t.index, t.starts_on, t.ends_on) for t in summary.terms] == [
        (t["index"], t["starts_on"], t["ends_on"]) for t in v1["school_class"]["terms"]
    ]
    device = v2_answer.window.device
    assert (device.linked, device.role, device.can_edit) == (True, ProtoRole.EDITOR, True)


async def test_stored_terms_are_answered_as_stored(v2, v2_tokens, session, school_class) -> None:
    await terms_service.ensure(session, school_class, YEAR)
    first = await session.scalar(
        select(Term).where(Term.class_id == school_class.id, Term.index == 1)
    )
    first.ends_on = date(2026, 10, 26)
    await session.commit()
    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    terms = answer.message.window.school_class.terms
    assert (terms[0].ends_on, terms[1].starts_on) == ("2026-10-26", "2026-11-01")
    holidays = [d for d in answer.message.window.days if d.date == "2026-10-28"]
    assert holidays[0].off_reason is DayOffReason.BETWEEN_TERMS


async def test_a_senior_class_with_no_terms_is_shown_half_years(
    v2, v2_tokens, session, school_class
) -> None:
    klass = await session.get(SchoolClass, school_class.id)
    klass.grade = 10
    await session.commit()
    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    summary = answer.message.window.school_class
    assert summary.term_kind is TermKind.SEMESTER
    assert [(t.index, t.starts_on, t.ends_on) for t in summary.terms] == [
        (1, "2026-09-01", "2026-12-31"),
        (2, "2027-01-01", "2027-05-31"),
    ]


@pytest.mark.parametrize("year", [0, 1999, 2099, 10000])
async def test_a_year_outside_the_bounds_is_refused_on_its_field(v2, v2_tokens, year) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow", _window(year), token=v2_tokens["unlinked"]
    )
    assert (answer.status, answer.code, answer.reason) == (
        400,
        "INVALID_ARGUMENT",
        "VALIDATION_FAILED",
    )
    assert answer.violations == [("year", "year must be between 2000 and 2098")]


async def test_the_first_and_last_years_are_served(v2, v2_tokens) -> None:
    for year in (2000, 2098):
        answer = await v2.rest(
            "ScheduleService/GetScheduleWindow", _window(year), token=v2_tokens["unlinked"]
        )
        assert answer.status == 200, year


# ---- the tag ---------------------------------------------------------------


async def test_an_unchanged_window_keeps_its_tag_and_its_answer_moves_on(v2, v2_tokens) -> None:
    token = v2_tokens["unlinked"]
    first = await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    second = await v2.connect("ScheduleService/GetScheduleWindow", _window(), token=token)
    assert first.message.etag == second.message.etag == first.headers["etag"]
    assert re.fullmatch(r'"[0-9a-f]{64}"', first.message.etag)
    generated = first.message.window.generated_at.to_datetime()
    assert abs(generated - datetime.now(UTC)) < timedelta(minutes=1)


@pytest.mark.parametrize(
    "shape",
    ["{tag}", "W/{tag}", '"another", {tag}', "*"],
    ids=["exact", "weak", "listed", "star"],
)
async def test_a_matching_tag_answers_not_modified_on_both_paths(v2, v2_tokens, shape) -> None:
    token = v2_tokens["unlinked"]
    tag = (await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)).message.etag
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(if_none_match=shape.format(tag=tag)),
        token=token,
    )
    assert answer.status == 304 and answer.body == b""
    assert answer.headers["etag"] == tag
    assert answer.message.not_modified is True and not answer.message.has_field("window")


async def test_a_stale_tag_gets_the_window(v2, v2_tokens) -> None:
    answer = await v2.both(
        "ScheduleService/GetScheduleWindow",
        _window(if_none_match='"stale"'),
        token=v2_tokens["unlinked"],
        differ=EVERY_DAY,
    )
    assert answer.status == 200 and answer.message.has_field("window")


async def test_the_tag_moves_with_the_class_and_with_the_device_s_role(
    v2, v2_tokens, session, school_class
) -> None:
    token = v2_tokens["viewer"]
    before = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    session.add(
        Homework(
            class_id=school_class.id, due_date=date(2026, 9, 7), subject_name="Физика", text="§ 3"
        )
    )
    await session.commit()
    after_homework = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    member = await session.scalar(select(BotUser).where(BotUser.telegram_id == 2001))
    member.role = Role.EDITOR
    await session.commit()
    after_role = (
        await v2.rest("ScheduleService/GetScheduleWindow", _window(), token=token)
    ).message.etag
    assert len({before, after_homework, after_role}) == 3


# ---- writing nothing, and the size of a dense year -------------------------


async def test_the_window_writes_nothing_where_v1_seeds_and_adopts(
    v2, v2_tokens, statement_writes
) -> None:
    # The answer is held too: an unserved method writes nothing either, and
    # would pass the write assertions below without ever having read a window.
    with statement_writes() as v2_writes:
        answer = await v2.both(
            "ScheduleService/GetScheduleWindow",
            _window(),
            token=v2_tokens["unlinked"],
            differ=EVERY_DAY,
        )
    with statement_writes() as v1_writes:
        await v2.http.get(
            "/api/v1/bundle", headers={"Authorization": f"Bearer {v2_tokens['unlinked']}"}
        )

    assert answer.status == 200 and answer.message is not None
    assert all(s.startswith("UPDATE device_tokens SET last_seen_at=?") for s in v2_writes), (
        v2_writes
    )
    assert any(s.startswith("INSERT INTO terms") for s in v1_writes), (
        "the probe saw v1 seed nothing"
    )
    assert any(s.startswith("INSERT INTO subjects") for s in v1_writes), (
        "the probe saw v1 adopt nothing"
    )


async def test_a_dense_year_stays_under_two_megabytes(v2, v2_tokens, session, school_class) -> None:
    """The design's risks: Vercel fails a response over 4.5 MB. Six weekdays of
    eight lessons, a homework of 200 characters on every lesson of every
    teaching day, two events and one substitution a week."""
    session.add(
        BellPeriod(
            schedule_id=school_class.bell_schedule_id,
            index=8,
            starts_at=time(15, 15),
            ends_at=time(16, 0),
        )
    )
    for weekday in range(1, 7):
        for index in range(1, 9):
            if weekday == 1 and index <= 3:
                continue
            session.add(
                TimetableEntry(
                    class_id=school_class.id,
                    weekday=weekday,
                    index=index,
                    subject_name=f"Предмет {index}",
                    room=str(200 + index),
                    parity=WeekParity.ANY,
                )
            )
    day = FIRST
    while day <= LAST:
        if day.isoweekday() <= 6:
            for index in range(1, 9):
                session.add(
                    Homework(
                        class_id=school_class.id,
                        due_date=day,
                        subject_name=f"Предмет {index}",
                        text="Прочитать параграф и ответить на вопросы. " * 5,
                    )
                )
        if day.isoweekday() in (2, 4):
            session.add(
                DayEvent(
                    class_id=school_class.id,
                    date=day,
                    starts_at=time(16, 0),
                    ends_at=time(17, 0),
                    title="Кружок",
                    kind=EventKind.EVENT,
                )
            )
        if day.isoweekday() == 3:
            session.add(
                LessonOverride(
                    class_id=school_class.id,
                    date=day,
                    index=2,
                    action=OverrideAction.REPLACE,
                    subject_name="Замена",
                    room="101",
                )
            )
        day += timedelta(days=1)
    await session.commit()

    answer = await v2.rest(
        "ScheduleService/GetScheduleWindow", _window(), token=v2_tokens["unlinked"]
    )
    assert answer.status == 200
    assert len(answer.body) < 2_000_000, len(answer.body)
    assert sum(len(d.homework) for d in answer.message.window.days) > 1500
