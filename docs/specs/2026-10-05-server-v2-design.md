# Serving v2: the RPC layer, the REST transcoder, the host target and the streaming beta

Status: **drafted on 5 October 2026 while the owner was away, not yet approved.** Sub-project 3 of
[the programme](2026-10-03-one-contract-design.md). It serves the contract sub-project 2 wrote down
([its design](2026-10-04-contract-v2-design.md), merged as #297) from the code that serves v1
today. Every decision below is taken from the approved programme where the programme took it.
The rest are decisions this document makes, each with its reason. The questions only the owner
can answer are at the end, each with a recommendation. **Nothing that serves v2 in production
merges before the owner approves this document.**

**Delivers:**
- The generated `google/rpc` messages the error model needs.
- `server/app/rpc/`: one handler per method, over `services/`.
- `server/app/rest/`: the transcoder.
- The two mounts in the Vercel app.
- The generic gate: credential, role and client version.
- The one error table.
- The reads made side-effect-free.
- The per-provider diary registry.
- `server/app/host.py`, a `Dockerfile` and the streaming beta.
- The tests that hold all of it.
- The documents turned from «will» to «does».

**Does not deliver:**
- Any Kotlin (sub-project 5).
- The deletion of v1, which waits for the new APK on the family's phones.
- A production deployment of the host target (question 3).
- A change to v1's behaviour.

## What the code is today

A read-only survey of `server/app` on 5 October 2026 found what this design has to stand on. The
parts that shape the decisions:

- **Commits are scattered.** v1 has no commit on exit. Endpoints commit after the service call,
  many services commit inside themselves (`tasks`, `linking`, `calendar`, `diary`, the
  throttles), and `services/manage/` does not. The edit endpoints commit and *then* send their
  Telegram notice (`edit._tell`), so a notice never announces a change that rolled back.
- **The role checks are three dependencies** (`edit.editor_device`,
  `manage/_common._role_at_least`, `public._linked_id`). Each reads `linking.effective_role` on
  every request, and they phrase the same refusals three ways.
- **About thirty exception classes** from services and providers are translated into HTTP errors,
  each in the endpoint that
  catches it. About fifty more refusals are `HTTPException`s raised inline: unknown ids, date
  bounds, the timetable's refusals.
- **The helpers v2 needs live in a router module.** `caller_bucket`, the date bounds and the
  clock sit in `api/public.py`, and `edit.py`, `diary.py` and `directory.py` import them from
  there. A v2 handler doing the same would pull v1's router in.
- **Writes during reads:**
  - `/bundle` seeds terms and adopts subjects, then commits unconditionally;
  - `/me` mints a link code;
  - `/calendar` mints the feed secret;
  - `/manage/terms` seeds terms;
  - `/manage/subjects` adopts subjects;
  - every authenticated request may write `last_seen_at`;
  - diary reads re-seal a rotated upstream credential.
- **No client version is read anywhere**, on either side.

## Decisions

### 1. Three stages, each its own pull request

| Stage | Merges | Waits for |
| --- | --- | --- |
| **3a. The shells** | `rpc/` and `rest/` with the gate, the error table, the call scope, both mounts, the two guards and the test harness, and the methods a phone calls first: `GetScheduleWindow`, `GetMe`, `GetDiaryCapabilities`, `CreateDevice` | the owner's approval of this document |
| **3b. Every method** | the other seventy-two, service by service, with the reads made side-effect-free and the per-provider registry | 3a |
| **3c. The host and the beta** | `host.py`, the `Dockerfile`, native gRPC, `WatchClass` and its bus, and a CI job that starts the host | 3b |

v2 goes live on Vercel with 3a's merge, beside v1, which nothing in this sub-project changes. A
76-method pull request is not reviewable, and 3a carries every mechanism and a real path through
it. If the gate, the scope or the transcoder is wrong, it shows on four methods rather than
seventy-six.

### 2. Where things live

```
server/app/rpc/__init__.py   rpc_app(): the seventeen generated ASGI apps, each over its adapter
server/app/rpc/call.py       Call and invoke(): one call's gate, scope, handler, commit and effects
server/app/rpc/gate.py       the generic gate: (auth, min_role) from the descriptor, the client version
server/app/rpc/errors.py     the one table: an exception → code, reason, metadata, message
server/app/rpc/values.py     model ↔ message conversions every handler shares (dates, times, instants)
server/app/rpc/<service>.py  one module per proto service, named for it (schedule.py, me.py, …)
server/app/rest/__init__.py  rest_routes(): the transcoder, from the descriptors
server/app/rest/errors.py    Google's JSON error body, and the code → status table of docs/api.md
server/app/host.py           the second target's entry point (3c)
server/app/watch.py          the class-changed bus (3c): neutral, so services never import rpc/
```

`rpc/` and `rest/` may import `services/`, `models`, `schemas`, `contract/` and `api/deps.py`.
They may not import `app.bot` or any v1 router. The helpers they share with v1 move out of
`api/public.py` first:
- `caller_bucket` goes to `api/deps.py`, rewritten over a header mapping and a peer address, so
  it serves a Starlette request and a Connect call alike.
- The date bounds and the clock go to `services/`.

`tests/test_service_layering.py` extends its walk: no `app.services` module reaches `app.rpc`,
`app.rest` or `app.api`. A sibling test holds that `app.rpc` and `app.rest` reach neither
`app.bot` nor `app.api.public`, `app.api.edit`, `app.api.manage` or `app.api.diary`.

### 3. The gate is generic, and no handler checks a credential

Every method declares its credential and least role in the contract (`(lessons.v2.auth)`,
`(lessons.v2.min_role)`). `gate.py` reads both from the descriptors once, at import, into a table
keyed by service and method.

**Both transports call one function, `invoke`.** It takes the method, the request message, the
headers and the peer, and runs, in order: the gate, the call's scope, the handler, the commit and
the after-commit effects (decision 4).
- Over RPC, each generated service `Protocol` is implemented by an adapter that turns Connect's
  `RequestContext` into those arguments and calls `invoke`. A method with no handler yet keeps
  the `Protocol`'s own `UNIMPLEMENTED`.
- Over REST, the transcoder calls `invoke` directly.
- A handler is a plain `async` function of `(call, request)` that knows neither transport.

The 5 October spike showed `connectrpc` hands a handler the headers and the peer, so the adapter
loses nothing. One function on both paths is simpler than an interceptor on one and a call on the
other, and it cannot let the two drift. Before any handler runs, the gate does four things:
- It resolves the bearer the method's kind asks for, by the rules of `api/deps.py`:
  - the device token, and its class;
  - or the diary token, by `services.diary.find_session`.
- `DEVICE_LINKED` refuses an unlinked phone (`DEVICE_NOT_LINKED`).
- A `min_role` above viewer reads `linking.effective_role` and refuses below it (`ROLE_REQUIRED`
  with `metadata.role`). This happens on every call, as v1 does.
- It reads `X-Lessons-Client` (decision 9).

The handler then receives a `Call` that already holds the device, the class, the linked account
and its role, or the diary session. A handler cannot forget a check, because it never makes one.
One parametrised test over all 76 methods sends each without a credential, with the wrong kind of
credential and, for every role above viewer, as the role below it, and reads the refusal on both
paths.

### 4. One call, one scope, and effects after the commit

`Call` opens a dishka `REQUEST` scope from the app container per call, the way the bot's
`ContextMiddleware` does and not through `setup_dishka`:
1. The handler runs.
2. On success, the scope commits.
3. Only then do the call's after-commit effects run, in order: the Telegram notices v1's edit
   and request endpoints send, and on the host the bus (decision 13).
4. On a refusal, it rolls back and no effect runs.

Services that commit inside themselves today keep doing so; a second commit of nothing is
harmless. **A handler never commits**, and a test greps `rpc/` for `.commit(` to keep it that way.

### 5. The one error table

`rpc/errors.py` maps each of the service and provider exceptions the survey found to
a canonical code, an `ErrorReason`, its `metadata` and a message. Examples:
- `SubjectExists` → `ALREADY_EXISTS` / `RESOURCE_EXISTS`;
- `SubjectInUse` → `FAILED_PRECONDITION` / `RESOURCE_IN_USE`, with `metadata.lessons`;
- `SessionExpired` → `UNAUTHENTICATED` / `DIARY_REAUTH`;
- `AllowanceSpent` → `UNAVAILABLE` / `DIRECTORY_SPENT`, with a retry delay.

The code and metadata of each reason are the ones `errors.proto` gives it; the table follows the
file.

**`google.rpc.ErrorInfo` has to be generated first.** Today only `google/api` is generated into
`server/app/contract/google/`, and nothing anywhere provides `google/rpc/*`. A spike on 5 October
2026 had to hand-encode the `Any`. `buf.gen.yaml` gains a second input: googleapis'
`google/rpc/error_details.proto`, `status.proto` and `code.proto`, from the module `buf.lock`
already pins, generated beside `google/api`. Every detail then comes from a generated class:
`ErrorInfo`, `BadRequest`, `RetryInfo`. This is 3a's first commit, because every refusal depends
on it.

What the client sees, observed in that spike through `connectrpc` 0.12.1 mounted in FastAPI: a
Connect error is always a JSON body, even for a binary request, carrying
`{"code": "failed_precondition", "message": …, "details": [{"type": "google.rpc.ErrorInfo",
"value": <base64>}]}` under the protocol's own HTTP status. There is no `debug` rendering, so a
client decodes the detail's bytes itself.

A refusal v1 raised inline becomes a `Refusal(reason, message, **metadata)` raised by the handler:
- an unknown id is `NOT_FOUND` / `RESOURCE_NOT_FOUND`;
- a date out of bounds is `INVALID_ARGUMENT` / `VALIDATION_FAILED`, with a `google.rpc.BadRequest`
  naming the field;
- a throttle is `RESOURCE_EXHAUSTED` / `THROTTLED`, with a `google.rpc.RetryInfo`, and REST also
  sends `Retry-After`;
- a missing or unknown token is `UNAUTHENTICATED` / `DEVICE_TOKEN_INVALID` or
  `DIARY_TOKEN_INVALID`.

**The message is v1's own text**, English or Russian as v1 had it. The app acts on the reason
(api.md, «Errors») and shows its own strings, so inventing new copy here would be churn. Anything
the table does not know is `INTERNAL`, logged with its traceback, and answered with a fixed
sentence. The exception's text never reaches the client, because it can carry what was typed.

Three tests hold the table:
- Every row raises its exception through a real method and is read back on both paths.
- Every `ErrorReason` is produced by some row or `Refusal`, except those listed as reserved for a
  later method.
- A password-shaped string sent in every string field of every method appears in no error
  (the programme's «No error ever echoes a request field»).

### 6. The transcoder

`rest_routes()` builds one Starlette route per unary method from its `google.api.http` rule, at
import. Annotations say `/v2/…` and are served under `/api`.

**Requests:**
- Path variables, including dotted ones such as `{task.id}`, are bound into the request message.
- The body is mapped as the rule says: `"*"`, a named field, or none.
- What a `GET` or `DELETE` carries besides its path comes from the query string, with dotted
  names for nested fields and a repeated name for a repeated field.
- JSON is parsed as canonical proto3 JSON. Unknown fields are ignored, which is what Connect does
  (the spike), so the two paths agree on a newer client's request. A body that does not decode
  is `INVALID_ARGUMENT` / `REQUEST_UNDECODABLE`.

**Responses:**
- Every success is `200` with the `<Method>Response` as canonical JSON (question 5).
- A refusal is Google's JSON error body under the status of `docs/api.md`'s table.

**The ETag rule is generic, not a special case.** A request message with an `if_none_match` field
takes it from `If-None-Match`. A response with `not_modified` set answers `304` with its `etag`
as `ETag` and no body; any other response with an `etag` sends it as `ETag`. Only
`GetScheduleWindow` has those fields today, and a later method that adds them gets the behaviour
for free.

The transcoder is tested generically:
- every method has its route with its verb;
- every path variable binds;
- a `GET`'s query string reaches nested and repeated fields;
- the error body has Google's shape;
- the ETag rule works in both directions.

### 7. The two mounts and the two guards on Vercel

`app.main` adds the transcoder's routes and mounts `rpc_app()` at `/api/rpc`. v1's routers are
untouched, and no path overlaps.

Two guards stand in front of the library's two defects that the spike found:
- A request whose content type is `application/grpc` (with or without `+proto`) is refused before
  `connectrpc` sees it: `415`, with a body saying native gRPC is served by the host target.
- A Connect body that does not decode answers `invalid_argument` / `REQUEST_UNDECODABLE`, not
  `500 unknown`. The transcoder answers the same for a REST body.

Each guard has a test that sends the bad request.

On Vercel, `WatchClass` answers `UNIMPLEMENTED` / `FEATURE_UNSUPPORTED`. The target decides what
it serves, and there is no flag to claim otherwise.

### 8. The cold start grows, and its guard changes shape

`app.main` now imports `app.rpc`, `app.rest`, `app.contract`, `connectrpc` and the protobuf
runtime. The spike measured that at about 61 ms of cold import, which the programme accepted.
Two tests change:
- `test_contract.py`'s `test_the_api_cold_start_imports_no_generated_code` is turned round: the
  generated code is on the cold path now, on purpose.
- `test_cold_start.py` still refuses `aiogram` in a fresh interpreter's `sys.modules`, and gains a
  ceiling on the import's own time, measured once and given generous room so CI is not flaky.

### 9. The client version

`gate.py` reads `X-Lessons-Client: <versionCode>` on every call, REST and RPC alike. With
`MIN_CLIENT_VERSION` set, a present version below it is refused with `FAILED_PRECONDITION` /
`CLIENT_TOO_OLD` and `metadata.min_version`. A header that is not a positive integer is
`INVALID_ARGUMENT`.

`MIN_CLIENT_VERSION` is an optional setting: empty means zero. It is not added to the settings
`DeploymentNotConfigured` insists on (CLAUDE.md, «Do not add an optional setting to that list»).
**A missing header is not refused** (question 4).

### 10. Reads stop writing

Every `Get` and `List` is `NO_SIDE_EFFECTS` in the contract, and the v2 handlers keep that
promise. v1's endpoints keep their writes until v1 is deleted.

- **Terms are computed, not seeded, on a read.** When a year has no rows, the read answers the
  conventional set for the class's current scheme (`default_term_bounds`), and nothing is
  inserted. The first write that edits a term or the scheme persists the set inside its own
  transaction, as `ensure` does today.
  - This is the same answer v1 gives, because the conventional bounds are contiguous and
    `off_reason_for` falls back to the same span when a year has no rows.
  - It differs in one case only: a class that moves from grade 9 to 10 without ever having its
    dates edited gets half-years rather than frozen quarters, which is the scheme it is now in.
  - No data migration is needed.
- **Subjects are adopted where a timetable is written, not where it is read.**
  `services/structure.apply_timetable` and the `timetable_edit` mutations end with
  `subjects.sync_from_timetable`, so the bot's paste and editor and v2's timetable methods all
  adopt. A v2 read never does. Production's existing rows were adopted long ago, because every
  phone poll of `/bundle` adopted them.
- **The link code and the calendar secret** are minted only by `CreateLinkCode` and
  `CreateCalendarFeed`, as the contract already has it. `GetMe` and `GetCalendarFeed` read.
- **Kept on purpose:**
  - `last_seen_at`, telemetry no client observes, at most every fifteen minutes;
  - a diary credential the upstream rotated, because a read that dropped it would sign the family
    out;
  - the throttles' own rows.
  None of these changes a resource a client can read.

One test calls every `NO_SIDE_EFFECTS` method against a fresh class, including one with no terms
and an unadopted subject. It counts the rows of every domain table before and after, and fails on
any difference.

### 11. Throttles are shared with v1

`CreateDevice` uses `join_limiter`, `CreateDiarySession` the two diary limiters, and
`ListSchoolRegions` the directory limiter and DaData's anonymous allowance. Each uses the same
scope string and the same `caller_bucket`, so v1 and v2 draw on one budget: a caller cannot
double its attempts by alternating versions. A test alternates them and is refused on the attempt
the budget says.

### 12. The per-provider diary registry

`providers/diary/registry.py` becomes a table, as the programme's «Growing the diary» asks. Each
row has:
- the key;
- the module and class, still imported lazily;
- what a binding needs (nothing, or a region and a school);
- the sign-in methods;
- the `DiaryFeature`s the provider declares.

`binding()` validates per row, with no `if` per key. `GetDiaryCapabilities` is built from the
table. A diary method whose feature the session's provider does not declare answers
`UNIMPLEMENTED` / `FEATURE_UNSUPPORTED` before any upstream call. The features each provider
declares are read from what its `DiaryConnection` really implements, in 3b's plan, not from a
list written here.

### 13. The host and the streaming beta (3c)

- **What runs:** `python -m app.host` serves the same `app.main.app` under `pyvoy`, HTTP/2 with
  trailers. That means v1, v2 over REST and Connect, native gRPC, the webhook and the cron tick.
  `hypercorn` is the fallback the spike also proved.
- **Host-only dependencies:** `pyvoy` and anything else only the host needs are locked
  separately (`server/requirements-host.in` → `requirements-host.txt`), so Vercel's lock and cold
  start do not carry them.
- **Packaging:** the `Dockerfile` installs both locks.
- **Streaming:** with `LESSONS_STREAMING=true`, `WatchClass` streams «this class changed», a
  revision and never the data.
- **The bus** is `app/watch.py`, in process, as the programme decided.
  - It is fed by a SQLAlchemy session listener, not by every service. Before a flush, it collects
    the `class_id` of every new, changed or deleted row that has one, ignoring a `DeviceToken`
    whose only change is `last_seen_at`. After the commit, it publishes those classes.
  - That catches every write in the process, whichever shell made it: v1, v2 or the bot. A write
    no service remembered to announce cannot be missed.
  - The listener is attached only when streaming is on.
- **CI:** a job on a Linux runner starts the host against SQLite. A native gRPC client then makes
  a unary call, an `UNIMPLEMENTED` call and a `WatchClass` that sees a change the test makes
  itself.

**What the programme did not see:** a bus in one process only hears the writes that process
makes. On Vercel the bot's webhook lands on Vercel. A host serving only streaming beside it would
never hear an edit made in the bot. The host is therefore a **complete** deployment, chosen
instead of Vercel at build time rather than added beside it (question 2), and the beta stays
single-instance, as the programme says.

### 14. How it is tested

- **The harness** calls a method both ways in one test:
  - over REST, through the app on httpx's ASGI transport;
  - over Connect, as plain HTTP POSTs of canonical JSON to `/api/rpc/…`.
  Connect's unary protocol is that simple, so no client library is needed in-process. A few cases
  use binary (`application/proto`). It asserts that both paths return the same message, which is
  how «REST and RPC cannot disagree» is held rather than hoped.
- **Every method** has a test of its success and of each refusal its v1 endpoint had. v1's own
  tests are the checklist: a refusal tested there is tested here.
- **The generic tests:**
  - the gate (decision 3);
  - the error table and the no-echo sweep (decision 5);
  - the transcoder (decision 6);
  - the guards (decision 7);
  - the cold start (decision 8);
  - reads that write nothing (decision 10);
  - shared throttles (decision 11).
- **The host's** native gRPC and streaming tests run only in the CI job of decision 13, behind a
  pytest marker the ordinary run skips.

## Questions for the owner

Each has the recommendation this document is written to. A different answer changes the
decision it names.

1. **Ship v2 to production in three stages, starting with 3a?** v2's routes appear on the
   deployment with 3a's merge, beside v1, and no APK uses them until sub-project 5.
   *Recommended: yes.* The alternative, holding v2 off production until all 76 methods are
   done, makes one unreviewable pull request.
2. **Is the host a complete deployment or a sidecar?** A complete one serves everything,
   including the bot's webhook, and is chosen instead of Vercel. A sidecar would serve only
   native gRPC and streaming. *Recommended: complete*, because a sidecar's stream cannot hear the
   bot's edits (decision 13).
3. **Where does the host run?** It does not have to be decided for this sub-project, which
   delivers the `Dockerfile` and a CI run but no deployment. *Recommended: decide when a phone
   needs it.* A host inside Russia would also be the egress #235 waits for.
4. **Is a request with no `X-Lessons-Client` refused once a minimum is set?** *Recommended: no.*
   Only a present version below the minimum is refused. The minimum exists to retire the
   family's old APKs, which will all send it. A third-party client or a `curl` without it is not
   an old APK.
5. **Should every REST success be `200`, including creates and deletes?** That is Google's
   transcoding rule, and it drops v1's `201`/`204` and sends no `Location`. *Recommended: yes*,
   because the response message already carries what was created.
6. **Should error messages stay v1's own text, English or Russian as it was?** *Recommended:
   yes.* The app reads the reason, and rewriting the sentences is a separate piece of work if it
   is ever wanted.

## Risks

- **`connectrpc` is beta (0.12.1).** The 5 October spike sent a detail end to end through it
  (decision 5). It also confirmed:
  - Connect `GET` for a `NO_SIDE_EFFECTS` method answers `200`;
  - any other method sent as `GET` answers `405`;
  - the handler sees the headers and the peer.
  Two quirks: it does not insist on `Connect-Protocol-Version`, which nothing here needs; and its
  peer address is the proxy's on Vercel, which is why throttles key on `caller_bucket`.
- **The window's size on Vercel — measured, not a risk.** A response over 4.5 MB fails there.
  The 5 October spike built a dense synthetic year: six weekdays of eight lessons, a homework on
  every lesson-day, two events and one substitution a week, 280 days. Its sizes:

  | Format | Raw | gzip |
  | --- | --- | --- |
  | v1 `/bundle` JSON | 864,908 bytes | 12,910 |
  | v2 canonical JSON | 662,834 | 8,467 |
  | v2 binary | 506,209 | 6,903 |

  The v2 JSON is under a fifth of the limit. The demo class's own year is 215,765 bytes. A class
  with much longer homework texts would narrow that margin, so `GetScheduleWindow`'s test asserts
  the dense year stays under a ceiling of 2 MB.
- **Two notices for one change.** v1 sends the Telegram notice after its commit, and v2 must
  send it only after its own. Decision 4's after-commit list is the one place that holds it, and
  each notifying method's test asserts the notice is not sent on a refusal.
- **The stream's revision.** A revision is a counter in the process, so a phone reconnecting to a
  restarted host sees revision 1 again. The stream says «changed», never «unchanged since», and
  the phone always fetches the window on (re)connect, so a reset counter costs one fetch.
