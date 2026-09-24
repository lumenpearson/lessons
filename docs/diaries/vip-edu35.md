# vip.edu35.ru — Вологодская область, the college register

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted and checked against the code, not yet dated. Confidence of the whole: high. Nothing here has been tried against the live service.*

**Not a family diary, and not a platform of its own.** `school.vip.edu35.ru` and
`ssuz.vip.edu35.ru` are БАРС installations for Вологодская область — the school register and the
register of its colleges. The region's family diary is the МЭШ-based
[«Моя школа»](mesh-myschool.md) at `dnevnik.edu35.ru`; this page exists because a search for the
region's hosts finds these two first.

**What the clients call.** Two teacher-side tools, one built on the other, drive the college
register's M3/ExtJS actions under `/actions/…` — opening and closing a lesson, saving
work — with the `ssuz_sessionid` cookie a teacher pastes from a browser. On the school register
only the generic [БАРС](bars.md) `/rest/login` is seen.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| web | `https://ssuz.vip.edu35.ru` | college (СПО) register; every route the two clients call is an M3 action under /actions/...; form-encoded POST, JSON or ExtJS JavaScript back |
| auth | `https://ssuz.vip.edu35.ru/auth` | /auth/login-page (GET) and /auth/login (POST) |
| web | `https://school.vip.edu35.ru` | school register; БАРС Web-Образование (confirmed by BarsDiaryNativeLogin posting /rest/login and dnvk.ru's БАРС server list); the rest of its API is the generic БАРС /rest/* and /api/*Service/* surface, not seen with this host in any client |
| mobile_api | `https://school.vip.edu35.ru/rest` | only /rest/login is seen with this host |

**Signing in.**

*Browser cookie paste (ssuz, current in LauncherSPO)* — token carried as cookie ssuz_sessionid (plus cookie csrftoken echoed in header X-Xsrftoken). Lifetime: server session; not stated. Refresh: paste new cookies.

1. Teacher signs in to https://ssuz.vip.edu35.ru in a browser (login/password or Госуслуги)
2. DevTools > Сеть > open the register > any request > Cookie: copy csrftoken and ssuz_sessionid
3. LauncherSPO LoginForm: the 'login' field is the csrftoken, the 'password' field is the ssuz_sessionid; AvtoJ.login builds Cookie 'csrf_token_header_name=X-XSRFTOKEN;ssuz_sessionid=<redacted>;csrftoken=<csrftoken>' and calls set_cookie
4. set_cookie does GET https://ssuz.vip.edu35.ru with that cookie and stores it; every subsequent POST sends the cookie plus X-Xsrftoken: <csrftoken> (the substring after 'csrftoken=')
5. POST /actions/... with Content-Type application/x-www-form-urlencoded and X-Requested-With: XMLHttpRequest

   NOTE: AvtoJ.__init__ ALWAYS does GET /auth/login-page first (reads csrfmiddlewaretoken and the csrftoken cookie) regardless of which flow follows, but login() ignores that csrf and uses the pasted values. LauncherSPO's LoginForm labels are «csrftoken» and «ssuz_sessionid» and stores them in a plain file 'cash' as 'csrftoken;ssuz_sessionid'. Eljurnal's .env takes COOKIE=ssuz_sessionid=... and XSRFTOKEN=... (the value used both as the csrftoken cookie and the X-Xsrftoken header).

*Password form login (ssuz, Eljurnal live via menu 0; commented out in LauncherSPO)* — token carried as cookie ssuz_sessionid. Refresh: repeat login.

1. Eljurnal auth(): POST https://ssuz.vip.edu35.ru/auth/login with headers_without_cookie (no Cookie, no CSRF, no Referer, no X-Requested-With) and form login_login, login_password — it does NOT fetch /auth/login-page and does NOT send csrfmiddlewaretoken
2. Check JSON: json()['success']=='False' (string) means wrong password
3. Take ssuz_sessionid from the cookie jar; thereafter Eljurnal keeps using the pasted COOKIE from .env for the actions, not the jar
4. LauncherSPO's disabled AvtoJ.login variant would instead GET /auth/login-page for csrfmiddlewaretoken and POST /auth/login with csrfmiddlewaretoken+login_login+login_password

   Eljurnal warns credentials go to the server unencrypted; its menu line «0. Авторизоваться» is commented out though typing 0 still calls auth(). LauncherSPO comment «Для тестов, а то банит» — repeated logins got the account blocked, which is why it switched to cookie paste; its ReJ.py __main__ has a hard-coded login/password (redacted).

*REST login (school.vip.edu35.ru, BARS mobile endpoint)* — token carried as cookie (БАРС session cookie, named 'sessionid' on other БАРС servers — confirmed by mironovmeow/barsdiary using cookie 'sessionid'; not named in this client).

1. On https://school.vip.edu35.ru/*auth* page, the content script POSTs /rest/login with FormData login, password (same origin)
2. On json.success truthy, the session cookie is set; navigate to window.location.origin

   Exists because the web login page offers only Госуслуги; the /rest endpoint still accepts login/password. mironovmeow/barsdiary reaches the same generic BARS endpoint as GET /rest/login?login=&password= on other hosts.

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Cookie` | csrftoken=<csrftoken>; csrf_token_header_name=X-XSRFTOKEN; ssuz_sessionid=<redacted> | session plus Django-style CSRF cookie; the csrftoken value equals the X-Xsrftoken header value |
| `X-Xsrftoken` | <same value as csrftoken cookie> | CSRF header whose name the csrf_token_header_name cookie announces (X-XSRFTOKEN); README «от 05.10: Снова всё работает, добавлен crf token» — calls failed without it |
| `X-Requested-With` | XMLHttpRequest | M3 action calls are made as AJAX; both clients send it on the action calls (not on Eljurnal's /auth/login, which uses headers_without_cookie) |
| `Content-Type` | application/x-www-form-urlencoded; charset=UTF-8 | all actions take form fields |
| `Referer` | https://ssuz.vip.edu35.ru/auth/login-page | sent on the action calls by both clients (browser copy); necessity not stated; absent from Eljurnal's /auth/login |
| `Host` | ssuz.vip.edu35.ru | set explicitly in both clients' header dicts |
| `User-Agent` | browser string (Firefox 105 in Eljurnal; Chrome/Edge 116 mobile in LauncherSPO) | browser imitation; no documented block |

**Captcha and second factor.** none handled; Госуслуги sign-in is done in the browser and its cookies pasted

LauncherSPO disables TLS verification (verify=False) on every call. Every /actions/register and /actions/lesson_work call repeats the register context fields (unit_id, period_id, date_from, date_to, practical, slave_mode, month, group_id, subject, subject_gen_pr_id, exam_subject_id, subject_sub_group_obj, subject_id) — the server is stateful M3 and reads the whole filter each time. The exam_score actions do NOT use those context fields; they use m3_window_id/grid_id and ExtJS writer fields instead.

**Routes** — 22 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| POST | `https://school.vip.edu35.ru/rest/login` | Login/password sign-in on the school register (БАРС mobile-app endpoint), bypassing the Госуслуги-only web form | none | body (multipart/form-data): `login`, `password` | JSON with success (bool); on success the browser holds the session cookie (named 'sessionid' on БАРС, per mironovmeow/barsdiary on other hosts) and is sent to window.location.origin | Georglider/BarsDiaryNativeLogin `main.js` `form.onsubmit` | current |
| GET | `https://ssuz.vip.edu35.ru/` | Request with the pasted cookie, made by AvtoJ.set_cookie (called from AvtoJ.login) before using the actions | cookie |  | HTML; response unused | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.set_cookie` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/exam_score/exam_score_rows` | Write a student's score into an exam sheet | cookie | body (application/x-www-form-urlencoded): `exam_sheet_id`, `m3_window_id` 'cmp_2fb9c023', `grid_id` 'cmp_83d63e96', `xaction` 'update' (ExtJS writer), `rows` JSON {"student_id":S,"score_23":"5"} | response not read | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.ved_score` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/exam_score/objectrowsaction` | List exam sheets (ведомости) for a period/subperiod filtered by teacher surname | cookie | body (application/x-www-form-urlencoded): `start` '0', `m3_window_id` M3 component id, hard-coded 'cmp_2b72f74a', `grid_id` 'cmp_850ea220', `ssuz.exam_score.actions.PeriodSelectPack_id` period id, `ssuz.exam_score.actions.SubperiodSelectPack_id` subperiod id, `filter` surname text, `id` '-1' | {rows:[{id:<exam_sheet_id>, ...}]}; this and the other exam_score actions do NOT send the register context fields | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.ved_get` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/exam_score/re_score_type_save_action` | Set the score type of an exam sheet | cookie | body (application/x-www-form-urlencoded): `exam_sheet_id`, `m3_window_id` 'cmp_eb198448', `grid_id` 'cmp_850ea220', `score_type_ids` '[23]' | response not read | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.ved_score_type` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/lesson_work/objectrowsaction` | List the work columns of a lesson (to get work_id for marks) | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false', `type_id` '82', `description` '', `lesson_work_id` '0' | ExtJS JSON with rows[] of works (id used as work_id in save_lesson_score) | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.show_score_pole` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/lesson_work/objectsaveaction` | Create a work column (поле для оценок) on a lesson | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false', `type_id` '82' (work type), `description` '', `lesson_work_id` '0' = new | response not read | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.create_score_pole` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lesson_register/lesson_register_save` | Save the lesson topic (тема занятия) | cookie | body (application/x-www-form-urlencoded): `lesson_subject` topic text, `lesson_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false' | JSON with 'message'; 'Занятие закрыто!' when the lesson is closed (used as a closed-check in wwwww3) | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.uploading_topics` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_close_lesson_action` | Close (lock) one lesson | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `student_id` any student of the group, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false', `subperiod` '399' in Eljurnal closePractic, `mark` '' in Eljurnal closePractic | JSON (printed only) | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.close_open_lesson` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_group_rows` | Groups (учебные группы) the teacher has in the lesson register for the period | cookie | body (application/x-www-form-urlencoded): `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month` '' (LauncherSPO only), `empty_item` '1' (LauncherSPO only), `filter` '' (LauncherSPO only) | ExtJS store JSON {rows:[{id:<group_id int>, name:'<group> - ...'}], total}; Eljurnal create_spisok_student calls it twice (practical '' and '1') with only unit_id/period_id/date_from/date_to/practical/slave_mode | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.group_rows` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_open_lesson_action` | Reopen one lesson | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `student_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false' | JSON (printed only) | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.close_open_lesson (open=True)` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_rows` | Register grid: every student of the group with every lesson column (id, date), averages | cookie | body (application/x-www-form-urlencoded): `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject` '0', `subject_gen_pr_id` '0', `exam_subject_id` '0', `subject_sub_group_obj` the subject row's id JSON string, `subject_id` numeric, parsed from subject_sub_group_obj (nums[0]), `empty_item` '1' (LauncherSPO), `filter` '' (LauncherSPO), `view_lessons` 'false' — only in Eljurnal saveThemesTeory's direct payload (with slave_mode ''); commented out in Eljurnal payload_rows_* and absent in LauncherSPO student_rows | {rows:[{student_id, student_name, lessons:[{id:<lesson_id>, date:'dd.mm.YYYY', ...}], aver_period, 'aver_subper_<subject_id>_<subperiod_id>'}], total}; lesson ids are read from rows[0].lessons | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.student_rows` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_save_rows` | Save final (annual) and subperiod (semester) marks for a student | cookie | body (application/x-www-form-urlencoded): `data` JSON string {"lessons":{},"final_marks":{"<student>_annual_estimation":{"mark":"5","type":"annual_estimation","student_id":S}},"subperiod_marks":{}} or subperiod_marks {"<student>_subperiod_<group>_<subperiod>":{"mark","subperiod_id","student_id"}}, `unit_id`, `period_id`, `date_from`, `date_to`, `practical` '', `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id` | response not read | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.score_final` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_subject_rows` | Subjects (дисциплины) and practice sub-groups of one group | cookie | body (application/x-www-form-urlencoded): `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject` '0', `subject_gen_pr_id` '0', `exam_subject_id` '0', `subject_sub_group_obj` '' (empty for this call), `empty_item` '1', `filter` '' | {rows:[{id:'{"subject_id":6132}' or '{"subject_id":6132,"sub_group_id":13664}' (a JSON string), name:'<subject>'}], total}; the row's id is sent back verbatim as subject_sub_group_obj on later calls. Eljurnal builds it itself from group listings instead | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.disc_rows` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/save_lesson_score` | Save attendance (явка, e.g. 'Н') or a mark for a student in a lesson (one endpoint; url[5] for attendance, url[8] for marks) | cookie | body (application/x-www-form-urlencoded): `data` JSON string: attendance {"lesson_id":L,"attendance":"Н","student_id":S}; mark {"lesson_id":L,"attendance":"","work_id":"W","score_type_id":"36","score":"5","student_id":S}, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false' — sent by expose_score (marks); NOT sent by setting_turnout (attendance) | response not parsed | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.setting_turnout / AvtoJ.expose_score` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/ssuz.register.actions.Pack/finalmarktypesaveaction` | Add a final-mark column (Годовая, Итоговая, семестр) to the register | cookie | body (application/x-www-form-urlencoded): `group_id`, `period_id`, `subject_sub_group_obj`, `mark` 0 = годовая, 1 = итоговая, '' otherwise, `subperiod` subperiod id, e.g. 400, `unit_id`, `date_from`, `date_to`, `practical` '', `slave_mode`, `month`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_id`, `sub_group_names` a duplicated Python dict key in аssign_rating (sub_g[0] then sub_g[1]) so only sub_g[1] is actually sent, once, `mark_name` e.g. 'Годовая', 'Итоговая', '2 семестр(22/23)', `mark_type_id` '23' | response printed as Response object only | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.аssign_rating` | current |
| POST | `https://ssuz.vip.edu35.ru/actions/ssuz.register.actions.Pack/finalmarktypewindowaction` | Final-mark-type window; used only for its sub_group_names | cookie | body (application/x-www-form-urlencoded): `unit_id`, `period_id`, `date_from`, `date_to`, `practical` '', `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `subperiod` '', `mark` '0' | JSON with key sub_group_names (list) | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.uploader_sub_group_names` | current |
| GET | `https://ssuz.vip.edu35.ru/auth/login-page` | Login page of the college register; LauncherSPO requests it in AvtoJ.__init__ on every start and reads the csrfmiddlewaretoken hidden input and the csrftoken cookie from it | none |  | HTML (Django-style CSRF): <input name=csrfmiddlewaretoken value=...>; Set-Cookie csrftoken=...; also the Referer every client sends | SkifssA/LauncherSPO `ReJ.py` `AvtoJ.__init__` | current |
| GET | `https://school.vip.edu35.ru/auth/login-page` | School register login page (Госуслуги button, .gosuslugi block); the extension's content script matches https://school.vip.edu35.ru/*auth* | none |  | HTML; the code only matches /*auth* and reads document.getElementsByClassName('gosuslugi'); exact path /auth/login-page confirmed by web search, not by the code | Georglider/BarsDiaryNativeLogin `manifest.json` `content_scripts.matches` | uncertain |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_get_add_work_window` | Get the ExtJS window of a lesson (date and current topic) | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `student_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false' | JavaScript (M3/ExtJS component code, e.g. new Ext.form.TextArea({fieldLabel:'Тема',value:'...\u000A'})), parsed with regex value:'...' or title:'...' | Egortex/Eljurnal `get_theme.ipynb` `url_get_theme` | uncertain |
| POST | `https://ssuz.vip.edu35.ru/auth/login` | Password sign-in; on success sets the ssuz_sessionid cookie | none | body (application/x-www-form-urlencoded): `login_login`, `login_password`, `csrfmiddlewaretoken` sent ONLY in LauncherSPO's commented-out AvtoJ.login variant, taken from /auth/login-page; Eljurnal's live call does not send it and does not fetch the login page first | JSON with 'success' (Eljurnal compares json()['success']=='False' as a string); cookie jar then holds ssuz_sessionid. Eljurnal reaches it through menu choice 0 (line commented, branch live) then keeps using the pasted COOKIE; LauncherSPO commented the call out and switched to pasted cookies, so whether it still works is unknown | Egortex/Eljurnal `funcEJ.py` `auth` | uncertain |
| POST | `https://ssuz.vip.edu35.ru/actions/register/lessons_tab/lessons_tab_save_work_lesson_subject` | Save the lesson topic (older route used by Eljurnal; LauncherSPO replaced it with lesson_register_save) | cookie | body (application/x-www-form-urlencoded): `lesson_id`, `student_id`, `unit_id`, `period_id`, `date_from`, `date_to`, `practical`, `slave_mode`, `month`, `group_id`, `subject`, `subject_gen_pr_id`, `exam_subject_id`, `subject_sub_group_obj`, `subject_id`, `view_lessons` 'false', `lesson_subject` topic text | JSON (printed) | Egortex/Eljurnal `funcEJ.py` `saveThemesTeory / saveThemesPracticy (url_save)` | legacy |

**Formats.**

- *Dates*: date_to and lesson dates 'dd.mm.YYYY'; date_from 'dd.mm.YYYY' (LauncherSPO date_patch: 01.09.YYYY or 01.01.YYYY) or 'YYYY-MM-DDT00:00:00' (Eljurnal .env DATES_FROM) — both accepted
- *Ids*: integers: group_id (e.g. 4649), subject_id (6132), sub_group_id (13664), student_id (70002), lesson_id (3264542), period_id (Eljurnal hard-codes 30; LauncherSPO computes yy+8+(yy-23)[+1 Jan-Aug]), unit_id 22, subperiod ids (399, 400), mark_type_id 23, score_type_id 36, work type_id 82. A subject row's id is a JSON string {"subject_id":..[,"sub_group_id":..]} sent back verbatim as subject_sub_group_obj; subject_id numeric is parsed from it. M3 window/grid ids (cmp_xxxxxxxx) are hard-coded and page-generated
- *Pagination*: ExtJS store responses {rows:[...], total}; exam_score/objectrowsaction takes start=0; no client pages further
- *Notes*: Register context fields every /actions/register and /actions/lesson_work call repeats: unit_id ('22'), period_id, date_from, date_to, practical ('' теория / '1' практика), slave_mode ('1'; '' in one Eljurnal saveThemesTeory payload), month (''), group_id, subject ('0'), subject_gen_pr_id ('0'), exam_subject_id ('0'), subject_sub_group_obj (JSON string), subject_id (numeric). group_rows omits the subject fields; subject_rows omits subject_id. The exam_score/* actions use m3_window_id/grid_id/xaction/rows instead of the register context. Responses are JSON for grid/row actions and JavaScript component code for *_window actions. Write actions carry a 'data' or 'rows' field that is itself a JSON string.

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Вологодская область | `school.vip.edu35.ru` | школы, «Электронный журнал» (БАРС Web-Образование) |
| Вологодская область | `ssuz.vip.edu35.ru` | СПО, «Электронный колледж»; the only host the teacher clients call for the /actions API |
| Вологодская область | `detsad.vip.edu35.ru` | детский сад; listed by dnvk.ru only |
| Вологодская область | `edo-app.edu35.ru` | дополнительное образование, http://; listed by dnvk.ru only |
| Вологодская область | `gu.vip.edu35.ru` | «Объявления» portal seen in web search; role unknown |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/Egortex/Eljurnal | 2024-05-29 | funcEJ.py (lessons_tab_* routes, /auth/login via auth() with headers_without_cookie, cookie+X-Xsrftoken action headers, payload builders), TemaEJ.py menu (choice 0 commented but branch live), get_theme.ipynb (lessons_tab_get_add_work_window + ExtJS response sample, real ids group 4649/subject 6132/student 70002/period 30), README (crf-token note, take X-XSRFTOKEN from header), .env-template |
| https://github.com/ChePchik/Eljurnal | 2024-05-29 | identical tree to Egortex/Eljurnal (same commit; the account LauncherSPO's README links as «За основу взят Eljurnal») |
| https://github.com/SkifssA/LauncherSPO | 2025-07-02 | ReJ.py AvtoJ class (all 20 ssuz routes incl. attendance, marks, work columns, topic save via lesson_register_save, final marks, exam sheets), LoginForm.py (cookie paste: 'login'=csrftoken, 'password'=ssuz_sessionid, 'cash' file), period_id arithmetic, unit_id 22, test/zurnal.ipynb (same URLs) |
| https://github.com/Georglider/BarsDiaryNativeLogin | 2024-10-13 | school.vip.edu35.ru is БАРС: content script on /*auth* posting /rest/login with FormData login/password; README region table lists school.vip.edu35.ru for Вологодская область |
| https://github.com/mironovmeow/barsdiary |  | NEW cross-check (found via Sourcegraph): generic БАРС client — GET /rest/login?login=&password= returns cookie 'sessionid', which is the session cookie name /rest/login sets; confirms the BARS session-cookie naming the school.vip flow relies on. Different host, so not added as a vip_edu35 route |
| https://github.com/search?type=repositories&q=ssuz.vip |  | NEW search (WebFetch): 0 repositories — no additional ssuz.vip.edu35.ru client exists on GitHub beyond the ones read |
| WebSearch: "school.vip.edu35.ru" OR "ssuz.vip.edu35.ru" api python OR bot |  | no further code; confirms school=школы, ssuz=Электронный колледж, БАРС.Education by BARS Group, Госуслуги/ЕСИА sign-in; gu.vip.edu35.ru «Объявления» |
| Sourcegraph stream: edu35.ru, ssuz.register, lesson_register_save, exam_score_rows, finalmarktypesaveaction |  | no code results for the distinctive ssuz action names anywhere on Sourcegraph's index; only rest/login hits in unrelated БАРС clients |
| https://github.com/search?type=repositories&q=vip.edu35.ru ; q=edu35 ; q=Eljurnal OR LauncherSPO (prior extractor) |  | 0 / 4 unrelated / the three cloned repos + matveygrth/eljurnal.github.io (unrelated static page) |
| local checkpoint extract_bars.json (earlier BARS extraction) |  | cross-check: generic БАРС /rest/* and /api/*Service/* routes; school.vip.edu35.ru among БАРС instances |

**Caveats.**

- Only two teacher-side ssuz clients exist and they share a lineage: LauncherSPO is built on Eljurnal («За основу взят Eljurnal»). Every ssuz route comes from one author lineage for one college (unit_id 22).
- Everything is the teacher side of the college register (ssuz); no client reads anything as a student or parent, and nothing calls school.vip.edu35.ru beyond /rest/login.
- school.vip.edu35.ru is БАРС Web-Образование (confirmed by BarsDiaryNativeLogin and dnvk.ru); its student/parent API is presumably the generic БАРС /rest/* and /api/*Service/* surface (kind 'bars'), but no client uses those paths with this host. The БАРС session cookie is named 'sessionid' (confirmed by mironovmeow/barsdiary on other hosts).
- The ssuz M3 actions are internal web-UI endpoints reverse-engineered from browser DevTools; there is no documented API. Values like m3_window_id/grid_id are page-generated and hard-coded here, so they will drift.
- lessons_tab_save_work_lesson_subject (Eljurnal) was replaced by lesson_register/lesson_register_save in LauncherSPO — treated as legacy.
- period_id arithmetic in LauncherSPO is a local guess tied to the 2023 numbering and will drift; Eljurnal hard-codes 30.
- POST /auth/login is reached in Eljurnal only via hidden menu choice 0 (line commented, branch live) and uses headers_without_cookie (no CSRF, no Referer); the same call is fully commented out in LauncherSPO, which notes accounts got banned for repeated logins.
- Field-list corrections applied: subject_rows does not send subject_id; save_lesson_score sends view_lessons only for marks not attendance; finalmarktypesaveaction's sub_group_names is a duplicated dict key so only one value is sent; lessons_tab_group_rows' month/empty_item/filter are LauncherSPO-only; lessons_tab_rows' view_lessons appears only in one Eljurnal payload.
- Secrets in the repos (LauncherSPO ReJ.py __main__ hard-coded login/password; Eljurnal .env-template sample ssuz_sessionid and XSRF token) are redacted here.
