"""The answer to a command this bot does not have.

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
from aiogram.types import Message

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
