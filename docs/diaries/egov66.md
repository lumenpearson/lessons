# ГИС СО «ЕЦП» — Свердловская область, 2023–2026

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: high. Nothing here has been tried against the live service.*

**A regional system that has just been switched off.** The «Журнал» and «Электронный дневник»
modules of the Свердловская область «Единое цифровое пространство» replaced the per-city
«Сетевой город» servers in 2023 and served the region until 31 August 2026. Both
`dnevnik.egov66.ru` and `jurnal.egov66.ru` now read «Работа в модулях «Журнал» и «Электронный
дневник» ГИС СО «ЕЦП» завершена» and send families to «Госуслуги Моя школа», the
[federal ТОР](myschool-federal.md). Every diary route below is therefore legacy; the page exists
so that the region's history is not re-discovered as a live option.

**How it worked.** A person signed in through Госуслуги — the only way in since 1 July 2024 —
and the SPA kept a bearer token in local storage, which the four hobby clients copied out and
sent as `Authorization: Bearer` to `dnevnik.egov66.ru/api`. It had one API generation from
start to end: a client of January 2025 and one of August 2026 call the same paths. The colleges'
cabinets under `*.ecp.egov66.ru` were a separate Laravel application, and they now serve the
same «Госуслуги Моя школа» notice, so their rows are legacy too.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| api | `https://dnevnik.egov66.ru/api` | JSON REST API behind the «Электронный дневник» SPA; the only API generation (same paths from dnevnikc.py 2024 to dnevnik_client.py 2026-08). Legacy: switched off 1 September 2026; dnevnik.egov66.ru and /modules read «Работа в модулях «Журнал» и «Электронный дневник» ГИС СО «ЕЦП» завершена» |
| web | `https://dnevnik.egov66.ru` | SPA; sign-in only through «Войти через Госуслуги» (ЕСИА) from 1 July 2024 until shutdown; tokens in localStorage Auth__token / Auth__refresh / childId. Now «Дневник недоступен» with app-store links to «Госуслуги Моя школа» |
| web | `https://jurnal.egov66.ru` | teacher-facing «Журнал»; no open-source client calls it. Now the same shutdown notice |
| auth | `https://lk.ecp.egov66.ru` | ЕЦП personal cabinet for СПО (registration/sign-in via Госуслуги, title «Личный кабинет студента, родителя и преподавателя ГИС СО «Единое цифровое пространство»»); returned 503 to WebFetch on 2026-09-24, search index gives the title «Госуслуги Моя школа» |
| legacy | `https://{instance}.ecp.egov66.ru` | per-college cabinet instance tNN (Laravel + Livewire timetable); t2, t8, t26, t40, t92 now serve only the «Госуслуги Моя школа» shutdown notice |

**Signing in.**

*ЕСИА (Госуслуги) sign-in on dnevnik.egov66.ru in a browser/WebView, then token taken from localStorage* — token carried as header Authorization: Bearer <Auth__token> (frogfile/jrn additionally sends Cookie Aiss2Auth=Bearer%20<token>; Egov66Client names its stored token and parameter 'Aiss2Auth' but sends it only in Authorization). Lifetime: access token is a JWT with an 'exp' claim; dnevnik66 decodes exp without verifying (database.get_jwt_exp) and refreshes REFRESH_BEFORE_SEC=600 s before it; jrn keeps its own AuthToken cookie for one day ('TODO: find out how long it really lives'). Refresh: POST https://dnevnik.egov66.ru/api/auth/Token/Refresh with JSON {"refreshToken": <Auth__refresh>} (sent with the same headers as every call, including the current Authorization: Bearer) returns {"accessToken", "refreshToken"} (refresh token rotates; store both). dnevnik66 also refreshes once on any 401 and retries.

1. Open https://dnevnik.egov66.ru/ in a real browser or Android WebView with JavaScript and DOM storage enabled
2. Press «Войти через Госуслуги»; the SPA redirects to the ЕСИА login page (fields #login and #password, submit button 'plain-button plain-button_wide')
3. Complete ЕСИА second factor (the Dreamlord4k parser switches the Госуслуги account to TOTP and types pyotp.TOTP(KEY).now() into the first input of the code form)
4. ЕСИА redirects back to dnevnik.egov66.ru; the SPA stores localStorage['Auth__token'] (access JWT), localStorage['Auth__refresh'] (refresh token) and localStorage['childId'] (selected student id)
5. The client reads Auth__token (Egov66Client injects JS on onPageStarted/onPageFinished/doUpdateVisitedHistory calling localStorage.getItem('Auth__token'); dnevnik66 and the sos-mislom extension ask the user to copy Auth__token and Auth__refresh)
6. Call GET /api/students with Authorization: Bearer <Auth__token> and take students[0].id as studentId for every later call

   No login/password API exists for the diary: since 1 July 2024 sign-in is only through Госуслуги. Every client either drives a browser (Selenium, WebView) or has the user paste tokens. Unchanged through 2025–2026 (newest client dnevnik66, August 2026, still pastes Auth__token/Auth__refresh copied via F12). Since 1 September 2026 no flow works: the SPA offers no sign-in at all; the successor is the separate platform «Госуслуги Моя школа» (ТОР «Моя школа»).

*Pasted bearer token (manual)* — token carried as header Authorization: Bearer. Lifetime: unknown; refreshable via /api/auth/Token/Refresh. Refresh: same as above.

1. User signs in at dnevnik.egov66.ru, copies Auth__token (and Auth__refresh) from DevTools → Application → Local Storage → https://dnevnik.egov66.ru (or with the sos-mislom browser extension, which reads Auth__refresh, Auth__token and childId)
2. Client stores them and sends Authorization: Bearer <token>

*ЕЦП personal cabinet (*.ecp.egov66.ru, СПО) session cookies + Livewire CSRF* — token carried as cookies edinyi_lk_session and remember_web_<hash>; header X-CSRF-TOKEN per request. Lifetime: Laravel session; remember_web cookie keeps it alive. Refresh: edinyi_lk_session is re-issued in each page response and must be replaced.

1. User signs in to the college cabinet in a browser and copies cookies edinyi_lk_session and remember_web_<hash>
2. GET https://{instance}.ecp.egov66.ru/schedule/groups (or /schedule/teachers) with those cookies; take the rotated edinyi_lk_session from the response cookies
3. Parse <meta name="csrf-token" content> from <head>; a <meta http-equiv="refresh"> instead means the session expired
4. Parse the div with attribute wire:initial-data containing 'scheduleGridWeekType' → JSON {fingerprint, serverMemo}
5. POST Livewire messages with headers X-CSRF-TOKEN and X-Livewire: true, the same cookies, and the current fingerprint/serverMemo

   Laravel Livewire v2 protocol (fingerprint/serverMemo/updates with type callMethod); cookie session obtained after Госуслуги sign-in on lk.ecp.egov66.ru; edinyi_lk_session rotates about every two hours (library docs). All *.ecp.egov66.ru cabinets show the shutdown notice since 1 September 2026.

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Authorization` | Bearer <Auth__token> | all /api routes on dnevnik.egov66.ru |
| `User-Agent` | Mozilla/5.0 … (Egov66Client sends plain 'Mozilla/5.0'; dnevnik66 a Chrome 124 desktop string) | every client sets a browser-like UA; not proven required |
| `Accept` | application/json or */* | set by dnevnik66 / Egov66Client |
| `Content-Type` | application/json | POST bodies (/api/auth/Token/Refresh, /api/homework/done) |
| `Cookie` | Aiss2Auth=Bearer%20<token> | sent by frogfile/jrn only, alongside Authorization; cookie name used by the SPA |
| `X-CSRF-TOKEN` | <meta csrf-token> | Livewire POSTs on *.ecp.egov66.ru |
| `X-Livewire` | true | Livewire POSTs on *.ecp.egov66.ru |

**Captcha and second factor.** ЕСИА second factor (SMS or TOTP) during Госуслуги sign-in; Dreamlord4k automates it with a TOTP secret. No captcha seen on the API itself.

Egov66Client's StudentResponse carries a TODO that the student list is 'protected' even with a token (an unexplained failure). dnevnik66 treats 400/401/403 as unauthorized. Direct login/password to the diary was switched off on 1 July 2024 (school notice on mou-sh11.ru). As of 1 September 2026 every flow is dead: dnevnik.egov66.ru, jurnal.egov66.ru and the tNN.ecp.egov66.ru cabinets serve only the «Госуслуги Моя школа» notice.

**Routes** — 20 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://dnevnik.egov66.ru/api/file/{fileId}` | Homework attachment download, guessed candidate | bearer | `{fileId}` | host switched off 1 September 2026 as well, so this can no longer be verified | yanaydenov/dnevnik66 `dnevnik_client.py` `DnevnikClient.download_file` | uncertain |
| GET | `https://dnevnik.egov66.ru/api/files/{fileId}` | Homework attachment download, guessed candidate | bearer | `{fileId}` | host switched off 1 September 2026 as well, so this can no longer be verified | yanaydenov/dnevnik66 `dnevnik_client.py` `DnevnikClient.download_file` | uncertain |
| GET | `https://dnevnik.egov66.ru/api/homework/file` | Homework attachment download, guessed candidate | bearer | `fileId` | guess; the method references an undefined self.api_url, so it never ran; host switched off 1 September 2026 as well, so this can no longer be verified | yanaydenov/dnevnik66 `dnevnik_client.py` `DnevnikClient.download_file` | uncertain |
| GET | `https://dnevnik.egov66.ru/api/homework/files/{fileId}` | Homework attachment download, guessed candidate | bearer | `{fileId}` homeWorkFiles[].id | host switched off 1 September 2026 as well, so this can no longer be verified | yanaydenov/dnevnik66 `dnevnik_client.py` `DnevnikClient.download_file` | uncertain |
| GET | `https://dnevnik.egov66.ru/api/lesson/homework/files` | Download a homework attachment (declared, @Streaming; the caller is an empty stub and no file-id parameter is declared) | bearer |  | binary stream; parameters unknown; host switched off 1 September 2026 as well, so this can no longer be verified | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/HomeWorkApi.kt` `HomeWorkApi.downloadHomeWorkFile` | uncertain |
| GET | `https://dnevnik.egov66.ru/` | SPA entry: «Войти через Госуслуги» starts the ЕСИА redirect; after sign-in localStorage holds Auth__token/Auth__refresh/childId | none |  | HTML SPA; the grades page (a period URL copied from the browser) is scraped by Dreamlord4k with CSS classes _discipline_875gj_34, _grade_1qkyu_5, _gap30_19hvj_93. Since 1 September 2026 the page shows «Дневник недоступен» / «Работа в модулях «Журнал» и «Электронный дневник» ГИС СО «ЕЦП» завершена» and links to «Госуслуги Моя школа» (ru.gosuslugi.school); no sign-in button | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/ui/login/LoginFragment.kt` `LoginFragment.initCallback` | legacy |
| POST | `https://dnevnik.egov66.ru/api/auth/Token/Refresh` | Exchange a refresh token for a new access/refresh token pair | bearer | body (application/json): `refreshToken` value of localStorage Auth__refresh | {accessToken, refreshToken}; both replaced | yanaydenov/dnevnik66 `dnevnik_client.py` `DnevnikClient.refresh_tokens` | legacy |
| GET | `https://dnevnik.egov66.ru/api/classes` | Student's class for a school year (classId needed by periods/subjects/estimate) | bearer | `studentId`; `schoolYear=2025` | {currentClass{value,text}, gradeItemModels[{value,text}]}; value is the classId | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/GradesApi.kt` `GradesApi.getClasses` | legacy |
| GET | `https://dnevnik.egov66.ru/api/estimate` | Grades: week table, period (quarter/half-year) table or year table, depending on periodId | bearer | `studentId`; `schoolYear?=2025`; `classId?`; `periodId?` («Текущая неделя» → weekGradesTable, a quarter → periodGradesTable, «Итоговые оценки» → yearGradesTable); `subjectId?=00000000-0000-0000-0000-000000000000` (all-subjects GUID); `weekNumber?` (pages the week table (pageNumber ± 1)); `date?=2024-10-14` (jrn calls /api/estimate?studentId=&date= only and reads weekGradesTable) | {weekGradesTable{paginationData, beginDate, endDate, days[{date, lessonGrades[{lessonId,name,sequenceNumber,beginHour,beginMinute,endHour,endMinute,presence,grades[[str]]}]}]}, periodGradesTable{days[{date}], disciplines[{name,averageGrade,averageWeightedGrade,totalGrade,grades[{presence,lessonId,date,grades[[str]]}]}]}, yearGradesTable{lessonGrades[{lesson{id,name},yearGrade,testGrade,finalyGrade,grades[{averageGrade,averageWeightedGrade,finallygrade,periodId}]}], periods[{id,name}]}, showAverageWeighted}; unused tables are null; grades are arrays of arrays of strings | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/GradesApi.kt` `GradesApi.getGrades` | legacy |
| GET | `https://dnevnik.egov66.ru/api/estimate/periods` | Grade periods of the class for a year | bearer | `studentId`; `schoolYear`; `classId` (dnevnik66: MUST be passed) | {periods[{id,name}]}; names include «Текущая неделя», «Итоговые оценки», then «1 четверть»… or «1 Полугодие»…; the chosen periodId decides which table /api/estimate returns | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/GradesApi.kt` `GradesApi.getPeriods` | legacy |
| GET | `https://dnevnik.egov66.ru/api/estimate/subjects` | Subjects of the class for a year | bearer | `studentId`; `schoolYear`; `classId` | {subjects[{id,name}]}; all subjects = 00000000-0000-0000-0000-000000000000 | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/GradesApi.kt` `GradesApi.getSubjects` | legacy |
| GET | `https://dnevnik.egov66.ru/api/estimate/years` | School years available to the student and the current one | bearer | `studentId` | {currentYear{id,text}, schoolYears[{id,text}]}; id is the starting calendar year ('2025'), text '2025/2026' | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/GradesApi.kt` `GradesApi.getYears` | legacy |
| GET | `https://dnevnik.egov66.ru/api/homework` | Homework for one day | bearer | `studentId`; `date?=2025-05-12` (yyyy-MM-dd; omitted = nearest day (jrn and dnevnik66 omit it; Egov66Client always sends it)) | {date, pagination{nextDate, previousDate}, homeworks[{id,isDone,lessonName,lessonNumber,startTime,endTime,lessonId,description,isHomeworkElectronicForm,homeWorkFiles[{id,name,size,type}],individualHomeworkDescription,isIndividualHomeworkElectronicForm,individualHomeWorkFiles[]}]}; paging goes day to day via nextDate/previousDate | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/HomeWorkApi.kt` `HomeWorkApi.getHomeWork` | legacy |
| POST | `https://dnevnik.egov66.ru/api/homework/done` | Mark a homework as done or not done | bearer | body (application/json): `homeworkId`, `isDone` boolean, `studentId` | body ignored by the client | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/HomeWorkApi.kt` `HomeWorkApi.doneHomeWork` | legacy |
| GET | `https://dnevnik.egov66.ru/api/schedule` | Week timetable for a student | bearer | `studentId` (GUID from /api/students); `date?=2025-05-12` (any day of the wanted week (jrn, dnevnik66)); `pageNumber?` (week number; Egov66Client pages with scheduleModel.weekNumber ± 1) | {schoolYear, paginationData{pageSize,pageNumber,totalCount,pageActionLink,totalPages,hasPreviousPage,hasNextPage,pageNumberOutOfRange}, scheduleModel{beginDate,endDate,weekNumber,days[{date,isWeekend,isCelebration,dayOfWeekName,scheduleDayLessonModels[{id,lessonid,lessonName,groupName,room,number,beginHour,beginMinute,endHour,endMinute}]}]}}; times can be null | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/ScheduleApi.kt` `ScheduleApi.getSchedule / getScheduleOnCurrentWeek` | legacy |
| GET | `https://dnevnik.egov66.ru/api/students` | Current user and the students (children) available to the account; source of studentId | bearer |  | {isParent, currentUser{firstName,lastName,surName}, students[{id, firstName, lastName, surName, className, orgName, avatarId}]}; clients take students[0].id | v228a/Egov66Client `app/src/main/java/com/vovka/egov66client/data/source/StudentApi.kt` `StudentApi.getStudents` | legacy |
| POST | `https://{instance}.ecp.egov66.ru/livewire/message/schedule-group-grid` | Livewire call on the group grid: set(group), addWeek, minusWeek | cookie | body (application/json): `fingerprint` from initial data, `serverMemo` current memo, checksum/htmlHash updated after each call, `updates` [{type:'callMethod', payload:{id:<4 random chars>, method:'set'\|'addWeek'\|'minusWeek', params:[…]}}] | diff {serverMemo{data{…events}, checksum, htmlHash}}; no 'events' key = no timetable | vyalkov-2002/ecp.egov66.ru-timetable `egov66_timetable/client.py` `Client._call_livewire_method` | legacy |
| POST | `https://{instance}.ecp.egov66.ru/livewire/message/schedule-teacher-grid` | Livewire call on the teacher grid: set(teacher UUID), addWeek, minusWeek | cookie | body (application/json): `fingerprint`, `serverMemo`, `updates` |  | vyalkov-2002/ecp.egov66.ru-timetable `egov66_timetable/client.py` `TeacherClient` | legacy |
| GET | `https://{instance}.ecp.egov66.ru/schedule/groups` | College group timetable page; yields CSRF token and Livewire initial data (serverMemo.data.events) | cookie |  | HTML; <meta name=csrf-token>; div[wire:initial-data] JSON {fingerprint, serverMemo{checksum,htmlHash,data{group,teacher,addNumWeek,minusNumWeek,events{cell:[{id,classroom,group,place,discipline,comment,teachers,dayWeekNum,numberPair}]}}}} | vyalkov-2002/ecp.egov66.ru-timetable `egov66_timetable/client.py` `Client._fetch_initial_data` | legacy |
| GET | `https://{instance}.ecp.egov66.ru/schedule/teachers` | College teacher timetable page (CSRF + Livewire initial data) | cookie |  | as /schedule/groups; serverMemo.data.teacher is a UUID | vyalkov-2002/ecp.egov66.ru-timetable `egov66_timetable/client.py` `TeacherClient` | legacy |

**Formats.**

- *Dates*: Query dates yyyy-MM-dd; response dates ISO 8601 with time and offset (yyyy-MM-dd'T'HH:mm:ss.SSSXXX, clients split on 'T'); lesson times as separate beginHour/beginMinute/endHour/endMinute integers (nullable)
- *Ids*: GUIDs for studentId, classId, periodId, subjectId, lesson and homework ids; all-subjects subjectId = 00000000-0000-0000-0000-000000000000; schoolYear id is the starting year as a string ('2025' for 2025/2026). ЕЦП timetable ids are UUID4
- *Pagination*: Schedule and week grades: paginationData{pageSize,pageNumber,totalCount,totalPages,hasPreviousPage,hasNextPage,pageNumberOutOfRange} with pageNumber/weekNumber = week of the school year; homework: pagination{nextDate, previousDate}; ЕЦП: Livewire addWeek/minusWeek
- *Notes*: Grades come as arrays of arrays of strings; presence marks (Н/У/Б) appear in 'presence'

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Свердловская область | `dnevnik.egov66.ru` | single regional diary host for all schools; API under /api; switched off 1 Sep 2026 |
| Свердловская область | `jurnal.egov66.ru` | teacher journal; no client; switched off |
| Свердловская область | `lk.ecp.egov66.ru` | ЕЦП cabinet for СПО: registration and sign-in (via Госуслуги) |
| Свердловская область | `t2.ecp.egov66.ru` | ГАПОУ СО «АМТ» (Алапаевский многопрофильный техникум); shutdown notice |
| Свердловская область | `t5.ecp.egov66.ru` | college cabinet (search result) |
| Свердловская область | `t8.ecp.egov66.ru` | «Личный кабинет ПРОФИ»; shutdown notice |
| Свердловская область | `t23.ecp.egov66.ru` | college cabinet (search result) |
| Свердловская область | `t26.ecp.egov66.ru` | ecp.egov66.ru-timetable test fixture (storage tenant_tandem_lk_100_2); library showcase is Ирбитский аграрно-технологический техникум; shutdown notice |
| Свердловская область | `t40.ecp.egov66.ru` | college cabinet; search title already «Госуслуги Моя школа» |
| Свердловская область | `t81.ecp.egov66.ru` | college cabinet (search result) |
| Свердловская область | `t92.ecp.egov66.ru` | ГАПОУ СО «УГК им. И.И. Ползунова»; shutdown notice |
| Свердловская область | `priem.egov66.ru` | «Личный кабинет абитуриента ГИС СО «ЕЦП»» (/guest, /guest/entrant-lists/{id}); admissions, no diary routes |
| Свердловская область | `edu.egov66.ru` | «Моё образование» / profession navigator; no diary routes |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/v228a/Egov66Client | 2025-08-29 | Retrofit interfaces (base https://dnevnik.egov66.ru/ in core/Constants.kt) for students, schedule, homework, homework/done, lesson/homework/files, estimate, estimate/years\|periods\|subjects, classes; DTOs; WebView ЕСИА login reading localStorage Auth__token; README naming ООО «Априкод» |
| https://github.com/yanaydenov/dnevnik66 | 2024-10-16 to 2026-08-28 (unshallowed) | httpx client (DNEVNIK_API_URL https://dnevnik.egov66.ru/api) with the token refresh endpoint, JWT exp handling, 401 retry, period semantics, guessed file-download paths, tests with response samples; git history shows the same /api paths in the 2025 dnevnikc.py (requests); static/loginPage.html (2026-08-27) still asks for an F12 copy of Auth__refresh/Auth__token |
| https://github.com/frogfile/jrn | 2024-10-14 | SvelteKit frontend: fetch base https://dnevnik.egov66.ru/api (src/lib/api.ts:95); /api/students, /api/schedule?date, /api/homework?date, /api/estimate?date; Authorization plus Cookie Aiss2Auth |
| https://github.com/Dreamlord4k/dnevnik.egov66.ru-parser | 2025-04-09 | Selenium ЕСИА login with TOTP, HTML scraping of the grades page; no API routes |
| https://github.com/sos-mislom/egov66-extention | 2025-11-28 | localStorage keys Auth__token, Auth__refresh, childId |
| https://github.com/vyalkov-2002/ecp.egov66.ru-timetable | 2026-04-14 | ЕЦП cabinet Livewire timetable protocol, cookies edinyi_lk_session/remember_web, CSRF, per-college instances; test fixture host t26.ecp.egov66.ru (tenant_tandem_lk_100_2) |
| https://github.com/vyalkov-2002/egov66-timetable-chatbot | 2026-04-12 | only consumes the egov66_timetable library; no routes of its own |
| https://github.com/KrapivinAndrey/AliceDiarySve | 2023-12-17 | verifier-added source («Дневник ученика Свердловской области», an Alice skill): an empty skeleton (alice/__init__.py, CI files), no HTTP calls or routes |
| https://dnevnik.egov66.ru/modules |  | operator shutdown notice: «Работа в модулях «Журнал» и «Электронный дневник» ГИС СО «ЕЦП» завершена»; from 1 September 2026 «Госуслуги Моя школа» (RuStore, Google Play, AppGallery, App Store links) |
| https://jurnal.egov66.ru/ |  | same shutdown notice on the teacher journal host; no login or journal paths |
| https://dnevnik.egov66.ru/ |  | WebFetch 2026-09-24: «Дневник недоступен», «Работа в модулях … завершена», «С 1 сентября 2026 года» → «Госуслуги Моя школа» (ru.gosuslugi.school store links); no sign-in button |
| https://t92.ecp.egov66.ru/login; https://t2.ecp.egov66.ru/login; https://t8.ecp.egov66.ru/login; https://t26.ecp.egov66.ru/schedule/groups |  | WebFetch 2026-09-24: all four serve only the «Госуслуги Моя школа» notice, no login form or timetable |
| https://lk.ecp.egov66.ru/ |  | HTTP 503 to WebFetch on 2026-09-24 |
| https://www.uksap.ru/student/study/on-line-study/lk_ecp/ |  | college page: cabinet at lk.ecp.egov66.ru, «Регистрация в Личном кабинете осуществляется через портал ГосУслуг» |
| https://altlinux.space/acme-corp/ecp.egov66.ru-timetable/commits/branch/master |  | Anubis bot-check page only; unreadable |
| WebSearch: site:ecp.egov66.ru login; "ecp.egov66.ru" техникум личный кабинет; ".ecp.egov66.ru/login" техникум Свердловская |  | hosts lk, t2, t5, t8, t23, t40, t81, t92 .ecp.egov66.ru, priem.egov66.ru, edu.egov66.ru; sgo-egov66.ru (a different platform) |
| https://github.com/search?type=repositories&q=egov66 |  | six repositories: the four candidates plus two vyalkov-2002 mirrors |
| https://github.com/search?type=repositories&q=dnevnik66 |  | yanaydenov/dnevnik66 |
| https://github.com/search?type=repositories&q=ecp+egov66 |  | only vyalkov-2002/ecp.egov66.ru-timetable |
| https://github.com/search?type=repositories&q=dnevnik.egov66.ru |  | frogfile/jrn, Dreamlord4k/dnevnik.egov66.ru-parser (nothing new) |
| https://github.com/search?type=repositories&q=jurnal.egov66 |  | no repositories |
| https://github.com/search?type=repositories&q=дневник+свердловской |  | KrapivinAndrey/AliceDiarySve |
| https://sourcegraph.com/.api/search/stream (egov66.ru, Aiss2Auth, Auth__refresh, estimate/periods, ecp.egov66) |  | nothing relevant (only infoculture/govdomains listing the domain) |
| https://pypi.org/pypi/{egov66,dnevnik66,ecp.egov66.ru-timetable,egov66_timetable}/json and registry.npmjs.org |  | all 404; no published packages |
| https://www.mou-sh11.ru/ob-yavleniya/1195-o-rabote-elektronnogo-dnevnika-sverdlovskoj-oblasti |  | «с 01 июля 2024 года вход в электронный дневник будет осуществляться только через Единый портал государственных услуг» |
| https://xn--2-7sbirdczi9n.xn--80acgfbsl1azdqr.xn--p1ai/?section_id=39 |  | school notice: from 2026/2027 progress is reported through ТОР, via the app «Госуслуги. Моя школа» |
| WebSearch: dnevnik.egov66.ru / ЕЦП «Моя школа» 2026; "dnevnik.egov66.ru/api" github; dnevnik.egov66.ru api swagger OR документация API |  | search summaries: from 1 September 2026 schools, colleges and technical schools of Свердловская область use «Госуслуги Моя школа»; no API documentation anywhere |

**Caveats.**

- Legacy confirmed by the operator: dnevnik.egov66.ru (and /modules) and jurnal.egov66.ru read «Работа в модулях «Журнал» и «Электронный дневник» ГИС СО «ЕЦП» завершена»; from 1 September 2026 all educational organisations of Свердловская область use «Госуслуги Моя школа» (ТОР «Моя школа»). Every route here is legacy or, for the five file-download rows (four never-run guesses in dnevnik66 and Egov66Client's parameterless stub), uncertain and unverifiable.
- The *.ecp.egov66.ru (СПО) Livewire routes are now legacy too: t2, t8, t92 /login and t26 /schedule/groups serve only the «Госуслуги Моя школа» notice «Доступ по текущей ссылке в региональный электронный дневник осуществляться не будет» (WebFetch 2026-09-24).
- There was only ever one API generation: dnevnik66's January 2025 dnevnikc.py and its August 2026 dnevnik_client.py call the same /api paths; the API still answered in late August 2026 (dnevnik66 commit 5dcc7e7 added a summer TEST_SCHEDULE_DATE fallback).
- All routes are reverse-engineered from small hobby clients; there is no official public API or documentation (search found none; egov66.ru/information_systems/e_education returned 503).
- No client touches jurnal.egov66.ru (teacher journal); its API is unknown.
- The four file-download paths in yanaydenov/dnevnik66 are guesses that never ran (download_file references an undefined self.api_url); Egov66Client's /api/lesson/homework/files declares no file id and its caller is empty.
- /api/auth/Token/Refresh is seen only in yanaydenov/dnevnik66. Its refresher.py indexes the tuple refresh_tokens() returns as a dict, so the background refresh loop raises and only the 401-retry path refreshes in practice.
- No sign-in API: Госуслуги (ЕСИА) sign-in in a browser was the only way in from 1 July 2024; clients paste tokens or drive a WebView/Selenium. Unchanged to the shutdown.
- Response shapes come from client DTOs, not captured traffic; dnevnik66 tolerates alternative keys (years vs schoolYears, fileName vs name).
- Dreamlord4k/dnevnik.egov66.ru-parser scrapes HTML with hashed CSS class names, which break on every front-end build.
- The successor «Госуслуги Моя школа» (ru.gosuslugi.school) is a different platform; its API belongs under the ТОР «Моя школа» kind, not here. sgo-egov66.ru («Сетевой город Нижний Тагил») is also a different platform (Сетевой город. Образование), not an ЕЦП host.
- The canonical repository of the ЕЦП timetable library is altlinux.space/acme-corp/ecp.egov66.ru-timetable (GitHub is a mirror); it sits behind Anubis and its newer commits could not be read.
