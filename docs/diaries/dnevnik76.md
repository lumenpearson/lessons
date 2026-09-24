# «Дневник76» — Ярославская область, until 2024

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted and checked against the code, not yet dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**A regional diary that closed on 2 September 2024.** «Региональный интернет-дневник 4.1» at
`my.dnevnik76.ru` served Ярославская область until the region moved to the ЭлЖур-based
«Образование-76» at `school.yarcloud.ru` on 16 September 2024 — see [ЭлЖур](eljur.md). Every
route below is legacy, and the page exists so that its two clients on GitHub are not mistaken
for a way into the region today.

**It never had an API.** It was a server-rendered Django site; the clients posted its login form
and scraped the pages, and the one JSON answer in the whole table is an unread-message counter.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| legacy | `https://my.dnevnik76.ru` | Django server-rendered site («Региональный интернет-дневник 4.1»); every route is HTML scraping except /ajax/messages_count/ (JSON). Closed 02.09.2024 per school notices |
| legacy | `https://my.dnevnik76.ru/ajax` | urlAjax in bvp main.go: HTML <select> fragments for pickers (kladr, school, subj) and one JSON counter |
| web | `https://school.yarcloud.ru` | Successor «Образование-76», live from 16.09.2024, ЕСИА sign-in; ЭлЖур-based; not covered by any client here |

**Signing in.**

*Django form login (bvp/dnevnik76-api)* — token carried as cookie: Django session cookie held in the jar (name not written in the code; Django default is sessionid, CSRF cookie csrftoken); form field csrfmiddlewaretoken on the POST. Lifetime: Unknown; no refresh logic — the client simply logs in again. Refresh: None; re-run Login().

1. NewClient: create a cookie jar (publicsuffix) and pre-set cookie items_perpage=1000 (Domain my.dnevnik76.ru, Path /); build an http.Client with InsecureSkipVerify: true
2. NewClient also always calls GET /ajax/school/{region_id}/?login=true (public, via Go's default http client, not the jar) to look up the school name; GET /ajax/kladr/?login=true (GetRegions) is a separate public helper the login flow never calls
3. Login: GET https://my.dnevnik76.ru/accounts/login/ and read .login__form > input[name='csrfmiddlewaretoken'] (was #login_form before 2020-10-12); the CSRF cookie lands in the jar
4. POST https://my.dnevnik76.ru/accounts/login/ as application/x-www-form-urlencoded with next='', csrfmiddlewaretoken, username='{login}@{school_id}', fake_username='{login}', password, school='{school_id}', submit='' and headers Referer: https://my.dnevnik76.ru/accounts/login/, Host: my.dnevnik76.ru, Origin: https://my.dnevnik76.ru, Content-Type: application/x-www-form-urlencoded
5. Go's client follows the redirect; the session cookie is now in the jar (response body ignored)
6. getCurrentInfo: GET /homework/ to establish context: class name from #auth_info > #role, class_id from body onload loadSubjects('/ajax/subj/{id}/', true), school year from #eduyear > #curedy; a parse failure here is the only failure signal of the login
7. Switch school year with SetCookie('edu_year', '{YYYY}') (empty = current), which re-runs getCurrentInfo (re-reads /homework/)

   No captcha, SMS, ЕСИА or device token. Credentials are school-issued numeric logins (8 digits, e.g. 08331111) plus a short password. Client disables TLS verification (InsecureSkipVerify: true) for the jar client only; the public ajax helpers use http.Get.

*Browser login via Selenium (maxxig/dnevnik76_telegram_notifier)* — token carried as cookie (browser session). Lifetime: Per run; the README's crontab runs main.py every 10 minutes and each run logs in afresh.

1. Headless Chrome opens https://my.dnevnik76.ru/accounts/login/
2. Click the first div.custom-select__selected and pick //div[contains(@data-value,'{region}')] (e.g. 76000001000/3)
3. Click the second div.custom-select__selected and pick //div[contains(@data-value,'{school}')] (e.g. 760218)
4. Click div#continue-button
5. Type the login into input#id_fake_username and the password into input#id_password, click input[type=submit]
6. Browse /homework/ and /marks/current/{selector}/list/ in the same browser session

   Sleeps 1–3 s between steps. That the page's JS fills the hidden username '{login}@{school}' from fake_username is an inference from bvp's form fields, not shown in maxxig's code.

*Successor ЕСИА sign-in (school.yarcloud.ru) — documents only* — token carried as unknown (no client code); ЭлЖур-based.

1. Open https://school.yarcloud.ru
2. Choose sign-in through Госуслуги (ЕСИА) — «Вход в новый интернет-дневник для всех пользователей возможен через авторизацию в ЕСИА (учетная запись на ЕПГУ)»

   Old my.dnevnik76.ru logins/passwords do not work on the new system (school notices). Some schools reportedly still issue individual logins (promodoc.ru).

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Referer` | https://my.dnevnik76.ru/accounts/login/ | Set on the login POST; Django's CSRF check over HTTPS rejects a POST without a same-origin Referer |
| `Origin` | https://my.dnevnik76.ru | Set on the login POST alongside Referer |
| `Content-Type` | application/x-www-form-urlencoded | Form login |
| `Host` | my.dnevnik76.ru | Set explicitly by bvp on the login POST |
| `Cookie: items_perpage` | 1000 (UI offers 10, 20, 30, 50) | Page size of server-rendered lists (homework, messages); set so that one page holds everything, since the client does not paginate; bvp's tests reset it to empty |
| `Cookie: edu_year` | YYYY or empty | Selects which school year pages show; empty means current |

**Captcha and second factor.** None seen on my.dnevnik76.ru. The successor requires ЕСИА (Госуслуги) with whatever 2FA the user's Госуслуги account has.

No rate limiting or User-Agent handling in any client; bvp uses Go's default UA, maxxig a real headless Chrome. All data is scraped from HTML with CSS selectors, so any markup change breaks the clients (bvp already had to change the login selector once, in 2020).

**Routes** — 22 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://school.yarcloud.ru/` | Successor system «Образование-76» (ЭлЖур-based GIS) landing and sign-in; the extension's shutdown page links here | none |  | Not a dnevnik76 route: listed so the successor host is recorded. Sign-in through ЕСИА (Госуслуги) per school notices; no open-source client for this host was found, its API surface belongs with ЭлЖур | dnevnik76/dnevnik76.github.io `index.html` `a.button «Новый дневник»` | current |
| GET | `https://my.dnevnik76.ru/marks/current/{period}/date/` | Marks for one period ordered by date («по датам») | cookie | `{period}` as above | Path built by the client but parsing not implemented («Not implemented right now»); documented in doc.go as 'date - по датам' | bvp/dnevnik76-api `models.go` `MarksListType Date / GetMarksForWithType case Date` | uncertain |
| GET | `https://my.dnevnik76.ru/messages/new/` | Compose a message to a user (seen only as a link target on /teachers/) | cookie | `to?={user_id}@{school_id}` (recipient login qualified by school id) | Never requested by any client; the sending POST is unknown | bvp/dnevnik76-api `main.go` `Client.GetTeachers (href prefix '/messages/new/?to=')` | uncertain |
| GET | `https://my.dnevnik76.ru/schedule/` | Timetable page (link in the extension menu only) | cookie |  | No client parses it | dnevnik76/dnevnik76-files `dnevnik76.zip:dnevnik76/home.html` `menu link` | uncertain |
| GET | `https://my.dnevnik76.ru/settings/` | Account settings page (link in the extension menu only) | cookie |  | No client parses it | dnevnik76/dnevnik76-files `dnevnik76.zip:dnevnik76/home.html` `menu link` | uncertain |
| GET | `https://my.dnevnik76.ru/stat/` | Statistics page (link in the extension menu only) | cookie |  | No client parses it | dnevnik76/dnevnik76-files `dnevnik76.zip:dnevnik76/home.html` `menu link` | uncertain |
| GET | `https://my.dnevnik76.ru/` | Logged-in home page (the extension replaces it wholesale with its own home.html menu) | cookie |  | HTML; content.js does document.write(home.html) over it, so no element of it is read. The site chrome content.js strips on /marks/current/* pages (and so presumably shared by every page) is: header h1 «Региональный интернет-дневник 4.1», header img.agr_stat, #eduyear, #auth_info, nav a «Домашнее задание», «Учителя», «Сообщения», «Файлы», stylesheets under /static/css/, Yandex.Metrika comments; home.html links the logo at /static/images/logo.png | dnevnik76/dnevnik76-files `dnevnik76.zip:dnevnik76/content.js` `modifyContent / manifest content_scripts` | legacy |
| GET | `https://my.dnevnik76.ru/accounts/login/` | Fetch the Django sign-in page and scrape the CSRF token from `.login__form > input[name='csrfmiddlewaretoken']`; the same page hosts the region and school pickers (custom-select divs with data-value) used by the browser flow | none |  | HTML. input[name=csrfmiddlewaretoken] value under .login__form (selector was #login_form in bvp's 2019 initial commit, changed to .login__form in b02a02f 2020-10-12 'updated css selector'); the CSRF cookie lands in the jar (its name is not in the code). Browser version: div.custom-select__selected (region, then school), div[data-value*='{region}'] / div[data-value*='{school}'] (XPath contains), div#continue-button, input#id_fake_username, input#id_password, input[type=submit] (maxxig modules/webdriver.py login_to_dnevnik76) | bvp/dnevnik76-api `main.go` `Client.Login (urlLogin)` | legacy |
| POST | `https://my.dnevnik76.ru/accounts/login/` | Sign in with the school-issued login and password; the session cookie is kept in the cookie jar | cookie | body (application/x-www-form-urlencoded): `next` empty; comment suggests /marks/current/, `csrfmiddlewaretoken` scraped from the GET, `username` '{login}@{school_id}', e.g. 08331111@760215, `fake_username` the bare login as typed (visible field id_fake_username), `password`, `school` numeric school id, e.g. 760215, `submit` empty | Response body ignored (parsing commented out); success is judged only by the next page (GET /homework/ in getCurrentInfo) parsing. Redirect is followed by Go's http.Client. | bvp/dnevnik76-api `main.go` `Client.Login` | legacy |
| GET | `https://my.dnevnik76.ru/ajax/kladr/` | List regions/municipalities for the login picker (public) | none | `login=true` (always 'true' in the client) | HTML fragment: <select><option value='{region_id}'>name</option>; value 0 skipped. region_id is a KLADR-style code, e.g. 76000001000 (config_test.json.example); the browser picker's data-value carries a suffix, e.g. 76000001000/3 (maxxig config-template.yaml). Requested with http.Get (Go default client, no jar); not called by NewClient/Login | bvp/dnevnik76-api `main.go` `GetRegions` | legacy |
| GET | `https://my.dnevnik76.ru/ajax/messages_count/` | Unread and total message counters | cookie |  | JSON object {"unread_messages": int, "all_messages": int} — the only JSON endpoint seen | bvp/dnevnik76-api `main.go` `Client.GetMessagesCount` | legacy |
| GET | `https://my.dnevnik76.ru/ajax/school/{region_id}/` | List schools of one region for the login picker (public) | none | `{region_id}` from /ajax/kladr/, e.g. 76000001000; `login=true` | HTML fragment: <select><optgroup label='{school type}'><option value='{school_id}'>name</option>; school_id six digits starting 76 (e.g. 760215, 760218, 760206). NewClient always calls it (via http.Get, default client without the jar) to fill CurrentInfo.SchoolName | bvp/dnevnik76-api `main.go` `GetSchools (called by NewClient)` | legacy |
| GET | `https://my.dnevnik76.ru/ajax/subj/{class_id}` | List subjects (courses) of the pupil's class | cookie | `{class_id}` parsed from <body onload="loadSubjects('/ajax/subj/{class_id}/', true)"> on /homework/ | HTML fragment: <select><option value='{course_id}'>subject name</option>; value 0 skipped. The page's own JS calls it with a trailing slash ('/ajax/subj/{id}/'); bvp calls it without. Also used by GetMarksFinal to name courses | bvp/dnevnik76-api `main.go` `Client.GetCourses` | legacy |
| GET | `https://my.dnevnik76.ru/homework/` | Homework list; also the page bvp reads the session context from (class name, class id, school year) | cookie |  | HTML. #homework_list > table.list > tbody > tr: td1 date («17 декабря 2018 г.» style, parsed by russiantime), td2 weekday, td3 > a subject, td4 homework text, td5 lesson topic. Pager #homework_list > div.pager > span.page / span.page_remark. Context: #auth_info > #role «Учащийся (5 "В")» (format per the v0.1.3 regex `Учащийся\n\s+\((\d+) "(.)"\)`; current code only strips «Учащийся (» and «)»), body[onload] loadSubjects('/ajax/subj/{class_id}/', true), #eduyear > #curedy «2018-2019 учебный год». maxxig: select#items_perpage option 50, table.list.mtop, columns date/subject/homework (td1, td3, td4) | bvp/dnevnik76-api `main.go` `Client.GetHomework / Client.getCurrentInfo` | legacy |
| GET | `https://my.dnevnik76.ru/marks/current/` | Marks landing page: list of reporting periods (months, quarters/half-years) of the current school year | cookie |  | HTML. select#mark_range > optgroup > option value = period slug, text = period name («… четверть», «… полугодие», month); for each option bvp then requests /marks/current/{value}/note to read its bounds. Slug shapes: month9..month8 (models.go MarkRange) and edurng{N} (maxxig config, e.g. edurng11385) | bvp/dnevnik76-api `main.go` `Client.GetMarksPeriods` | legacy |
| GET | `https://my.dnevnik76.ru/marks/current/{period}/list/` | Marks for one period as a per-subject list (with average) | cookie | `{period}` as above; maxxig passes a configured selector such as edurng11385; bvp tests pass GetCurrentQuarter() | HTML. #marks > #mark-row: div.mark-label subject; span.mark > a[onclick="showMarkInfo('17 декабря 2018 г. (Понедельник)…')"] grade text; span.mark.avg is the average (skipped by bvp); maxxig uppercases non-numeric marks and reads decimals with a comma. Also used by maxxig get_scores_for_user (reads first //h3 and div#marks) | bvp/dnevnik76-api `main.go` `Client.GetMarksForWithType(p, List)` | legacy |
| GET | `https://my.dnevnik76.ru/marks/current/{period}/note/` | Marks for one period in the «ученический дневник» (weekly diary) layout: per day, subject, topic, homework and marks | cookie | `{period}` month9..month12, month1..month8 (MarkRange) or edurng{N} from #mark_range; 'current' in the path means the current school year, so month9 is September of that year (doc.go) | HTML. GetMarksPeriods requests it WITHOUT the trailing slash (fmt '%s%s/note'); GetMarksForWithType with it. #content > h3 «... с 1 сентября 2018 г. по 31 октября 2018 г.» (period bounds parsed by regex). #marks > div.week > div.dayofweek > div.weekday > h3 «Понедельник (17 декабря 2018 г.)», table tbody tr[title='Тема: …']: td1 subject, td2 homework, td.col-mark > span.mark grades | bvp/dnevnik76-api `main.go` `Client.GetMarksForWithType(p, Note) / GetMarksPeriods` | legacy |
| GET | `https://my.dnevnik76.ru/marks/current/{view}/` | Marks for the current period without naming it (GetMarksCurrent uses view=note) | cookie | `{view}` note \| list \| date | Same HTML as the {period} variants | bvp/dnevnik76-api `main.go` `Client.GetMarksCurrent -> GetMarksForWithType("", Note)` | legacy |
| GET | `https://my.dnevnik76.ru/marks/itog/` | Final marks: quarter/half-year and annual grades per subject | cookie |  | HTML. #marks > #wrap-col > #wrap-marks > div > #mark-row[name='{course_id}']; .mark.itg-q = term grade (a[onclick="showMarkItogInfo('1 четверть…')"]), .mark.itg-y = annual grade | bvp/dnevnik76-api `main.go` `Client.GetMarksFinal` | legacy |
| GET | `https://my.dnevnik76.ru/messages/input/` | Inbox list | cookie | `page?=2` (documented in doc.go; the client reads only the first page and counts pages) | HTML. #content > form > table.list > tbody > tr: td1 input.message_mark[name='marks'][value='{message_id}'] checkbox, td2 > a[href='/messages/input/{id}/'] subject (class 'unread' when unread), td3 sender «ФИО (Школа № 83, Ярославль г)», td4 date «17 декабря 2018 г. 18:09». Pager #content > div.pager > span.page (contains an a; class page_next marks the next page), span.page_remark present when paged; total pages = text of the second-to-last span.page | bvp/dnevnik76-api `main.go` `Client.GetMessages (urlMessages, no trailing slash in code)` | legacy |
| GET | `https://my.dnevnik76.ru/messages/input/{message_id}/` | Read one inbox message | cookie | `{message_id}` numeric, from the inbox checkbox value | HTML. #msgview > div.msg-meta > div.msg-props > div:nth-child(1) «Дата: …», div:nth-child(2) > a:nth-child(2) sender; #msgview > div.msg-text body. Opening it presumably marks it read (not verified) | bvp/dnevnik76-api `main.go` `Client.GetMessage` | legacy |
| GET | `https://my.dnevnik76.ru/teachers/` | Teachers of the pupil's class with their subjects | cookie |  | HTML. #content > table.list > tbody > tr: td2 full name, td3 (> b, else td3 text) subject; td.action_links > a.mailto[href='/messages/new/?to={user_id}@{school_id}'] gives the teacher's login | bvp/dnevnik76-api `main.go` `Client.GetTeachers` | legacy |

**Formats.**

- *Dates*: Russian long-form text, no ISO: «17 декабря 2018 г.», with time «17 декабря 2018 г. 18:09», day header «Понедельник (17 декабря 2018 г.)», period header «с 1 сентября 2018 г. по 31 октября 2018 г.», school year «2018-2019 учебный год». bvp parses with github.com/bvp/russiantime; maxxig replaces month names with numbers and uses '%d %m %Y г.'. Times are local (Europe/Moscow).
- *Ids*: Numeric: region_id KLADR-like 11 digits (76000001000; picker data-value adds '/3'), school_id 6 digits (760215), class_id and course_id integers from select options, message_id integer (checkbox value), login 8 digits (08331111) qualified as '{login}@{school_id}' in the form and in teacher links. Period slugs: month9..month12, month1..month8 and edurng{N} (term, e.g. edurng11385). Grades are integers; averages with a comma decimal; non-numeric marks exist (maxxig uppercases them).
- *Pagination*: Server-rendered pager: div.pager > span.page (span.page_next for next), span.page_remark present when there is more than one page; ?page=N on /messages/input/; page size from cookie items_perpage (10/20/30/50 in UI, bvp sets 1000, maxxig picks 50 in select#items_perpage). No JSON pagination.
- *Notes*: Everything except /ajax/messages_count/ is HTML; /ajax/* returns <select> fragments. 'current' in /marks/current/ means the current school year, so /marks/current/month9/ is September of that year.

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Ярославская область (76) | `my.dnevnik76.ru` | The only instance; regions inside it are municipalities from /ajax/kladr/ (e.g. 76000001000 = г. Ярославль), schools six-digit ids 76xxxx. Closed 02.09.2024 |
| Ярославская область (76), successor | `school.yarcloud.ru` | ГИС «Образование-76», ЭлЖур-based, from 16.09.2024; mobile app «Дневник Ярославской области» |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/bvp/dnevnik76-api | last commit 2023-10-05 (v0.1.6); archived by owner 2025-03-28; 10 commits 2019-07-08..2023-10-05, 2 stars | Go scraper: main.go (all routes, login form fields, headers, cookies, CSS selectors), models.go (period slugs, view types), doc.go (messages paging, items_perpage values, marks views), main_test.go (edu_year and items_perpage cookie use), config_test.json.example (login/region/school id shapes). Full history (unshallowed by the verifier): CSRF selector #login_form -> .login__form in b02a02f 2020-10-12, old #role format «Учащийся (5 "В")» in the v0.1.3 regex, regionID added to NewClient in v0.1.6 |
| https://github.com/maxxig/dnevnik76_telegram_notifier | last commit 2023-10-10 | Selenium login steps (region/school pickers, id_fake_username, id_password, continue-button), /homework/ table, /marks/current/{selector}/list/, period selector edurng11385, region data-value 76000001000/3, crontab every 10 minutes |
| https://github.com/dnevnik76/dnevnik76-files | last commit 2024-08-13; zip files dated 2024-08-03 | Chrome MV3 extension: page list (/, /marks/current/*, /schedule/, /homework/, /stat/, /teachers/, /messages/input/, /settings/, /static/...), title «Региональный интернет-дневник 4.1», nav items |
| https://github.com/dnevnik76/dnevnik76.github.io | last commit 2024-09-08 | Shutdown notice: «Разработка расширения прекращена ... из-за смены эл.дневника в Ярославской области. С 16 числа будет действовать новый дневник по новому адресу» linking https://school.yarcloud.ru |
| https://school52.edu.yar.ru/roditelyam/elektronniy_dnevnik.html |  | «Региональный интернет-дневник по адресу https://my.dnevnik76.ru/ прекращает свою работу с 02.09.2024 г.»; «Новый интернет-дневник начинает работать с 16.09.2024 г. по адресу https://school.yarcloud.ru/»; ЕСИА-only sign-in |
| https://promodoc.ru/education/school-yarcloud |  | school.yarcloud.ru = ГИС «Образование-76», Госуслуги sign-in, app «Дневник Ярославской области», some schools issue individual logins |
| https://github.com/search?type=repositories&q=dnevnik76 |  | Only four repositories: bvp/dnevnik76-api (archived), maxxig/dnevnik76_telegram_notifier, dnevnik76/dnevnik76-files, dnevnik76/dnevnik76.github.io (re-checked by the verifier, same four); searches for 'yarcloud' and 'dnevnik yaroslavl' returned nothing |
| https://github.com/bvp/dnevnik76-api (repository page) |  | Archived by owner on 2025-03-28, GPL-3.0, no README |
| https://pkg.go.dev/github.com/bvp/dnevnik76-api | v0.1.6 published 2023-10-05 | Module listing confirms the exported surface (GetRegions, GetSchools, NewClient, Login, GetCourses, GetMarksCurrent/For/ForWithType/Final/Periods, GetCurrentQuarter, GetHomework, GetMessages, GetMessage, GetMessagesCount, GetTeachers, SetCookie) — no route beyond those in main.go |
| https://sourcegraph.com/.api/search/stream?q=context:global+dnevnik76.ru |  | Only domain lists (Chauncy-lab/Deep-learning-of-DGA, thevillagehacker/Bug-Hunting-Arsenal); no client code |
| WebSearch: "my.dnevnik76.ru" github OR parser OR bot |  | Only bvp/dnevnik76-api and its pkg.go.dev page; the rest are dnevnik.ru clients (a different platform) |

**Caveats.**

- The system no longer answers as a diary: school notices say my.dnevnik76.ru stopped working on 02.09.2024 and was replaced by school.yarcloud.ru from 16.09.2024; not probed live (Russian hosts unreachable from here), the Wayback availability API had no snapshot of /accounts/login/ and the CDX API cannot be fetched from this environment. Every my.dnevnik76.ru route is therefore legacy and unverifiable today.
- Only two real clients exist, both unmaintained: bvp/dnevnik76-api (Go, archived 2025-03-28, last commit 2023-10-05) and maxxig/dnevnik76_telegram_notifier (Selenium, last commit 2023-10-10). There was no official or JSON API; this is HTML scraping of a Django site, and the only JSON response is /ajax/messages_count/.
- /schedule/, /stat/ and /settings/ are known only as links in a browser extension (and / only as the page it overwrites); /messages/new/?to= only as an href on /teachers/; /marks/current/{period}/date/ is built but never parsed. Their status is marked uncertain.
- No write operations (sending messages, marking read, settings) appear in any client; the menu item «Файлы» has no known path.
- Session cookie names are not written in the clients; sessionid/csrftoken are an inference from the Django csrfmiddlewaretoken field.
- bvp calls /messages/input, /ajax/subj/{id} and (from GetMarksPeriods) /marks/current/{period}/note without the trailing slash the site's own links use; Django presumably redirects (APPEND_SLASH), not verified.
- Login success is never checked directly: bvp ignores the POST response and only fails if /homework/ cannot be parsed afterwards.
- The successor school.yarcloud.ru (ЭлЖур-based, ЕСИА sign-in) has no open-source client found here; its API surface belongs with the ЭлЖур platform and was not extracted in this task.
