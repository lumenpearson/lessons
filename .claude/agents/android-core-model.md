---
name: android-core-model
description: The pure-JVM module :core:model — domain types, ScheduleEngine, AlertPlanner. Use for schedule resolution, alert planning or any domain type on the Android side. This is the module whose tests run without a device.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/core/model/`. It is pure JVM: it applies `kotlin.jvm`, not
`org.jetbrains.kotlin.android`, and it depends on no Android framework class. Keep it that
way — that is why `./gradlew :core:model:test` answers in seconds.

## The rule that produced two of the fourteen confirmed defects in the audit

**Time is naive local wall time, in the class's zone, not the device's.** The client decides
"today" through `Timetable.nowAtSchool()`. `LocalDateTime.now()` and `ZoneId.systemDefault()`
in this codebase are almost always a bug — grep for both before you finish.

## What mirrors the server, and what does not

**The server resolves the days, and the phone does not.** `server/app/schedule.py` turns the
weekly template, the overrides and the class's terms into concrete days, and the phone caches
the result. `ScheduleEngine` takes that cached, already-resolved `Timetable` and an instant
and answers a `DayState`: which lesson, break or event is on now and what comes next. Nothing
in this module resolves a template, so a rule about which days carry lessons belongs on the
server (`server-domain`), not here.

**`SchoolYear` is the mirror, and it has to stay one.** It is `school_year_bounds` from
`schedule.py` on the phone's side: the client asks for exactly that range and the server caps
exactly that range, so a disagreement is a window that silently comes back short.
`SchoolYearTest` pins the same cases the server's own test pins. A change to one is a change
to both.

`tests/test_schedule.py -k parity` is not that test: it selects the odd/even **week**-parity
tests (#316). The school year's end is the class's own terms, read by `off_reason_for` on the
server, falling back to 31 May only for a year with none.

## Gates

From `android/`: `./gradlew :core:model:test` while you iterate, `./gradlew test` before you
hand anything back. In a sandbox with no network, add `--offline`.
