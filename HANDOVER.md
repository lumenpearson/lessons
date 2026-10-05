# Where the work stands

A working document, not part of the reference set in `docs/`. It describes **the state at
the moment of handover**, so that a new session — human or agent — continues from the same
place without reopening or redoing anything.
What every batch before the last two added is in [docs/history.md](docs/history.md),
newest first.

Last updated: **5 October 2026**. **PRs #63 through #85, #128, #129, #133, #134, #140,
#166, #186, #187, #189, #214, #218, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261,
#263, #267, #274, #277, #294, #296, #297, #300, #301, #303, #305, #306, #307, #308, #311,
#313, #319, #328, #329, #332, #333 and #335 are merged**; `main` is at `175ff42`, the merge
of #335, on 5 October 2026, and `dev` is level with it. **The four designs of sub-projects 3
to 6 are approved and on `main`**: the owner answered every question with its recommendation
on 5 October (#301, #306, #307, #308). **One pull request is open: #342, the one carrying
this paragraph**, a draft from `server-v2/3a`, on milestone 11, `v0.10.0 — One contract: REST
v2, Connect and native gRPC, build console`, which closes #336, #337, #338, #339, #340 and
#341 and refers to #273: v2 is served beside v1, four methods of it, over REST and Connect.
#335 closed #334, and GitHub closed #235 at the same merge. The schema head did not move: it
is still `0017`, and `EXPECTED_REVISION` did not move either. Production answered
`/api/v1/warmup` with `{"status":"ok","api_version":1,"schema":"0017"}` at 14:38 UTC on 5
October, after the owner's redeploy of 13:20 UTC.

The section «What the last session added» below is #342's batch, and «What the session
before it added» is #335's.

The SHA of its own merge is for the next close-out to write.

**#267 closed #264, #265 and #266**, read back from GitHub on 3 October, and **#296 closed
#271, #272 and #275**, read back on 4 October. **#274 and #277 closed nothing.** Of the
defects the survey and the plan filed on milestone 11, #268–#270 are open, #276 closed with
#305, #309 and #310 with #311, #312 with #313, #314–#318 with #319, #320–#325 with #328, #331
with #332, and #273 is that milestone's epic. #336–#341, filed on #342's branch, stay open
until it merges.
**#235** closed at #335's merge on 5 October, and the proxy it asked for was verified in
production the same day («Outside the pull request, the same day», in the section on #342).
**#236**, a phone's sign-in showing nothing for over a minute, was closed as a duplicate of
#233, which #234 had already fixed.
Of the device epic **#109**, **#111** and **#113** stay open for what only a phone can say,
and **#112** (a macrobenchmark module) was not started.

**The code expects head `0017`, and production is at `0017` since 26 September 2026 at 12:26 UTC.**
`EXPECTED_REVISION` in `app/db.py` is `0017`, pinned to the real head by
`tests/test_schema_version.py`. `0015` adds the eight nullable columns the second diary
needs. `0016` creates `usage_counters`, the anonymous school directory's daily count of
DaData requests. `0017` files the diary corrections under the child rather than a login, and
it is data only. All three went on through the Neon connector (the project named `lessons`)
**before** #140's merge, in one transaction; «Schema» in the section on #140, in
[docs/history.md](docs/history.md) now, says what the database said. The `0014` chapter
still holds: #83's pinned table of the enum column
widths fails when a `SAEnum` member's **name** outgrows its `VARCHAR`, which SQLite cannot
see and production Postgres finds at the moment somebody marks a day.

**Production was read after #140 deployed, and the schema and the code met.** On 26 September
2026 at 17:05 UTC the production deployment of `bd7c816` answered `/api/v1/warmup` with
`{"status":"ok","api_version":1,"schema":"0017"}`. It was read through the Vercel connector's
`web_fetch_vercel_url`, which carries the owner's access through Deployment Protection — the
thing that made #83 call it unreachable from a session. The project's production alias
`lessons-ruddy-zeta.vercel.app` is **not** behind that protection (`/api/v1/health` answers
`200`), while the per-deployment URLs and `lessons-codeilluminators.vercel.app` redirect to
Vercel's sign-in; a phone is pointed at the first. The bot answered `/start` in the owner's
chat the same day. Both halves of #119 are therefore read, and it closes.

**Two things changed about how this project is tracked, and they are the reason to read on
before planning anything.**

**The work moves to a local machine.** Everything up to #85 was built with no emulator, no
`adb` and no device, and so were #133, #134 and #140; #186 is the first batch made with
one. The section «Where the work happens
from here» below says what that unlocks and what it still cannot do; **#109** is the epic.
#140 is the first batch since that decision to change screens, so nearly everything it adds
to section 5 is a question a device answers.

**The repository has issues now, and until #128 it had none at all.** Before that, the
milestones were the only grouping the history had. Forty-two issues were opened in one go:
**#86–#108**, closed, for what was built and what each bug sweep found, and **#109–#127**,
open, for the whole of what was left. Several of the open ones record a decision *not* to do
something, so that a later session does not re-discover it as an oversight. From #129 on, a
found defect becomes an issue before it becomes a fix: #131 and #132 were the first two
filed under that rule, and #140's branch filed #136–#138 and #145–#165.

## Where the work happens from here: a local machine

**#128 was meant to be the last batch built in a cloud session, and the change is one of
venue rather than of direction.** #133 and #134 were still written from one, because neither
needs a device, and #140 was written without one too, although its screens want one most.
Everything up to and including #85 was made with no emulator, no `adb` and no device. That
is what produced a project with 968 Android tests, 1634 server tests —
and a section 5 of this document that has only ever grown, because the things in it are not
things a JVM test can be asked.

From here: Android Studio, an emulator and a real phone, with Claude Code in the terminal
beside them. What that unlocks, in the order it is worth:

1. **Seeing the screen.** `adb exec-out screencap -p` makes a PNG and a local agent reads
   PNGs. Every «nobody has looked at this» item in section 5 becomes answerable.
2. **Instrumented tests.** Until #187 there was **no `androidTest` source set anywhere in
   this project**, which is why the reorder gesture was two touches until #181 and why its
   threshold was asserted only in arithmetic. There is one now, in `:core:designsystem`
   (#110): `ToolbarOnDeviceTest` drags the arranging gesture by fractions of a slot at the
   device's own density, with `./gradlew :core:designsystem:connectedDebugAndroidTest`. The
   next screen that wants one follows its pattern — the clock held, `settle()` sending the
   snapshot notification first — and its catalog pins (Espresso 3.7.0, runner 1.7.0,
   because the Compose BOM's own Espresso fails on API 34 and later). `uiautomator` is still
   absent: nothing here has needed a second app yet.
3. **Frame timing.** `dumpsys gfxinfo … framestats` costs nothing and works today; Perfetto
   plus `trace_processor` answers «which composable recomposed» in SQL; Macrobenchmark's
   `FrameTimingMetric` gives P50/P90/P99 and `frameOverrunMs`.

**What a local session still cannot do**, so that an evening is not spent finding out: an
emulator's GPU is emulated, so absolute frame numbers are not a phone's; a Glance widget is
drawn by the **launcher's** process, so the app's `gfxinfo` says nothing about it; haptics
cannot be felt; a release build under a trace needs `-dontobfuscate` or `retrace`; and
Macrobenchmark against a debuggable build measures the debugger.

**The handover is this repository and nothing else.** A local session shares no state with a
cloud one. It reads `CLAUDE.md`, this file, the agents in `.claude/agents/` and the
procedures in `.claude/skills/`, and so arrives knowing the traps that have cost a deploy
and a release each. `.claude/README.md` says why there is no `.mcp.json`; locally, Neon and
Vercel can go in one.

### And the work has a tracker now

**Until #128 there were no issues in this repository at all** — the milestones were
the only grouping the history had, and everything else lived in this document's prose.
Forty-two issues were opened in one go: **#86–#108 closed**, describing what was built and
what each bug sweep found, and **#109–#127 open**, which were then the whole of what was
left. Six of those have closed since: #114–#117 and #119 at #186's merge, and #110 at
#187's.

The forward half is worth reading before planning anything: **#109** is the epic this
section describes, **#118–#122** are the owner's alone, and **#126** collects the four small
things carried deliberately, so that a later session does not re-discover a decision as if
it were an oversight.

**Seventy-seven have been filed since.** #130 was the survey #134 closed, and #131 and #132
the two defects #133 closed. #135 is the owner's decision about the second diary; #136–#139
and #141 are the work #140 carries; #142–#144 are its deliberate follow-ups, on no
milestone; and #145–#165 are the defects found on its branch, each filed before its fix.
#167–#185 are what #186's walk on an emulator found and what the owner asked for that
night, and #188 what #189 found; all twenty are closed. #190–#211 are the external audit of
27 September 2026, on milestone 10, and #212 and #213 what merging its fixes found.
#268–#272 are the defects the survey behind milestone 11 found, #273 that milestone's
epic, and #275 and #276 what planning its first sub-project found. #293, on the same
milestone, is the stale tracker documents #294 closes, and #295 what writing #294 found: CI
skips the server job when only a document its tests read changes.

Labels are `type:` (feature, bug, chore, research, decision, epic), `area:`, `status:` (now,
next, someday, done) and `needs:` (device, owner). **The board is the owner's project 6,
«lessons»**, and it holds every issue and pull request. A remote session cannot reach it; a
local one fills it with `gh`, by the rule in the project's README, as «The board» in the
`github-pr` skill says.

## What the last session added: v2 served beside v1 — stage 3a of sub-project 3 (#273)

Open as #342, a draft from `server-v2/3a` to `main`, on milestone 11, and on project 6 as In
progress, P1, L, 21, started 5 October. It closes #336, #337, #338, #339, #340 and #341, and
refers to #273. The branch was cut from `main` at `175ff42`, the merge of #335, and carries 23
commits before this close-out, to `d501b42`. Written on 5 October 2026, after #335 merged.
The schema head did not move. This is stage 3a of `docs/specs/2026-10-05-server-v2-design.md`,
built by the plan beside it: v1 answers exactly as before, and v2 now answers too, four
methods of it.

- **The v1 rules v2 needs moved into `services/` first**, with v1 calling them: the join flow
  (`services/join.py`), the window's tag and v2's school year (`services/window.py`), the
  class's clock and the date bounds (`services/clock.py`), a device's access
  (`linking.Access`), and the four limiters with the device cap and the diary's attempt
  (`security.py`, one instance each). `caller_bucket` reads header lines and a peer host.
- **`app/rpc/` and `app/rest/` serve v2**: one method table read from the descriptors, one
  gate (client version, the diary's switch before any token, the bearer, the link, the role),
  one `invoke` per call (scope, handler, the one commit, then effects), one error table, the
  seventeen Connect apps at `/api/rpc` behind a `415` for native gRPC and a decoding guard,
  and the REST transcoder under `/api/v2`. `main.mount_v2` answers `503` there if v2 will not
  import.
- **Four methods**: `GetScheduleWindow` (a school year, terms computed rather than seeded,
  a strong tag and `not_modified`), `GetMe` (no code minted), `GetDiaryCapabilities` (v1's
  answer in v2's shape), `CreateDevice` (v1's flow, on v1's budget). `WatchClass` refuses with
  `FEATURE_UNSUPPORTED`. `MIN_CLIENT_VERSION` is a new optional setting.
- **`google/rpc` is generated** beside `google/api`, pinned to `buf.lock`'s googleapis commit.
- **`/api/v1/warmup` gains an additive `"v2": true|false`**, so a v2 that fell back to its
  `503`s is visible. Its `status` is not changed by it.
- **`Cache-Control: private, no-store`** goes on REST answers of the diary's GETs, of
  `GetCalendarFeed` (its answer is a secret URL) and of `CreateDevice` (its answer is a device
  token). The window keeps v1's answer: an ETag and no `Cache-Control`.
- **The `v2` test harness**: `v2.connect`, `v2.rest` and `v2.both`, which asserts one outcome
  on both transports. An unreadable answer is never equal to another.
- **Six defects were found on the branch, each filed before its fix:**
  - #337: a body claiming gzip that was not gzip gave a `500`;
  - #338: a header or query that is not UTF-8 gave a `500` quoting the exception. It is now
    read as latin-1, as v1 reads it;
  - #339: a REST JSON body with a lone surrogate decoded where Connect refused it, then gave a
    plain-text `500`;
  - #340: REST read any body as JSON, so a cross-site simple POST could spend the join budget
    v1 and v2 share. REST now takes `application/json` or `+json` only;
  - #341: REST now refuses a compressed body, and Connect's encode failure is a logged
    `INTERNAL` with the fixed sentence;
  - #336: a sentence of the documents left `DIARY_PROXY_URL` out of what compose forwards.
- **How it was built and reviewed**: eleven tasks, each with a review of the spec and the
  quality; further review rounds on Tasks 5, 6, 7, 8, 9 and 10; and a review of the whole
  branch, «Ready to merge: Yes», whose six Minors are all fixed in `d501b42`. The ledger is
  git-ignored scratch and is not in the repository.

### Gates

All at `d501b42`.

- **ruff**: `ruff check app tests scripts migrations`, all checks passed.
- **mypy**: no issues found in 214 source files.
- **The server suite.** `pytest -q -n auto`, run alone from `server/`, gave **2437 passed** in
  731.59 s (12 min 11 s). The seven places the `handover` skill names say 2437.
- **The contract**, per the review of the whole branch: `buf lint` exit 0;
  `buf breaking --against .git#ref=175ff42` exit 0; `buf generate` reproduces the 48
  committed files.
- **CI on the head** is read before the merge.
- **Android** was not run, because nothing under `android/` changed; its 1635 tests stand from
  before.

### What was deliberately left alone

- **Stage 3b**: the other 71 unary methods, the per-provider diary registry, and the Telegram
  notices as effects. **Stage 3c**: the host, native gRPC, and `WatchClass`'s stream.
- **#302 is not 3a's**: #303 fixed v1 on 5 October 2026, and v2's gate asks the diary's switch
  before any token on its own.
- **Ruled out of scope during review**, each with its reason in the ledger:
  - `404`, `405` and `307` under `/api/v2` come in FastAPI's `{"detail"}` shape;
  - connectrpc's own messages repeat the caller's own `Connect-Protocol-Version`,
    `Connect-Timeout-Ms` and `Content-Encoding` header values;
  - the native-gRPC guard keys on `http_version`;
  - the window sends no `Cache-Control`: RFC 9111 §3.5 keeps shared caches off answers to
    requests that carry `Authorization`, and `no-store` would defeat the ETag;
  - an unusable `MIN_CLIENT_VERSION` fails at import, as `TRUSTED_PROXY_HOPS` does;
  - a websocket scope under `/api/rpc` gets no explicit close;
  - a raising `rollback()` masks a refusal.
- **v1's `/join`** stays reachable through a body with no `Content-Type` until v1 is retired
  (sub-project 5). #340 closed that door for REST v2 only.
- **An import-time ceiling** in `test_cold_start.py` (the design's decision 8).

### What nobody has verified in this batch

- **v2 on production, and Connect on any deployment.** `connectrpc`, the generated contract,
  `protobuf-py-ext` and `pyqwest` do import on Vercel, and `mount_v2` mounted v2 there: Vercel's
  Preview deployment of `d501b42`, `lessons-git-server-v2-3a-codeilluminators.vercel.app`,
  behind Vercel Authentication, was read by the owner in their own browser on 5 October. It
  answered `/api/v1/warmup` with `"v2": true`, and REST `GET /api/v2/diary/capabilities` with
  JSON naming the NetSchool regions. Only those two were read; Connect was not called. The
  production deployment is checked after the merge, Task 11 Step 10 of
  `docs/specs/2026-10-05-server-v2-3a-plan.md`, and the fail-safe mount is there for a failure.
- **v2 against Postgres**: every v2 test ran on SQLite.
- **`x-vercel-forwarded-for` reaching a v2 call's bucket**: held by unit tests of
  `caller_bucket`, never through Vercel's proxy.
- **What `http_version` Vercel's Python bridge puts in the ASGI scope.** The native-gRPC
  guard steps aside only for `"2"` and `"3"` and reads a missing one as HTTP/1.1, as ASGI
  does; a bridge that said `"2"` for a request it carried over HTTP/1.1 would let native
  gRPC through to the library's `500`. The check after the merge sends no `application/grpc`.
- **`X-Lessons-Client` from an APK**: no APK sends it yet.
- **A segfault in a native extension at import cannot be caught by `mount_v2`.** It turns an
  import that raises into the `503`; an interpreter that crashes raises nothing to catch.

### Outside the pull request, the same day

None of this is code in #342, and all of it is state the next session needs.

**The Petersburg diary's proxy (#235, #334) is set up and verified in production.**

- **The VPS** is RUVDS, Rucloud Korolyov, on Ubuntu 24.04. Its address stays out of this
  public repository, like the proxy's URL and every password; the owner has them.
- **Squid on 23128** allows `CONNECT` to `dnevnik2.petersburgedu.ru:443` only, and only with
  the password. ufw allows 22, 2222 and 23128, and fail2ban runs.
- **SSH** is by key only, on 22 and 2222. The owner's home line blocks outgoing 22, so the
  session used 2222.
- **Checked from outside**:
  - six allow-and-refuse checks pass, both from Russia and through Amsterdam;
  - `PetersburgClient` reached the diary through the proxy (`TCP_TUNNEL/200`);
  - check-host.net reached 23128 from Frankfurt, Nuremberg, Amsterdam, Helsinki, Los Angeles
    and Moscow.
- **`DIARY_PROXY_URL`** is set by the owner in Vercel for Production, Preview and Development.
- **Production**, asked with a made-up Petersburg session:
  - before the redeploy it answered `503`, «Дневник не ответил вовремя»;
  - after the owner's redeploy at 13:20 UTC it answered `409`, «Дневник не принял эту сессию
    с нашего сервера — дело не в пароле.» — the diary judging a session instead of not
    answering;
  - a live tunnel ran from AWS Frankfurt through Squid to `46.243.177.102`.

  This is recorded as a comment on #235, which had already closed.
- **The root password** was rotated by the owner afterwards. The host key changed, and was
  confirmed through the RUVDS console.

**A real Petersburg account in the app: not confirmed.** On the emulator, with the debug build
pointed at production and a temporary route to the diary through the Russian line, the diary
answered `401` («Неверный логин или пароль») to an account the owner supplied. The same
request sent directly got `401` too, and so does a made-up account: the same 772-byte HTML. So
the answer does not tell a wrong password from another refusal. Further probing of the diary's
sign-in was refused by the session's safety check and was not pursued. The owner is to try
that login on the diary's own site, with the VPN off, since the site is fenced to Russia
(section 7).

Also observed: tapping «Войти» on the diary sign-in screen did nothing three times, while the
keyboard's «Готово» submitted. It is not yet confirmed as a defect, because confirming it costs
more attempts on a real account.

## What the session before it added: the Petersburg diary can go through a Russian proxy (#334)

Merged as #335 (`175ff42`, 5 October 2026), from `feat/diary-proxy`, on milestone 10, beside
#235. It closed #334 and refers to #235 and #273; GitHub closed #235 at the same merge.
Written on 5 October 2026, after #333 merged. The schema head did not move, and nothing under
`/api/v2` exists yet.

- **`DIARY_PROXY_URL`, optional and empty by default, routes the Petersburg diary through an
  HTTP proxy.** It goes as a `CONNECT` tunnel, so TLS stays end to end and the proxy sees the
  host name, never the family's credential. This is the server's half of the owner's
  decision on #235: the bot and the API stay on Vercel and Neon, and only the diary's requests
  go through a RUVDS VPS in Russia. Nothing else uses the proxy: the bot, Telegram, the
  database, DaData and the NetSchool diaries go direct.
  - **Empty means direct**, today's behaviour and right for a deployment inside Russia. It
    is not announced and not in the deployment refusal's list, so a fully configured
    deployment still announces nothing.
  - **An unusable value is treated as unset rather than raised.** That means not `http://` or
    `https://`, no host, or a port that is not a number. httpx would refuse it when the client
    is built and take the diary down. The startup log says it is unusable, and never quotes it,
    because it can carry the proxy's password.
- **`test_diary_proxy.py` (14 tests)** holds the setting's reading, the announcement without
  the value, and the client's route, read off the client httpx built. The route test failed
  without the client change.
- **`docker-compose.yml` hands the server the setting.** `test_compose` caught it on the first
  full run.
- **`.env.example`, `docs/deploy.md` and `CLAUDE.md`** name it.
  `docs/deploy.md`'s «The electronic diary» says what the proxy must refuse: anything but
  `CONNECT` to `dnevnik2.petersburgedu.ru:443`, and anyone without the password.
- **After #333's merge**, `dev` was fast-forwarded to `2f529af`, Vercel reported the deploy
  successful, and production answered `/api/v1/warmup` with
  `{"status":"ok","api_version":1,"schema":"0017"}`. The board reads Done for #333 and #330.

### Gates

- **The server suite.** `pytest -q -n auto`, run alone from this worktree's own venv, gave
  2221 passed and one failure, `test_compose`, which the compose line fixed. `test_compose`,
  `test_diary_proxy` and `test_env_example` then gave 21 passed. The suite is **2222**, which
  the seven places the `handover` skill names now say.
- **ruff and mypy** are clean; mypy covers 197 modules.
- **Android** is unchanged, 1635 tests.

### What was deliberately left alone

- **The NetSchool diaries** go direct: no evidence says their regions drop foreign addresses,
  and opting them in is one line when there is.
- **Buying and setting up the VPS** is the owner's (section 7 then; done the same day, and in
  `docs/history.md`, «Moved out of section 7 on 5 October 2026», now).

### What nobody has verified in this batch

- **No request has gone through a real proxy.** One request through the owner's VPS to the
  diary is the test that closes #235. It was verified in production on 5 October, after the
  merge: «Outside the pull request, the same day», in the section on #342 above.

## The milestones

**The milestones as they are now.** The owner renamed all nine on 25 September 2026, so that
every title names what its version delivered and every description names its pull requests
and issues, and created the tenth the same day, and the eleventh, twelfth and thirteenth on 3
October. The older batch sections, in `docs/history.md`
now, quote the titles of their own time, which a search no longer finds; the third column
maps them. The
`github-pr` skill carries the same table.

| # | Title | Called before 25 September | State | Covers |
| --- | --- | --- | --- | --- |
| 1 | `v0.1.0 — App, widget, admin bot and read API` | `v0.1.0 — First run on a phone` | closed | PRs #1–#14; issues #86, #88, #89, #278–#281 |
| 2 | `v0.2.0 — Petersburg e-diary, class run from bot and phone` | `v0.2.0 — The diary, and the class run from the bot` | closed | PRs #15–#17, #28–#31; issues #87, #90, #91, #102, #282, #283 |
| 3 | `v0.3.0 — School year, terms, school search, several classes` | `v0.3.0 — The school year` | closed | PRs #27, #32–#35, #43; issue #92 |
| 4 | `v0.4.0 — 67-defect sweep, first audit, app-wide correction mode` | `v0.4.0 — Nothing breaks in silence` | closed | PRs #44, #45, #50; issues #93, #94 |
| 5 | `v0.5.0 — Public repo: secrets audit, English docs, font licence` | `v0.5.0 — A public repository` | closed | PRs #46–#49, #51, #55–#57, #59; issue #95 |
| 6 | `v0.6.0 — Dishka DI, scrolling text, in-app guide from the repo` | `v0.6.0 — One container, and nothing cut off` | closed | PRs #60–#74; issues #96, #97, #99, #101, #103, #104, #284–#291 |
| 7 | `Dependencies — dependabot bumps` | `Dependencies` | open, for good | every dependabot bump; deliberately not a version |
| 8 | `v0.7.0 — School-year calendar, day ribbon, rearrangeable tabs` | `v0.7.0 — Оптимизация` | closed | PRs #75–#85, #128; issues #98, #100, #105–#108, #292 |
| 9 | `v0.8.0 — On-device checks, 89-region e-diary survey` | `v0.8.0 — On a device` | open | PRs #129, #133, #134, #186, #187, #189, #234, #238, #239, #241, #245, #248, #250, #252, #257, #261, #263, #267; issues #109–#117, #119, #130–#132, #167–#185, #188, #219–#233, #237, #240, #242–#244, #246, #247, #249, #251, #253–#256, #258–#260, #262, #264–#266 — the first whose work needs an emulator or a phone, and #186 the first done on one |
| 10 | `v0.9.0 — NetSchool e-diary, onboarding via the school's diary` | none — proposed as «v0.9.0 — A second diary», never created under that name | open | PRs #140, #214, #218, #303, #335 (merged); issues #135–#139, #141, #145–#165, #190–#211 (the external audit of 27 September), #212, #213, #235, #236, #302, #334 |
| 11 | `v0.10.0 — One contract: REST v2, Connect and native gRPC, build console` | none — created on 3 October 2026 under this name | open | PRs #274, #277, #294, #296, #297, #300, #301, #305, #306, #307, #308, #311, #313, #319, #328, #332 (merged) and #342 (open); issues #268–#273, #275, #276, #293, #295, #298, #299, #304, #309, #310, #312, #314–#318, #320–#325, #331, #336–#341 — the programme of `docs/specs/2026-10-03-one-contract-design.md` |
| 12 | `v1.0.0 — A build somebody else can install` | none — created on 3 October 2026 under this name | open | PRs #329, #333 (merged); issues #120–#122, #127, #142, #144, #326, #327, #330 — the steps epic #127 names between one class on one phone and a build a second family could use |
| 13 | `Backlog — not scheduled` | none — created on 3 October 2026 under this name | open | issues #118, #123–#126, #143; deliberately not a version, like 7 — known gaps and decisions no release is waiting for |

**#142, #143 and #144**, two follow-ups and a decision that #140 left alone on purpose, were
on no milestone until 3 October: #142 and #144 are in the twelfth now, and #143 in the
thirteenth.

**Creating a milestone is the owner's decision**, and a session only attaches one — the owner
created the ninth on 22 September 2026, the tenth on 25 September and the eleventh, twelfth
and thirteenth on 3 October. **The ninth was the first
milestone that groups issues rather than pull requests**, and the first whose work cannot be
done without an emulator or a phone.

## Sections 1 to 4, and the history

Sections 1 to 4 — the state as of PR #51, what had been done by then, what had not, and the
defects closed before the tracker existed — are in [docs/history.md](docs/history.md) now,
after the oldest batch. Section 4 is worth reading before an audit files anything: it lists
defects already closed, each with what closed it. The four sections below kept their
numbers, because the documents, the skills and the history refer to them by number.

## 5. What nobody has verified

This is the main thing worth knowing: **all of this work is proved by tests and by nothing
else.**

**Most of this section is now also an issue**, which is where it should be worked from:
#113 the widget's sizes, #111 what the ribbon's shader costs and #121 the real diary are
open; #114 the arranging gesture, #115 the correction mode, #116 two classes on one phone
and #117 the upgrade path were closed by #186's walk on an emulator, and what only a phone
can add to them stays below. They are children of **#109**, the epic for moving this work to
a machine with a device on it. #140's additions below belong to #121 where they need a live
diary; #156, the backup, closed on the same emulator walk, and a phone's own backup is its
bullet below. The rest wait for an APK on a phone. The prose here is kept because it says
*why* each one is unverifiable, which an issue title cannot.

**#186 looked at a good part of this on an emulator** (26–27 September 2026; its section,
«…the first walk of the app on a device», in `docs/history.md` now, has the detail). The
bullets it answered say so in place, and what an emulator
cannot answer — a thumb, a haptic, a real GPU, a real launcher's corners — is still here.

- **The app's handling of a `503 disabled` on a diary read with a token has not been checked on
  a device** (#302). The app parses `X-Diary-Unavailable` for every diary call, but a read that
  carries a token and is still answered «disabled» is a new case for it; only the server's
  tests have seen it.
- **The new CI filter (#295) has been seen on a GitHub runner twice**: in #305's own CI, and in
  #311's, whose first commit (`66c7ba1`) touched only `CLAUDE.md`, `.claude/` and
  `docs/build.md` and started the server job with the Android and contract jobs skipped. Nobody
  has seen a commit confined to `proto/` start it on its own. Its guard, `test_ci_paths.py`, sees only the first part of a path, so a new file
  under a root already listed (`.github/dependabot.yml`, say) is caught only by adding it to
  the guard's written list.
- **Whether Telegram ever delivers a message that starts with whitespace is unverified**
  (#304). The form breakout now treats «  /week» as a command because aiogram's `Command`
  filter does, after `text.split()`; its clients are not known to keep plain spaces there, but
  a no-break space is whitespace to `str.split`, and nobody has sent one.
- **v2 as #342 serves it has been asked little outside the test client** (stage 3a; its
  section above has the detail):
  - **on Vercel, a preview and REST only**: `connectrpc`, the generated contract,
    `protobuf-py-ext` and `pyqwest` import on a Vercel preview of `d501b42`, read by the owner
    on 5 October, which answered `/api/v1/warmup` with `"v2": true` and REST
    `GET /api/v2/diary/capabilities` with the NetSchool regions. Connect was not called there,
    and the production deployment is checked after the merge, Task 11 Step 10 of
    `docs/specs/2026-10-05-server-v2-3a-plan.md`;
  - **against Postgres**: every v2 test ran on SQLite;
  - **`x-vercel-forwarded-for` reaching a v2 call's bucket**: held by unit tests of
    `caller_bucket`, never through Vercel's proxy;
  - **what `http_version` Vercel's Python bridge puts in the ASGI scope**, which is what the
    native-gRPC guard keys on;
  - **`X-Lessons-Client` from an APK**: no APK sends it yet;
  - **a segfault in a native extension at import**, which `mount_v2` cannot catch: it turns an
    import that raises into the `503`, and an interpreter that crashes raises nothing.
- **Vercel's proxy in front of Connect has been asked nothing.** The v2 contract (#297)
  writes `/api/rpc/lessons.v2.<Service>/<Method>` down, #342 serves four methods there, and
  no deployment has been asked one over Connect: a preview was asked over REST only (above).
  Whether Vercel passes a Connect request and its streaming body through is sub-project 3's
  first question, and native gRPC is off the Vercel target for a reason `docs/api.md` states.
- **Buf's unauthenticated rate limit has not been met.** CI's «Contract (Buf)» job fetches two
  remote plugins without a token; one run, 37230983817, was not throttled. If it ever is,
  either step can be the one that fails (the buf-action step fetches `googleapis` from the
  Schema Registry before generate runs, and says nothing of what to do), and the remedy is the
  same `BUF_TOKEN` (section 7).
- **`buf breaking` has refused a change only locally.**
  - Locally, it compared a scratch copy of the contract with `main` at `45f0680` over 25
    mutations (#300, which found #298).
  - In CI it has compared once, on #300 (run 37239214776), and found nothing breaking, as
    there was none. No CI run has yet refused a breaking change.
  - Buf reads no options (#299), so a binding, a credential or a role changed in `proto/` is
    caught by the resource map in `test_contract.py` and by nothing in Buf.
- **The Android generation of the contract was never compiled** (#300). The protocolbuffers
  java and kotlin (lite) and connectrpc/kotlin plugins were run once into a scratch directory
  with no clash and no warning; no Gradle module has seen the output, and what sub-project 5
  inherits is in the programme design's section 3.
- **The Postman smoke collection has never been run in Postman** (#300). It lives in the
  owner's personal workspace and is not in the repository; its anonymous requests were made
  with curl against production and answered as its tests expect, and its second folder, which
  needs a device token, has not run at all.
- **The cold-start fix (#275) has not been read on Vercel.** The 736 aiogram modules were
  measured in a fresh interpreter on Windows under Vercel's settings, and
  `tests/test_cold_start.py` holds the answer there; no deployment of this branch has been
  asked how long its first request takes.
- **The developer mode (#237) past its door has been seen by nobody.** #257's session saw
  the reveal on an API 37 emulator, along with the toast, the section in the settings, the
  signed-out page and hiding it again; that walk is what found #255 and #256. Everything
  behind the GitHub sign-in is still unseen: the Keystore round trip, the connectivity,
  notification, alarm and widget checks, the grid, the large text, the stretched strings.
  The GitHub permission call has not met a real account. An APK built without
  `LESSONS_GITHUB_CLIENT_ID` cannot get past the door, and every build here so far is one.
  The mode is also the first tool for most of this section: its checks run from the phone's
  own network, and its records name the leg a sign-in is stuck on.
- **Not one route in `docs/diaries/` has been seen answering.** #134's 1916 routes
  were read from client code and two official apps, and cross-checked against each other.
  No diary host answers a cloud session, so none was ever called. A route marked *current*
  means that a client active in 2025 or 2026 sends it, not that a server accepts it today.
  ТОР «Моя школа» rests on one app build, 5.0.0.454, and its first-wave list rests on one
  official post. The first live session on any platform will correct its page. Whoever has
  it should edit the page by hand, because the generator is not in the repository — and,
  where the edit touches `docs/diaries/regions.md`, regenerate the region catalog with
  `scripts/region_catalog.py`, which a test insists on.
- **The «Сетевой город» provider has never met a live server.** #140 wrote the client, the
  mapper, the keep-alive and the school search from open-source clients and hand-written test
  payloads; no ИРТех server answers a cloud session, so none was called. Every upstream shape
  — the salted-MD5 sign-in, `/webapi/context`, the weekly diary, the school search — is what a
  client sends, not what a server was seen to accept. The refresh-token grant (LoginType 9)
  and the Госуслуги refusal path (`SignInUnsupported`) are the least exercised of all. The
  first live sign-in on it is the owner's, #121's caveat a second time.
- **The phone's own sign-in has never met a live diary either — neither «Сетевой город» nor
  Петербург.** The Kotlin ports and the Python originals run one set of known-answer vectors
  (`server/tests/vectors/diary_protocol.json`), which proves the two agree and nothing about
  whether either is right. Whether a Петербург pupil's own account lists itself as a pupil,
  and how long its token lives, are unknown for the same reason. Петербург's sign-in met the
  live diary once, on 5 October, on an emulator: an account the owner supplied was answered
  `401`, which a made-up account gets too, so it says nothing about whether the port is right
  (the section on #342).
- **Nobody knows whether a diary accepts a session opened on a phone when our server replays
  it.** The phone signs in from the family's own address, and `POST /api/v1/diary/session`
  then reads with that session from Frankfurt. A diary that ties a session to its address, or
  refuses foreign ones, answers that read with a refusal, and the server turns it into a
  `409`, meaning the diary refused the session from our address — by design, with no automatic
  retry. If that is what the first live session gets, the phone-registered path does not work
  for that diary and only the password routes remain. Whether «Сетевой город»'s four bootstrap
  calls fit inside Vercel's 30-second ceiling is unmeasured too. **For Петербург the read gets
  that far since 5 October.** On 2 October the city's network did not answer Frankfurt at all
  (#235); production's diary requests now leave through a Russian proxy, and a made-up session
  was answered `409`, «Дневник не принял эту сессию с нашего сервера — дело не в пароле.»
  («Outside the pull request, the same day», in the section on #342). A session a phone opened
  is then replayed from the proxy's address rather than the family's, and whether Петербург
  accepts a real one that way is as unknown as for any diary. «Сетевой город»'s regions were
  not asked from Frankfurt.
- **#140's phone half has run on an emulator, and only as far as a diary without a
  password goes.** Nothing composes `LessonsApp` in a test, so the gate between the three
  homes, the hold on the first run and the resume after the process dies are still proved by
  the pure rules and the view models only. #186 walked the first run both ways in, in both
  languages: the legal documents online and the bundled fallback offline, the region list, a
  Госуслуги-only region's hand-off, ТОР «Моя школа»'s, a live school search and the password
  form. Nobody has heard any of it through TalkBack, and `diary.db` has not been written,
  because no password was typed.
- **TLS against the regional servers is seen succeeding once, and its failures and Android's
  own windows-1251 are inferred.** #186's school search reached Амурская область's live
  «Сетевой город» server over TLS from the emulator and got its schools back. The failure
  shapes `UpstreamHttp` classifies are the ones Conscrypt and the JVM are known to throw, not
  ones seen on a device; the port carries its own windows-1251 table rather than
  trusting the device's ICU, and whether the device's would have agreed is unasked. A
  `Retry-After` given as a date is read as no wait.
- **The backup exclusion of crash reports (#156) is seen on an emulator, not on a phone.**
  `BackupRulesTest` reads both rule files; #186 ran `bmgr` through the local transport, plain
  and flagged as a device transfer, and the report stayed out while a probe file came back.
  Google's own transport to a real account has not been asked.
- **The widget's third sentence has never been on a launcher.** Whether «Дневник — в
  приложении» and the new sentence for a phone with neither fit their rungs without a clip is
  judged by length only, and the redraw when the mode changes rests on a broadcast no test
  composes.
- **DaData's side of the school directory is assumed.** Whether its company rows carry
  `region_kladr_id` is unknown — the directory falls back to the region's name for exactly
  that reason — and so are the subject codes of the four regions admitted in 2022 (90, 93,
  94, 95), every DaData spelling in the catalog's overlay, and whether DaData's day turns at
  Moscow midnight, which is when `usage_counters` starts a new one. Two requests racing for
  the last unit of the day were checked on SQLite only.
- **The terms of use and the privacy policy have never been read by a lawyer.** They
  describe what the code does and claim compliance with nothing: whether naming a GitHub
  account as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line
  and parental consent are open.
- **The tab arranging has been seen on an emulator only, and it is a gesture.** #84 is a
  long press, a wobble and a drag; #85 is the drag actually reporting where it landed. What
  the 39 tests prove is arithmetic and contracts: where a drag of so many pixels lands, that
  the gesture reports the slot it computed, that a move is a permutation, that the stored
  order repairs itself, that the shell's one back rule leaves the mode before it leaves the
  app. #186 saw it work on an emulator (#114), and made it one gesture (#181). What nobody
  has felt: whether the wobble is plausible, whether half a slot is the right threshold
  under a real thumb, whether a hold that picks the tab up at once is too eager for someone
  who only meant to look, and whether the haptic lands where the mode opens. **The APK built for #84 predates the fix and does not
  rearrange anything**; the first install worth an evening is one built after #186, as
  section 7 says, because #181 made the gesture one touch.
- **The drag is driven from a test now, and how far it goes is the whole reason it can be.**
  #84 left it undriven on the ground that Robolectric reports its own densities, so a
  synthetic swipe of «half a slot» measures the environment. True, and it hid a defect for a
  batch: a drag of ten thousand pixels parks at the end under any density, which is what
  `ToolbarDragTest` does and what caught it. Anything *between* two slots is still not asked
  of a composition, and `ToolbarReorderTest` asks it in pixels instead.
- **The year scrolling has been looked at once, on an emulator** (#186): the year picker,
  and 2025/26 fetched on demand under «Загружаю год» from a server on the same machine.
  What the tests prove is which year is asked for and when, which one is dropped, and that
  a request made while another was in flight comes back. What nobody has seen: how long a
  school year takes to arrive over a school's wifi, and what three years of day rows cost a
  `LazyColumn` that re-reads them on every write. The cap of three is a guess.
- **The calendar and the day screen have been looked at on an emulator only** (#186): the
  week, the month, the day's ribbon and its list, and «Подробно». What the tests prove is
  which accent a day resolves to, where a band of one reason ends, which row
  «вернуться к текущему» lands on, and which of three depth levels a given API level gets.
  What nobody has judged: the four accents beside one another, whether the summer's tint is
  faint enough across sixty cells, whether the perspective tilt reads as depth or as a
  wobble, and whether the AGSL band reads as light rather than as a smear. **What it costs
  on a phone is unmeasured too** — one shader layer and a per-frame clock on a mid-range
  phone is a frame budget nobody has looked at. #111's flings on the emulator put the shader
  below that emulator's own floor, which is relative only, and #111 stays open for a phone.
  **One qualification, earned in #79:** the ribbon *has* now been run on a real phone, and
  it crashed on opening. What that proves is that the three defects #79 fixes were the ones
  reported, and the bugreport says so in the platform's own words. It proves nothing about
  how any of it looks, because nobody got far enough to see it. #186 got that far on the
  emulator, and the ribbon opened without the crash.
- **No rule stops another composable reading `BuildConfig` directly**, which is how #81
  happened. Three tests hold `AboutCard`; nothing holds the next one. Such a defect is
  invisible on a pull request by construction — `ci.yml` does not set the build properties
  and should not, because it does not produce an APK anybody installs.
- **The server badge has been drawn against a real server once, on an emulator** — «Сервер:
  база и код разошлись» from a local `create_all` database, correctly — and not yet from a
  phone on a school's wifi. `ServerStatus` has four
  states and a test for each, all of them from a fake; nothing in the suite opens a socket.
  What that leaves unchecked is the shape of a real answer — whether `/api/v1/warmup` from
  a phone on a school's wifi resolves into `Ok`, `Degraded` or `Unreachable` as intended,
  and how long it takes to say so. It is also the first thing in this app that makes a
  network request from the settings page, so it is the first that can make that page wait.
- **#80's screens have been on an emulator, and nobody judged what this bullet asks.** #186
  opened the about card — «Схема unknown» on it was #172 — and the calendar. The tests
  compose the about card, the calendar header and both link buttons and assert their text
  is *displayed*. What nobody has judged: whether the concentric corner reads as
  concentric, whether a marquee that never stops is pleasant rather than merely readable,
  and whether stepping a month with the arrows surprises somebody who has just switched
  «День» into its list mode.
- **The widget's new corners have been on a launcher only on an emulator's, and nobody judged
  them there**: #186 and #189 had the widget on the Pixel launcher at a few sizes, and #113
  stays open for the corner radii. A launcher is the only place they exist. #82 derives every
  inner radius from the size class's own padding, and the five tests reproduce that
  arithmetic and nothing else — no test draws a widget. What nobody has
  seen: whether the gap now reads as even at 8 dp of padding *and* at 16, whether the 6 dp
  floor is tight enough to still look like a corner on the widest rungs, and how any of it
  sits against a launcher's own widget rounding. Below API 31 the question does not arise —
  `cornerRadius` is a no-op there and the launcher supplies square edges — so the check
  needs a phone on 31 or later and a widget resized twice.
- **#189 was seen on an emulator's launcher, and only as far as the day-off layout goes.**
  The 2×1, 2×2 and 2×5 after school, at font scale 1.0 and 2.0, in both languages. Not
  seen:
  - the one-line size during lessons at a large font, where «ПЕРЕМЕНА» is weighted beside a
    countdown the same way «ДЗ на завтра» was beside its count;
  - whether a change of the *system* font scale redraws the widget by itself — it decides
    the rung and the 2×1's count, it is a configuration change rather than one of the three
    app settings #188 now redraws on, and every render in that check was forced with a
    reinstall;
  - any launcher but the emulator's.
- **#83's widget work has been on a launcher only on an emulator's, and that is the surface
  #83 changed most.** #186 put the widget there at four sizes and two font scales, where the
  2×2 and, at the larger scale, the 2×1 still cut their text (#174, fixed by #189 and seen
  again there, above). The font-scale division, the two weights that stop the trailing
  detail starving the subject, and the outer box that gives the seven day chips their gaps
  are all proved by tests that reproduce the arithmetic and the budgets — no test draws a
  widget. Worse for confidence: **two of the claims are about what Glance does with a
  modifier chain, and they come from reading its translator rather than from pixels** — that
  `applyModifiers` folds every padding into one `setViewPadding` on the background's own
  view, and that a container keeps ten children. What nobody has seen: whether the largest
  system font fits the rung it lands on at the sizes #186 did not use, whether the chips read
  as seven days, and
  whether the subject keeps its width beside a long teacher's name. Below API 31 the corner
  half of this does not arise at all — `cornerRadius` is a no-op there.
- **`docker compose up` has never been run.** There is no Docker in this environment, so
  what #83 proves about the new `migrate` service is that the compose file parses and that
  its dependency conditions are what they claim — `service_completed_successfully` before
  the API starts. Nobody has watched the stack come up, nobody has seen
  `alembic upgrade head` run inside the image, and nobody has confirmed that the revisions
  the Dockerfile now copies are the ones it needs. #190, #191 and #195 changed the file and
  the image again — a password the stack refuses to start without, every setting passed
  through, a non-root user — and are read by `test_compose.py` and `test_dockerfile.py`,
  not by a container.
- **The animations #247 added have been seen only in two frames on an emulator.** Rows
  opening under their switches and the selected tab's label being revealed were caught with
  the animator scale at ten; the list items fading and sliding on «Сегодня» and «Задания»,
  and the lessons' cross-fade, have not been looked at, and what the extra fades cost a cheap
  phone's GPU is unmeasured.
- **Back in settings follows a path now (#243), and only its rules are tested.** Nothing
  composes `HomeShell`, so that its back handlers and the pill read the path rests on one
  walk on an emulator; a rotation or a process death in the middle of a path, which the
  saved names are meant to survive, has not been seen.
- **#201's Keystore seal and #202's https-only release have run on emulators, not on a
  phone** (2 October 2026, #241). Seen: an upgrade in place sealing a plain-text membership
  and the sealed token opening after a force-stop and a reboot; a release build refusing
  `http://` to anything but the loopback names at the address field, joining over
  `http://127.0.0.1`, and reading production's warmup over TLS. Not seen: a restore onto
  another phone or a wiped Keystore, which should read as no token; the diary's bearer,
  sealed the same way, because no diary session was opened; and a release build joined to a
  production class.
- **Dependabot's `uv` entry (#192) has not opened a pull request yet**, so the way a bump
  regenerates the lock is read from dependabot-core rather than seen. The lock itself was
  installed on Linux and Python 3.12 by #214's CI, which ran the suite on it, and Vercel
  built #214's preview from it; the local runs were all Windows and Python 3.13.
- **No Compose test was actually made to hang**, so `MarqueeClockTest` — the guard #83 added
  for the trap that costs a whole Gradle run — is reasoned from `MarqueeText`'s
  `Int.MAX_VALUE` iteration count and from the list of files that call `createComposeRule`.
  It is the honest status of a meta-test: what it proves is that thirteen files say in their
  own words why they cannot overflow, not that the fourteenth would have hung.
- **Eighteen `AdrenoVK-0: Shader compilation failed` lines in that bugreport are
  unexplained**, and no other app on that device logs them. They are `I`-level, carry no
  shader source and no reason, and are spread across screens rather than clustered on the
  ribbon, so nothing in the report ties them to a defect anybody saw. The honest status is
  «замечено, не объяснено»: it is a real signal, it is nobody's confirmed bug yet, and the
  first question is whether the AGSL sheen is even the source, since a `RuntimeShader` that
  would not compile normally throws in-process instead of logging from the render thread.
- **The reported term defect is fixed against tests, not against the class that reported
  it.** The server fix needs no new APK, so the first real check is that class's next sync
  after the deploy, and no batch since has recorded one. The cheapest sign that the
  migration and the code met is in: production's `/api/v1/warmup` answered
  `"schema":"0017"` on 26 September 2026, read through the Vercel connector.
- **There is one `androidTest` in the project, and it is about one gesture.** Until #187
  there was none, because until #186 no session had an emulator: the cloud containers have no
  `/dev/kvm`. `ToolbarOnDeviceTest` (#110) drags the tab bar's arranging gesture at a
  device's own density, on demand and never in CI; every other screen is still asked on the
  JVM only.
  **But "nobody has pressed it" is untrue for a good deal of the app by now.** Robolectric
  runs Compose's test harness on the JVM (`:core:designsystem` already lived this way), and
  `:app` has twelve files that compose a real screen and press it: the class group, the
  join-mode switch, the code screen's refusals, the bell rows, the schedule sheet, the term
  label, the onboarding reveal, the debug report, the translation session, the edge fade and
  the Telegram card's failure — 43 tests between them. It started at three screens and 21
  tests, which is what this paragraph said for several batches after it had stopped being
  true. Those are real presses on real strings rather than stubs: the locale is pinned to
  `ru-rRU`, or Robolectric takes `values-en/` and the test checks the translation instead of
  the source.
  What this does **not** prove: how it looks. Not the layout, not the dark theme, not the
  animations, not dynamic colours, and not the widget — about which what is proved is exactly
  that the size ladder is monotonic over real sizes.
- **Every card in the bot is now drawn**, and that found eight defects in one pass
  (section 4, in `docs/history.md` now). The hole that is left is exactly where it was: the
  tests assert what is
  written on a card rather than how it looks in a client. A live Telegram has shown these
  cards once, in #186's walk — `/start`, «📱 Подключить телефон» and the class «11А» driven
  from the owner's chat — which recorded what the bot did and not how a card looked.
  **Nobody draws the widget.** Glance has `glance-appwidget-testing` at the same version as
  the Glance in this project (`1.3.0-alpha02`) — the twelve rungs of the size ladder could be
  rendered and compared with no device. Not done.
- **Nobody has made a correction over the diary on a screen.** They are covered by tests on
  both halves — the key when a class is split into groups, an empty correction, the diary
  moving out from under a correction, the "correct / reset / do nothing" decision, and that a
  correction survives signing out and back in. **Not one test opens the correction window.**
  Nobody has pressed a lesson, saved, reset, or seen the «Исправлено» mark. The test counts
  are deliberately not named: they go stale in one commit, and `./gradlew test` and
  `pytest -q` print them themselves.
- **The real dnevnik2 has still not been opened.** The corrections are proved against a
  hand-written stub — exactly like the rest of the integration, and exactly as far as it
  claims. In particular, nobody has seen a real day split into groups, which is what the
  refusal to apply a correction on a key collision was made for.
- **Nobody has held two classes on one phone.** Storing memberships and splitting the cache
  by class are covered from below; from above, `ClassRowsScreenTest` now presses the group
  itself: the tick stands next to the class being shown and it is not a button, another row
  calls the switch with the right id, and there are two ways out, each saying how far it
  goes. The last is the one that deletes data if you get it wrong: with one class, «Выйти»
  always meant "leave this one", and there used to be nothing to catch the day that row
  quietly started meaning "all of them".
  #186 held two on an emulator (#116): the switch was seen, and the widget's redraw after a
  second switch inside one Glance session was found broken and fixed (#168). Still seen by
  nobody: the re-planning of the alarms, which is inferred from code that is called rather
  than observed.
- **Nobody has switched the join mode on a live class** — but the switch is now pressed in
  tests: the confirmation appears only in the direction that takes something away, «Отмена»
  writes nothing, and restoring the class code writes straight away. It is covered lower down
  the stack too: the class code's refusal in `invite`, a personal code for one phone, a race
  between two requests for one code, a stale button, the sweeping, a `PATCH` in both
  directions, the log line, and that the column holds `OPEN` rather than `open`. The presses
  are `ClassJoinModeScreenTest`'s. #186 saw «📱 Подключить телефон» in the live bot, its
  code link a phone as «Владелец», the spent code answer `404`, and the invite-only class's
  own code refused in words; nobody has flipped the switch on a live class or seen the
  confirmation in the app.
- **The `429` on the code screen was seen once, on an emulator (#186):** «Слишком много
  попыток. Попробуйте через 15 минут.», with `Retry-After: 866`.
- **The upgrade path has been installed over once, on an emulator (#117)**: a build of
  `fa4fe0c` upgraded to `bd7c816` kept its class, its tab order and its first run done. What
  follows is what held it before. An old install
  kept its class in four flat keys; a test builds exactly those keys and makes sure the class
  is found and the "introduction shown" flag is not reset. Nobody has upgraded a real
  installation of the previous version. If that is broken, the user sees the code field
  instead of their class — and nothing in the logs.
- **The build-chain bumps have been looked at only as far as #186's walk goes.** AGP
  9.3.1 → 9.4.0 → 9.4.1, Gradle 9.5.0 → 9.7.1 and compose-bom 2026.06.01 → 2026.09.00 are
  proved by every test passing and both assembles building — locally and on the runner.
  The same goes for the two Python floors raised the same way, sqlalchemy 2.0.54 and
  pydantic 2.13.5: the suite passes on them and nobody read either changelog. The
  compose-bom is **the app's entire rendering**, and one of its effects already surfaced by
  itself (`OverlayLayerTest`, section 6). Both builds #186 walked on an emulator —
  `bd7c816`'s release and its own branch's debug — are on all three, and the one defect it
  filed that names any of them is #185, AGP 9.4's warnings about `srcDirs`: a build
  message, not a screen. Nobody has set a screen beside one from a build before them, so
  what the compose-bom changed where there is no test, nobody knows. On a phone, that is
  the first reason on the list to open the APK.
- **Both alarm fixes are unverifiable without a device, in principle.** They are inferred
  from the platform's contract and from neighbouring code that had already taken the same
  decision (`SchoolAlerts.arm` refused `setWindow` and explained why).
- **A live DaData has never been asked**: there is no `DADATA_TOKEN` in the development
  environment, and every test on both sides runs a stub. The owner reported setting the key
  on Vercel — **nothing has verified that.** #140's anonymous school directory rests on the
  same stub, and so does its daily count.
- **The real dnevnik2 has never been opened**, neither the sign-in — the server's or, since
  #140, the phone's own — nor any of the four screens.
- **Not one server-side fix has been run against a live class.** `claim` is verified by
  claiming twice on one date, but nobody has run two ticks at once; the export's round trip
  is verified by computing both sides, which is all it claims.

- **#62, the batch that stopped text being cut off, has been seen moving only in the reports
  #80 answered**: on a phone, a line that scrolled beside one that had spent its three passes
  and parked, which #80 made endless. Robolectric composes the marquee but advances no
  animation and reads no frame, so what is proven is that a line which fits is laid out
  exactly as before, that a line which does not stays inside its box rather than pushing
  the row apart, and that the whole string is present either way. Whether it reads well
  scrolling needs the APK. The same for the other direction: nobody has seen what an uncapped block does with a long string. **A five-line
  lesson note now makes a five-line row**, and whether an uncapped heading pushes a sheet's
  first control too far down is a question only a screen can answer.
- **The fade widths are chosen, not measured.** 8 dp at each end, narrower on a segment
  because a segment is narrow; nobody has looked at them beside the Essentials original they
  came from.

- **The pull request flow has never reached GitHub.** Signing in was already exercised only
  as far as the token; opening a fork, cutting a branch on it, committing a file and opening
  the pull request are four calls that have never been made from this app to a real account.
  `StringsDocumentTest` proves what goes *into* the file, and nothing proves what happens to
  it afterwards. The failure modes that were closed — a repository merely named `lessons`, a
  branch cut from a stale fork, the owner's own 422 — were reasoned about, not observed.
  **The first press should be the owner's**, because the first press is also the first test.

- **The guide has never been fetched.** The parser, the two languages' parity and the choice
  between a stored and a bundled copy are tested; the four steps between the app and
  `raw.githubusercontent.com` are written and have never run. Nor has the bundled fallback
  been read at runtime — the assets are proven only by listing the built APK, because
  `:core:data`'s tests have no `Context`. The pager, the loader on the guide, the banner
  naming its version and the arrow that leaves it have been looked at by nobody: one of
  #186's screenshots caught the guide open, where the owner had left it on the shared
  emulator, and what it showed — or whether it had come from GitHub or from the APK — was
  not recorded.

**The most useful next action is to install the APK on a phone and live with it for one
school day.** After that the only questions left are about runtime and layout, and those are
invisible from anywhere except a real screen.

---

## 6. What you need to know so as not to break things

Beyond what is already in `CLAUDE.md`.

Fourteen subsections that stood here until 27 September 2026 are in
[docs/history.md](docs/history.md), «Moved out of section 6». Eleven retold, in older words,
rules `CLAUDE.md` carries — escaping what comes from outside, callback data, two screens on
one press, the translation guard, the order a constraint migrates in, the end of the school
year, a lesson and its bell (twice), a list and its keyboard, `mypy`, a renderer and its
type; one described `.claude/`, which `.claude/README.md` describes; and two narrated batches
that are done — the move into English and the documents' sweep of 19 September. What those
two left alone on purpose is kept below, under «Two things left in Russian on purpose».

### CI and building the APK

`android-actions/setup-android` is **pinned to `v4.0.1`** and is given
`packages: platform-tools` — in `ci.yml` and in `apk.yml`. That is not cosmetic: a floating
`@v4` asks by default for the `tools` package, which Google removed, and `sdkmanager` now
returns a non-zero code for it. Without the pin, every Android run fails before Gradle
starts. **Do not remove the pin and do not drop `packages` without checking that the action
has stopped asking for `tools`.**

### The cache is split by class

`school_day` carries a `class_id`, and **every read in `TimetableDao` requires it as a
parameter** — not by default but mandatorily. That is not pedantry: two classes' windows lie
side by side on the phone, both weeks look like a plausible school week, and a query that
lost the filter will draw somebody else's timetable, which on screen is indistinguishable
from your own. The compiler is the only thing here capable of catching a missing filter,
which is why the parameter is mandatory.

A sync writes under the class the **server** matched to the token
(`timetable.schoolClass.id`) rather than the one the phone thinks is active: there is a
second between those two answers during a switch, and taking the local one would mean filing
one class's window under another's name. `replaceAll` moves the `classId` from the class's
row onto the day records for the same reason.

`lessons.db` (`LessonsDatabase`) is at Room schema version 5, and #140 added a second
database, `diary.db` (`DiaryDatabase`), at version 1. Neither has a migration and neither needs
one — both are disposable (`fallbackToDestructiveMigration`): the first sync fills the one and
the next diary read the other.

The memberships live in `Memberships.kt`, separately from `LessonsPreferences`: that is also
where the **old format** is read — one session in four flat keys — and where the
"introduction shown" flag comes from. The four keys are deleted on the first write of the
list. **Do not bring writing to them back**, and do not touch the fallback read path without
reading `MembershipsTest`: it is about installations already sitting on people's phones.

### Corrections over the diary

A correction's key (`target`) is **built by the server alone**, and the client hands it back
verbatim. Two implementations of a key that has to match byte for byte agree exactly until
the first lesson with no number. The format is in `services/diary_overrides.py`, and it is
semantic rather than positional: this project has already shipped positional keys once (the
calendar's UIDs), and deleting one element moved every later one onto somebody else's
subject.

A lesson's key is the day, the number **and** the subject, and either half alone is not
enough: the number alone collides when a class is split into groups (the diary numbers
lessons within a day and is not shy about giving two the same number), and the subject alone
collides on a doubled lesson. Together they are no guarantee either: one subject taught to
two groups in the same hour gives one key for two lessons, and what tells them apart is the
room and the teacher — that is, exactly the correctable fields. So a key that landed on two
lessons is **applied to neither**, and the client says so. Do not "simplify" it down to one
number.

**Marks and the turnstile cannot be corrected, and that is not an unfinished feature.** A
mark is a statement about what happened; an app that lets you rewrite one produces a forged
record that looks official. The set of fields is closed in `FIELDS_BY_KIND`.

**The bot does not apply corrections** — deliberately. The bot draws a day as one block of
text with no button on a lesson, so a correction there would be indistinguishable from the
school's own and there would be nothing to take it off with, and that is the surface a parent
reads more often. There is a comment about this in `app/bot/handlers/diary.py`; if the bot
ever gets buttons on a lesson, apply them **and** mark them, but do not apply them silently.

Corrections live on the account's login rather than on the diary's session: the session dies
every few days, and a correction that went with it would disappear by itself before anybody
pressed "reset".

The diary's `422` means two different things: on a read, a bad date range; on a correction, a
refusal. The caller tells them apart (`DiaryFailure.of(failure, unprocessable)`), because
nothing in the answer does except a Russian sentence in `detail`.

**A row with no key must not be made pressable.** The server builds `target`, and a row that
arrived without one (a server older than `0009`, or a lesson the server could not enclose in
a key) cannot be matched to any correction. Pressable, it sends every press into a `422`.
`DiaryLesson.correctable` / `DiaryHomework.correctable` are that very question, and the "press
a lesson" hint is not printed when there is nothing on the week to press.

### Who lets a phone in

A class has a `join_mode`: `open` (the class code lets in anybody who types it) or `invite`
(the class code lets nobody in, and a phone joins with a personal one-time code from the
bot). Both are typed into the same field of the same `POST /api/v1/join` — for the app that
is one screen and one error — and they do not collide because **the lengths differ**: a class
code is eight characters, a personal one is ten. `services/device_invites.CODE_LENGTH` is
written about exactly this; if you change the length, check that it is still not equal to
`JOIN_CODE_LENGTH`.

**Switching takes nothing away.** Neither `invite` nor a return to `open` touches the tokens
of phones that are already connected. That is promised on three screens in a row, and
breaking it means dropping the timetable for a whole class at once, with not one line in the
logs.

**An enum is stored by name, not by value.** `SAEnum(JoinMode)` puts `OPEN` in the column,
although `JoinMode.OPEN.value == "open"`; the API hands out that `value`. A draft of revision
`0010` set `server_default="open"` — that would have sat on every class, and the first ORM
read would have failed with a `LookupError`, that is, for the bot in the middleware, that is,
the whole bot at once. Caught before it was applied; two tests in `test_join_modes.py` hold
both sides. **Ask the same question of any future enum with a `server_default`.**

**`is_public` is gone.** It sat on the class card, printed as «публичный / закрытый» — and was
read by no code path at all. The column stayed in Postgres (dropping a column is not
additive) and is not mapped in the model; there is a comment above that spot in `models.py`
asking that nothing be hung on it again. A second sign of "who is let in" will drift from the
first sooner or later.

**A personal code is burnt by a conditional `UPDATE` rather than by an assignment.** "One
code, one phone" is a statement about two requests rather than one: two requests that passed
`find_live` in the same instant would both write `used_at` and both get a token.
`device_invites.burn` returns whether it won, and `/join` burns the code **before** it issues
a token.

**The switch button carries the mode it wants, not "the other one".** A keyboard is a
message, messages stay in the chat, and an admin with two «👥 Доступ» pages open would
otherwise press a button still captioned "invitation only" and give the class code back to
everybody who still had it. Pressing the mode that is already in force says "it already is"
and writes nothing to the log. **Do not "simplify" it back into a toggle** — the `is_public`
that was removed had exactly that shape, and it was harmless only because nobody read the
flag.

**What the bot promises and what it does not.** A phone's role is bounded by the role of
whoever issued the code, and is checked on every request. The number of phones is bounded by
nothing: any member can press the button and forward codes, so "by invitation" replaces one
shared secret with a named person who decides each time, rather than with a smaller number of
readers. Every phone that arrives carries the account that let it in — in «📱 Устройства» and
in the log (`device.link`, written in `/join` rather than in the bot: that is where the
connection happens). This is said out loud in `phone_code`'s docstring, because the previous
version of that docstring claimed the opposite.

**Revoking a member kills their unissued codes** (`device_invites.drop_for`). A code is
checked only by its hash — `find_live` does not re-read `BotUser` — so without this, somebody
excluded in the last fifteen minutes would still let a phone in. Used rows are left alone:
they are the record that a phone has already joined.

### The second way in: a phone that signs in to its own diary

`docs/architecture.md` says where the password, the upstream session and the two bearers
live during the first run, and where they never do. These are the parts that break quietly:

- **On the phone's path the password goes to the diary and nowhere else.**
  `POST /api/v1/diary/session` refuses a body with a `password` key (`extra="forbid"`), and
  every credential value passes the one "safe to put in a header" check in
  `providers/diary/http.py`, because cookie values go straight into `Cookie:`. Do not add a
  field there that could carry a password. `/diary/login` and the bot's `/diary/signin`
  still take one, and say so to the person typing it.
- **The phone's diary hosts come from the bundled catalog and nowhere else.** `OriginGuard`
  refuses any other origin; the catalog is `server/app/catalog/data/regions.json`, bundled
  in place rather than copied, and its «Сетевой город» origins are the server's allow-list
  byte for byte. Change the allow-list or `docs/diaries/regions.md` and regenerate with
  `scripts/region_catalog.py` — `--check` and
  `test_the_committed_catalog_is_what_the_generator_writes` fail otherwise.
- **The two sign-in implementations are held together by one file.**
  `server/tests/vectors/diary_protocol.json` runs in the Python tests and against the Kotlin
  port; a protocol change is a change to the vectors first, then to both.
- **Nothing reads the diary in the background** — not the sync worker, not the widget.
  `SyncWorkerSourceTest` scans them and every application, receiver and service the manifests
  declare. A background read would count as the family's activity and hold a «Сетевой город»
  session open for the keep-alive's thirty days with nobody behind it (#142).
- **Periodic class sync is armed only in class mode**, from the shell mode's arming in
  `LessonsApplication` rather than on every settings emission (#152).
- **The directory's share of DaData is its own.** `/api/v1/directory/school-regions` is
  charged to `usage_counters` under a cap of 4,000 a day; the bot's and `/manage/schools`'
  searches are never charged to it. Putting them on one counter is how onboarding would
  spend the search the bot's create-class step depends on.
- **The legal address is per fork.** `LESSONS_LEGAL_BASE_URL` compiles into
  `BuildConfig.LEGAL_BASE_URL`, and the build refuses a value that is not https; the texts
  carry `FORK:` comments naming what a fork rewrites, and `docs/build.md` says the rest.
- **CI runs a job when a file its tests read changes**, and `ci.yml`'s path filter names
  those files; a test that starts reading a document outside its half adds the document
  there (#159).

### The correction mode knows about all the text, and two imports hold it

The mode's coverage is not a list of places wrapped by hand. `:app` and `:core:designsystem`
have **one** `Text`, and it is their own: `core/designsystem/text/Text.kt` — Material's plus
`modifier.correctable(text)`. Strings are read through `correctedString` rather than through
`stringResource`: that both substitutes the correction and remembers which resource drew
these words, for as long as they are on the screen. A long press asks the registry rather
than `values/` — a key cannot be looked up by text, since 204 strings of 991 match another's
text, and another 100 are patterns with arguments.

Both original imports are still on the classpath and still compile, so a new screen written
out of habit would simply be a page the proofreader cannot touch, and nothing would say so.
`CorrectionReachTest` holds this: it reads every module's sources and fails on
`import androidx.compose.material3.Text` and on `import androidx.compose.ui.res.stringResource`.
There are three exceptions (`Corrections.kt` itself, the editor and the corrections sheet),
each named by path, and a separate test checks that an exception did not outlive its file.

`CorrectableTextTest` compares the shim's signature against Material's by reflection: an
undeclared parameter would silently stop working on every screen at once, while the call
still resolves. The signature grows — `autoSize` arrived in 1.4 — so checking it by eye once
would have been pointless.

And one line without which all of this quietly ceases to exist: `MainActivity` wraps the app
in `CorrectionHost`. Without it everything builds and draws, and the default implementation
does nothing. That is in a test too.

### The guide is fetched, and a debug-signed APK is not an update of another one

Two things about builds and documents that cost an afternoon each:

* **Every debug-signed build carries a different certificate.** `~/.android/debug.keystore`
  is generated on the machine that builds, the first time it is needed — constant alias,
  constant passwords, a fresh key pair. A CI runner is a fresh machine and the Gradle cache
  covers `~/.gradle`, not `~/.android`, so one APK run's build never updates another's, and
  Android says only «Приложение не установлено». `versionName` is never compared and a higher
  `versionCode` does not help. The cures are an uninstall, which takes the device's classes,
  token, diary session and corrections, or the four keystore secrets. `docs/build.md` has the
  long version.
* **The in-app guide is markdown in `docs/app/`, not `values/`.** `docs/app/` is
  `:core:data`'s asset folder rather than a copy of it, so a change to the guide is one edit
  in one place: the app fetches those same files from the repository and falls back to the
  ones in its APK. The parser understands `##`, `-`, `1.` with a bold lead, `>` and three
  inline marks, and **never throws** — anything that is not a guide parses to no pages and
  the stored copy stands. Correction mode cannot reach that text any more, which is the cost;
  `DocsGuideParityTest` reads the shipped files and holds the two languages level, the job
  `ResourceTranslationTest` does for everything still in `values/`.

### A correction leaves the phone as somebody's pull request, and the fork is not its name

The sheet that collects corrections can now open a pull request against this repository, and
it opens it from the reader's own account — the `public_repo` scope the sign-in already asks
for is enough for a fork and a pull request both. Four things about that path are easy to get
wrong and are written into the code rather than left to memory:

* **A fork is identified by its parent, never by its name.** `lessons` is not a rare word.
  Asking `/repos/{login}/lessons` and reading a 200 as "the fork is there" is how this app
  would cut a branch in a stranger's unrelated repository and commit to it. The check reads
  the body and requires `fork` with the parent's `full_name` equal to this project.
* **The branch is cut from the upstream commit, not from the fork's head.** GitHub allows a
  ref at any commit in the parent network, and a fork made once and never synced would offer
  everything merged since back as reverts.
* **The owner cannot fork their own repository** — GitHub answers 422 — so for them the
  branch goes straight to the upstream repository. That is the case the first press will hit,
  and it is the owner's press that should be first.
* **`POST /forks` answers 202,** not 201: the fork is created afterwards, so it is polled
  for. And GitHub wraps base64 contents at 60 characters, so the decode must ignore line
  breaks (`Base64.DEFAULT`) while the encode must not add them (`Base64.NO_WRAP`).

`StringsDocument` is the part of this with tests: it replaces one element's body and leaves
every other byte alone, refuses a key the file does not declare rather than appending it, and
does not confuse `settings_title` with `settings_titles_plural` or with a `<string-array>` of
the same name. Everything past that — fork, branch, commit, pull request — is written and has
never run.

### Idempotent is not the same as safe under a race

`/api/v1/bundle` is a read, and it writes: it seeds the terms and the subject dictionary for
a new class. Every phone in the class polls it on one timer, so "both found the year
unseeded" is the ordinary case rather than a rare one. Both insert index 1, `uq_term_slot`
refuses the second, and on Postgres an `IntegrityError` poisons the whole transaction — a 500
on a read, on exactly the first request the seeding exists for.

The shape this project already knows: the insert inside `session.begin_nested()`, and **the
loser yields** — rolls its rows back and returns the other's (`terms.ensure`,
`subjects._adopt`). The only one that must not yield is an explicit change of scheme:
quietly accepting quarters means answering "done" to an admin who asked for half-years.

The same rule about the sign-in ticket: `diary_link.claim` and `device_invites.burn` are both
one conditional `UPDATE`, because "one link, one sign-in" is a promise about **two** requests
rather than one.

**And separately: do not commit by a counter.** `sync_from_timetable` returns how many
dictionary entries were *created*, but it also links the timetable's rows.
`GET /api/v1/manage/subjects` committed `if ...:` — and for a class with a full dictionary and
unlinked lessons the work was done and thrown away on every read.

### A slot is one "every week" row or two halves

`uq_timetable_cell` is `(class, day, number, parity)`, and "every week" next to "numerator"
does not violate that key. Both rows pass the parity filter on an odd week, and `_load`
selects the template **with no `ORDER BY`** and keeps the last — so which subject the phone
sees is decided by the database, and the answer can differ between two reads. Three places
hold the rule, and all three are needed: `timetable_io._conflicts` on a paste, the editor's
handler, and — as of this commit — `timetable_edit.edit_lesson`, that is, the one entrance of
the second shell.

### The intercepting layer and the compose-bom

For two releases `OverlayLayerTest` asserted that a layer swallowing gestures to keep a touch
from reaching the pager beneath it cancels presses on its own rows with the same movement —
and that there is no fix. With **compose-bom 2026.09.00** that stopped being true: the same
layer over the same rows lets presses through. Narrowed to the bom itself (with navigation
2.10.1 and room 2.8.5, rolling back the bom alone restores the old behaviour). The test was
rewritten for the new order.

**We are not going back to the layer, though**, and that is the main thing to take away here:
the delivery order between two subtrees is promised nowhere, it moved under a dependency bump
silently, and a finger on a row would just as silently stop working again — and that bug has
already shipped twice. The tabs are still removed from the composition rather than covered.

### The dependency mirror

The root `requirements.txt` is a **lock** since #192: exact pins for the whole tree,
compiled by uv for CPython 3.12 on Linux from the root `requirements.in`, whose header names
the command. `requirements.in` and `server/pyproject.toml` have to carry the same floors.
Dependabot edits **the root files only** — which is how `main` once drifted
(`sqlalchemy>=2.0.52` against `>=2.0.30`, `pydantic-settings>=2.15.0` against `>=2.4`) — and
its `uv` entry raises the floor in `requirements.in` and recompiles the lock, so pyproject is
still raised by hand. Tests hold this rather than attentiveness:
`tests/test_requirements_mirror.py` checks the input against pyproject in both directions,
that every lock line is one pin inside its floor, that the lock was regenerated after the
input changed, and that it is closed on Linux/3.12; the three packages deliberately absent
from the bundle (`uvicorn`, `aiosqlite`, `alembic`) are listed there with their reasons.

### Week parity

`week_parity` counts weeks **from the start of the school year** rather than by the ISO
number. The first week of the year deliberately keeps the parity the old rule would have
given it: in 2024/25, 2025/26, 2027/28 and 2031/32 both rules agree day for day, and they
differ only inside the two years where the old one went wrong. **If you touch this place, do
not "simplify" it back to `isocalendar().week`.**

### Deleting a subject

A subject that stands in the timetable can no longer **be deleted** — the API answers `409`
and the bot shows how many lessons use it. That is a change of contract rather than a bug:
the dictionary fills itself from the timetable, so a deleted name came back on the very next
read with no colour and no teacher. The order is: take the lessons out of the timetable
first, then the subject out of the dictionary.

### The timetable paste grammar

The last field takes the rest of the line, so a teacher «Иванов И.И., к.п.н.» is written with
no syntax. A subject or a room containing a comma is wrapped in double quotes, and a `""`
inside is one literal quote. Quotes are used only where they are needed, so any line already
written parses as it did before.

### Two things left in Russian on purpose

* **The commit history keeps its Russian, by decision.** Of 242 commits, 41 carry Russian
  prose outside quoted product strings — about 2,055 characters, mostly in merge-commit
  bodies. Rewriting them means rewriting every hash from the first affected commit and force
  pushing `main` and `dev`: every existing clone breaks, the merge references in the pull
  requests stop resolving, and the release tags move. The owner decided not to. So a `git log`
  older than #51's move into English reads in two languages, and that is expected rather
  than missed.

**One thing the documents' sweep of 19 September 2026 found and did not touch.** Five test
functions on the server carry a Russian
word in their names — `test_a_day_with_one_maximum_length_задание_still_sends` and four like
it, in `test_bot_message_limits.py` and `test_bot_manage.py`. The rule says identifiers are
English; it also says a quotation of what the user sees keeps its Russian, and an identifier
has nowhere to put guillemets, so these sit between the two halves of it. Renaming them is a
code change and would have to pass the gates, which a documentation sweep does not run — so
it is written down here rather than done quietly. Nothing else in `server/` or `android/`
has a Cyrillic identifier: Kotlin has none at all.

## 7. Left to the owner

All of this is beyond an agent's reach: it needs a phone, a key or a live service.

**These are issues now**, so that they can be closed rather than re-read: #118 the Preview
environment, #120 the external cron and `DADATA_TOKEN`, #121 the real diary, #122 the
widget's tick cadence and the diary credential's bound, #135 the second diary, #144 a default
server for a fresh install. Each carries the label `needs:owner`. #119 (`/api/v1/warmup` and
`/start`) and #156 (the backup) were answered by #186 and closed at its merge on 26 September
2026.

**Decide whether to change the release signing passwords.** On 5 October 2026 an agent of the
session that wrote #311 read `~/.gradle/gradle.properties` and printed a signing password into
that session's transcript. It is in no file, issue, commit or pull request. `keytool` changes a
keystore's passwords without changing its key, so a phone that installed the APK still accepts
the next one; the new values then go into that file and into the Actions secrets —
`KEYSTORE_BASE64` as well, because the keystore file changes with its password.

**The release keystore is one key in two places, and a new password goes into both.** The
local release build and the APK workflow were both signed `CN=lumenpearson` on 2 October
(`docs/history.md`, «Moved out of section 7 on 2 October 2026»), so the Actions secrets hold
the same keystore as `~/.gradle/gradle.properties` names. On 5 October a session asked to do
the rotation was refused by Claude Code's own safety classifier («secret-store writes»), so
it stays here.

**Make #330's rule a rule (optional).** `apk.yml` signs with the real key only on `main` or a
`v*` tag, but the guard binds the workflow as committed on the ref being run, and the four
secrets are repository secrets any branch's workflow can read. Moving them into a GitHub
Environment open only to `main` and `v*` tags, with a ruleset on `v*` tags, would make it
hold against somebody with push access too (`docs/build.md`, «A public repository»).

**Give the Vercel connector the project's scope again (optional).** On 5 October its token
answered `403` for the team «codeilluminators» the project lives under, so a session could
read neither the runtime logs (whether the external cron calls the tick, #120) nor the
environment's names.

**Three lines in `.claude/settings.json`, which a session may not edit itself** (#314, #318):
- **Under `deny`**, `Read(~/.gradle/gradle.properties)`. It stops the file tools from opening
  the passwords' file. It would not stop a shell `cat`, which the instructions alone hold.
- **Under `allow`**, `Bash(pytest *)` and `Bash(pytest)`. The list holds only `python -m
  pytest`, so the bare command CI runs, and every gate document now gives, asks for
  permission each time.
- **`Read(./server/.env.*)` under `deny`** also catches `server/.env.example`, which holds no
  secret and documents every setting. Narrow it, or accept it.

On 5 October a `/permissions` run removed twenty allow rules from the file, and the owner
restored it with `git checkout`; none of the three lines is in it yet.

**Keep some space on C:.** It had about 1.3 GB left early on 27 September, and 14 GB when #187
began, the same night; its builds and one emulator boot left 12 GB, and #189's left 9.4 GB,
so each batch with a device in it costs two or three. The emulator refused to
start once below 2 GB, and the AVD's Quick Boot image alone is 8.5 GB. The worktrees under
`.claude/worktrees/` each carry their own Gradle build directories. On 2 October it had
4.5 GB at the start of #241's session and fell to 3.0 GB by #245, and #245 gave it room:
8 GB moved to `F:\MovedFromC` or deleted as a rebuildable cache, listed in #245's section,
for 11.3 GB free. Both AVDs live on F: (`Pixel_10_Pro_XL` 12 GB, `Release_Check` 5 GB).
`F:\MovedFromC` is yours to keep, put back or delete.

**Restart Android Studio once, when it is free.** Three changes wait for it, because the IDE
rewrites those files on exit: `server/.venv` as the Python SDK, the root module as a Python
one (which quiets «Unsupported Modules Detected»), and the third-party «Python Portable»
plugin disabled, since it fails to load on every start. Everything else in the IDE's set-up
is already in place (see «…the first walk of the app on a device», the section on #186, in
`docs/history.md` now).

**Add a `BUF_TOKEN` repository secret if CI's «Contract (Buf)» job is ever throttled.** The
job reads no secret today. If its log says Buf refused an unauthenticated request (429,
resource exhausted), create a token at buf.build and pass it to the job as `BUF_TOKEN`;
`docs/build.md`, «The v2 contract and Buf», says where. Nothing is needed until then.

**Optional: give the Postman collection a device token.** The second folder of «lessons — API
smoke (read-only)», «With a device token», skips itself until the environment «lessons —
production» has a **current value** for `deviceToken`; type one there from a phone joined to
a class, and leave the initial value empty so that it stays on that machine. The collection has
never been run in Postman, so the first run is also its first test.

**Next for the programme: stage 3b of sub-project 3, once #342 is in.** Stage 3a is #342.
After its merge, production is read as its plan's Task 11 Step 10 says
(`docs/specs/2026-10-05-server-v2-3a-plan.md`), and what it shows goes into the next
close-out; a `503` saying «v2 is not available on this deployment» there is a defect, filed as
an issue before anything else. 3b — the other 71 unary methods, the per-provider diary
registry and the Telegram notices as effects — has no plan yet, and sub-project 4's pull
request A can still run beside it, one heavy job at a time. The questions in section 5 about
Vercel's proxy and the second host (below) are 3c's inputs.

**Set `MIN_CLIENT_VERSION` only after sub-project 5's APK is on the family's phones, and never
above the version they run.** #342 adds the setting, optional and empty. Set, it refuses a v2
request whose `X-Lessons-Client` is below it with `CLIENT_TOO_OLD` (`docs/api.md`), and
`docs/deploy.md` gives the same advice beside the setting. A request without the header is
never refused, and no APK sends one yet.

**The tenth milestone exists, and #140 is on it.** The owner created
`v0.9.0 — NetSchool e-diary, onboarding via the school's diary` on 25 September and renamed
the other nine the same day. Its description is behind: it names issues #145–#159, and
#160–#165 are on it too, as are the external audit's #190–#211 of 27 September. The ninth's
is further behind — it names PRs #129, #133 and #134 only. A milestone's description is the
owner's to edit; nothing in a session here can.

**Decide where milestone 11's second host lives, when it is needed.** The design
(`docs/specs/2026-10-03-one-contract-design.md`, section 2) adds a long-running target beside
Vercel for native gRPC and the streaming beta, packaged as a `Dockerfile` for Cloud Run,
Fly.io or a VPS, and deliberately leaves the place open. It is not needed before sub-project
3, and until then that target is only ever run locally. #235 no longer waits on it: its
egress is a RUVDS VPS since 5 October.

**Give the APK a GitHub client id, or the developer mode stays shut.** The mode (#237) opens
through «Войти через GitHub», which a build without `LESSONS_GITHUB_CLIENT_ID` hides
(`docs/build.md`). Then, on a phone: seven taps on the version, sign in as an account with
push to this repository, and run the checks once from a phone's own network — the first
answer to whether Petersburg's host answers a Russian mobile network at all.

**Try the Petersburg account you supplied on the diary's own site, then change its
password.** On 5 October the diary answered `401` to it from an emulator, through the Russian
line and directly, and it answers a made-up account with the same 772 bytes, so only the site
can say whether the login is right («Outside the pull request, the same day», in the section
on #342). Turn the VPN off first, because the site is fenced to Russia. The password was
typed into a chat, so change it afterwards.

**Sign in once, for real, from a phone — it is the one question #140 cannot answer about
itself.** Build an APK from `main`, which has carried #140 since 26 September, install it on
a phone in no class, and take the diary path twice: a «Сетевой город» region that takes a
password, and Петербург. Whether the import arrives, or the phone says the diary refused the
session from our server's address — the `409` — decides whether the phone-registered path
works for that diary at all. It is #121's first live session, for both diaries at once. For
Петербург the server reaches the diary through the proxy since 5 October, so the answer is no
longer «Дневник не отвечает» before the phone is picked up; try the account on the diary's
site first (above).

**Then walk the rest of the first run on that phone.** The class code as the other way in;
leaving the last class with a diary signed in, which should land on the diary as the home
with «Выйти из дневника» still in settings (#151's path). The legal links, the ТОР hand-off
and the backup were seen on an emulator in #186; a phone adds the thumb, not the answer.

**Decide #144: whether a fresh install gets a default server.** The diary path needs our
server to register the session, and today a fresh install asks for its address where it is
first needed, because no address is compiled in and none is promised any more (#154). A
default is a build property and a decision about who that server serves; it is the owner's.

**Have the terms of use and the privacy policy read before anybody relies on them.** They
describe what the code does and claim compliance with nothing: whether a GitHub account named
as operator satisfies 152-ФЗ, the transfer to hosting in Frankfurt, the age line and parental
consent are questions for a lawyer. Each passage about this deployment sits under a `FORK:`
comment. Nothing has been published, so `docs/legal/legal.json` is still edition 1; once
the texts are out, every change to them raises it, as the `release` skill says.

**`DADATA_TOKEN` now serves a second caller (#120).** The anonymous school directory spends at
most 4,000 of DaData's 10,000 daily requests. Without the key it answers `503` with
`X-Directory-Unavailable: disabled`, and a family still finds its region in the bundled
catalog — only the search by a school's name is gone.

**The keep-alive is only as alive as the cron (#120).** «Сетевой город» sessions are held
open from `GET /api/v1/cron/tick`, so they lapse if the external cron does not tick;
`.github/workflows/reminders.yml` is the fallback, not the clock, exactly as for the digests.

**#135 now has an answer in the code to each of its three questions, and closing it is the
owner's.** `docs/diaries.md` is the map:

- **Which platform** — «Сетевой город», built on the server in #140's first part: one route
  set, the largest group of regions, and a password still accepted outside the regions that
  allow Госуслуги only. The МЭШ family comes after it. Against a live server it has not been
  read.
- **How a family signs in** — with its password, typed into the app's own form and sent to
  the diary alone; the session, not the password, reaches our server. Госуслуги is not driven
  and no token is carried over from a browser, because a Госуслуги session is the person's
  whole state-services account. The three Госуслуги-only «Сетевой город» regions hand off to
  their own site.
- **Whether ТОР «Моя школа» may be used** — it is not read. The provider screen names it as
  the region's system and hands off to the official site. Nobody has read Госуслуги's terms,
  and nothing needs them read until somebody proposes reading ТОР.

Whichever platform comes next, the first step is one real session against it, as #121 is for
Петербург. Not one route in `docs/diaries/` has been seen answering.

**Install one built after #186 and long-press a tab on the home screen.** Since #181 the
long press picks the tab up in the same touch, and a tap anywhere closes the mode (#182).
#186 saw the gesture work on an emulator, but not by a hand — and the emulator was shared
with the owner at the time, so the drop was never watched undisturbed. The APK on #84's
merge cannot rearrange anything — the drag reported the order unchanged — so it is the wrong
build to judge the feature by. #84 and #85 together are the whole of a gesture and nothing in
a session here could see any of it: whether the wobble reads as «иконки на
iOS» or as a fault, whether a tab can actually be dragged where the finger means it to go,
whether the haptic lands at the moment the mode opens, and — the one that matters most —
whether the reader really does stay on the tab they were looking at rather than on the slot
it used to occupy. Then leave the app, come back, and check the order survived. A back press
should leave the arranging mode rather than the app.

**The widget wants resizing twice *and* the system font turned up, and that is the whole
check.** #82 makes every block inside it round itself to the padding of its size class, and
the twelve rungs differ by a factor of two, so that defect is invisible at one size and
obvious at another. #83 adds the second axis for the same reason: the ladder measures in dp
and its type sizes are in sp, so at «Настройки → Экран → Размер шрифта» turned to its
largest the old build chose a rung for about twice the content it could draw — the state
word came out «ПЕРЕ…» and the bottom rows ran off the edge. Drop the widget small, look at
the corners and at the seven day chips; drag it to four cells wide and tall and look again;
then turn the font up and repeat both. Nothing in the suite can do any of it, and the corner
half needs Android 31 or later to exist at all.

**Decide what the Preview environment is for, because today it is a red herring.** All
eleven of the project's variables on Vercel are scoped to **Production only**, so every
preview deployment — one per push to `dev`, which is one per pull request — dies while
importing `app.db` and answers `500` to every request. The build is green, Vercel comments
«Ready» on the pull request, and the link leads to a function that refused to start. That
refusal is `DeploymentNotConfigured` doing precisely its job: nothing is touched, no
connection is opened, no webhook is registered, and no GitHub check turns red. It cost an
export of the runtime logs to establish, which is why `docs/deploy.md` now says it in the
variables section. Two honest ways out, and **copying Production's values across is not one
of them** — that points every branch at the real database and hands a throwaway deployment
the real bot token, and Telegram gives its updates to whoever registered the webhook last.
Either give Preview its own set (a Neon branch, a second BotFather bot, its own secrets) or
turn Preview deployments off in the project's Git settings; nothing here is a web page, so
there is nothing for a preview to show. Only the owner can do either — this session can read
which keys exist per environment but must not create them.
**On 5 October 2026 this changed, and it is not known how.** The preview of #342 started and
answered `/api/v1/warmup`, so Preview now has the mandatory variables. The owner added
`DIARY_PROXY_URL` to Preview that day, but which `DATABASE_URL` and `BOT_TOKEN` Preview holds is
not known here. If they are Production's, every pull request's preview reads and writes the real
database. The code never registers the Telegram webhook itself — that is done by hand — so a
preview cannot take the bot's updates, but `RUN_BOT` must stay `false` there. Check in Vercel
which values Preview has, and decide this paragraph's question with that in view.

**The APK's own badges are new in #80 and they are worth one press.** The about page now
names the server's state, the repository, the ref and the commit the build came from. Two
of those cannot be checked from here at all: whether the server badge says the right thing
about the real deployment, and whether the commit chip opens the right diff on a phone.
Both are a few seconds on the page that already exists.

**The hour ruler is gone.** «Календарь → День» is the ribbon now, and a reader who preferred
reading the day by position — a gap as a height rather than as a row — has lost that. It was
a deliberate replacement rather than an addition, because two views of one day is how the
bot's two timetable editors nearly drifted apart; if it turns out to be missed, the ruler is
in the history at `f5a8172^`.

**The first press of two network paths should be the owner's.** Neither the translation
pull request from #64 nor the guide's fetch from #67 has ever run against GitHub, and both
are written to be pressed by a reader. Opening the documentation once on a real phone, with
and without a network, checks the second of them in about a minute — and the first press of
«Отправить как pull request» checks the first.

**Two more are decisions rather than actions**, both from the second audit, both
deliberately not taken by the session that found them because they trade one real cost
against another and the trade is the owner's to make.

**A. The widget's tick cadence.** Every rung above 2×1 draws its countdown with a
`Chronometer` that ticks in the launcher's own process and is always exact; the 2×1 alone
draws a frozen string, so the whole refresh cadence buys freshness for that one size.
`BeforeSchool` is entered at midnight and held until the first bell, which costs about
**34 device-waking alarms a school night** — against zero for the symmetric `AfterSchool`,
which is documented as costing nothing precisely because it has no countdown. Two ways out,
and they trade against each other:

  * cap the look-ahead (treat "more than an hour away" as nothing to count, wake on the
    bell): 34 alarms → 1, and the 2×1 reads «8 ч 30 мин» all night until the bell nears,
    which is arguably the honest answer at 3 a.m.;
  * round the printed figure per tier («~8 ч» above the hour, so a quarter-hour of drift
    really is inside the rounding — which the code comment already claims and the
    `%d ч %d мин` format does not do): keeps all 34 wake-ups, costs the 2×1 its minutes.

  The tier table was left untouched either way. What *was* fixed is the disagreement between
  the colour and the figure: they rounded opposite ways, so «6 мин» was drawn in the error
  colour under a rule documented as the last five minutes.

**B. Whether the diary credential should carry a bound, and which.** A Fernet `ttl` is the
obvious answer and is probably the wrong one: the clock would run from when the *upstream*
last rotated its cookie, not from when the family last read their diary, because
`services/diary` re-seals only when the token comes back different. On the calls where it
does not rotate, a ttl would refuse a credential the upstream would still have accepted —
and since `unseal` returns `None` for both a rotated key and an expired blob, `find_session`
marks the row dead and sends the person back to a sign-in form needing a fresh ticket from
the bot. That is a real, user-visible expiry bought against a narrow threat, since the blob
is worthless to anyone holding the dump but not `DIARY_SECRET`. If a bound is wanted, the
cheaper one that cannot misfire is an age check on `last_used_at` in `find_session`: our own
clock over our own facts. **Today there is no bound at all except the row's own lifecycle**,
and that is the thing to decide rather than the ttl.

Items 1, 1a and 1b of this list are done — the last of them, reading `/api/v1/warmup` and
sending the bot `/start`, on 26 September 2026 — and so is item 5, the correction mode's walk,
which #186 made on an emulator (#115). All four are in [docs/history.md](docs/history.md),
«Moved out of section 7», with four paragraphs this section no longer needs.

2. **Build the APK from `main` and install it on a phone.** See section 5 — it is the only
   way to check what nothing currently checks, and doubly so after three build-chain bumps.
   Three things have been added to this: connect the phone to two classes and walk between
   them, looking at the widget after a switch; if a phone with the previous version is to
   hand, upgrade it in place and make sure the class is still there and the introduction is
   not shown again; and sign into a real diary, press a lesson, correct the room and reset
   it. And a fourth: put a class into "invitation only", make sure a connected phone goes on
   working, take a personal code with the «📱 Подключить телефон» button and connect a second
   phone with it. #186 did the first and the second on an emulator (#116, #117), and the
   fourth on a class that was invite-only already, so the switch itself is still unflipped
   (section 5). On a phone those are a thumb's check rather than an open question; the diary
   is still nobody's (#121).
3. **Make sure `DADATA_TOKEN` really works** on Vercel (Production and Preview; it is the API
   key, not the secret one).
4. **Open «Настройки → О приложении → Лицензии» in the app** and look at the eighth row —
   Google Sans Flex. It is built and it compiles, but nobody has seen it with their eyes:
   what is of interest is how an eighth shade sits in a palette of six.

---

## 8. If you are starting new work

The order that paid off here:

1. Read `CLAUDE.md` in full, then `docs/architecture.md`. If you are an agent, look at
   whether `.claude/agents/` has a file for your area and `.claude/skills/` a procedure for
   the task: those record the traps that have already sprung in that area.
2. Read a file before editing it and **grep every caller** before changing a function. The
   audits in `docs/design.md` exist because a conclusion drawn from call sites turned out to
   be wrong.
3. Audits by reading subagents, split by area, work well and find real things: sixteen
   findings over four runs, several of them in code written an hour earlier in the same
   session. Give them a narrow area, the project's rules and a requirement for a concrete
   failure scenario, and make the edits yourself.
4. Do not commit without green gates on both halves.
5. Write "what is not covered" into the commit body and into the honest status in
   `README.md`. "Written, never run" is a legitimate status; a claim that something was
   verified when it was not is not.
6. Re-read your own diff before you push it, asking what would make it wrong rather than
   whether it looks right. Both defects in the pull request flow were found that way, in
   code written an hour earlier in the same session, and neither would have failed a test —
   there was no test that could have run.

### How to continue

`dev` remains the working branch, but after a merge it is restarted from `main`: a merged
pull request accepts no new commits. Which pull requests are merged and which one is open
is at the top of this file, not here — this paragraph is about the two commands under it.

```bash
git fetch origin
git checkout -B dev origin/main   # the same dev, a new starting point
```

The gates, both halves (`CLAUDE.md` requires running both if you touched both):

```bash
cd server  && ruff check app tests scripts migrations   # clean
cd server  && pytest -q -n auto                          # 2527 tests, ~12 min alone on Windows
cd server  && python -m mypy                             # clean, 218 modules
cd android && ./gradlew test                             # 1635 tests across the five modules
cd android && ./gradlew detekt                           # nothing beyond the five baselines
cd android && ./gradlew assembleDebug assembleRelease    # both assembles
```
