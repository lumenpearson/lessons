# БАРС.Web-Образование — БАРС Груп

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**One product under a regional name, one server per region.** Хакасия (`school.r-19.ru`), Тыва
(`school.rtyva.ru`), Удмуртия (`es.ciur.ru`), Кабардино-Балкария (`school.07.edu.o7.com`),
Карелия (`school.karelia.ru`), Тюменская (`school.72to.ru`), Рязанская, Владимирская,
Магаданская and more run the same code, and БАРС's own directory at
`aggregator.edu.bars.group` lists the servers the official app «Мой дневник» can pick. Several
of these regions are moving off it in 2026/27 — see [the regions](regions.md).

**Two API families.** The web front end talks to `/api/<Service>/<method>` with a `sessionid`
cookie; the older mobile app talked to `/rest/*` with form-encoded posts. The `/rest/*` data
calls are marked uncertain, because the only clients that use them stopped in 2020 and 2023;
the `/api/*` calls are what the 2025–2026 clients use, and even they disagree on whether a
method takes GET or POST, so both are listed.

**Signing in.** Most of these regions are ЕСИА-only now, and no client drives ЕСИА: the
working flow is a person signing in in a browser and the client reusing the `sessionid`
cookie. The login and password form is listed because some servers may still accept it.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| web | `https://{host}` | one host per region; web SPA /personal-area/, login /auth/login |
| api | `https://{host}/api` | web JSON API /api/<Service>/<method>, cookie sessionid |
| mobile_api | `https://{host}/rest` | mobile-app REST, form-encoded POST, cookie sessionid |
| other | `https://aggregator.edu.bars.group` | regional server directory /my_diary |
| legacy | `http://aggregator-obr.bars-open.ru` | old directory address |

**Signing in.**

*Mobile REST login (/rest/login)* — token carried as cookie sessionid. Lifetime: server session; not stated in code. Refresh: re-login.

1. GET https://{host}/rest/login?login=..&password=.. (barsdiary) or POST form login,password (krypt0nn/BarsAPI, BarsDiaryNativeLogin)
2. Check JSON success; read childs[][0] as pupil_id, profile_id, fio
3. Keep the sessionid cookie from Set-Cookie and send it on every /rest/* POST (barsdiary auth_by_diary_session can resume from a stored sessionid)
4. POST /rest/logout to end

   Password accounts only where the region still allows them (not Мурманская since 2017, not Рязанская since April 2024). barsdiary disables TLS verification and uses UA barsdiary/<version>. Last client evidence that it works: BarsDiaryNativeLogin POST /rest/login on school.vip.edu35.ru, October 2024.

*Web form login (/auth/login)* — token carried as cookie sessionid (held in a cookie jar; the clients never name it). Refresh: repeat login.

1. POST https://{host}/auth/login form login_login, login_password
2. Expect body {"success": true,"redirect": "/"}
3. GET https://{host}/ (denisov) or /personal-area/#marks (school-journal) once; without this the API calls fail (denisov comment)
4. Call /api/<Service>/<method> with the cookie jar

   Lockout message 'Account locked: too many login attempts. Please try again later'. Region-dependent: PARS-DIARY docs/3 (2025) says es.ciur.ru still accepts it after hiding the form in September 2024; regions that are ESIA-only refuse it.

*ЕСИА/Госуслуги browser session, cookie copied by hand* — token carried as cookie sessionid (whole Cookie header also accepted). Lifetime: expires after inactivity; PARS-DIARY keeps it alive by an API request every hour (docs/3, notify/__main__.py NOTIFY_DURATION=1). Refresh: Responses may Set-Cookie a new sessionid; hfiehgb replaces the stored value.

1. Open https://{host}/auth/login-page (the path regional school pages link to) and press «Войти через портал госуслуг»; complete ЕСИА sign-in, including 2FA, in a desktop browser
2. DevTools > Сеть, reload, open the personal-area/ request (or api/MultiprofileService/getProfiles), copy the Cookie header value containing sessionid=...
3. Client sends it as the Cookie header on POST/GET https://{host}/api/...

   The only flow available to everybody as of 2025–2026: Мурманская область has been ESIA-only since 01.01.2017, Рязанская from 1 April 2024, Владимирская by a ministry order of 11 May 2023 (search snippet), and es.ciur.ru hid the login/password fields with CSS in September 2024 while the backend still accepted them. No client automates the ЕСИА redirect chain. PARS-DIARY rejects a pasted cookie without 'sessionid='. None of this applies after a region leaves БАРС: from 1 September 2026 Вологодская, Мурманская, Карелия (МЭШ-based «Моя школа») and Ростовская, НАО, Республика Алтай (ТОР «Госуслуги Моя школа», ESIA-only) no longer serve this API.

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Cookie` | sessionid=<redacted> | only credential; 403 or 'Server.UserNotAuthenticatedError' otherwise |
| `User-Agent` | random browser UA (fake_useragent) or barsdiary/0.1.5 | bars-api, askiphy/BarsAPI and pypi barsapi always send a random browser UA; PARS-DIARY docs/habr.md says no header spoofing is needed, only the cookie |
| `Referer` | https://{host}/personal-area/ | set by Sergey20091/hfiehgb together with browser Accept headers; optional in other clients |
| `X-Requested-With` | XMLHttpRequest | sent by Sergey20091/hfiehgb only; no other client sends it |

**Captcha and second factor.** Госуслуги (ЕСИА) 2FA is done in the browser on the ЕСИА side; no captcha handling in any client and no captcha seen on the БАРС login form in any source

The /rest/* family (mobile app ru.barsopen.mydiary) takes form fields with pupil_id; the /api/* family (personal-area web SPA) infers the pupil from the session. Errors: /rest returns success:false or {error}; /api returns 'Server.UserNotAuthenticatedError' or 'Client.ValidationError' strings. As of 2025–2026 no new login mechanism (no app-only token, no mos.ru-style ID) was found: the maintained client (iamlostshe/bars-api 2.2.0, 2026-02-17) and PARS-DIARY (2026-09-09) still take a hand-copied sessionid cookie; pars-diary/bot #18 (May 2025) was a client parser bug, not a server change.

**Routes** — 31 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://aggregator.edu.bars.group/my_diary` | Directory of regional diary servers (region name -> URL) | none |  | {success, data:[{name, url}]}; PARS-DIARY builds its region keyboard from it (pars_diary/utils/keyboards.py). Re-fetched 2026-09-24: 11 entries, including four regions that left БАРС on 1 September 2026 — the list is not a reliable signal of which regions are live | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_regions (consts.AGGREGATOR_URL)` | current |
| GET | `https://{host}/` | Main page fetched right after /auth/login; the session does not work for /api calls without it | cookie |  | HTML; response ignored. mainURL is https://shkola.nso.ru with no path; school-journal fetches /personal-area/#marks at the same point instead | denisov/notifier `shkolanso/parser.go` `getSummaryMarksRaw (client.Get(mainURL))` | current |
| GET | `https://{host}/api/HomeworkService/GetHomeworkFromRange` | Homework via GET, for the active week or around a date | cookie | `date?=2025-09-04` (YYYY-MM-DD; omitted for the active week) | same list of days with homeworks[]; template reads day.date, day.name, day.homeworks[].discipline/homework/teacher and strips HTML from homework | Sergey20091/hfiehgb `school-app-docker/app.py` `SchoolAPI.get_homework_active_week / get_homework_for_date` | current |
| POST | `https://{host}/api/HomeworkService/GetHomeworkFromRange` | Homework for the active week, or for the week starting at date | cookie | `date?=2024-12-16` (YYYY-MM-DD; PARS-DIARY history (commit 1e79a6f, utils/hw.py) sent the Monday of the week with requests.post; bars-api today sends none (active week)) | list of days [{date:'YYYY-MM-DD', name, homeworks:[{discipline, homework(HTML), teacher}]}]; PARS-DIARY takes the first 6 days | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_homework` | current |
| GET | `https://{host}/api/MarkService/GetSummaryMarks` | Same summary marks via GET | cookie | `date=2018-11-06` (YYYY-MM-DD) | see POST row; also used by denisov/notifier (shkola.nso.ru), daniil-dushenev/school-journal (sh-open.ris61edu.ru), Sergey20091/hfiehgb get_marks_for_date (школа.образование33.рф) | askiphy/BarsAPI `src/bars_api/barsapi.py` `BarsAPI.get_summary_marks` | current |
| POST | `https://{host}/api/MarkService/GetSummaryMarks` | Current-subperiod marks per discipline with averages | cookie | `date=2026-09-24` (YYYY-MM-DD, sent as query string even on POST) | {summary_marks_data:[{subperiod:{code,name}, dates:[YYYY-MM-DD], discipline_marks:[{discipline, average_mark, marks:[{date, mark, description}]}]}]}; older servers (2018 denisov testdata, 2023 sh-open.ris61edu.ru in school-journal) returned subperiod/dates/discipline_marks at top level. Body may contain <span> tags and U+200B which bars-api strips before json.loads | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_summary_marks` | current |
| POST | `https://{host}/api/MarkService/GetTotalMarks` | Final marks per subperiod (quarters/half-years/year) | cookie |  | {total_marks_data:[{subperiods:[{code,name}], discipline_marks:[{discipline, period_marks:[{subperiod_code, mark}]}]}]}; also askiphy/BarsAPI get_total_marks. pars-diary/bot issue #41 (2026-04-23, open): «Не удаётся получить итоговые оценки», cause unknown — the call or its shape may have changed. | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_total_marks` | current |
| GET | `https://{host}/api/MultiprofileService/getProfiles` | List of profiles (parent's children); used as a cheap token-validity check | cookie |  | JSON; 401/403 = invalid session; Set-Cookie may carry a renewed sessionid which the client stores | Sergey20091/hfiehgb `school-app-docker/app.py` `SchoolAPI.check_token_validity` | current |
| GET | `https://{host}/api/ProfileService/GetPersonData` | Same as POST, fetched with GET after form login | cookie |  | indicators[] with name 'Общий средний балл'; school-journal requests it over plain http://sh-open.ris61edu.ru with the cookie jar from /auth/login | daniil-dushenev/school-journal `main.py` `check_marks` | current |
| POST | `https://{host}/api/ProfileService/GetPersonData` | Current user and selected pupil profile with average-mark indicators | cookie |  | {children_persons[], selected_pupil_id, selected_pupil_name, selected_pupil_ava_url, selected_pupil_school, selected_pupil_is_male, selected_pupil_classyear, user_ava_url, user_has_ava, user_fullname, user_desc, user_is_male, phone, phone_sms, auth_user_profile_id, indicators:[{name, value, css}] (e.g. 'Общий средний балл')}. 403 or 'Server.UserNotAuthenticatedError' in body = cookie expired. Same call in askiphy/BarsAPI and pypi barsapi getData/getMarks; PARS-DIARY uses it to validate a pasted cookie | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_person_data (consts.PERSON_DATA_URL)` | current |
| GET | `https://{host}/api/ScheduleService/GetWeekSchedule` | Week timetable containing the date | cookie | `date` (YYYY-MM-DD (str(datetime.now().date()))) | {days:[{date, is_weekend?, lessons:[{discipline, ...}]}]} | daniil-dushenev/school-journal `main.py` `check_schedule` | current |
| POST | `https://{host}/api/SchoolService/getClassYearInfo` | Pupil's class: level, letter, form master, pupils | cookie |  | {study_level, letter, form_master, form_master_photo, form_master_male, specialization, photo, pupils:[{fullname, photo, male}]}; pypi barsapi getClass | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_class_year_info` | current |
| POST | `https://{host}/api/SchoolService/getSchoolInfo` | School card with staff list | cookie |  | {name, address, phone, site_url, count_employees, count_pupils, photo, email, ustav, employees:[{group, fullname, employer_jobs[], category, photo, male}]} | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_school_info` | current |
| POST | `https://{host}/api/WidgetService/getBirthdays` | Classmates' birthdays widget | cookie |  | list [{date, short_name}] | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_birthdays` | current |
| POST | `https://{host}/api/WidgetService/getClassHours` | Class-hours (классные часы) widget | cookie |  | raw JSON, not modelled | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_class_hours` | current |
| POST | `https://{host}/api/WidgetService/getEvents` | Events widget (parent meetings, school events) | cookie |  | raw JSON list, not modelled | iamlostshe/bars-api `src/bars_api/parser.py` `BarsAPI.get_events` | current |
| POST | `https://{host}/auth/login` | Web login form with login/password (hidden behind ЕСИА on some regions since 2024 but still accepted) | none | body (application/x-www-form-urlencoded): `login_login`, `login_password` | exact body {"success": true,"redirect": "/"} on success; session cookie kept by the client's cookie jar (the code never names it); failure text e.g. 'Account locked: too many login attempts. Please try again later'. Also daniil-dushenev/school-journal main.py check_marks/check_schedule (sh-open.ris61edu.ru) and pypi barsapi login_url (school.r-19.ru, declared but unused). Currency: region-dependent; accepted on es.ciur.ru after the form was hidden in September 2024 (PARS-DIARY docs/3), refused where the region is ESIA-only. | denisov/notifier `shkolanso/parser.go` `getSummaryMarksRaw` | current |
| GET | `https://{host}/auth/login-page` | Browser sign-in page (login/password form where still enabled, and the «Войти через портал госуслуг» ЕСИА button); where the hand-copied sessionid is obtained. An HTML page, not an API | none |  | HTML. The same path is linked by regional pages for school.72to.ru (72.gim12tyumen.ru), e-school.ryazangov.ru, sosh.mon-ra.ru (older БАРС server listing) and school.r-19.ru (search result). The page's form posts to /auth/login; openschool.49gov.ru's page shows «Войти через портал госуслуг». The ЕСИА redirect chain behind the button is not in any source. | butschool-melen.ucoz.ru/index/ehlektronnye_dnevniki_i_zhurnaly/0-175 `school page «Электронные дневники и журналы» (Владимирская область)` `link https://xn--80atdl2c.xn--33-6kcadhwnl3cfdx.xn--p1ai/auth/login-page` | current |
| GET | `https://{host}/personal-area/` | Personal-area SPA page; visited after login (school-journal fetches /personal-area/#marks) and used as Referer; the page whose request carries the sessionid cookie users copy | cookie |  | HTML; ignored. Sergey20091/hfiehgb sets Referer: https://<host>/personal-area/ on every API call | daniil-dushenev/school-journal `main.py` `check_marks / check_schedule` | current |
| POST | `https://{host}/rest/login` | Same login sent as a form body (PHP client and the browser extension that restores the hidden login form) | none | body (application/x-www-form-urlencoded): `login`, `password` | JSON with success, fio, profile_id, id, childs; session cookie kept in a cookie jar (PHP writes session.tmp). Georglider/BarsDiaryNativeLogin main.js does fetch('/rest/login',{method:'POST',body:FormData}) and redirects to origin on success. Currency: last seen working in Georglider/BarsDiaryNativeLogin (2024-10-13) on school.vip.edu35.ru. Login/password is region-dependent: Мурманская область ESIA-only since 01.01.2017, Рязанская from 1 April 2024, es.ciur.ru hid the form in September 2024 (backend still accepted it per PARS-DIARY docs/3). | krypt0nn/BarsAPI `php/Bars.php` `Bars::__construct / Bars::request` | current |
| GET | `https://{host}/api/MarkService/GetTotalMarks` | Final marks for a given child (seen only in commented-out code) | cookie | `childPersonId?` (child person id; example value in code is a placeholder) | commented code reads subperiods[] and discipline_marks[].period_marks[] at top level | daniil-dushenev/school-journal `main.py` `check_marks (commented block)` | uncertain |
| POST | `https://{host}/rest/additional_materials` | Attached materials of one lesson | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login, `lesson_id` lesson[0] from /rest/diary | {success, kind, data:[{name, file(URL)}]}. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.additional_materials` | uncertain |
| POST | `https://{host}/rest/check_food` | Whether the school has the canteen (food) plugin | cookie |  | {food_plugin:'NO'\|'YES'}; the reply still goes through _check_response, which raises unless success is true, but CheckFoodObject itself has no success field. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.check_food` | uncertain |
| POST | `https://{host}/rest/diary` | Diary (lessons, homework, marks, teacher, room, attendance) for a date range | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login, `from_date` dd.mm.YYYY, `to_date` dd.mm.YYYY; defaults to from_date | {success, days:[["dd.mm.YYYY", {kind, lessons:[{discipline, subject(topic), teacher, room, comment, remark, attendance, homework[], next_homework[], individualhomework[], next_individualhomework[], marks[[names],[values]], date, lesson:[id, name, start, end]}]}]]}; also used by krypt0nn/BarsAPI User::getDiary. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.diary` | uncertain |
| POST | `https://{host}/rest/lessons_scores` | Marks per lesson/date for the current subperiod, optionally one subject | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login, `date` dd.mm.YYYY, `subject` discipline name or empty string for all | {success, kind, subperiod, data:{discipline:[{date, marks:{text:[marks]}}]}}; used by MeowSchool (vk_bot/blueprints/scheduler.py) for new-mark notifications. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.lessons_scores` | uncertain |
| GET | `https://{host}/rest/login` | Mobile-app login with login/password; sets the sessionid cookie | none | `login`; `password` | JSON {success, childs:[[pupil_id, name, school], ...], profile_id, id, type, fio}; Set-Cookie sessionid=...; errors {error:"..."} or success:false with error_code. barsdiary also has sync.py (httpx) with identical calls; TLS verification disabled. DiaryApi.auth_by_diary_session rebuilds a client from a stored sessionid cookie without calling this endpoint. Currency: GET form seen only in barsdiary (last commit 2023-04-14); the 2024 extension uses POST. Login/password is region-dependent: Мурманская область ESIA-only since 01.01.2017, Рязанская from 1 April 2024, es.ciur.ru hid the form in September 2024 (backend still accepted it per PARS-DIARY docs/3). | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.auth_by_login` | uncertain |
| POST | `https://{host}/rest/logout` | End the mobile session | cookie |  | {success}; also called by krypt0nn/BarsAPI Bars::logout. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.logout` | uncertain |
| POST | `https://{host}/rest/progress_average` | Average marks of the pupil vs class year vs level | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login, `date` dd.mm.YYYY | {success, kind, self:{total, data:{discipline: avg}}, classyear:{...}, level:{...}, subperiod}. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.progress_average` | uncertain |
| POST | `https://{host}/rest/school_meetings` | Parent meetings | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login | {success, kind} (payload not modelled by the client). Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.school_meetings` | uncertain |
| POST | `https://{host}/rest/totals` | Period (term/year) final marks | cookie | body (application/x-www-form-urlencoded): `pupil_id` child id = childs[i][0] from /rest/login, `date` dd.mm.YYYY | {success, period, period_types:['1 Полугодие','2 Полугодие','Годовая'], subjects:{discipline:[mark per period]}, period_begin, period_end}. Currency: called only by barsdiary (2023-04-14) and krypt0nn/BarsAPI (archived 2020); no 2024–2026 client uses the /rest/* data calls, and whether the «Мой дневник» app still does is not established. | mironovmeow/barsdiary `barsdiary/aio.py` `DiaryApi.totals` | uncertain |
| GET | `http://aggregator-obr.bars-open.ru/my_diary` | Old address of the regional directory | none |  | named in bars-api consts.py as old_url | askiphy/BarsAPI `README.md` `HOST comment` | legacy |

**Formats.**

- *Dates*: /rest/*: request dates dd.mm.YYYY (from_date, to_date, date); diary day and lesson dates dd.mm.YYYY; lesson times inside lesson[2..3]. /api/*: date query param YYYY-MM-DD; marks[].date and homework day date YYYY-MM-DD.
- *Ids*: integer pupil_id = childs[i][0] from /rest/login (childs is an array of [id, name, school] tuples); profile_id and id also returned; lesson id = lesson[0] in /rest/diary; /api uses selected_pupil_id / auth_user_profile_id; subperiod codes like 'Четверть_2'; childPersonId query param seen once
- *Pagination*: none; ranges are by date (from_date/to_date, date=week or subperiod)
- *Notes*: /api responses can embed <span ...> markup and zero-width spaces inside strings; marks are strings ('5', '4-').

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Республика Хакасия | `school.r-19.ru` | current: in aggregator.edu.bars.group/my_diary (re-fetched 2026-09-24); web page answered 503 to WebFetch |
| Республика Тыва | `school.rtyva.ru` | status unknown: in older БАРС listings only, absent from aggregator.edu.bars.group/my_diary on 2026-09-24; answered 503 to WebFetch |
| Удмуртская Республика | `es.ciur.ru` | current: in aggregator 2026-09-24; login/password form hidden since September 2024, ЕСИА button (PARS-DIARY docs/3) |
| Кабардино-Балкарская республика | `school.07.edu.o7.com` | current: in aggregator 2026-09-24 |
| Республика Карелия | `school.karelia.ru` | in aggregator 2026-09-24, but Карелия moves to the МЭШ-based «Моя школа» at karelia.minedu.ru from 2026/27 (МЭШ help region table); pars-diary/bars-api #16 (2025-09-03) enumerated its /api services |
| Республика Карелия (ССУЗ) | `college.karelia.ru` | ССУЗ; dnvk.ru; not in the 2026 aggregator |
| Республика Карелия | `contingent.karelia.ru` | legacy: older listing |
| Тюменская область | `school.72to.ru` | legacy: until 29 August 2024; Тюменская область moved to the МЭШ-based «Моя школа» myschool.72to.ru (app 29 Aug 2024, web 11 Sep 2024); login page link school.72to.ru/auth/login-page on 72.gim12tyumen.ru |
| Рязанская область | `e-school.ryazangov.ru` | legacy: ESIA-only from 1 April 2024; closed 29 August 2025, replaced by «Сетевой Город. Образование» at e-school.ryazan.gov.ru (a netschool host, not БАРС) from 1 September 2025 (ryazannews.ru 01.09.2025) |
| Владимирская область | `школа.образование33.рф` | current: in aggregator 2026-09-24 (punycode xn--80atdl2c.xn--33-6kcadhwnl3cfdx.xn--p1ai); login page /auth/login-page linked from school pages; the regional portal is piloting «Госуслуги Моя школа», no shutdown date found; default host of krypt0nn/BarsAPI and Sergey20091/hfiehgb |
| Вологодская область | `school.vip.edu35.ru` | legacy from 1 September 2026: in aggregator 2026-09-24, but Вологодская область moved all 319 schools to the МЭШ-based «Моя школа» dnevnik.edu35.ru on 1 September 2026; BarsDiaryNativeLogin (2024) |
| Магаданская область | `openschool.49gov.ru` | current: in aggregator 2026-09-24; page shows «Войти через портал госуслуг», support support_obr.49@bars.group; no 2026 transition notice found |
| Магаданская область | `eschool.49edu.ru` | legacy: older listing |
| Мурманская область | `s51.edu.o7.com` | legacy from 1 September 2026: ESIA-only since 01.01.2017; Мурманская область moved to the МЭШ-based «Моя школа» edu.mso51.ru on 1 September 2026, though still in the aggregator on 2026-09-24 |
| Мурманская область (УСПО) | `c51.edu.o7.com` | УСПО; older listing; fate after the region's 2026 move not established |
| Мурманская область (доп. образование) | `do.51.edu.o7.com` | доп. образование; older listing; fate after 2026 not established |
| Мурманская область (детский сад) | `d51.edu.o7.com` | детский сад; older listing; fate after 2026 not established |
| Ненецкий автономный округ | `edu.adm-nao.ru` | legacy from 1 September 2026: the host now shows only «С 1 сентября 2026 г. Ненецкий автономный округ переходит на использование приложения «Госуслуги Моя школа». Электронный дневник будет доступен только там.» (still listed in the aggregator) |
| Ростовская область | `sh-open.ris61edu.ru` | legacy from 1 September 2026: Ростовская область moved to ТОР «Госуслуги Моя школа»; «Региональный сервис «Электронная школа» прекращает свою работу» (sosh8.bkobr.ru); still in the aggregator 2026-09-24; daniil-dushenev/school-journal |
| Ростовская область | `sh-open.rostobr.ru` | legacy: older listing |
| Новосибирская область | `shkola.nso.ru` | legacy: no longer resolves (September 2026); Новосибирская область uses ГИС НСО «Электронная школа» at school.nso.ru (Иннотех, not БАРС) since about 2021; denisov/notifier |
| Пензенская область | `uko.edu-penza.ru` | status unknown: absent from the 2026 aggregator, answered 503; el-shk.ru in an older listing |
| Республика Алтай | `sosh.mon-ra.ru` | legacy from 1 September 2026: all schools of Республика Алтай moved to «Госуслуги Моя школа» (1line.info, 13.08.2026); older БАРС listing gives sosh.mon-ra.ru/auth/login-page; MeowSchool/barsdiary |
| Республика Алтай (СПО) | `spo.mon-ra.ru` | СПО; older listing; fate after 2026 not established |
| Липецкая область | `schools48.ru` | legacy: «Мой дневник» Play-store support replies of 2022-10-25 (PARS-DIARY history) name https://schools48.ru/ as the web version; Липецкая область now on ЭлЖур (edu.schools48.ru, vsosh44/schools48bot); not in the 2026 aggregator |
| general | `eduschl-general-1.edu.bars.group` | current: first entry of aggregator.edu.bars.group/my_diary (2026-09-24); a general БАРС server, region not stated |
| Ростовская область (СПО) | `col-open.ris61edu.ru` | legacy from 1 September 2026 with the region; colleges instance named in the vendor/market research host list; no client uses it |
| Тюменская область | `old-school.72to.ru` | legacy: «Электронная школа Тюменской области» archive host after the 2024 move; answered 503 |
| Пензенская область | `el-shk.ru` | older unofficial listing (xn----7sbbabcizpm8cgdwdm.xn--p1ai); answered 503; attribution to БАРС unconfirmed |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/mironovmeow/barsdiary | 2023-04-14 | all /rest/* endpoints, form fields, response models, UA |
| https://pypi.org/project/barsdiary/ | 0.1.5 | wheel identical to repo |
| https://github.com/iamlostshe/bars-api | 2026-02-17 (v2.2.0) | /api/* services, aggregator, error strings, response types, docs; full history re-read (last functional change 2025-09-22, no path changes) |
| https://github.com/pars-diary/bars-api | 2026-02-17 | same code as iamlostshe/bars-api |
| https://github.com/askiphy/BarsAPI | 2025-03-21 | earlier bars-api: GET GetSummaryMarks, old aggregator URL |
| https://pypi.org/project/barsapi/ | 1.2 | school.r-19.ru URLs, /auth/login, Cookie sessionid, POST GetPersonData/getClassYearInfo |
| https://github.com/krypt0nn/BarsAPI | 2020-03-14 (archived) | POST /rest/login, /rest/diary, /rest/logout, d.m.Y dates, default host школа.образование33.рф |
| https://github.com/Georglider/BarsDiaryNativeLogin | 2024-10-13 | POST /rest/login via FormData from the web login page; school.vip.edu35.ru |
| https://github.com/mironovmeow/MeowSchool | 2022-04-14 | barsdiary usage, sosh.mon-ra.ru, dd.mm.YYYY |
| https://github.com/iamlostshe/PARS-DIARY | 2026-09-09 | cookie-copy flow, keep-alive, ЕСИА notes, hidden login form on es.ciur.ru (September 2024, backend still accepts), homework shape |
| https://github.com/iamlostshe/PARS-DIARY (full git history, 152 commits) | 2024-12-16 commit 1e79a6f; 2025-01-31 commit b72c800 | verifier-added: POST GetHomeworkFromRange?date=<Monday> in the old utils/hw.py; «Мой дневник» Play-store support replies naming schools48.ru, e-school.ryazangov.ru, school.vip.edu35.ru, sh-open.ris61edu.ru as web versions |
| https://github.com/pars-diary/bot |  | same as PARS-DIARY |
| https://github.com/daniil-dushenev/school-journal | 2023-08-02 | /auth/login fields, /personal-area/, ScheduleService/GetWeekSchedule, GET GetSummaryMarks/GetPersonData, sh-open.ris61edu.ru |
| https://github.com/denisov/notifier | 2025-07-07 | /auth/login success body, main-page warm-up, shkola.nso.ru, 2018 GetSummaryMarks shape |
| https://github.com/Sergey20091/hfiehgb | 2026-02-08 | HomeworkService/GetHomeworkFromRange, MultiprofileService/getProfiles, Referer/X-Requested-With headers, sessionid rotation; compiled .pyc files carry no other endpoints |
| https://github.com/karpyzin/bars-diary-privacy-policy | 2022-01-17 | verifier-added: privacy policy of a third-party Android client «Барс Дневник» (ru.unit.barsdiary) where the user types the server address; no code or endpoints |
| https://github.com/Hateman31/barsy | 2015-04-04 | nothing (README only, unrelated) |
| https://github.com/vsosh44/schools48bot | 2025-12-05 | shows Липецк now uses ЭлЖур API; excluded |
| https://github.com/Nsity/schooldiary | 2020-04-23 | unrelated |
| https://github.com/ann-dvlpr/Dnevnik-Ryazan | 2024-04-05 | WebView wrapper of e-school.ryazangov.ru, no API |
| https://github.com/invistoy/black-for-school.karelia.ru |  | CSS theme only |
| https://github.com/sheeiavellie/api-bars |  | verifier-checked: unrelated (sport-places API named 'Bars API') |
| https://aggregator.edu.bars.group/my_diary |  | regional server list |
| https://dnvk.ru/ |  | regional server list |
| https://xn----7sbbabcizpm8cgdwdm.xn--p1ai/ |  | older regional server list |
| https://telegra.ph/Instrukciya-po-registracii-v-bote-04-25 |  | cookie copy instructions |
| https://aggregator.edu.bars.group/my_diary | 2026-09-24 | currency pass: re-fetched, 11 entries (list unchanged from the earlier read) |
| https://github.com/pars-diary/bars-api/issues/16 | 2025-09-03 | /api service names seen via debug mode on school.karelia.ru |
| https://github.com/pars-diary/bars-api/issues |  | 2 open issues (#16, #17), nothing about auth changes |
| https://github.com/pars-diary/bot/issues |  | #41 final marks fail (2026-04-23), #18 auth problem was a client bug (2025-05-14) |
| https://butschool-melen.ucoz.ru/index/ehlektronnye_dnevniki_i_zhurnaly/0-175 |  | login page https://xn--80atdl2c.xn--33-6kcadhwnl3cfdx.xn--p1ai/auth/login-page, system named «БАРС.Образование – Электронная школа» |
| https://edu.adm-nao.ru/ |  | (via discover research) notice that НАО moves to «Госуслуги Моя школа» from 1 September 2026 |
| https://sosh8.bkobr.ru/press-centr/novosti/6188-znakomimsya-s-platformoj-gosuslugi-moya-shkola-2026 |  | (via discover research) Ростовская «Электронная школа» stops, move to ТОР |
| https://ryazannews.ru/fn_1717498.html |  | (via discover research) Рязанская replaced «БАРС.Образование» with «Сетевой Город. Образование» on 1 September 2025 |
| https://myschool.mos.ru/help/instructions/mesh-id/authorization-esia/authorization-diaryreg/ |  | (via vendors research) МЭШ-based «Моя школа» hosts of Тюменская, Карелия, Вологодская, Мурманская |
| https://1line.info/news/ak/social/obrazovanie/s-1-sentyabrya-2026-vse-shkoly-respubliki-altay-pereydut-na-moyu-shkolu.html |  | (via vendors research) Республика Алтай to «Госуслуги Моя школа» from 1 September 2026 |
| https://raw.githubusercontent.com/beerphilipp/tabbed-out/main/analysis/results/ru.barsopen.mydiary.res.json |  | nothing (static-analysis flags only) |
| https://sourcegraph.com (ProfileService/GetPersonData, HomeworkService/GetHomeworkFromRange, ScheduleService/GetWeekSchedule, MarkService/GetSummaryMarks, aggregator.edu.bars.group, auth/login-page) |  | no БАРС hits beyond known clients |
| https://es.ciur.ru/, https://school.r-19.ru/, https://school.rtyva.ru/, Play/RuStore pages of ru.barsopen.mydiary |  | 503 / truncated / 404 — nothing |

**Refuted during verification** — rows an extractor proposed that no source carries, kept here so that nobody re-proposes them.

- GET `/auth/login-page` — No source contains 'login-page'. BarsDiaryNativeLogin's manifest matches https://school.vip.edu35.ru/*auth* and main.js only tests location.pathname.includes('auth'); PARS-DIARY docs describe the es.ciur.ru login page without a path. The path was invented. — REVERSED by the currency pass: regional school pages link to it (butschool-melen.ucoz.ru for школа.образование33.рф, 72.gim12tyumen.ru for school.72to.ru, older listings for sosh.mon-ra.ru and e-school.ryazangov.ru), so GET /auth/login-page is now a route (the browser page, not an API) with a school page as its source.

**Caveats.**

- Everything is reverse-engineered; there is no official public API documentation.
- Two API families: /rest/* (mobile app «Мой дневник») seen only in barsdiary (last commit 2023-04-14), krypt0nn/BarsAPI (archived, 2020-03-14) and a 2024 browser extension that uses only /rest/login, so the /rest/* data calls are marked uncertain; /api/* (web SPA) is what the maintained client iamlostshe/bars-api (2026-02-17, used by PARS-DIARY 2026-09-09) calls and what pars-diary/bars-api issue #16 (2025-09-03) enumerated on school.karelia.ru.
- HTTP method is inconsistent across clients for /api/*: bars-api uses POST with query params, others GET; both are listed as separate rows.
- ScheduleService/GetWeekSchedule and MultiprofileService/getProfiles each appear in only one hobby client; GET GetTotalMarks?childPersonId only in commented-out code.
- Most regions moved to ЕСИА-only sign-in; login/password endpoints may be refused on some hosts, so the practical flow is a hand-copied sessionid cookie.
- Response shapes changed over time (GetSummaryMarks top-level in 2018 and still in 2023 on sh-open.ris61edu.ru vs summary_marks_data[0] now).
- The browser login page is /auth/login-page: no client code carries it (BarsDiaryNativeLogin only matches any URL containing 'auth'), but regional school pages link to it for школа.образование33.рф, school.72to.ru, e-school.ryazangov.ru and sosh.mon-ra.ru; the ЕСИА redirect chain behind its button is in no source.
- Hateman31/barsy and mironovmeow/MeowSchool carry no endpoints of their own (barsy is an empty README; MeowSchool uses barsdiary). pypi bars-api does not exist; bars-api installs from git. Nsity/schooldiary and vsosh44/schools48bot turned out unrelated (the latter is ЭлЖур). karpyzin publishes only the privacy policy of a third-party Android client «Барс Дневник» (ru.unit.barsdiary), not its code.
- No live host was reachable from the research environment; nothing here was verified against a server.
- The platform is shrinking. БАРС's own directory (aggregator.edu.bars.group/my_diary, re-fetched 2026-09-24) lists 11 servers, but four of them stopped being the regional diary on 1 September 2026: Вологодская and Мурманская (МЭШ-based «Моя школа»), Ростовская and НАО (ТОР «Госуслуги Моя школа»); Карелия moves to МЭШ-based «Моя школа» in 2026/27, and Республика Алтай (not in the directory) moved to ТОР on 1 September 2026. Earlier: Тюменская to МЭШ-based «Моя школа» (2024), Рязанская to «Сетевой Город» (2025), Новосибирская to Иннотех (≈2021), Липецкая to ЭлЖур. Regions still on БАРС as far as found: Владимирская, Кабардино-Балкарская, Магаданская, Хакасия, Удмуртия (Тыва and Пенза unknown). Hosts are listed with that status in regional_instances.
- Contamination check: the successor hosts myschool.72to.ru, dnevnik.edu35.ru, edu.mso51.ru, karelia.minedu.ru (МЭШ API), e-school.ryazan.gov.ru (netschool), school.nso.ru (Иннотех), edu.schools48.ru (ЭлЖур) and gosuslugi.ru/school (ТОР) are other platforms and are deliberately not listed here; no route in this list was found to belong to another platform.
- pars-diary/bars-api issue #16 names further /api services seen through debug mode on school.karelia.ru — LessonPlanService, PortfolioServices, PortfolioFieldServices, ContingentPortfolioServices, FeedbackService, YaClassWidgetService, TestworkService, LiteratureService, SferumChatService, SferumService — without their method names, so no route rows exist for them.
- pars-diary/bot issue #41 (2026-04-23, open): final marks can no longer be fetched, cause unknown.
- No official API documentation exists; barsdiary.readthedocs.io and bars-api.readthedocs.io document community clients only.
- The WebSearch budget of this session was exhausted before this pass began, so the migration dates come from earlier research checkpoints (discover_3..7, vendors-and-market) and fresh WebFetches of the aggregator, GitHub issues and one school page, not from new searches.
