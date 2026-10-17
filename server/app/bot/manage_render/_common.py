"""What more than one management page prints."""

from __future__ import annotations

from html import escape


def person(
    name: str | None,
    username: str | None,
    telegram_id: int | None,
    *,
    limit: int | None = None,
) -> str:
    """The best name we hold for somebody, already escaped.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can actually act on.

    ``limit`` cuts a long ``name`` to that many characters, ellipsis included
    - *before* escaping, as ``editor_render.slot_label`` already does for a
    subject, because cutting after can leave a severed entity («&am») that is
    a refused message of its own. ``None`` (every caller but one, today)
    leaves a name exactly as long as whatever is held for it; a page whose
    keyboard builds one row per item regardless of a name's length
    (``manage_render.devices.OWNER_NAME_MAX``) passes one instead, because
    without it a handful of long names cut the page to fewer rows than the
    keyboard offers (#352). A username is never cut: Telegram already bounds
    it to 32 characters, far under any budget a page needs.
    """
    if username:
        return f"@{escape(username)}"
    if name:
        if limit is not None and len(name) > limit:
            name = name[: limit - 1].rstrip() + "…"
        return escape(name)
    return str(telegram_id) if telegram_id is not None else "—"
