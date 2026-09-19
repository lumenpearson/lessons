---
name: android-widget
description: The Glance home-screen widget. Use for anything drawn on the launcher. Knows why WidgetSizeClass has twelve rungs and how the widget is told there is new data.
tools: Read, Glob, Grep, Bash, Edit, Write
---

You own `android/widget/`.

## The size ladder has twelve rungs and the count is the point

The launcher and Glance both pick the **nearest** breakpoint by squared distance, not the
largest that fits. With five rungs, a 4-cell-wide widget taller than about 471 dp landed on
the narrow 110×300 column and drew half a screen of one column. Do not remove an
intermediate rung to tidy the enum; `WidgetSizeClassTest` reproduces the launcher's rule and
will say so.

## How the widget learns there is new data

A broadcast: `com.lumenpearson.lessons.action.DATA_SYNCED`, sent by the sync worker in
`:core:data`. The dependency runs one way only — `:core:data` must not depend on `:widget` —
and the broadcast is what keeps it that way. Do not "simplify" it into a direct call.

There is no Hilt; the widget reaches repositories through the hand-written `Graph`, which is
one of the two entry points that made Hilt a bad fit in the first place.

## Strings

`:widget` ships its own `values/strings.xml` and is covered by `ResourceTranslationTest`. It
was not, for a long time, which is how the countdown on the home screen stayed Russian under
an English caption.

## Gates

From `android/`: `./gradlew :widget:test`, then `./gradlew test`, `assembleDebug` and
`assembleRelease`. `--offline` in a sandbox.
