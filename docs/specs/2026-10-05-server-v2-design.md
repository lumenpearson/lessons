# Serving v2: the RPC layer, the REST transcoder, the host target and the streaming beta

Status: **approved by the owner on 5 October 2026**, every question answered with its
recommendation (#301). Drafted the same night while the owner was away. Sub-project 3 of
[the programme](2026-10-03-one-contract-design.md). It serves the contract sub-project 2 wrote down
([its design](2026-10-04-contract-v2-design.md), merged as #297) from the code that serves v1
today. Every decision below is taken from the approved programme, or from the merged contract,
where they took it; the rest are decisions this document makes, each with its reason. The
questions only the owner can answer are at the end, each with a recommendation, and every one
was answered with it.

It was revised the same night after an independent review that checked every claim against
the code and the contract. The review found:
- a bus that missed bulk writes;
- a 3a that depended on 3b;
- rules that live in v1 routers rather than in `services/`;
- two error examples and a status code that contradicted the merged contract;
- a v1 defect, filed as #302 and fixed the same night by #303.
This version is the corrected one.

**Delivers:**
- the generated `google/rpc` messages the error model needs;
- the v1 rules a v2 handler needs, moved out of v1's routers into `services/`;
- `server/app/rpc/`, one handler per method over `services/`;
- `server/app/rest/`, the transcoder;
- the two mounts in the Vercel app;
- the generic gate: client version, credential and role;
- the one error table;
- reads that write nothing;
- the per-provider diary registry;
- `server/app/host.py`, a `Dockerfile` and the streaming beta;
- the tests that hold all of it, and the documents turned from «will» to «does».

**Does not deliver:**
- any Kotlin (sub-project 5);
- the deletion of v1, which waits for the new APK on the family's phones;
- a production deployment of the host target (question 3);
- a change to v1's behaviour, beyond the moves, which keep v1's answers as they are. #302's fix
  is #303's, already merged.

## What the code is today

A read-only survey of `server/app` on 5 October 2026, and the review that followed it, found what
this design stands on.

- **Commits are scattered, and some are load-bearing.** v1 has no commit on exit, and
  `di.py` says why the container never commits.
  - Endpoints commit after the service call. `services/manage/` does not commit.
  - Other services commit inside themselves, on purpose:
    - the throttle (`JoinThrottle.admit` and `forgive`), so a wrong join code stays counted
      when the request fails;
    - the diary (`find_session`, `_expire`, `_remember_token`);
    - `last_seen_at` (`deps.touch_last_seen`);
    - `tasks`, `linking` and `calendar`.
  - The edit and request endpoints commit and *then* send their Telegram notice (`edit._tell`,
    which never fails the request), so a notice never announces a change that rolled back.
- **Many rules live in v1's routers, not in `services/`.** `api/public.py` holds:
  - the join flow: throttle admit and forgive, the invite-only refusal, the 300-device cap,
    burning a personal invite, and the audit line;
  - building the window and its ETag.

  `api/edit.py` holds the substitution rules. `api/diary.py` holds the diary's sign-in
  attempt (`_Attempt`) and the limiter instances. `api/directory.py` holds the directory
  limiter. A v2 handler can neither import a v1 router nor copy those rules without making a
  third implementation beside the bot's.
- **The role checks are three**: `edit.editor_device`, `manage/_common._role_at_least`, and
  `public._linked_id`, which only asks «linked?». All three refuse an unlinked phone with
  «device is not linked» **before** they ask the role. The app shows different screens for the
  two refusals.
- **The helpers v2 needs live in a router module.** `caller_bucket`, the date bounds and the
  clock sit in `api/public.py`.
- **Reads that write:**
  - `/bundle` seeds terms and adopts subjects, then commits unconditionally;
  - `/me` mints a link code;
  - `/calendar` mints the feed secret;
  - `/manage/terms` seeds terms;
  - `/manage/subjects` adopts subjects;
  - any authenticated request may write `last_seen_at`;
  - diary reads re-seal a rotated upstream credential.
- **A missing `DIARY_SECRET` expired every diary session it was asked about, for good** (#302):
  `unseal` answered `None`, and `find_session` expired the row and committed. #303 fixed v1:
  `services/diary.unusable` expires only what a configured key cannot open, and `current_diary`
  answers «disabled» before it looks at a token. The v2 gate makes the same check (decision 3).
- **No client version is read anywhere**, on either side.

## Decisions

### 1. Three stages, each its own pull request

| Stage | Merges | Waits for |
| --- | --- | --- |
| **3a. The shells** | see below | the owner's approval of this document (given 5 October 2026) |
| **3b. Every other method** | the other seventy-one unary methods, service by service. Each brings its v1 rules into `services/` first (decision 2). Also the per-provider registry table and the Telegram notices as effects | 3a |
| **3c. The host and the beta** | `host.py`, the `Dockerfile`, native gRPC, `WatchClass` and its bus, and a CI job that starts the host | 3b |

**3a merges:**
- `google/rpc`;
- `rpc/` and `rest/`, with the gate, the error table, `invoke`, the mounts and the guards;
- the test harness;
- the methods a phone calls first: `GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities` and
  `CreateDevice`;
- everything those four need:
  - the join flow, the window builder and the throttles moved into `services/`;
  - terms computed on a read;
  - the side-effect test;
  - capabilities served from today's registry.

v2 goes live on Vercel with 3a's merge, beside v1. A 76-method pull request is not reviewable,
and 3a carries every mechanism and the phone's main path through them. If the gate, the scope or
the transcoder is wrong, it shows on four methods rather than seventy-five.

### 2. Where things live, and the rules that move first

```
server/app/rpc/__init__.py   rpc_app(): the seventeen generated ASGI apps, each over its adapter
server/app/rpc/call.py       Call and invoke(): one call's gate, scope, handler, commit and effects
server/app/rpc/gate.py       the (auth, min_role) table from the descriptors; client version; bearers
server/app/rpc/errors.py     the one table: an exception → code, reason, metadata, message
server/app/rpc/values.py     model ↔ message conversions every handler shares
server/app/rpc/<service>.py  one module per proto service, named for it (schedule.py, me.py, …)
server/app/rest/__init__.py  rest_routes(): the transcoder, from the descriptors
server/app/rest/errors.py    Google's JSON error body, and the code → status table
server/app/host.py           the second target's entry point (3c)
server/app/watch.py          the class-changed bus (3c), neutral, so services never import rpc/
server/app/telegram_send.py  build_bot() and the one-shot send, out of app.bot (3b)
```

`rpc/` and `rest/` may import `services/`, `models`, `schemas`, `schedule`, `wording`,
`contract/`, `security`, `config`, `crypto`, `di`, `api/deps.py`, the diary registry
(`app.providers.diary.registry`), the NetSchool region list (`app.providers.netschool.regions`)
and the neutral `telegram_send`. They may not import `app.bot`, `app.api.public`,
`app.api.edit`, `app.api.manage` or `app.api.diary`. `tests/test_service_layering.py` extends
its walk both ways:
- no `app.services` module reaches `app.rpc`, `app.rest` or `app.api`;
- `app.rpc` and `app.rest` reach neither `app.bot` nor a v1 router.

The walk sees imports inside functions too, which is why `build_bot` has to move.
`app.bot.bot` re-exports it, so the bot is unchanged.

**A rule a v1 router holds moves into `services/` before its v2 handler is written**, and v1
calls the moved code. Each move keeps v1's answers byte for byte, and v1's own tests are the
proof. For 3a:
- `services/join.py`: the join flow, which v1's `/join` then calls;
- `services/window.py`: the tag rules and v2's school-year window.
  - v1 and v2 share the rules and the role check (`linking.Access`), not one builder. v1's
    `/bundle` resolves its days, looks ahead, seeds terms, adopts subjects and commits, in that
    order, and a shared builder would move those steps.
  - This was found when 3a's plan was written.
- the limiter instances, `MAX_DEVICES_PER_CLASS` and the diary's `_Attempt`, moved to
  `security.py` and re-exported where tests import them, so v1 and v2 share **one instance**
  each;
- `caller_bucket` to `api/deps.py`, rewritten over a header mapping and a peer address:
  - it reads every `X-Forwarded-For` line, as v1 does with `getall`;
  - the adapter strips the port from the peer before `caller_bucket` sees it. `connectrpc`
    reports the peer as `host:port`, so without this every connection off Vercel would get a
    bucket of its own.
  - The strip is done where that format is known, not on an arbitrary string, because
    `::1:4321` is itself a valid IPv6 address;
- the date bounds and the clock to `services/`. The tests that patch `public._now` follow it.

3b moves the substitution rules and the rest the same way, one service at a time.

### 3. Both transports call one `invoke`, and the gate is generic

**`invoke`.** It takes the method, the request message, the headers and the peer, and runs, in
order:
1. the gate;
2. the call's scope;
3. the handler;
4. the commit;
5. the after-commit effects (decision 4).

How each transport reaches it:
- Over RPC, each generated service `Protocol` is implemented by an adapter that turns Connect's
  `RequestContext` into those arguments and calls `invoke`. A method with no handler yet keeps
  the `Protocol`'s own `UNIMPLEMENTED`.
- Over REST, the transcoder calls `invoke` directly.
- A handler is a plain `async` function of `(call, request)` that knows neither transport.

One function on both paths cannot let the two drift. The 5 October spike showed that `connectrpc`
hands an adapter the headers and the peer.

**The gate.** Every method declares its credential and least role in the contract
(`(lessons.v2.auth)` and `(lessons.v2.min_role)`). `gate.py` reads both from the descriptors once,
at import. Before any handler runs, it checks, in this order:
1. **The client version** (decision 9) comes first, so an old APK with a dead token is told to
   update rather than to sign in again.
2. **A diary method** checks that the diary is enabled before it resolves a token. If it is not,
   the answer is `UNAVAILABLE` / `DIARY_DISABLED`, and no session row is touched (#302).
3. **The bearer** is resolved by `api/deps.py`'s rules for the method's kind:
   - a device token, its class, and its `last_seen_at`;
   - a diary token, by `services.diary.find_session`.
   An unknown or missing one is `UNAUTHENTICATED` / `DEVICE_TOKEN_INVALID` or
   `DIARY_TOKEN_INVALID`.
4. **A linked account** is required for `DEVICE_LINKED`, and for any `min_role` above viewer. An
   unlinked phone is refused with `DEVICE_NOT_LINKED` **before** its role is read, as v1 does.
5. **The role** is read with `linking.effective_role` on every call. A role below `min_role` is
   `PERMISSION_DENIED` / `ROLE_REQUIRED`, with the role the proto's metadata names.

The handler receives a `Call` that already holds the device, the class, the linked account and
its role, or the diary session. A handler cannot forget a check, because it never makes one.

The gate is tested two ways:
- its table is read for all 76 methods and compared with the contract;
- its behaviour runs over every method a stage implements: no credential, the wrong kind, an
  unlinked phone, and the role below the least one, on both paths.

### 4. One call, one scope, and effects after the commit

`Call` opens a dishka `REQUEST` scope from the app container per call, the way the bot's
`ContextMiddleware` does and not through `setup_dishka`:
1. The handler runs.
2. On success, the scope commits.
3. Then the effects run, in order, with the call's session still open. `notify_subscribers`
   reads from the session, and commits when it switches off a recipient who blocked the bot.
4. An effect never fails the call. It is logged and dropped, as `_tell` does.
5. On a refusal, the scope rolls back and no effect runs.

**Not everything rolls back, on purpose.** The writes services commit inside themselves stay
committed when a call is refused:
- a wrong join code stays counted;
- a dead diary credential stays expired;
- `last_seen_at` stays touched.

These are named in `call.py`, and each has a test over v2: for example, «a wrong code over v2 is
still counted». **A handler never commits.** A test greps `rpc/` for `.commit(` outside
`call.py`.

The Telegram notices that v1's edit and request endpoints send become effects in 3b. 3a has none.
The stream's bus is not an effect: it is fed by the session listener (decision 13), which also
hears v1 and the bot.

### 5. The one error table

`rpc/errors.py` maps each service and provider exception the survey found to a canonical code, an
`ErrorReason`, the metadata **`errors.proto` names for that reason**, and a message. Examples,
checked against the file:
- `SubjectExists` → `ALREADY_EXISTS` / `RESOURCE_EXISTS`;
- `SubjectInUse` → `FAILED_PRECONDITION` / `RESOURCE_IN_USE`, with `resource`, `used_by` and
  `count`;
- `SessionExpired` → `UNAUTHENTICATED` / `DIARY_REAUTH`;
- `AllowanceSpent` → `RESOURCE_EXHAUSTED` / `DIRECTORY_SPENT`, with `retry_after_seconds`.

A refusal v1 raised inline becomes a `Refusal(reason, message, **metadata)` raised by the handler:
- an unknown id is `NOT_FOUND` / `RESOURCE_NOT_FOUND`, with `resource`;
- a date out of bounds is `INVALID_ARGUMENT` / `VALIDATION_FAILED`, with a
  `google.rpc.BadRequest` naming the field;
- a throttle is `RESOURCE_EXHAUSTED` / `THROTTLED`, with `retry_after_seconds` in the metadata
  as the proto says, and a `google.rpc.RetryInfo` beside it. REST also sends `Retry-After`.

**One status changes from v1 because the contract says so.** `errors.proto` files
`DEVICE_LIMIT_REACHED` (a class at its 300 phones) under `RESOURCE_EXHAUSTED`. REST therefore
answers `429` where v1's `/join` answered `409`, and a generic HTTP client may retry a 429. The
app acts on the reason and will not, but whoever writes a third-party client should read it
there. Changing the code would be a contract change, and this design does not make one.

**The message is the shell's own words**, as the programme says. Where v1 had a sentence, it is
v1's sentence, kept once in `app/wording.py` where both versions say it: a service refuses with
facts, never a sentence. The app acts on the reason.

Anything the table does not know is `INTERNAL`, logged with its traceback, and answered with a
fixed sentence. `docs/api.md`'s code table gains `INTERNAL` → 500. The exception's text never
reaches the client, because it can carry what was typed.

**`google.rpc` has to be generated first, and it is 3a's first commit.**
- Today only `google/api` is generated, and nothing provides `google/rpc/*`; the 5 October spike
  had to hand-encode the `Any`.
- `buf.gen.yaml` gains a second input: googleapis' `google/rpc/error_details.proto`,
  `status.proto` and `code.proto`. A module reference under `inputs:` is not resolved through
  `buf.lock`, so it pins the same commit explicitly, and `test_contract.py` holds the two level.
- On REST, the details are written as JSON through protobuf-py's type registry. That is checked
  in 3a's first test, because the spike checked only Connect's base64 form.

What a Connect client sees was observed in the spike, through `connectrpc` 0.12.1 mounted in
FastAPI:
- an error is always a JSON body, even for a binary request;
- it carries `{"code": "failed_precondition", "message": …, "details": [{"type":
  "google.rpc.ErrorInfo", "value": <base64>}]}` under the protocol's own HTTP status;
- with a hand-encoded `Any` there is no `debug` rendering, but with the generated classes there
  is. `connectrpc` keeps the message a detail was built from and writes it as `debug` JSON
  beside `value`, which 3a's plan observed. A client should still decode `value`, because
  `debug` is the library's courtesy, not the protocol's promise.

Three tests hold the table:
- every row raises its exception through a real method and is read back on both paths. Each
  row names the test that does so, or the later stage that will, and a row without one fails;
- every `ErrorReason` is produced by some row or `Refusal`, except those listed as belonging to a
  later stage;
- a password-shaped string sent in every string field of every implemented method appears in no
  error.

### 6. The transcoder

`rest_routes()` builds one Starlette route per unary method from its `google.api.http` rule, at
import. Annotations say `/v2/…` and are served under `/api`.

**Binding a request:**
- **Path variables**, dotted ones such as `{task.id}` included, are bound into the request.
  Starlette cannot name a parameter `task.id`, so each is renamed internally.
- **The body** is mapped as the rule says: `"*"`, a named field, or none.
- **Every field the path and the body do not bind comes from the query string, whatever the
  verb.** Every `Update*` takes its `update_mask`, and `UpdateDay` its `allow_missing`, there
  under `google.api.http`.
- Dotted names reach nested fields, and a repeated name a repeated field.
- A well-known type in the query (`FieldMask`, `Timestamp`) is parsed from its JSON string form.

**Parsing and limits:**
- JSON is canonical proto3 JSON. Unknown fields are ignored, which is what Connect does, so the
  two paths agree on a newer client's request.
- A body that does not decode is `INVALID_ARGUMENT` / `REQUEST_UNDECODABLE`.
- A body over 4 MB is refused, Connect's own limit.

**Responses:**
- **Statuses follow the merged contract.** The eight methods whose proto comment says «REST
  answers 201» answer `201`:
  - `CreateBellSchedule`, `CreateDevice`, `CreateDiarySession` and `CreateEvent`;
  - `CreateHomework`, `CreateTask`, `CreateSubject` and `CreateSubstitution`.

  The other three `Create*` methods (`CreateLinkCode`, `CreateCalendarFeed`,
  `CreateHomeworkTick`) promise nothing of the kind and answer `200`, like every other success,
  with the `<Method>Response` as canonical JSON. The set lives in a table in `rest/`, because
  comments do not reach the descriptors at runtime. A test holds it level with the proto files'
  comments.
- A refusal is Google's JSON error body under the status of `docs/api.md`'s table.
- No CORS headers: v2 answers apps, not browsers (question 5).
- Diary reads carry `Cache-Control: private, no-store`.

**The ETag rule is generic.**
- A request message with an `if_none_match` field takes it from the `If-None-Match` header. The
  header wins over a query parameter of the same name.
- A list of tags, `*` and the `W/` prefix match exactly as v1's `_matches` does.
- A response with `not_modified` set answers `304`, with its `etag` as `ETag` and no body. Any
  other response with an `etag` sends it as `ETag`.
- The tag is a strong validator, quoted: a SHA-256 of the response's canonical JSON, taken
  **before** `generated_at` is set. Clearing the field on a copy is not an option, because
  protobuf-py's copy is shallow and clearing a nested field on it cleared the original, which
  3a's plan observed.

What the transcoder's tests cover:
- every unary method has its route with its verb;
- every path variable binds, the dotted ones included;
- a `PATCH`'s `update_mask` comes from its query string;
- a `GET`'s query reaches nested and repeated fields;
- the error body has Google's shape;
- the ETag rule works in both directions.

### 7. The mounts, the guards, and a v2 that cannot take v1 down

`app.main` adds the transcoder's routes and mounts `rpc_app()` at `/api/rpc`. v1's routers are
untouched, and no path overlaps.

**3a is the first time production imports `connectrpc`, the generated code and their native
wheels** (`protobuf-py-ext`, `pyqwest`). They have been imported on Linux CI and on Windows, but
never on Vercel.
- **v2 is mounted so that a failed import cannot take v1 down.** The import is caught and logged,
  `/api/v2` and `/api/rpc` answer `503`, and v1 and the webhook go on.
- After 3a's merge the session reads production's `/api/v1/warmup`, one REST call and one Connect
  call, in JSON and in binary.

**The two guards**, in front of the library's two defects that the spike found:
- **Native gRPC over HTTP/1.1.** A request whose content type is `application/grpc` or
  `application/grpc+…` (never `application/grpc-web…`) is refused before `connectrpc` sees it.
  The answer is `415`, with a body saying native gRPC is served by the host target. On the host,
  over HTTP/2, it passes. Only a request the server marks HTTP/2 or HTTP/3 passes: ASGI reads a
  scope that names no version as HTTP/1.1, and so does the guard.
- **An undecodable Connect body** answers `invalid_argument` / `REQUEST_UNDECODABLE`, not `500
  unknown`.

Each guard has a test that sends the bad request.

**What the target decides.** The process knows which target it is from a deployment marker: the
`VERCEL` variable the platform sets, or `LESSONS_TARGET=host` on the host. Several things read it:
- `WatchClass` answers `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED` on Vercel;
- the settings refusal applies on both targets;
- the API docs stay off on both.

There is no flag that could claim gRPC on Vercel.

### 8. The cold start grows, and its guard changes shape

`app.main` now imports `app.rpc`, `app.rest`, `app.contract`, `connectrpc` and the protobuf
runtime. The spike measured about 61 ms of cold import for one service, which the programme
accepted. Writing 3a's plan measured more: the seventeen generated service modules alone took
117–180 ms on this machine. That is still a fraction of a cold start that already pays for
FastAPI and SQLAlchemy, and the programme's decision stands, but the number is the larger one.
Two tests change:
- `test_contract.py`'s `test_the_api_cold_start_imports_no_generated_code` is turned round: the
  generated code is on the cold path now, on purpose.
- `test_cold_start.py` still refuses `aiogram` in a fresh interpreter's `sys.modules`.

A ceiling on the import's time is not added, because no fixed number holds across CI's runners
and this machine without being either flaky or meaningless.

### 9. The client version

`gate.py` reads `X-Lessons-Client: <versionCode>` first on every call, REST and RPC alike.

`MIN_CLIENT_VERSION` is an optional setting: empty means zero. It is not added to the settings
`DeploymentNotConfigured` insists on (CLAUDE.md, «Do not add an optional setting to that list»).
Like every setting, `docker-compose.yml` hands it to the server and `docs/deploy.md` names it.
With a minimum set:
- **a present version below it** is `FAILED_PRECONDITION` / `CLIENT_TOO_OLD`, with
  `min_version`;
- **a missing header is not refused** (question 4);
- **a header that is not a whole number from 1 to 2,100,000,000** is `INVALID_ARGUMENT` /
  `VALIDATION_FAILED`. That ceiling is the build's own: `android/app/build.gradle.kts` refuses a
  versionCode above it, because Google Play does.

With no minimum, a malformed header is ignored, like a missing one.

Sub-project 5 must send the header from the APK's first v2 build. An APK that never sent it can
never be retired by it.

### 10. Reads stop writing

Every `Get` and `List` is `NO_SIDE_EFFECTS` in the contract, and the v2 handlers keep that
promise. v1's endpoints keep their writes until v1 is deleted.

- **Terms are computed, not seeded, on a read.**
  - When a year has no rows, the read answers the conventional set for the class's current
    scheme (`default_term_bounds`). Those are plain values, never `Term` objects attached to the
    session, so an autoflush cannot persist them.
  - The first write that edits a term or the scheme persists the set inside its own
    transaction, as `ensure` does today.
  - The review checked this preserves behaviour. `default_term_bounds` is contiguous from the
    school year's start to 31 May, which is exactly the span `off_reason_for` falls back to. All
    three readers of term rows fall back the same way: the resolver (the bundle, the calendar
    feed, the digests), `timetable_edit.why_no_lesson_can_be_drawn`, and the bot's «🗓 Четверти»,
    which seeds for itself.
  - It differs in one case only: a class that moves from grade 9 to 10 without ever having its
    dates edited gets half-years rather than frozen quarters, which is the scheme it is now in.
- **Subjects: v2's reads simply do not adopt.** Every place a timetable is written already links
  each row through `subjects.canonical`: `structure.apply_timetable` and the `timetable_edit`
  mutations. What v2 drops is v1's repair of rows written before the link existed.
  - The repair also runs in the bot's editor and in «📚 Предметы».
  - Nothing is left for it to repair. A read-only count on production on 5 October 2026 found 35
    timetable rows in one class, **none** without a `subject_id`, so dropping the repair from
    v2's reads loses nothing.
- **The link code and the calendar secret** are minted only by `CreateLinkCode` and
  `CreateCalendarFeed`, as the contract already has it. `GetMe` and `GetCalendarFeed` read.
- **Kept on purpose:**
  - `last_seen_at`, telemetry no client observes, at most every fifteen minutes;
  - a diary credential the upstream rotated, and `last_used_at`;
  - the throttles' own rows and the DaData counter.

  None of these changes a resource a client can read.

**The test counts statements, not rows.** An engine listener records every `INSERT`, `UPDATE` and
`DELETE` while each implemented `NO_SIDE_EFFECTS` method runs against a fresh class, including one
with no terms. It fails on any statement outside the allowlist above. Row counts would miss
exactly the writes this decision is about: the link code, the secret and the relinking are all
updates.

### 11. Throttles are shared with v1

The limiters live in `security.py` (decision 2), one instance each:
- `CreateDevice` uses `join_limiter` and the device cap;
- `CreateDiarySession` uses the two diary limiters through the moved `_Attempt`;
- `ListSchoolRegions` uses the directory limiter and DaData's anonymous allowance.

`caller_bucket` keys all of them, so v1 and v2 draw on one budget: a caller cannot double its
attempts by alternating versions. Two tests hold it:
- a test alternates v1 and v2 and is refused on the attempt the budget says;
- a test sends two connections from one peer, on different ports, over Connect, and they land in
  one bucket.

`test_every_throttle_on_the_attempts_table_uses_one_window` still holds.

### 12. The per-provider diary registry (3b)

`providers/diary/registry.py` becomes a table, as the programme's «Growing the diary» asks. Each
row has:
- the key;
- the module and class, still imported lazily;
- what a binding needs (nothing, or a region and a school);
- the sign-in methods;
- the `DiaryFeature`s the provider declares;
- how its corrections are scoped (`services/diary_corrections.child_scope` today).

`binding()` validates per row, with no `if` per key. A diary method whose feature the session's
provider does not declare answers `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED` before any upstream
call. The features each provider declares are read from what its `DiaryConnection` really
implements, in 3b's plan.

In 3a, `GetDiaryCapabilities` is served from today's registry and the NetSchool allow-list, the
same answer v1's `/diary/capabilities` gives in v2's shape. Its `sign_in_methods` and `features`
stay empty until 3b fills them from the table. **Sub-project 5 therefore must not move the diary
before 3b**: a client that hides the screens of an undeclared feature would hide the whole diary.
Its design (#307) moves only the schedule window and the join against 3a, and everything else
after 3b.

### 13. The host and the streaming beta (3c)

- **What runs.** `python -m app.host` serves the same app under `pyvoy`: HTTP/2, trailers, gzip.
  That means v1, v2 over REST and Connect, native gRPC, the webhook and the cron tick.
  `hypercorn` is the fallback the spike also proved.
- **Who sets the marker.** `LESSONS_TARGET=host` is the deployment's own statement about itself,
  as `VERCEL` is the platform's, so the `Dockerfile` sets it and `app.host` never does. The CI
  job below and a local run (the build console's host target) start `python -m app.host`
  without it, against SQLite, exactly as a local `uvicorn` runs today. With the marker, decision
  7's settings refusal would reject the SQLite default, and the job could not start at all.
- **Dependencies and packaging.** Host-only dependencies are locked separately:
  `server/requirements-host.in` is compiled with `-c requirements.txt` into
  `requirements-host.txt`. That way Vercel's lock and cold start do not carry them, and the two
  locks cannot disagree. The `Dockerfile` installs both.
- **`WatchClass`**, with `LESSONS_STREAMING=true`, streams «this class changed»: a revision,
  never the data. The revision carries a boot identifier, so a restarted host is not mistaken for
  an unchanged one.
- **The stream holds no database session.** The gate runs in a short scope at the start. The
  token is re-checked on every event and on a heartbeat, and a revoked device or a deleted class
  ends the stream with `UNAUTHENTICATED`. One scope held per watcher would hold a pool connection
  per watcher, and the pool is 5 + 10.
- **The bus** is `app/watch.py`, in process, as the programme decided. It is fed from the session,
  so it hears every shell's writes in the process: v1, v2 and the bot.
  - **An allowlist of the tables that change the window.** Timetable entries, substitutions,
    events, homework, day overrides, terms, subjects, bell schedules and their periods, and the
    class itself. A pupil's personal task, a diary session, a reminder setting or an audit line
    wakes nobody.
  - **ORM writes** are collected before each flush by their class: `class_id`, a bell period
    through its schedule, the class row through its `id`.
  - **Bulk statements** (`sa_update` and `sa_delete`) never pass through the unit of work. Each
    of the few that touch a window table calls `watch.touch(session, class_id)`; all of them
    already have the class at hand. A test finds every `sa_update(`/`sa_delete(` on a window
    table under `services/` and fails if one does not touch.
  - The collected set is published after the commit and cleared on a rollback. The listener is
    attached only when streaming is on.
- **CI.** A job on a Linux runner starts the host against SQLite. A native gRPC client then makes
  a unary call, an `UNIMPLEMENTED` call, and a `WatchClass` that sees a change the test makes
  itself through each kind of write: an ORM write, a bulk write, and a bell change. If the build
  console ([#308](2026-10-05-build-console-design.md)) has merged by then, its test walks every
  CI job, so 3c's pull request also gives this job its row in the console's task table.

**What the programme did not see.** A bus in one process hears only the writes that process
makes. On Vercel, the bot's webhook lands on Vercel, so a host serving only streaming beside it
would never hear an edit made in the bot. The host is therefore a **complete** deployment, chosen
instead of Vercel at build time rather than added beside it (question 2). The beta stays
single-instance, as the programme says.

### 14. How it is tested

- **The harness** calls a method both ways in one test:
  - over REST, through the app on httpx's ASGI transport;
  - over Connect, as plain HTTP POSTs of canonical JSON to `/api/rpc/…`. Connect's unary
    protocol is that simple, so no client library is needed in-process. A few cases use binary.

  It asserts that both return the same message, which is how «REST and RPC cannot disagree» is
  held rather than hoped.
- **Every method** has a test of its success and of each refusal its v1 endpoint had. v1's own
  tests are the checklist.
- **The generic tests:**
  - the gate (decision 3);
  - the error table and the no-echo sweep (decision 5);
  - the transcoder (decision 6);
  - the guards and the fail-safe mount (decision 7);
  - the cold start (decision 8);
  - the statement count (decision 10);
  - shared throttles and one bucket per peer (decision 11);
  - the bulk-touch rule (decision 13).
- **The host's** native gRPC and streaming tests run only in the CI job of decision 13, behind a
  pytest marker the ordinary run skips.

### 15. What sub-project 5 asks of this one

The app's design ([#307](2026-10-05-android-transports-design.md)) was written the same night,
and leans on this sub-project in three places. None of them changes a decision above.
- **Golden files.** Its stage 5a adds, to `server/tests`, the writer of the golden JSON files
  (`server/tests/golden/v2/`) and the table of each method's credential. They are built on this
  sub-project's test harness (decision 14), so the harness must be able to return each call's
  request and response as canonical JSON.
- **`google/rpc` on the phone too.** 3a's `google/rpc` input in `buf.gen.yaml` (decision 5) is
  run through the Android plugins as well, when 5a adds them.
- **A device's last client version, if the owner wants it.** Question 3 of the app's design
  proposes that the gate record the last `X-Lessons-Client` each device sent, so that
  «📱 Устройства» can show which phones still run an old APK before v1 is deleted. That is an
  additive column on `device_tokens`, a migration of its own, and 3b would carry it. It is built
  only if the owner says yes there.
  - **It does not break «reads stop writing» (decision 10).** The gate runs on every read, the
    sync's `GetScheduleWindow` included, so the version is written in the same statement as
    `last_seen_at` and on the same fifteen-minute clock, never on its own. The statement-count
    test's allowlist then grows by one column of an update it already allows, and by nothing
    else. A version that changed inside the fifteen minutes waits for the next touch, which is
    soon enough for a screen that asks «which phones still run an old APK».

## Questions for the owner

Each has the recommendation this document is written to; a different answer changes the decision
it names. The programme already settled where v2 ships («beside v1; a merge to `main` deploys
itself») and whose words the errors carry («the shell's own»), so neither is asked again.

1. **Should v2 ship in three stages, 3a first?** v2's first four methods appear on the
   production deployment with 3a's merge, and the rest follow in 3b. No APK uses them until
   sub-project 5. *Recommended: yes.* The alternative, one pull request of 76 methods, cannot be
   reviewed.
2. **Is the host a complete deployment or a sidecar?** *Recommended: complete.* A sidecar's
   stream cannot hear the bot's edits (decision 13). A complete host serves everything and is
   chosen instead of Vercel. It must carry:
   - the settings refusal, read from its own deployment marker;
   - the webhook, pointed at it;
   - the external cron, pointed at it;
   - a single instance.
3. **Where does the host run?** It does not have to be decided for this sub-project, which
   delivers the `Dockerfile` and a CI run but no deployment. *Recommended: decide when a phone
   needs it.* A host inside Russia would also be the egress #235 waits for.
4. **Is a request with no `X-Lessons-Client` refused once a minimum is set?** *Recommended: no.*
   Only a present version below the minimum is refused. The minimum exists to retire the
   family's old APKs, which will all send it. A third-party client or a `curl` without it is not
   an old APK.
5. **Should v2 answer browsers?** You named web and third-party clients as one reason for the
   contract. *Recommended: not now.* v2 sends no CORS headers until a web client exists, and
   then the origins it names are added. This keeps a credential-bearing API closed to pages on
   other sites by default.

## Risks

- **`connectrpc` is beta (0.12.1).** The 5 October spike sent a detail end to end through it
  (decision 5). It also confirmed:
  - Connect `GET` for a `NO_SIDE_EFFECTS` method answers `200`;
  - any other method sent as `GET` answers `405`;
  - the handler sees the headers and the peer.

  Two quirks: it does not insist on `Connect-Protocol-Version`, which nothing here needs; and
  its peer is the proxy's on Vercel and carries a port everywhere, which is why throttles key
  on `caller_bucket` (decision 2).
- **The window's size on Vercel — measured, not a risk.** A response over 4.5 MB fails there.
  The spike built a dense synthetic year: six weekdays of eight lessons, a homework on every
  lesson-day, two events and one substitution a week, 280 days.

  | Format | Raw | gzip |
  | --- | --- | --- |
  | v1 `/bundle` JSON | 864,908 bytes | 12,910 |
  | v2 canonical JSON | 662,834 | 8,467 |
  | v2 binary | 506,209 | 6,903 |

  The v2 JSON is under a fifth of the limit. The demo class's own year is 215,765 bytes. Much
  longer homework texts would narrow that margin, so `GetScheduleWindow`'s test asserts the dense
  year stays under a 2 MB ceiling.
- **Notices and the commit, in order.** v1 sends a Telegram notice after its commit, and v2 must
  send it only after its own. Decision 4's effects are the one place that holds this, and each
  notifying method's test asserts no notice is sent on a refusal.
- **The first production import (decision 7).** A wheel that does not load on Vercel would take
  the whole function down. The fail-safe mount and the post-merge smoke check are there for
  that.
