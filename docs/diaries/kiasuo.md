# КИАСУО — «Электронный журнал и дневник», Красноярский край

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**The regional system of Красноярский край**, which replaced ЭлЖур in 2023/24 and serves every
school since 2024/25. Its diary is a PWA at `pwa.kiasuo.ru` and `dnevnik.kiasuo.ru`, and its JSON
API has lived at `diaryapi.kiasuo.ru/diary/api/*` since 3 February 2025; code older than that
names `dnevnik.kiasuo.ru/diary/api`.

**Signing in.** A person signs in through Госуслуги in a browser, and the PWA keeps a JWT
`accessToken` in local storage; the clients take the refresh token once, with a bookmarklet, and
from then on refresh at `/diary/refresh` with no browser at all. Children under fourteen have a
login-and-password entry of their own, which no client implements.

**Twelve routes, from three Telegram bots.** The most complete one talks to its own proxy, which
forwards to `diaryapi.kiasuo.ru` path for path. Rows known only from that proxy's route list are
marked uncertain.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| api | `https://diaryapi.kiasuo.ru` | The diary PWA's JSON API. All routes are under /diary/api/*, and the token refresh is at /diary/refresh. It became the API origin on 2025-02-03 (oddyamill/kiasuo commit 6781798 «chore: new api subdomain»). oddyamill/kiasuo has resolved homework file URLs against this host since 2025-02-07. |
| web | `https://dnevnik.kiasuo.ru` | Diary web front end (https://dnevnik.kiasuo.ru/diary). The registration bookmarklet runs on this page to extract the refresh token. It was also the API origin and the file host until February 2025 (see the legacy row). |
| legacy | `https://dnevnik.kiasuo.ru/diary` | The API origin before 2025-02-03: the same /api/* and /refresh paths. The archived TS client called https://dnevnik.kiasuo.ru/diary/api and /diary/refresh directly until 2024-05-31, and the oddyamill proxy's ORIGIN_DOMAIN was dnevnik.kiasuo.ru until 2025-02-03. The archived client's MEDIA_ENDPOINT for relative file URLs is https://dnevnik.kiasuo.ru. |
| web | `https://pwa.kiasuo.ru` | PWA. After the Госуслуги login it stores the bearer token in localStorage under the key 'token'. It is the only 'mobile app': there is no store app, and the vendor's guides say to install the PWA from Chrome. |
| other | `https://support.kiasuo.ru` | Support knowledge base. It has no API. |
| other | `https://kiasuo.oddya.ru/diary` | Third-party Cloudflare Worker proxy run by oddyamill/kiasuo. It forwards to diaryapi.kiasuo.ru with the same paths, but only for the patterns in wrangler.jsonc (refresh, user, recipients, study_periods, lesson_marks, student_marks, notices, conversations, schedule). /diary/pwa_logout is not in that list. Not official. On 2025-10-30 (commit 88a9ebf) the automatic Worker deploy was commented out, and on 2025-10-31 (cdca229) the kiasuo.oddya.ru DNS record was removed from terraform because the subdomain 'is now managed by cloudflare tunnel'. What serves kiasuo.oddya.ru now is not in the repository, so the wrangler.jsonc route list is the last known configuration, not a proven current one. |
| legacy | `https://kiasuo-proxy.oddya.ru/diary` | Third-party proxy used by the archived oddyamill-archive/kiasuo-telegram from 2024-05-31. Its routes were kiasuo-proxy.oddya.ru/diary/api/* and /diary/refresh, and the domain was renamed to kiasuo.oddya.ru on 2024-11-23. Not official. |
| web | `https://v4.kiasuo.ru` | «КИАСУО 4» main portal, mainly for school staff, with Госуслуги login. No client uses it and no API on it is known. |

**Signing in.**

*Госуслуги (ЕСИА) login in a browser, then the token is taken from the PWA* — token carried as header Authorization: Bearer <JWT accessToken>. Lifetime: About one hour (the kiasuo_diary_bot README says to re-authenticate every hour). Clients read exp from the JWT payload.. Refresh: POST https://diaryapi.kiasuo.ru/diary/refresh with refresh-token=<refreshToken>. The Go client sends it form-urlencoded; the archived TS client sends JSON {"refresh-token": ...}. The response is {accessToken, refreshToken}. The refresh token is rotated, so the new one must be stored. It is 32 hex characters..

1. Open https://pwa.kiasuo.ru/ (or https://dnevnik.kiasuo.ru/diary) in a real browser
2. Press «Войти через Госуслуги» and finish the ЕСИА login by hand. A verified Госуслуги account is required, and no client automates the ЕСИА redirect chain
3. After the redirect back, the PWA holds a JWT access token. ImpostorBoy228/kiasuo_diary_bot reads it through Selenium with window.localStorage.getItem('token')
4. oddyamill/kiasuo instead has the user run a bookmarklet on dnevnik.kiasuo.ru: import(document.scripts[0].src).then(i=>window.open('https://t.me/kiasuoRobot?start='+i.u().refreshToken)). This reads the refresh token from the PWA's own JS module state and passes it to the bot, which accepts it only if it is 32 hex characters (internal/commands/start.go) and then refreshes at once
5. Send the access token as Authorization: Bearer <accessToken> on every /diary/api/* call, together with ?id=<child id>

   A 401 from any API call means the access token has expired; clients refresh once and retry. A 401 from /diary/refresh means the refresh token is invalid.

*Starting from a refresh token (no browser needed after the first time)* — token carried as header Authorization: Bearer <accessToken>. Lifetime: The access token is short-lived (JWT exp). The refresh token lasts until it is used or revoked.. Refresh: Same endpoint. The returned refresh token replaces the old one..

1. Get a refresh token once, with the bookmarklet above
2. POST /diary/refresh with refresh-token=<32 hex> and Content-Type application/x-www-form-urlencoded, or a JSON body {"refresh-token":...}
3. Store the returned accessToken and refreshToken (the refresh token is rotated)
4. Before each call, decode the JWT payload's exp; if it has passed, refresh first

*Logout / revoke* — token carried as header Authorization: Bearer.

1. DELETE https://diaryapi.kiasuo.ru/diary/pwa_logout?id=<child id> with Authorization: Bearer <accessToken>

   Defined as RevokeToken in oddyamill/kiasuo internal/client/client.go. cmd/app/handler.go calls it when a registered user blocks the bot (the my_chat_member status is banned) and throws the error away. The request goes to kiasuo.oddya.ru/diary/pwa_logout, which is not in the worker's route list, so there is no evidence that it ever reached upstream.

*Login and password for a student under 14 (no client implements it)* — token carried as unknown (presumably header Authorization: Bearer <accessToken>, as for the ЕСИА login).

1. A parent logs in through Госуслуги and, in the «Доступ для учеников до 14 лет» section, creates a login and a password for the child (the /diary/api/user children[].can_create_login field matches this)
2. The child presses «Вход для детей до 14 лет» on the start page of dnevnik.kiasuo.ru / pwa.kiasuo.ru and enters that login and password
3. The endpoint this form posts to and what it returns are unknown; presumably it ends in the same accessToken/refreshToken pair

   Described by the vendor's support knowledge base (article titles «Вход для детей до 14 лет. Создание детской учётной записи на портале Госуслуг», «Как создать логин и пароль для ребёнка до 14 лет») and by 2025 guides. The article title suggests the child login may now be a Госуслуги child account rather than a КИАСУО-local password; the support site answered 503, so this could not be settled.

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Authorization` | Bearer <accessToken> | Needed on all /diary/api/* routes |
| `Accept` | application/json | The Go client and the Python bot set it on every request; the archived TS client does not, so it is probably not required |
| `Content-Type` | application/x-www-form-urlencoded or application/json | Only for POST /diary/refresh |

**Captcha and second factor.** The Госуслуги (ЕСИА) login is done interactively in a browser, with whatever 2FA or SMS ЕСИА asks for. No client automates it. Students under 14 have a separate login-and-password entry («Вход для детей до 14 лет»), and no client automates it either.

oddyamill/kiasuo sends all traffic through a Cloudflare Worker pinned to Russian edges (colo DME, KLD, KJA, LED, SVX), and optionally through a Yandex Cloud function relay that renames Authorization to Kiasuo-Authorization for that hop. This suggests diaryapi.kiasuo.ru cannot be relied on from non-Russian IP addresses. The Worker forwards only Accept, Authorization and Content-Type/Content-Length, and uses redirect: manual. The Worker-Authorization, Worker-Cache and Worker-Edge headers belong to the proxy, not to the platform. No client sets a special User-Agent. As of 2025–2026 the vendor's guides say parents and students aged 14 and over can log in only through Госуслуги with a verified account (НГС24, 2024-09-11: «Вход через нее возможен только через «Госуслуги»»). Children are attached to a parent by СНИЛС or by an invitation code (Профиль пользователя -> Приглашения on pwa.kiasuo.ru). No change to the token mechanics was found: the newest client (2026-05-18) still takes the JWT from localStorage 'token' after a manual Госуслуги login.

**Routes** — 12 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://diaryapi.kiasuo.ru/diary/api/lesson_marks/{study_period_id}` | Marks per subject for one study period, with averages | bearer | `{study_period_id}` id from /study_periods; `id` (Child id) | {lessons:[{id, subject, color, slots:[{lesson_date, mark:{value}, text, updated_at}], averages:{for_student:[string,number]\|null, for_class, predicted, sickness, with_reason, without_reason}}]}. That is the current client's shape; the archived client reads marks:[{lesson_date, mark, weight, remark, slot:{text, short_text}, updated_at}] instead of slots. The absence marks are «Б», «Н» and «У». | oddyamill/kiasuo `internal/client/client.go` `GetLessons` | current |
| GET | `https://diaryapi.kiasuo.ru/diary/api/recipients` | People who can receive messages: teachers and staff grouped by role, and classmates with their parents | bearer | `id` (Child id) | An object keyed by child id -> {staff: {<role>: {<full name>: <user id>}}, students: {<full name>: {parents, id}}} | oddyamill/kiasuo `internal/client/client.go` `GetRecipients` | current |
| GET | `https://diaryapi.kiasuo.ru/diary/api/schedule` | Weekly timetable with lessons, marks and homework | bearer | `year=2025` (ISO week-numbering year (Go uses time.ISOWeek; the Python bot sends the calendar year)); `week=36` (ISO week number); `id` (Child id) | {schedule:[{subject, teacher, color, lesson_date (YYYY-MM-DD), lesson_number, from, to, theme, slots/marks, created_homework_id, homework_to_check_ids:[int]}], homeworks:[{id, lesson_date, check_at, text, files:[{url (relative), title}], links:[{url, title}]}]}. The text «Без задания» means there is no homework, and «не указана» means no lesson theme was given. Still called directly at https://diaryapi.kiasuo.ru/diary/api/schedule by ImpostorBoy228/kiasuo_diary_bot (last commit 2026-05-18), the newest evidence that this route is live. | oddyamill/kiasuo `internal/client/client.go` `GetSchedule` | current |
| GET | `https://diaryapi.kiasuo.ru/diary/api/study_periods` | The current year's study periods (quarters or half-years) | bearer | `id` (Child id) | Array of {id, text, from, to}. from and to are YYYY-MM-DD. id is the path parameter for /lesson_marks/{id}. | oddyamill/kiasuo `internal/client/client.go` `GetStudyPeriods` | current |
| GET | `https://diaryapi.kiasuo.ru/diary/api/user` | The current account and the children it can see | bearer | `id?` (Child (student) id. The Go client's appendID always adds it (0 before a child is chosen); the archived TS client adds it only once studentId is set) | {id, username, parent, vk_id, unread_notices_count, children:[{id, first_name, last_name, middle_name, school_class, age, can_create_login, username}]}. children[].id is the value passed as ?id= on every other call. | oddyamill/kiasuo `internal/client/client.go` `GetUser` | current |
| POST | `https://diaryapi.kiasuo.ru/diary/refresh` | Exchange a refresh token for a new access token and a new (rotated) refresh token | none | body (application/x-www-form-urlencoded or application/json): `refresh-token` 32 hex characters. Sent as the form field refresh-token=... or the JSON key "refresh-token" | {accessToken, refreshToken}. accessToken is a JWT with exp. 401 means the refresh token is invalid. | oddyamill/kiasuo `internal/client/http.go` `refreshToken` | current |
| GET | `https://diaryapi.kiasuo.ru/diary/api/conversations` | Message conversations. Known only because the proxy forwards the path (pattern conversations*); no client code calls it | bearer |  | Shape unknown | oddyamill/kiasuo `wrangler.jsonc` `routes` | uncertain |
| GET | `https://diaryapi.kiasuo.ru/diary/api/notices` | Notices (announcements). Known only because the proxy forwards the path (pattern notices*); no client code calls it | bearer |  | Shape unknown. /user returns unread_notices_count. | oddyamill/kiasuo `wrangler.jsonc` `routes` | uncertain |
| GET | `https://diaryapi.kiasuo.ru/diary/api/school_classes` | The school classes the student has been in, one per study year | bearer | `id` (Child id) | Array of {id, name, ou, study_year}. The client wraps ids of 16 or more digits in quotes before JSON.parse so they keep their precision. The current proxy's route list does not include this path. Status is uncertain rather than legacy: no replacement route is known; it simply stopped being called by the one client that used it. | oddyamill-archive/kiasuo-telegram `libs/client/src/KiasuoClient.ts` `getSchoolClasses` | uncertain |
| GET | `https://diaryapi.kiasuo.ru/diary/api/student_marks/{school_class_id}` | Final marks (for the year or a period) for one of the student's school classes | bearer | `{school_class_id}` id from /school_classes. Treated as a string; it can be a very large integer; `id` (Child id) | {lessons:[{id, subject, color, marks:{<standard_mark_type id>: <value>}}], standard_mark_type:[{id, text, short_text, order_by}]}. Only the archived client calls it; the current proxy still forwards the path. Status is uncertain rather than legacy: no replacement route is known, and the proxy's route list (last changed 2025-10) still forwards it, which suggests it was still in use. | oddyamill-archive/kiasuo-telegram `libs/client/src/KiasuoClient.ts` `getStudentMarks` | uncertain |
| DELETE | `https://diaryapi.kiasuo.ru/diary/pwa_logout` | End the PWA session (logout) | bearer | `id?` (Child id, appended by the client's helper) | The client expects no body and handles a 204. The bot calls it (cmd/app/handler.go) when a registered user blocks the bot, and ignores the result. The proxy's route list does not include this path, so no evidence shows the upstream answer. | oddyamill/kiasuo `internal/client/routes.go` `revokeURL / Client.RevokeToken` | uncertain |
| GET | `https://diaryapi.kiasuo.ru/{file_url}` | Download a homework attachment. files[].url from /schedule is a relative path | none | `{file_url}` homeworks[].files[].url exactly as returned | A binary file. The current client has put https://diaryapi.kiasuo.ru in front of the path since 2025-02-07 (commit d571ea6), and used https://dnevnik.kiasuo.ru before that. The archived 2024 client uses https://dnevnik.kiasuo.ru. | oddyamill/kiasuo `internal/client/structures.go` `File.String / PublicUrl` | uncertain |

**Formats.**

- *Dates*: lesson_date, from, to and check_at are YYYY-MM-DD. updated_at is an ISO 8601 timestamp. The schedule is requested by ISO week-numbering year plus ISO week number.
- *Ids*: Users, children, study periods and homework have integer ids. School class ids can be integers of 16 or more digits; the archived client types them as strings and quotes them before JSON.parse.
- *Pagination*: None seen. The schedule is paged by ISO week and marks by study period.
- *Notes*: Every /diary/api call carries ?id=<child id>, taken from children[].id in /user. Averages are either a [display string, number] pair or null. The absence marks are «Б», «Н» and «У».

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Красноярский край | `diaryapi.kiasuo.ru` | The diary's JSON API for the whole krai, since February 2025 |
| Красноярский край | `dnevnik.kiasuo.ru` | Diary web front end («Дневник ГИС КИАСУО Красноярского края»). It was the API host until February 2025 |
| Красноярский край | `pwa.kiasuo.ru` | PWA front end; installed to the home screen from Chrome, and there is no store app |
| Красноярский край | `v4.kiasuo.ru` | «КИАСУО 4» main portal (staff), with Госуслуги login |
| Красноярский край | `{school_code}.kiasuo.ru` | Staff journal for each school, for example https://050760.kiasuo.ru/auth/login and 421690.kiasuo.ru. No client uses it. |
| Красноярский край | `spt.kiasuo.ru` | «СПТ КИАСУО» (socio-psychological testing). It is not part of the diary and has no known API |
| Красноярский край | `kiasuo.ru` | Official site of the system. It has no API |
| Красноярский край | `support.kiasuo.ru` | Support knowledge base; answered 503 |
| Красноярский край | `forum.kiasuo.ru` | Developer and user forum («Разработка КИАСУО. Форум.»); answered 503 |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/oddyamill/kiasuo | 2026-02-04 (255 commits, full history now fetched) | Go Telegram bot client: routes.go, http.go, client.go and structures.go (user, recipients, study_periods, lesson_marks, schedule, refresh, pwa_logout) and the JWT exp check. The caller of RevokeToken is in cmd/app/handler.go. Also the Cloudflare Worker proxy (cmd/worker/server) and the Yandex function relay (cmd/yandex), with the list of forwarded routes in wrangler.jsonc (student_marks, notices, conversations), and the registration bookmarklet (scripts/registration.js). The git history dates the move from dnevnik.kiasuo.ru to diaryapi.kiasuo.ru (2025-02-03 for the API, 2025-02-07 for files) and shows how the route list changed. Re-read for currency: the Worker auto-deploy was disabled on 2025-10-30 and kiasuo.oddya.ru moved to a Cloudflare tunnel on 2025-10-31; the Go client's ApiUrl and the worker/relay ORIGIN_DOMAIN (diaryapi.kiasuo.ru) are unchanged at HEAD (2026-02-04). |
| https://github.com/oddyamill-archive/kiasuo-telegram | 2024-08-15 (archived; full history now fetched) | TypeScript client in libs/client: KiasuoClient (schedule, study_periods, lesson_marks, student_marks, recipients, school_classes, user), the JSON refresh body, the response interfaces, quoting of large ids before JSON.parse, and the media host dnevnik.kiasuo.ru. The history shows that it called https://dnevnik.kiasuo.ru/diary/api and /diary/refresh directly until 2024-05-31. |
| https://github.com/ImpostorBoy228/kiasuo_diary_bot | 2026-05-18 | Python bot: a direct GET https://diaryapi.kiasuo.ru/diary/api/schedule with id, week and year, and Accept: application/json; a Selenium Госуслуги login on pwa.kiasuo.ru that reads the token from localStorage 'token'; the token expires about hourly |
| https://github.com/lalalpldpdpdadal-dotcom/kiasuo-phish | 2026-04-19 | Nothing: the repository contains only a one-line README. |
| https://github.com/Savelii123/KIASUO_for_English | 2025-04-25 | Nothing: a static index.html with no fetch calls and no kiasuo hosts. |
| https://registry.npmjs.org/kiasuo and https://registry.npmjs.org/kiasuo-api |  | Both packages (2023-11 to 2024-01, probably the early oddyamill client) are unpublished, so there are no tarballs to read. Neither name exists on PyPI. |
| https://github.com/search?type=repositories&q=kiasuo |  | Six repositories. Three are API clients; the other three (kiasuo-phish and two copies of KIASUO_for_English) have no code. A search for diaryapi found only unrelated projects. |
| https://sh76-krasnoyarsk-r04.gosweb.gosuslugi.ru/roditelyam-i-uchenikam/elektronnyy-dnevnik-kiasuo/ |  | Entry point dnevnik.kiasuo.ru/diary, the requirement for a Госуслуги account, and the per-school staff host 050760.kiasuo.ru/auth/login |
| https://sourcegraph.com/.api/search/stream (kiasuo.ru, diaryapi.kiasuo, dnevnik.kiasuo, pwa_logout, study_periods lesson_marks) |  | No further clients (only unrelated hits such as zmap/zdns) |
| WebSearch "diaryapi.kiasuo.ru", "kiasuo дневник api github", and "kiasuo" api github lesson_marks/study_periods |  | Only support.kiasuo.ru knowledge-base pages (which answer 503) and the front-end hosts. No further clients. |
| https://teletype.in/@faqi/KIASUO-Krasnoyarskogo-kraya-Vhod | 2025-05-04 | Hosts v4.kiasuo.ru, dnevnik.kiasuo.ru, pwa.kiasuo.ru, per-school 050760.kiasuo.ru and 421690.kiasuo.ru; login by Госуслуги only for parents and students 14+, simplified accounts or invitation codes for students under 14; the PWA is installed from the browser |
| https://ngs24.ru/text/education/2024/09/11/74076050/ | 2024-09-11 | КИАСУО replaced ЭлЖур from the start of the 2023/24 school year; login only through Госуслуги; PWA loading problems |
| https://tvknews.ru/publications/news/75744/ | 2023-09-28 | The reason for the switch (only state platforms may process students' personal data); students 14+ need Госуслуги; built to the order of the krai's ministry of education |
| https://foedu.ru/krasnoyarskiy-kray.html |  | Hosts pwa.kiasuo.ru, dnevnik.kiasuo.ru, v4.kiasuo.ru and spt.kiasuo.ru (СПТ) |
| https://sh81-krasnoyarsk-r04.gosweb.gosuslugi.ru/elektronnaya-informatsionnaya-sreda/fgis-moya-shkola/ |  | A Krasnoyarsk school presents ФГИС «Моя школа» (myschool.edu.ru) as a content aggregator and keeps «Электронный дневник КИАСУО» as its diary; no transition date |
| WebSearch: ФГИС «Моя школа» с 1 сентября 2026 … Красноярский край КИАСУО; КИАСУО новости 2026; КИАСУО мобильное приложение; КИАСУО до 14 лет |  | The September 2026 pilot list found (Курская, Ростовская, Свердловская, Челябинская области, Краснодарский край, КБР, ДНР, ЛНР) does not name Красноярский край; outage reports for kiasuo.ru in January and March 2026; no store app; the under-14 login |
| https://support.kiasuo.ru and http://forum.kiasuo.ru/t/elektronnyj-zhurnal-i-dnevnik/80/280 |  | Both answered HTTP 503 to WebFetch; only their titles, seen in search results, were used |

**Caveats.**

- Everything here was reverse-engineered from third-party Telegram bots. There is no official API documentation.
- Only three clients exist. The most complete one (oddyamill/kiasuo) talks to its own proxy at kiasuo.oddya.ru/diary, which maps one to one onto diaryapi.kiasuo.ru/diary for the paths in its route list.
- The API moved from dnevnik.kiasuo.ru/diary to diaryapi.kiasuo.ru/diary in February 2025. Code written before then, including the archived client, names dnevnik.kiasuo.ru.
- student_marks and school_classes are called only by the client archived in 2024. The current proxy still forwards student_marks but not school_classes.
- notices and conversations are known only from the proxy's route list; their parameters and response shapes are unknown. pwa_logout is called when a user blocks the bot, but its result is thrown away and the proxy's route list does not include the path, so nothing confirms it works.
- No client automates the ЕСИА/Госуслуги login. Tokens are taken from a browser that is already logged in, so the redirect chain and the PWA's login endpoints are unknown.
- The file hosts differ by date: dnevnik.kiasuo.ru before February 2025 and diaryapi.kiasuo.ru after it. Which one serves them today has not been checked against a live response.
- support.kiasuo.ru answered 503 and could not be read.
- The proxy setup (pinned to Russian Cloudflare edges, with a Yandex Cloud relay) suggests the API may be blocked or unreliable from outside Russia.
- ФГИС «Моя школа»: no evidence that Красноярский край moves its diary off КИАСУО on 1 September 2026. The 2026 pilot list found does not name the krai, and local school pages treat myschool.edu.ru as an aggregator beside the КИАСУО diary. This could change, and was not confirmed from an official regional source.
- The proxy that the most complete client talks to (kiasuo.oddya.ru) has been served through a Cloudflare tunnel since 2025-10-31, and the Worker is no longer auto-deployed, so its wrangler.jsonc route list is a last-known configuration rather than proven current evidence for notices, conversations and student_marks.
- The under-14 login-and-password entry exists according to the vendor, but no client implements it and its endpoint is unknown.
- The newest dated evidence that the API is live is ImpostorBoy228/kiasuo_diary_bot (2026-05-18, /diary/api/schedule only) and 2026 outage reports; nothing is dated after the start of the 2026/27 school year.
