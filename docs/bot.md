# Telegram admin bot

The bot **is** the admin panel. There is no web dashboard and no login form —
Telegram already solved identity, and a class timetable does not deserve its own
password reset flow.

Everything below is what the code in `server/app/bot/` actually does. Handlers
live in `handlers/`, the structural half of them in `handlers/manage.py`; the
wording lives in `render.py` and `manage_render.py`; the buttons in
`keyboards.py`, `manage_keyboards.py` and `calendar_keyboard.py`.

## Button colours

Bot API 9.3 lets an inline button be coloured, and `app/bot/button_style.py`
decides once what each colour means, so that a red button means the same thing
on every page of the bot:

| | |
|---|---|
| **red** | takes something away or throws away what you were doing — delete, cancel, reject, revoke access, unlink, switch off. A toggle carrying both halves in one label («🔄 Замены: выключить») is painted by what pressing it does, so the same button reading «включить» is plain |
| **green** | commits, or marks the one row that is the current state — approve, apply, add, today in the calendar, a ticked task, the default bell schedule |
| **blue** | a span of time — «завтра», weeks, periods, the weekday picker, «Ещё ›», reminder offsets |

Everything else stays uncoloured, and most of every keyboard does. Colour only
separates while the majority is plain: paint half a keyboard and the three
meanings above become decoration, which costs a glance and buys nothing.

The bare `‹` `›` arrows are the clearest case. They were blue — they do move a
span, which is what blue is for — and in a month grid of forty grey cells the
pair under the thumb was the loudest thing on a screen whose point is the green
day. They are grey now. «Отмена» likewise dropped its ✖️: the button is already
red and already says «Отмена», and the cross was a third way of saying it.

There are two deliberate exceptions to "cancel is red". In a destructive
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
   the **year** (1–11, as buttons), the letter, the **school** and the **time
   zone** — one of the eleven Russian zones, from Kaliningrad (МСК−1) to
   Kamchatka (МСК+9). The year is a number rather than part of a typed name
   because the term scheme follows it: years 1–9 are taught in четверти (quarters)
   and 10–11 in полугодия (half-years), and every date is editable afterwards.
   Those dates are what the timetable runs between: a day inside none of the
   class's periods draws no lessons anywhere — phone, widget, calendar feed,
   digest — so ending a half-year on 28 May ends the lessons on 28 May, and
   leaving a gap between two quarters marks the holidays in it. The
   conventional dates are contiguous, so a class that never edits them is
   unaffected.

   The school step searches the register when this deployment has a
   `DADATA_TOKEN` — type «гимназия 3 Казань», pick from the results, five to a
   page. Twenty is the register's own ceiling for one search, so the bot says
   «показаны первые 20» rather than «найдено 20»: the way to a school that is
   not in them is a longer query. Without a key, or on a day the register is
   not answering, the same step asks for the name to be typed, which is what it
   has always done.

`PUBLIC_BASE_URL` is only needed for `/calendar`; without it the bot says so
instead of printing a URL that would not resolve.

## Roles

| Role | Can |
| --- | --- |
| **Наблюдатель** (viewer) | read the schedule and homework, search it, subscribe to the calendar, link a phone read-only, ask for more |
| **Редактор** (editor) | + homework, substitutions, events, special days, collecting the subject list, statistics |
| **Администратор** (admin) | + the timetable, the bells, the subjects, the devices, the log, the class settings, import and export, granting roles |
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
  «👥 Доступ» uses, writes the log line, and tells the requester;
* on refusal tells the requester too, so nobody waits on silence.

Pending requests also sit at the top of **👥 Доступ**, above the member list —
which is the context an admin needs to answer them.

## Editing the timetable

**⚙️ → 🧩 Расписание** opens one message you stay inside. It lists the day's
lessons as buttons, pages Понедельник–Суббота with `‹` `›` (Суббота wraps to
Понедельник rather than dead-ending on an always-empty Воскресенье), and keeps
a strip of the whole week under the heading — `Пн 6 · Вт 5 · …` — so "and how
many on Tuesday?" is answered without stepping onto Вторник.

Tapping a lesson opens its card: **▲ Выше / ▼ Ниже** to reorder, **✏️ Изменить**
to retype it, **✂️ По неделям** to split it into numerator and denominator weeks (and
«Оставить чис / знам» to collapse it back), **🗑 Удалить урок** to remove it.

Three rules the buttons enforce that a paste could not:

* **A number is a slot, and a slot holds both weeks.** Moving lesson 3 moves the
  numerator and the denominator week together. Any other rule and one week's third lesson
  becomes the other week's second — and the bells, which are keyed on the number
  alone, are then right for one week and wrong for the other.
* **Deleting closes the gap.** A day numbered 1, 2, 4 reads as a *lost* lesson
  rather than a deleted one, and hands lesson 4 the fourth bell when it is now
  the third thing that happens.
* **A viewer sees the template and is offered nothing that would refuse them.**
  "What is the third lesson on Wednesday" is a question anybody in the class may ask; the
  editing buttons simply are not drawn, and tapping a lesson answers with its
  card as an alert.

**⏱ Перемены** turns on the times and the gaps between them — `08:30–09:15`,
then `⏸ перемена · 10 минут`. It is off by default because it doubles the line
count of a day you are usually reading to check which subject is third, and the
switch travels with the `‹` `›` arrows, so checking the breaks on three days is one
press and not three. A break is never stored: it is the gap between bell N's end
and bell N+1's start, so it can only ever be derived.

**🍽 Столовая** marks which break lunch falls on. It is stored on the *bell
schedule* (`bell_schedules.canteen_after_index`), not on a date and not as a
recurring event: it is the same break every day that schedule is in force,
and it moves with the bells when a shortened day moves them. The last lesson is
not offered — there is no break after it — and a press on a card still open from
before a shorter schedule was pasted is refused rather than honoured: a keyboard
is a message that stays in the chat, and a lunch break marked after a lesson that
no longer rings is drawn nowhere under a card saying it was marked.

Adding or changing a lesson offers **the class's own subjects as buttons**,
above the typed prompt. Typing still works and is still the only way to give a
room and a teacher in one go — the button carries a subject and nothing else,
and that subject's teacher reaches the lesson through the dictionary anyway.
The list is the one «📚 Предметы» holds, after it has adopted whatever the
timetable already uses, so it is empty only for a class whose timetable is
being typed for the first time.

Every write goes in the log, and all of it is ADMIN-only; substitutions and events
stay an editor's business.

### Pasting a whole day

The button editor changes one lesson. Entering a term is still fastest as a
paste, so **📋 Вставить день** sits on the day it would overwrite:

```
1. Алгебра, 214
2. Физика, 305, Иванова И.И.
3. История [чис]
3. Обществознание [знам]
```

The format is `номер. предмет[, кабинет[, учитель]]` — number, subject, room,
teacher — optionally followed by a week parity: `[чис]`/`[знам]`, `(чис)`/`(знам)`,
`[1]`/`[2]`, or a bare `числ`/`знам`.

* A line **with** a parity suffix writes that variant only, so one lesson number
  can hold two subjects on alternating weeks.
* A line **without** one means every week and replaces both variants.
* The message replaces that weekday wholesale in one transaction, so what you
  sent is exactly what is stored. `-` clears the day.
* Lines that cannot be parsed, and repeats that would collide, are reported back
  rather than silently dropped — and a paste from which nothing parsed changes
  nothing at all.
* **A comma inside a field.** The last field takes everything that is left, so a
  teacher «Иванов И.И., к.п.н.» needs nothing special. A subject or a room with
  a comma in it is wrapped in double quotes — `1. "Иностранный язык, второй",
  305` — and that is what «Экспорт» writes, because without it the export wrote
  a line the import read back as a different subject in a room called «второй».
  A doubled `""` inside a quoted field is one literal quote.
* **A lesson number the bells do not reach is not written**, and the reply says
  which ones were skipped. The day view builds its times out of the bell rows,
  so such a lesson would be stored and then shown nowhere at all — not in the
  bot, not in the app, not in the widget, not in the digest. If the paste brings
  its own `== Звонки ==` block, the lessons are checked against *those*, so one
  message can legitimately add a ninth bell and a ninth lesson together. The
  «⚠️ Не добавлены уроки № …» line names each number once — it is one thing to
  fix — while the log counts the rows, because the same number under two
  weekdays is two lessons gone. The same rule guards the other direction:
  deleting a lesson closes the gap only when every lesson it moves lands on a
  number that rings, so a class whose bells are 1, 2, 4 keeps its numbering
  instead of sliding a lesson onto a third slot that rings nothing. And a
  «⏱ Сокращённый день» cannot be pointed at a bell schedule with no rows in
  it: such a day would draw nothing at all under a card announcing shortened
  lessons.

The grammar lives in one place, `services/timetable_io.py`, and the day editor,
the button editor, the week import and «Экспорт» all speak it. The current day is
listed back in exactly the format it accepts, parity included. A subject typed
into the button editor goes through the same splitter
(`split_lesson_body`) — including the part that strips a `[чис]` suffix rather
than letting it become the subject's *name*, which is the bug that made the day
editor and the week import disagree once already.

## Export and import — `/export`, `/import`

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

## Subjects — `/subjects`

The subject dictionary keeps «Алгебра», «алгебра» and «Алг.» from becoming three
different subjects in the timetable, the homework and the app's colours. Each
entry has a name, a short name, a teacher and a colour (eight presets, or type
`#5B6ABF`).

* **The list keeps itself.** Every lesson written into the weekly template —
  typed, tapped or pasted — goes through the dictionary: its subject is found
  (ignoring case) or added, and the lesson is linked to it. Opening this screen
  adopts anything a class typed before that was true. A class with a full
  timetable therefore cannot have an empty «📚 Предметы», which is what it used
  to have.
* **🔄 Собрать из расписания** still exists, and is now the button for "I have
  just pasted a day and want to see its subjects without leaving this screen". An
  editor may run it: it invents nothing.
* **The dictionary is what gives a lesson its colour**, and its teacher when the
  cell in the template names nobody. That is the reason to fill it in: before,
  a colour set here reached only substitutions.
* **Renaming an entry renames the subject everywhere** — `timetable_entries`,
  `homework` and `lesson_overrides` of that class, in the same transaction — and
  the confirmation says how many rows moved. The homework and the substitutions store
  the name as text on purpose (a lesson keeps its name when a subject is
  deleted), and this is the price of that choice.
* **Deleting an entry the timetable still uses is refused**, with the number of
  lessons that use it. It used to be allowed and leave the lessons alone — but
  once the list began keeping itself that stopped meaning anything: the name is
  still in the template, so the next read adopted it back without its colour,
  short name or teacher, and the admin was left believing they had deleted
  something. Take the subject out of the timetable first. A subject nothing
  teaches still goes in one tap. To retire a spelling rather than a subject,
  rename it — that moves the rows instead of orphaning them.

## Special days — `/holidays`

A day that is not a normal school day: **каникулы / выходной** (holiday or day
off), **сокращённые уроки** (shortened lessons, which then asks which bell
schedule to ring), **дистанционно** (remote, taught at the usual times),
**самоподготовка** (set work, nobody at school), **отгул** (a day off this
class alone was given), or **обычный день** (an ordinary day), which deletes
the mark — "normal" is the absence of a row, not a kind of row.

Two of them empty the day and two do not. «Каникулы / выходной» and «отгул» drop
the lessons whatever the template says — everywhere at once: the day card, the
phone, the widget, the calendar feed and the morning digest, whose own rule about
staying silent on an empty day could not fire while the day was never empty.
«Дистанционно» and «самоподготовка» keep them on purpose: the first is the
same lessons at the same times somewhere else, and the second is plausibly «эти
уроки, но дома».

The list above the buttons narrows to one kind, and the row of filters carries
«Все» to take the narrowing off. Which kind is showing lives in the button's
own payload rather than in the form state, so a card left open for an hour
still means what it says. An empty list distinguishes «нет вовсе» from «нет
таких»: the second is a filter to remove, not a calendar to fill in.

Pick the date in the calendar (below) or type `12.09` / `12.09.2026`. A bare
day and month is read in the year that is coming, because a school year straddles
New Year. A note can be attached — «осенние каникулы», say.

**📆 Период** asks what to mark the range as — каникулы, дистанционно,
самоподготовка or отгул — and then takes the dates (`26.10-05.11`), marking
every day inside at once, up to 120 days. «Обычный день» and «сокращённые» are
not offered over a range: the first is the same as not marking it, and the
second points at a bell schedule, so a range would quietly choose the class
default for a fortnight. Marking single days is an editor's job; a whole range
stays with admins.

## Bells — `/bells`

A class may keep several schedules: «Обычное», «Сокращённое», «Суббота». One of
them is the class default and is what the day view rings; a shortened day points
at another.

```
1. 08:30-09:15
2. 09:25-10:10
```

`:` or `.`, any kind of dash. A paste from which nothing parsed **never** erases
what is stored — clearing a schedule is not something you can do by accident.
The default cannot be deleted, and neither can one a special day still points at;
both would silently move those days onto another schedule.

## Devices — `/devices`

Every phone that entered the class code:

```
📱 Pixel 8 · привязан: @masha (Редактор) · был 2 ч назад
📱 Samsung A54 · не привязан · был 3 дн назад
```

A device token is read-only until its owner links it with `/link <код>`; from
then on it writes with whatever role that account holds **at request time**. So
the role shown here is a lookup, not something stored on the row: revoking
somebody in «Доступ» has already revoked their phone.

A phone that joined with a personal code from **📱 Подключить телефон** is linked
straight away: that code could not have been obtained without being recognised, so
there is simply no second step here. `/link` remains for phones that joined with
the class code.

* **🚫** revokes the token — the row stays, which is what makes the refusal
  instant and permanent.
* **🔗 Отвязать** returns the phone to read-only without taking it off the class.

## The log — `/log`

Every write in the bot and in the API adds one line: `12.09 14:05 · @masha ·
добавлено ДЗ: Алгебра на 14.09`. Names are resolved from the class's members and
fall back to the numeric id. Thirty lines a page, «Ещё ›» for the next.

Times are shown on the class's own clock: the rows are stored in UTC, and an
admin in Vladivostok reading a Moscow server's log should not see yesterday
evening on this morning's change.

## Class settings — `/class`

One card: name, school, city, time zone, join code, how telephones are let in,
whether a calendar link has been issued, and how many members, devices and
pending requests there are. From it: the subjects, the special days, the bells,
the devices, the calendar, the log, the time zone and the join code.

* **🔀 Сменить класс** appears only for somebody who is in more than one. The
  choice is stored in the FSM table under its own key and read back by the
  middleware on the next update — never kept in the process, because on Vercel
  the next message is a different one. A preference for a class you have since
  been removed from is ignored, not honoured.
* **🗑 Удалить класс** is owner-only and asks for the class name typed back
  exactly. Nothing else is accepted: an "are you sure?" button is pressed by the
  same thumb that pressed the one before it.

## The calendar — `/day`

Every "which day?" in the bot is answered in the same month grid: a heading
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
events, its homework, and whether it is a holiday, a shortened or a remote
day — rendered by the same function `/today` uses. Under it, for whoever has
the role:

| Button | Who | Goes to |
| --- | --- | --- |
| **📝 Задать ДЗ** | editor+ | the homework flow, at «по какому предмету?» |
| **🔄 Замена** | editor+ | the substitutions flow, at «какой урок меняем?» |
| **🎉 Событие** | editor+ | the events flow, at «что это за событие?» |
| **🏖 Тип дня** | editor+ | the special days flow, at «что это за день?» |

An observer gets the card and the two navigation rows, and no button that would
only answer with a refusal.

Those buttons carry the date into each flow's **own** handler — the one the
flow's own menu entry reaches — so "add homework" exists once, not twice. The
payload is the prefix, a short tag and digits: the longest one the calendar can
build is 23 bytes against Telegram's ceiling of 64, and a test says so, because
this project has already shipped a payload that was counted in characters and
overflowed in Cyrillic.

## Day-to-day editing

* **📝 Домашнее задание** — pick a day in the calendar (any day, past ones
  included), pick a subject from that day's actual lessons (or type one), send
  the text. Re-sending for the same day and subject
  updates the existing entry instead of duplicating it. The digest shows each
  reader's own «сделал» ticks. It draws twelve assignments and stops at a length
  budget below Telegram's 4096, saying «… и ещё N» for the rest: a fortnight of
  three assignments a day came to 5371 characters, and Telegram refuses the whole
  message rather than clipping it, so the screen was «что-то пошло не так» and
  `/homework` was silence. Every row drawn has a tick button under it — the
  keyboard is built from the rows the renderer chose, not from the days again.
* **🔄 Замены** — pick a day and a lesson, then either send the replacement
  (`Физика, 214`), cancel the lesson, or restore it to the template. The weekly
  template is never mutated for a one-off change.
* **🎉 События** — lunch, an assembly, a test, an excursion, a meeting, each with
  a time range. An assembly and an excursion default to `covers_lesson = true`;
  lunch does not, so it shows during the break without hiding a lesson.

Each of these saves, writes its log line in the same transaction, and only
then notifies: subscribers who asked for «🔄 Замены» or «📝 Задания» in
**🔔 Напоминания** get a message, the author excluded. Nothing is announced
before it is committed, and one recipient who blocked the bot is switched off
rather than retried forever.

## Search — `/find`

`/find параграф 12` searches this class's homework — the text and the subject
name — from a month back onwards, with no bound ahead, newest due first, fifteen
results. Any member may: it is the same text the day view already shows them,
reachable by memory instead of by date. Case folding is SQL's `lower()`, and it
folds Cyrillic on both dialects: Postgres does it natively, and `app/db.py`
replaces SQLite's ASCII-only version with Python's on every connection, so
«Алгебра» is found by «алгебра» on a laptop exactly as it is for the class.

## The electronic diary — «📒 Мой дневник»

An admin binds the class in **⚙️ Класс → 📒 Привязать дневник**. Binding gives
the class *nothing*: it puts one button on every member's menu, and behind that
button is each member's own dnevnik2 account. Nobody in the class sees anybody
else's child.

That is enforced rather than asserted. Every lookup in `handlers/diary.py`
starts from `callback.from_user.id`, and a session is found by
`(telegram_id, class_id)` and nothing else — so a crafted payload reaches the
presser's own diary or nothing at all. The class half matters too: a parent in
two classes must not read one child's diary from the other class's screen.

**The password never enters Telegram.** «🔐 Войти в дневник» hands out a link to
`/diary/signin/<ticket>`, a page this app serves itself. The password goes from
that browser straight to dnevnik2 and is written down nowhere — not in the chat
history, not on Telegram's servers, not in the notification on a locked screen,
not in the phone's backup. A ticket is worth **one** sign-in for fifteen
minutes for one Telegram account in one class; a GET checks it without spending
it (Telegram fetches link previews by itself), a POST spends it before
attempting the sign-in, and a malformed form does not spend it at all. The one
failure that hands the ticket back is a diary that did not answer — a transport
error or a 5xx, where nothing ever looked at what was typed. An answer we
cannot read still costs it: a login form on Yii refuses a password with the
same "200 with some HTML" a captcha arrives in, so forgiving that would turn the link
into an unlimited password oracle against the upstream from our address. The
page says so in words rather than blaming the password, which is what it used
to do for every failure that was not a plain 401.

The session is stored encrypted (`DIARY_SECRET`, `app/crypto.py`). Without that
key the whole feature refuses at the door rather than falling back to
plaintext. Without `PUBLIC_BASE_URL` the bot says there is nowhere to point the
link, instead of printing one that would 404.

Four views, paged with `‹` `›`: **📅 День**, **🗓 Неделя**, **📝 Задания** (grouped
by the day they are *due*), **📊 Оценки** (last 30 days, averaged over digits
only — «Н» and «Б» are attendance codes in the same column). A parent account
with several children is asked once, in **👥 Ребёнок**, and the answer is kept on
the session. **Выйти** drops every session this account holds in this class —
one row is one sign-in and nothing expires an earlier one, so «вышли» that
dropped only the newest left the next press walking straight back in; the other
class of a parent with two children is untouched. The upstream is not told,
because it has no logout that can be called without a browser.

An empty answer is always said, never drawn as a blank: the upstream returns
nothing for the holidays, for a day it has no data for, and for a register a teacher
has not filled, and one blank screen makes a parent refresh four times.

## Who lets a phone in

**👥 Доступ → 🔒 Только по приглашениям** decides whether the join code alone is
enough. «⚙️ Класс» names the current answer on the card; changing it is on the
access page, next to the list of people it is about.

The default is **by the class code**: whoever types it is connected. For a class
whose timetable hangs on the wall anyway, that is exactly right. For a class that
does not see it that way it is not: a code read aloud and forwarded is worth as
much as the least careful person holding it, and rotating it throws everybody out
at once rather than the one it leaked through.

**Invitation only** switches the class code off and personal codes on. Everybody
in the class takes a one-time code for themselves with the **📱 Подключить
телефон** button in the menu: ten characters, fifteen minutes, one phone. The
button is there for every role — the phone gets the role of whoever took the code,
so it cannot hand out more than that person has. It also links the phone to the
account straight away: in the open mode a phone joins anonymously and is linked
later with a second code, which almost nobody does, and the device list fills up
with rows nobody can attribute.

**Switching takes nothing away.** Phones that are already connected go on
working — exactly as they do when the code is rotated — and switching back gives
the class code its force again. That is worth saying out loud on the screen: an
admin who suspects otherwise either never switches, or switches and spends the
evening answering the question of where the timetable went.

`/code` shows the class code in both modes, because it has not disappeared, only
gone to sleep — but in invitation mode it says that the code currently opens
nothing.

> There used to be a «публичный / закрытый» flag on the class card here. Nothing
> read it: a class that was "closed" was not closed. It was removed rather than
> renamed — two separate signs of "who is let in" sooner or later drift apart.

## Calendar subscription — `/calendar`

Not to be confused with `/day`, which is the month grid. This one is the class
as an iCalendar feed, for Google Calendar, Apple Calendar and the rest.
The bot prints the subscription URL and two lines on how to add it. The feed's
secret is **not** the join code: a subscription URL ends up in calendar settings,
on a family laptop and in the odd screenshot, and none of those should be able to
mint device tokens.

**🔁 Новая ссылка** (admin) rotates the secret; every existing subscription stops
updating, which is the point.

The secret is minted on first use, by whichever screen asks first — this one or
`GET /api/v1/calendar` — with a conditional write, and the URL that is printed
is read back from the row rather than from what that request generated. Two
screens opened together used to mint two, the second overwrote the first, and
the first person was handed a link the class no longer had: a subscription is
set up once and never looked at again, so that feed would have answered 404 for
ever.

## Statistics — `/stats`

Lessons a week (a lesson that alternates weeks counts as a half), subjects, open
and total homework, substitutions and special days ahead, events ahead, members by
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
| **📱 Подключить телефон** | members | a personal one-time code for one phone (button, not a command) |
| `/request [текст]` | below editor | ask for the editor role |
| `/stats` | editor+ | class statistics |
| `/subjects` | editor+ (edits: admin) | subject dictionary |
| `/holidays` | editor+ (a whole range: admin) | special days |
| `/bells` | admin+ | bell schedules |
| `/devices` | admin+ | connected phones |
| `/log` | admin+ | the log of changes |
| `/class` | admin+ | class card and settings |
| `/export`, `/import` | admin+ | timetable as text |
| `/code` | admin+ | join code for the app |

A command you may not use answers with a refusal rather than silence: Telegram
shows one command list per bot, and a button that does nothing teaches people to
distrust the whole thing.

A command the bot does **not** have answers too — «🤔 Не знаю такой команды.
Наберите /help, чтобы увидеть список.» Telegram itself stays silent on one, and
for a bot with one screen that is fine; since a command breaks out of a
half-finished form (below), silence became misleading, because a typo would drop
what somebody was filling in and then appear to do nothing. The handler is a
router included **last** in `build_router()`, so every command that has an answer
is asked first, and a test walks the whole `COMMANDS` list to hold that.

## What a card may say

Every screen in this document is one Telegram message, and Telegram refuses a
message it will not deliver rather than clipping it — so a card that grows with
the class's data has to decide, before it is sent, what it is not going to show.
The rules are the same everywhere and they are worth knowing before adding a
renderer.

* **The ceiling is 4096 characters after entity parsing.** That is the Bot API's
  own wording for `sendMessage`'s `text`: `<b>` costs nothing and `&amp;` counts
  as the one «&» it becomes. What is refused is the whole message, so the symptom
  is never a broken line — it is a blank screen and, where the press had no
  callback to apologise on, no answer at all. The homework digest had no bound
  and a fortnight of three assignments a day came to 5371 characters: «📝
  Домашнее задание» said «что-то пошло не так» and `/homework` said nothing.
* **Renderers budget in raw characters against `MESSAGE_LIMIT = 3900`.** The
  count includes the tags Telegram does not count, which makes it conservative
  rather than exact, and the margin also covers what nothing here measures:
  Telegram counts UTF-16 code units, so every emoji outside the BMP — «📝»,
  «📥», «🗓» — is two where Python sees one. A number in `render.py` is a budget,
  never a measurement, and it is not to be tidied up towards 4096.
* **What is cut is announced.** `clamp` and `more_line` live in `app/bot/render.py`
  and the per-page caps in `manage_render.py`; every list that stops early says
  «… и ещё N». The caps differ per page on purpose — a bell schedule's row carries
  three buttons and twelve lines of times, a subject's one of each — and each one
  is read by both the renderer and `manage_keyboards`, because a row that is drawn
  and cannot be pressed is worse than a row that is not drawn. Nothing paginates: past the cap
  a row is only a number.
* **Cut before escaping, never after.** Cutting an escaped string can leave
  «&am», which is a refused message of its own.
* **Everything from outside is escaped.** Anything the Petersburg diary sends,
  anything typed into the bot or pasted into the timetable grammar (a subject
  really can be «Алгебра <7>»), and anything out of the schools registry.
* **An alert is not a card.** `answerCallbackQuery` takes no parse mode, so a
  card built for a message shows its own tags in the popup, and Telegram answers
  400 past 200 characters — which means the press answers nothing at all.
  `editor_render.as_alert` does both halves.

## Operational notes

The bot runs either as a long-poll task inside the API process
(`app/main.py` lifespan) or behind a webhook — `build_dispatcher()` is shared by
both, so the two behave identically.

FSM state lives in the database (`app/fsm_storage.py`), not in memory: on a
serverless deployment each update may hit a fresh process, and with memory
storage every multi-step flow in this document would forget its previous step in
a way that looks random. The same table holds the «current class» preference.

**A command breaks out of a half-finished form.** Every free-text step is
filtered by state alone, and nothing but router order kept a command out of one:
`/week` typed at «Теперь пришлите текст задания:» was committed as an assignment
whose text was «/week» and pushed to every subscriber. Somebody who types a
command mid-form wants to be somewhere else, so the state is dropped, the bot
says «✖️ Форма отменена: вы отправили команду.» and the command runs as though
the form had never been open. It is one outer middleware —
`CommandBreakoutMiddleware` in `app/bot/middlewares.py` — rather than a check in
each step, because the state has to be *cleared*, which no filter can do, and
because a rule copied into twenty-five handlers is twenty-five places to forget
it.

Set `RUN_BOT=false` to start the API alone — that is what the test suite and the
`scripts/seed_demo.py` workflow use.
