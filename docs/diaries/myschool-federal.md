# ФГИС «Моя школа» and ТОР «Моя школа» — the federal systems

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted, checked against the code, and dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**Two federal things share the name, and neither is the app in the screenshot.** ФГИС «Моя
школа» (`myschool.edu.ru`) is a federal portal — a library, tests, Сферум — that pulls marks and
timetables *from* the regional systems; its only published API is the server-to-server contract
by which it calls a regional system's `/Schedule/{ЕСИА id}`. ТОР «Моя школа» is the federal
«типовое облачное решение»: a journal and diary of its own, which a first wave of regions adopts
from 1 September 2026 and which families reach only through «Госуслуги Моя школа» — the app
`ru.gosuslugi.school` and `www.gosuslugi.ru/school`.

**For a client author the answer is short: there is no open client of ТОР, and nothing shows how
its session is carried.** No repository, package, capture or document read here calls it. The
one client whose name says FGIS, `fgis-dnevnik-desktop` (sixteen commits written on one day in
September 2026), in fact calls the МЭШ family API on regional «Моя школа» nodes; its rows are
kept because they show that pattern, and forty-three rows an extractor attached to this
platform were moved back to [«Моя школа»](mesh-myschool.md) and [МЭШ](mesh-moscow.md), where
they belong.

**Why it matters anyway.** Most regions that move to ТОР in 2026/27 move *off* a platform that
has open clients — Свердловская's ЕЦП, Оренбургская's «Цифровое образование», Липецкая's ЭлЖур
among them; Брянская leaves «Виртуальная школа», which had none. The full list is in
[the regions](regions.md). For all of them, as things stand, the diary becomes one with no known
route at all.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| web | `https://www.gosuslugi.ru/school` | Current (2026/27) web entry of the ТОР «Моя школа» diary, the web version of the app «Госуслуги Моя школа». One host for every ТОР region, no per-region host. Answers WebFetch only with a JS loading shell; no client or document shows its API |
| web | `https://edu.gosuslugi.ru` | Linked as «Веб-версия» of «Госуслуги Моя школа» by a school of the Еврейская АО (school-11eao, 01.09.2026); HTTP 503 to WebFetch; nothing else known |
| auth | `https://esia.gosuslugi.ru` | ЕСИА. The only sign-in of ФГИС «Моя школа» (official FAQ) and of «Госуслуги Моя школа» (official instruction, Dec 2025); also reached by redirect from {region_domain}/v3/auth/esia/login on МЭШ nodes |
| web | `https://myschool.edu.ru` | Federal portal ФГИС «Моя школа» (library ЦОК, tests, Сферум); not a diary host: marks and schedules are pulled from regional systems. The desktop client lists it as region 99, but its author moved the default off it within minutes of the first commit; no client confirms that it answers /api/family/web/v1 or /v3/auth/* |
| web | `https://users-management.myschool.edu.ru` | «Единая точка доступа» of ФГИС «Моя школа», named in the official FAQ (myschool.eduprosvet.ru/support/faq/); ЕСИА-only |
| api | `https://{region_domain}` | A МЭШ-platform regional node — ONLY these, per the МЭШ help page «Авторизация для учащихся и родителей в регионах»: authedu.mosreg.ru, education.admoblkaluga.ru, ms-edu.tatar.ru, myschool.05edu.ru, myschool.72to.ru, school.yanao.ru, karelia.minedu.ru, dnevnik.edu35.ru, dnevnik.edurb.ru, dnevnik.tvobr.ru, edu.mso51.ru (and school.mos.ru for Москва). Hosts /api/family/web/v1, /api/ej/rating/v1, /v3/auth/*. The desktop client's routes were fitted against dnevnik.edurb.ru. It is NOT a host of the ТОР regions |
| mobile_api | `https://{region_domain}/api/family/mobile` | Mobile family API base of the МЭШ regional app (ru.mes.dnevnik / .fgis flavour). The app strings pair it with ms-edu.tatar.ru, myschool.05edu.ru, myschool.72to.ru, education.admoblkaluga.ru and authedu.mosreg.ru. Route list: see the mesh_myschool entry |
| other | `https://myschool.eduprosvet.ru` | Methodical/help site of ФГИС «Моя школа» (FAQ, instructions, the integration PDF); one Краснодарский край school links it for the teacher side of ТОР |
| other | `https://school.gosuslugi.ru` | Named in the task; referenced by no client or document that was read |
| other | `https://lk.myschool.edu.ru` | Named in the task and in the vendors survey; referenced by no client |
| other | `[Базовый_URL] of a regional ЭЖД system` | Server to server: FGIS calls the regional system's /Schedule/{esia id} (documented contract, 2021–2022) |

**Signing in.**

*ТОР «Моя школа» / «Госуслуги Моя школа» (official user flow, app ru.gosuslugi.school and www.gosuslugi.ru/school)* — token carried as unknown — no client, capture or document shows how the app or the web version carries its session. Lifetime: unknown. Refresh: unknown.

1. Parent: have a confirmed (подтверждённая) Госуслуги account; add the child in «Документы» → «Семья и дети» (карточка ребёнка)
2. Child under 14: the parent creates the child's Госуслуги account there; child 14+: registers and confirms their own account, and the parent presses «Привязать» on the child's card
3. Open the web version (www.gosuslugi.ru/school) or the app «Госуслуги Моя школа» and sign in with the Госуслуги login and password (ЕСИА)
4. First sign-in: choose the child (parent) or, for pupils over 18, first choose the role «Ученик»
5. Give consent to personal data processing, to receiving progress data and to content access: parent for under 14, parent or child for 14–18, the pupil alone for 18+
6. Choose the region in which the school is («Выберите регион, в котором находится школа»)
7. Alternative entry named by schools: the «Дневник» widget in «Сферум» (MAX tab)

   Source: «Как начать пользоваться сервисом «Госуслуги Моя школа»» (myschool.eduprosvet.ru, PDF created 2025-12-02) and school/regional notices of July–September 2026. No open-source client exists; the API behind gosuslugi.ru/school was not observed

*ФГИС «Моя школа» portal (myschool.edu.ru, users-management.myschool.edu.ru)* — token carried as unknown (browser session after ЕСИА).

1. Open myschool.edu.ru or https://users-management.myschool.edu.ru/
2. Sign in through ЕСИА (Госуслуги) — the only method («Авторизация во ФГИС «Моя школа» осуществляется только через ЕСИА»)
3. Password changes happen in the Госуслуги account, not in «Моя школа»

   Official FAQ myschool.eduprosvet.ru/support/faq/, fetched 2026-09-24. The FGIS does not replace regional journals; their data is «автоматически транслироваться» into its interface

*Desktop client (fgis-dnevnik-desktop): ЕСИА on a МЭШ-platform regional node, token captured from the aupd_token cookie* — token carried as header Authorization: Bearer <aupd_token> AND cookie aupd_token=<token>; aupd_current_role=2:1. Refresh: None; sign in again. A manual paste of a Bearer JWT is the fallback outside Electron.

1. Choose the region's domain from the built-in list (84 entries; myschool.edu.ru for the federal node, region 99)
2. Open https://{region_domain}/v3/auth/esia/login in a modal Electron window (Chrome 130 UA, fresh persist:auth_<timestamp> partition); region 77 opens /v3/auth/sudir/login instead
3. The node redirects to esia.gosuslugi.ru; the user signs in to Госуслуги (SMS/2FA and captcha are handled by ЕСИА in the browser)
4. ЕСИА redirects back to the node, which sets cookie aupd_token (JWT)
5. The client takes aupd_token from a Set-Cookie response header, from ?aupd_token=/token=/access_token= in the URL (value >20 chars), or from the cookie jar (aupd_token, or any cookie on the node domain whose name contains 'token' and whose value is >30 chars)
6. Save {token, regionDomain} via the save-session IPC to userData/user_session.json (%APPDATA%/fgis-dnevnik-desktop on Windows) and a copy in <cwd>/user_session.json — before any API call
7. GET /api/family/web/v1/profile to read student_id (child.id) and personId (child.contingent_guid); a failed profile call does not reject the token

   x-mes-subsystem: familyweb on every call. The README also lists a Profile-Id header, which the code never sends, and says the captured cookie is auth_token, while the code reads aupd_token. The routes were fitted against dnevnik.edurb.ru (Башкортостан), which the code keeps as default region, with a hard-coded student_id 53373; region 99 (myschool.edu.ru) is never shown to work. The first commit sent UA 'ru.mes.dnevnik.fgis/3.87.7 (Android)'; 'api fix' replaced it with desktop Chrome

*mos.ru СУДИР (desktop client, region 77 only — Moscow МЭШ, not FGIS)* — token carried as same as the ЕСИА flow.

1. Open https://school.mos.ru/v3/auth/sudir/login in the auth window
2. Sign in to mos.ru
3. Capture aupd_token as above

   Kept because the desktop client offers it; the flow itself belongs to mesh_moscow

*ЕСИА with app redirect and code exchange on a МЭШ regional node (МЭШ regional app, incl. the ru.mes.dnevnik.fgis flavour)* — token carried as aupd_token JWT; on МЭШ nodes sent as Authorization: Bearer and/or auth-token (see mesh_myschool). Refresh: /v2/token/refresh or /v3/token/refresh (strings only).

1. GET https://{region_domain}/v3/auth/esia/login?redirect_url=<app scheme>://authRegionRedirect&state=<uuid> in an in-app browser
2. After ЕСИА, the node redirects to <app scheme>://authRegionRedirect?code=… (the app strings carry both dnevnik-mes:// and dnevnik-myschool://)
3. GET https://{region_domain}/v3/auth/token?code=<code>&state=<uuid> returns the token (the saga steps authRegion->UPDATE_CODE, TRIGGER_GET_AUPD_TOKEN, UPDATE_AUPD_TOKEN)
4. Refresh: the app strings carry /v2/token/refresh and /v3/token/refresh (saga authRegionRefresh); method and host not visible in strings

   Only verified in code against authedu.mosreg.ru (OctoDiary-kt, now in mesh_myschool). The app's region table knows Tatarstan, Tyumen, Dagestan, Kaluga, Chechnya and constants YANAO/CHECHNYA with regionIdToClientIdMap and client ids fgismobile, fgismobile_89, fgismobile_95 — so the FGIS flavour selects a per-region client id (89 ЯНАО, 95 Чечня). No host for myschool.edu.ru or gosuslugi.ru occurs in the app

*FGIS → regional system (server to server, documented)* — token carried as OAuth 2.0 bearer (assumed standard Authorization header; the document does not name it).

1. FGIS obtains a marker from the regional system by OAuth 2.0 grant_type=client_credentials (not described in the document; skipped while the marker is still valid)
2. FGIS calls GET [Базовый_URL]/Schedule/<ЕСИА id>?start=&end=

   Not usable by end-user clients; describes how regional diaries feed FGIS

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Authorization` | Bearer <aupd_token> | family/web and ej/rating calls in the desktop client |
| `Cookie` | aupd_token=<token>; aupd_current_role=2:1; | The desktop client sends it with every call and file download; aupd_current_role=2:1 selects the student/parent role |
| `x-mes-subsystem` | familyweb | Sent by the desktop client on every call; the МЭШ app strings also carry fgismobile, fgismobile_89 and fgismobile_95, the FGIS flavour's per-region client ids (regionIdToClientIdMap) |
| `User-Agent` | Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36 | The desktop client presents itself as desktop Chrome, both on API calls and in the ЕСИА window |

**Captcha and second factor.** Handled by ЕСИА (Госуслуги) in a real browser or the Госуслуги app; confirmed Госуслуги account required for «Госуслуги Моя школа»; no client automates it

Two different things share the name. (1) ТОР «Моя школа», used from July–September 2026 in the first-wave regions, is reached only through «Госуслуги Моя школа» (www.gosuslugi.ru/school, ru.gosuslugi.school, Сферум widget) with ЕСИА; its API is unknown. (2) The МЭШ-platform regional «Моя школа» nodes (11 regions + Москва) speak the МЭШ family API with the aupd_token; that is what fgis-dnevnik-desktop calls. Mosreg-only flows (kauth login/password, Selenium auth_token grab) and their headers (auth-token, X-Mes-Role, Client-Type, Profile-Id/Profile-Type, partner-source-id) were moved to the mesh_myschool entry.

**Routes** — 23 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `[Базовый_URL] of the regional electronic journal (ЭЖД) system/Schedule/{esia_account_id}` | Documented FGIS «Моя школа» integration contract: FGIS (the caller) pulls a pupil's or teacher's schedule and diary from the regional ЭЖД system, server to server, to show it in the FGIS interface | bearer | `{esia_account_id}` идентификатор учётной записи ЕСИА, e.g. 1563978; `start?=2021-12-20` (optional; if one of start/end is given, both must be; default period is previous + current + next week); `end?=2021-12-21` | 200: resource {resourceType:'Schedule', id, meta{lastUpdated}, school[{id (uuid), name, scheduleUrl, period{start,end}, academYear '2021/22', distantUrl?, class[{class '8б', grade '8', lesson[{id (uuid), subject, insteadOf?, topic?, homework?, period{start,end ISO 8601 with offset}, classroom, distant?, note?, attendance{code 'н'\|'о'...}?, score[{code, weight?, score, score2?, scoreSystem}]}]}]}]. Error: HTTP status + {resourceType:'OperationOutcome', id, issue[{severity fatal\|error\|warning\|information, code e.g. not-found\|structure\|expired, details}]} Currency: the document dates from 2021–2022 and no newer version was found on myschool.eduprosvet.ru (instructions list, 2026-09-24); still the only published FGIS contract. It feeds the FGIS, not the ТОР journal, and nothing says whether ТОР regions still use it. | myschool.eduprosvet.ru/upload/iblock/633/39cm7zctwnwwds6xc6sptjt394rhvpie.pdf `Методические рекомендации для технических специалистов…, Приложение 5. REST API для запроса расписания и журнала` `Schedule / OperationOutcome` | current |
| GET | `https://users-management.myschool.edu.ru/` | «Единая точка доступа» of ФГИС «Моя школа» (portal sign-in, ЕСИА only) | other |  | Browser entry, not an API; named in the official FAQ. | myschool.eduprosvet.ru/support/faq/ `Часто задаваемые вопросы` `users-management.myschool.edu.ru` | current |
| GET | `https://www.gosuslugi.ru/school` | Web version of «Госуслуги Моя школа» — the ТОР «Моя школа» diary for pupils and parents from 2026/27 (single federal host) | other |  | HTML/JS loading shell («Госуслуги сейчас откроются»); ЕСИА sign-in with a confirmed account, then consent and «Выберите регион». No JSON API observed; no client exists. | myschool.eduprosvet.ru/upload/iblock/515/ifctp89btzs512iip1qh2asg2kbutc7l/Kak-nachat-polzovatsya-servisom-_Gosuslugi-Moya-shkola_.pdf `Как начать пользоваться сервисом «Госуслуги Моя школа» (2025-12-02) + regional notices (sh1-labinsk-r03.gosweb.gosuslugi.ru, uopavl.ru/item/2508069, uksap.ru 2026-07-31)` `web entry` | current |
| GET | `https://{region_domain}/api/ej/rating/v1/rank/class` | Class ranking on a date (anonymised) | bearer | `personId` (contingent_guid); `date=2026-09-24` | Array of {personId, imageId, rank{rankPlace, averageMarkFive, rankStatus, trend}} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getClassRating` | current |
| GET | `https://{region_domain}/api/ej/rating/v1/rank/rankShort` | History of the student's place in class between two dates | bearer | `personId`; `beginDate=2026-09-01`; `endDate=2026-09-24` | Array of {date, rankPlace} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getClassRating` | current |
| GET | `https://{region_domain}/api/ej/rating/v1/rank/subjects` | Student's rank per subject on a date | bearer | `personId`; `date` | Array of {subjectId, subjectName, rank{rankPlace, averageMarkFive}} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getClassRating` | current |
| GET | `https://{region_domain}/api/family/web/v1/homeworks` | Homework in a date range (client asks today −30 to +30 days) | bearer | `student_id`; `from=2026-08-25`; `to=2026-10-24` | payload[] {homework_entry_student_id, homework_id, subject_id, subject_name, date (due), date_assigned_on, description\|homework, is_done, materials[{uuid,id,type_name,title,action_name,urls[]\|url}], attachments[{id,name,size,link\|url\|download_url}]}; relative URLs are prefixed with https://{region_domain} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getHomeworks` | current |
| POST | `https://{region_domain}/api/family/web/v1/homeworks/{homework_entry_student_id}/done` | Mark a homework as done | bearer | `{homework_entry_student_id}` from homeworks payload; body (application/json): `(empty object {})` README claims {"is_done": true\|false}; the code sends {} | Only res.ok is read. Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `toggleHomeworkDone` | current |
| GET | `https://{region_domain}/api/family/web/v1/profile` | Signed-in user's profile and linked children; used to validate the token and to pick student_id and personId | bearer |  | Top level keys: profile{id,user_id,first_name,last_name,middle_name,school{short_name,name},class_name,contingent_guid\|person_id\|guid}, children[] (or profiles[]). child.id / student_id is the student_id used by family/web routes; child.contingent_guid (or person_id/guid) is the personId used by the rating routes. Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getProfile` | current |
| GET | `https://{region_domain}/api/family/web/v1/schedule` | Schedule of one day (client calls it six times, Monday–Saturday) | bearer | `student_id`; `date=2026-09-24` (YYYY-MM-DD) | activities[] filtered on type=='LESSON'; each has begin_time, end_time, room_number\|room_name, lesson{schedule_item_id, subject_name, teacher{last_name,first_name,middle_name}, marks[{id,value,weight,comment,control_form_name}], homework} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getWeekSchedule` | current |
| GET | `https://{region_domain}/api/family/web/v1/subject_marks` | Marks grouped by subject for the current period | bearer | `student_id` | payload[] {subject_id, subject_name, average\|average_by_all, periods[{value, marks[{id,value,weight,date,comment,control_form_name,is_exam}]}]} Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getSubjectsMarks` | current |
| GET | `https://{region_domain}/v3/auth/esia/login` | Start of the ЕСИА (Госуслуги) sign-in on a regional node or myschool.edu.ru; opened in an embedded browser window, the node redirects to esia.gosuslugi.ru and back, then sets cookie aupd_token | none |  | Browser flow, not JSON. Client captures aupd_token from a Set-Cookie header, from the cookie jar, or from ?aupd_token=/token=/access_token= in a redirect URL (value longer than 20 chars). Currency: current МЭШ generation, valid on МЭШ-platform regional nodes only (fitted against dnevnik.edurb.ru), not on myschool.edu.ru or gosuslugi.ru/school. | SolomonNumb1/fgis-dnevnik-desktop `src/components/LoginModal.tsx + electron/main.ts` `handleGosuslugiLogin / open-esia-login` | current |
| GET | `https://{region_domain}/v3/auth/sudir/login` | Start of the mos.ru СУДИР sign-in, used only for region 77 (school.mos.ru) | none |  | Browser flow; same aupd_token capture as the ЕСИА path. Moscow (school.mos.ru) only; the СУДИР flow is mesh_moscow's. | SolomonNumb1/fgis-dnevnik-desktop `src/components/LoginModal.tsx` `handleGosuslugiLogin` | current |
| GET | `https://edu.gosuslugi.ru/` | Web version link of «Госуслуги Моя школа» given by a Еврейская АО school | other |  | HTTP 503 to WebFetch (2026-09-24); nothing else known; possibly an alias of www.gosuslugi.ru/school. | school-11eao.ucoz.ru/news/prilozhenie_gosuslugi_moja_shkola_edinnyj_cifrovoj_pomoshhnikom_dlja_shkolnikov_i_roditelej/2026-09-01-474 `school news, 01.09.2026` `«Веб-версия доступна по ссылке: https://edu.gosuslugi.ru/»` | uncertain |
| GET | `https://{region_domain}/api/family/web/v1/attendance` | Absences and late arrivals | bearer | `student_id` | attendance[]\|items[] {id,date,status ABSENT\|LATE,reason\|comment,missed_lessons_count}; tried first, visits is the fallback Fallback guess of the desktop client; no other client carries it on a regional node. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getAttendance` | uncertain |
| GET | `https://{region_domain}/api/family/web/v1/food/meals` | School meals account (tried first) | bearer | `student_id` | Client expects balance, account_number\|client_id, transactions[{id,name\|dish_name,price\|amount,date,time}], is_buffet, status_note; falls back to a static 'organised hot meals' card Fallback guess of the desktop client; no other client carries it on a regional node. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getMeals` | uncertain |
| DELETE | `https://{region_domain}/api/family/web/v1/homeworks/{homework_entry_student_id}/done` | Undo the done mark on a homework | bearer | `{homework_entry_student_id}` | No body; only res.ok is read. The DELETE undo on family/web is seen only in this client (mesh_moscow lists only the POST for family/web; the DELETE exists on family/mobile). | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `toggleHomeworkDone` | uncertain |
| GET | `https://{region_domain}/api/family/web/v1/visits` | Turnstile (СКУД) entries and exits | bearer | `student_id` | visits[]\|items[] {id,date,enter_time\|in_time,exit_time\|out_time} Fallback guess of the desktop client; no other client carries it on a regional node. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getAttendance` | uncertain |
| GET | `https://{region_domain}/api/food/meals/v3/client/{student_id}` | School meals account (fallback) | bearer | `{student_id}` | Same expected shape as food/meals Fallback guess of the desktop client; no other client carries it on a regional node. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getMeals` | uncertain |
| GET | `https://{region_domain}/api/materials/v1/{uuid}` | Resolve a homework learning material (ЦОК/МЭШ Библиотека) to a launch URL | bearer | `{uuid}` materials[].uuid from homeworks | url \| urls.launch_url \| id; fallback built by the client: https://uchebnik.mos.ru/material/{id\|uuid}. The path appears in no other source: the МЭШ app strings use /api/family/materials/v1 and /api/materials/launcher/v1/launch?activity_url=, so this row is probably the desktop client's guess. Fallback guess of the desktop client; no other client carries it on a regional node. | SolomonNumb1/fgis-dnevnik-desktop `src/services/api.ts` `getMaterialUrl / getHomeworks` | uncertain |
| GET | `https://{region_domain}/v2/token/refresh` | Refresh the aupd_token on a МЭШ regional node (string in the МЭШ app; saga authRegionRefresh) | bearer |  | Method not visible in the strings; OctoDiary-kt declares it as GET (never called). Paired in the app with /v3/token/refresh; which of the two is current is not visible. | AmetistYT/mesh_expressive `decompiled/dnevnik_strings.txt` `'/v2/token/refresh'` | uncertain |
| GET | `https://{region_domain}/v3/auth/token` | Exchange the code from the regional ЕСИА redirect (…/authRegionRedirect?code=) for the aupd_token — the МЭШ regional app's step, incl. the ru.mes.dnevnik.fgis flavour | none | `code`; `state` | App strings: '/v3/auth/token?code=', saga authRegion->UPDATE_CODE → TRIGGER_GET_AUPD_TOKEN → UPDATE_AUPD_TOKEN. Verified in code only on authedu.mosreg.ru (OctoDiary-kt, mesh_myschool), where it returns {token}. Not seen on myschool.edu.ru or any ТОР host. | AmetistYT/mesh_expressive `decompiled/dnevnik_strings.txt` `'/v3/auth/token?code=' / authRegionSaga` | uncertain |
| POST | `https://{region_domain}/v3/token/refresh` | Newer-generation token refresh string in the МЭШ app (saga authRegionRefresh) | bearer |  | Only the path string is visible; method assumed POST as in mesh_moscow's school.mos.ru row. Whether regional nodes answer it is unknown. | AmetistYT/mesh_expressive `decompiled/dnevnik_strings.txt` `'/v3/token/refresh'` | uncertain |

**Formats.**

- *Dates*: Query dates YYYY-MM-DD (date, from, to, beginDate, endDate, begin_date, end_date); times begin_time/end_time as strings; the integration contract uses ISO 8601 dateTime with an offset (2021-12-20T09:30:00+04:00) and academYear '2021/22'
- *Ids*: student_id is numeric (profile child.id); personId is a GUID (contingent_guid); homework_entry_student_id is numeric; material uuid; the integration contract keys users by numeric ЕСИА account id and uses uuid lesson and school ids
- *Pagination*: None; ranges are bounded by from/to dates. The desktop client calls schedule once per day; homework spans ±30 days
- *Notes*: family/web lists live under payload[]; schedule under activities[]; rating returns bare arrays; errors in the integration contract are OperationOutcome resources

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| РФ — ФГИС «Моя школа» (federal portal) | `myschool.edu.ru` | Portal, not a diary host; desktop region 99; no client shows the МЭШ family API on it |
| РФ — ФГИС «Моя школа» «Единая точка доступа» | `users-management.myschool.edu.ru` | Official FAQ |
| Краснодарский край (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: from July 2026 (families by 1 September 2026); replaces the regional app (sgo.rso23.ru). Evidence: sh1-labinsk-r03.gosweb.gosuslugi.ru; uopavl.ru/item/2508069 |
| Свердловская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026, «первой волны»; jurnal.egov66.ru / dnevnik.egov66.ru now «недоступен». Evidence: jurnal.egov66.ru; uksap.ru 2026-07-31 |
| Смоленская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; replaces dnevnik.admin-smolensk.ru. Evidence: dnevnik.admin-smolensk.ru notice |
| Чувашская Республика (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; replaces «Е-услуги» and «Сетевой город. Образование». Evidence: grani21.ru 05.08.2026; mosk.cap.ru 22.09.2026 |
| Ростовская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; replaces БАРС sh-open.ris61edu.ru. Evidence: school21.ucoz.com; ksosh.ucoz.net 13.08.2026 |
| Воронежская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026. Evidence: cro.edu-vrn.ru/archives/37787 |
| Астраханская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026. Evidence: astrakhan.su |
| Оренбургская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026. Evidence: eanews.ru 2026-09-01 |
| Брянская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026. Evidence: guberniya.tv/obrazovanie/339812 |
| Челябинская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; data in the app from 18 September 2026. Evidence: chel.aif.ru |
| Калининградская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026. Evidence: school9klgd.gosuslugi.ru/glavnoe/tor-moya-shkola/ |
| Ярославская область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; replaces the «Образование-76» app. Evidence: school.yarcloud.ru; ivan-shbor.edu.yar.ru |
| Республика Алтай (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026, all schools. Evidence: 1line.info 13.08.2026 |
| Еврейская автономная область (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; «Дневник.ру» прекратит работу; web link edu.gosuslugi.ru. Evidence: obrazovanie-eao.ru 09.09.2026 |
| Ненецкий автономный округ (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026; edu.adm-nao.ru now only points to ru.gosuslugi.school. Evidence: edu.adm-nao.ru |
| Донецкая Народная Республика (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026, pilot, gradual; replaces paper. Evidence: volnovaxa-r897.gosweb.gosuslugi.ru; yugrf.ru 03.09.2026 |
| Луганская Народная Республика (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: announced for 2026/27 (schools from July 2026); no post-September confirmation. Evidence: sh-novoe-pokolenie-lugansk-r181.gosweb.gosuslugi.ru |
| Кабардино-Балкарская Республика (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 2026/27; pupils and parents see the diary only in «Госуслуги Моя школа»; the БАРС journal server stays live. Evidence: school.07.edu.o7.com |
| Удмуртская Республика (ТОР «Моя школа») | `www.gosuslugi.ru/school` | ТОР region: 1 September 2026 (news quoting the ministry). Evidence: susanin.news 2026-08-11 |
| Еврейская автономная область (ТОР, web link) | `edu.gosuslugi.ru` | Web version link given by school-11eao (01.09.2026); 503 to WebFetch |
| Республика Коми («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app «Госуслуги. Моя школа» as a viewer over ГИС ЭО since March 2025; not ТОР |
| Республика Татарстан («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app used as a front end by 2025/26 beside the regional МЭШ node ms-edu.tatar.ru |
| Новосибирская область («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app used as a front end in 2025/26; the journal stays in school.nso.ru |
| Белгородская область («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app pilot 2025 (first stage from 19 February 2025) |
| Ивановская область («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app pilot from 24 March 2025 |
| Амурская область («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app for parents since 2025 |
| Ульяновская область («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | app promoted by the ministry in 2025/26; no ТОР switch found |
| Ямало-Ненецкий автономный округ («Госуслуги Моя школа» as a front end) | `www.gosuslugi.ru/school` | the federal app is the parents' client for the МЭШ-based school.yanao.ru since November 2025 |
| 50 Московская область — МЭШ-platform regional «Моя школа» node | `authedu.mosreg.ru` | МЭШ-platform node (official help page); front end myschool.mosreg.ru; family/mobile base per the МЭШ app |
| 50 Московская область (web) — МЭШ-platform regional «Моя школа» node | `myschool.mosreg.ru` | Front end; in the desktop table and the МЭШ app strings |
| 40 Калужская область — МЭШ-platform regional «Моя школа» node | `education.admoblkaluga.ru` | МЭШ-platform node (official help page); /api/family/mobile in the МЭШ app |
| 40 Калужская область (web) — МЭШ-platform regional «Моя школа» node | `dnevnik.admoblkaluga.ru` | In the МЭШ app strings and the desktop 7-region table (commit 0fd51c3) |
| 16 Республика Татарстан — МЭШ-platform regional «Моя школа» node | `ms-edu.tatar.ru` | МЭШ-platform node (official help page); /api/family/mobile in the МЭШ app; in the desktop 7-region table |
| 16 Республика Татарстан (web) — МЭШ-platform regional «Моя школа» node | `school-edu.tatar.ru` | МЭШ app strings |
| 05 Республика Дагестан — МЭШ-platform regional «Моя школа» node | `myschool.05edu.ru` | МЭШ-platform node (official help page); /api/family/mobile in the МЭШ app; in the desktop table from the first commit |
| 05 Республика Дагестан (web) — МЭШ-platform regional «Моя школа» node | `education.05edu.ru` | МЭШ app strings |
| 72 Тюменская область — МЭШ-platform regional «Моя школа» node | `myschool.72to.ru` | МЭШ-platform node (official help page); /api/family/mobile in the МЭШ app; in the desktop table from the first commit |
| 72 Тюменская область (web) — МЭШ-platform regional «Моя школа» node | `education.72to.ru` | МЭШ app strings |
| 89 Ямало-Ненецкий АО — МЭШ-platform regional «Моя школа» node | `school.yanao.ru` | МЭШ-platform node, entry https://school.yanao.ru/esia/ (official help page). The desktop's myschool.yanao.ru is NXDOMAIN |
| 10 Республика Карелия — МЭШ-platform regional «Моя школа» node | `karelia.minedu.ru` | МЭШ-platform node from 2026/27 (official help page). The desktop's myschool.gov.karelia.ru is unconfirmed |
| 35 Вологодская область — МЭШ-platform regional «Моя школа» node | `dnevnik.edu35.ru` | МЭШ-platform node from 1 September 2026 (official help page). The desktop's myschool.vologda-oblast.ru is unconfirmed |
| 02 Республика Башкортостан — МЭШ-platform regional «Моя школа» node | `dnevnik.edurb.ru` | МЭШ-platform node from 2026/27 (official help page). It is the desktop client's default region and the node its routes were fitted against (hard-coded student_id and a «ГО г. Октябрьский» school) — the earlier 'doubtful' note is withdrawn |
| 69 Тверская область — МЭШ-platform regional «Моя школа» node | `dnevnik.tvobr.ru` | МЭШ-platform node, entry https://dnevnik.tvobr.ru/esia/, phased from 2026/27 (official help page). The desktop's myschool.tverreg.ru is unconfirmed |
| 51 Мурманская область — МЭШ-platform regional «Моя школа» node | `edu.mso51.ru` | МЭШ-platform node from 1 September 2026 (official help page). The desktop's myschool.gov-murman.ru is unconfirmed |
| 77 г. Москва (МЭШ) — МЭШ-platform regional «Моя школа» node | `school.mos.ru` | Moscow МЭШ (mesh_moscow); desktop region 77 with the СУДИР login |
| 95 Чеченская Республика — МЭШ-platform regional «Моя школа» node | `(host not established)` | The МЭШ app has CHECHNYA/Chechnya in its region table and client id fgismobile_95; no host string found. The desktop's myschool.chechnya.gov.ru is unconfirmed |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/SolomonNumb1/fgis-dnevnik-desktop | 16 commits, all on 2026-09-13 (60b5a1d 14:34 initial commit … e08ce02 23:07 regions update); history read with git fetch --unshallow | The only client that targets FGIS «Моя школа»: an 84-region domain table, the ЕСИА/СУДИР login URLs and aupd_token capture (electron/main.ts), 16 routes in src/services/api.ts, headers. README says it was reverse-engineered from ru.mes.dnevnik.fgis. History: the first commit called other paths against myschool.edu.ru with an FGIS-app UA; «api fix» moved to family/web against dnevnik.edurb.ru; the 83-host table appeared only in «github prep» (2689f8b); the repository has no issues |
| https://myschool.eduprosvet.ru/upload/iblock/633/39cm7zctwnwwds6xc6sptjt394rhvpie.pdf |  | Official Методические рекомендации (53 pp.), Приложение 5: the REST contract GET [Базовый_URL]/Schedule/<ЕСИА id>?start&end, Schedule/OperationOutcome JSON, OAuth2 client_credentials |
| https://iro23.ru/wp-content/uploads/2022/11/Функциональные-возможности-ФГИС-Моя-школа-для-администраторов_051022.pdf |  | ФИЦТО webinar (2022-10-05): sign-in only through ЕСИА, school data comes from the regional system; only host myschool.edu.ru |
| https://github.com/AmetistYT/mesh_expressive | 2026-09-23 | Strings from the decompiled МЭШ app: ru.mes.dnevnik.fgis, ru.mes.sputnik.fgis, fgismobile(_89,_95), esia.gosuslugi.ru, regional /api/family/mobile hosts (tatar, 05edu, 72to, admoblkaluga, mosreg) |
| https://github.com/OctoDiary/OctoDiary-kt | 2026-05-13 | Regional ЕСИА code exchange (/v3/auth/esia/login, /v3/auth/token, /v2/token/refresh), kauth, family/mobile/v1 routes and headers for the МЭШ regional platform (authedu.mosreg.ru); api.md and the interfaces readme |
| https://raw.githubusercontent.com/OctoDiary/OctoDiary-kt/v2-develop/api.md |  | Route table for mos.ru and mosreg.ru. It does NOT mention myschool.edu.ru, although a web-search summary claimed it did |
| https://github.com/GGergy/PyMyschoolApi | 2024-05-07 | familyweb routes on authedu.mosreg.ru with the Auth-Token header; Selenium auth_token cookie grab; schedule/short |
| https://github.com/dotundrscr/myschool-mosreg-api | 2023-10-24 | README-only notes: Auth-Token and Authorization carry the same token; eventcalendar; lesson_schedule_items |
| https://github.com/xrmqd/myschool-API | 2023-09-18 | Empty stub; nothing |
| https://github.com/Mokichan/GisSolo-Opener-GSO- | 2025-10-15 | Opens e-school.obr.lenreg.ru and a gosweb.gosuslugi.ru school site in a browser; no API |
| https://github.com/markich-bissnes/gosuslugi-max-app | 2026-09-14 | Flutter WebView wrapper around a private IP; unrelated |
| https://www.rustore.ru/catalog/app/ru.gosuslugi.school |  | App «Госуслуги Моя школа» by Минцифры, v5.0.0.454, updated 2026-09-03; needs a verified Госуслуги account |
| https://www.gosuslugi.ru/school |  | Only a JS loading shell; no content |
| https://www.gosuslugi.ru/myschool |  | Only a JS loading shell |
| https://myschool.edu.ru/ |  | HTTP 503 to WebFetch |
| https://help-myschool.edu.ru/ |  | HTTP 503 to WebFetch |
| https://github.com/search?type=repositories&q=myschool.edu.ru |  | 0 results; 'fgis myschool' also 0; 'моя школа дневник' gave only SolomonNumb1/fgis-dnevnik-desktop; 'gosuslugi school' gave only gosuslugi-max-app |
| https://sourcegraph.com/.api/search/stream (myschool.edu.ru, gosuslugi.ru/school, ru.gosuslugi.school, ru.mes.dnevnik.fgis, lk.myschool.edu.ru, school.gosuslugi.ru, aupd_current_role) |  | 0 matches for every query (limited index) |
| https://github.com/VanyaSvetoslav/AutoEdu | 2026-04-28 | Telegram bot over authedu.mosreg.ru: family/web homeworks and subject_marks with Bearer + Profile-Id/Profile-Type; eventcalendar needs Auth-Token + x-mes-role + auth_token cookie; source_types list |
| https://github.com/TheGodfatherDog/MESH_Helper | 2026-09-18 | Teacher-side browser extension for authedu.mosreg.ru: POST /api/ej/acl/v1/sessions (teacherweb, x-mes-hostid/roleid 9) and /api/ej/core\|plan\|report/teacher/v1/* routes (teacher subsystem, not listed) |
| https://github.com/AmetistYT/mesh_expressive (decompiled/mesh_reverse_engineering_report.md) | 2026-09-23 | Moscow МЭШ service map (school.mos.ru/api/family/mobile/v1, ej/rating/v1, food/meals/v3, pass/entrances/v1, family/materials/v1); nothing FGIS-specific |
| https://github.com/search?type=repositories&q=authedu |  | 5 results; AutoEdu and MESH_Helper are the relevant ones |
| https://sourcegraph.com/.api/search/stream (authRegionRedirect, fgismobile, dnevnik-myschool, v3/auth/esia/login, familyweb, ms-edu.tatar.ru) |  | No relevant matches |
| https://myschool.eduprosvet.ru/support/faq/ |  | ФГИС «Моя школа» sign-in is ЕСИА only; users-management.myschool.edu.ru; FGIS does not replace regional journals (data is relayed) |
| https://myschool.eduprosvet.ru/data/instructions/ |  | Current instruction list (no API or ТОР technical documents; the integration contract PDF is not re-published) |
| https://myschool.eduprosvet.ru/upload/iblock/515/ifctp89btzs512iip1qh2asg2kbutc7l/Kak-nachat-polzovatsya-servisom-_Gosuslugi-Moya-shkola_.pdf | PDF created 2025-12-02 | Official «Госуслуги Моя школа» sign-in: confirmed Госуслуги account, child card and linking, age-based consent, role «Ученик» for 18+, then region choice |
| https://edu.gosuslugi.ru/ |  | HTTP 503 to WebFetch |
| https://www.gosuslugi.ru/school (re-fetched 2026-09-24) |  | JS loading shell only; no script or config URLs visible |
| https://github.com/search?type=repositories&q=gosuslugi+school / moya+shkola / gosuslugi+dnevnik / sferum |  | No ТОР or «Госуслуги Моя школа» client; only gosuslugi-max-app and Сферум message bots |
| https://github.com/SolomonNumb1/fgis-dnevnik-desktop/issues |  | No issues |
| checkpoints/vendors-and-market.json, verify_0/1.json, discover_2/3/5/6/7/8.json (earlier research of this run) |  | Official МЭШ help-page table of 11 regional «Моя школа» entry hosts; per-region evidence for the ТОР «Моя школа» first wave (2026/27) and for «Госуслуги Моя школа» used as a front end |
| https://github.com/AmetistYT/mesh_expressive (re-read for regions) |  | ERegionIds with YANAO and CHECHNYA, region names Tatarstan/Tyumen/Dagestan/Kaluga/Chechnya, regionIdToClientIdMap, authRegion saga steps (UPDATE_CODE, TRIGGER_GET_AUPD_TOKEN, UPDATE_AUPD_TOKEN), dnevnik-myschool:// scheme; still no myschool.edu.ru |

**Refuted during verification** — rows an extractor proposed that no source carries, kept here so that nobody re-proposes them.

- GET `/v3/auth/esia/login` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/v3/auth/token` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/v2/token/refresh` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/v3/auth/kauth/login` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- POST `/lms/api/sessions` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/v3/auth/kauth/callback` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/profile` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/marks` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/marks/{mark_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/subject_marks` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/subject_marks/short` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/homeworks` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- POST `/api/family/mobile/v1/homeworks/{homework_id}/done` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- DELETE `/api/family/mobile/v1/homeworks/{homework_id}/done` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/school_info` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/lesson_schedule_items/{lesson_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/eventcalendar/v1/api/events` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/ej/rating/v1/rank/class` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/mobile/v1/visits` — Belongs to mesh_moscow: OctoDiary/OctoDiary-kt calls it on school.mos.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/schedule/short` — Belongs to mesh_myschool: GGergy/PyMyschoolApi calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/lesson_schedule_items` — Belongs to mesh_myschool: dotundrscr/myschool-mosreg-api calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/profile` — Belongs to mesh_myschool: GGergy/PyMyschoolApi calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/schedule` — Belongs to mesh_myschool: GGergy/PyMyschoolApi calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/homeworks` — Belongs to mesh_myschool: GGergy/PyMyschoolApi calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/homeworks` — Belongs to mesh_myschool: VanyaSvetoslav/AutoEdu calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/family/web/v1/subject_marks` — Belongs to mesh_myschool: VanyaSvetoslav/AutoEdu calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/eventcalendar/v1/api/events` — Belongs to mesh_myschool: VanyaSvetoslav/AutoEdu calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/acl/api/users/profile_info` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on myschool.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/ej/rating/v1/rank/subjects` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/usersettings/v1` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- PUT `/api/usersettings/v1` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/ej/partners/v1/homeworks/launch` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/portfolio/app/persons/{person_id}/govexams/list/` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/avatarmanagement/v1/{person_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- POST `/api/avatarmanagement/v1/{person_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- DELETE `/api/avatarmanagement/v1/{person_id}/{image_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/avatars/{person_id}` — Belongs to mesh_myschool: OctoDiary/OctoDiary-kt calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- POST `/api/ej/acl/v1/sessions` — Belongs to mesh_myschool: TheGodfatherDog/MESH_Helper calls it on authedu.mosreg.ru; not ФГИС or ТОР «Моя школа». Already in that entry.
- GET `/api/ej/acl/v1/mod-acl/users/profile_info` — Only in fgis-dnevnik-desktop's initial commit 60b5a1d (default host myschool.edu.ru); replaced six minutes later in 'api fix' 262576e by /api/family/web/v1/*. An abandoned guess, not a legacy generation.
- GET `/api/eventcalendar/v1/api/events/schedule/` — Only in fgis-dnevnik-desktop's initial commit 60b5a1d (default host myschool.edu.ru); replaced six minutes later in 'api fix' 262576e by /api/family/web/v1/*. An abandoned guess, not a legacy generation.
- GET `/api/subjectMarksShort` — Only in fgis-dnevnik-desktop's initial commit 60b5a1d (default host myschool.edu.ru); replaced six minutes later in 'api fix' 262576e by /api/family/web/v1/*. An abandoned guess, not a legacy generation.
- GET `/api/family/mobile/v1/homeworks (desktop initial commit)` — Only in fgis-dnevnik-desktop's initial commit 60b5a1d (default host myschool.edu.ru); replaced six minutes later in 'api fix' 262576e by /api/family/web/v1/*. An abandoned guess, not a legacy generation.
- GET `/api/pass/entrances/v1/` — Only in fgis-dnevnik-desktop's initial commit 60b5a1d (default host myschool.edu.ru); replaced six minutes later in 'api fix' 262576e by /api/family/web/v1/*. An abandoned guess, not a legacy generation.

**Caveats.**

- ТОР «Моя школа» (the federal «типовое облачное решение», regional component of ФГИС «Моя школа») is the diary of the 2026/27 first-wave regions and is reached only through «Госуслуги Моя школа»: app ru.gosuslugi.school, www.gosuslugi.ru/school (edu.gosuslugi.ru in one ЕАО link) and the «Дневник» widget in Сферум/MAX. No client-visible API of it was found: no open-source client, no document, and the web entry returns only a JS shell. Its route table here is therefore empty apart from the entry pages.
- ФГИС «Моя школа» itself (myschool.edu.ru) is a portal with ЕСИА-only sign-in that relays regional diary data; its only published API is the server-to-server integration contract (FGIS calls the regional system's /Schedule/{ЕСИА id}), from 2021–2022.
- The only client labelled FGIS, SolomonNumb1/fgis-dnevnik-desktop (16 commits written in one day, 2026-09-13, no issues), calls the МЭШ family/web API on МЭШ-platform regional nodes. Its routes were fitted against dnevnik.edurb.ru (Башкортостан); its first-commit calls against myschool.edu.ru were dropped within minutes. Its rows are current for МЭШ nodes, not for myschool.edu.ru or ТОР regions.
- The desktop client's 83-host table was generated in the 'github prep' commit (2689f8b). Only the 7 hosts of its earlier table (dnevnik.edurb.ru, school.mos.ru, myschool.mosreg.ru, myschool.72to.ru, myschool.05edu.ru, dnevnik.admoblkaluga.ru, ms-edu.tatar.ru) match the МЭШ app or the official МЭШ help page. The other 76 myschool.<region> hosts were removed from regional_instances. One is NXDOMAIN (myschool.yanao.ru), and five contradict the official entries of their regions (Карелия, Вологда, Тверь, Мурманск, ЯНАО). For the ТОР regions (Краснодарский край, Свердловская, Смоленская, Чувашия, Ростовская, Воронежская, Астраханская, Оренбургская, Брянская, Челябинская, Калининградская, Ярославская, Республика Алтай, ЕАО, НАО, ДНР, ЛНР, КБР, Удмуртия), the host in 2026/27 is www.gosuslugi.ru/school. petersburgedu.ru (region 78) is another platform altogether.
- The routes of OctoDiary-kt, PyMyschoolApi, AutoEdu, myschool-mosreg-api and MESH_Helper against authedu.mosreg.ru / myschool.mosreg.ru, and OctoDiary's school.mos.ru visits call, were moved out (see refuted). They describe the Московская область regional «Моя школа» (mesh_myschool) and Moscow МЭШ (mesh_moscow). Both are separate entries that already carry them.
- The МЭШ regional app ships a ru.mes.dnevnik.fgis flavour («Моя Школа»). Its strings contain a region table (Татарстан, Тюмень, Дагестан, Калуга, Чечня, ЯНАО), per-region client ids (fgismobile, fgismobile_89, fgismobile_95) and the code exchange on /v3/auth/token. They contain no host for myschool.edu.ru or gosuslugi.ru, so the FGIS-branded app is a МЭШ regional client, not a ТОР client.
- In ЯНАО, Татарстан, Коми, Новосибирская, Белгородская, Ивановская, Амурская and Ульяновская области, «Госуслуги Моя школа» is used as a front end over the regional system rather than as the ТОР journal, so the same app reads different back ends by region. How it reaches them is not visible.
- Live hosts were not probed (unreachable from here). WebSearch budget was exhausted, so the ТОР region list comes from earlier research of this run and from pages fetched now, not from a complete official list (reported as 20 first-wave regions).
- Desktop README and code disagree: README names Profile-Id and auth_token and gives {is_done} as the POST body; the code sends no Profile-Id, reads aupd_token and sends {}.
