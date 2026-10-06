"""«📊 Проект»: the deployment's numbers, read without writing, drawn within budget.

The screen's numbers are read from a database seeded with one of everything
they count, and the reading is watched for writes; the renderer is handed the
pathological input in one line - five hundred builds and four failing checks
with the longest reasons - and has to send; and everything that came from
outside is escaped. Who may see it is held through the real dispatcher, in
``test_bot_commands.py``, because aiogram attaches a router to one
dispatcher only.
"""

from __future__ import annotations

from datetime import UTC, date, datetime, timedelta
from types import SimpleNamespace
from zoneinfo import ZoneInfo

from sqlalchemy.dialects import postgresql

from app.bot import project_render
from app.bot.manage_keyboards.class_card import class_menu
from app.bot.project_keyboard import ProjectAction
from app.config import Settings
from app.db import EXPECTED_REVISION
from app.models import (
    BotUser,
    DeviceToken,
    DiarySession,
    HealthCheck,
    ReminderSettings,
    Role,
    SchoolClass,
)
from app.services import project_stats
from app.services.project_stats import CheckRow, ProjectStats
from app.wording import MESSAGE_LIMIT

MOSCOW = ZoneInfo("Europe/Moscow")


def _now() -> datetime:
    return datetime.now(UTC).replace(tzinfo=None)


def _stats(**overrides) -> ProjectStats:
    now = _now()
    values = dict(
        now=now,
        checks=[],
        commit="a" * 40,
        environment="production",
        region="fra1",
        started_at=now - timedelta(minutes=12),
        python="3.12.13",
        revision=EXPECTED_REVISION,
        expected_revision=EXPECTED_REVISION,
        database_bytes=None,
        connections=None,
        last_tick=None,
        tick_before=None,
        mornings_today=0,
        evenings_today=0,
        classes=1,
        accounts=1,
        phones_day=0,
        phones_week=0,
        builds=[],
        diaries=[],
    )
    values.update(overrides)
    return ProjectStats(**values)


def _check(name: str, status: str, reason: str = "", **overrides) -> CheckRow:
    now = _now()
    values = dict(
        name=name,
        status=status,
        reason=reason,
        since=now - timedelta(hours=1),
        checked_at=now,
        previous_checked_at=now - timedelta(minutes=5),
        round_trip_ms=None,
    )
    values.update(overrides)
    return CheckRow(**values)


async def test_the_numbers_are_the_deployment_s_and_reading_them_writes_nothing(
    session, school_class, statement_writes
):
    now = _now()
    other = SchoolClass(name="5Б", join_code="OTHER5B1")
    session.add(other)
    await session.flush()
    session.add_all(
        [
            BotUser(telegram_id=2001, class_id=school_class.id, role=Role.ADMIN),
            BotUser(telegram_id=2001, class_id=other.id, role=Role.VIEWER),
            BotUser(telegram_id=2002, class_id=other.id, role=Role.EDITOR),
            DeviceToken(token_hash="a" * 64, class_id=school_class.id, client_version=412,
                        last_seen_at=now - timedelta(hours=2)),
            DeviceToken(token_hash="b" * 64, class_id=school_class.id, client_version=412,
                        last_seen_at=now - timedelta(days=3)),
            DeviceToken(token_hash="c" * 64, class_id=other.id, client_version=None,
                        last_seen_at=now - timedelta(days=30)),
            DeviceToken(token_hash="d" * 64, class_id=other.id, client_version=413,
                        revoked=True, last_seen_at=now),
            DiarySession(token_hash="e" * 64, upstream_token="x", login="a", provider=None),
            DiarySession(token_hash="f" * 64, upstream_token="x", login="b",
                         provider="netschool"),
            DiarySession(token_hash="g" * 64, upstream_token="x", login="c",
                         provider="netschool", expired_at=now),
            ReminderSettings(class_id=school_class.id, telegram_id=2001,
                             last_morning_sent=datetime.now(MOSCOW).date()),
            ReminderSettings(class_id=other.id, telegram_id=2002,
                             last_morning_sent=date(2026, 1, 1),
                             last_evening_sent=datetime.now(MOSCOW).date()),
            HealthCheck(name="v2", status="ok", reason="mounted", since=now - timedelta(days=1),
                        checked_at=now - timedelta(minutes=2),
                        previous_checked_at=now - timedelta(minutes=7)),
        ]
    )
    await session.commit()

    with statement_writes() as seen:
        stats = await project_stats.gather(session, Settings(), now)

    assert seen == []
    assert (stats.classes, stats.accounts) == (2, 2)
    assert (stats.phones_day, stats.phones_week) == (1, 2)
    assert stats.builds == [(412, 2), (None, 1)]
    assert stats.diaries == [("netschool", 1), ("petersburg", 1)]
    assert (stats.mornings_today, stats.evenings_today) == (1, 1)
    assert [check.name for check in stats.checks] == ["v2"]
    assert stats.last_tick == now - timedelta(minutes=2)
    assert stats.tick_before == now - timedelta(minutes=7)
    # SQLite: no alembic_version in the suite's database, and no catalogue.
    assert (stats.revision, stats.expected_revision) == (None, EXPECTED_REVISION)
    assert (stats.database_bytes, stats.connections) == (None, None)


async def test_on_postgres_the_size_and_the_connections_are_its_own_catalogue_s():
    """CI has no Postgres, so the two statements are read off a session that
    says it is one."""
    asked: list[str] = []

    class _Postgres:
        def get_bind(self):
            return SimpleNamespace(dialect=postgresql.dialect())

        async def scalar(self, statement):
            asked.append(str(statement))
            return 12_900_000 if "pg_database_size" in str(statement) else 3

    assert await project_stats.postgres_numbers(_Postgres()) == (12_900_000, 3)
    assert asked == [
        "SELECT pg_database_size(current_database())",
        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database()",
    ]


def test_the_screen_shows_each_block():
    now = _now()
    stats = _stats(
        now=now,
        checks=[
            _check("schema", "ok"),
            _check("v2", "ok"),
            _check("diary_proxy", "ok", "HTTP 200", round_trip_ms=312),
            _check("deploy", "failing", "running aaaaaaa, main is bbbbbbb since 2026-10-06"),
        ],
        database_bytes=12_900_000,
        connections=3,
        last_tick=now - timedelta(minutes=2),
        tick_before=now - timedelta(minutes=7),
        mornings_today=12,
        evenings_today=8,
        builds=[(412, 15), (None, 4)],
        diaries=[("petersburg", 3)],
    )
    text = project_render.render_project(stats, MOSCOW)

    for line in (
        "<b>📊 Проект</b>",
        "Коммит: <code>aaaaaaa</code> · голова main: 🔴",
        "Регион: fra1 · Python 3.12.13",
        "Экземпляр жив: 12 мин",
        f"Схема: {EXPECTED_REVISION}, код ждёт {EXPECTED_REVISION}",
        "Размер: 12,3 МБ · соединений: 3",
        "Сводок сегодня: утренних 12, вечерних 8",
        "• сборка 412 — 15",
        "• без версии — 4",
        "Дневники: petersburg — 3",
        "v2: включён",
        project_render.GRAPHS,
    ):
        assert line in text.splitlines(), line
    assert "✅ отвечает · 312 мс · с " in text
    assert "(2 мин назад), до него: 5 мин" in text
    assert "https://" not in text


def test_a_check_never_run_says_so():
    text = project_render.render_health([_check("v2", "ok")], MOSCOW)
    assert text.splitlines()[0] == "<b>🩺 Состояние</b>"
    assert "❔ Схема базы — ещё не проверялось" in text
    assert "✅ v2 — с " in text
    assert len(text.splitlines()) == 1 + 4


def test_five_hundred_builds_and_four_long_failures_still_send():
    reason = "<" * 200
    stats = _stats(
        checks=[_check(name, "failing", reason) for name in project_render.CHECKS],
        builds=[(build, 1) for build in range(1000, 500, -1)],
        diaries=[(f"provider-{n}", n) for n in range(20)],
    )
    text = project_render.render_project(stats, MOSCOW)

    assert len(text) <= MESSAGE_LIMIT
    assert "… и ещё 490" in text
    assert text.count("• сборка") == project_render.BUILDS_MAX


def test_what_came_from_outside_is_escaped():
    stats = _stats(
        checks=[_check("diary_proxy", "failing", "no answer: <ProxyError> & co")],
        diaries=[("<b>bold</b>", 1)],
        region="fra1<script>",
        commit="<a href>",
    )
    text = project_render.render_project(stats, MOSCOW)

    assert "&lt;ProxyError&gt; &amp; co" in text
    assert "&lt;b&gt;bold&lt;/b&gt; — 1" in text
    assert "fra1&lt;script&gt;" in text
    assert "<ProxyError>" not in text and "<script>" not in text and "<b>bold" not in text


def test_the_class_card_opens_the_screen_for_the_deployment_s_owner_alone():
    def buttons(menu) -> list[str]:
        return [button.callback_data for row in menu.inline_keyboard for button in row]

    opened = ProjectAction(action="open").pack()
    owner = class_menu(is_owner=True, many_classes=False, pending=0, deployment_owner=True)
    class_owner = class_menu(is_owner=True, many_classes=False, pending=0)

    assert opened in buttons(owner)
    assert opened not in buttons(class_owner)
