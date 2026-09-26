<!--
  The terms of use of «Дневник», in English: the translation of terms.ru.md, which is the
  source. Keep the two level — the same sections, in the same order, with the same ids, built
  from the same blocks — and change the Russian first.

  The format, the FORK passages and the fields to fill are described at the top of
  terms.ru.md. Every github.com/lumenpearson/lessons link is the field [REPOSITORY].
-->

# Terms of use of Lessons

## What this document covers
<!-- id: SCOPE; label: About; summary: What these terms apply to and whose rules apply where -->

These terms apply to one program in three parts: the Android app Lessons («Дневник» in Russian), the server it works with, and that server's Telegram bot. The source code of all three is open: [github.com/lumenpearson/lessons](https://github.com/lumenpearson/lessons).

<!--
  FORK: [OPERATOR] [REPOSITORY]
  The list below names the upstream operator: the maintainer of github.com/lumenpearson/lessons,
  whose repository the APK is built from and who runs the server these texts were written
  for. A fork that builds its own APK or runs its own server names itself here.
-->

- **The operator** is the maintainer of that repository (`lumenpearson` on GitHub): the one who keeps the repository the app is built from and runs the server this text was written for.
- **What you accept** is these terms and the privacy policy that sits next to them. Both open from the app: Settings → About.

The app does not choose its server: you type the server's address into the app. If that is a server somebody else started — your school, for example — then it and its bot are run by whoever started it, and their rules apply, not these.

## Who may use it
<!-- id: WHO; label: Who; summary: Pupils, parents and the school — and parents' consent -->

Pupils, their parents and other legal guardians, teachers, and whoever keeps a class's timetable may use the app and the bot.

If you are a pupil under 18, use Lessons with the knowledge and consent of a parent or another legal guardian. The diary holds information about you, and part of what you do in the app and in the bot is kept on the operator's server; the privacy policy says exactly what.

The app does not ask your age and does not check who signs in to a diary, so this condition rests on you and your parents.

## What the app does
<!-- id: SERVICE; label: What it does; summary: The class timetable, your diary and corrections over it -->

- It shows the class timetable that people with rights in that class keep in the Telegram bot, once you have entered the class code or a personal code from the bot.
- It shows your electronic diary — lessons, homework, marks — if you have signed in to it. The server reads the diary from the diary's system and passes it on to the app.
- It lets you correct what came from the diary: the homework text, the room, the teacher, the lesson's topic. A correction is kept on the server next to the diary and **is never sent to the diary's system** — the school register does not change because of it. Corrections are shared per pupil: everyone whose diary account shows that pupil — the other parent, for example — sees them in the app and can change or reset them. Marks and attendance cannot be corrected.

## This is not the official record
<!-- id: NOT_OFFICIAL; label: Not the record; summary: What to trust when the app and the school disagree -->

The timetable in the app is a copy a person typed into the bot. It may have been changed after the app last fetched it: before a test or a substitution, check against what the school said.

The app shows the diary as the diary's system handed it over — possibly late, or misread. What the system's own website shows is what is right.

Lessons is not affiliated with Petersburg Education («Петербургское образование»), Setevoy Gorod. Obrazovanie («Сетевой город. Образование»), Gosuslugi or My School («Моя школа»), and does not act on their behalf. Those names belong to their owners.

## Signing in to a diary
<!-- id: SIGN_IN; label: Sign-in; summary: Which diaries the app can sign in to and which it cannot -->

Sign in only to your own account, or to one you have been allowed to use — a parent's, for example.

The app can sign in only to Petersburg Education and to Setevoy Gorod in the regions where it accepts a login and password. Which regions those are can be seen in [the server's source code](https://github.com/lumenpearson/lessons/blob/HEAD/server/app/providers/netschool/regions.py).

If your region's diary opens only through Gosuslugi, the app does not sign in to it: it opens that system's website in your browser and receives nothing back. The app never signs in to Gosuslugi.

When you use a diary you remain bound by its system's own rules: these terms do not replace them.

## What not to do
<!-- id: CONDUCT; label: Rules; summary: Other people's accounts, codes and load on the server -->

- Do not sign in to somebody else's diary without the permission of the person it belongs to.
- Do not pass on a class code or a personal code that was given to you.
- Do not try to guess codes, overload the server or get around its limits.

> The app does not lock the diary behind a password of its own: whoever holds your unlocked phone can open your diary in it.

## The bot and class admins
<!-- id: BOT; label: Bot; summary: What admins see and what the operator does not check -->

A class is run in the Telegram bot. Its admins see the names and Telegram usernames of the class's members and the names of the connected phones (manufacturer and model), can disconnect any of those phones, and read the change log: who changed what.

The operator does not check what a class's admins and editors type into the bot: the timetable, substitutions, homework, events, teachers' names.

## Availability
<!-- id: AVAILABILITY; label: Availability; summary: What is not promised: the server, the bot and the diary working -->

The operator does not promise that the server, the bot or the diary in the app will always be available. A diary's system may stop accepting requests from the server's address at any time, and then the diary will not open in the app.

As of this edition, signing in to a diary and reading one have never been tried against a real diary — neither Petersburg Education nor Setevoy Gorod. The first real sign-in may not work.

Features of the app, the server and the bot may change, and some may go away.

## No warranty
<!-- id: WARRANTY; label: Warranty; summary: The MIT licence — the program as is -->

The program is distributed under [the MIT licence](https://github.com/lumenpearson/lessons/blob/HEAD/LICENSE) “as is”, without warranty of any kind: nobody promises that it fits your purpose or that it works without errors.

The operator is liable for the consequences of using Lessons only to the extent that the law does not allow that liability to be excluded.

## How to stop using it
<!-- id: ENDING; label: Leaving; summary: What happens when you sign out, leave or uninstall -->

- **Sign out of the diary** — in the app or in the bot. The server deletes the session and, for Setevoy Gorod, also tells the diary you have signed out; the corrections to the diary stay on the server until somebody who sees that pupil resets them — you, or the other parent, for example.
- **Leave a class** in the app — this erases only what is kept on the phone. The server deletes its record of the phone by itself 180 days after the phone's last request; an admin can disconnect the phone sooner, but even then the record is kept until that date.
- **Uninstall the app** — this erases everything it keeps on the phone; on the server, data is kept for the periods the privacy policy gives.

To have the server delete what it keeps about you and does not delete by itself, write to the operator — the “Contact” section says how.

## Changes
<!-- id: CHANGES; label: Changes; summary: Editions, the date, and what continuing to use it means -->

This text has an edition number and a date from which it applies; both are in the file `legal.json` next to it. The app carries a copy of the edition it was built with, and opens the current one at its link when the build knows that link.

By continuing to use the app, the server or the bot after the text has changed, you accept the new edition.

Neither the phone nor the server records what you accepted or when: using Lessons is what counts as accepting. The app says so on its first screen, under the button; the bot does not show a link to these terms yet.

This text is written in Russian, and the English is its translation. Where they differ, the Russian prevails.

## Contact
<!-- id: CONTACT; label: Contact; summary: How to reach the operator -->

<!--
  FORK: [OPERATOR] [CONTACT]
  Both paragraphs below are the upstream's: the operator is the maintainer of
  github.com/lumenpearson/lessons, and the contact is that repository's public Issues page
  plus the «Отправить письмом» address a build may carry (LESSONS_CONTACT_EMAIL). A fork
  names its own operator and a contact it actually reads.
-->

The operator of this server is the owner of the GitHub account `lumenpearson`, who maintains the repository [lumenpearson/lessons](https://github.com/lumenpearson/lessons).

You can write to the operator on [the repository's Issues page](https://github.com/lumenpearson/lessons/issues). Anybody can read it, so do not write logins, passwords, phone numbers or children's names there — only what your question is about. If your build has a “Send by email” button (Settings → About → Report a problem), you can also write by email.
