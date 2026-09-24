# «Петербургское образование» — Санкт-Петербург

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**The one diary this project already talks to.** `server/app/providers/petersburg/` is a client
of `dnevnik2.petersburgedu.ru`, and it was written from the same open clients this page is
built from. It calls exactly the nine routes that more than one independent client agrees on:
the sign-in, the list of children, the periods, the studied subjects, the teachers, the marks
table, the lessons with homework, the timetable and the turnstile log. Everything else below is
seen in one client only, and is marked so.

**Signing in.** The service takes an email and a password at one endpoint and hands back a
session that lives in a cookie called `X-JWT-Token`, refreshed on the way past most answers.
An account created through Госуслуги has no password there at all, so no client signs it in:
each of them asks the person to copy the cookie out of a browser. That is the gap #121 is
about, and nothing on this page closes it.

**What the evidence is.** Thirty-seven sources, the newest a Telegram bot from July 2026, none
of them official: the operator publishes no API. The older `petersburgedu.ru` portal and its
form login are listed as legacy, because the clients that used them stopped in 2020.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| api | `https://dnevnik2.petersburgedu.ru` | current JSON API under /api/...; also the web SPA (/login, /students/my, /estimate, /journal/estimate) |
| api | `https://dnevnik2.petersburgedu.ru/api` | deknowny client's api_endpoint prefix |
| other | `https://dnevnik2.petersburgedu.ru/fps` | food-payment (питание) proxy: /fps/api/netrika/mobile/v1/accounts/ |
| legacy | `https://petersburgedu.ru` | old portal (2014-2020 clients): form login /user/auth/login/ and HTML pages under /dnevnik/...; not a ФГИС «Моя школа» host, although SolomonNumb1/fgis-dnevnik-desktop maps region 78 to it |
| legacy | `http://petersburgedu.ru` | plain-http base of the any-balance provider (dukei ab-education-petersburgedu v2, 2014): GET/POST user/auth/login/ and dnevnik/lessons/?period= |
| other | `https://school.glolime.ru` | Glolime school-meal payment service, reached with the signed frameData from /api/journal/fps/list; HTML in windows-1251 |
| legacy | `https://dnevnik.petersburgedu.ru` | listed as a known host by the task; no client or document read here uses it; also absent from infoculture govdomains region-78 lists (2022-10-08); unverified |

**Signing in.**

*Email + password (current)* — token carried as cookie X-JWT-Token. Lifetime: not documented; newtover's README says the cookie does not expire ('Кука не протухает'); kewldan refreshes it after 1 day; the server re-issues it on use. Refresh: Set-Cookie X-JWT-Token on ordinary responses; otherwise log in again.

1. POST https://dnevnik2.petersburgedu.ru/api/user/auth/login with JSON {"type":"email","login":<email>,"activation_code":null,"password":<password>,"_isEmpty":false}; the local client notes that sending fewer fields gets a 400
2. 400 (some clients also treat 401/403 as the same) = wrong credentials; newtover treats any 4xx as wrong login or password; spb_dnevnik's error text mentions too many attempts, i.e. rate limiting
3. Session token arrives twice: in the body as data.token (a JWT, eyJ...) and as Set-Cookie X-JWT-Token; the local client prefers the cookie because the two have been seen to differ, while spb_dnevnik and petersburgedu_wrap use the body token
4. Every later call sends the cookie X-JWT-Token=<token> (a cookie, not a header, despite the name)
5. Responses to most calls carry a refreshed Set-Cookie X-JWT-Token, sometimes twice under different Path values; clients store the newest value (kewldan and the local client both do this)
6. Expiry shows up as 401/403 or as a 200 HTML login page instead of JSON; kewldan logs in again with the stored password when the token is older than one day

   Current as of 2026: kewldan/TelegramDnevnik (2026-07-21) still uses exactly this flow, and RomyxR (2026-04) documents no change. The vendor's own pages could not be read (503), so a 2025-2026 change on the vendor's side (ЕСИА-only sign-in, a captcha) cannot be ruled out.

*Token copied from a browser (Госуслуги/ЕСИА accounts)* — token carried as cookie X-JWT-Token.

1. Sign in on https://dnevnik2.petersburgedu.ru/login in a browser, including through ЕСИА (Госуслуги)
2. Copy the cookie X-JWT-Token (domain dnevnik2.petersburgedu.ru, path /)
3. Pass it to the client (spb_dnevnik login_token, petersburgedu_wrap login_by_token, newtover cookies.json)

   No client automates the dnevnik2 ЕСИА redirect chain. The swagger says the login 'type' can also be "eisa" (sic), without documenting it. The /v3/auth/esia/login gateway, which one multi-region ФГИС client assigns to petersburgedu.ru, is not this platform's ЕСИА entry; that row was refuted.

*Legacy petersburgedu.ru form login (old portal)* — token carried as cookie (portal session cookies, names not recorded).

1. Optional GET http://petersburgedu.ru/user/auth/login/ (dukei) or https://petersburgedu.ru/user/auth/login/get-form/1/ (yar229) to prime cookies
2. POST https://petersburgedu.ru/user/auth/login/ as application/x-www-form-urlencoded with Login, Password, doLogin=1 (plus RememberMe=0 and authsubmit=Войти in some clients), with X-Requested-With: XMLHttpRequest and Referer/Origin https://petersburgedu.ru
3. The answer is JSON {status:'ok'} or {errors:[{text:'Е-mail или пароль неверный'}]}; the session lives in cookies
4. HTML pages under /dnevnik/... are scraped

   Legacy, 2014-2020 clients. bargool's ЕСИА variant drove PhantomJS: it clicked .esia-login and filled #mobileOrEmail and #password on the Госуслуги page.

*Glolime food-payment (питание) signed link* — token carried as query string (token + zsign signature).

1. GET /api/journal/fps/list?p_limit&p_page&p_education=<education_id> with the X-JWT-Token cookie
2. Take data.iframe[].frameData: a signed query string with merch_gmt, merch_url, nonce, suid, timestamp, token and zsign
3. Append it to https://school.glolime.ru/api/netrika/account/ (balances), /api/netrika/userinfo/ (account number) or /api/netrika/transfer/create/ (transfer)

   Only a 2023 client does this; its status is uncertain. The newer alternative (kewldan, 2026) is POST https://dnevnik2.petersburgedu.ru/fps/api/netrika/mobile/v1/accounts/ with form RegId=<child hash_uid> and the X-JWT-Token cookie

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Cookie` | X-JWT-Token=<token> | session; the only credential |
| `User-Agent` | a desktop browser UA (Chrome/Firefox) | the local client says the upstream has answered differently to non-browsers; every client sends a browser UA, and deknowny suppresses aiohttp's default UA |
| `Accept` | application/json (or application/json, text/plain, */*) | JSON answers; all clients send it |
| `Content-Type` | application/json | login body is JSON; newtover sends it with Content-Type: text/plain (session header from headers.json overrides requests' json default) and it is accepted, so not strictly required |
| `X-Requested-With` | XMLHttpRequest | sent by newtover and the old-portal clients; optional for dnevnik2 |
| `Referer` | https://dnevnik2.petersburgedu.ru/login \| /students/my \| /estimate | newtover imitates the SPA per path; others do not, so not strictly required |

**Captcha and second factor.** No client handles a captcha or SMS on dnevnik2. ЕСИА accounts cannot use the email login, so their token is copied from a browser. spb_dnevnik's error text suggests throttling after repeated logins. The 2026 clients (kewldan 2026-07, RomyxR 2026-04) do not handle a captcha or 2FA either.

There are no bearer headers and no OAuth client id; the whole session is the X-JWT-Token cookie. The login field is 'login' in every working client, while the swagger calls it 'email'. deknowny sends the login as GET with a JSON body, which no other client does.

**Routes** — 35 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://dnevnik2.petersburgedu.ru/api/group/group/get-list-period` | Academic periods (quarters/half-years) of a class | cookie | `p_limit=500`; `p_page=1`; `p_group_ids[]=<group_id>` | data.items[]: identity.id, name, date_from, date_to (dd.mm.yyyy), education_period.id (kewldan skips education_period.id 23 - likely the whole-year period) \| also used by: kewldan/TelegramDnevnik, newtover/dnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.periods` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/acs/list` | Turnstile (СКУД) entries/exits of a pupil | cookie | `p_limit=30`; `p_page=1`; `p_education=<education_id>` (singular, no []) | data.items[]: identity.id, direction ('input'/'output'), datetime \| also used by: kewldan/TelegramDnevnik | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.attendance` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/estimate/table` | Marks (estimates) for a date range; also absences (code 30000), lateness (30001), remarks | cookie | `p_limit=1000`; `p_page=1`; `p_date_from=15.09.2026` (dd.mm.yyyy); `p_date_to=15.10.2026` (dd.mm.yyyy); `p_educations[]=<education_id>` | data.total_pages; data.items[]: id, education_id, lesson_id, subject_id, subject_name, date (dd.mm.yyyy), estimate_value_code, estimate_value_name ('Замечание' for remark), estimate_type_code ('30000' absence, '30001' late), estimate_type_name, estimate_comment \| also used by: RomyxR/dnevnik2_spb_api, Zeusina/petersbugredu-wrap-python (pypi petersburgedu-wrap 0.0.1), deknowny/dnevnik2-petersburgedu-python-client (package dnev2spb 0.1.0a0, not on PyPI), kewldan/TelegramDnevnik, newtover/dnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.marks` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/group/related-group-list` | Classes visible to the account | cookie | `p_page`; `p_jurisdictions[]`; `p_institutions[]` | also used by: RomyxR/dnevnik2_spb_api, newtover/dnevnik (Referer /estimate) \| still listed by RomyxR/dnevnik2_spb_api (2026-04-21) | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_group_related_group_list` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/institution/related-institution-list` | Schools visible to the account | cookie | `p_page`; `p_jurisdictions[]` | also used by: RomyxR/dnevnik2_spb_api \| still listed by RomyxR/dnevnik2_spb_api (2026-04-21) | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_institution_related_institution_list` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/institution/related-jurisdiction-list` | Districts (районы) visible to the account | cookie | `p_page` | also used by: RomyxR/dnevnik2_spb_api \| still listed by RomyxR/dnevnik2_spb_api (2026-04-21) | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_institution_related_jurisdiction_list` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/lesson/list-by-education` | Lessons with homework and topics for a date range | cookie | `p_limit=500`; `p_page=1`; `p_datetime_from=15.09.2026+00:00:00` (date + '+' + wall time here; Zeusina/spb_dnevnik/RomyxR send 'dd.mm.yyyy HH:MM:SS'); `p_datetime_to=21.09.2026+00:00:00`; `p_educations[]=<education_id>` | data.total_pages; data.items[]: identity{id,uid}, number, datetime_from/datetime_to ('dd.mm.yyyy HH:MM:SS'), subject_id, subject_name, content_name (topic), content_description, content_additional_material, tasks[]{task_name, task_code, task_kind_code, task_kind_name, files[]}, estimates[]{estimate_type_code, estimate_type_name, estimate_value_code, estimate_value_name, estimate_comment} (shape from petersburgedu_wrap; local mapper accepts several alternative names) \| also used by: RomyxR/dnevnik2_spb_api, Zeusina/petersbugredu-wrap-python (pypi petersburgedu-wrap 0.0.1), kewldan/TelegramDnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.lessons` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/person/get-classroom-teacher/{group_id}` | Class teacher (классный руководитель) of a group | cookie | `{group_id}` class id | also used by: RomyxR/dnevnik2_spb_api \| still listed by RomyxR/dnevnik2_spb_api (2026-04-21) | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_person_get_classroom_teacher` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/person/related-child-list` | Children (pupils) linked to the account | cookie | `p_page=1` | data{items[], before, current, last, next, total_items, total_pages}, debug[], messages[], validations[]; items[]: identity.id, firstname, surname, middlename, hash_uid, action_payload{can_apply_for_distance, can_print}, educations[]{education_id, group_id, group_name, institution_id, institution_name, jurisdiction_id, jurisdiction_name, distance_education, distance_education_updated_at, is_active, parent_email, parent_firstname, parent_middlename, parent_surname, push_subscribe}; 401 = check token \| also used by: RomyxR/dnevnik2_spb_api, Zeusina/petersbugredu-wrap-python (pypi petersburgedu-wrap 0.0.1), deknowny/dnevnik2-petersburgedu-python-client (package dnev2spb 0.1.0a0, not on PyPI), kewldan/TelegramDnevnik, mikhaillav/dnevnik2_docs, newtover/dnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.children` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/schedule/list-by-education` | Timetable for a date range | cookie | `p_limit=500`; `p_page=1`; `p_datetime_from=15.09.2026` (plain dd.mm.yyyy here (spb_dnevnik documents DD.MM.YYYY HH:MM:SS)); `p_datetime_to=21.09.2026`; `p_educations[]=<education_id>` | data.items[] (shape not established) \| also used by: kewldan/TelegramDnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.schedule` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/subject/list-studied` | Subjects studied by a class in a period | cookie | `p_limit=500`; `p_page=1`; `p_groups[]=<group_id>`; `p_periods[]=<period_id>`; `p_educations[]` ((seen in npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2))) | data.items[]: subject_id / identity.id, subject_name\|name\|title \| also used by: RomyxR/dnevnik2_spb_api, kewldan/TelegramDnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.subjects` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/teacher/list` | Teachers of a pupil's education | cookie | `p_page=1`; `p_limit=500`; `p_educations[]=<education_id>` | data.items[]: identity.id, surname, firstname, middlename, position_name, subjects[]{subject_name} \| also used by: RomyxR/dnevnik2_spb_api, Zeusina/petersbugredu-wrap-python (pypi petersburgedu-wrap 0.0.1), kewldan/TelegramDnevnik, npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.teachers` | current |
| POST | `https://dnevnik2.petersburgedu.ru/api/user/auth/login` | Email+password sign-in; returns session token (body data.token and Set-Cookie X-JWT-Token) | none | body (application/json (newtover's session-wide Content-Type: text/plain stays on its JSON login and is accepted)): `type` "email" (swagger also names "eisa" = ЕСИА), `login` email (swagger calls the field email), `password`, `activation_code` null, `_isEmpty` false; omitting fields gets 400 | {data:{token}}; token also in Set-Cookie X-JWT-Token (cookie preferred, the two have been seen to differ); 400/401/403 = bad credentials; 200 HTML = unexpected \| also used by: Zeusina/petersbugredu-wrap-python (pypi petersburgedu-wrap 0.0.1), kewldan/TelegramDnevnik, mikhaillav/dnevnik2_docs, newtover/dnevnik (Referer /login), npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2). spb_dnevnik and petersburgedu_wrap take the token from body data.token, not from the cookie; newtover keeps the cookie jar; petersburgedu_wrap treats only 400 as bad credentials, newtover any 4xx \| still used unchanged by kewldan/TelegramDnevnik (2026-07-21) | lumenpearson/lessons (local) `server/app/providers/petersburg/client.py` `PetersburgClient.login` | current |
| POST | `https://dnevnik2.petersburgedu.ru/fps/api/netrika/mobile/v1/accounts/` | School-meal (питание) accounts and balances of a pupil via the Netrika/Glolime food-payment system proxied under /fps | cookie | body (application/x-www-form-urlencoded): `RegId` child's hash_uid from related-child-list | accounts[] (read from data.accounts or top-level accounts): id, accounttypename, sum, customer_name | kewldan/TelegramDnevnik `api/src/lib/dnevnik_api/client.py` `DnevnikClient.get_accounts` | current |
| GET | `https://dnevnik2.petersburgedu.ru/api/cms/banner/list` | Information banners/resources shown on the site | cookie | `p_page` | status uncertain: attested only by npm spb_dnevnik 1.4.1 (2023-04-23); no 2024-2026 client repeats it | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_cms_banner_list` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/announcement/list-open` | Open announcements (оповещения) | cookie | `p_page` | status uncertain: attested only by npm spb_dnevnik 1.4.1 (2023-04-23); no 2024-2026 client repeats it | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_announcement_list_open` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/fps/list` | Food payment system (питание) iframe data for a pupil | cookie | `p_limit`; `p_page`; `p_education=<education_id>` | data.iframe[].frameData - signed query string (merch_gmt, merch_url, nonce, suid, timestamp, token, zsign) for school.glolime.ru \| status uncertain: attested only by npm spb_dnevnik 1.4.1 (2023-04-23); no 2024-2026 client repeats it; kewldan (2026) uses POST /fps/api/netrika/mobile/v1/accounts/ instead | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_fps_list` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/journal/person/related-person-list` | Persons (pupils) visible to the account filtered by district/school/class | cookie | `p_page`; `p_jurisdictions[]`; `p_institutions[]`; `p_groups[]` | status uncertain: attested only by npm spb_dnevnik 1.4.1 (2023-04-23); no 2024-2026 client repeats it | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_journal_person_related_person_list` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/user/auth/login` | Login - this client sends it as GET with a JSON body (the other clients use POST) | none | body (application/json): `login`, `password`, `type` email, `_isEmpty` false | data.token; X-JWT-Token cookie kept by aiohttp session jar | deknowny/dnevnik2-petersburgedu-python-client (package dnev2spb 0.1.0a0, not on PyPI) `dnev2spb/client.py` `APIAuthedClient.new` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/user/permission/get` | Current user's permissions | cookie |  | status uncertain: attested only by npm spb_dnevnik 1.4.1 (2023-04-23); no 2024-2026 client repeats it | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_user_permission_get` | uncertain |
| GET | `https://dnevnik2.petersburgedu.ru/api/{path}/` | Generic GET passthrough: any api/ path with a query string | cookie | `{path}` any path under api/ | URL is built as https://dnevnik2.petersburgedu.ru/${api}/${query}, so a slash always precedes the query string; returns response.data.data. The package's example calls api/journal/teacher/list with p_page=1&p_educations=<id> (no []) | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `index.js` `get_api` | uncertain |
| GET | `https://school.glolime.ru/acquiring/searchbypaymentnumber/acquier/` | Glolime: payment (top-up) page URL, built not fetched | none | `type=1` (1 hot meals, 2 buffet); `usernumber` |  | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `glolime/index.js` `glolime.getAddMoneyString` | uncertain |
| GET | `https://school.glolime.ru/api/netrika/account/` | Glolime: meal balances table (hot meals, buffet, card reissue, SMS, bracelet, mobile app) | query_token | `merch_gmt`; `merch_url`; `nonce`; `suid`; `timestamp`; `token`; `zsign` | HTML windows-1251 tbody rows | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `glolime/index.js` `glolime.getBalance` | uncertain |
| GET | `https://school.glolime.ru/api/netrika/transfer/create/` | Glolime: transfer money between the pupil's meal accounts | query_token | `sourceAccount`; `destinationAccount`; `sum` (rubles); `merch_gmt`; `merch_url`; `nonce`; `suid`; `timestamp`; `token`; `zsign` |  | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `glolime/index.js` `glolime.transferMoney` | uncertain |
| GET | `https://school.glolime.ru/api/netrika/userinfo/` | Glolime food-payment: account (лицевой счёт) number; HTML in windows-1251, first <td> | query_token | `merch_gmt`; `merch_url`; `nonce`; `suid`; `timestamp`; `token`; `zsign` | HTML windows-1251 | npm:spb_dnevnik 1.4.1 (mikhaillav/SPB_Dnevnik2) `glolime/index.js` `glolime.getAccountID` | uncertain |
| GET | `http://petersburgedu.ru/dnevnik/lessons/` | Old portal: subjects and marks for a period (HTML table.lessons-list + table.marks-table) | cookie | `period=current` (or a period id) | HTML | dukei/any-balance-providers (providers/ab-education-petersburgedu) `providers/ab-education-petersburgedu/main.js` `main` | legacy |
| GET | `http://petersburgedu.ru/user/auth/login/` | Old portal login page (primes cookies) | none |  |  | dukei/any-balance-providers (providers/ab-education-petersburgedu) `providers/ab-education-petersburgedu/main.js` `main` | legacy |
| GET | `https://petersburgedu.ru/dnevnik/cabinet` | Old portal: cabinet page listing students (HTML) | cookie |  | HTML | yar229/SpbEdu `YaR.SpbEdu/Requests/StudentListRequest.cs` `StudentListRequest` | legacy |
| GET | `https://petersburgedu.ru/dnevnik/lessons/index/student/{student_id}` | Old portal: marks table of a student (HTML #marks) | cookie | `{student_id}` old portal student id | HTML table#marks with month headers | yar229/SpbEdu `YaR.SpbEdu/Requests/StudentGradRequest.cs` `StudentGradRequest` | legacy |
| GET | `https://petersburgedu.ru/dnevnik/subject/statistic/student/{student_id}/subject/{subject_id}` | Old portal: per-subject statistic page with latest homework (HTML table.subject-stat, attachments) | cookie | `{student_id}` old portal student id; `{subject_id}` old portal subject id, e.g. 3149 алгебра | HTML table.subject-stat | ohmyp/petersburgedu_parser `parser.py` `loginbot` | legacy |
| GET | `https://petersburgedu.ru/dnevnik/timetable` | Old portal weekly timetable page with lessons, homework, grades (HTML table.week-lessons) | cookie | `date=14.11.2018` (dd.mm.yyyy) | HTML: table.week-lessons; cell h5/a@href (lesson id), p.about, p.homework, span.grade; id('period') title with date range | bargool/spb-dnevnik-bot `spb_dnevnik_bot/parser.py` `LoginSession.get_timetable_url` | legacy |
| GET | `https://petersburgedu.ru/user/auth/login` | Old portal login page; ESIA (Госуслуги) path clicks '.esia-login' and fills mobileOrEmail/password in PhantomJS | none |  | HTML | bargool/spb-dnevnik-bot `spb_dnevnik_bot/parser.py` `EsiaSession.login` | legacy |
| POST | `https://petersburgedu.ru/user/auth/login` | Old portal form login (HTML site, pre-dnevnik2) | none | body (application/x-www-form-urlencoded): `Login`, `Password`, `doLogin` 1 | sets session cookies | bargool/spb-dnevnik-bot `spb_dnevnik_bot/parser.py` `RegularSession.login` | legacy |
| POST | `https://petersburgedu.ru/user/auth/login/` | Old portal AJAX form login; JSON answer {status:'ok'} or {errors:[{text}]} ('Е-mail или пароль неверный') | none | body (application/x-www-form-urlencoded): `Login`, `Password`, `RememberMe` 0, `doLogin` 1, `authsubmit` Войти | JSON status/errors[].text \| also used by: ohmyp/petersburgedu_parser, yar229/SpbEdu (Origin https://petersburgedu.ru, Chrome/56 UA); dukei posts to http://petersburgedu.ru/user/auth/login/ with Referer http://petersburgedu.ru/user/auth/login/, while yar229 (LoginRequest.cs) and ohmyp (parser.py) post to https with only Login, Password, doLogin=1 | dukei/any-balance-providers (providers/ab-education-petersburgedu) `providers/ab-education-petersburgedu/main.js` `main` | legacy |
| GET | `https://petersburgedu.ru/user/auth/login/get-form/1/` | Old portal: fetch login form first (primes cookies) | none |  |  | yar229/SpbEdu `YaR.SpbEdu/Requests/InitRequest.cs` `InitRequest` | legacy |

**Formats.**

- *Dates*: Query dates are dd.mm.yyyy (p_date_from/p_date_to; newtover sends them unpadded as d.m.yyyy). Lesson and schedule ranges use p_datetime_from/p_datetime_to, sent as 'dd.mm.yyyy HH:MM:SS' (Zeusina, spb_dnevnik, RomyxR), 'dd.mm.yyyy+HH:MM:SS' (kewldan, local lessons) or plain dd.mm.yyyy (schedule). Response dates are 'dd.mm.yyyy' and datetimes 'dd.mm.yyyy HH:MM:SS'; the local mapper also accepts ISO. The city keeps Moscow time.
- *Ids*: Most objects carry identity.id (lessons also identity.uid). A pupil has a person id (identity.id), an opaque hash_uid (32 hex; used as RegId for food accounts) and one or more educations[] each with education_id (the key for marks/lessons/schedule/teachers/acs), group_id (class), institution_id (school) and jurisdiction_id (district). Periods have identity.id plus education_period.id (kewldan skips 23). Marks have id, lesson_id, subject_id; estimate_type_code '30000' = absence (Н), '30001' = lateness; estimate_value_name 'Замечание' = remark.
- *Pagination*: p_page (1-based; Zeusina starts at 0 then 2..n) and p_limit (clients use 500, or 1000 for marks). The envelope is {data:{items[], current, before, next, last, total_items, total_pages}, debug[], messages[], validations[]}. List filters are PHP-style arrays: p_educations[], p_groups[], p_periods[], p_group_ids[], p_jurisdictions[], p_institutions[]. acs/list and fps/list take a singular p_education.
- *Notes*: Success: data is an object, or occasionally a bare list. Errors: 200 with message or errors[{message}]. Logged out: an HTML page. Lesson items: number, datetime_from/to, subject_id, subject_name, content_name (topic), content_description, content_additional_material, tasks[]{task_name, task_code, task_kind_code, task_kind_name, files[]}, estimates[].

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Санкт-Петербург | `dnevnik2.petersburgedu.ru` | current diary + API |
| Санкт-Петербург | `petersburgedu.ru` | portal; old diary pages /dnevnik/... and form login (legacy); the city education portal (СПб ГКУ), not a «Моя школа» region host |
| Санкт-Петербург | `dnevnik.petersburgedu.ru` | given as a known host; not seen in any client read nor in govdomains region-78 lists; unverified |
| Санкт-Петербург | `school.glolime.ru` | school-meal payment provider linked from the diary |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| /home/user/lessons/server/app/providers/petersburg/client.py |  | 9 routes, cookie refresh / CookieConflict handling, date formats, envelope rules |
| /home/user/lessons/server/app/providers/petersburg/mapper.py |  | field names across endpoints, absence/late codes, acs direction values |
| /home/user/lessons/server/app/providers/petersburg/models.py |  | project's own model (no upstream routes) |
| https://github.com/kewldan/TelegramDnevnik | 2026-07-21 | aiohttp client (10 calls incl. /fps/api/netrika/mobile/v1/accounts/), token refresh from Set-Cookie, response fields in routes/journal.py; re-grepped for ЕСИА/captcha: none, email login only |
| https://www.npmjs.com/package/spb_dnevnik | 1.4.1, 2023-04-23 | widest route list (cms/banner, announcement, permission, fps, related-* lists, classroom teacher) + glolime module |
| https://github.com/mikhaillav/SPB_Dnevnik2 | 2023-07-16 | same code as npm package (index.js byte-identical; also mirrored as mikhaillav/spb_dnevnik) |
| https://github.com/mikhaillav/spb_dnevnik | 2023-07-16 | identical to SPB_Dnevnik2 |
| https://github.com/mikhaillav/dnevnik2_docs (https://mikhaillav.github.io/dnevnik2_docs/) | 2023-03-24 | OpenAPI: login schema (type email\|eisa), related-child-list schema and pagination envelope |
| https://github.com/RomyxR/dnevnik2_spb_api | 2026-04-21 | list of 9 endpoints with params, reverse-engineered from site and apps; example dates April 2026, i.e. the /api/journal family was live then |
| https://github.com/deknowny/dnevnik2-petersburgedu-python-client | 2022-12-30 | login (as GET), children, marks; pydantic models of child and mark |
| https://github.com/newtover/dnevnik | 2021-12-04 | browser headers, per-path Referer, related-group-list, cookie-file auth for ЕСИА users |
| https://pypi.org/project/petersburgedu-wrap/ (GitHub Zeusina/petersbugredu-wrap-python) | 0.0.1; repo 2023-08-03 | endpoints.py, total_pages paging, lesson item shape with tasks/estimates |
| https://github.com/Zeusina/petersbugredu-wrap-python | 2023-08-03 | empty __init__.py; code only in the wheel |
| https://github.com/bargool/spb-dnevnik-bot (and pypi spb-dnevnik-bot 0.2.0) | 2018-11-14 | legacy petersburgedu.ru form login, ЕСИА via PhantomJS, /dnevnik/timetable scraping; the PyPI wheel's parser.py is identical |
| https://github.com/yar229/SpbEdu | 2017-04-05 | legacy get-form/login/cabinet/lessons pages |
| https://github.com/ohmyp/petersburgedu_parser | 2020-04-07 | legacy subject statistic page |
| https://github.com/dukei/any-balance-providers/tree/master/providers/ab-education-petersburgedu | provider v2, 2014-09-03 | legacy JSON form-login answer and /dnevnik/lessons/?period=, all on http://petersburgedu.ru/ |
| https://github.com/NickSerg/dnevnik-chrome-extension | 2020-11-23 | no HTTP calls; content script on /journal/estimate |
| https://github.com/ivabus/GradeMapper | 2022-07-01 | nothing (offline calculator) |
| https://github.com/SolomonNumb1/fgis-dnevnik-desktop | 2026-09-13 | Re-read for contamination. One REGIONS table (src/services/api.ts) maps every region to a myschool.*-style domain, 77 to school.mos.ru and 78 to petersburgedu.ru, and the same /v3/auth/esia\|sudir/login (LoginModal.tsx) and /api/family/web/v1/* shape is applied to all of them. So its petersburgedu.ru entry is not evidence about Petersburg, and the /v3/auth/esia/login row was refuted. |
| https://github.com/1Finder1/script-for-dnevnik2 | 2022-01-07 | unrelated (dnevnik.ru userscript) |
| https://github.com/romanrakhlin/dnevnik-spb |  | gone (404); search snippet only: uses /api/journal/estimate/table |
| https://zeusina.gitbook.io/petersburgedu-wrap/ |  | 404 |
| https://github.com/search?type=repositories&q=petersburgedu ; q=dnevnik2 (pages 1-2) ; q=dnevnik2+petersburg |  | candidate list (all cloned) |
| https://sourcegraph.com stream search petersburgedu.ru / api/journal/... / related-child-list |  | only dukei/any-balance-providers relevant; re-run during verification returned no hits |
| https://github.com/kontur-1c/AliceDiary | 2022-04-13 | nothing: Yandex Alice «Цифровой дневник» skill from an SPb hackathon, makes no diary calls (found by GitHub search 'дневник спб') |
| https://github.com/mikhaillav/SPB_Dnevnik2/tree/master/examples | 2023-07-16 | get_api example (teacher/list via passthrough) and fps/list -> glolime getBalance_frameData chain |
| https://pypi.org/pypi/dnev2spb/json |  | 404: deknowny's package is not published on PyPI despite its README |
| https://registry.npmjs.org/-/v1/search?text=petersburgedu \| dnevnik2 \| spb dnevnik |  | only spb_dnevnik 1.4.1 is Petersburg-related |
| https://github.com/search?type=repositories&q=dnevnik2+spb ; q=dnevnik+petersburg ; q=petersburg+diary ; q=дневник+спб |  | no client beyond those already cloned |
| https://github.com/kewldan/TelegramDnevnik/issues |  | no issues (open or closed), so nothing about login or API changes |
| https://github.com/search?type=repositories&q=dnevnik2.petersburgedu&s=updated&o=desc |  | 5 repos, newest RomyxR/dnevnik2_spb_api (April 2026); all already cloned |
| https://github.com/infoculture/govdomains | 2022-10-08 | region-78 domain lists: petersburgedu.kobr.gov.spb.ru only; no dnevnik.petersburgedu.ru |
| https://play.google.com/store/search?q=Петербургское образование дневник&c=apps |  | third-party «Школяр Электронный дневник СПб» (ru.spb.dnevnik, bruno.bored) and «Госуслуги Моя школа» (ru.gosuslugi.school); app detail pages came back truncated |
| https://dnevnik2.petersburgedu.ru/login ; https://petersburgedu.ru/ |  | 503 via WebFetch |
| https://ru.wikipedia.org/wiki/ФГИС_«Моя_школа» ; https://ru.wikipedia.org/wiki/Моя_школа |  | 404 |
| https://html.duckduckgo.com/html/?q=dnevnik2.petersburgedu.ru Моя школа 2026 ; https://www.bing.com/search?q=dnevnik2.petersburgedu.ru «Моя школа» переход |  | captcha / unrelated results; no usable information |

**Refuted during verification** — rows an extractor proposed that no source carries, kept here so that nobody re-proposes them.

- GET `/v3/auth/esia/login` — Belongs to the МЭШ / ФГИС «Моя школа» platform, not to «Петербургское образование». SolomonNumb1/fgis-dnevnik-desktop (src/components/LoginModal.tsx, src/services/api.ts) builds https://${region.domain}/v3/auth/esia/login for every region of one table and calls /api/family/web/v1/* on the same domain. It maps region '78' to the portal petersburgedu.ru, apparently by name. The Petersburg diary is dnevnik2.petersburgedu.ru, with /api/journal/* and a cookie X-JWT-Token. No Petersburg client from 2014 to 2026 uses a /v3/ or /api/family/ path.

**Caveats.**

- Everything is reverse-engineered; the operator publishes no official API. The only 'documentation' is a WIP community swagger with 2 paths and a markdown list (RomyxR).
- Only login, related-child-list, get-list-period, subject/list-studied, teacher/list, estimate/table, lesson/list-by-education, schedule/list-by-education and acs/list are used by more than one client. cms/banner/list, announcement/list-open, user/permission/get, journal/fps/list, related-person-list and the related-institution/jurisdiction lists appear only in spb_dnevnik (2023), and some of them in RomyxR's list.
- The response shapes of lesson/list-by-education and schedule/list-by-education are only partly known (from petersburgedu_wrap). The local mapper deliberately accepts several field names.
- p_datetime_from/p_datetime_to are sent in three different spellings by different clients; which the server actually requires is unverified from here.
- deknowny's GET login is probably a bug that happened to work; POST is the consensus.
- Glolime (school.glolime.ru) routes come from one 2023 client and parse HTML tables. kewldan's 2026 client instead uses /fps/api/netrika/mobile/v1/accounts/ on dnevnik2, so the glolime iframe path may be obsolete.
- Everything on petersburgedu.ru (/user/auth/login/, /dnevnik/...) is the pre-dnevnik2 portal and is kept as legacy. These clients date from 2014 to 2020 and scrape HTML; the 2014 any-balance provider used plain http.
- dnevnik.petersburgedu.ru was not seen in any source. romanrakhlin/dnevnik-spb and the zeusina gitbook are gone (404).
- GitHub code search needs sign-in and Sourcegraph's index is thin, so repositories that mention the endpoints only in code may have been missed.
- No live host was probed (unreachable from this environment).
- deknowny's dnev2spb is not on PyPI (404) although its README gives 'pip install dnev2spb'; the code read is the GitHub repository.
- The Sourcegraph stream API returned no hits for dnevnik2.petersburgedu.ru, related-child-list or estimate/table, and the WebSearch budget was exhausted during verification.
- No client automates ЕСИА/Госуслуги sign-in for dnevnik2; the ЕСИА token is copied from a browser. The /v3/auth/esia/login row that an earlier pass carried came from a multi-region ФГИС client and was refuted as contamination.
- Platform currency could not be checked against the vendor. dnevnik2.petersburgedu.ru/login and petersburgedu.ru answered 503 to WebFetch, the WebSearch budget was exhausted, and web search engines returned a captcha or unrelated results. So it is unverified whether Санкт-Петербург moves its diary to ФГИС «Моя школа» / «Госуслуги Моя школа» (for example from 1 September 2026). The newest evidence that dnevnik2 works as described is kewldan/TelegramDnevnik, 2026-07-21.
- Five routes (cms/banner/list, announcement/list-open, user/permission/get, related-person-list, journal/fps/list) are 'uncertain' only because nothing after 2023 attests them. They belong to the current /api/journal family and may well still work.
- Single-region platform: Санкт-Петербург only. No other region runs «Петербургское образование»; school.glolime.ru is a third-party payment service.
