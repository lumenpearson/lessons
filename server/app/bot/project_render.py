"""«📊 Проект» and its first block alone, «🩺 Состояние» (``handlers/project``).

One message, so one budget: :func:`render_project` is clamped like every
page that grows with the data, and the one list that grows without bound -
phones by app build, one row per APK still in the wild - stops at
:data:`BUILDS_MAX` and says «… и ещё N». Everything that did not come from a
constant here is escaped: a check's reason, a diary provider's name, the
commit and the region. Times are shown in the deployment's zone.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import UTC, datetime
from html import escape
from zoneinfo import ZoneInfo

from app.services.health import CHECKS, FAILING, OK, UNKNOWN
from app.services.project_stats import CheckRow, ProjectStats
from app.wording import clamp, cut, duration, more_line

#: Rows of «Сборки» before «… и ещё N».
BUILDS_MAX = 10

#: The longest reason quoted on a check's line.
REASON_SHOWN = 120

CHECK_TITLES = {
    "schema": "Схема базы",
    "v2": "v2",
    "diary_proxy": "Прокси дневника",
    "deploy": "Деплой",
}
ICONS = {OK: "✅", FAILING: "🔴", UNKNOWN: "❔"}

#: The dashboards, by name and never by link: a link names the owner's
#: accounts, and ``docs/deploy.md`` keeps them.
GRAPHS = (
    "Vercel → Observability (запросы, ошибки, время по маршрутам), "
    "Sentry → Issues и Performance, Neon → Monitoring. Ссылки — в docs/deploy.md."
)


def _when(stamp: datetime, zone: ZoneInfo) -> str:
    return f"{stamp.replace(tzinfo=UTC).astimezone(zone):%d.%m %H:%M}"


def _minutes(span) -> int:
    return max(0, int(span.total_seconds() // 60))


def _code(text: str, limit: int = REASON_SHOWN) -> str:
    return f"<code>{escape(cut(text, limit))}</code>"


def state_lines(checks: Sequence[CheckRow], zone: ZoneInfo) -> list[str]:
    """«🩺 Состояние»: each check, its state, since when, and why when it is not ok."""
    by_name = {check.name: check for check in checks}
    lines = ["<b>🩺 Состояние</b>"]
    for name in CHECKS:
        title = CHECK_TITLES[name]
        check = by_name.get(name)
        if check is None:
            lines.append(f"❔ {title} — ещё не проверялось")
            continue
        line = f"{ICONS.get(check.status, '❔')} {title} — с {_when(check.since, zone)}"
        if check.status != OK and check.reason:
            line += f" · {_code(check.reason)}"
        lines.append(line)
    return lines


def render_health(checks: Sequence[CheckRow], zone: ZoneInfo) -> str:
    """``/health``: the first block of «📊 Проект», alone."""
    return clamp(state_lines(checks, zone))


def render_project(stats: ProjectStats, zone: ZoneInfo) -> str:
    by_name = {check.name: check for check in stats.checks}
    lines = ["<b>📊 Проект</b>", ""]
    lines += state_lines(stats.checks, zone)

    lines += ["", "<b>🖥 Сервер</b>"]
    deploy = by_name.get("deploy")
    on_main = ICONS.get(deploy.status, "❔") if deploy is not None else "❔"
    commit = _code(stats.commit[:7]) if stats.commit else "не известен"
    lines.append(f"Коммит: {commit} · голова main: {on_main}")
    where = escape(stats.region) if stats.region else "не Vercel"
    lines.append(f"Регион: {where} · Python {escape(stats.python)}")
    lines.append(f"Экземпляр жив: {duration(_minutes(stats.now - stats.started_at))}")

    lines += ["", "<b>🗄 База</b>"]
    revision = escape(stats.revision) if stats.revision else "не известна"
    lines.append(f"Схема: {revision}, код ждёт {escape(stats.expected_revision)}")
    if stats.database_bytes is not None:
        size = f"{stats.database_bytes / 1024 / 1024:.1f}".replace(".", ",")
        lines.append(f"Размер: {size} МБ · соединений: {stats.connections}")

    lines += ["", "<b>🛰 Прокси дневника</b>"]
    proxy = by_name.get("diary_proxy")
    if proxy is None:
        lines.append("❔ ещё не проверялось")
    elif proxy.status == OK:
        took = f" · {proxy.round_trip_ms} мс" if proxy.round_trip_ms is not None else ""
        lines.append(f"✅ отвечает{took} · с {_when(proxy.since, zone)}")
    elif proxy.status == FAILING:
        lines.append(f"🔴 не отвечает · с {_when(proxy.since, zone)} · {_code(proxy.reason)}")
    else:
        lines.append(f"❔ {_code(proxy.reason)}")

    lines += ["", "<b>⏰ Часы</b>"]
    if stats.last_tick is None:
        lines.append("Тиков ещё не было")
    else:
        ago = duration(_minutes(stats.now - stats.last_tick))
        tick = f"Последний тик: {_when(stats.last_tick, zone)} ({ago} назад)"
        if stats.tick_before is not None:
            tick += f", до него: {duration(_minutes(stats.last_tick - stats.tick_before))}"
        lines.append(tick)
    lines.append(
        f"Сводок сегодня: утренних {stats.mornings_today}, вечерних {stats.evenings_today}"
    )

    lines += ["", "<b>📦 Проект</b>"]
    lines.append(f"Классов: {stats.classes} · аккаунтов: {stats.accounts}")
    lines.append(f"Телефонов за сутки: {stats.phones_day} · за неделю: {stats.phones_week}")
    if stats.builds:
        lines.append("Сборки:")
        shown = stats.builds[:BUILDS_MAX]
        for build, phones in shown:
            label = f"сборка {build}" if build is not None else "без версии"
            lines.append(f"• {label} — {phones}")
        lines += more_line(len(stats.builds), len(shown))
    if stats.diaries:
        listed = ", ".join(f"{escape(name)} — {sessions}" for name, sessions in stats.diaries)
        lines.append(f"Дневники: {listed}")
    v2 = by_name.get("v2")
    v2_state = {OK: "включён", FAILING: "выключен"}.get(v2.status if v2 else "", "не известно")
    lines.append(f"v2: {v2_state}")

    lines += ["", "<b>📈 Где графики</b>", GRAPHS]
    return clamp(lines)
