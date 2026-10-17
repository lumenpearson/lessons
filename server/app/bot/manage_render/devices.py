"""«📱 Устройства»: the phones on the class, and when each was last seen."""

from __future__ import annotations

from datetime import UTC, datetime

from app.bot.manage_render._common import cut_escaped
from app.bot.render import clamp, more_line
from app.models import Role

#: Phones drawn on «📱 Устройства», and the rows ``manage_keyboards.devices``
#: builds for them; ``subjects.SUBJECTS_MAX`` says why each page has a number of
#: its own.
DEVICES_MAX = 15

#: How many *escaped* characters a linked owner's name (``manage_render.
#: _common.person``'s ``limit``, no @username) or a device's own name
#: (below) may draw before this page cuts it with «…»
#: (:func:`manage_render._common.cut_escaped`). Unlike every other page's
#: cap, these are not about how many *rows* fit — they are about keeping all
#: ``DEVICES_MAX`` of them inside ``clamp``'s ceiling at once, so the
#: keyboard (which always draws one row per device, capped at
#: ``DEVICES_MAX``, never asking the text) never offers a button the page
#: has already dropped (#352). A *raw*-length cap does not do this: nothing
#: stops either name from being nothing but ``&``, which is one raw
#: character and five escaped ones, so capping the raw length still let one
#: field or the other balloon to five times the cap once escaped - which is
#: why both are cut by the length ``cut_escaped`` actually draws.
#:
#: Both names must survive whole well past anything a person or a phone
#: really carries - a two-or-three-word Cyrillic name is at most some forty
#: characters and has no ``&``, ``<`` or ``>`` to grow under escaping, so a
#: budget of 50 never touches it. The worst row the two budgets must still
#: fit together: a device name and an owner's name (no @username) each made
#: of the character that grows the most under escaping (``&`` → ``&amp;``,
#: five for one) and cut at 50, no role left in the class (« (без роли в
#: классе)», 20 characters - longer than any ``Role.title_ru`` including
#: «Администратор»), the longest build a 32-bit ``client_version`` can hold
#: (2147483647, ten digits), and the longest «был …» text («ещё не выходил
#: на связь», 23 characters, longer than any relative time it competes
#: with). At 50, fifteen such rows plus the header come to 2743 of
#: ``MESSAGE_LIMIT``'s 3900 - comfortably clear, because ``cut_escaped``'s
#: own ellipsis reservation keeps the actual escaped length a few characters
#: under the budget it is given, not at it. Both budgets can rise to 85
#: together before the same worst case first drops a row; 50 is held well
#: back from that line. See ``test_bot_manage.py``'s worst-case devices test
#: for the sum.
OWNER_NAME_MAX = 50

#: The device's own name, cut the same way and for the same reason; see
#: ``OWNER_NAME_MAX`` just above for both budgets' arithmetic together.
DEVICE_NAME_MAX = 50


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
    """«📱 Pixel 8 · привязан: @user (Редактор) · был 2 ч назад · сборка 412».

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
        name = cut_escaped(device.device_name or f"Устройство {device.id}", DEVICE_NAME_MAX)
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
        # The app's build, as the phone last sent it with a v2 request: which
        # phones still run an old APK before v1 is retired (the server-v2
        # design, decision 15). An integer from a column, so nothing to escape;
        # a phone that never sent one — every APK that speaks only v1 — says
        # nothing about a version.
        build = (
            f" · сборка {device.client_version}" if device.client_version is not None else ""
        )
        lines.append(f"📱 <b>{name}</b> · {link} · {seen}{build}")
    lines.extend(more_line(len(devices), DEVICES_MAX))
    return clamp(lines)
