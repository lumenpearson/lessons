---
name: android-data
description: :core:data — Room, Retrofit, repositories, sync, notifications. Use for anything about how the phone gets, caches or is told about data. Knows why the module must not depend on :widget and how a 304 or a 401 is actually handled.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/core/data/`.

## Two structural facts

- **Room is the single source of truth; the network only fills it.** A screen reads Room. If
  you are tempted to render from a response, you are building a second truth.
- **`:core:data` must not depend on `:widget`.** The sync worker tells the widget it has new
  data by broadcasting `com.lumenpearson.lessons.action.DATA_SYNCED`, precisely so the
  dependency does not have to be circular. Adding the Gradle dependency "just for a class"
  is the change this rule exists to stop.

There is no Hilt. `Graph` is a small hand-written container a test can swap wholesale,
because the Glance widget and the WorkManager worker both need repositories from entry
points Hilt does not inject cleanly.

## Retrofit and caching traps that shipped

- **`HttpException` is thrown only for body-typed suspend methods.** A method typed
  `Response<T>` receives the error response verbatim and throws nothing — which is how a
  `401` on `/bundle` never reached `onTokenRejected`, and a revoked device kept a class and
  a year of somebody else's data on screen.
- **A `304` against an empty cache is not success.** The stored `If-None-Match` tag outlives
  the data — `removeSession`, `clearMemberships` and a destructive Room migration all leave
  it standing, and the signature `classId|start|window` does not move across a leave and a
  re-join of the same class. So the phone sent a tag the server matched while holding
  nothing, reported a successful sync over «Расписание ещё не загружено» for ever, and every
  pull to refresh repeated the 304. On a 304, ask the cache; if it is empty, refetch with
  `ifNoneMatch = null`.
- Device tokens: the device token and the diary session token are **two independent**
  bearer tokens on different endpoint families. Losing one does not invalidate the other.

## Gates

From `android/`: `./gradlew :core:data:test`, then `./gradlew test`. `--offline` in a
sandbox. `:core:data` ships its own `values/strings.xml`, so
`ResourceTranslationTest` covers it — an added string needs its `values-en/` twin.
