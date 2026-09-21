<!--
  The guide the app draws, and the only copy of it. `values/strings_docs.xml` used to
  hold this text; it does not any more.

  The app parses a deliberately small subset of Markdown — `##` starts a page and
  carries its metadata on the comment line under it, a paragraph is a paragraph, `-`
  is a list of peers, `1.` with a bold lead is a numbered step, and `>` is the aside a
  skimmer must not skim past. Anything else is drawn as prose. Both languages must keep
  the same pages, in the same order, with the same ids: `DocsGuideParityTest` reads both
  files and fails on the first difference.
-->

# Lessons — how to use this

## First run
<!-- id: START; label: Start; summary: Five steps and a class code -->

One person keeps the class timetable in a Telegram bot; the app reads it and shows it — on screen, in the widget and in notifications. There is nothing for you to type in but the class code, once.

A fresh install does not open with the code field. It opens with five steps.

1. **Welcome** — The app mark, the theme and the language. The mark spins under a finger; the theme and the language are in settings afterwards.
2. **What this is** — A few paragraphs on what the app shows and what it does not do. It is also where you choose whether crash reports are kept — by default they are not.
3. **Preferences** — Haptics, wallpaper colours, a black background, the edge blur, the teacher on a lesson row and the progress bar in the widget. Only the things you can judge before seeing a single lesson.
4. **Permissions** — Notifications, exact alarms and background work, one card each. Without them the timetable still works — it just stays silent.
5. **The class code** — That field.

> The language is on the first step on purpose: somebody who does not read Russian has to be able to switch it before the third screen, not after it. Below Android 13 changing it recreates the screen — that is normal, and the step is not lost.

The bot hands out the code with /code, to anybody who is an administrator of the class. A new code is eight characters long and never contains the look-alikes O, 0, I and 1. Older six-character codes still work: the field accepts 4 to 16 characters and upper-cases them for you.

If the school runs its own server rather than the one built into this APK, tap “Server” at the bottom of the join screen and type the whole address. It has to be the address the server is reachable at from the phone itself: “localhost” on a phone means the phone.

To change class or leave it: Settings → Class. After leaving, the app shows the code field again rather than the five screens over.

## The three tabs
<!-- id: TABS; label: Tabs; summary: What lives where, and where settings are -->

Three tabs under one floating toolbar; swipe between them or tap an icon in the bar. Settings is not a fourth tab but the button beside it: you go in, change one thing and come back.

- “Today” — what is on now and how long is left, then the rest of the day and the homework.
- “Calendar” — the timetable as a week, a month or a single day, with rooms, replacements and cancellations.
- “Homework” — assignments grouped by the day they are due on.

“Homework” shows only what is still ahead. The “All” button opens the past ones too, and says how many were hidden.

Settings holds nine sections, each one a page of its own: appearance, interaction, content, notifications, sync, class, diary, updates and “about”. An administrator of the class has a tenth — “Managing the class”.

In the month view the days with no lessons are shaded differently, and it is not decoration: a weekend, the holidays between two terms, the summer and a public holiday are four different answers to “why is this empty”. Consecutive days with the same answer are drawn as one band, and a month with no teaching in it at all is labelled across the grid. The day card names the date where it has a name — both “День Победы” and “День учителя”; the second one still has lessons, the first does not.

> Tapping a day in the widget opens “Calendar” in its day view, on the date you tapped.

## The widget
<!-- id: WIDGET; label: Widget; summary: What is on now, without opening the app -->

The widget answers one question — “what is on now?” — and once the lessons are over it switches itself to “what is set”.

Added like any other: long-press an empty spot on the home screen, “Widgets”, “Lessons — timetable”. It resizes both ways with no upper limit, and what it shows changes with its size — from a single line with a countdown to the whole day beside the homework.

- It works with no internet. Everything it draws is already on the phone; the network is only needed to refresh it.
- It does not wake the phone every minute. One alarm is set for the exact moment the text will change, and updates only get more frequent when a bell is minutes away.
- It counts in the school's time, not the phone's. A parent in another timezone sees the bells as they ring at the school.
- After the lessons it shows the homework — for the next school day, and across a holiday for the first day after it.

> If the widget says there is no data, open the app: nothing is cached for that date and no sync has run yet.

## Notifications
<!-- id: ALERTS; label: Alerts; summary: Four reasons, and when to stay quiet -->

Four reasons, a switch for each, and every one of them off on a fresh install. An app that starts buzzing on day one is an app whose notifications get turned off wholesale.

- “Lesson soon” — 5, 10, 15 or 30 minutes before the bell; briefly, or with the room and the teacher.
- “Morning summary” — at an hour you pick, on the weekdays you pick, if there are lessons today.
- “Homework reminder” — in the evening, if anything is set for the next school day.
- “Replacements and cancellations” — right after the sync that found the change.

Two more settings say when to stay silent: quiet hours — nothing arrives inside the window, and nothing missed is delivered afterwards — and “nothing during the holidays”.

> The first row of the section is access. While notifications are blocked by the system the switches below do nothing, and the page says so plainly.

The same page has “Show an example”: it posts a lesson reminder right now, with your current settings, so a bell is not needed to test one.

## Language and wording
<!-- id: LANGUAGE; label: Language; summary: Switching language and fixing a phrase -->

Russian or English, or “same as the phone”. It is switched under Appearance and applies to everything: the screens, the notifications and the widget.

> Below Android 13 the only things that stay in the phone's language are the ones the app does not draw: the widget's name in the launcher's picker and its preview.

The same page carries the theme (light, dark, follow the system, plus a black background for OLED), wallpaper colours, the typeface and its size. The theme changes as a circle spreading from under your finger; if that is too much, the circle and the ripple can be switched off separately under “Effects” on the Interaction page.

If a phrase reads badly, it can be fixed without leaving the app: Settings → About → Correction mode. While the mode is on, a long press on any text opens an editor for it, and the “Corrections” row collects everything you changed into a list — copy it as ready XML or send it through Share.

> The mode saves nothing and dies with the app: an export not taken before it closes is gone.

## The phone and the bot
<!-- id: TELEGRAM; label: Telegram; summary: Linking, roles and the bot commands -->

On its own the app only reads. To give it any rights, link it to a Telegram account: Settings → Class → Telegram.

A six-character code appears there, next to an “Open the bot” button. Send the bot /link with that code and the app can do exactly what you can do in the bot. Rights are checked on the server at the moment you tap, so the phone holds no rights of its own: if the bot demotes you to a viewer, the app becomes read-only in the same second.

“Unlink” puts the app back into reading mode; it goes on showing the timetable exactly as it did before the link.

- Viewer — reads.
- Editor — adds homework, replacements and events.
- Administrator — edits the timetable, the bells and the subjects, and hands out roles.
- Owner — everything, including deleting the class.

> Homework, replacements and events are entered in the bot: the app has no form for them. Linking is what opens “Managing the class”, and that section is for administrators and owners. To ask for a higher role: /request.

The commands an ordinary member of the class needs. One you are not allowed refuses out loud rather than staying silent.

- /today, /tomorrow, /week, /next — the timetable for today, for tomorrow, for the week, and “what is next”.
- /homework — the assignments as a list; /find searches them.
- /tasks and /task — personal to-dos only you can see.
- /remind — summaries and reminders inside Telegram itself.
- /calendar — a subscription link: the timetable in the phone's calendar.
- /help — the command list, grouped by what is available to you.

## The Petersburg diary
<!-- id: DIARY; label: Diary; summary: Marks and absences from the city service -->

A separate thing with a separate account: the class timetable lives in the bot, while the diary is the St Petersburg city service that holds marks, absences and the homework the school itself sets. In the app it is Settings → Diary.

Signing in takes the same login and password as the diary's own site. What the attempt came to is said in a pop-up: “Signed in”, naming the account, or “Could not sign in” with the reason - wrong pair, diary not answering, no connection. Those are different things and they need different fixes.

> The password is never stored: it is needed for exactly one request, after which both the phone and the server forget it. Only the diary's own session is kept, and it lives as long as the service renews it. When it ends the app asks for the password again — the login stays where it was.

Inside are two tabs — Timetable and Marks — and a child picker if the account sees more than one. Diary homework appears under the lessons of the day it is due on. An absence, a late arrival and a remark all arrive in the same list as the marks, but the app tells them apart and shows them differently.

> The service belongs to somebody else and is undocumented. If it answers in a way the app cannot read, the app says so: that is fixed on the server, not in the app.

## Managing the class
<!-- id: ADMIN; label: Manage; summary: The section an administrator or owner has -->

The section appears in settings only for an administrator or the owner of the class, and only while the server still confirms the linked account's role. Somebody demoted in the bot loses it in the same second, page open or not.

Eight pages, each roughly one bot command; they open as a sheet from the bottom.

- “Class” — the name, the school, the city and the timezone. The owner can delete the class outright.
- “Subjects” — the subject dictionary: name, short name, teacher, colour.
- “Bells” — the bell schedules, the time of each lesson in them, and which schedule is the default one.
- “Timetable” — importing a timetable as text, showing what it would replace before anything is saved.
- “Devices” — the phones joined with the class code; any of them can have its access revoked.
- “Log” — who changed what, a page at a time.
- “Statistics” — how much of everything the class has.
- “Requests” — who is asking for a higher role; approve or decline.

> None of this is a right the phone grants itself: the server checks the role on every single request, and hiding the page is tidiness rather than security.

The class code for new phones comes from the bot, with /code.
