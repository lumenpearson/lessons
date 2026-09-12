"""Text for the management pages.

Kept apart from ``render.py`` for the same reason ``manage_keyboards`` is kept
apart from ``keyboards``: the day-to-day views and the structural ones are read
and changed by different people at different times, and a helper renamed in one
must never be able to quietly reword the other.

Everything here escapes what it is handed. The rule across this codebase is
that a row is stored exactly as it was typed and escaped once, at render time -
so an admin who names a subject «Алгебра & геометрия» sees that name back and
not an entity, and a name typed with an angle bracket cannot close a tag the
message opened.
"""

from __future__ import annotations

from datetime import UTC, datetime, timedelta
from datetime import date as Date
from html import escape

from app.bot.render import DAY_KIND_LABELS, MONTHS_GENITIVE, WEEKDAYS, plural
from app.models import DayKind, DayOverride, Role

#: Telegram refuses a message over 4096 characters. Splitting at 4000 leaves
#: room for the header a caller adds to each part.
CHUNK_LIMIT = 4000

#: Rows listed before «… и ещё N». Twenty lines is about a phone screen and a
#: half; past that the reader scrolls instead of reading.
LIST_MAX = 20

#: What one message may grow to. Telegram's ceiling is 4096 characters *after*
#: entity parsing; the margin covers the tags. Every list here is already
#: capped by row count, but a row carries free text — a note, a задание, an
#: audit summary — and thirty long ones would overrun a limit that no row
#: count can express.
MESSAGE_LIMIT = 3900


def _utcnow() -> datetime:
    """Naive UTC, matching the naive ``DateTime`` columns the model declares."""
    return datetime.now(UTC).replace(tzinfo=None)


def time_ago(moment: datetime | None, now: datetime | None = None) -> str:
    """«только что» / «5 мин назад» / «2 ч назад» / «3 дн назад».

    ``moment`` is a naive UTC column value, like every timestamp in the schema;
    ``None`` - a device that has never phoned home - is «никогда».

    The units are abbreviated on purpose. «мин», «ч» and «дн» do not inflect,
    so the line is grammatical for every count - which a full word would not be
    without carrying three forms through a value nobody reads twice. Anything
    older than a month is shown as a date instead, because «47 дн назад» is not
    a fact anyone can hold.
    """
    if moment is None:
        return "никогда"

    delta = (now or _utcnow()) - moment
    seconds = delta.total_seconds()
    # A clock skew between the writer and the reader must not read as "in the
    # future"; the phone was, in fact, just here.
    if seconds < 120:
        return "только что"
    minutes = int(seconds // 60)
    if minutes < 60:
        return f"{minutes} мин назад"
    hours = minutes // 60
    if hours < 24:
        return f"{hours} ч назад"
    days = hours // 24
    if days <= 30:
        return f"{days} дн назад"
    return f"{moment:%d.%m.%Y}"


def swatch(colour: str | None) -> str:
    """«■ #5B6ABF» - the square is the colour's only preview Telegram allows."""
    if not colour:
        return "—"
    return f"■ {escape(colour)}"


def person(name: str | None, username: str | None, telegram_id: int | None) -> str:
    """The best name we hold for somebody, already escaped.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can actually act on.
    """
    if username:
        return f"@{escape(username)}"
    if name:
        return escape(name)
    return str(telegram_id) if telegram_id is not None else "—"


def more_line(total: int, shown: int) -> list[str]:
    hidden = total - shown
    return [f"… и ещё {hidden}"] if hidden > 0 else []


def clamp(lines: list[str], limit: int = MESSAGE_LIMIT) -> str:
    """Join lines, dropping the tail that would not fit and saying how many.

    Cutting from the end rather than shortening every line: the rows are
    ordered by what matters most (the nearest date, the newest change), so the
    ones that survive are the ones worth reading.
    """
    kept: list[str] = []
    used = 0
    for position, line in enumerate(lines):
        cost = len(line) + 1
        if used + cost > limit:
            rest = len(lines) - position
            kept.append("… и ещё " + plural(rest, "строка", "строки", "строк"))
            break
        kept.append(line)
        used += cost
    return "\n".join(kept)


def cut(text: str, limit: int) -> str:
    """One long free-text value, shortened for a list where it is one row."""
    text = " ".join(text.split())
    return text if len(text) <= limit else text[: limit - 1].rstrip() + "…"


def split_text(text: str, limit: int = CHUNK_LIMIT) -> list[str]:
    """Cut a long message into Telegram-sized parts on line boundaries.

    A single line longer than the limit is cut mid-line rather than dropped:
    losing a line of somebody's timetable to keep the formatting tidy would be
    the wrong trade in an export that doubles as a backup.
    """
    if not text:
        return [""]

    parts: list[str] = []
    current: list[str] = []
    used = 0
    for line in text.split("\n"):
        while len(line) > limit:
            if current:
                parts.append("\n".join(current))
                current, used = [], 0
            parts.append(line[:limit])
            line = line[limit:]
        extra = len(line) + (1 if current else 0)
        if used + extra > limit:
            parts.append("\n".join(current))
            current, used = [line], len(line)
            continue
        current.append(line)
        used += extra
    if current:
        parts.append("\n".join(current))
    return parts or [""]


# --------------------------------------------------------------------------
# Subjects
# --------------------------------------------------------------------------


def render_subjects(subjects: list) -> str:
    lines = ["<b>📚 Предметы</b>", ""]
    if not subjects:
        lines.append("<i>Список пуст. «🔄 Собрать из расписания» создаст его по урокам.</i>")
        return "\n".join(lines)

    for subject in subjects[: LIST_MAX * 2]:
        parts = [f"<b>{escape(subject.name)}</b>"]
        if subject.short_name:
            parts.append(escape(subject.short_name))
        if subject.teacher:
            parts.append(escape(subject.teacher))
        parts.append(swatch(subject.color))
        lines.append("• " + " · ".join(parts))
    lines.extend(more_line(len(subjects), LIST_MAX * 2))
    return clamp(lines)


def render_subject_card(subject) -> str:
    return "\n".join(
        [
            f"<b>{escape(subject.name)}</b>",
            "",
            f"Сокращение: {escape(subject.short_name) if subject.short_name else '—'}",
            f"Учитель: {escape(subject.teacher) if subject.teacher else '—'}",
            f"Цвет: {swatch(subject.color)}",
            "",
            "<i>Переименование предмета переименует его и в расписании, "
            "и в домашних заданиях, и в заменах.</i>",
        ]
    )


# --------------------------------------------------------------------------
# Особые дни
# --------------------------------------------------------------------------

#: The «обычный день» kind never has a row of its own - that is what deleting
#: the override means - so it needs no label here.
KIND_LABELS = dict(DAY_KIND_LABELS)


def render_holidays(overrides: list[DayOverride], schedules: dict[int, str], today: Date) -> str:
    lines = ["<b>🏖 Особые дни</b>", ""]
    if not overrides:
        lines.append("<i>Впереди особых дней нет.</i>")
        return "\n".join(lines)

    for override in overrides[:LIST_MAX]:
        label = KIND_LABELS.get(override.kind, "День")
        row = f"<b>{override.date:%d.%m}</b> · {label}"
        if override.kind is DayKind.SHORTENED and override.bell_schedule_id:
            name = schedules.get(override.bell_schedule_id)
            if name:
                row += f" · 🔔 {escape(name)}"
        if override.note:
            row += f" · {escape(override.note)}"
        if override.date == today:
            row += " · сегодня"
        lines.append(row)
    lines.extend(more_line(len(overrides), LIST_MAX))
    return clamp(lines)


def render_period_result(first: Date, last: Date, created: int) -> str:
    return (
        f"✅ Каникулы с <b>{first:%d.%m}</b> по <b>{last:%d.%m}</b>: "
        f"отмечено {plural(created, 'день', 'дня', 'дней')}."
    )


# --------------------------------------------------------------------------
# Звонки
# --------------------------------------------------------------------------


def render_bells(schedules: list, default_id: int | None) -> str:
    lines = ["<b>🔔 Расписания звонков</b>", ""]
    if not schedules:
        lines.append("<i>Ни одного расписания ещё нет.</i>")
        return "\n".join(lines)

    for schedule in schedules[:LIST_MAX]:
        star = "⭐ " if schedule.id == default_id else ""
        periods = schedule.periods
        lines.append(
            f"{star}<b>{escape(schedule.name)}</b> — "
            f"{plural(len(periods), 'урок', 'урока', 'уроков')}"
        )
        for period in periods[:12]:
            lines.append(
                f"<code>{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}</code>"
            )
        lines.extend(more_line(len(periods), 12))
        lines.append("")
    lines.append("⭐ — основное расписание класса.")
    return clamp(lines)


def render_bell_rows(schedule) -> str:
    """The stored rows in exactly the format the editor accepts back."""
    return "\n".join(
        f"{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}"
        for period in schedule.periods
    )


# --------------------------------------------------------------------------
# Устройства
# --------------------------------------------------------------------------


def render_devices(devices: list, owners: dict[int, tuple[str, Role | None]]) -> str:
    """«📱 Pixel 8 · привязан: @user (Редактор) · был 2 ч назад».

    ``owners`` maps a Telegram id to (display name, role in this class) - the
    role is looked up per device by the caller, because a device acts with
    whatever role its owner holds right now, not with one stored on the row.
    """
    lines = ["<b>📱 Устройства класса</b>", ""]
    if not devices:
        lines.append(
            "<i>Ни одного телефона не подключено. Код класса — /code, "
            "его вводят в приложении.</i>"
        )
        return "\n".join(lines)

    for device in devices[:LIST_MAX]:
        name = escape(device.device_name or f"Устройство {device.id}")
        if device.telegram_id is None:
            link = "не привязан"
        else:
            who, role = owners.get(device.telegram_id, (str(device.telegram_id), None))
            suffix = f" ({role.title_ru})" if role is not None else " (без роли в классе)"
            link = f"привязан: {who}{suffix}"
        seen = (
            "ещё не выходил на связь"
            if device.last_seen_at is None
            else f"был {time_ago(device.last_seen_at)}"
        )
        lines.append(f"📱 <b>{name}</b> · {link} · {seen}")
    lines.extend(more_line(len(devices), LIST_MAX))
    return clamp(lines)


# --------------------------------------------------------------------------
# Журнал
# --------------------------------------------------------------------------


def render_audit(entries: list, names: dict[int, str], tz, offset: int = 0) -> str:
    """«12.09 14:05 · @user · добавлено ДЗ», newest first.

    ``created_at`` is stored as naive UTC, so it is read as UTC and shown in the
    class's own zone - an admin in Vladivostok reading a Moscow server's log
    should not see yesterday's evening on this morning's change.
    """
    header = "<b>📜 Журнал изменений</b>" + (f" · с {offset + 1}" if offset else "")
    lines = [header, ""]
    if not entries:
        lines.append("<i>Записей пока нет.</i>")
        return "\n".join(lines)

    for entry in entries:
        stamp = entry.created_at
        if stamp is not None:
            local = stamp.replace(tzinfo=UTC).astimezone(tz)
            when = f"{local:%d.%m %H:%M}"
        else:
            when = "—"
        who = names.get(entry.telegram_id, str(entry.telegram_id or "система"))
        lines.append(f"<code>{when}</code> · {who} · {escape(cut(entry.summary, 160))}")
    return clamp(lines)


# --------------------------------------------------------------------------
# Карточка класса
# --------------------------------------------------------------------------


def render_class_card(
    school_class,
    *,
    zone_label: str,
    feed_ready: bool,
    members: int,
    devices: int,
    pending: int,
) -> str:
    lines = [
        f"<b>⚙️ {escape(school_class.name)}</b>",
        "",
        f"🏫 Школа: {escape(school_class.school) if school_class.school else '—'}",
        f"🏙 Город: {escape(school_class.city) if school_class.city else '—'}",
        f"🕒 Часовой пояс: {escape(zone_label)}",
        f"🔑 Код для приложения: <code>{escape(school_class.join_code)}</code>",
        f"📅 Календарь: {'ссылка выдана' if feed_ready else 'ссылка ещё не создавалась'}",
        "",
        f"👥 Участников: {members} · 📱 устройств: {devices}",
    ]
    if pending:
        lines.append(f"🙋 Запросов доступа: <b>{pending}</b>")
    return "\n".join(lines)


# --------------------------------------------------------------------------
# Поиск, импорт, календарь
# --------------------------------------------------------------------------


def render_search(needle: str, rows: list, today: Date) -> str:
    """Search hits, newest due first. The needle is echoed back escaped too -
    it is the one string on this page that certainly came from a keyboard."""
    lines = [f"<b>🔎 Поиск: {escape(needle)}</b>", ""]
    if not rows:
        lines.append("<i>Ничего не нашлось. Ищу по заданиям за последний месяц и впереди.</i>")
        return "\n".join(lines)

    for item in rows:
        day = item.due_date
        when = f"{day.day} {MONTHS_GENITIVE[day.month - 1]}"
        if day == today:
            when = "сегодня"
        elif day == today + timedelta(days=1):
            when = "завтра"
        lines.append(
            f"• <b>{escape(item.subject_name)}</b> ({when}, {WEEKDAYS[day.weekday()]}): "
            f"{escape(cut(item.text, 200))}"
        )
    return clamp(lines)


def render_import_preview(days: dict[int, list], rejected: list[str]) -> str:
    lines = ["<b>📥 Импорт расписания</b>", ""]
    for weekday in sorted(days):
        count = len(days[weekday])
        lines.append(
            f"• {WEEKDAYS[weekday - 1].capitalize()}: "
            f"{plural(count, 'урок', 'урока', 'уроков')}"
        )
    if not days:
        lines.append(
            "<i>Ни одного дня не распознано — нужен заголовок "
            "вида «== Понедельник ==».</i>"
        )
    if rejected:
        lines.append("")
        lines.append("⚠️ Не разобрал строки:")
        for line in rejected[:10]:
            lines.append(f"<code>{escape(line)}</code>")
        lines.extend(more_line(len(rejected), 10))
    if days:
        lines.append("")
        lines.append(
            "«Применить» заменит эти дни целиком. Остальные дни недели останутся как есть."
        )
    return clamp(lines)


def render_calendar(url: str | None, rotated: bool = False) -> str:
    if url is None:
        return (
            "📅 <b>Календарь</b>\n\n"
            "Адрес сервера не настроен — задайте <code>PUBLIC_BASE_URL</code>, "
            "и ссылка появится здесь."
        )
    head = "🔁 <b>Новая ссылка на календарь</b>" if rotated else "📅 <b>Календарь класса</b>"
    lines = [
        head,
        "",
        f"<code>{escape(url)}</code>",
        "",
        "• Google Календарь: «Другие календари» → «Подписаться по URL» → вставьте ссылку.",
        "• iPhone: «Настройки» → «Календарь» → «Учётные записи» → «Другое» → "
        "«Подписной календарь».",
    ]
    if rotated:
        lines.append("")
        lines.append("<i>Старая ссылка больше не работает — раздайте новую заново.</i>")
    return "\n".join(lines)
