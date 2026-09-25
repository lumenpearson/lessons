<!--
  The privacy policy of «Дневник», in English: the translation of privacy.ru.md, which is the
  source. Keep the two level — the same sections, in the same order, with the same ids, built
  from the same blocks — and change the Russian first.

  The format, the rule that this text describes the code, the list of what it must not
  promise, and the FORK passages with their fields are described at the top of
  privacy.ru.md. Every github.com/lumenpearson/lessons link is the field [REPOSITORY].
-->

# Privacy policy of Lessons

## Who is responsible for the data
<!-- id: OPERATOR; label: Operator; summary: Who keeps the data and which server this text is about -->

This text describes what the app Lessons («Дневник» in Russian), its server and its Telegram bot do with data. What it says about the app holds for any build of the app from this code, what it says about the server holds for a server run from this code unmodified, and the operator and the place the data is kept are named below for one server only — the one this operator runs.

<!--
  FORK: [OPERATOR] [HOSTING] [DB] [SERVER_ADDRESS] [BOT]
  The list below is the upstream deployment: the operator is the maintainer of
  github.com/lumenpearson/lessons; the hosting is Vercel, region fra1 (vercel.json); the
  database is Neon, region aws-eu-central-1 (read from the Neon project, not from the
  repository). A fork that runs its own server rewrites the first two points. The upstream
  names no server address and no bot on purpose: the repository publishes neither, and the
  APK has no server built in. A fork with a public address or bot may add them to the list,
  keeping the Russian and English lists the same length.
-->

- **The operator** is the maintainer of the repository [lumenpearson/lessons](https://github.com/lumenpearson/lessons) on GitHub.
- **Where the server runs**: on Vercel, region `fra1` (Frankfurt am Main, Germany); the database is on Neon, region `aws-eu-central-1` (also Frankfurt).
- **How to get in touch**: see the “Contact” section at the end.

You type the server's address into the app yourself. If it leads to a server somebody else started, whoever started it is responsible for the data on that server and in its bot, and their own policy should describe it.

## What is kept on the phone
<!-- id: PHONE; label: Phone; summary: What the app keeps and what is kept out of a backup -->

- For each class you have joined: an access key, and the names of the class and the school.
- A copy of the class timetable, events and homework, teachers' names included, so that the app and the widget work offline.
- If you have signed in to a diary: the diary access key the server issued, your login, which diary, region and school it is and which pupil you chose; and, in a separate database, a copy of the diary — lessons, homework, marks, pupils' and teachers' names.
- The server's address, the app's settings and, if you signed in to GitHub from the app, the GitHub key and your account name there.
- Crash reports, only if you turned them on: up to five files with the phone's model, the Android and app versions, a description of the error and the app's latest log lines.

The keys, the login and the settings sit in the app's settings files **unencrypted**. The databases, the settings files and the crash reports are excluded from Google backup and from transfer to a new phone: a report stays on this phone until you send it yourself. That crash reports really are kept out of the backup has not yet been checked on a device.

The phone never stores the diary password. The session the diary hands out after sign-in is held only in memory, never written down, and forgotten as soon as the phone has handed it to the server or the sign-in is abandoned.

## What the server receives and keeps
<!-- id: SERVER; label: Server; summary: Phones, diary sessions, corrections, school search, the bot -->

When a phone joins a class, the server keeps:

- the phone's access key — only as a SHA-256 hash, from which the key cannot be recovered;
- the phone's name — its manufacturer and model;
- when the phone last made a request to the server;
- the Telegram account, if the phone has been linked to one.

When you sign in to a diary, the server keeps:

- the diary session — **encrypted** with the operator's key; for Setevoy Gorod, the region, the school's number and name and the school year's details are sealed inside with it;
- your login — in plain text, as you typed it, minus spaces at its ends and characters that print nothing; in Petersburg Education that is an email address;
- which diary and region it is, when the session was opened, when it was last used, when the server last kept it open and when the diary stopped accepting it;
- the diary access key issued to the phone — only as a SHA-256 hash.

A session opened in the app is linked to neither a Telegram account nor a class, and the bot does not see it. A session opened from the bot is linked to your Telegram account and to the class, and remembers which pupil you chose.

Your corrections to the diary: what you corrected, the new value and what the diary said when you made the correction. They are tied to your login and the pupil's number, not to the session, so they remain after you sign out of the diary.

If the operator has turned school search on, you can find your region by your school's name while choosing a diary: the server passes the text you typed to the DaData service. The server does not write that text to its database, but it is part of the request's address, and so it stays in the hosting's request log.

To limit guessing of codes and passwords, the server counts failed attempts to join a class and to sign in to a diary, and every school search too. To do so it writes down a SHA-256 hash of your IP address, taken without a secret key. Such a hash can be reversed by trying every address, so this is not anonymisation: it is personal data.

If you use the bot, the server keeps:

- your Telegram account number, your username and name as Telegram gives them, and your role in the class;
- your phone number — only if you shared your contact yourself and an invitation was found for it; a number with no invitation is not stored;
- the invitations by phone number that admins set up, with their labels;
- your tasks, your “done” ticks, your reminder times and your requests for rights, with their text;
- the class's change log — who changed what; its lines may contain names and phone numbers;
- what you started typing in the bot and did not finish, and the class you chose if you are in several.

The class's own data — the timetable, substitutions, homework, events, teachers' names and who added each homework — is kept on the server for as long as the class exists.

## What the server does not keep
<!-- id: NEVER; label: Not kept; summary: The password, the diary's contents, analytics -->

**The server does not keep your diary password.** The app this text comes with sends the password only to the diary's system, straight from the phone, and the server never receives it.

The password does pass through the server in two cases: when you sign in to a diary on the page the bot opens — the browser sends the password to the server, and the server passes it to the diary once — and when an older version of the app signs in, one that does not have this text yet. In both cases the server writes the password down nowhere.

**The diary's contents** — marks, homework, timetable, the list of pupils — are read by the server when you ask and passed on without being kept. The exception is what an entry said when you corrected it.

**There are no analytics, no advertising identifiers and no automatic crash reporting**, in the app or on the server.

## How signing in to a diary works
<!-- id: SIGN_IN; label: Sign-in; summary: Where the password goes and what the server gets -->

1. **The form** — You type your login and password into the app's own form, which says which diary and which address the password will go to.
2. **The diary** — The app sends them over HTTPS straight to the diary system's server — only to addresses on a list built into the app — and gets a session back.
3. **The server** — The app hands that session and your login to the server, plus the region and school for Setevoy Gorod. The server checks the session with a request of its own to the diary (four for Setevoy Gorod), encrypts it and gives the app a diary access key of its own.
4. **After that** — The phone forgets the diary's session and reads the diary through the server, and the server talks to the diary from its own address.

If the diary does not accept the session from the server's address, the server does not keep it, and tells the app that the problem is not the password but where the session came from.

The app does not sign in to Gosuslugi or to My School: it opens the system's page in your browser, and nothing from Gosuslugi reaches either the app or the server.

## Who can read your diary
<!-- id: READERS; label: Readers; summary: The diary's system, the operator, Telegram and whoever has the phone -->

- **The diary's system**, after sign-in, sees the server's address using your session, not your phone's.
- **The operator** holds the key the sessions are encrypted with, and so can technically open your diary. The encryption protects against whoever gets the database without the key, not against the operator.
- **Telegram** keeps in the chat history whatever the bot sent you from the diary — marks, homework, timetable.
- **Whoever holds your phone:** the diary access key sits on it unencrypted, and the app opens the diary without a password until you sign out.

## Keeping the session open
<!-- id: KEEPALIVE; label: Session; summary: How the server keeps a Setevoy Gorod session open and for how long -->

A Setevoy Gorod session closes by itself after a quarter of an hour to an hour without requests, and the server has no password to sign in again with. So while the server's clock is running — an outside service that calls the server every few minutes, if the operator has set one up — the server contacts Setevoy Gorod with each open session, no more than once every four minutes, so that it does not close.

This goes on for up to 30 days after you last signed in to the diary or read it through the server — in the app or in the bot; then the server deletes the session. The requests that keep the session open do not count as use and do not extend that period.

Use means the diary being read by the app while it is open on screen, or by the bot. The widget and background sync do not read the diary and do not extend the session.

The server does not keep Petersburg Education sessions open: they last as long as that system allows, and that period is documented nowhere.

## How long things are kept
<!-- id: RETENTION; label: Retention; summary: What is deleted by itself, when, and what is not -->

- The IP address hash from a failed attempt or a school search: no more than an hour.
- A diary session: 30 days after it was last used; a session the diary stopped accepting: one day after that was found out.
- The record of a phone in a class: 180 days after its last request; neither leaving the class in the app nor an admin disconnecting the phone deletes it sooner.
- The link for signing in to a diary from the bot: 15 minutes; a personal code for connecting a phone: one day after its 15 minutes have run out.
- What you started typing in the bot: 2 days without a change.

**With no time limit** — until somebody deletes them or deletes the class — the server keeps the change log, tasks and “done” ticks, reminder times, requests for rights, invitations by phone number, your membership of the class in the bot, and the class's data. Diary corrections and the class chosen in the bot are not deleted even with the class; corrections are deleted by the “Reset corrections” button.

These periods are kept by the server's clock. If the operator has not set that clock up, the records in this list stay past their periods, and Setevoy Gorod sessions are not kept open.

<!--
  FORK: [DB_HISTORY] [HOSTING_LOGS]
  The paragraph below is the upstream's hosting: the Neon project keeps 21600 seconds of
  history (history_retention_seconds), and Vercel keeps request logs for as long as its plan
  does — a number the repository does not hold. A fork on other hosting describes its own,
  including whether it logs request URLs.
-->

This server's database (Neon) keeps a history of changes for a further 6 hours, so something deleted can be restored within that time. The hosting (Vercel) keeps request logs with the IP address, the request's address and the time; some addresses contain a secret — the link to a class's calendar, for example, or the link for signing in to a diary from the bot. How long the hosting keeps those logs is set by its own rules.

## Third parties
<!-- id: THIRD_PARTIES; label: Third parties; summary: Who else gets what, besides the server -->

<!--
  FORK: [HOSTING] [DB] [DADATA] [REPOSITORY]
  «Vercel и Neon» are the upstream's. DaData applies only where DADATA_TOKEN is set; without
  it the search refuses at the door and Settings.disabled_features logs it as off. The GitHub
  point holds for every build of this code: the update check and the guide are fetched from
  lumenpearson/lessons, hard-coded in GithubApi.kt, whatever repository built the APK.
-->

- **The hosting and the database** — Vercel and Neon, for this operator: the server runs on their machines, and everything it keeps is stored there.
- **Telegram** — everything you do in the bot and everything the bot sends you goes through it.
- **The diary systems** — Petersburg Education and Setevoy Gorod's regional servers. The phone sends them your login and password when you sign in, and sends Setevoy Gorod your school's name when you search for it; the server sends them your requests to the diary.
- **DaData** — if the operator has turned school search on: the server sends it the school name you typed, but not your IP address.
- **GitHub** — once a day at start-up the app checks there whether a new version is out (on by default, can be turned off in settings), and fetches the guide from there when you open it; GitHub sees the phone's address and the app's version when it does. A bug report or a translation fix goes there publicly — only if you send it yourself.
- **Google** — the phone's backup, but only what is not excluded from it (see “What is kept on the phone”).
- **Gosuslugi and the diary systems' websites** that the app opens in your browser: their own rules apply there.

## Where the data is kept
<!-- id: ABROAD; label: Where; summary: The country the server's data is kept in -->

<!--
  FORK: [COUNTRY]
  Germany is where the upstream's Vercel region (fra1) and Neon region (aws-eu-central-1)
  are. A fork states its own country.
-->

This operator's server and its database are in Germany, in Frankfurt am Main. Everything the server keeps is kept outside Russia, and data from a phone in Russia leaves the country.

If you have connected to a different server, whoever started it decides where its data is kept.

## Children and parents
<!-- id: CHILDREN; label: Children; summary: Parents' consent and what the app does not check -->

A diary is information about a child: their marks, homework and timetable. If a pupil under 18 uses Lessons, it should be with the knowledge and consent of their parent or another legal guardian.

The app does not ask anybody's age and checks nothing about who signs in. A parent's diary account may show several children, and everybody who signs in to it sees all of them.

A parent who wants the server to delete their child's data can write to the operator — the “Contact” section says how.

## Security
<!-- id: SECURITY; label: Security; summary: Hashes, the encrypted session, and why an http:// address is risky -->

- The access keys of phones and diaries and the one-time codes — for signing in from the bot and for connecting a phone — are kept by the server only as SHA-256 hashes. The class code is kept as it is: the bot shows it to admins.
- The server encrypts the diary session (Fernet: AES-128-CBC with HMAC) with a key the operator sets. Without that key the diary does not run on the server at all, rather than keeping sessions unencrypted.
- The app itself encrypts nothing on the phone: there, the data is protected only by whatever protects the phone itself.

**HTTPS depends on the address you typed.** The app also accepts `http://` addresses, for servers on a school's local network. At such an address everything between the phone and the server travels in the clear: the class and diary access keys, the phone's name, the timetable, and the diary session the phone hands to the server when you sign in. Anybody on the same network can intercept it and open your diary. The app warns you about this before you sign in to a diary, but does not forbid it.

In the app, whatever the server's address, the diary password goes only to the diary's system, and only over HTTPS.

## What you can do
<!-- id: CHOICES; label: Your choices; summary: Sign out, reset, switch off — and what you cannot do yourself -->

- Sign out of the diary in the app or in the bot — the server deletes the session. If the phone is offline at that moment, the session is deleted by itself 30 days after it was last used, and until then the server keeps a Setevoy Gorod session open.
- Reset your corrections to the diary.
- Leave a class — this erases the copy on the phone, but not the server's record of the phone.
- Turn off the automatic update check. Crash reports stay off until you turn them on yourself.

You cannot remove yourself from the bot or delete your data from the server on your own: there are no commands for that, so write to the operator. The operator cannot delete your Telegram chat history or the data in the diary's system — that is done there.

## Changes
<!-- id: CHANGES; label: Changes; summary: Editions, the date, and what changes this text -->

The edition number and the date it applies from are in the file `legal.json` next to this text. The app carries a copy of the edition it was built with; the current one opens at its link when the build knows that link.

When what the code keeps or sends, or how long it keeps it, changes, this text changes too, with a new edition number. By continuing to use Lessons after that, you accept the new edition; when you accepted it is recorded nowhere.

This text is written in Russian, and the English is its translation. Where they differ, the Russian prevails.

## Contact
<!-- id: CONTACT; label: Contact; summary: Questions about your data and requests to delete it -->

<!--
  FORK: [OPERATOR] [CONTACT]
  Both paragraphs below are the upstream's: the operator is the maintainer of
  github.com/lumenpearson/lessons, and the contact is that repository's public Issues page
  plus the «Отправить письмом» address a build may carry (LESSONS_CONTACT_EMAIL). A fork
  names its own operator and a contact it actually reads.
-->

The operator of this server is the owner of the GitHub account `lumenpearson`, who maintains the repository [lumenpearson/lessons](https://github.com/lumenpearson/lessons).

Write questions about your data, and requests to delete it, on [the repository's Issues page](https://github.com/lumenpearson/lessons/issues). Anybody can read it, so do not write logins, passwords, phone numbers or children's names there — only what you are asking for. If your build has a “Send by email” button (Settings → About → Report a problem), you can also write by email.
