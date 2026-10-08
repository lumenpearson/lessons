# Signing in on the diary's own page, and an in-app browser for every link

Status: **sections 1–5 approved by the owner on 5 October 2026**, through questions and answers in
the session. The written spec is waiting for the owner's review. It is not built yet, and is planned
for after stage 3b-1 of sub-project 3 merges and before the monitoring work.

Milestone: `v0.9.0 — NetSchool e-diary, onboarding via the school's diary`.

Closes:
- **#343**, the second factor at password sign-in;
- **#143**, a diary session opened on the phone is invisible to the bot.

It also revisits **#135**, whose second and third questions were answered «no Госуслуги».

## The owner's words, and what they decided

The request, in the owner's own words:

- «ты сделаешь аутентификацию через сайт госуслуги с кнопкой входа, но в конце беря сессию для
  дневника. то есть, вход будет происходить не на нашей стороне ВООБЩЕ»;
- «я про все провайдеров, если у них вход не через госуслуги, то делаешь таким же образом как с
  госуслугами: авторизация на их стороне, а сессия хранится у нас»;
- «сессия с автопродлением»;
- «сделай любое открытие внешних ссылок в приложении - внутри него самого же … собственное
  веб-вью, на базе нативного веб-вью, которое уже имеется, с собственным дизайном подстать
  приложению и material design 3e»;
- «без того, чтобы тянуть весь движок с собой».

| Question | The owner's answer |
| --- | --- |
| The paths where a password passes through our server: the bot's `/diary/signin` page and `POST /api/v1/diary/login` | **Remove them all.** |
| How the diary reaches the bot once those pages are gone | **Through the linked phone** (#143). |
| How the app takes the diary's session after a sign-in on the diary's side | **A WebView**, from which only the diary's own session is taken. |
| What opens ordinary external links | **The system WebView for everything.** It is one component, and the APK carries no engine. |

## Why

**A real Petersburg account cannot sign in today.** The password is right, but the diary asks
for a code from SMS or MAX, and the app's own password form has no second-factor step (#343).
Signing in on the diary's own page makes every second factor the diary's problem: an SMS code,
a MAX confirmation, or Госуслуги with its own codes. It also opens the three «Сетевой город»
regions that admit only Госуслуги (Altai Krai, Primorye, Tula), which the app hides today.

The owner's second reason is the stronger one. With this change, a password reaches neither
the phone's code nor our server. Today the app's form sees the password, the bot's page sends
it through the server, and the legacy `/login` route does too.

## What this reverses, and where the old reason still holds

`docs/diaries.md` (lines 311–316 and 411), `docs/architecture.md` (line 849), `docs/history.md`
(lines 2578–2582) and `HANDOVER.md` all record a decision: «no Госуслуги sign-in, no WebView and
no cookie capture anywhere». The reason given is that a Госуслуги session is a session to the
person's whole state-services account. The privacy policy and the terms promise the same thing
in public: «В Госуслуги приложение не входит никогда».

The reason was right about what it named, and this design keeps it. **The app never takes,
reads or keeps a Госуслуги session.** It takes only the *diary's* own session: the cookie or
token the diary issues for its own domain once Госуслуги has handed the person back. Every
Госуслуги cookie dies with the page, wiped together with everything else (section 2).

The one place where the diary's session *is* the Госуслуги session is ТОР «Моя школа». There
the diary lives on `gosuslugi.ru/school`, and its cookies are the state-services account's. So
ТОР stays exactly what it is today: a link to its own site, now opened in the in-app browser,
with no capture. The old decision stands for ТОР unchanged.

The public promise changes its wording, not its substance (section 5).

## 1. The in-app browser

**One component.** It is a full-screen Compose screen hosting the system `android.webkit.WebView`.
- That is Android System WebView, preinstalled on the phone and updated through Google Play.
- The APK carries no engine, so this adds 0 MB.
- No Custom Tabs, no GeckoView, and no `androidx.browser`.

**Every external `http(s)` link the app opens goes to it.** Today that is:
- `OpenLink.kt`, `AboutCard.kt` (two call sites) and `TranslationSessionSheet.kt`;
- the five `LocalUriHandler` users: `DeveloperScreen`, `LicensesSheet`, `SupportSheets`,
  `TelegramLinkRows` and `UpdateHost`;
- `ProviderStep.kt`'s hand-off.

Two seams catch them all:
- `LocalUriHandler` is overridden at the app's root, so its users need no change;
- `OpenLink.kt` routes the same way, and the direct `ACTION_VIEW` call sites move onto it.

**What still leaves the app**, because a web page cannot do it:
- **`t.me` and `telegram.me` links** open Telegram itself. Otherwise «Открыть бота» would show a
  page that cannot start the bot.
- **`mailto:`, `tel:`, `intent:` and any non-`http(s)` scheme**, the MAX confirmation link
  (`intent://max.ru…`) included.
- **File downloads**, through `DownloadListener`. The APK of an update is the case that matters,
  because a WebView cannot install anything.

**The browser forgets.** Closing it wipes cookies (`CookieManager.removeAllCookies` and `flush`),
web storage (`WebStorage.deleteAllData`) and the cache, and destroys the WebView. Every page
starts clean, and nothing a page left behind can be read by the next one. It costs a site
sign-in that does not survive closing the page; nothing the app links to needs one.

**What a page may not do:**
- no file or content access (`allowFileAccess` and `allowContentAccess` off);
- no `addJavascriptInterface`, ever;
- no geolocation, camera or microphone, so every permission request is denied;
- no third-party cookies;
- no mixed content;
- Safe Browsing on.

JavaScript is on, because the diaries and Госуслуги need it.

**The design is the app's own, in Material 3 Expressive.** The app already uses Expressive:
`Theme.kt`, `LoadingIndicator` and `WavyProgress`.
- **The top app bar** carries the page's title, with its host below. The host is the part a
  person checks, and it carries a lock when the connection is HTTPS.
- **The buttons:** «Закрыть», «Назад» (which walks the page's history first) and «Обновить».
- **Loading** shows the wavy progress the app already has.
- **The overflow menu** holds «Открыть в браузере», «Копировать ссылку» and «Поделиться». It is
  hidden in sign-in mode (section 2).
- Light and dark follow the app's theme.

## 2. Sign-in mode

Sign-in is the same browser in a mode with three extra rules: where it may go, when it is done,
and what it takes.

**Where it starts.** The bundled region catalog (`RegionCatalog.kt`, generated from
`server/scripts/region_catalog.toml`) gives each provider and region a start page:
- **Petersburg:** `https://dnevnik2.petersburgedu.ru/login`. The diary's own form and its
  «Госуслуги» button are both on it.
- **«Сетевой город»:** the region's own server. Its sign-in page carries the region's password
  form, and its Госуслуги button where the region enables one. The three Госуслуги-only regions
  are offered like every other region.

**Where it may go.** The catalog also gives each start page an allow-list of hosts:
- the diary's own host;
- `esia.gosuslugi.ru`;
- any host the region's Госуслуги hand-off is known to pass through.

Any other `http(s)` navigation is not loaded. The page says «Эта страница не относится ко
входу», and the menu offers to open it on its own after the sign-in. Non-`http(s)` schemes leave
the app (section 1), and that is how a MAX confirmation reaches MAX.

**When it is done, and what it takes.** It checks after every finished page load:

| Provider | Done when | Taken |
| --- | --- | --- |
| Petersburg | `CookieManager` holds `X-JWT-Token` for `https://dnevnik2.petersburgedu.ru` | That cookie's value, as `credential.token` |
| «Сетевой город» | The page is inside the region's diary, past the sign-in (its post-sign-in path) | `NSSESSIONID` and, where present, `ESRNSec` for the region's origin, and the `at` token read once from the diary's own page with `evaluateJavascript` |

Nothing else is read, not the form fields and not any other host's cookies.

The capture then hands the session to the server exactly as today's
`DiarySignInImpl` does after its own sign-in: `POST /api/v1/diary/session`, through
`DiarySessionRequestDto`. Then it wipes. It also wipes on «Назад» past the start page, on
«Закрыть» and on any failure.

**What is unknown, and is written down as unknown.** Nothing in this repository has watched a
Petersburg sign-in through Госуслуги end. `docs/diaries/petersburg.md` knows only «the cookie
`X-JWT-Token`, copied from a browser». Three questions get their answers from the first live
sign-in (section 6):
1. whether the Госуслуги path ends in the same cookie on the same domain;
2. whether Госуслуги refuses a WebView. If it does, the sign-in mode sends Госуслуги the phone's
   own Chrome User-Agent without the `; wv` marker, as other diary clients do
   (`docs/diaries/epos.md`, lines 47–49);
3. how long the token lives (its `exp`).

**«Сетевой город»'s capture is a design, not a fact, and it stays one until somebody with an
account in one of its regions signs in.** The owner has none today. In particular it is unknown
where its web client keeps `at`. The plan carries this as «written, not verified».

**The app's own password form is deleted**, together with what serves only it:
- `PetersburgSignIn.kt`;
- `NetSchoolSignIn.kt`'s password path and its hashing on the phone;
- the form half of `SignInPage.kt` and `DiarySignInScreen.kt`;
- the strings that only they use.

`DiarySignInProblem` keeps the failures that still exist: the diary did not answer, the session
was refused, no connection. It drops those that described a typed password.

## 3. The server: taking the session, keeping it, ending it

**Taking it** is today's `POST /api/v1/diary/session`, and later v2's `CreateDiarySession` with
the same rules: one upstream read from the server's own address (Petersburg through
`DIARY_PROXY_URL`), the credential sealed with `DIARY_SECRET`, and our own bearer returned. Two
changes:
- **`login` becomes optional.** After a Госуслуги sign-in the phone does not know a login.
  - The phone sends one only when it really has it; nothing in this design reads one off a form.
  - What the app shows as «Вы вошли как» comes from the diary's own answer at adoption, the
    pupils it lists. That needs no new upstream call.
  - `diary_sessions.login` becomes nullable (a revision; see «Revisions»).
- **The class device token may come along**, in the header `X-Device-Token`, for the bot's half
  (section 4). When it is absent or does not qualify, the session is the phone's alone, exactly as
  today.

**Keeping it alive** (the owner's «автопродление»):
- **«Сетевой город»** keeps today's rule in `services/diary_keepalive.py`. Every tick pings
  `GET /webapi/context` at most every four minutes. The external cron the owner connected on
  5 October is what makes that frequent enough.
- **Petersburg** is new. Today its provider's `keep_alive` does nothing, and the token only
  rotates when somebody reads the diary.
  - The tick claims a Petersburg session once its token is past two thirds of its life. The
    life is read from the token's own `exp`, decoded without verifying, since it is ours to
    read and not to trust.
  - It then makes one light read through the proxy, `students()`, and seals the rotated token
    with the existing guarded `UPDATE`.
  - Pinging is at most hourly. A token with no readable `exp` is pinged every six hours.
  - The claim stays in the same bounded batch as today, ordered stalest first.
- **Keeping alive never touches `last_used_at`.** A session nobody opened for 30 days is still
  purged. That is the privacy rule doing its job, not a failure.

**When the diary ends it anyway** (a changed password, an absolute expiry, a sign-out
everywhere), the keep-alive or a read sees `SessionExpired`. Three things follow:
- the row is marked expired;
- the app shows «Сессия дневника закончилась — войдите снова», with the button that opens the
  same sign-in page;
- if the session is the bot's too (section 4), the bot writes once to that member, «Войдите в
  дневник снова в приложении».

Nothing signs back in by itself, because nothing here holds a password. That is the point.

**Capabilities.** `GET /api/v1/diary/capabilities` and v2's `GetDiaryCapabilities` gain a sign-in
method, the diary's page:
- v2 gets `SIGN_IN_METHOD_PROVIDER_PAGE = 3`, an additive enum value that `buf breaking`
  accepts;
- v1 gets the matching string.

The Госуслуги-only regions are listed instead of withheld.

**Removed:**
- `POST /api/v1/diary/login`. v2 never had it: `diary.proto` says so on line 8;
- `server/app/api/diary_web.py`: the `/diary/signin` page and its tickets;
- every provider's `sign_in(request)` password path (`PetersburgClient.login` and
  «Сетевой город»'s password login);
- the server's one rendered page.

`CLAUDE.md`'s «one deliberate exception» paragraph goes with that page, and the server serves
no HTML at all.

## 4. The bot, through the linked phone (#143)

**The link** is made when the phone registers a session and sends `X-Device-Token`, and only
when both of these hold:
- the device is linked to a Telegram account (`device_tokens.telegram_id`, through «📱
  Подключить телефон»);
- the device's class is bound to the same diary: the same provider and, for «Сетевой город»,
  the same region.

When both hold, the session row gets that `telegram_id` and that `class_id`. One session then
has two readers: the phone by its diary token, and the bot by `(telegram_id, class_id)`, the
lookup `handlers/diary.py` already makes. If several linked phones of one account sign in, the
bot reads the newest session.

**«🔐 Войти в дневник»** in the bot no longer hands out a link to a server page. It says that the
sign-in is done in the app, on a phone connected to this account. Without such a phone it
offers «📱 Подключить телефон».

**The three costs #143 asked to weigh, decided:**
1. **The bot's «Выйти» ends the session everywhere.** It is one session, and the bot says so
   before doing it: «Сессия закроется и на телефоне».
2. **Deleting the class unlinks instead of deleting.** `diary_sessions.class_id` changes from
   `ON DELETE CASCADE` to `ON DELETE SET NULL`, a revision. The phone keeps its diary, because
   the diary is the family's and not the class's.
3. **Rebinding the class to another diary, or a member leaving the class, unlinks too** (it sets
   `telegram_id` and `class_id` to NULL), instead of expiring the session. Today's rebinding
   expires the members' sessions, because nobody would read them. The phone still does.

## 5. Texts, documents and the catalog

**The privacy policy** (`docs/legal/privacy.ru.md`, with its English twin) gets a new «Вход»
section:
- the sign-in happens on the diary's own page, or on Госуслуги's, opened inside the app;
- the app reads nothing typed there, and keeps nothing of Госуслуги;
- the page is wiped as soon as the sign-in is done;
- the app takes only the diary's session, and the server stores it sealed and renews it;
- no password reaches the phone's code or the server.

Today's two exceptions are deleted: the bot's page and «старые версии приложения». A sentence
says honestly that the page runs inside the app: the app could technically see what is typed,
and it does not; its code is public. Госуслуги joins the list of other systems as one a family
deals with directly and that we send nothing to.

**The terms** (`docs/legal/terms.ru.md`, with its twin) name both diaries and every listed
region, Госуслуги-only regions included. The clause «в тех регионах, где он принимает логин и
пароль» is deleted.

**The in-app guide** (`docs/app/guide.ru.md`, with its twin) rewrites its «Вход» parts: the
page, the host at the top, the wipe, and «войдите снова».

**The strings:**
- the browser's and the sign-in mode's new strings go in `values/`, each with its English twin
  in `values-en/`;
- the form's strings are deleted from both;
- `ResourceTranslationTest` holds the pairing.

**The documents:**
- `docs/diaries.md`: the decision is rewritten as reversed by the owner on 5 October 2026,
  with its reason and the ТОР boundary above;
- `docs/diaries/petersburg.md`: the page sign-in, its unknowns, and later the live findings;
- `docs/api.md`: no `/diary/login`, an optional `login`, `X-Device-Token`, the new sign-in
  method;
- `docs/bot.md`: the diary through the linked phone;
- `docs/architecture.md`;
- `docs/deploy.md`: `PUBLIC_BASE_URL` now serves `/calendar` only;
- `CLAUDE.md`: the HTML exception, the sign-in-ticket item under «What will bite you», and the
  mentions of `/diary/login`;
- `docs/history.md` is a record and is not edited.

**The catalog.** A region's facts (`password`, `mixed`, `esia_only`) stay facts. The action for
Petersburg and «Сетевой город» becomes «sign in on the diary's page», with a `start_url` and an
`allow_hosts` list. `scripts/region_catalog.py` today refuses any field for an in-page sign-in,
and that test is inverted. ТОР keeps its hand-off link.

## 6. Testing

**Without anybody's account** (unit tests and Robolectric):
- **Link routing:**
  - `http(s)` opens the in-app browser;
  - `t.me`, `mailto:`, `tel:`, `intent:` and downloads go to the system.
- **The browser's settings:** no file or content access, no JavaScript interface, permissions
  denied.
- **Sign-in mode:**
  - a host outside the allow-list is not loaded;
  - done is detected on a fake cookie store holding `X-JWT-Token`;
  - nothing but that cookie is read;
  - the wipe runs on success, «Назад», «Закрыть» and failure.
- **The server:**
  - a session without `login` is taken;
  - «Вы вошли как» comes from the pupils;
  - `/api/v1/diary/login` and `/diary/signin/*` answer 404;
  - the bot link forms only under section 4's two conditions;
  - deleting the class sets NULL;
  - rebinding unlinks;
  - Petersburg's keep-alive claims at two thirds of `exp`, at most hourly, and every six hours
    with no `exp`;
  - expiry marks the row and sends the bot one message;
  - capabilities list the Госуслуги-only regions with the page method.

**On the emulator, without an account:** the Petersburg sign-in page opens in the in-app
browser, from a Russian address. Nothing is pressed. No sign-in is attempted with made-up
details, and none ever will be.

**The live sign-in, with the owner, when an SMS code is at hand.** One real Petersburg sign-in
through Госуслуги, and one with the diary's own form and its second factor. They record:
- what the sign-in ends with;
- whether the `; wv` marker had to go;
- the token's `exp`.

The findings go into `docs/diaries/petersburg.md`. Until then, «Petersburg through Госуслуги»
is marked «written, not verified» everywhere it is claimed.

## 7. Rollout, so that nothing breaks in between

1. **The server takes the new path.** An optional `login`, `X-Device-Token` linking, the
   Petersburg keep-alive, the page method in capabilities, and the two revisions. All of it is
   additive, and today's app keeps working.
2. **The app** ships the in-app browser and the page sign-in. The owner installs it on the
   family's phones.
3. **The old paths go:**
   - `/api/v1/diary/login`, `/diary/signin` and the providers' password paths;
   - the bot's link button and the app's form strings, if any are left;
   - `MIN_CLIENT_VERSION` is raised, so that an old app on v2 is told «обновите приложение».

   The owner says when step 2 is on the phones.

**Revisions.** They take the next free numbers at the time: `0018` is stage 3b-1's, and the
monitoring work plans one too. There are two:
- **`diary_sessions.login` nullable.** It loosens a constraint, so it is safe on either side of
  the merge.
- **`diary_sessions.class_id` to `ON DELETE SET NULL`.** It replaces a foreign key. It is safe on
  either side as well: old code never relies on the cascade for anything but deleting what the
  new code keeps. Production holds no diary session today, and the `preview` branch is a copy.

Each goes to the Neon branch `preview` when its pull request is pushed, then to production
before the merge.

## What only the owner can do

- Sign in once with the real Petersburg account, with an SMS code at hand (#343).
- Install the new app on the family's phones, then say so, so that step 3 can run.
- If anybody with a «Сетевой город» account is available, one sign-in there turns its capture
  from a design into a fact.

## Risks

- **Госуслуги may refuse an embedded page**, or change how it detects one. The User-Agent rule
  is the known answer, and the live sign-in is the test. If it fails, Petersburg still has its
  own form on the same page.
- **A page inside the app is not a browser.** The honest sentence in the policy says what that
  means, and the code being public is the assurance. Custom Tabs would isolate more, but it
  cannot hand the session back. That is why it was not chosen.
- **The diary may cap a session's absolute life.** Then «автопродление» renews it only up to the
  cap, and the family signs in again when the diary asks. The app and the bot say so plainly.
