package com.lumenpearson.lessons.core.designsystem.state

import androidx.compose.runtime.Composable
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import java.time.Duration
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.math.ceil

/**
 * Fixed 24-hour clock format.
 *
 * Held as a constant because a [DateTimeFormatter] is expensive to build and the
 * timeline formats two of these per lesson on every recomposition.
 */
private val HourMinute: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * Wall-clock time as the schedule shows it: `08:30`.
 *
 * Deliberately not locale-sensitive — a Russian school timetable is 24-hour
 * everywhere, and an am/pm fallback in an English locale would not match the
 * printed schedule on the classroom door.
 */
fun LocalTime.formatHm(): String = format(HourMinute)

/** A lesson's span as one string: `08:30 – 09:15`. Uses an en dash, not a hyphen. */
fun formatTimeRange(start: LocalTime, end: LocalTime): String =
    "${start.formatHm()} – ${end.formatHm()}"

/**
 * Countdown text: `12 мин`, `1 ч 05 мин`, `меньше минуты` — and the English
 * twins of all three.
 *
 * Takes a [Context] rather than being a pure function, which is the whole point
 * of the change that gave it one: the words used to be Kotlin string literals,
 * so the largest figure on the home screen came out in Russian under a caption
 * that came out of `values-en/`. The widget has always worded its own countdown
 * through resources (`WidgetStrings.duration`); this is the same rule applied
 * to the card the app draws above it.
 *
 * Read through [correctedString] and not through `Context.getString`, which
 * is what it used to do: the words never learned their resource id, so the
 * largest number on the home screen was the one thing on that card correction
 * mode could not touch — no outline, and a long press that did nothing, right
 * beside a caption that had both. `CorrectionReachTest` could not see it,
 * because it looks for two imports and this file used neither.
 *
 * Rounds *up* rather than truncating, because this always labels time that is
 * still remaining: a truncating "0 мин" that sits there for a full minute is the
 * single most confusing thing a countdown can do. Negative durations — a clock
 * that drifted past the boundary before the next tick — clamp to zero instead of
 * printing a minus sign.
 */
@Composable
fun Duration.formatCountdown(): String {
    if (isNegative || isZero) return correctedString(R.string.ds_countdown_under_minute)
    val totalMinutes = ceil(seconds / 60.0).toLong()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L -> correctedString(R.string.ds_countdown_hours_minutes, hours, minutes)
        else -> correctedString(R.string.ds_countdown_minutes, totalMinutes)
    }
}

/**
 * A length of time that is not a countdown: `45 мин`, `1 ч 05 мин`, `0 мин`.
 *
 * The same words as [formatCountdown] and the opposite rounding, which is the
 * only reason it is a second function. A countdown rounds up because "0 мин"
 * sitting there for a whole minute is the worst thing a countdown can say. A
 * length is a fact about the thing rather than a promise about the clock: a
 * lesson from 08:30 to 09:15 is forty-five minutes, and rounding that up to
 * forty-six would be wrong in a way nobody could explain.
 *
 * Which is also why zero prints as `0 мин` here instead of «меньше минуты» — an
 * entry of no length is a data error, and saying «меньше минуты» about it reads
 * as a very short lesson rather than as something being wrong.
 */
@Composable
fun Duration.formatLength(): String {
    val totalMinutes = if (isNegative) 0L else seconds / 60
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours > 0L -> correctedString(R.string.ds_countdown_hours_minutes, hours, minutes)
        else -> correctedString(R.string.ds_countdown_minutes, totalMinutes)
    }
}

/**
 * A 0f..1f progress value as a whole percentage.
 *
 * Exists so accessibility descriptions and the hero card agree on the rounding;
 * a screen reader saying "84%" over a bar that visually reads 83 is a bug report
 * nobody can reproduce.
 */
fun Float.asPercent(): Int = (coerceIn(0f, 1f) * 100f).toInt()
