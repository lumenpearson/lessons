"""Logic shared by the bot handlers and the API endpoints.

Everything in this package is a plain async function taking an
``AsyncSession`` and ORM rows, and returning ORM rows, dataclasses or text.
No module imports aiogram or FastAPI at the top level: ``notify`` and
``reminders`` need Telegram's exception types to tell "blocked the bot" from a
network hiccup, and import them inside the function that needs them, so the
API can use the rest of the package without paying aiogram's import time on a
cold start.

The split exists because a linked phone and the bot write the same facts. A
homework tick from the app and a tick from an inline button must land in the
same table with the same rules, and the only way to guarantee that is for
both to call the same function.
"""
