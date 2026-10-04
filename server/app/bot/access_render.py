"""The member list on «👥 Доступ», and the caps ``handlers/access`` reads with it."""

from __future__ import annotations

from html import escape

from app.wording import clamp, cut, more_line

#: Members listed on «👥 Доступ», and the number the role picker builds its
#: buttons from.
#:
#: The same number in both places, imported by ``handlers/access`` rather than
#: written there again: while they were twenty and «all of them», a class where
#: both parents of thirty pupils had joined drew sixty names above twenty
#: buttons — forty people named on screen whose role nothing could change, and
#: no «… и ещё N» saying so, because the renderer counted from itself. It is
#: also the only screen the join mode can be switched back from, and at sixty
#: members the page went over Telegram's ceiling and stopped opening at all.
ACCESS_MEMBERS_MAX = 20

#: Pending phone invites listed under them. Fewer, because an invite is a row
#: nobody re-reads: it is a thing waiting to happen or to be revoked.
ACCESS_INVITES_MAX = 10

#: An invite's free-text label, which the API accepts at 120 characters.
ACCESS_LABEL_MAX = 60

#: A pending access request's note on the «👥 Доступ» heading, which
#: ``handlers/access`` draws above this list. The column is ``String(300)`` and
#: five of them may be on the page at once.
ACCESS_REQUEST_NOTE_MAX = 120


def render_access_list(members: list, invites: list, limit: int) -> str:
    """The member list, inside ``limit`` characters.

    ``limit`` is a parameter because this list is never the whole message: the
    handler draws pending requests above it and the join-mode explanation below
    it, and while the budget here was the whole of ``MESSAGE_LIMIT`` those two
    blocks were simply extra. Thirty members with the long names Telegram
    allows, ten pending phone invites and three access requests with their
    300-character notes came to 4623 characters, so «👥 Доступ» answered
    «что-то пошло не так» — and that is the only screen the join mode can be
    switched back from.
    """
    lines = ["<b>👥 Доступ к классу</b>", ""]
    if members:
        for member in members[:ACCESS_MEMBERS_MAX]:
            name = escape(member.full_name or member.username or str(member.telegram_id))
            handle = f" (@{escape(member.username)})" if member.username else ""
            lines.append(f"• {name}{handle} — <b>{member.role.title_ru}</b>")
        lines.extend(more_line(len(members), ACCESS_MEMBERS_MAX))
    else:
        lines.append("<i>Пока никого нет.</i>")

    pending = [invite for invite in invites if not invite.is_used]
    if pending:
        lines.append("")
        lines.append("<b>Приглашения по номеру</b>")
        for invite in pending[:ACCESS_INVITES_MAX]:
            label = f" — {escape(cut(invite.label, ACCESS_LABEL_MAX))}" if invite.label else ""
            lines.append(f"⏳ +{invite.phone} → {invite.role.title_ru}{label}")
        lines.extend(more_line(len(pending), ACCESS_INVITES_MAX))
    return clamp(lines, limit)
