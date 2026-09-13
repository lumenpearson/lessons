# Telegram admin bot

The bot **is** the admin panel. There is no web dashboard and no login form —
Telegram already solved identity, and a class timetable does not deserve its own
password reset flow.

Everything below is what the code in `server/app/bot/` actually does. Handlers
live in `handlers/`, the structural half of them in `handlers/manage.py`; the
wording lives in `render.py` and `manage_render.py`; the buttons in
`keyboards.py`, `manage_keyboards.py` and `calendar_keyboard.py`.

## Цвета кнопок

Bot API 9.3 lets an inline button be coloured, and `app/bot/button_style.py`
decides once what each colour means, so that a red button means the same thing
on every page of the bot:

| | |
|---|---|
| **красная** | takes something away or throws away what you were doing — удалить, отменить, отклонить, убрать доступ, выключить |
| **зелёная** | commits, or marks the one row that is the current state — одобрить, применить, добавить, today in the calendar, a ticked task, the default расписание звонков |
| **голубая** | a span of time and the way between spans — `‹` `›`, недели, периоды, the weekday picker, «Ещё ›» |

Everything else stays uncoloured, and most of every keyboard does. Colour only
separates while the majority is plain: paint half a keyboard and the three
meanings above become decoration, which costs a glance and buys nothing.

There are two deliberate exceptions to «отмена красная». In a destructive
confirmation the red button is the one that deletes, so the escape beside it
stays plain — otherwise the pair is told apart by its labels alone, which is
the reading the colour exists to save. And on **🔔 Звонки** the green row is the
default schedule rather than the ⭐ that would make another one default: green
says *which one is in force*, the same thing it says about today in the
calendar, and a page cannot have one colour for «current» and the same colour
for «make current».

A Telegram client too old for `style` draws a plain button. Nothing here is load
bearing — the label still says what the button does.

## Setup

1. Create a bot with [@BotFather](https://t.me/BotFather) and copy the token.
2. Find your numeric Telegram id (write to [@userinfobot](https://t.me/userinfobot)).
3. Put both in `server/.env`:

```ini
BOT_TOKEN=123456:ABC-DEF...
OWNER_IDS=123456789
PUBLIC_BASE_URL=https://lessons.example.com
```

4. Start the server. Send `/start` to your bot; because your id is in
   `OWNER_IDS`, it offers to create the first class. Creating a class asks for
   its name, its school and its **time zone** — one of the eleven Russian zones,
   from Kaliningrad (МСК−1) to Kamchatka (МСК+9).

`PUBLIC_BASE_URL` is only needed for `/calendar`; without it the bot says so
instead of printing a URL that would not resolve.

## Roles

| Role | Can |
| --- | --- |
| **Наблюдатель** (viewer) | read the schedule and homework, search it, subscribe to the calendar, link a phone read-only, ask for more |
| **Редактор** (editor) | + homework, замены, events, особые дни, «собрать предметы», статистика |
| **Администратор** (admin) | + timetable, звонки, предметы, устройства, журнал, настройки класса, импорт/экспорт, выдача ролей |
| **Владелец** (owner) | + granting admin, rotating the join code, deleting the class |

Three rules make the ladder safe, and all three are enforced in the code rather
than in the UI:

* **Owner comes only from the environment.** `OWNER_IDS` is read from `.env` and
  cannot be changed from inside the bot, so owner is not reachable by escalation.
* **Nobody may grant a role at or above their own** (`roles.can_grant`). An admin
  can create editors but not other admins, which removes the "promote a friend,
  get demoted by them" failure mode entirely. The same function answers a
  request for access, so approving one cannot go round the rule.
* **Every step re-checks.** A multi-step form keeps its state in the database,
  and that state is set by the user's own client — so each step checks the role
  again instead of trusting the step before it.

## Adding someone by phone number

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

## Asking for access — `/request`

A viewer who needs to write sends `/request` (with or without a note:
`/request я староста`). The bot:

* creates **one** pending request per person per class — a second `/request`
  replaces the first rather than filling anybody's screen;
* messages every admin and owner of the class with **✅ Одобрить / ✖️ Отклонить**,
  swallowing one blocked recipient rather than failing the request;
* on approval grants **Редактор** through `can_grant` and the same rank guard
  «👥 Доступ» uses, writes the journal line, and tells the requester;
* on refusal tells the requester too, so nobody waits on silence.

Pending requests also sit at the top of **👥 Доступ**, above the member list —
which is the context an admin needs to answer them.

## Editing the timetable

Cell-by-cell button editing is miserable on a phone, so the weekly template is
edited by pasting one message per weekday:

```
1. Алгебра, 214
2. Физика, 305, Иванова И.И.
3. История [чис]
3. Обществознание [знам]
```

Format is `номер. предмет[, кабинет[, учитель]]`, optionally followed by a week
parity — `[чис]`/`[знам]`, `(чис)`/`(знам)`, `[1]`/`[2]`, or a bare `числ`/`знам`.

* A line **with** a parity suffix writes that variant only, so one lesson number
  can hold two subjects on alternating weeks.
* A line **without** one is «каждую неделю» and replaces both variants.
* The message replaces that weekday wholesale in one transaction, so what you
  sent is exactly what is stored. `-` clears the day.
* Lines that cannot be parsed, and repeats that would collide, are reported back
  rather than silently dropped — and a paste from which nothing parsed changes
  nothing at all.

The grammar lives in one place, `services/timetable_io.py`, and the day editor,
the week import and «Экспорт» all speak it. The current day is listed back in
exactly the format it accepts, parity included.

## Экспорт и импорт — `/export`, `/import`

`/export` prints the whole template, one block per weekday plus `== Звонки ==`,
in `<code>` blocks split at 4000 characters. It doubles as the backup: keep the
message, and `/import` will read it back.

`/import` asks for a paste with a header before each day:

```
== Понедельник ==
1. Алгебра, 214

== Вторник ==
1. Химия, 118
```

It then shows a **preview** — lessons per day, and every line it could not
read — and writes nothing until **✅ Применить**. Applying replaces exactly the
weekdays the paste named, in one transaction; days it did not mention are left
alone, and a day whose header has nothing under it is emptied. A `== Звонки ==`
block updates the class's main bell schedule.

## Предметы — `/subjects`

The subject dictionary keeps «Алгебра», «алгебра» and «Алг.» from becoming three
different subjects in the timetable, the homework and the app's colours. Each
entry has a name, a short name, a teacher and a colour (eight presets, or type
`#5B6ABF`).

* **🔄 Собрать из расписания** creates an entry for every distinct subject name
  the timetable already uses. An editor may run it: it invents nothing.
* **Renaming an entry renames the subject everywhere** — `timetable_entries`,
  `homework` and `lesson_overrides` of that class, in the same transaction — and
  the confirmation says how many rows moved. The tables store the name as text
  on purpose (a lesson keeps its name when a subject is deleted), and this is
  the price of that choice.
* Deleting an entry leaves the lessons alone; the class only loses the colour
  and the teacher.

## Особые дни — `/holidays`

A day that is not a normal school day: **каникулы / выходной**, **сокращённые
уроки** (which then asks which bell schedule to ring), **дистанционно**, or
**обычный день**, which deletes the mark — «normal» is the absence of a row, not
a kind of row.

Pick the date in the calendar (below) or type `12.09` / `12.09.2026`. A bare
day and month is read in the year that is coming, because a school year straddles
New Year. A note can be attached («осенние каникулы»).

**📆 Период** (`26.10-05.11`) marks every day inside the range at once, up to
120 days. Marking single days is an editor's job; a whole период stays with
admins.

## Звонки — `/bells`

A class may keep several schedules: «Обычное», «Сокращённое», «Суббота». One of
them is the class default and is what the day view rings; a сокращённый день
points at another.

```
1. 08:30-09:15
2. 09:25-10:10
```

`:` or `.`, any kind of dash. A paste from which nothing parsed **never** erases
what is stored — clearing a schedule is not something you can do by accident.
The default cannot be deleted, and neither can one an особый день still points
at; both would silently move those days onto another schedule.

## Устройства — `/devices`

Every phone that entered the class code:

```
📱 Pixel 8 · привязан: @masha (Редактор) · был 2 ч назад
📱 Samsung A54 · не привязан · был 3 дн назад
```

A device token is read-only until its owner links it with `/link <код>`; from
then on it writes with whatever role that account holds **at request time**. So
the role shown here is a lookup, not something stored on the row: revoking
somebody in «Доступ» has already revoked their phone.

* **🚫** revokes the token — the row stays, which is what makes the refusal
  instant and permanent.
* **🔗 Отвязать** returns the phone to read-only without taking it off the class.

## Журнал — `/log`

Every write in the bot and in the API adds one line: `12.09 14:05 · @masha ·
добавлено ДЗ: Алгебра на 14.09`. Names are resolved from the class's members and
fall back to the numeric id. Thirty lines a page, «Ещё ›» for the next.

Times are shown on the class's own clock: the rows are stored in UTC, and an
admin in Vladivostok reading a Moscow server's log should not see yesterday
evening on this morning's change.

## Настройки класса — `/class`

One card: name, school, city, time zone, join code, whether a calendar link has
been issued, and how many members, devices and pending requests there are. From
it: предметы, особые дни, звонки, устройства, календарь, журнал, часовой пояс,
код класса.

* **🔀 Сменить класс** appears only for somebody who is in more than one. The
  choice is stored in the FSM table under its own key and read back by the
  middleware on the next update — never kept in the process, because on Vercel
  the next message is a different one. A preference for a class you have since
  been removed from is ignored, not honoured.
* **🗑 Удалить класс** is owner-only and asks for the class name typed back
  exactly. Nothing else is accepted: a «вы уверены?» button is pressed by the
  same thumb that pressed the one before it.

## Календарь — `/day`

Every «на какой день?» in the bot is answered in the same month grid: a heading
(`Сентябрь 2026`), the weekday initials `Пн … Вс`, the weeks as rows of seven
with blank cells for the padding, today painted green, and `‹ Сегодня ›` at the
bottom. It replaced a list of the next seven days, which put two ordinary things
out of reach: **a day that has already happened** — homework is written down
after the lesson at least as often as before it — and anything more than a week
out.

`‹` and `›` move one month and stop at the ends of **the school year today falls
in**, 1 September to 31 August. Beyond it there is nothing to plan: in June the
timetable for September does not exist yet, and a date in the year that ended
cannot be taught again. A month button from a message left open across the
boundary draws the nearest month it may rather than refusing.

Opening the calendar from the menu or with `/day` and picking a date gives the
**day card**: what that day already is — its lessons, its replacements, its
events, its homework, and whether it is каникулы, a shortened or a remote
day — rendered by the same function `/today` uses. Under it, for whoever has
the role:

| Button | Who | Goes to |
| --- | --- | --- |
| **📝 Задать ДЗ** | editor+ | the homework flow, at «по какому предмету?» |
| **🔄 Замена** | editor+ | the замены flow, at «какой урок меняем?» |
| **🎉 Событие** | editor+ | the события flow, at «что это за событие?» |
| **🏖 Тип дня** | editor+ | особые дни, at «что это за день?» |

A наблюдатель gets the card and the two navigation rows, and no button that
would only answer with a refusal.

Those buttons carry the date into each flow's **own** handler — the one the
flow's own menu entry reaches — so «добавить ДЗ» exists once, not twice. The
payload is the prefix, a short tag and digits: the longest one the calendar can
build is 23 bytes against Telegram's ceiling of 64, and a test says so, because
this project has already shipped a payload that was counted in characters and
overflowed in Cyrillic.

## Day-to-day editing

* **📝 Домашнее задание** — pick a day in the calendar (any day, past ones
  included), pick a subject from that day's actual lessons (or type one), send
  the text. Re-sending for the same day and subject
  updates the existing entry instead of duplicating it. The digest shows each
  reader's own «сделал» ticks.
* **🔄 Замены** — pick a day and a lesson, then either send the replacement
  (`Физика, 214`), cancel the lesson, or restore it to the template. The weekly
  template is never mutated for a one-off change.
* **🎉 События** — столовая, мероприятие, контрольная, экскурсия, собрание, with
  a time range. Мероприятие and экскурсия default to `covers_lesson = true`;
  столовая does not, so lunch shows during the break without hiding a lesson.

Each of these saves, writes its journal line in the same transaction, and only
then notifies: subscribers who asked for «🔄 Замены» or «📝 Задания» in
**🔔 Напоминания** get a message, the author excluded. Nothing is announced
before it is committed, and one recipient who blocked the bot is switched off
rather than retried forever.

## Поиск — `/find`

`/find параграф 12` searches this class's homework — the text and the subject
name — from a month back and forward, newest due first, fifteen results. Any
member may: it is the same text the day view already shows them, reachable by
memory instead of by date. Case folding is SQL's `lower()`, which on SQLite
covers Latin only.

## Подписка на календарь — `/calendar`

Not to be confused with `/day`, which is the month grid. This one is the class
as an iCalendar feed, for Google Calendar, Apple Calendar and the rest.
The bot prints the subscription URL and two lines on how to add it. The feed's
secret is **not** the join code: a subscription URL ends up in calendar settings,
on a family laptop and in the odd screenshot, and none of those should be able to
mint device tokens.

**🔁 Новая ссылка** (admin) rotates the secret; every existing subscription stops
updating, which is the point.

## Статистика — `/stats`

Lessons a week (a lesson that alternates weeks counts as a half), subjects,
open and total homework, замены and особые дни ahead, events ahead, members by
role, connected devices, and hours per subject. Editors and above.

## Time zone

The zone belongs to the class, because one deployment can serve schools ten
hours apart. It is picked at creation and changed later in **⚙️ Класс → 🕒
Часовой пояс**.

Changing it moves nothing: bells are stored as wall time, so 08:30 stays 08:30.
What changes is which instant the app and the widget consider "now" for that
class. Everything in the bot that says «сегодня» — the day view, the search
window, the upcoming holidays — is computed on the class's clock, never the
server's.

## Commands

| Command | Who | What |
| --- | --- | --- |
| `/start` | anyone | menu, or onboarding |
| `/help` | anyone | commands, grouped by what you may do |
| `/today`, `/tomorrow`, `/week`, `/next` | members | the schedule |
| `/day` | members | the calendar: any day, and what may be added to it |
| `/homework` | members | digest with «сделал» ticks |
| `/find <текст>` | members | search the homework |
| `/tasks`, `/task <текст>` | members | personal to-do list |
| `/remind` | members | digests and instant notifications |
| `/calendar` | members | iCal subscription link |
| `/link <код>` | members | attach a phone to this account |
| `/request [текст]` | below editor | ask for the editor role |
| `/stats` | editor+ | class statistics |
| `/subjects` | editor+ (edits: admin) | subject dictionary |
| `/holidays` | editor+ (период: admin) | особые дни |
| `/bells` | admin+ | bell schedules |
| `/devices` | admin+ | connected phones |
| `/log` | admin+ | journal of changes |
| `/class` | admin+ | class card and settings |
| `/export`, `/import` | admin+ | timetable as text |
| `/code` | admin+ | join code for the app |

A command you may not use answers with a refusal rather than silence: Telegram
shows one command list per bot, and a button that does nothing teaches people to
distrust the whole thing.

## Operational notes

The bot runs either as a long-poll task inside the API process
(`app/main.py` lifespan) or behind a webhook — `build_dispatcher()` is shared by
both, so the two behave identically.

FSM state lives in the database (`app/fsm_storage.py`), not in memory: on a
serverless deployment each update may hit a fresh process, and with memory
storage every multi-step flow in this document would forget its previous step in
a way that looks random. The same table holds the «current class» preference.

Set `RUN_BOT=false` to start the API alone — that is what the test suite and the
`scripts/seed_demo.py` workflow use.
