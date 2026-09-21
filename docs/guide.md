# How to use this

A document for whoever uses the app rather than builds it. Building is in
[build.md](build.md), hosting in [deploy.md](deploy.md).

The short version: one person keeps the class's timetable in a Telegram bot, and the app
on the phone reads it and shows it — on the screen, in the widget and in the
notifications. Nothing has to be typed in except the class code on first run.

> The app speaks Russian (with an English translation, chosen in the app), so the buttons
> and menu paths quoted below are quoted as they appear on the screen.

## First run

An installed app opens not with a field for a code but with five steps:

1. **Welcome** — the app's mark, the theme and the language. The mark spins under a
   finger; the theme and the language are changed here and are always available in the
   settings afterwards.
2. **What this is** — four paragraphs about what the app shows and what it does not do.
   They are worth reading: it is the shortest description of the project there is. Whether
   to keep crash reports is chosen here too (off by default).
3. **Properties** — haptics, colours from the wallpaper, a black background, blur, the
   teacher on a lesson's line, the progress bar in the widget. Only the things that can be
   judged before a single lesson has been seen.
4. **Permissions** — notifications, exact alarms and background work, each with its own
   button and an explanation of what it is for. Refusing breaks nothing: with no
   notifications there simply are no reminders.
5. **The class code** — that field.

The language sits on the first step deliberately: somebody who does not read Russian has
to be able to switch it **before** the third screen, not after. Below Android 13 changing
the language recreates the screen — that is normal, and the step is not lost.

## The class code

The bot hands the code out with `/code` to whoever has administrator rights in the class.
It is eight characters, with none of the lookalike `O`, `0`, `I` and `1`; older six-digit
codes still work.

Sometimes what you are given instead is a **personal code** — ten characters, for one
phone and for fifteen minutes. It goes into the same field: the app works out which is
which. Such a code is taken from the bot with the **«📱 Подключить телефон»** button, and
everybody in the class has it, not only the administrator.

If the app answers that the class connects phones only by personal invitation, the class
code is correct — that class simply does not let anybody in with it any more. Take a
personal code from the bot; there is no need to go back to whoever gave you the class
code.

If the school's server is its own rather than the one built into the app, press
**«Сервер»** at the bottom of the connection screen and enter the whole address —
`http://192.168.1.50:8000/` or `https://lessons.example.com/`. The address has to be the
one the **phone** can see the server at: to a phone, `localhost` means itself. The details,
and how to check from a browser, are in [build.md](build.md#pointing-the-app-at-a-server).

### Several classes on one phone

There can be more than one class. «Настройки → Класс» lists every class this phone is
connected to; a tick stands next to the one being shown, pressing any other switches to
it, and **«Добавить класс»** asks for one more code — the ones already connected stay
where they are.

Switching is instant and works with no network: each class's timetable sits on the phone
separately, so what is shown is whatever was loaded last, and anything fresher follows.
The widget and the notifications follow the selected class.

There are two ways out now, and they are different. **«Выйти из класса «7А»»** removes one
class and its timetable and leaves the others alone; **«Выйти из всех классов»** removes
them all — after which the app shows the code field again rather than the four screens.
While there is exactly one class there is one line, and it means what it always did.

Editing rights are per class: a phone is linked to Telegram per class rather than as a
whole, so you can be an editor in one class and only a reader in another.

## What is on the screen

Three tabs and a gear at the side.

| Tab | What it shows |
| --- | --- |
| **Сегодня** | what is running now and how long is left, then the rest of the day and the assignments |
| **Календарь** | four readings of the same timetable — the day as a ribbon, the week, the month as a grid and the month as a list — with rooms, substitutions and cancellations |
| **Задания** | homework grouped by the day it is **set for** |

The «Задания» tab shows only future assignments by default — the «Все» button opens the
past ones too.

The settings are not a fourth tab but a separate button: you go in, change one thing and
come back. Inside are nine sections, each opened on its own: appearance, interaction,
content, notifications, sync, class, diary, updates and "about".

## The widget

The point of the whole thing. The widget answers one question — "what now?" — and when the
lessons end it switches by itself to "what is set".

It is added like any other: a long press on an empty spot of the home screen → «Виджеты» →
«Lessons — расписание». It stretches both ways with no upper bound, and what it draws
changes with its size — from one line with a countdown at 2×1 to the timetable together
with the homework at 5×5 and larger. The full table of sizes is in [widget.md](widget.md).

Worth knowing:

- **It works with no internet.** Everything the widget draws is already on the phone; the
  network is only needed to refresh it. The countdown to the bell keeps running in a
  basement and on the metro.
- **It does not wake the phone every minute.** The widget sets one alarm for exactly the
  moment its text will change, and only quickens in the last ten minutes of a lesson.
- **It counts by the school's time, not the phone's.** A parent in Moscow sees a
  Novosibirsk school's bells as they ring there.
- **After lessons it shows the homework** — for tomorrow, on Friday for Monday, and across
  the holidays for the first school day after them.

If the widget says «нет данных», open the app: there is nothing in the cache for that date
and no sync has happened yet.

## Notifications

Four occasions, each with its own switch, and **all of them are off on install**. An app
that starts buzzing straight away is an app whose notifications get turned off wholesale.

| Occasion | When it arrives |
| --- | --- |
| A lesson is coming | 5, 10, 15 or 30 minutes before the bell; briefly, or with the room and the teacher |
| The morning digest | at the chosen hour, on the chosen days, if there are lessons today |
| A homework reminder | in the evening, if anything is set for the next school day |
| Substitutions and cancellations | right after the sync that found the change |

Two more settings next to them: **quiet hours** (nothing arrives inside that window, and
what was missed is not sent afterwards) and **no reminders during the holidays**.

The first line of the section is access. While notifications are forbidden at the system
level the switches below do nothing, and the page says so plainly. There is a «Показать
пример» there too — it sends a lesson reminder right now, with the current settings, so
you do not have to wait for a bell to check.

## Language and appearance

The language is Russian or English, or "as on the phone". It is switched under
«Оформление» and applies to everything: the screens, the notifications and the widget.
Below Android 13, the only things left in the phone's language are the ones the app does
not draw: the widget's caption in the launcher's list, and its preview.

The same place holds the theme (light, dark, as in the system, plus a "black background"
for OLED), colours from the wallpaper, the typeface and its size. The theme changes as a
circle spreading from under your finger; if that makes you queasy, the circle and the wave
can be switched off separately under «Эффекты».

### If there is a mistake in the app's text

**«Настройки → Перевод → Режим исправления»** turns on correcting right there on the
screens. All the text written inside the app itself gets a thin outline; a long press on
any of it — a heading, a settings line, a button's caption, an empty screen — opens an
editor with the string's key, what it says now, and a field for what it should say.
Subject names, teachers and homework get no outline: those are the class's data rather
than the app's text, and they are edited in the bot.

Turning the mode off removes the outlines but **leaves the corrections on the screen**:
that is intended — it is the only way to see that a corrected heading no longer fits on
its line.

What accumulates sits under «Настройки → Перевод → Исправления» and comes out as a ready
piece of XML — it can be copied or sent: it does not have to be pasted anywhere, only sent
in. Above each group of strings is the file they belong to, including the module: some of
the app's text lives in the design system, and a correction from there fixes nothing in
another folder. Corrections live until the app is closed and are deliberately not saved
anywhere, so send them while the app is still open.

## Linking the phone to Telegram

Homework, substitutions and events are created in the bot. The phone is linked to a
Telegram account — **«Настройки → Класс → Telegram»** — so that the server knows who you
are: for an administrator and an owner this opens the «Управление» section in the app,
where the class is configured exactly as it is from the bot.

A six-character code appears there, with an «Открыть бота» button. Send the bot
`/link <code>` and from that moment the app can do exactly what you can do in the bot. The
rights are checked on the server at the moment of the press, so there are no separate
rights on the phone: if the bot moves you down to observer, the app becomes read-only in
the same second.

«Отвязать» returns the app to reading; it goes on showing the timetable exactly as it did
before it was linked.

A phone connected with a personal code from the bot is linked straight away: the code
could not have been obtained without being recognised. Nothing has to be sent a second
time.

The roles: an **observer** reads, an **editor** adds homework, substitutions and events,
an **administrator** edits the timetable, the bells, the subjects and grants roles, and an
**owner** can do everything, including deleting the class. To ask for a higher role, use
`/request` in the bot.

### If you are an administrator: who may connect a phone

«Управление → Класс» shows how phones connect at the moment, and that is where it is
changed. There are two options: **by the class code** (as it always was) and **by personal
invitation only** — the class code stops letting anybody in, and every member takes a
one-time code for themselves in the bot with the «📱 Подключить телефон» button.

The second mode is worth turning on when the class code has spread further than you wanted.
It **switches nothing off**: phones that are already connected go on working, exactly as
they do when the code is changed — and switching back gives the class code its force again.
The app asks again before turning the class code off, and does not ask when you turn it
back on: only access is being given back.

## The bot

The bot is the main way to run a class, and the only one for an editor: the «Управление»
page in the app opens for an administrator and an owner only. The full description is in
[bot.md](bot.md); what an ordinary member of a class needs is here.

| Command | What it does |
| --- | --- |
| `/today`, `/tomorrow`, `/week`, `/next` | the timetable: today, tomorrow, the week, "what next" |
| `/homework` | the assignments as a list, with "done" marks |
| `/find <text>` | search the assignments |
| `/tasks`, `/task <text>` | personal to-dos that only you see |
| `/remind` | digests and notifications inside Telegram itself |
| `/calendar` | a subscription link: the timetable in the phone's calendar |
| `/link <code>` | link a phone |
| `/request` | ask for the editor role |
| `/help` | the list of commands, grouped by what is available to you |

A command you are not allowed answers with a refusal rather than staying silent.

Editors and administrators additionally have `/subjects`, `/holidays`, `/bells`,
`/devices`, `/log`, `/class`, `/export`, `/import`, `/stats` and `/code` — all described in
[bot.md](bot.md).

## The Petersburg electronic diary

A separate thing and a separate account: the class's timetable is kept by the bot, while
the diary is St Petersburg's state service, holding the marks, the absences and the
assignments the school sets. In the app it is the **«Настройки → Дневник»** section.

You sign in with the same login and password as on the diary's website. **The password is
not saved anywhere**: it is needed for exactly one request, after which both the phone and
the server forget it. Only the diary's own session is stored, and it lives as long as the
service keeps extending it. When it ends, the app says «Сессия дневника закончилась» and
asks for the password again — the login stays where it was.

Inside are two tabs — «Расписание» and «Оценки» — and a choice of child, if the account
sees several. The diary's assignments are shown under the lessons of the day they are set
for. An absence, a late arrival and a remark are all put into one list with the marks by
the service, but the app tells them apart and shows them differently.

The service is somebody else's and undocumented. If it answers incomprehensibly, the app
says exactly that: the service has changed, and that is fixed on the server rather than in
the app.

### Correcting what came from the diary

The diary often arrives with empty homework or the wrong room. Press a lesson or an
assignment and a window opens where every field can be rewritten. Under each field it says
**what the diary actually holds**: a correction lies over the school's answer rather than
erasing it.

Corrected rows are marked with the word «Исправлено». «Сбросить правки» puts everything
back the way the diary has it.

Three things worth knowing:

* **Nothing changes in the diary itself.** Nothing goes upstream, the teacher does not see
  the correction, and it does not affect a mark. It is only what you see.
* **Marks and the turnstile cannot be corrected.** A mark is a statement about what
  happened; an app that lets you rewrite one makes a forged record that looks genuine.
* **If the teacher later fills the field in**, the app says the diary now holds something
  else and shows the new value beside yours — but it does not drop your correction by
  itself.

The bot does not show corrections: there the diary is seen as it is.

## Updates

The app is not in a store, so a new version appears on GitHub and the app checks the
releases itself — no more than once a day. When a version is found, a sheet rises with the
release notes; «Обновить» opens the APK in a browser and the system installer takes over
from there. «Позже» remembers that release, and the sheet will not rise for it again — a
manual check in the «Обновления» section will.

A manual check in the same place also shows the notes for the version already installed:
"what have I got" is an answer too.

## If something is wrong

| What you see | What it means |
| --- | --- |
| The widget says «нет данных» | there is no cache for that date — open the app and let it sync |
| The time in the app is wrong | "now" is computed in the class's time zone; an administrator changes it in the bot, «⚙️ Класс → 🕒 Часовой пояс» |
| Notifications do not arrive | the first line of the «Уведомления» section says whether they are forbidden at the system level; check the quiet hours and "no reminders during the holidays" |
| The code does not connect | check the server address under «Синхронизация» and open `<address>/api/v1/health` in the phone's browser |
| The timetable does not change after an edit in the bot | the app syncs on a schedule; pull the screen down |
| "Read only" after linking | this account's role in the class is observer; `/request` in the bot |

If none of that fits — **«Настройки → О приложении → Сообщить об ошибке»**. The report
goes out as an issue on GitHub and already contains the table about the device and the
version. There is no need to add the class code or anybody's surname to it: the issue is
public.

If the app closes by itself, switch **«Настройки → О приложении → Отчёты о сбоях»** on and
let it happen once more. The row under the switch opens the last five reports and sends the
one you choose — it is there for everybody, not only for an administrator, because the
reports are written into a folder Android no longer lets a file manager open, and the crash
worth reporting is often the one that leaves no time to reach anything else.
