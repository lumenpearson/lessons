---
name: server-bot
description: aiogram routers, keyboards and renderers under server/app/bot/. Use for any change to what the bot shows or how a press is handled. Knows the 4096-character ceiling, the escaping rule and the callback-parsing guards.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `server/app/bot/`. The bot **is** the admin panel: anything it cannot express does
not exist in this product, so a missing card is a missing feature, not a missing screen.

## Layout

- `handlers/` — `access`, `calendar`, `content`, `diary`, `editor`, `manage`, `reminders`,
  `start`, `tasks`, `timetable`, `week`.
- The weekly template has **two** editors and both are wanted: `timetable.py` pastes a whole
  weekday (fastest way to enter a term), `editor.py` changes one lesson with buttons. They
  share one grammar (`services/timetable_io.py`) and one set of mutations
  (`services/timetable_edit.py`). The editor's ‹ › pager and «⏱ Перемены» switch live in the
  callback payload, never in FSM state.
- FSM state lives in the database (`app/fsm_storage.py`) because each update may hit a fresh
  process. `build_dispatcher()` is shared between long polling and the webhook.

## The four ways a bot change breaks production

1. **A message Telegram will not deliver is a screen that says nothing.** 4096 characters
   after entity parsing, and the whole message is refused rather than clipped. Every
   renderer that grows with the data carries a budget — `WEEK_TEXT_LIMIT`,
   `TASK_LINES_MAX`, `HOMEWORK_DIGEST_LIMIT`, `render.clamp` — and says «… и ещё N». Cut
   **before** escaping: cutting after can leave «&am», which is a refused message of its own.
2. **Everything from outside is escaped.** Anything the Petersburg diary sends, anything
   typed into the bot or pasted into the timetable grammar (a subject really can be
   «Алгебра <7>»), anything out of the schools registry. A stray `<` blanks the whole
   message with no error anybody sees.
3. **Callback data is whatever the client sent.** `int(callback_data.value)` raises out of
   the handler, `callback.answer()` is never reached, and the button keeps its spinner until
   Telegram gives up. Use the guards that already exist: `_int_or_none`, `_date_or_none`,
   `_role_or_none`, `_kind_or_none`, `_index_or_none`, `shift_days`/`shift_weeks`. Check the
   value **where it is picked**, not where it is finally read.
4. **Two screens can match one press.** Split a shared callback payload on a field, never on
   registration order — both role pickers send a `RolePick` and only `target` tells them
   apart.

Two smaller ones in the same family: `plural(n, …)` already contains the number, so
`f"{n} {plural(n, …)}"` prints «10 10 минут»; and `answerCallbackQuery` takes no parse mode,
so run a card through `editor_render.as_alert`, which also cuts at 200 characters.

## A list page and the keyboard under it read the same number

`manage_render` declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX`, `LIST_MAX` and
`manage_keyboards` builds its rows from those same names. The values differ on purpose. Do
not tidy them into one constant; the rule is that what is drawn is what can be pressed, and
`test_no_list_page_draws_a_row_the_keyboard_cannot_reach` holds it.

## Gates

`ruff check app tests scripts migrations`, `python -m mypy`, `python -m pytest -q -n auto`.
A new card needs a test that renders it with data long enough to hit its budget.
