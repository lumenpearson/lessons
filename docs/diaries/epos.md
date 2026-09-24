# ЭПОС.Школа — Пермский край

*Part of [the electronic diaries of Russia’s regions](../diaries.md). Verification: extracted and checked against the code, not yet dated. Confidence of the whole: medium. Nothing here has been tried against the live service.*

**The regional system of Пермский край**, «Электронная Пермская образовательная система», which
replaced Web2edu in 2019–2020. It is an older fork of the МЭШ code base: the same `core/api`,
`acl/api`, `jersey/api` and `reports/api` families, the same `Auth-Token` and `Profile-Id`
headers, so a reader of the [МЭШ](mesh-moscow.md) page will recognise almost every row.

**Two generations, four years apart.** In 2022 a password form on `cabinet.permkrai.ru` handed a
session over to `school.permkrai.ru`, and the one Python client of that time used both; those
rows are legacy. In 2026 sign-in is Госуслуги through a Keycloak realm at
`auth-epos.permkrai.ru`, and the diary is at `edu-epos.permkrai.ru`. The 2026 rows come from the
string literals of a compiled mobile app: the paths and hosts are exact, but which method each
takes and which query belongs to which path are inferred from the МЭШ conventions, and several
are marked uncertain for that reason.

**Hosts.**

| Role | Base URL | Note |
| --- | --- | --- |
| api | `https://edu-epos.permkrai.ru` | current ЭПОС host (Lyric client 2026): /authenticate*, /core/api, /acl/api, /jersey/api, /reports/api; also serves /download/instructions_for_*.pdf |
| auth | `https://auth-epos.permkrai.ru` | Keycloak, realm epos: /kk/auth/realms/epos/protocol/openid-connect/{auth,token}; ЕСИА/Госуслуги login |
| legacy | `https://cabinet.permkrai.ru` | 2022 cabinet / single sign-on with Laravel CSRF form login (nkrapivin/epos.py); current entry is auth-epos.permkrai.ru via Госуслуги |
| legacy | `https://school.permkrai.ru` | 2022 ЭПОС.Школа host used by nkrapivin/epos.py; the same МЭШ-style APIs are now called on edu-epos.permkrai.ru |
| web | `https://epos.permkrai.ru` | information portal (WordPress theme epos_portal); no API calls seen in clients |
| legacy | `https://web2edu.ru` | predecessor system named in the task; no client code references it |
| other | `https://epos-api.zotov.dev` | Epos Next third-party proxy (private C# backend epos-next/api); clients web and mobile talk only to it |
| other | `https://epos.zotov.dev` | Epos Next web client front-end (from epos-next/docs) |
| other | `https://www.gosuslugi.ru/api/myschool/v1` | federal «Моя школа» API used by the Lyric client as an alternative backend (tokens from cookies acc_t and u after a WebView login at https://www.gosuslugi.ru/school/feed); not ЭПОС |

**Signing in.**

*Current: Госуслуги (ЕСИА) via Keycloak auth-epos.permkrai.ru, then ЭПОС session cookies (Lyric client, legacy OAuth mode)* — token carried as header Auth-Token (auth_token cookie value) + header Profile-Id (profile_id cookie); cookies auth_token/profile_id also sent. Lifetime: not visible in the client (it decodes a JWT exp: symbol _jwtExp). Refresh: Keycloak refresh_token (scope offline_access), then the /authenticate/oauth hand-off again.

1. Open in a WebView https://edu-epos.permkrai.ru/authenticate/oauth?app=phone&mode=epos&sub_provider=esia (ЕСИА entry point) or the Keycloak authorize endpoint https://auth-epos.permkrai.ru/kk/auth/realms/epos/protocol/openid-connect/auth with response_type=code, client_id, redirect_uri (custom scheme diaryperm://), scope "openid offline_access", state, code_challenge (S256) - PKCE
2. The user signs in at Госуслуги; the redirect carries code
3. POST https://auth-epos.permkrai.ru/kk/auth/realms/epos/protocol/openid-connect/token (application/x-www-form-urlencoded) grant_type=authorization_code, code, code_verifier, redirect_uri, client_id -> access_token, refresh_token, expires_in (the app stores this «OAuth-пара»)
4. «Phase C» in a hidden WebView: https://edu-epos.permkrai.ru/authenticate/oauth?mode=epos&groupRole=student, whose redirect chain sets the cookies auth_token and profile_id on edu-epos.permkrai.ru
5. Call /core/api, /acl/api, /jersey/api, /reports/api on edu-epos.permkrai.ru with headers Auth-Token/auth-token and Profile-Id/profile-id plus the cookies, and ?pid=<profile id> on most calls
6. Renew: POST the token endpoint with grant_type=refresh_token, then repeat Phase C

   Reconstructed from string literals of the Lyric APK (Dart AOT, not decompilable with blutter for Dart 3.13); the client_id value was not identified. The app labels this mode «Legacy: PKCE + refresh_token + Phase C через скрытый WebView».

*Current: device-token flow (Lyric 1.1.7 «Новая система логина в ЭПОС»)* — token carried as header Auth-Token + Profile-Id (session); device_token/long_token kept by the client. Lifetime: unknown. Refresh: device_token -> new auth_token via /authenticate*.

1. Interactive login once (WebView through Госуслуги as above); the client generates and keeps a device_key (storage key epos_device_key_v1)
2. Exchange at https://edu-epos.permkrai.ru/authenticate/loginEom and /authenticate (DeviceExchangePayload -> DeviceExchangeResult: device_token (UUID), auth_token (UUID), long_token)
3. Later sessions are renewed from device_token without the WebView («Обмен токена на сессию...»); the debug screen describes «три запроса /authenticate»
4. Session stored as epos_session_v1; data calls as in the flow above with Auth-Token / Profile-Id

   Methods, bodies and exact order are not recoverable from the binary; only the URLs, the payload class names and the Russian debug strings are.

*(legacy, 2022) Cabinet password login + OAuth hand-off into ЭПОС.Школа (nkrapivin/epos.py)* — token carried as header auth-token (plus header profile-id and the session cookies auth_token/profile_id); logout passes query authentication_token. Lifetime: short: epos-next/docs says the original ЭПОС token lives about an hour (summary) and caches it 10 minutes in redis. Refresh: none; log in again.

1. GET https://cabinet.permkrai.ru/login with a Chrome-like header set; scrape the CSRF token from <meta name="csrf-token" content="..." id="csrf">; read cookie XSRF-TOKEN
2. Set headers x-csrf-token (scraped token) and x-xsrf-token (XSRF-TOKEN cookie); this GET /login refresh is repeated before every cabinet call and before GET school.permkrai.ru/authenticate (not before the school API calls, though the headers stay on the session)
3. POST https://cabinet.permkrai.ru/login form {_token, login, password}; success = status < 400
4. Optionally POST https://cabinet.permkrai.ru/check_agreement (JSON)
5. GET https://school.permkrai.ru/authenticate?mode=oauth&app=<rsaags student \| rsaag parent \| rsaa teacher>; the redirect chain sets cookies auth_token and profile_id on school.permkrai.ru
6. Copy cookie auth_token into header auth-token and cookie profile_id into header profile-id
7. POST https://school.permkrai.ru/lms/api/sessions?pid=<profile_id> JSON {auth_token}; response id = user id, profiles[0].id = profile id used as pid/student_profile_id
8. Call the /core/api, /acl/api, /reports/api, /notification/api endpoints with ?pid=<profile id> and headers auth-token/profile-id (cookies still attached)
9. Log out: DELETE /lms/api/sessions?authentication_token=<auth-token> (json []) and GET cabinet /logout

   ЕСИА/Госуслуги login existed on the cabinet but epos.py does not implement it (commented placeholder "login_gosuslugi O_O"); the 2026 Lyric client does, see the first flow.

*Epos Next proxy JWT (epos-next web/mobile)* — token carried as header Authorization: Bearer <access JWT>. Lifetime: access 10 minutes, refresh 365 days (ExpiresRules.kt). Refresh: POST /api/1.0/auth/reauthenticate {refresh, id}.

1. POST https://epos-api.zotov.dev/api/1.0\|1.1/auth/authenticate {email, password} (the ЭПОС credentials)
2. On a first login only, the proxy logs in to the original ЭПОС, stores the user (and the e-mail/password) in its DB and caches the ЭПОС auth_token in redis for 10 minutes; a repeat login makes no upstream call. It returns its own JWTs {access\|auth, refresh} and id
3. Send Authorization: Bearer <access> on every call
4. On 401 (web: 401 or 403) POST /api/1.0/auth/reauthenticate {refresh, id} and retry once
5. Upstream ЭПОС is re-logged lazily on a data request when the redis auth_token has expired, with the stored e-mail and password

   Third-party service; the proxy keeps users' ЭПОС passwords in its database to re-login upstream.

**Headers the clients send.**

| Header | Value | Why |
| --- | --- | --- |
| `Auth-Token` | auth_token cookie value (UUID) | ЭПОС API authentication on edu-epos.permkrai.ru (both spellings Auth-Token and auth-token are literals in Lyric) |
| `Profile-Id` | profile_id cookie value | selects the active profile (Lyric literal Profile-Id / profile-id) |
| `auth-token` | auth_token cookie value | ЭПОС API authentication (МЭШ convention) |
| `profile-id` | profile_id cookie value | selects the active profile |
| `x-csrf-token` | scraped from cabinet /login meta tag | Laravel CSRF on cabinet.permkrai.ru |
| `x-xsrf-token` | XSRF-TOKEN cookie | Laravel CSRF on cabinet.permkrai.ru |
| `x-requested-with` | XMLHttpRequest | imitate the browser SPA |
| `user-agent` | Chrome 98 on Windows string plus sec-ch-ua headers | the client imitates Chrome 'as much as possible' |

**Captcha and second factor.** Current login is Госуслуги/ЕСИА (whatever 2FA ЕСИА imposes happens inside the WebView; no client automates it). The 2022 cabinet form had no captcha in epos.py.

Two generations. 2022: cabinet.permkrai.ru password form + school.permkrai.ru (nkrapivin/epos.py). 2026: auth-epos.permkrai.ru Keycloak realm epos + ЕСИА, APIs on edu-epos.permkrai.ru (Lyric APK). The pid query parameter is sent on most API calls in both. The Lyric APK bundles a TrustAsia LiteSSL RSA CA 2025 chain (assets/certs/epos_chain.pem) for edu-epos.permkrai.ru.

**Routes** — 47 rows. Status: *current* is what a maintained client calls today; *legacy* is a generation the code or its author has marked as old; *uncertain* was seen in one place and nowhere else.

| Method | Path | Purpose | Auth | Parameters | Answer | Seen in | Status |
| --- | --- | --- | --- | --- | --- | --- | --- |
| GET | `https://edu-epos.permkrai.ru/acl/api/users` | User records by id list (names of teachers/users) | header_token | `ids` (comma-separated); `pid` | JSON list (literal /users?ids= appended to https://edu-epos.permkrai.ru/acl/api) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/teachers_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/authenticate/oauth` | ЕСИА entry point of the ЭПОС phone app: starts the OAuth login through Госуслуги (opened in a WebView) | none | `app=phone`; `mode=epos`; `sub_provider=esia` | Redirect chain via auth-epos.permkrai.ru and Госуслуги; ends with ЭПОС cookies (full URL literal https://edu-epos.permkrai.ru/authenticate/oauth?app=phone&mode=epos&sub_provider=esia) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/screens/login_screen.dart` | current |
| GET | `https://edu-epos.permkrai.ru/authenticate/oauth` | «Phase C»: role-scoped hand-off (student) from the Keycloak session into ЭПОС, in a hidden WebView | cookie | `mode=epos`; `groupRole=student` | Sets cookies auth_token and profile_id on edu-epos.permkrai.ru (full URL literal https://edu-epos.permkrai.ru/authenticate/oauth?mode=epos&groupRole=student) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/epos_oauth_client.dart (_handleEduPhase)` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/academic_years` | Academic years (current year used for everything else) | header_token | `pid` (profile id) | JSON array of years (full URL literal ...?pid=) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/academic_years_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/bells_timetables` | Bell timetables with their day timetables | header_token | `ids`; `include=bells_day_timetables`; `academic_year_id`; `pid` | JSON (literal fragment &include=bells_day_timetables&academic_year_id=). Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/bells_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/marks` | Marks of a student in a date range | header_token | `student_profile_id`; `created_at_from` (fragment &created_at_from=); `created_at_to` (fragment &created_at_to=); `page=1`; `per_page=1000 or 50 (both fragments exist)`; `pid` | JSON list of marks. Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/marks_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/rooms` | Rooms by id (lesson cards) | header_token | `ids`; `pid` | JSON list | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/models/room_info.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/student_homeworks` | Homework of a student in a date range | header_token | `begin_date`; `end_date` (fragment &end_date=); `student_profile_id`; `types=all` (fragments &types=all&pid= / &types=all&page=1&per_page=200&pid=); `page`; `per_page`; `pid` | JSON list of student homeworks with attachments (both a full URL literal and a relative /core/api/student_homeworks?begin_date= exist). Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/homework_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/student_profiles/{id}` | Student profile with attendance (class unit, groups, schools) | header_token | `{id}` student profile id; `with_attendance=true` (fragment &with_attendance=true&pid=); `pid`; `academic_year_id?` (fragment ?academic_year_id= / &academic_year_id=) | JSON profile; groups feed the schedule call (literal /student_profiles/ appended to https://edu-epos.permkrai.ru/core/api). Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/profile_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/core/api/teacher_profiles` | Teacher profiles of the year (teacher list with subjects) | header_token | `academic_year_id`; `ids?` (fragment &ids=); `pid` | JSON list (full URL literal ...teacher_profiles?academic_year_id=). Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/teachers_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/jersey/api/schedule_items` | Schedule (lessons) for a date range and the student's groups | header_token | `from`; `to` (fragment &to=); `student_profile_id` (fragment &student_profile_id=); `with_group_class_subject_info=true`; `with_rooms_info=true`; `with_course_calendar_info=true`; `with_lesson_info=true`; `with_column=true`; `cancelled=false`; `group_id` (comma-separated group ids from the profile) | JSON list of schedule items (full URL literal ...schedule_items?from=; the with_* block is one literal ending in &group_id=). Query fragments are separate literals in the binary; their assignment to this path is inferred from МЭШ conventions. | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/schedule_service.dart` | current |
| GET | `https://edu-epos.permkrai.ru/reports/api/progress/json` | Progress report (grades by subject and period) | header_token | `academic_year_id`; `student_profile_id`; `hide_half_years=true`; `pid` | JSON (full URL literal ...progress/json?academic_year_id=, fragment &hide_half_years=true&pid=) - same route as the 2022 school.permkrai.ru row | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/grades_service.dart` | current |
| GET | `https://auth-epos.permkrai.ru/kk/auth/realms/epos/protocol/openid-connect/auth` | Keycloak authorize (PKCE) for ЭПОС, realm epos; leads to Госуслуги/ЕСИА login in a WebView | none | `response_type=code`; `client_id` (value not identified in the binary); `redirect_uri` (custom scheme diaryperm://); `scope=openid offline_access`; `state`; `code_challenge`; `code_challenge_method=S256` | Browser redirect to redirect_uri with code | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/epos_oauth_client.dart (EposOAuthClient, generateVerifier, _handlePkcePhase)` | uncertain |
| POST | `https://auth-epos.permkrai.ru/kk/auth/realms/epos/protocol/openid-connect/token` | Keycloak token endpoint: code exchange and refresh | none | body (application/x-www-form-urlencoded): `grant_type` authorization_code or refresh_token, `code`, `code_verifier`, `redirect_uri`, `client_id`, `refresh_token` | {access_token, refresh_token, expires_in, ...} (standard Keycloak) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/epos_oauth_client.dart (exchangeCode)` | uncertain |
| UNKNOWN | `https://edu-epos.permkrai.ru/authenticate` | Session (re)establishment: exchanges device_token / cookies for an ЭПОС session; the debug screen issues «три запроса /authenticate» | other |  | Debug strings «authenticate: HTTP », «authenticate: пусто»; result carries auth_token and profile | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/auth_coordinator.dart / session_refresh_service.dart` | uncertain |
| UNKNOWN | `https://edu-epos.permkrai.ru/authenticate/loginEom` | Device-token login step of the new flow («Новая система логина в ЭПОС», Lyric 1.1.7); method and body not recoverable | other |  | Part of DeviceExchangePayload -> DeviceExchangeResult (device_token UUID, auth_token UUID, long_token) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/device_token_auth.dart (_doLoginEom)` | uncertain |
| UNKNOWN | `https://edu-epos.permkrai.ru/authenticate/student_profile` | Select / fetch the student profile for the session after authentication (full URL literal) | cookie |  | not recoverable | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/core/auth/*` | uncertain |
| GET | `https://edu-epos.permkrai.ru/core/api/attachments` | Homework attachments metadata | header_token | `pid`; `ids` (fragment &ids=) | JSON list (literal /core/api/attachments?pid=) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/homework_attachments_service.dart` | uncertain |
| GET | `https://edu-epos.permkrai.ru/core/api/attachments/{id}` | Download one attachment | header_token | `{id}` | file bytes (literal /core/api/attachments/) | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/services/attachment_service.dart` | uncertain |
| GET | `https://edu-epos.permkrai.ru/core/api/profiles` | Profiles by id (debug screen; the literal carries a hard-coded id 17938056) | header_token | `ids=17938056` | JSON list | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/screens/developer_screen.dart` | uncertain |
| GET | `https://edu-epos.permkrai.ru/core/api/student_homeworks/{id}` | One student homework (details sheet) | header_token | `{id}` student homework id; `pid` | JSON object | makaYtech/lyric_apk `releases/download/v1.1.7/lyric-1.1.7-beta.apk -> lib/arm64-v8a/libapp.so (Dart AOT string literals)` `package:lyric/widgets/homework/homework_details_sheet.dart` | uncertain |
| POST | `https://cabinet.permkrai.ru/check_agreement` | Check whether the user has accepted the user agreement (personal-data consent) after login | cookie | body: none | JSON (shape not documented in the client) | nkrapivin/epos.py `epos.py` `EposClient.check_agreement` | legacy |
| GET | `https://cabinet.permkrai.ru/login` | Load the ЭПОС cabinet (single sign-on) login page to obtain the CSRF token and the XSRF-TOKEN cookie before any cabinet POST | none |  | HTML; token scraped between '"csrf-token" content="' and '" id="csrf"'; sets cookie XSRF-TOKEN (Laravel-style) plus session cookie | nkrapivin/epos.py `epos.py` `EposClient.__refreshcsrf__` | legacy |
| POST | `https://cabinet.permkrai.ru/login` | Sign in to the cabinet with login (e-mail) and password | cookie | body (application/x-www-form-urlencoded): `_token` the scraped CSRF token (same as x-csrf-token), `login` user login, the test script asks for an e-mail, `password` | Success judged only by HTTP status < 400; establishes the cabinet session cookie used by /authenticate on school.permkrai.ru | nkrapivin/epos.py `epos.py` `EposClient.login_password` | legacy |
| GET | `https://cabinet.permkrai.ru/logout` | End the cabinet session | cookie |  | Status < 400 means success | nkrapivin/epos.py `epos.py` `EposClient.logout` | legacy |
| POST | `https://epos-api.zotov.dev/api/1.0/advertisement` | Create a class advertisement (proxy feature, not in ЭПОС) | bearer | body (application/json): `content`, `targetDate` kotlinx LocalDateTime (ISO date-time) the ad stays relevant until | {success, id} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.createAdvertisement` | legacy |
| GET | `https://epos-api.zotov.dev/api/1.0/app-version` | Check whether the mobile app is up to date (third-party Epos Next proxy) | bearer | `platform=0`; `versionId` (app versionCode) | {type: up-to-date\|new-update\|new-major-update, success, version} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.appVersion` | legacy |
| POST | `https://epos-api.zotov.dev/api/1.0/auth/authenticate` | Same login, version used by the web client and documented in the OpenAPI (third-party Epos Next proxy) | none | body (application/json): `email`, `password` | {success:true, tokens:{auth\|access, refresh}, id}; 400 {success:false, error:'not-validated-error', errors:[{path,error,message}]}; the web client takes its base URL from env GATSBY_SERVER_URL (.env.development = https://localhost:5001), epos-api.zotov.dev is hard-coded only in the mobile client | epos-next/web `src/utils/api/routes.ts` `ApiRoutes.authenticate / ApiService.authenticate` | legacy |
| POST | `https://epos-api.zotov.dev/api/1.0/auth/reauthenticate` | Refresh the proxy JWT pair (third-party Epos Next proxy) | none | body (application/json): `refresh` refresh JWT, `id` proxy user id | {success, tokens:{access, refresh}, id} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/Auth.kt` `handleUnauthorizedStatus` | legacy |
| POST | `https://epos-api.zotov.dev/api/1.0/control-work` | Create a control work (test) entry shared with classmates (proxy feature, not in ЭПОС) | bearer | body (application/json): `lesson`, `date` kotlinx LocalDateTime (ISO date-time), not a bare date, `name` | {success, id} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.createControlWork` | legacy |
| GET | `https://epos-api.zotov.dev/api/1.0/data` | Same aggregated data object, web client / OpenAPI version (third-party Epos Next proxy) | bearer |  | {success:true, data:{user{id,name}, lessons[{id,subject,groupId,room,lessonNumber,duration,date}], homework[{id,lesson,content,done}], controlWorks[{id,lesson,date,name}], advertisements[{id,content,targetDate}], marks{<subject>:{periods[{all[{value,date,topic,name}],total}],total}}}}; the web client takes its base URL from env GATSBY_SERVER_URL (.env.development = https://localhost:5001), epos-api.zotov.dev is hard-coded only in the mobile client | epos-next/web `src/utils/api/routes.ts` `ApiRoutes.fetchData / ApiService.getData` | legacy |
| GET | `https://epos-api.zotov.dev/api/1.0/data/lessons` | Lessons in a date range, web client version (third-party Epos Next proxy) | bearer | `from` (Date.toISOString()); `to` (Date.toISOString(), inclusive) | {success, data:[lesson]}; 400 carries errors[{path,error,message}]; the web client takes its base URL from env GATSBY_SERVER_URL (.env.development = https://localhost:5001), epos-api.zotov.dev is hard-coded only in the mobile client | epos-next/web `src/utils/api/routes.ts` `ApiRoutes.fetchLessons / ApiService.getLessons` | legacy |
| PUT | `https://epos-api.zotov.dev/api/1.0/homework/{id}/cancel-complete` | Unmark a homework item (third-party Epos Next proxy) | bearer | `{id}` homework id | {success:true} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.cancelCompleteHomework` | legacy |
| PUT | `https://epos-api.zotov.dev/api/1.0/homework/{id}/complete` | Mark a homework item done (proxy-side state, third-party Epos Next proxy) | bearer | `{id}` homework id | {success:true} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.completeHomework` | legacy |
| PUT | `https://epos-api.zotov.dev/api/1.0/user` | Update proxy user profile (third-party Epos Next proxy) | bearer | body (application/json): `name`, `username` without @, `dateOfBirth` LocalDateTime | User object | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.updateUser` | legacy |
| POST | `https://epos-api.zotov.dev/api/1.1/auth/authenticate` | Epos Next proxy login with ЭПОС e-mail and password (third-party Epos Next proxy, not the regional server; it replays the user's ЭПОС e-mail+password upstream) | none | body (application/json): `email`, `password` | {success, tokens:{access, refresh}, user}; 400 = invalid credentials | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.authenticate / ApiImpl.authenticate` | legacy |
| GET | `https://epos-api.zotov.dev/api/1.1/data/lessons` | Lessons in a date range (third-party Epos Next proxy) | bearer | `from=2021-05-31` (kotlinx LocalDate.toString()); `to=2021-06-06` | {success, data:[lesson]} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.fetchLesson / ApiImpl.fetchLessons` | legacy |
| GET | `https://epos-api.zotov.dev/api/1.2/data` | Everything at once: user, lessons of the current week, homework, control works, advertisements, marks (third-party Epos Next proxy) | bearer |  | {success, data:{user, lessons[], homework[], controlWorks[], advertisements[], marks}} | epos-next/mobile `shared/src/commonMain/kotlin/epos_next/app/network/ApiRoutes.kt` `ApiRoutes.data / ApiImpl.getData` | legacy |
| GET | `https://school.permkrai.ru/acl/api/system_messages` | System-wide announcements (maintenance / outage banners) | header_token | `pid`; `published=true`; `today=true` | JSON (shape not described) | nkrapivin/epos.py `epos.py` `EposClient.epos_get_system_messages` | legacy |
| GET | `https://school.permkrai.ru/acl/api/users` | Fetch user records by id list | header_token | `ids=123,456` (comma-separated user ids); `pid` | JSON list of users | nkrapivin/epos.py `epos.py` `EposClient.epos_get_users` | legacy |
| GET | `https://school.permkrai.ru/authenticate` | OAuth hand-off from the cabinet session into ЭПОС.Школа; selects the role application and sets the ЭПОС session cookies | cookie | `mode=oauth`; `app=rsaags` (rsaags = student, rsaag = parent, rsaa = teacher) | Redirect chain via cabinet.permkrai.ru; afterwards cookies auth_token and profile_id exist and are copied into request headers auth-token and profile-id | nkrapivin/epos.py `epos.py` `EposClient.auth_epos / auth_epos_student / auth_epos_parent / auth_epos_teacher` | legacy |
| GET | `https://school.permkrai.ru/core/api/academic_years` | List academic years | header_token | `pid` (profile id) | JSON array of years; the client takes the last element's 'id' as the current academic_year_id | nkrapivin/epos.py `epos.py` `EposClient.epos_get_academic_years` | legacy |
| GET | `https://school.permkrai.ru/core/api/student_profiles/{id}` | Student profile (class unit, school, groups) for a profile id | header_token | `{id}` student profile id (= profile id from /lms/api/sessions); `pid`; `academic_year_id?` (omitted when not given) | JSON object; note the client's URL expression is broken by operator precedence (without academic_year_id it requests an empty URL) | nkrapivin/epos.py `epos.py` `EposClient.epos_get_student_profiles` | legacy |
| DELETE | `https://school.permkrai.ru/lms/api/sessions` | Log out of ЭПОС.Школа (destroy the auth token) | query_token | `authentication_token` (the auth-token value); body (application/json): `(empty array [])` client sends json=[] | Status < 400 means success | nkrapivin/epos.py `epos.py` `EposClient.epos_logout` | legacy |
| POST | `https://school.permkrai.ru/lms/api/sessions` | Create/read the ЭПОС LMS session: returns the user and his profiles (МЭШ-style sessions endpoint) | header_token | `pid` (profile id from the profile_id cookie); body (application/json): `auth_token` the auth_token cookie value | JSON object; 'id' = user id, 'profiles' = list, profiles[0]['id'] = profile id used as pid / student_profile_id everywhere else | nkrapivin/epos.py `epos.py` `EposClient.epos_get_sessions` | legacy |
| GET | `https://school.permkrai.ru/notification/api/notifications/status` | Notification counters/status for a student | header_token | `pid`; `student_id` (the client passes the profile id) | JSON | nkrapivin/epos.py `epos.py` `EposClient.epos_get_notifications` | legacy |
| GET | `https://school.permkrai.ru/reports/api/progress/json` | Progress report: marks per subject per period (успеваемость) | header_token | `academic_year_id`; `hide_half_years=true`; `pid`; `student_profile_id` | JSON (МЭШ-style progress report; shape not described in client) | nkrapivin/epos.py `epos.py` `EposClient.epos_get_progress` | legacy |

**Formats.**

- *Dates*: ЭПОС direct: not visible in epos.py (МЭШ-derived APIs typically use YYYY-MM-DD). Epos Next proxy: ISO dates/datetimes (e.g. 2021-05-31T09:55:00.000; web sends Date.toISOString(), mobile LocalDate YYYY-MM-DD).
- *Ids*: Integer ids: user id and profile id from /lms/api/sessions; profile id doubles as pid, student_profile_id and student_id; academic_year_id from /core/api/academic_years (last element = current). Lyric: auth_token and device_token are UUIDs; group ids are passed comma-separated in group_id.
- *Pagination*: МЭШ-style page/per_page query parameters in the 2026 client (per_page 50, 200, 1000 seen), always page=1; none in the 2022 client.
- *Notes*: Booleans in query strings as lowercase true/false; user id list as comma-separated ids.

**Regional instances the clients or the vendor name.**

| Region | Host | Note |
| --- | --- | --- |
| Пермский край | `edu-epos.permkrai.ru` | current ЭПОС diary/journal API host (Lyric 2026) |
| Пермский край | `auth-epos.permkrai.ru` | Keycloak realm epos, Госуслуги entry |
| Пермский край | `school.permkrai.ru` | ЭПОС.Школа diary/journal and APIs (legacy host, 2022 client) |
| Пермский край | `cabinet.permkrai.ru` | login cabinet (legacy host, 2022 client) |
| Пермский край | `epos.permkrai.ru` | information portal |
| Пермский край | `web2edu.ru` | predecessor system (not in code) |

**Sources read.**

| Source | Activity | What it gave |
| --- | --- | --- |
| https://github.com/nkrapivin/epos.py | 2022-05-02 | The only direct ЭПОС client: cabinet login with CSRF, OAuth hand-off with app codes, 9 school.permkrai.ru API routes, headers; main.py shows call order and response fields id/profiles |
| https://github.com/epos-next/mobile | 2022-04-27 | Epos Next proxy routes (ApiRoutes.kt, ApiImpl.kt, Auth.kt, NetworkClient.kt), token lifetimes |
| https://github.com/epos-next/web | 2022-01-17 | Proxy routes v1.0, axios refresh interceptor, client-side rate limit 3 req/2 s |
| https://github.com/epos-next/api-docs | 2022-01-06 | OpenAPI of the proxy with response shapes |
| https://github.com/epos-next/docs | 2022-01-17 | Description of how the proxy uses the original ЭПОС auth_token (redis cache, re-login with stored password); names private repo epos-next/api |
| https://github.com/Ev-genia/python_epos_permkrai | 2022-07-06 | Nothing: Python course exercises |
| https://github.com/Ev-genia/cpp_epos_permkrai | 2022-05-30 | Nothing: C++ exercises |
| https://github.com/MrYourCarrot/edu-epos.permkrai.ru | 2025-12-26 | Nothing: static HTML mock of a grades page |
| https://github.com/autotests-cloud/permkrai_api_tests | 2020-08-06 | Nothing relevant: asn.permkrai.ru transport-tax calculator tests |
| https://github.com/search?type=repositories&q=permkrai |  | Discovery: no further ЭПОС clients (also searched 'epos perm', 'web2edu', 'эпос дневник', 'epos school perm'; Sourcegraph for the hosts returned 0 matches) |
| https://github.com/orgs/epos-next/repositories |  | Org lists docs, api-docs, web, mobile; backend epos-next/api is private (clone requires auth) |
| https://github.com/makaYtech/lyric_apk | 2026-09-22 (v1.1.7 beta) | Current unofficial Flutter client «Лирика»: new hosts edu-epos.permkrai.ru and auth-epos.permkrai.ru (Keycloak realm epos, PKCE, ЕСИА), device-token flow via /authenticate/loginEom, and 14 МЭШ-style data paths; also a federal «Моя школа» backend. Found via github.com/search?type=repositories&q=ЭПОС. blutter failed (Dart 3.13 merged snapshot), so only string literals were used. |
| https://github.com/worawit/blutter | 2026-08-18 | Nothing: cannot parse libapp.so of Dart 3.13.3 (no _kDartVmSnapshotData symbol) |
| WebSearch "edu-epos.permkrai.ru" |  | ЭПОС entry points are epos.permkrai.ru / auth-epos.permkrai.ru, login requires a confirmed Госуслуги account; edu-epos.permkrai.ru hosts /download/instructions_for_students.pdf |

**Caveats.**

- Two generations of the system. 2022 (nkrapivin/epos.py): cabinet.permkrai.ru password form + school.permkrai.ru APIs, now marked legacy. 2026 (Lyric APK): auth-epos.permkrai.ru Keycloak/ЕСИА + edu-epos.permkrai.ru APIs, same МЭШ path family (core/api, acl/api, jersey/api, reports/api).
- The 2026 routes come from string literals in a Dart AOT binary: paths and hosts are exact, but HTTP methods (assumed GET for reads) and which query fragment belongs to which path are inferred from МЭШ conventions. Unassigned fragments: &all_schools=true&pid=, &page=1&per_page=50&pid=, /updates, /datamart?_= (the last two may belong to «Моя школа»).
- The /authenticate, /authenticate/loginEom and /authenticate/student_profile methods and bodies are unknown (method recorded as UNKNOWN).
- The Keycloak client_id used by the app was not identified; the redirect scheme literal is diaryperm://.
- Lyric also talks to the federal «Моя школа» (https://www.gosuslugi.ru/api/myschool/v1, WebView login at https://www.gosuslugi.ru/school/feed, cookies acc_t and u); no paths under it were identified and it is not ЭПОС, so no rows.
- No schedule, homework or marks endpoints appear in the 2022 client; the 2026 client supplies them (jersey/api/schedule_items, core/api/student_homeworks, core/api/marks).
- epos.py get_student_profiles has an operator-precedence bug: without academic_year_id it requests an empty URL.
- epos-next web/mobile never call ЭПОС; they call the third-party proxy epos-api.zotov.dev whose backend (epos-next/api) is private. Those routes are listed as legacy (last activity 2022–2023) and describe the proxy, not the regional system.
- The ЕСИА/Госуслуги login is implemented only by the Lyric client (WebView + Keycloak); the 2022 cabinet form login is presumably gone, since ЭПОС now requires a confirmed Госуслуги account.
- web2edu.ru and epos.permkrai.ru appear in no client code.
- No live verification possible: regional hosts are not reachable from this environment.
