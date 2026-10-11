<!--
  The English translation of privacy.ru.md. The Russian file is the source: it carries the
  notes on the format, the FORK fields and the list of what the code does not do, and when
  the two disagree the Russian prevails (section «Changes»). Keep the sections, their ids and
  their blocks level with the Russian — LegalDocumentsTest in :core:data compares them.
-->

# Lessons privacy policy

## Who is responsible for the data
<!-- id: OPERATOR; label: Operator; summary: Who the operator is, which server this text is about, and whose choice it is to use it -->

This text is the operator's policy on the processing of personal data that article 18.1 of Russia's Federal Law No. 152-ФЗ «On personal data» asks for. It describes what the app Lessons («Дневник» in Russian), its server and its Telegram bot do with data. What it says about the app holds for any build of it from this code; what it says about the server holds for a server started from this code unchanged; and the operator and the place of storage are named below for one server only — the one this operator runs.

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

- **The operator** is the maintainer of the repository [lumenpearson/lessons](https://github.com/lumenpearson/lessons) on GitHub. They keep the repository the app is built from and run the server this text was written for.
- **Where the server runs** — Vercel hosting, region `fra1` (Frankfurt am Main, Germany); the database is Neon, region `aws-eu-central-1` (Frankfurt as well).
- **How to get in touch** — see “Contact” at the end.

Nobody is obliged to use Lessons. The school, its electronic register and your region's diary system work without it, and nothing a pupil or a parent is entitled to under the law on education is arranged through the app. Whether to install the app is your choice, and it can be undone by uninstalling it. The source code of the app, the server and the bot is open, so what the program actually does can be read before installing it.

You type the server's address into the app yourself. If it leads to a server somebody else started, whoever started it is responsible for the data on that server and in its bot, and their policy is the one that should describe it.

## Why, and on what basis
<!-- id: BASIS; label: Basis; summary: The purposes, consent, the terms, and what the server does on your instruction -->

The data is processed for what the app does, and for nothing else:

- to show the class timetable kept in the bot — on the phone and in the widget;
- to show you your electronic diary and to keep the corrections you make over it;
- to run a class in the bot: who is in it, with what rights, and who changed what;
- to protect the server from code and password guessing;
- to answer your questions and requests about data.

The basis is your consent, which you give when you start using the app or the bot, and the terms of use you accept at the same time. There is no separate consent screen in the app: using it counts as consent, and the app's first screen says so under its button. If a parent has to consent on a pupil's behalf, see “Children and parents”.

The server reads your diary on your instruction. You sign in to the diary's system yourself, hand the server the session it gave you, and the server reads the diary with that session when you open it — and keeps nothing of what it read, except what an entry said at the moment it was corrected (see “How signing in to a diary works”).

Lessons collects no special categories of personal data — health, ethnicity, views — and no biometrics. No decision with legal consequences for you is made on the basis of this data: the app shows, it does not judge.

The processing is automated: the data sits in the server's database and in the phone's memory, and a program works with it. The operator looks into the database when answering a request, investigating a failure or checking how the server runs.

## What is kept on the phone
<!-- id: PHONE; label: Phone; summary: What the app keeps and what is kept out of a backup -->

- For each class you have joined: an access key, and the names of the class and the school.
- A copy of the class timetable, events and homework, teachers' names included, so that the app and the widget work offline.
- If you have signed in to a diary: the diary access key the server issued, your login, which diary, region and school it is and which pupil you chose; and, in a separate database, a copy of the diary — lessons, homework, marks, pupils' and teachers' names.
- The server's address, the app's settings and, if you signed in to GitHub from the app, the GitHub key and your account name there.
- Crash reports, only if you turned them on: up to five files with the phone's model, the Android and app versions, a description of the error and the app's latest log lines.
- If you turned on the developer mode (Settings → About, seven taps on the version; it opens only for a GitHub account with write access to the repository): a record of the app's requests (the address without secrets, the method, the status, the duration; no contents and no headers) and a record of its actions. Both live only in memory while the app runs, and go nowhere until you share a report yourself.

The app keeps the class and diary access keys encrypted, with a key that Android holds in its protected store (the Android Keystore) and never hands out. If that key is lost — after the app's data is moved to another phone, say, or the store is reset — you will have to join the class and sign in to the diary again. The login, the GitHub key and the settings sit in the app's settings files **unencrypted**. The databases, the settings files and the crash reports are excluded from Google backup and from transfer to a new phone: a report stays on this phone until you send it yourself. That crash reports really are kept out of the backup has not yet been checked on a device.

The phone never stores the diary password. The session the diary hands out after sign-in is held only in memory, never written down, and forgotten as soon as the phone has handed it to the server or the sign-in is abandoned.

## What the server receives and keeps
<!-- id: SERVER; label: Server; summary: Phones, diary sessions, corrections, school search, the bot -->

When a phone joins a class, the server keeps:

- the phone's access key — only as a SHA-256 hash, from which the key cannot be recovered;
- the phone's name — its manufacturer and model;
- when the phone last made a request to the server, and which app version it named;
- the Telegram account, if the phone has been linked to one.

When you sign in to a diary, the server keeps:

- the diary session — **encrypted** with the operator's key; for Setevoy Gorod, the region, the school's number and name and the school year's details are sealed inside with it;
- your login — in plain text, as you typed it, minus spaces at its ends and characters that print nothing; in Petersburg Education that is an email address;
- which diary and region it is, when the session was opened, when it was last used, when the server last kept it open and when the diary stopped accepting it;
- the diary access key issued to the phone — only as a SHA-256 hash.

A session opened in the app is linked to neither a Telegram account nor a class, and the bot does not see it. A session opened from the bot is linked to your Telegram account and to the class, and remembers which pupil you chose.

Corrections to the diary: what was corrected, the new value and what the diary said when the correction was made. They are tied not to a login or a session but to the pupil — to the diary system (for Setevoy Gorod, the region's server) and the pupil's number in it — so they remain after you sign out of the diary and are shared by everyone whose account shows that pupil (see “Who can read your diary”). The server does not record who made a correction, and no login is kept with it.

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
<!-- id: NEVER; label: Not kept; summary: The password, the diary's contents, Gosuslugi, analytics -->

**The server does not keep your diary password.** The app this text comes with sends the password only to the diary's system, straight from the phone, and the server never receives it.

The password does pass through the server in two cases: when you sign in to a diary on the page the bot opens — the browser sends the password to the server, and the server passes it to the diary once — and when an older version of the app signs in, one that does not have this text yet. In both cases the server writes the password down nowhere.

**The diary's contents** — marks, homework, timetable, the list of pupils — are read by the server when you ask and passed on without being kept. The exception is what an entry said when it was corrected.

**Your Gosuslugi account** is seen by neither the app nor the server: they do not sign in to it, read nothing from it and keep nothing of it.

**There are no analytics and no advertising identifiers**, in the app or on the server. The app sends no crash report anywhere by itself. The server, if its operator set it up so, sends reports of its own errors to the Sentry service, without personal data (see “Third parties”).

## The regions' electronic diaries
<!-- id: DIARIES; label: Diaries; summary: Whose systems they are, which the app signs in to, which it opens in the browser and which it does not know -->

Russia has no single electronic diary. Every region has its own, kept by the region's education authorities and its schools as part of their duty under the law on education. As of this edition, by public sources of September 2026, families in twenty regions have the federal «Госуслуги Моя школа» (the state system «Моя школа») as their diary, and in many more it serves as an additional way in; twenty have «Сетевой город. Образование» (Setevoy Gorod); thirteen have Dnevnik.ru; Moscow has the Moscow Electronic School (МЭШ), and eleven regions have «Моя школа» built on it; Saint Petersburg has Petersburg Education; the rest have the «one.» platform, ЭлЖур, БАРС, КИАСУО, ЭПОС.Школа, «Электронная школа 2.0», «Виртуальная школа» or «АйТи Школа», and Sevastopol keeps paper diaries this school year. Each system has its own operator — the region's education authority, a school or the company that built it — and its own rules, policy and consent form, which you accepted when you were given an account in it.

Lessons is bound to none of these systems by any contract, does not act on their behalf and is not part of them. It gets a pupil's data from a diary only because you signed in to your own account yourself and handed the server the session you were given.

What the app can do with each system it shows when you choose a region, and the same is written in [the region catalog](https://github.com/lumenpearson/lessons/blob/HEAD/server/app/catalog/data/regions.json) in the source code, which is where the app takes that list from:

- **Sign-in** — the app signs in to the system and reads the diary through the server. As of this edition that is Petersburg Education and Setevoy Gorod in the sixteen regions where it accepts a login and password; those regions are listed in [the server's source code](https://github.com/lumenpearson/lessons/blob/HEAD/server/app/providers/netschool/regions.py).
- **Hand-off** — the app opens the system's website in your browser and receives nothing from it. That is how «Госуслуги Моя школа» opens wherever it exists, and how Setevoy Gorod's servers open in the regions that accept only Gosuslugi.
- **Nothing** — for every other system the app has only the class timetable kept in the bot; it does not read the diary.

You choose the region and the system yourself; the app does not learn your location and does not work out your school from your IP address. If you search for a school by name, see “What the server receives and keeps”.

The app has not yet signed in to any of the systems listed with a real account: the sign-in and the reading were written from public descriptions of those systems and may not work the first time.

## How signing in to a diary works
<!-- id: SIGN_IN; label: Sign-in; summary: The sign-in happens in the diary's system, and the server gets only a session -->

Signing in to a diary happens in the diary's own system. The app creates no accounts and does not check who you are: the diary's system did that when it gave you an account, and it is the one that checks the login and password at every sign-in. All the app gets from it is a **session** — a temporary key the system issues after a successful sign-in and hands the diary out against; it is the same thing a browser gets when you sign in to the diary's website. The phone hands that session to the server, and the server reads the diary with it on your behalf.

1. **The form** — You type your login and password into the app's own form, which says which system and which address they will go to.
2. **The diary's system** — The app sends them over HTTPS straight to the diary system's server — only to addresses on a list built into the app — and gets a session back. Whether to let you in is the diary system's decision: neither the app nor the server decides it, and neither could.
3. **The server** — The app hands that session and your login to the server, plus the region and school for Setevoy Gorod. The server checks the session with a request of its own to the diary (four for Setevoy Gorod), encrypts it and gives the app a diary access key of its own.
4. **After that** — The phone forgets the diary's session and reads the diary through the server, and the server talks to the diary from its own address or through the intermediary server (see “Third parties”).

If the diary does not accept the session from the server's address, the server does not keep it, and tells the app that the problem is not the password but where the session came from.

A session lasts as long as the diary's system decides. It can be closed by you — by signing out of the diary in the app or in the bot — by the system itself, and by the operator; Setevoy Gorod is also told about the sign-out by the server.

The app does not sign in to Gosuslugi or to My School: it opens the system's page in your browser, and nothing from Gosuslugi reaches either the app or the server.

## Who can read your diary
<!-- id: READERS; label: Readers; summary: The diary's system, the operator, Telegram, whoever has the phone, and whoever sees the same pupil -->

- **The diary's system**, after sign-in, sees the server's address using your session, not your phone's.
- **The operator** holds the key the sessions are encrypted with, and so can technically open your diary. The encryption protects against whoever gets the database without the key, not against the operator.
- **Telegram** keeps in the chat history whatever the bot sent you from the diary — marks, homework, timetable.
- **Whoever holds your phone:** the diary access key on it is encrypted, but the app decrypts it itself and opens the diary without a password until you sign out.
- **Everyone whose diary account shows the same pupil** — the other parent, the pupil in their own account, and any other account in which the diary shows that pupil — sees in the app the corrections made for that pupil, whoever made them, and can change or reset them. Beyond the corrections, they see only what the diary shows their own account.

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

**With no time limit** — until somebody deletes them or deletes the class — the server keeps the change log, tasks and “done” ticks, reminder times, requests for rights, invitations by phone number, your membership of the class in the bot, and the class's data. Diary corrections and the class chosen in the bot are not deleted even with the class. A correction is deleted by the “Reset corrections” button — for everyone at once, and anybody who sees that pupil can press it.

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
<!-- id: THIRD_PARTIES; label: Third parties; summary: Who processes data on the operator's instruction, and who else gets it -->

<!--
  FORK: [HOSTING] [DB] [SENTRY] [PROXY] [DADATA] [REPOSITORY]
  «Vercel и Neon» are the upstream's.
  The Sentry point is the upstream's: SENTRY_DSN set on Vercel (#359), organisation region
  de.sentry.io (read 11 October 2026). A fork without SENTRY_DSN deletes the point, in both
  files, and the sentence in «Чего сервер не хранит» says the server sends nothing.
  The intermediary server is the upstream's: DIARY_PROXY_URL on a VPS at RUVDS (#334, #406),
  used for the servers the registry and regions.py mark via_proxy, under DIARY_EGRESS.
  A fork with no DIARY_PROXY_URL deletes the point, in both files.
  DaData applies only where DADATA_TOKEN is set; without
  it the search refuses at the door and Settings.disabled_features logs it as off. The GitHub
  point holds for every build of this code: the update check and the guide are fetched from
  lumenpearson/lessons, hard-coded in GithubApi.kt, whatever repository built the APK.
  «No individual contract» is a fact about the upstream on 11 October 2026: every service
  below is used on its public terms. A fork that signs one says so.
-->

The services in the first five points were chosen and set up by the operator, who can drop them; what of the data each one sees is said in its point. The operator uses each of them on its public terms; there is no individual data-processing agreement with any of them. The rest receive data because that is how the app and the bot are built, and act under rules of their own.

- **The hosting and the database** — Vercel and Neon, for this operator: the server runs on their machines, and everything it keeps is stored there.
- **Sentry** — if the operator has connected it (this operator has): the server sends it reports of its own errors and of the response time of some requests — the error's type, the place in the code, the request's route, the server's version. The requests' contents, headers and parameters, your data and your IP address do not go there. This operator's Sentry data is kept in Germany.
- **The operator's intermediary server in Russia** — RUVDS hosts it for this operator. The server's requests to Petersburg Education and to the Setevoy Gorod servers marked in [the regions table](https://github.com/lumenpearson/lessons/blob/HEAD/server/app/providers/netschool/regions.py) in the source code go through it (it is an HTTP proxy) — as of this edition, eleven of the sixteen regions; the server talks to the rest directly. It is a tunnel encrypted with TLS from the server to the diary itself: the intermediary sees only the diary site's address and the time of the request and writes them to its log, but never sees logins, passwords, sessions or marks.
- **DaData** — if the operator has turned school search on: the server sends it the school name you typed, but not your IP address.
- **The server's outside clock** — a service that calls the server on a schedule, if the operator has set one up: it knows only the server's address and the secret of the call, and gets nothing of your data.
- **Telegram** — everything you do in the bot and everything the bot sends you goes through it.
- **The diary systems** — the ones the app signs in to (see “The regions' electronic diaries”). The phone sends them your login and password when you sign in, and sends Setevoy Gorod your school's name when you search for it; the server sends them your requests to the diary with your session.
- **GitHub** — once a day at start-up the app checks there whether a new version is out (on by default, can be turned off in settings), and fetches the guide from there when you open it; GitHub sees the phone's address and the app's version when it does. A bug report or a translation fix goes there publicly — only if you send it yourself.
- **Google** — the phone's backup, but only what is not excluded from it (see “What is kept on the phone”).
- **Gosuslugi and the diary systems' websites** that the app opens in your browser: their own rules apply there.

## Where the data is kept
<!-- id: ABROAD; label: Where; summary: The countries the data is kept in and passes through -->

<!--
  FORK: [COUNTRY]
  Germany is where the upstream's Vercel region (fra1), Neon region (aws-eu-central-1) and
  Sentry organisation (de.sentry.io) are. A fork states its own countries.
-->

This operator's server, its database and its error reports are in Germany, in Frankfurt am Main. Everything the server keeps is kept outside Russia, and data from a phone in Russia leaves the country — a cross-border transfer in the sense of Law No. 152-ФЗ.

- **Germany** — Vercel, Neon and Sentry: everything the server keeps, and the reports of its errors.
- **The United States** — GitHub: the phone's address and the app's version when checking for updates and reading the guide; the reports and translations you sent yourself.
- **Outside Russia** — Telegram: everything that passes through the bot.
- **Russia** — the operator's intermediary server, DaData and the diary systems themselves.
- **Where your phone's backup is kept** is decided by Google and your settings.

If you have connected to a different server, whoever started it decides where its data is kept.

## Children and parents
<!-- id: CHILDREN; label: Children; summary: Whose consent is needed, and what the app does not check -->

A diary is information about a child: their marks, homework and timetable. If a pupil under 18 uses Lessons, it should be with the knowledge and consent of their parent or another legal guardian. For a pupil under 14 the consent is the parent's, and the parent is the one who should use the app on that pupil's behalf; a pupil of 14 to 18 may use it themselves with a parent's consent.

The app does not ask anybody's age and checks nothing about who signs in: who the account holder is was already checked by the diary's system when it issued the account. A parent's diary account may show several children, and everybody who signs in to it sees all of them. Corrections to the diary are shared for the child: everyone whose account shows that child sees them and can change or reset them — the child too, in their own account.

A parent who wants the server to delete their child's data, or wants to know what is kept about the child, can write to the operator — the “Contact” section says how.

## Other people's data
<!-- id: OTHERS; label: Other people; summary: Teachers, numbers in invitations, names in the log and other children in an account -->

Part of the data on the server is not about you but about other people, and it does not come from them:

- **Teachers' names** in the timetable and in the diary. In the class timetable they are typed by the class's admins and editors in the bot; from the diary they arrive as the diary's system shows them.
- **Phone numbers in invitations.** An admin types a number so that the person with that number can join the class; the server keeps the number and its label until the invitation is deleted.
- **Telegram names and usernames** of the class's members — in the change log and in the list of members the admins see.
- **Other children** that your parent's diary account shows. The app shows all of them, and the server keeps about them only the corrections, if you made any.

Whoever types somebody else's name or number into the bot is responsible for having the right to; the operator does not check it. A person whose name or number is kept can ask the operator to delete it — see “Contact”.

## Security
<!-- id: SECURITY; label: Security; summary: Hashes, the encrypted session, who has access, and why an http:// address is risky -->

- The access keys of phones and diaries and the one-time codes — for signing in from the bot and for connecting a phone — are kept by the server only as SHA-256 hashes. The class code is kept as it is: the bot shows it to admins.
- The server encrypts the diary session (Fernet: AES-128-CBC with HMAC) with a key the operator sets. Without that key the diary does not run on the server at all, rather than keeping sessions unencrypted.
- On the phone the app encrypts only the class and diary access keys (AES-256-GCM, with a key from the Android Keystore). If the phone's key store does not work, it keeps them unencrypted, as it used to, and tries again to encrypt them at every start. Everything else — the copies of the timetable and the diary, the login, the settings — is protected only by whatever protects the phone itself. The encryption of the keys has not yet been checked on a device.
- The operator has access to the database and to the encryption key; the hosting's and the database's staff have it under their own rules. The server keeps no log of who read the database and when.

**The app talks to the server over HTTPS only.** It does not accept an address that starts with `http://`, and sends nothing to one: at such an address everything between the phone and the server — the class and diary access keys, the phone's name, the timetable, and the diary session the phone hands to the server when you sign in — would travel in the clear, and anybody on the same network could intercept it and open your diary. The exception is a server on the phone itself (`localhost`, `127.0.0.1`): requests to it never leave the phone. A debug build of the app, made for development, does accept `http://` addresses, and sends everything to them in the clear.

In the app, whatever the server's address, the diary password goes only to the diary's system, and only over HTTPS.

## Your rights and what you can do
<!-- id: CHOICES; label: Your rights; summary: Sign out, reset, switch off, ask, delete — and what you cannot do yourself -->

- Sign out of the diary in the app or in the bot — the server deletes the session. If the phone is offline at that moment, the session is deleted by itself 30 days after it was last used, and until then the server keeps a Setevoy Gorod session open.
- Reset a correction to the diary — for everyone who sees that pupil, whoever made it.
- Leave a class — this erases the copy on the phone, but not the server's record of the phone.
- Turn off the automatic update check. Crash reports stay off until you turn them on yourself.
- Uninstall the app — this erases everything it keeps on the phone.

Under Law No. 152-ФЗ you have the right to learn what data of yours the operator keeps, where it came from and whom it was passed to, to have it corrected, blocked or deleted, and to withdraw your consent to the processing. To do so, write to the operator — the “Contact” section says how. The operator answers within the period the law sets: ten working days from receiving the request, and up to five more if the operator warns you that more time is needed. Withdrawing consent means the operator deletes what it keeps about you, and the app and the bot cannot be used further without that data.

You cannot remove yourself from the bot or delete your data from the server on your own: there are no commands for that, so write to the operator. The operator cannot tell which corrections to the diary are yours: the server does not record who made each. So a request to delete your corrections is met by deleting that pupil's corrections — including those somebody else made for that pupil — or the particular ones the request names. The operator cannot delete your Telegram chat history or the data in the diary's system — that is done there.

## If the data leaks
<!-- id: INCIDENTS; label: Leaks; summary: What the operator will do, and what you can do -->

If the operator learns that somebody unauthorised has reached the server's data, they will report it to Roskomnadzor within the periods the law sets — within a day about the event itself and within three days about what was found out — and will write about it on [the repository's Issues page](https://github.com/lumenpearson/lessons/issues), so that everyone it may concern learns of it.

What you can do if you suspect that your session or your phone has fallen into somebody else's hands:

- sign out of the diary in the app or in the bot — the server deletes the session and tells Setevoy Gorod about the sign-out;
- change your password in the diary's system — what happens then to the sessions already issued is up to that system;
- ask a class admin to disconnect the phone in the bot — after that the phone's key stops working.

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

Questions about your data and requests to delete it go to [the repository's Issues page](https://github.com/lumenpearson/lessons/issues). It is open to everyone, so write no logins, passwords, phone numbers or children's names there — only the substance of the request and something the operator can find you by without revealing more, such as a Telegram username. If your build has a “Send by email” button (Settings → About → Report a bug), you can write by email too — only the operator sees it.
