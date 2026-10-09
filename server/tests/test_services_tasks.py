"""What v1's ``public.py`` held for a phone's own things, in ``services/``.

v1's ``/tasks``, ``/homework/{id}/done``, ``/me``, ``/me/unlink`` and
``/calendar`` held these rules in their router: a task's link to homework
checked against the class, a reminder stored as the class's wall time, a
patch applied field by field with ``done`` through ``set_done``, a tick set
rather than toggled, no link code for a phone already linked, the code's deep
link, unlinking a phone that is not linked, and the feed's address. v2's
``MeService`` needs every one of them, so they moved before its handlers were
written, with v1 calling them (``docs/specs/2026-10-05-server-v2-design.md``,
decision 2). ``test_api_extended.py``, untouched, is the proof that v1's
answers did not move; these hold the rules a fact at a time.
"""

from __future__ import annotations

from datetime import UTC, date, datetime

import pytest
from sqlalchemy import func, select

from app import wording
from app.models import DeviceToken, Homework, HomeworkDone, PersonalTask, SchoolClass
from app.security import hash_token
from app.services import calendar, linking, tasks
from app.services import homework as homework_service

MONDAY = date(2026, 9, 7)


async def _other_class_s_homework(session) -> Homework:
    other = SchoolClass(name="10Б", join_code="OTHER1")
    session.add(other)
    await session.flush()
    foreign = Homework(class_id=other.id, due_date=MONDAY, subject_name="Алгебра", text="№ 1")
    session.add(foreign)
    await session.commit()
    return foreign


async def _homework(session, school_class) -> Homework:
    own = Homework(class_id=school_class.id, due_date=MONDAY, subject_name="Физика", text="§ 3")
    session.add(own)
    await session.commit()
    return own


async def test_a_task_from_the_api_is_checked_against_the_class_and_reminded_on_its_clock(
    session, school_class
) -> None:
    foreign = await _other_class_s_homework(session)
    with pytest.raises(tasks.HomeworkNotInClass):
        await tasks.create_task(session, school_class, 42, "Чужое", homework_id=foreign.id)
    assert await session.scalar(select(func.count()).select_from(PersonalTask)) == 0

    own = await _homework(session, school_class)
    # 05:00 UTC is 08:00 in Moscow, the class's zone.
    linked = await tasks.create_task(
        session,
        school_class,
        42,
        "Своё",
        homework_id=own.id,
        remind_at=datetime(2026, 9, 15, 5, 0, tzinfo=UTC),
    )
    assert (linked.homework_id, linked.remind_at) == (own.id, datetime(2026, 9, 15, 8, 0))
    # A naive reminder is wall time already.
    plain = await tasks.create_task(
        session, school_class, 42, "Без зоны", remind_at=datetime(2026, 9, 15, 8, 0)
    )
    assert plain.remind_at == datetime(2026, 9, 15, 8, 0)


async def test_an_update_changes_what_it_names_and_stamps_done_only_when_it_changes(
    session, school_class
) -> None:
    task = await tasks.add_task(
        session, school_class.id, 42, "Купить тетрадь", notes="в клетку", due_date=MONDAY
    )
    await tasks.update_task(session, school_class, task, {"title": "Две тетради", "due_date": None})
    assert (task.title, task.due_date, task.notes) == ("Две тетради", None, "в клетку")

    await tasks.update_task(session, school_class, task, {"done": True})
    stamped = task.done_at
    assert task.done is True and stamped is not None
    await tasks.update_task(session, school_class, task, {"done": True})
    assert task.done_at is stamped
    await tasks.update_task(session, school_class, task, {"done": False})
    assert (task.done, task.done_at) == (False, None)

    await tasks.update_task(
        session, school_class, task, {"remind_at": datetime(2026, 9, 15, 5, 0, tzinfo=UTC)}
    )
    assert task.remind_at == datetime(2026, 9, 15, 8, 0)

    foreign = await _other_class_s_homework(session)
    with pytest.raises(tasks.HomeworkNotInClass):
        await tasks.update_task(
            session, school_class, task, {"title": "Чужое", "homework_id": foreign.id}
        )
    # Checked before anything changed.
    assert (task.title, task.homework_id) == ("Две тетради", None)


async def test_a_tick_is_set_not_toggled(session, school_class) -> None:
    homework = await _homework(session, school_class)
    ticks = select(func.count()).select_from(HomeworkDone)
    assert await tasks.set_homework_done(session, homework, 42, True) is True
    assert await tasks.set_homework_done(session, homework, 42, True) is True
    assert await session.scalar(ticks) == 1
    assert await tasks.set_homework_done(session, homework, 42, False) is False
    assert await tasks.set_homework_done(session, homework, 42, False) is False
    assert await session.scalar(ticks) == 0


async def test_homework_is_found_only_in_its_own_class(session, school_class) -> None:
    own = await _homework(session, school_class)
    foreign = await _other_class_s_homework(session)
    assert await homework_service.homework_of(session, school_class.id, own.id) is own
    assert await homework_service.homework_of(session, school_class.id, foreign.id) is None
    assert await homework_service.homework_of(session, school_class.id, 999_999) is None


async def test_a_linked_phone_gets_no_link_code_and_an_unlinked_one_keeps_its_own(
    session, school_class
) -> None:
    unlinked = DeviceToken(token_hash=hash_token("unlinked"), class_id=school_class.id)
    linked = DeviceToken(token_hash=hash_token("linked"), class_id=school_class.id, telegram_id=42)
    session.add_all([unlinked, linked])
    await session.commit()
    code = await linking.link_code_for(session, unlinked)
    assert code is not None and len(code) == linking.LINK_CODE_LENGTH
    assert await linking.link_code_for(session, unlinked) == code
    assert await linking.link_code_for(session, linked) is None
    assert linked.link_code is None


def test_the_deep_link_names_the_bot_or_nothing() -> None:
    expected = "https://t.me/lessons_bot?start=link_ABC234"
    assert linking.deep_link("@lessons_bot", "ABC234") == expected
    assert linking.deep_link("lessons_bot", "ABC234") == expected
    assert linking.deep_link("", "ABC234") is None
    assert linking.deep_link("@", "ABC234") is None


async def test_unlinking_a_phone_no_account_is_behind_changes_nothing(
    session, school_class
) -> None:
    unlinked = DeviceToken(
        token_hash=hash_token("unlinked"), class_id=school_class.id, link_code="ABC234"
    )
    linked = DeviceToken(
        token_hash=hash_token("linked"),
        class_id=school_class.id,
        telegram_id=42,
        linked_at=datetime(2026, 9, 1),
    )
    session.add_all([unlinked, linked])
    await session.commit()
    await linking.unlink_self(session, unlinked)
    assert unlinked.link_code == "ABC234"
    assert not session.dirty
    await linking.unlink_self(session, linked)
    assert (linked.telegram_id, linked.linked_at, linked.link_code) == (None, None, None)


def test_the_feed_s_address_is_v1_s_path_on_any_origin() -> None:
    expected = "https://lessons.example.com/api/v1/calendar/s3cret.ics"
    assert calendar.feed_url("https://lessons.example.com", "s3cret") == expected
    assert calendar.feed_url("https://lessons.example.com/", "s3cret") == expected


def test_the_phone_s_own_sentences_are_v1_s() -> None:
    assert wording.UNKNOWN_TASK_DETAIL == "Unknown task"
    assert wording.UNKNOWN_HOMEWORK_DETAIL == "Unknown homework"
    assert wording.HOMEWORK_NOT_IN_CLASS_DETAIL == "homework_id is not in this class"
    # Written inline in v1's router until it moved.
    assert tasks.LIST_MAX == 200
