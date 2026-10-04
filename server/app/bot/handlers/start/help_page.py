"""``/help``, grouped by what the caller may do.

Part of :mod:`app.bot.handlers.start`.
"""

from __future__ import annotations

from aiogram import Router
from aiogram.filters import Command
from aiogram.types import Message

from app.models import Role

router = Router(name="start.help_page")


HELP_SECTIONS: list[tuple[Role | None, str, list[str]]] = [
    (
        None,
        "Расписание",
        [
            "/today — сегодня",
            "/tomorrow — завтра",
            "/day — календарь: любой день и всё, что в нём",
            "/week — неделя",
            "/next — что дальше: урок, перемена, сколько осталось",
            "/homework — домашнее задание с отметками «сделал»",
            "/find — поиск по домашним заданиям",
        ],
    ),
    (
        None,
        "Личное",
        [
            "/tasks — мои задачи",
            "/task <i>текст</i> — добавить задачу одной строкой",
            "/remind — напоминания и сводки",
            "/calendar — подписка на календарь",
            # Named here as well as on the menu, because the comment on the
            # button in `keyboards.py` says people never find `/link` — and a
            # help page that lists only `/link` sends somebody in «по
            # приглашению» to the one route that cannot work there: it needs a
            # phone that is already in the class.
            "«📱 Подключить телефон» в /start — код на один телефон",
            "/link — привязать телефон, который уже подключён к классу",
            "/request — запросить доступ повыше",
        ],
    ),
    (
        Role.ADMIN,
        "Администрирование",
        [
            "/subjects — предметы и учителя",
            "/holidays — каникулы и особые дни",
            "/bells — расписание звонков",
            "/devices — подключённые устройства",
            "/log — журнал изменений",
            "/class — настройки класса",
            "/export — выгрузить расписание текстом",
            "/import — загрузить расписание текстом",
            "/stats — статистика класса",
            "/code — код класса для приложения (в «по приглашению» не действует)",
        ],
    ),
]


@router.message(Command("help"))
async def cmd_help(message: Message, role: Role | None) -> None:
    """Grouped by what the caller may do: a viewer is not shown admin commands
    that would only answer with a refusal."""
    lines = ["<b>Команды</b>", "/start — главное меню", "/help — эта справка"]
    for minimum, title, items in HELP_SECTIONS:
        if minimum is not None and (role is None or not role.at_least(minimum)):
            continue
        lines.append("")
        lines.append(f"<b>{title}</b>")
        lines.extend(items)
    if role is not None and role.at_least(Role.EDITOR):
        lines.append("")
        lines.append("Замены, события и домашнее задание добавляются из меню /start.")
    await message.answer("\n".join(lines))
