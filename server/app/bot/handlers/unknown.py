"""The answer to a press or a command nothing in this bot handles.

Telegram itself stays silent on an unknown command, and for a bot with one
screen that is fine. This one has twenty-odd forms, and since a command now
breaks out of a half-finished one (``CommandBreakoutMiddleware``), silence
became actively misleading: «/wek» — somebody reaching for «/week» — dropped
what they were filling in, said so, and then nothing happened. The reader is
left holding a closed form and no idea whether the typo did something.

So it answers. This router is **included last** and matches nothing but a
command, so every handler that has one wins first; what reaches here is by
definition a command nothing in the bot answers.
"""

from __future__ import annotations

from aiogram import Router
from aiogram.enums import ChatType
from aiogram.types import CallbackQuery, Message

from app.bot.middlewares import looks_like_command

router = Router(name="unknown")

#: Points at «/help» rather than listing anything: the list is one command away
#: and would be a second place to keep in step with the real one.
UNKNOWN_COMMAND = "🤔 Не знаю такой команды. Наберите /help, чтобы увидеть список."


def _is_unknown_command(message: Message) -> bool:
    """Whether this is a command, in a chat where answering one is ours to do.

    Private chats only. In a group Telegram delivers «/start@otherbot» to every
    bot that can see it, and a bot that answers «не знаю такой команды» to a
    command addressed to somebody else is noise in a chat it was invited to for
    one thing. Nothing in this bot is built for a group anyway — every flow
    here is one person and their class.
    """
    return message.chat.type == ChatType.PRIVATE and looks_like_command(
        message.text or message.caption
    )


@router.message(_is_unknown_command)
async def unknown_command(message: Message) -> None:
    await message.answer(UNKNOWN_COMMAND)


#: What a button on a card the bot has forgotten says when it is pressed.
#:
#: Cut for `answerCallbackQuery`, which refuses past 200 characters, and shown
#: as an alert rather than a toast: a toast on a card that then does nothing
#: reads as the tap having missed.
STALE_CARD = "Эта карточка устарела — откройте экран заново."


@router.callback_query()
async def stale_callback(callback: CallbackQuery) -> None:
    """Answer a press that reached no handler, so the button stops spinning.

    Telegram spins a button until `answerCallbackQuery` arrives or it gives up,
    and a press that matches nothing never gets one — aiogram returns
    `UNHANDLED` without raising, so `bot._on_error` cannot help either. There
    was no path to this until commands started breaking out of forms: «📝
    Задать ДЗ» → pick a day → type «/week», and the subject card is left above
    the week with live buttons and no state behind them. Twelve handlers across
    six modules are filtered on an FSM state with no sibling for the same
    payload, and every one of them was that dead press.

    Registered on the router that is included last, so nothing that does handle
    a press can reach here. The cost is the other side of that: a handler that
    silently stops matching now answers politely instead of visibly hanging.
    That is the better failure — the reader is told to open the screen again
    rather than left holding a spinner — but it is a real trade, and the tests
    that press a real button are what keep it from hiding one.
    """
    await callback.answer(STALE_CARD, show_alert=True)
