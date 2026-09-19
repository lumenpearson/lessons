"""What a coloured inline button means in this bot.

Bot API 9.3 gave ``InlineKeyboardButton`` a ``style``, and the four values it
takes — danger, success, primary, link — are colours, not roles: Telegram
paints them and says nothing about when each is right. Deciding that once, in
one file, is the whole point of this one. A colour picked per keyboard drifts
within a week and then carries nothing: a red button that is «удалить» on one
page and «назад» on the next has to be read anyway, so the paint has cost a
glance and bought nothing.

Three of the four are used, and each answers one question about the button:

``DANGER``
    It takes something away, or throws away what you were doing — delete,
    cancel, reject, revoke access, switch off. Not on the way *out* of a
    destructive confirmation: when «Да, удалить» is red, painting the escape
    beside it red too leaves the pair with nothing to tell them apart, and the
    escape is the safe half.

``SUCCESS``
    It commits, or it is where you already are — approve, apply, add, and the
    day that is today. That second half is the part worth
    having: a keyboard has no other way to show state, and until now today was
    ``«13»``, guillemets around a number, which reads as a quotation rather
    than as now.

``PRIMARY``
    A span of time, or the way between spans — the arrows, weeks, periods, the
    weekday picker, «Ещё ›».

Everything else stays unpainted, deliberately. Colour only separates while
most of the keyboard is plain; paint half the buttons and the three meanings
above go back to being decoration. The plain buttons are the background the
coloured ones are read against.

``LINK`` is left alone: this bot's buttons all act inside it, and a fourth
colour with no question of its own would only dilute the three that have one.

None of this is load bearing. A client too old for ``style`` draws the plain
button and the label still says what it does — the colour is a second, faster
reading of a keyboard that already worked.
"""

from __future__ import annotations

from aiogram.enums import ButtonStyle

#: Removes, refuses or abandons.
DANGER = ButtonStyle.DANGER

#: Commits, or marks the one row that is the current state.
SUCCESS = ButtonStyle.SUCCESS

#: Spans of time and movement between them.
PRIMARY = ButtonStyle.PRIMARY
