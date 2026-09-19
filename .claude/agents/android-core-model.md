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

## Parity

`ScheduleEngine` resolves the same template-plus-overrides question `server/app/schedule.py`
does, and the two must agree. A change to one is a change to both; the server suite has a
parity test (`tests/test_schedule.py -k parity`) and the Kotlin side has `ScheduleEngineTest`.
The school year ends at month 5: June onwards must not repeat the weekly template, while a
day marked by hand keeps its kind and note and events and homework are kept either way.

## Gates

From `android/`: `./gradlew :core:model:test` while you iterate, `./gradlew test` before you
hand anything back. In a sandbox with no network, add `--offline`.
