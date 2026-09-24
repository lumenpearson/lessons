# ЭлЖур

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**The one vendor that publishes its API.** `api.eljur.ru` documents version 1.4 under `/api`
and the same methods under `/apiv3`, with a developer key (`devkey`), a `vendor` naming the
school, and an `auth_token` in the query string. The documentation's method pages are cut
short, so most parameters below come from clients, and three of its method names differ from
what the clients actually call.

**It is both a school-by-school product and a regional one.** A school has its own host,
`<vendor>.eljur.ru`; a region that bought it as its system has its own brand in front of the
same API. In September 2026 that is Нижегородская (`edu.gounn.ru`), Курганская
(`eschool.gov45.ru`), Крым (`edu.rk.gov.ru`), Севастополь and Югра. The first wave of ТОР «Моя
школа» took more ЭлЖур regions than any other platform's: Калининградская (`keo.gov39.ru`),
Курская, Липецкая, Смоленская, Ярославская (`school.yarcloud.ru`), Астраханская
(`one.astrobl.ru`, which looks like the «one.» platform and is not) and the ЛНР all moved their
families' diary to «Госуслуги Моя школа» on 1 September 2026. Whether their teachers still mark
in ЭлЖур behind it is, region by region, not known.

**Signing in.** On a school host, a login and password against the API still give a token. On
the regional hosts that went ЕСИА-only, a client starts the Госуслуги flow, receives a
`v_token`, and exchanges it for the `auth_token` — BetterJournal's EljurAuthUtil (August 2026)
is the client that does this.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| api | `https://api.eljur.ru/api` | official doc base, API v1.4 |
| api | `https://api.eljur.ru/apiv3` | same methods, newer prefix |
| api | `https://{vendor}.eljur.ru/api` | per-school host |
| api | `https://{vendor}.eljur.ru/apiv3` | per-school host |
| web | `https://{vendor}.eljur.ru` | school site, cookie session |
| auth | `https://{host}` | school or regional host that serves the login page, starts and finishes the Госуслуги flow and answers /apiv3/getusersvendors: *.eljur.ru, edu.gounn.ru, eschool.gov45.ru, riso.sev.gov.ru, edu.rk.gov.ru, cop.admhmao.ru, and until 31.08.2026 keo.gov39.ru, school.yarcloud.ru, edu.kurskobr.ru, dnevnik.admin-smolensk.ru, one.astrobl.ru |
| legacy | `https://edu.schools48.ru` | Lipetsk regional instance (ГИС «Электронная школа», Dec 2024–Aug 2026): /api/getusersvendors and /apiv3/getdiary; region moved to «Госуслуги Моя школа» on 1.09.2026 |
| web | `https://edu.gounn.ru` | Nizhny Novgorod regional instance (РГИС «Нижегородская образовательная платформа»), ЕСИА-only since 11.03.2024, still the region's diary in 2026/27; origin of the storage upload |
| auth | `https://esia.gosuslugi.ru` | Госуслуги sign-in |
| other | `https://edu-storage-1.gounn.ru` | file storage, from uploadFileUrl |
| other | `https://eljur.ru/archive` | where an archived per-school instance redirects: /authorize -> https://eljur.ru/archive?domain=<vendor> |

**Signing in.**

*API login* — token carried as query auth_token. Lifetime: expires field; about 3 months per samplec0de.

1. Call /api/auth or /apiv3/auth with devkey, vendor, out_format=json, login, password (GET query, POST form, or POST JSON with the rest in the query, depending on the client)
2. Read response.result.token and expires
3. Send auth_token, devkey, vendor, out_format on every call

   Not documented on eljur.ru/api (the doc describes only a redirect sign-in page). Works for accounts that have an ЭлЖур password — per-school *.eljur.ru instances (w1nsh/eljur-to-obsidian, 2026-09-16). Regional hosts that went ЕСИА-only (Нижегородская 11.03.2024, Калининградская 1.11.2024, Курганская by 09.2024, Ярославская from 09.2024) give families no password, so there the token comes from the Госуслуги flow or is copied out of the app.

*Госуслуги (ESIA) v2* — token carried as query v_token, then query auth_token.

1. GET {host}/journal-esia-region-action?flow=v2 and follow two redirects by hand to ESIA, keeping its cookies
2. POST esia /aas/oauth2/api/login {login,password}
3. If ENTER_MFA, POST .../login/otp/verify?code= (SMS) or .../login/totp/verify?code= with body {}; on 404 retry .../sms/verify or .../ttp/verify
4. If MAX_QUIZ, POST .../login/quiz-max/skip
5. GET redirect_url, read form action and code, POST code (multipart) to the form action, {host}/journal-esia-action/action.validate; read esiaTaskId from the HTML
6. Poll {host}/journal-esia-action/action.check?taskId= up to 10 times every 5 s until it returns link
7. GET link (/journal-esia-region-action/redirect?region=..&token=..); Location /journal-app?v_token= carries v_token
8. GET {host}/apiv3/getusersvendors?out_format=json&v_token=&auth_token=true for per-school tokens

   no redirect following, cookie jar kept by hand, browser User-Agent (Chrome 130 string by default); this is the 2026 shape (EljurAuthUtil, first commit 2026-08-31). The login pages of 2022 (school.yanao.ru) and 2024 (keo.gov39.ru) linked esiaUrl '/journal-esia-action/' and an instruction PDF eljur.ru/pdf/instr/instr_eljur_pgu.pdf, so the region-action/flow=v2 entry is newer; tokens from getusersvendors are per school (vendor) and used as auth_token on that host's /apiv3

*Official redirect* — token carried as query auth_token.

1. App sends the user to Eljur's sign-in page
2. Eljur redirects back with ?token=
3. App sends it as auth_token

   eljur.ru/api (v1.4, read 2026-09-24): «стороннее приложение перенаправляет пользователя на специальную страницу авторизации в ЭлЖур … (или авторизуется с помощью внешнего сервиса, например, ЕСИА ГосУслуги)»; the page URL is not in the public doc

*Web session* — token carried as cookie.

1. POST {vendor}.eljur.ru/ajaxauthorize username, password (Zerguzik adds return_uri=/)
2. Keep cookies; read sentryData from / ?show=home or /authorize for uid, username and role
3. Use the csrf cookie set by /journal-messages-compose-action for form posts

   the login page's inline script (GET /authorize) says which sign-in methods a host enables; ЕСИА-only regional hosts leave only the ESIA button

*Extended school access* — token carried as query login/password.

1. Support issues a school-level login and password
2. linzer0 passes login and password in the query of getschedule instead of auth_token

   seen in one 2017 client only; eljur.ru/api still names the «расширенный» access mode

*Other web sign-in options* — token carried as cookie.

1. GET {host}/authorize and read the inline switches isGoogleAuthEnabled, isMSAuthEnabled, isHseAuthEnabled, isSirsAuthEnabled, isInviteEnabled
2. Follow googleAuthUrl /authorize-google-redirect/, msAuthUrl (ADFS or Azure AD, callback /authorize-ms), or hseLkUrl /authorize-hse?redirect
3. The host sets its session cookie on return

   all disabled on keo.gov39.ru (2024) and school.yanao.ru (2022); per-host feature switches, no client uses them

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Content-Type` | application/x-www-form-urlencoded | POST sendmessage, sendreplymessage, auth |
| `User-Agent` | browser string | web and ESIA flows; iOS app string on edu.schools48.ru |
| `Referer` | messages page | journal-api-messages-action |

**Captcha and second factor.** ESIA SMS or TOTP, survey step (MAX_QUIZ); ЕСИА is the only family sign-in on regional hosts that switched to it (edu.gounn.ru, keo.gov39.ru, eschool.gov45.ru, school.yarcloud.ru); no captcha seen on ЭлЖур's own /ajaxauthorize

devkey comes from Eljur support; several public clients hard-code one, values not reproduced here. As of 2025–2026 there are two ways to a token: login+password on /api(v3)/auth for per-school instances, and the Госуслуги chain ending in /apiv3/getusersvendors (v_token → one token per school) on ЕСИА-only regional hosts. Tokens are per vendor: a regional token is sent to that regional host, not to api.eljur.ru.

**Routes** — 86 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `https://api.eljur.ru/api/auth` | Log in with login and password, get auth_token | none | body (form-urlencoded, all five fields in the body (samplec0de, w1nsh); EJournalAutomate instead puts devkey, out_format, vendor in the query and posts JSON {login,password}): `login`, `password`, `vendor`, `devkey`, `out_format` example: json | envelope {response:{state, error, result}}; result.token, result.expires 'YYYY-MM-DD hh:mm:ss'; not in the official doc (which describes only a redirect sign-in page); refused in practice for family accounts on ЕСИА-only regional hosts, which have no ЭлЖур password | samplec0de/eljur-bot `CachedTelegramEljur.py` `auth` | current |
| GET | `https://api.eljur.ru/api/getassessments` | Marks in the older format | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd) | envelope {response:{state, error, result}} | felpsyatina/1543.eljur.bot `eljur_api.py` `get_assessments` | current |
| GET | `https://api.eljur.ru/api/getdiary` | Schedule + marks + homework per day | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `rings` (boolean; adds starttime/endtime) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}: items{num}, items_extday[], alert, holiday_name | felpsyatina/1543.eljur.bot `eljur_api.py` `get_diary` | current |
| GET | `https://api.eljur.ru/api/getfinalassessments` | Final (period) marks | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd) | envelope {response:{state, error, result}}; result.students{id}: per-subject finals with period | felpsyatina/1543.eljur.bot `eljur_api.py` `get_finalassessments` | current |
| GET | `https://api.eljur.ru/api/gethomework` | Homework by day | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `class=10В` (class name instead of student) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}.items{subject}.homework{id}; with class= result.days{YYYYMMDD} (serezk4/EljurApi) | samplec0de/eljur-bot `eljur.py` `homework` | current |
| GET | `https://api.eljur.ru/api/getmarks` | Marks by subject for a range | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd) | envelope {response:{state, error, result}}; result.students{id}.lessons[]: name, average, marks[] | samplec0de/eljur-bot `eljur.py` `marks` | current |
| GET | `https://api.eljur.ru/api/getmessageinfo` | One message; also marks it read | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `id` | envelope {response:{state, error, result}}; result.message: text, user_to[], files[] | samplec0de/eljur-bot `eljur.py` `get_message` | current |
| GET | `https://api.eljur.ru/api/getmessagereceivers` | Allowed recipients by group | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` | envelope {response:{state, error, result}}; result.groups[]: key, name, users[], subgroups[] | samplec0de/eljur-bot `eljur.py` `message_receivers` | current |
| GET | `https://api.eljur.ru/api/getmessages` | Mailbox page | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `folder=inbox` (inbox\|sent); `unreadonly`; `limit`; `page` (1-based); `filter` | envelope {response:{state, error, result}}; result: messages[], count, total | samplec0de/eljur-bot `eljur.py` `get_messages` | current |
| GET | `https://api.eljur.ru/api/getperiods` | Terms and optionally their weeks | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `weeks`; `show_disabled` | envelope {response:{state, error, result}}; result.students[].periods[]: name, fullname, start, end, weeks[] | samplec0de/eljur-bot `eljur.py` `periods` | current |
| GET | `https://api.eljur.ru/api/getrules` | Current user, roles, relations, allowed methods, upload URL | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` | envelope {response:{state, error, result}}; result: name (user id), title, roles[], relations{groups,schools[],students}, uploadFileUrl, vendor, vendor_id, vuid | samplec0de/eljur-bot `eljur.py` `Eljur.profile` | current |
| GET | `https://api.eljur.ru/api/getschedule` | Timetable of a student or a class | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `class=10В` (class name instead of student); `rings` (boolean; adds starttime/endtime) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}.items; with class= result.days{YYYYMMDD} | samplec0de/eljur-bot `eljur.py` `schedule` | current |
| GET | `https://api.eljur.ru/api/getupdates` | Feed of new marks and comments | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `limit=25`; `page` | envelope {response:{state, error, result}}; result.students{id}.updates{latest[],old[]} | felpsyatina/1543.eljur.bot `eljur_api.py` `get_updates` | current |
| POST | `https://api.eljur.ru/api/sendmessage` | Send a message (doc heading «Отправка нового сообщения», anchor сообщения-отправка-сообщения-post) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; body (application/x-www-form-urlencoded): `subject`, `text`, `users_to` comma-separated ids |  | eljur.ru/api/ `index` | current |
| GET | `https://api.eljur.ru/apiv3/getboardnoticeinfo` | One announcement | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `id` | envelope {response:{state, error, result}} | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetAdvert` | current |
| GET | `https://api.eljur.ru/apiv3/getboardnotices` | School announcements | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `limit`; `page` | envelope {response:{state, error, result}}; result: notices[], count, total, total_unread | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetAdverts` | current |
| GET | `https://api.eljur.ru/apiv3/getdiary` | Schedule + marks + homework per day (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `rings` (boolean; adds starttime/endtime) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}: items{num}, items_extday[], alert, holiday_name | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetDiary` | current |
| GET | `https://api.eljur.ru/apiv3/getfinalassessments` | Final (period) marks (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd) | envelope {response:{state, error, result}}; result.students{id}: per-subject finals with period | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetFinalAssessments` | current |
| GET | `https://api.eljur.ru/apiv3/gethomework` | Homework by day (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `class=10В` (class name instead of student) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}.items{subject}.homework{id}; with class= result.days{YYYYMMDD} (serezk4/EljurApi) | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetHomeworks` | current |
| GET | `https://api.eljur.ru/apiv3/getmarks` | Marks by subject for a range (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd) | envelope {response:{state, error, result}}; result.students{id}.lessons[]: name, average, marks[] | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetMarks` | current |
| GET | `https://api.eljur.ru/apiv3/getmessageinfo` | One message; also marks it read | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `id` | envelope {response:{state, error, result}}; result.message: text, user_to[], files[] | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetMessage` | current |
| GET | `https://api.eljur.ru/apiv3/getmessagereceivers` | Allowed recipients by group | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` | envelope {response:{state, error, result}}; result.groups[]: key, name, users[], subgroups[] | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetMessageReceivers` | current |
| GET | `https://api.eljur.ru/apiv3/getmessages` | Mailbox page | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `folder=inbox` (inbox\|sent); `unreadonly`; `limit`; `page` (1-based); `filter` | envelope {response:{state, error, result}}; result: messages[], count, total | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetMessages` | current |
| GET | `https://api.eljur.ru/apiv3/getperiods` | Terms and optionally their weeks (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `weeks`; `show_disabled` | envelope {response:{state, error, result}}; result.students[].periods[]: name, fullname, start, end, weeks[] | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetPeriods` | current |
| GET | `https://api.eljur.ru/apiv3/getprofileuserinfo` | Profile card of a related student | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` | envelope {response:{state, error, result}}; result.profileUserInfo: username, schoolName, parallel, teacher, marksForYears | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetProfileUserInfo` | current |
| GET | `https://api.eljur.ru/apiv3/getrules` | Current user, roles, relations, allowed methods, upload URL | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` | envelope {response:{state, error, result}}; result: name (user id), title, roles[], relations{groups,schools[],students}, uploadFileUrl, vendor, vendor_id, vuid | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `GetRules` | current |
| GET | `https://api.eljur.ru/apiv3/getupdates` | Feed of new marks and comments (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `limit=25`; `page` | envelope {response:{state, error, result}}; result.students{id}.updates{latest[],old[]} | OpenEljur/openeljur-backend `internal/usecase/proxy.go` `GetUpdates` | current |
| POST | `https://api.eljur.ru/apiv3/sendmessage` | Send a message | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; body (application/x-www-form-urlencoded): `subject`, `text`, `users_to` comma-separated ids, `fid[]`, `filename[]` | envelope {response:{state, error, result}}; the BetterJournal spec declares no query for this operation — the auth parameters come from OpenEljur's CallPOST, which puts devkey, out_format, auth_token, vendor in the query | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `PostMessage` | current |
| POST | `https://api.eljur.ru/apiv3/sendreplymessage` | Reply to a message | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; body (application/x-www-form-urlencoded): `replyto` the open-api spec spells it reply_to; the library's own SendReply model (src/mail/SendReply.ts) sends replyto, `text`, `fid[]`, `filename[]` | envelope {response:{state, error, result}} | BetterJournal/EljurAPI `src/open-api-index.ts (branch open-api)` `PostReplyMessage` | current |
| POST | `https://edu-storage-1.gounn.ru/storage/upload` | Attachment upload; the URL comes from getrules.uploadFileUrl | query_token | `token`; `domain`; body (multipart/form-data): `userfile` | JSON with url; token lasts about 4 hours; OpenEljur's UploadFile posts the same multipart field userfile to whatever uploadFileUrl says | doctorixx/eljurc `sender/pusher.py` `push_file` | current |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login` | ESIA password step | cookie | body (application/json): `login`, `password` | action DONE\|ENTER_MFA\|MAX_QUIZ, redirect_url, mfa_details | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | current |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login/otp/verify` | ESIA second factor: SMS code | cookie | `code`; body: application/json | action, redirect_url; body is an empty JSON object | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | current |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login/quiz-max/skip` | Skip the ESIA survey | cookie |  | redirect_url | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | current |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login/totp/verify` | ESIA second factor: authenticator code | cookie | `code`; body: application/json | action, redirect_url; body is an empty JSON object | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | current |
| GET | `https://{host}/apiv3/getusersvendors` | Exchange an ESIA v_token for per-school tokens | query_token | `v_token` (RS256 JWT from the ESIA chain); `out_format=json`; `auth_token=true`; `devkey?` (not sent by EljurAuthUtil; optional in the BetterJournal spec); `vendor?` | not wrapped: {result:[{token,vendor,vendor_id,vendor_title,user_title,login,expires}]}; 400 {error, result:false}; host is the school or regional host that ran the ESIA flow (e.g. keo.gov39.ru); the BetterJournal spec also lists it under https://api.eljur.ru/apiv3 | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_fetchUsersVendors` | current |
| GET | `https://{host}/journal-esia-action/action.check` | Poll the task, up to 10 times every 5 s | cookie | `taskId` | JSON link; following it gives Location with v_token | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_exchangeOAuthCode` | current |
| POST | `https://{host}/journal-esia-action/action.validate` | Hand the ESIA code to Eljur | cookie | body (multipart/form-data): `code` | HTML with esiaTaskId; the URL is the action of the form on the ESIA redirect page, the literal path appears only in test/html_parser_test.dart | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_exchangeOAuthCode` | current |
| GET | `https://{host}/journal-esia-region-action` | Start the Госуслуги sign-in; answers with a redirect | none | `flow=v2` | 302 towards esia.gosuslugi.ru; the 2026 entry point (EljurAuthUtil, 2026-08-31). The login page snapshots of 2022 (school.yanao.ru) and 2024 (keo.gov39.ru) link esiaUrl '/journal-esia-action/' instead | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_initiateOAuthFlow` | current |
| GET | `https://{vendor}.eljur.ru/` | Home page after sign-in | cookie | `show=home`; `user` (login; Zerguzik); `domain` (school subdomain; Zerguzik) | sentryData user | i3sey/EljurTelegramBot `Eljur/auth.py` `login` | current |
| POST | `https://{vendor}.eljur.ru/ajaxauthorize` | Web sign-in, sets the session cookie | none | body (application/x-www-form-urlencoded): `username`, `password`, `return_uri` example: / (Zerguzik only) | JSON result, error. Still used in 2026 on per-school *.eljur.ru hosts (matytsyn/EljurAPI 2026-02); on ЕСИА-only regional hosts (edu.gounn.ru since 11.03.2024, keo.gov39.ru since 1.11.2024) a family account has no password to send here | i3sey/EljurTelegramBot `Eljur/auth.py` `login` | current |
| POST | `https://{vendor}.eljur.ru/ajaxrecover` | Password reset by e-mail | none | body (application/x-www-form-urlencoded): `email` |  | i3sey/EljurTelegramBot `Eljur/auth.py` `recover` | current |
| POST | `https://{vendor}.eljur.ru/api/auth` | Login on the school host under /api | none | body (application/x-www-form-urlencoded): `login`, `password`, `devkey`, `vendor`, `out_format` json | response.result.token; the same client then reads getrules, gethomework, getmarks, getperiods from https://{vendor}.eljur.ru/api (it passes the student as students=) | w1nsh/eljur-to-obsidian `src/eljur/parser.py` `EljurParser.authenticate` | current |
| GET | `https://{vendor}.eljur.ru/apiv3/getschedule` | Timetable of a student or a class (apiv3) | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student` (student id(s), comma-separated ints); `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `class=10В` (class name instead of student); `rings` (boolean; adds starttime/endtime) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}.items; with class= result.days{YYYYMMDD}; Net2Fox's README sets BaseUrl to https://{vendor}.eljur.ru/apiv3, EljurCLI uses markbook.eljur.ru/apiv3 | Net2Fox/EJournalTelegramBot `EJournalTelegramBot/Service/ElJurApiService.cs` `GetSchedule` | current |
| GET | `https://{vendor}.eljur.ru/authorize` | Sign-in page; also checks a subdomain | none |  | The sign-in page of every ЭлЖур host (DAAMCS/EljurApi also reads it). Its inline script carries the sign-in switches: isEsiaEnabled, esiaUrl '/journal-esia-action/', esiaInstructionUrl, ajaxUrl '/ajaxauthorize', isGoogleAuthEnabled/googleAuthUrl, isMSAuthEnabled/msAuthUrl, isHseAuthEnabled/hseLkUrl, isSirsAuthEnabled, isInviteEnabled, returnUri, showRegisterPasswordLinks, and sentryData {uid, username, role, domain} | web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize `authorize (Wayback 2024-11-02)` `login page` | current |
| GET | `https://{vendor}.eljur.ru/journal-api-messages-action` | Web mail list, JSON | cookie | `method=messages.get_list`; `category`; `search`; `limit`; `offset`; `teacher`; `status`; `companion`; `minDate` | list[], total, teacher, companion | Zerguzik/EljurMessageParser `src/config.py` `messageget_params` | current |
| GET | `https://{vendor}.eljur.ru/journal-app` | Diary, current week (HTML) | cookie |  |  | Evgenchick4434/EljurParser `main.py` | current |
| GET | `https://{vendor}.eljur.ru/journal-app/page.journal/class.{class}/lesson_id.{lesson_id}/sp.{period}` | Teacher class journal | cookie | `{class}`; `{lesson_id}`; `{period}` I or II (half-year) |  | jakefish18/eljur-parser `eljur_parser.py` `get_lyceum_class_page` | current |
| GET | `https://{vendor}.eljur.ru/journal-app/u.{uid}/week.{n}` | Diary week of a given user | cookie | `{uid}`; `{n}` week offset, 0 is current |  | Evgenchick4434/EljurParser `README.md` | current |
| POST | `https://{vendor}.eljur.ru/journal-app/view.miss_report/u.{uid}/sp.{period}` | Absence report | cookie | `{uid}`; `{period}` example: I+четверть |  | i3sey/EljurTelegramBot `Eljur/portfolio.py` | current |
| GET | `https://{vendor}.eljur.ru/journal-app/week.{n}` | Diary week page (HTML) | cookie | `{n}` week offset, 0 is current; clients pass week * -1 |  | matytsyn/EljurAPI `eljur/schedule.py` `table` | current |
| GET | `https://{vendor}.eljur.ru/journal-index-my-action/u.{uid}` | Marks export | cookie | `{uid}`; `mode=excel` |  | jakefish18/eljur-parser `eljur_parser.py` `get_student_marks` | current |
| POST | `https://{vendor}.eljur.ru/journal-index-rpc-action` | Settings (setPref) | cookie | body (application/x-www-form-urlencoded): `method` example: setPref, `0` msgsignature, checkforwardedemail, schedule_default_student, `1` |  | i3sey/EljurTelegramBot `Eljur/profile.py` `Settings` | current |
| GET | `https://{vendor}.eljur.ru/journal-messages-action` | Mail page, visited before the JSON call | cookie |  |  | Zerguzik/EljurMessageParser `src/main.py` | current |
| GET | `https://{vendor}.eljur.ru/journal-messages-action/category.sent` | Sent mail page | cookie |  |  | Zerguzik/EljurMessageParser `src/main.py` | current |
| POST | `https://{vendor}.eljur.ru/journal-messages-ajax-action` | Web mail RPC: getList, delete, restore, get_recipient_list | cookie | `method` (messages.get_recipient_list in the query for recipients (EljurTelegramBot); the older i3sey/API-Eljur-ru sends getRecipientList); body (application/x-www-form-urlencoded): `method`, `0..7` positional arguments |  | i3sey/EljurTelegramBot `Eljur/message.py` `getMessages` | current |
| GET | `https://{vendor}.eljur.ru/journal-messages-compose-action` | Compose page; source of the csrf cookie | cookie |  |  | i3sey/EljurTelegramBot `Eljur/message.py` `sendMessage` | current |
| POST | `https://{vendor}.eljur.ru/journal-messages-send-action/` | Send a message from the web form | cookie | body (application/x-www-form-urlencoded): `csrf`, `receivers` ids separated by ;, `subject`, `message`, `submit` «Отправить», `cancel` «Отмена» |  | i3sey/EljurTelegramBot `Eljur/message.py` `sendMessage` | current |
| POST | `https://{vendor}.eljur.ru/journal-schedule-action/` | Timetable page by POST | cookie |  |  | i3sey/EljurTelegramBot `Eljur/timetable.py` | current |
| GET | `https://{vendor}.eljur.ru/journal-student-grades-action/` | Marks, current term | cookie |  |  | matytsyn/EljurAPI `eljur/table.py` `table` | current |
| GET | `https://{vendor}.eljur.ru/journal-student-grades-action/u.{uid}/sp.{period}` | Marks by term | cookie | `{uid}`; `{period}` example: I+четверть |  | DAAMCS/EljurApi `JournalApi/portfolio.py` `get_quarter_grades` | current |
| POST | `https://{vendor}.eljur.ru/journal-student-grades-action/u.{uid}/sp.{period}` | Marks by term, fetched by POST | cookie | `{uid}`; `{period}` example: I+четверть | every page read goes through errors._fullCheck, which uses session.post | i3sey/EljurTelegramBot `Eljur/portfolio.py` `reportCard` | current |
| GET | `https://{vendor}.eljur.ru/journal-student-resultmarks-action/u.{uid}/view.results/` | Final marks by year | cookie | `{uid}` DAAMCS hard-codes u.5600 in the literal; `year` |  | DAAMCS/EljurApi `JournalApi/portfolio.py` `get_final_grades` | current |
| GET | `https://{vendor}.eljur.ru/journal-user-preferences-action` | Profile form | cookie |  |  | matytsyn/EljurAPI `eljur/profile.py` `get_profile` | current |
| POST | `https://{vendor}.eljur.ru/journal-user-security-action/` | Change password | cookie | body (application/x-www-form-urlencoded): `csrf`, `old_password`, `new_password`, `verify`, `submit_button` «Сохранить» |  | i3sey/EljurTelegramBot `Eljur/profile.py` `changePassword` | current |
| GET | `https://api.eljur.ru/api/getcws` | Tests (контрольные работы); documented only, no client calls it | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` |  | eljur.ru/api/ `index (table of contents; method bodies cut off in the fetch)` | uncertain |
| GET | `https://api.eljur.ru/api/getreplaces` | Substitutions; documented only, no client calls it | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token` |  | eljur.ru/api/ `index (table of contents; method bodies cut off in the fetch)` | uncertain |
| GET | `https://api.eljur.ru/api/schools/mobileapp` | School search for the mobile app | none | `value`; `devkey`; `out_format`; `vendor` (ELJUR_DEFAULT_VENDOR is added when set) | host is ELJUR_GLOBAL_URL, set by env, test shows .../api | OpenEljur/openeljur-backend `internal/usecase/proxy.go` `SearchSchools` | uncertain |
| POST | `https://api.eljur.ru/apiv3/auth` | Login under apiv3, form body | none | `devkey`; `out_format`; `vendor`; body (application/x-www-form-urlencoded): `login`, `password` | result.token; host is ELJUR_AUTH_URL from the environment (the config test uses https://a.example/apiv3), so api.eljur.ru is an assumption | OpenEljur/openeljur-backend `internal/adapter/eljur/client.go` `Client.AuthLogin` | uncertain |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login/sms/verify` | Fallback path tried after a 404 | cookie | `code` |  | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | uncertain |
| POST | `https://esia.gosuslugi.ru/aas/oauth2/api/login/ttp/verify` | Fallback path tried after a 404 | cookie | `code` |  | BetterJournal/EljurAuthUtil `lib/src/auth/esia_auth_service.dart` `_loginToEsia` | uncertain |
| GET | `https://{host}/authorize-google-redirect/` | Google sign-in redirect (googleAuthUrl), shown only when isGoogleAuthEnabled | none |  | isGoogleAuthEnabled = false on keo.gov39.ru and school.yanao.ru; no client uses it | web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize `authorize (Wayback 2024-11-02, inline script)` `googleAuthUrl` | uncertain |
| GET | `https://{host}/authorize-hse` | НИУ ВШЭ personal-account sign-in (hseLkUrl '/authorize-hse?redirect'), shown only when isHseAuthEnabled | none | `redirect` (flag without value) | disabled on keo.gov39.ru and school.yanao.ru; relevant only to the HSE lyceum instance | web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize `authorize (Wayback 2024-11-02, inline script)` `hseLkUrl` | uncertain |
| GET | `https://{host}/authorize-ms` | Microsoft / ADFS sign-in callback (redirect_uri of msAuthUrl), shown only when isMSAuthEnabled | none | `code` (response_type=code, response_mode=query) | msAuthUrl on keo.gov39.ru is '/adfs/oauth2/authorize?client_id=&redirect_uri=https://keo.gov39.ru/authorize-ms&response_type=code&response_mode=query' (disabled); on school.yanao.ru (2022) it pointed at a login.microsoftonline.com tenant | web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize `authorize (Wayback 2024-11-02, inline script)` `msAuthUrl` | uncertain |
| GET | `https://{host}/journal-esia-action/` | Госуслуги sign-in entry linked from the login page (esiaUrl); the same prefix later receives action.validate and action.check | none |  | redirect to ESIA; seen as esiaUrl on keo.gov39.ru (2024) and school.yanao.ru (2022, also saved locally as research/yanao_eljur_2022.html); EljurAuthUtil (2026) enters through /journal-esia-region-action?flow=v2 instead, so whether this entry still starts the flow is not known | web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize `authorize (Wayback 2024-11-02, inline script)` `esiaUrl` | uncertain |
| GET | `https://{host}/journal-esia-region-action/redirect` | Link returned by action.check; following it answers with Location carrying v_token | cookie | `region` (hex hash; also seen as region.<hash>); `token` | 302 Location /journal-app?v_token=<RS256 JWT> | BetterJournal/EljurAuthUtil `test/html_parser_test.dart` `extracts region hash from link` | uncertain |
| GET | `https://api.eljur.ru/api/auth` | Same login, parameters in the query string | none | `login`; `password`; `vendor`; `devkey`; `out_format=json` | result.token, result.expires; serezk4/EljurApi also calls it by GET without out_format | felpsyatina/1543.eljur.bot `eljur_api.py` `_UserBase.login` | legacy |
| GET | `https://api.eljur.ru/api/sendmessage` | Send a message with fields in the query | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `subject`; `text`; `users_to` (comma-separated ids) |  | samplec0de/eljur-bot `eljur.py` `send_message` | legacy |
| GET | `https://api.eljur.ru/api/sendreplymessage` | Reply with fields in the query | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `replyto`; `text` |  | samplec0de/eljur-bot `eljur.py` `reply_message` | legacy |
| GET | `https://edu.schools48.ru/api/getusersvendors` | Same exchange on the Lipetsk instance | query_token | `v_token`; `devkey`; `out_format` | result[0]: vendor_id, vendor, token, expires; the shared client also sends rings=1 and empty auth_token, student, vendor, days; Lipetsk: the region moved to «Госуслуги Моя школа» on 1 September 2026 (regional ministry, see discover_4); the ЭлЖур host still answered fofankochevnik/ElJurDnevnik (commit 2026-09-13) and its app was updated 7 Sept 2026, so it runs on as the old regional diary rather than being switched off | vsosh44/schools48bot `src/api/consts.py` `API_GET_VENDORS` | legacy |
| GET | `https://edu.schools48.ru/apiv3/getdiary` | Diary on the Lipetsk instance with the token from getusersvendors | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `student`; `days=20250901-20251230` (Ymd, Y.m.d or range Ymd-Ymd); `rings=1` (fofankochevnik/ElJurDnevnik sends true) | envelope {response:{state, error, result}}; result.students{id}.days{YYYYMMDD}: items{num}, items_extday[], alert, holiday_name; also called by fofankochevnik/ElJurDnevnik App.js; Lipetsk: the region moved to «Госуслуги Моя школа» on 1 September 2026 (regional ministry, see discover_4); the ЭлЖур host still answered fofankochevnik/ElJurDnevnik (commit 2026-09-13) and its app was updated 7 Sept 2026, so it runs on as the old regional diary rather than being switched off; fofankochevnik takes auth_token pasted by hand (the region is ЕСИА-only, so no password login) | vsosh44/schools48bot `src/api/consts.py` `API_GET_DIARY` | legacy |
| GET | `https://{vendor}.eljur.ru/apiv3/auth` | Login on the school host under apiv3 | none | `login`; `password`; `vendor`; `devkey`; `out_format=json` | response.result.token; Aefyr/myEljur calls the same URL (Volley GET) | yakuri354/EljurCLI `eljur_login.py` `add_user` | legacy |
| GET | `https://{vendor}.eljur.ru/apiv3/sendmessage` | Send a message with fields in the query on the school host | query_token | `devkey`; `vendor`; `out_format=json`; `auth_token`; `users_to`; `subject`; `text` | Volley GET; success is judged by the body containing 200 | Aefyr/myEljur `app/src/main/java/com/af/myeljur/Messages.java` `sendMessage` | legacy |
| GET | `https://{vendor}.eljur.ru/class.{class}/startdate.{date}/journal-schedule-action` | Public class timetable, no sign-in | none | `{class}`; `{date}` example: 2019-01-21 |  | felpsyatina/1543.eljur.bot `examples/schedule_parser.py` `get_current_schedule` | legacy |
| POST | `https://{vendor}.eljur.ru/journal-app/week.{n}` | Same page fetched by POST | cookie | `{n}` week offset, 0 is current; clients pass week * -1 |  | DAAMCS/EljurApi `JournalApi/journal.py` `get_journal` | legacy |
| GET | `https://{vendor}.eljur.ru/journal-schedule-action/u.{uid}` | Timetable page | cookie | `{uid}` |  | DAAMCS/EljurApi `JournalApi/timetable.py` `get_timetable` | legacy |
| POST | `https://{vendor}.eljur.ru/journal-student-resultmarks-action/u.{uid}` | Final marks, year chosen in the POST body | cookie | `{uid}`; body (application/x-www-form-urlencoded): `(raw data)` school year, e.g. 2020/2021, passed as requests data | HTML; div.page-empty when there are no marks | i3sey/API-Eljur-ru `Eljur/portfolio.py` `finalGrades` | legacy |

**Formats.**

- *Dates*: Ymd, Y.m.d, range Ymd-Ymd; day keys YYYYMMDD; mark date YYYY-MM-DD; message date YYYY-MM-DD HH:MM:SS; times HH:MM:SS
- *Ids*: numeric ids as strings; users comma-separated; vuid vendor_id#id; booleans true/yes/__yes/1
- *Pagination*: limit and page for getmessages, getboardnotices, getupdates; web mail uses limit and offset
- *Notes*: results keyed by student id under students

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| global | `api.eljur.ru` | documented API host (/api, v1.4); vendor= picks the school |
| per school | `{vendor}.eljur.ru` | one subdomain per school (markbook, rbli, 1543, demo, …); regional contracts use a region prefix plus the school's ИНН (smol1504021141, kursk1506084298); archived ones redirect to eljur.ru/archive?domain=<vendor>; from 1.07.2025 new schools get no free version (eljur.ru/news/2025_tariffs) |
| Нижегородская область | `edu.gounn.ru` | РГИС «Нижегородская образовательная платформа», ЭлЖур since 2020/21, ЕСИА-only since 11.03.2024, app ru.eljur.nnov updated 7.09.2026; still the region's diary in 2026/27; storage edu-storage-1.gounn.ru; eljurc |
| Калининградская область | `keo.gov39.ru` | «Электронный журнал — Калининград», ЕСИА-only from 1.11.2024, app ru.eljur.kaliningrad; superseded by «Госуслуги Моя школа» 1.09.2026; EljurAuthUtil example host |
| Калининградская область | `klgd.eljur.ru` | older city address, now redirects to keo.gov39.ru |
| Липецкая область | `edu.schools48.ru` | ГИС «Электронная школа», Dec 2024–Aug 2026, app ru.eljur.lipetsk; «Госуслуги Моя школа» from 1.09.2026; schools48bot, fofankochevnik/ElJurDnevnik |
| Курганская область | `eschool.gov45.ru` | «Электронная школа», since 2022 (300+ schools, eljur.ru/news/2022_300schoolsKurgan), ESIA by 09.2024, app ru.eljur.kurgan updated 18.09.2026; no move to ТОР found — current |
| Курская область | `edu.kurskobr.ru` | «Школы Курска», 2023–2026, app ru.eljur.kursk; «Госуслуги Моя школа» from 1.09.2026; school subdomains kursk<ИНН>.eljur.ru |
| Смоленская область | `dnevnik.admin-smolensk.ru` | «Электронный дневник Смоленской области», 2018–31.08.2026, app ru.eljur.smolensk; ТОР from 1.09.2026; school subdomains smol<ИНН>.eljur.ru |
| Ярославская область | `school.yarcloud.ru` | ГИС «Образование-76», 1.09.2024–31.08.2026 (eljur.ru/news/2024_yars, «Единый вход через ЕСИА»), app ru.eljur.yaroslavl; ТОР from 1.09.2026 |
| Астраханская область | `one.astrobl.ru` | РИС «Цифровое образование Астраханской области», 2024/25–2025/26, ЭлЖур per eljur.ru/news/2025_astr and the /journal-help-action path; ТОР from 1.09.2026 |
| Севастополь | `riso.sev.gov.ru` | РИС ЦОСС / РИСО, since 2022, app ru.eljur.sevastopol updated 24.07.2026; no move to ТОР found — current |
| Республика Крым | `edu.rk.gov.ru` | ГИС в сфере образования Республики Крым, since 1.01.2023 (eljur.ru/news/2023_crimea); older per-school *.eljur.ru; no move to ТОР found |
| Луганская Народная Республика | `lnrNNNN.eljur.ru` | РГИС «Террикон» on ЭлЖур (eljur.ru/news/2025_lnr), per-school hosts such as lnr0487, lnr0488, lnr0523; all schools 1.09.2025; replaced by ТОР from July 2026 |
| Ханты-Мансийский автономный округ — Югра | `cop.admhmao.ru` | ЦОП «Образование Югры», ЭлЖур since 2020 or earlier; colleges pilot 20.10.2025; no move to ТОР found |
| Ямало-Ненецкий автономный округ | `school.yanao.ru` | ЭлЖур «Образовательная платформа» 2021–2022 (Wayback 2022-03-14); the hostname now serves МЭШ / «Госуслуги Моя школа» — legacy, do not point an ЭлЖур client at it |
| Республика Ингушетия | `{vendor}.eljur.ru` | pilot in 10 schools from 1.12.2025 (eljur.ru/news/2025_ing); hostnames not published |
| Донецкая Народная Республика | `donschool47.eljur.ru` | per-school instances only, never regional; the republic moves to ТОР from 1.09.2026 |
| Иркутская область | `fec.eljur.ru` | single non-state school; sh28irk.eljur.ru archived |
| Красноярский край | `{vendor}.eljur.ru` | krai-wide from 2017, dropped over 2023/24–2024/25 — legacy |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| http://eljur.ru/api/ | fetched 2026-09-24; re-fetched by the verifier 2026-09-24 | official method list, types, envelope |
| https://github.com/BetterJournal/EljurAPI (open-api branch) | 2026-05-28 | 15 apiv3 paths, params, models |
| https://github.com/BetterJournal/EljurAPI | 2026-05-31 | zod models, envelope; SendReply sends replyto |
| npm:@betterjournal/eljur-api | 1.2.2 | same models |
| https://github.com/BetterJournal/EljurAuthUtil | 2026-08-31 | ESIA chain; redirect link and v_token Location in test/html_parser_test.dart |
| https://github.com/OpenEljur/openeljur-backend | 2026-04-04 | apiv3 usage, schools/mobileapp, upload |
| https://github.com/OpenEljur/typescript-sdk | 2026-04-04 | proxy client only |
| npm:@openeljur/typescript-sdk | 0.1.3 | proxy client only |
| https://github.com/samplec0de/eljur-bot | 2020-06-19 | /api methods and auth |
| https://github.com/felpsyatina/1543.eljur.bot | 2019-07-18 | /api methods, public schedule page |
| https://github.com/Aefyr/myEljur | 2017-06-11 | apiv3 on school host, including GET sendmessage |
| https://github.com/yakuri354/EljurCLI | 2020-02-19 | GET apiv3/auth, getrules, getschedule on markbook.eljur.ru |
| https://github.com/w1nsh/eljur-to-obsidian | 2026-09-16 | POST /api/auth on school host |
| https://github.com/Net2Fox/EJournalTelegramBot | 2025-12-06 | getschedule with class on {vendor}.eljur.ru/apiv3 |
| https://github.com/Net2Fox/EJournalAutomate | 2026-05-20 | JSON auth, messages |
| https://github.com/vsosh44/schools48bot | 2025-12-05 | Lipetsk host, v_token, apiv3/getdiary |
| https://github.com/zetrobt/eljur | 2023-11-27 | GET auth with JSON body |
| https://github.com/DAAMCS/EljurApi | 2024-03-30 archived | web pages |
| https://github.com/i3sey/EljurTelegramBot | 2025-08-26 | web routes |
| https://github.com/matytsyn/EljurAPI | 2026-02-12 | web routes |
| https://github.com/Zerguzik/EljurMessageParser | 2025-06-23 | web mail JSON |
| https://github.com/jakefish18/eljur-parser | 2024-01-12 | teacher pages |
| https://github.com/Evgenchick4434/EljurParser | 2026-09-07 | journal-app week |
| https://github.com/doctorixx/eljurc | 2025-05-04 | storage upload |
| https://github.com/linzer0/Bot-For-Eljur | 2017-01-03 | extended access |
| https://github.com/i3sey/API-Eljur-ru | 2022-06-09 | older copy of the i3sey web client: resultmarks by POST, getRecipientList spelling |
| https://github.com/serezk4/EljurApi | 2024-05-17 | Java client on api.eljur.ru/api: auth, getmarks, gethomework with class -> result.days |
| https://github.com/nnnixxi64/eljur | 2023-11-27 | identical copy of zetrobt/eljur |
| https://github.com/fofankochevnik/ElJurDnevnik | 2026-09-13 | Expo app calling edu.schools48.ru/apiv3/getdiary |
| https://github.com/search?type=repositories&q=eljur | fetched 2026-09-24 | five repositories not read before; only ElJurDnevnik calls Eljur |
| https://eljur.ru/api/ | re-read by the currency verifier 2026-09-24 | v1.4, /api only; Сообщения section is headings with anchors, not method names; ЕСИА named as a sign-in option; no apiv3 |
| https://eljur.ru/news | read 2026-09-24, newest item 15.12.2025 | regional projects: Ингушетия 2025, ХМАО СПО 2025, ЛНР «Террикон» 2025, Астрахань 2025, Липецк 2025, Ярославль 2024, Крым 2023, Сириус 2023, Курган 2022; integration with ФГИС «Моя школа» 2022 |
| https://eljur.ru/news/2025_tariffs | 2025-07-01 | no free version for schools registered after 1.07.2025 |
| https://eljur.ru/news/2025_ing | 2025-12-01 | Ингушетия pilot, 10 schools, no hostname |
| https://eljur.ru/news/2023_sirius | 2023-01-23 | Сириус deployment, no hostname |
| https://eljur.ru/login | read 2026-09-24 | SPA school search; /legacy/apps/diary and /legacy/apps/teacher pages; no API endpoints visible |
| https://eljur.ru/legacy/apps/diary/index.html | read 2026-09-24 | official app «ЭлЖур.Дневник» com.eljur.client / iOS 910733989 |
| https://eljur.ru/features/regions | read 2026-09-24 | ЕСИА sign-in support; software hosted on regional servers |
| https://web.archive.org/web/20241102110626id_/https://keo.gov39.ru/authorize | 2024-11-02 snapshot, local copy research/d4/keo.html | login page switches: isEsiaEnabled, esiaUrl /journal-esia-action/, ajaxauthorize, Google/MS/HSE sign-in paths |
| https://web.archive.org/web/20220314193105id_/https://school.yanao.ru/authorize | 2022-03-14 snapshot, local copy research/yanao_eljur_2022.html | same switches on the ЯНАО instance, MS tenant |
| https://github.com/BetterJournal/EljurAPI/issues | read 2026-09-24 | no issues |
| research/checkpoints discover_3..8, verify_0..2, verify_6, vendors-and-market | earlier runs of this research, 2026-09-24 | region-host pairs, app ids, dates of ESIA-only and of the moves to «Госуслуги Моя школа» |

**Refuted during verification** — rows an extractor proposed that no source carries, kept here so that nobody re-proposes them.

- GET `/api/getmessage` — The eljur.ru/api page (read three times on 2026-09-24) lists the Сообщения section only as headings with anchors (сообщения-сообщение-get, «Одно сообщение»); 'getmessage' is a translation of the heading, not a method name. The method the clients call for one message is getmessageinfo, which is kept.
- GET `/api/getmessagerecipients` — Translation of the heading «Получатели сообщений» (anchor сообщения-получатели-сообщений-get) on eljur.ru/api, not a method name; the method every client calls is getmessagereceivers, which is kept.
- POST `/api/replymessage` — Translation of the heading «Отправка ответа на сообщение» (anchor сообщения-ответ-на-сообщение-post) on eljur.ru/api, not a method name; the method the clients call is sendreplymessage (POST /apiv3/sendreplymessage, GET /api/sendreplymessage), which is kept.

**Caveats.**

- Only the official doc is authoritative, and its method pages are cut short; most parameters come from clients.
- Doc names getmessage, getmessagerecipients and replymessage differ from what clients call.
- betterjournal.ru/eljur-api answered 503; Statuxia-API-Eljur-ru answered 404.
- OpenEljur's /v1 routes are its own proxy, not Eljur, and are left out; its Eljur hosts come from environment variables, so the api.eljur.ru base on its rows is an assumption.
- vernuch/EljurTelegramDemo, Egortex/Eljurnal and JaxGitHubber/Eljur do not call Eljur; neither do Dmitrijai/Eljur-Pro, Dmitrijai/Eljur-OLD, Ramil-Akhmetov/eljur and RusimusPrime/ELJUR_98 (checked by the verifier).
- nnnixxi64/eljur is a byte-identical copy of zetrobt/eljur.
- Sourcegraph's index returned nothing for eljur.ru/apiv3, getusersvendors, journal-api-messages-action or journal-esia.
- Nothing here was run against a live host.
- Credential and devkey values found in public client code are deliberately not reproduced; only parameter names are listed.
- WebSearch budget was exhausted in this session; regional facts come from earlier discovery checkpoints of this same research (which cite their sources) and from eljur.ru pages read directly.
- The /api vs /apiv3 split is not a deprecation: /api is the only documented prefix, /apiv3 is what the official apps and all 2025–2026 clients use, and both are called by clients in 2026.
- Seven regions that ran ЭлЖур as their regional diary moved to «Госуслуги Моя школа» on 1 September 2026 (ЛНР from July 2026); their hosts may still answer as archives, but they are no longer the region's diary. Нижегородская, Курганская, Севастополь, Крым and ХМАО had no announced move.
- The ESIA entry changed between 2024 (/journal-esia-action/ on the login page) and 2026 (/journal-esia-region-action?flow=v2); the 2026 chain rests on one client (EljurAuthUtil, single commit 2026-08-31).
- school.nso.ru (Новосибирская «Электронная школа») is built by ООО «Иннотех», a joint venture of Веб-Мост and «Иннопрактика», and appears in ЭлЖур's news, but whether it runs the ЭлЖур codebase and API was not established, so it is not listed as an instance.
