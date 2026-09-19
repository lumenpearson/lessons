---
name: bot-message
description: Write or change anything the Telegram bot sends — a card, a list, a digest, a callback alert — without producing a message Telegram refuses. Use for every renderer change in server/app/bot/.
---

# Sending something a person will actually see

## The ceiling

**4096 characters after entity parsing, and the whole message is refused rather than
clipped.** The homework digest had no bound; a fortnight of three заданий a day came to
5371, so «📝 Домашнее задание» answered «что-то пошло не так» and `/homework` — a plain
`answer`, with no callback to apologise on — answered nothing at all.

Every renderer that grows with the data carries a budget and says «… и ещё N»:
`WEEK_TEXT_LIMIT`, `TASK_LINES_MAX`, `HOMEWORK_DIGEST_LIMIT`, and `render.clamp` /
`render.more_line` / `render.cut` for the rest. If your renderer's length depends on how much
data a class has, it needs one.

**Cut before escaping.** Cutting after can leave «&am», which is a refused message of its
own.

## The escaping

The bot sends HTML, and Telegram refuses the **whole message** on a stray `<` rather than
damaging one row — so an unescaped string does not produce a broken line, it produces a blank
screen and no error anybody sees.

Escape everything from outside:

- anything the Petersburg diary sends — subject, room, teacher, topic, homework
- anything typed into the bot or pasted into the timetable grammar (a subject really can be
  «Алгебра <7>»)
- anything out of the schools registry

`render.py` always did this. `diary_render.py` and `editor_render.py` never did, and both
shipped that way.

## Two traps in the same family

- `plural(n, …)` **already contains the number**. `f"{n} {plural(n, …)}"` prints «10 10
  минут». Three callers had it, and one had a test that passed because «10 минут» is a
  substring.
- `answerCallbackQuery` takes **no parse mode**, so a card built for a message shows its own
  tags in an alert. Run it through `editor_render.as_alert`, which also cuts at 200
  characters — past that Telegram answers 400 and the press answers nothing at all.

## A list page and its keyboard

`manage_render` declares `SUBJECTS_MAX`, `BELLS_MAX`, `DEVICES_MAX`, `LIST_MAX`;
`manage_keyboards` builds its rows from the same names. The values differ on purpose — a bell
schedule's row carries three buttons and twelve lines of times, a subject's one of each. Do
not tidy them into one constant. What is drawn is what can be pressed, and
`test_no_list_page_draws_a_row_the_keyboard_cannot_reach` holds it. Nothing paginates, so
past the cap a row is only a number.

## Test it with enough data

A renderer test that passes on three rows proves nothing about a term's worth. Build the
fixture large enough to hit the budget, and assert the whole line where the line is the thing
under test — «10 минут» is a substring of «10 10 минут».
