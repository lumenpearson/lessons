"""«📱 Устройства»: the phones on the class, and when each was last seen."""

from __future__ import annotations

from datetime import UTC, datetime
from html import escape

from app.bot.render import clamp, more_line
from app.models import Role

#: Phones drawn on «📱 Устройства», and the rows ``manage_keyboards`` builds
#: for them; ``subjects.SUBJECTS_MAX`` says why each page has a number of its own.
DEVICES_MAX = 15


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

    for device in devices[:DEVICES_MAX]:
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
    lines.extend(more_line(len(devices), DEVICES_MAX))
    return clamp(lines)
