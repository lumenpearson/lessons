# Telegram admin bot

The bot **is** the admin panel. There is no web dashboard and no login form —
Telegram already solved identity, and a class timetable does not deserve its own
password reset flow.

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token.
2. Find your numeric Telegram id (write to [@userinfobot](https://t.me/userinfobot)).
3. Put both in `server/.env`:

```ini
BOT_TOKEN=123456:ABC-DEF...
OWNER_IDS=123456789
```

4. Start the server. Send `/start` to your bot; because your id is in
   `OWNER_IDS`, it offers to create the first class.

## Roles

| Role | Can |
| --- | --- |
| **Наблюдатель** (viewer) | read the schedule and homework in the bot |
| **Редактор** (editor) | + homework, замены, events |
| **Администратор** (admin) | + weekly timetable, bells, granting viewer/editor |
| **Владелец** (owner) | + granting admin, rotating the class join code |

Two rules make the ladder safe, and both are enforced in `app/bot/roles.py`
rather than in the UI:

* **Owner comes only from the environment.** `OWNER_IDS` is read from `.env` and
  cannot be changed from inside the bot, so owner is not reachable by escalation.
* **Nobody may grant a role at or above their own.** An admin can create editors
  but not other admins, which removes the "promote a friend, get demoted by
  them" failure mode entirely.

## Adding someone by phone number

This is the flow the project was asked for, and it works like this:

1. An admin opens **👥 Доступ → ➕ Добавить по номеру** and sends the number in
   any format (`+7 900 123-45-67`, `8 900 1234567`, …). It is normalised to
   digits, with a leading Russian `8` rewritten to `7`.
2. They pick the role to grant. Only roles below their own are offered.
3. The invited person opens the bot, sends `/start`, and taps
   **📱 Поделиться номером**.
4. The bot checks that the shared contact really is theirs
   (`contact.user_id == from_user.id` — Telegram will not let you share someone
   else's number through that button), matches it against pending invites, and
   grants the role.

An invite is single-use. Claiming one never lowers an existing role, so
re-sending an old viewer invite to an admin does not demote them.

## Editing the timetable

Cell-by-cell button editing is miserable on a phone, so the weekly template is
edited by pasting one message per weekday:

```
1. Алгебра, 214
2. Физика, 305, Иванова И.И.
3. История
```

Format is `номер. предмет[, кабинет[, учитель]]`. The message replaces that
weekday wholesale in a single transaction, so what you sent is exactly what is
stored. Sending `-` clears the day. Lines that cannot be parsed are reported
back rather than silently dropped.

Bells use the same idea:

```
1. 08:30-09:15
2. 09:25-10:10
```

## Day-to-day editing

* **📝 Домашнее задание** — pick a day, pick a subject from that day's actual
  lessons (or type one), send the text. Re-sending for the same day and subject
  updates the existing entry instead of duplicating it.
* **🔄 Замены** — pick a day and a lesson, then either send the replacement
  (`Физика, 214`), cancel the lesson, or restore it to the template. The weekly
  template is never mutated for a one-off change.
* **🎉 События** — столовая, мероприятие, контрольная, экскурсия, собрание, with
  a time range. Мероприятие and экскурсия default to `covers_lesson = true`;
  столовая does not, so lunch shows during the break without hiding a lesson.

## Commands

| Command | Who |
| --- | --- |
| `/start` | anyone |
| `/today` | members |
| `/code` | admin+ — shows the join code for the Android app |
| `/help` | anyone |

## Operational notes

The bot runs as a long-poll task inside the same process as the API
(`app/main.py` lifespan), so there is no webhook, no TLS termination and no
second deployment unit. Switching to a webhook later touches only
`app/bot/bot.py:run_polling`.

Set `RUN_BOT=false` to start the API alone — that is what the test suite and the
`scripts/seed_demo.py` workflow use.
