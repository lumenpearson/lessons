"""«📜 Журнал»: one page of the class's log."""

from __future__ import annotations

from datetime import UTC
from html import escape

from app.bot.render import clamp, cut

#: Audit lines per page of «📜 Журнал».
#:
#: Here rather than in the handler, for the reason the caps in ``subjects``,
#: ``bells``, ``devices`` and ``holidays`` are here: `manage_keyboards.audit_log`
#: builds «Ещё ›» from this number and the handler reads the same page with it.
#: While the pager had its own literal 30 the two agreed by coincidence, and
#: changing one would have skipped or repeated log lines with nothing to say
#: so.
AUDIT_PAGE = 30


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
