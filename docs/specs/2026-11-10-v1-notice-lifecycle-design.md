# A committed v1 write survives notification failures (#377)

The owner delegated selecting and releasing the next issue without questions.
HANDOVER prioritises open bugs. #377 is chosen from that queue and promoted from
someday to now: it has a deterministic saved-write/500 failure and a bounded fix.
The next-labelled bugs need a real second-factor session (#343), installed-client
transport migration (#270/#268) or external cron migration (#269); #365 concerns
an external network path. This is an executor priority decision, not a new owner
answer. Existing milestone13 remains unchanged. Base: PR721 merge92d84023.

## Intent and boundary

Once a v1 edit or access decision commits, an ordinary bot-construction, delivery
or HTTP-session-close exception cannot turn its success into a server error.
Preserve every /api/v1 route, response field, role check, audit entry and notice.
No model/migration, new dependency, transport change or real Telegram send.
The unrelated timeout/deadline issue #371 is not claimed fixed.

Two thin notification seams already exist: api/edit.py._tell and
api/manage/requests.py._tell. Keep their _build_bot factories, lazy imports and
normal successful send/close behaviour. Guard construction and close separately,
as telegram_send already does for v2. Do not catch transaction/validation errors
around endpoints or swallow cancellation. Log lifecycle exception types rather
than token-bearing values. Capture the class ID before notification work can
expire ORM objects.

Build committed response DTOs before notification I/O. notify_subscribers may
commit blocked-recipient preferences; a failure can expire or invalidate the
request session. Homework, substitution, event and day replies must not read ORM
attributes afterwards. Approval/decline replies follow the same boundary. There
is still one service implementation for domain decisions; only transport-side
notification containment and response timing change.

## Proof

Use existing ASGI/SQLite test families with synthetic bots, not external services.
For an event and each approve/decline decision, exercise construction failure,
close failure, send refusal, absent bot and ordinary delivery. Assert original
success status, one saved write/audit, unchanged response fields, recipient and
one close where a bot exists. Existing auth/refusal tests remain controls.
For homework, substitution, event and day writes, expire the request's ORM state
inside a failing notice and require the committed response to remain readable.
Observe RED before production edits; then focused GREEN and whole gates.

Use the owned editable Python3.12 environment and actual Git Bash, full SQLite
pytest -q -n8, Ruff and mypy. Run native test/both assembles/detekt separately with
the existing JDK21/isolatedSDK/offline/two-worker/2GiB setup. Regenerate measured
facts only from complete successful reports, then update current declarations.
No new complete PostgreSQL/device/live-account verification is claimed.

HANDOVER rides in this PR: current377/previous635, archive661 body once and record
721's actual CI/production proof. Fresh independent committed-range review,
one TDD correction pass for major findings, exact-head CI/review, pinned merge,
main CI, three production aliases/warmups/runtime aggregates and owned checkout
synchronisation complete the batch. Retain all artifacts and foreign work.
