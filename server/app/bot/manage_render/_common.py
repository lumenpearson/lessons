"""What more than one management page prints."""

from __future__ import annotations

from html import escape


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
