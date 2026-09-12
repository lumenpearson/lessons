package com.lumenpearson.lessons.core.designsystem.state

import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Minutes in a day; the modulus every value on this page is stored against. */
const val MinutesPerDay: Int = 24 * 60

private const val MinutesPerHour: Int = 60

/**
 * Fixed 24-hour clock format, for the phones that are set to it.
 *
 * A constant for the same reason [formatHm]'s is: building a [DateTimeFormatter]
 * costs more than the string it produces, and a settings row formats one of
 * these on every recomposition of the list it sits in.
 */
private val Hour24: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/**
 * The 12-hour formatters, one per locale that has actually asked for one.
 *
 * Unlike the 24-hour pattern, this one carries words — "AM", "пп" — so it cannot
 * be a single shared instance. In practice the map holds one entry, and it is a
 * map rather than a field so that a locale change at runtime does not keep
 * printing the previous language's meridiem.
 */
private val Hour12: ConcurrentHashMap<Locale, DateTimeFormatter> = ConcurrentHashMap()

/**
 * Pins a stored time inside the day it is supposed to describe.
 *
 * Clamping rather than wrapping, deliberately. These numbers come out of a
 * preferences file that older builds of the app wrote and future ones will, and
 * a value that has drifted past midnight is a bug, not an intention — `1500`
 * meaning "01:00 tomorrow" would silently move somebody's morning summary to the
 * middle of the night instead of leaving it at the end of the day it was set on.
 */
fun clampMinutesOfDay(minutes: Int): Int = minutes.coerceIn(0, MinutesPerDay - 1)

/** Hour hand of a stored time, 0..23. */
fun hourOfDay(minutesOfDay: Int): Int = clampMinutesOfDay(minutesOfDay) / MinutesPerHour

/** Minute hand of a stored time, 0..59. */
fun minuteOfHour(minutesOfDay: Int): Int = clampMinutesOfDay(minutesOfDay) % MinutesPerHour

/**
 * The inverse: what a picker's two dials mean as a stored value.
 *
 * Takes the same clamp as everything else here, so a caller handing over an
 * out-of-range hour gets the end of the day rather than a number no row on the
 * screen can display.
 */
fun minutesOfDayOf(hour: Int, minute: Int): Int =
    clampMinutesOfDay(hour * MinutesPerHour + minute)

/**
 * A stored time as the row shows it: `08:30`, or `8:30 AM`.
 *
 * [use24Hour] is the device's own setting rather than a property of the
 * language, which is the whole point of asking. A Russian school timetable is
 * printed in 24-hour and this app writes `08:30` everywhere else — see
 * [formatHm] — but that is the *school's* clock. This one is a time the user
 * picked for their own phone to interrupt them at, and it has to be readable
 * against the clock in their status bar.
 */
fun formatClockTime(
    minutesOfDay: Int,
    use24Hour: Boolean,
    locale: Locale = Locale.getDefault(),
): String {
    val time = LocalTime.of(hourOfDay(minutesOfDay), minuteOfHour(minutesOfDay))
    return if (use24Hour) {
        time.format(Hour24)
    } else {
        time.format(Hour12.getOrPut(locale) { DateTimeFormatter.ofPattern("h:mm a", locale) })
    }
}
