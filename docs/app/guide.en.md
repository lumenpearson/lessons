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
<!-- id: START; label: Start; summary: The introduction and two ways in -->

The app shows one of two things, or both: a class timetable that one person keeps in a Telegram bot — on screen, in the widget and in notifications; or your school's electronic diary, if you sign in to it yourself.

A fresh install does not open with the code field. It opens with five introductory steps. Under the “Continue” button on the first of them there is always the line “By continuing, you accept the Terms of Use and the Privacy Policy”. Tap either name to open it — in the browser, or offline from the copy built into the app; both are under Settings → About afterwards too.

1. **Welcome** — The app mark, the theme and the language. The mark spins under a finger; the theme and the language are in settings afterwards.
2. **What this is** — A few paragraphs on what the app shows and what it does not do. It is also where you choose whether crash reports are kept — by default they are not.
3. **Preferences** — Haptics, wallpaper colours, a black background, the edge blur, the teacher on a lesson row and the progress bar in the widget. Only the things you can judge before seeing a single lesson.
4. **Permissions** — Notifications, exact alarms and background work, one card each. Without them the timetable still works — it just stays silent.
5. **How do you want to start?** — Two ways: “With a class code” and “Find your school”. You can add the other one later.

> The language is on the first step on purpose: somebody who does not read Russian has to be able to switch it before the third screen, not after it. Below Android 13 changing it recreates the screen — that is normal, and the step is not lost.

“With a class code” opens the code field. The bot hands out the code with /code, to anybody who is an administrator of the class. A new code is eight characters long and never contains the look-alikes O, 0, I and 1. Older six-character codes still work: the field accepts 4 to 16 characters and upper-cases them for you. If the class is linked to a diary the app can sign in to, the app then offers to sign in to that diary too — “Not now” skips it.

“Find your school” is for a family that wants to see its own diary, whether or not there is a class in the bot. The steps come in this order, and the ones a region does not need are skipped:

- **Region** — Search by the name of a region or a city, by the region's code, or by a school's name. The list of every region is built into the app and searches offline; a school's name is looked up by the server, if school search is switched on there.
- **School** — Only for Setevoy Gorod: the phone takes the list of schools straight from your region's diary server.
- **The electronic diary** — The systems the region's schools use; one is marked “Recommended”, and “Why?” explains the choice. If a diary opens only through Gosuslugi, or the app cannot read it yet, its site opens instead — or you can use a class code.
- **Sign-in** — The diary's login and password. The “Where the password goes” card names the diary's address: the password goes there and nowhere else, over HTTPS, and the server gets the session the diary hands out.
- **Loading** — The student, the terms, two weeks of timetable, homework and marks. If the account has several students, the app asks whose diary to show; if the load stops, “Try again” carries on from where it stopped.
- **All set** — The student, the diary, the class if there is one, and the main settings. “Open the app” goes to the home screen.

The app has no server of its own: the address comes from the class admin, or from whoever set a server up for your school. It goes into the “Server address” row — on the “How do you want to start?” screen, under the code field, or in Settings → Sync; on the way through your school you are asked for it when it is needed. It has to be the address the server is reachable at from the phone itself: “localhost” on a phone means the phone.

To change class or leave it: Settings → Class. After leaving the last class the app opens not the introduction again but “How do you want to start?” — or the diary, if you are signed in to one.

## The three tabs
<!-- id: TABS; label: Tabs; summary: What lives where, and where settings are -->

Three tabs under one floating toolbar; swipe between them or tap an icon in the bar. Settings is not a fourth tab but the button beside it: you go in, change one thing and come back.

- “Today” — what is on now and how long is left, then the rest of the day and the homework.
- “Calendar” — the timetable as a week, a month or a single day, with rooms, replacements and cancellations; the day has two readings, “Ribbon” and “List”.
- “Homework” — assignments grouped by the day they are due on.

“Homework” shows only what is still ahead. The “All” button opens the past ones too, and says how many were hidden.

Settings holds nine sections, each one a page of its own: appearance, interaction, content, notifications, sync, class, diary, updates and “about”. An administrator of the class has a tenth — “Managing the class”.

Chips above the calendar narrow it to what you are after: with lessons, with homework, with events, marked. Several of them are an “or” rather than an “and” — with an “and” the second press would almost always empty the screen. In the month grid the days that do not match are dimmed rather than removed, so the month still lines up with its own weekday header; in “List” they are simply not drawn, where there is no shape to break. The list's order is chosen separately: by date, latest first, or by load.

Beside the period's name is the school year — “2026/27”. Tapping it opens a list of five years around the current one, each marked loaded or not. Nothing has to be tapped, though: the calendar fetches the year it is scrolled into by itself, and until it arrives says “Loading the year” rather than “No data” — two different things that used to look the same. The phone keeps three years at a time; the one furthest from the current year is dropped, and the year holding today never is.

In the month view the days with no lessons are shaded differently, and it is not decoration: a weekend, the holidays between two terms, the summer and a public holiday are four different answers to “why is this empty”. Consecutive days with the same answer are drawn as one band, and a month with no teaching in it at all is labelled across the grid. The day card names the date where it has a name — both “День Победы” and “День учителя”; the second one still has lessons, the first does not.

The day view has two readings, and a second picker under the view switcher chooses between them: “Ribbon” is one day, “List” is the days of the month as rows. The arrows step whatever is on screen — a day in the ribbon, a month in the list.

The ribbon: every entry of the day as a row of its own, in the order it happens, with the breaks as rows too rather than as emptiness between two lessons. Beside each row is its time — when it starts and ends, how long it runs for, and, for whatever is running now, how much has gone and how much is left. A button at the bottom returns to what is current; once the day is over there is nothing to return to, and the button is not there.

The ribbon's own settings are under the button beside it, and there are three: which way the progress travels (down the page, as a timetable is printed, or up it, as a countdown feels), whether the scroll settles on a whole row, and whether the cards have depth — the tilt, the gradients and the highlight on what is running. On Android 13 and above that highlight is a shader and runs along the row with the clock; below it the tilt and the gradients remain.

> Tapping a day in the widget opens “Calendar” in its day view, on the date you tapped.

## The widget
<!-- id: WIDGET; label: Widget; summary: What is on now, without opening the app -->

The widget answers one question — “what is on now?” — and once the lessons are over it switches itself to “what is set”.

Added like any other: long-press an empty spot on the home screen, “Widgets”, “Lessons — timetable”. It resizes both ways as far as the launcher allows, and what it shows changes with its size — from a single line with a countdown to the whole day beside the homework. A larger system font counts too: the widget steps down to the layout that fits the type rather than clipping its last rows.

- It works with no internet. Everything it draws is already on the phone; the network is only needed to refresh it.
- It does not wake the phone every minute. One alarm is set for the exact moment the text will change, and updates only get more frequent when a bell is minutes away.
- It counts in the school's time, not the phone's. A parent in another timezone sees the bells as they ring at the school.
- After the lessons it shows the homework — for the next school day, and across a holiday for the first day after it.

> If the widget says “No timetable yet”, open the app and pull the screen down: nothing is cached for that date. The widget draws only a class timetable, so on a phone with no class and only a diary it says “Your diary is in the app”.

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

## The electronic diary
<!-- id: DIARY; label: Diary; summary: Marks and homework from the school's diary -->

A separate thing with a separate account: the class timetable lives in the bot, while the electronic diary is the system the school keeps marks, absences and homework in. The app signs in to Petersburg Education, and to Setevoy Gorod in the regions where it takes a login and a password. A diary that lets people in only through Gosuslugi, the app does not sign in to.

Where the diary is in the app depends on how you came in. On a phone with a class it is Settings → Diary. Without a class the diary is the app: its two tabs are the home screen, and settings has six sections — no content, notifications or class, which all run on a class.

Signing in takes the same login and password as the diary's own site. The form names the diary and the address the password will go to; in settings, “Change” beside the name picks another diary. A failure is explained in a “Could not sign in” pop-up with the reason — wrong pair, diary not answering, too many attempts, no connection. Those are different things and they need different fixes.

> The password goes from the phone straight to the diary, over HTTPS, and nowhere else — not to the server and not to the phone's storage. The server gets only the session the diary hands out, and keeps that. When it ends the app asks for the password again — the login stays where it was.

Inside are two tabs — Timetable and Marks — and a child picker if the account sees more than one. Diary homework appears under the lessons of the day it is due on. An absence, a late arrival and a remark all arrive in the same list as the marks, but the app tells them apart and shows them differently.

What was loaded is kept on the phone and opens offline — the app then says it is showing what was saved, and when it was last updated. The app reads the diary again only while it is open: neither the background nor the widget ever reads it. That is also why the sign-in lasts while the app is being opened: after 30 days without that you sign in again, and the Petersburg diary may end the sign-in sooner.

“Sign out of the diary” is in the same place, Settings → Diary. The lessons and marks saved on the phone are deleted and the server forgets its copy of the sign-in; a phone with no class then goes back to “How do you want to start?”.

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
