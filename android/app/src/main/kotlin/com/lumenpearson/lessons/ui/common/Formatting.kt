package com.lumenpearson.lessons.ui.common

import androidx.compose.runtime.Composable
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Date and time formatting for the whole app.
 *
 * It lives in one file because school UIs are full of "8:30 – 9:15" and
 * "понедельник, 8 сентября", and a second spelling of either of those is how a
 * screen starts looking like it was written by a different person.
 *
 * Weekday and month names come from [Locale.getDefault], not from strings.xml:
 * java.time already has correctly declined Russian month names ("8 сентября",
 * not "8 сентябрь"), and hand-written string arrays get that wrong.
 *
 * That default is the app's language and not the phone's because `AppLocale`
 * sets it alongside every context it wraps, which matters only below Android 13
 * — above it the platform does the same thing itself. Without that, an English
 * app on a Russian phone would draw English screens with Russian month names,
 * because `createConfigurationContext` moves resource lookup and leaves
 * java.time where it was.
 */

/** 24-hour clock. Russian schools never write 8:30 AM. */
private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "8 сентября" — the long form used in section headers. */
private val DayMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")

/** "08.09" — the compact form used in the weekday selector. */
private val ShortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

/** "сентябрь 2026" — the month view's period label. */
private val MonthYearFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("LLLL yyyy")

/** "8:30" for a bell time. */
internal fun LocalTime.asClock(): String = format(ClockFormatter)

/** "8:30 – 9:15" for a lesson or an event. */
internal fun timeRange(start: LocalTime, end: LocalTime): String =
    "${start.asClock()} – ${end.asClock()}"

/** "8 сентября", correctly declined for the current locale. */
internal fun LocalDate.asDayMonth(locale: Locale = Locale.getDefault()): String =
    format(DayMonthFormatter.withLocale(locale))

/** "08.09" for tight spaces. */
internal fun LocalDate.asShortDate(): String = format(ShortDateFormatter)

/** "пн" — the weekday selector's chip label. */
internal fun LocalDate.asShortWeekday(locale: Locale = Locale.getDefault()): String =
    dayOfWeek.getDisplayName(TextStyle.SHORT, locale).replaceFirstChar { it.lowercase(locale) }

/**
 * "сентябрь 2026", in the nominative.
 *
 * `LLLL` rather than `MMMM`: the pattern letter for a standalone month name. On
 * its own `MMMM` gives the genitive form java.time uses inside a full date, so
 * the month view's own title read "сентября 2026" — a date with its day
 * amputated rather than the name of a month.
 */
internal fun LocalDate.asMonthYear(locale: Locale = Locale.getDefault()): String =
    format(MonthYearFormatter.withLocale(locale)).replaceFirstChar { it.uppercase(locale) }

/** "понедельник" — used as a page title in the week view. */
internal fun LocalDate.asFullWeekday(locale: Locale = Locale.getDefault()): String =
    dayOfWeek.getDisplayName(TextStyle.FULL, locale)

/**
 * "Сегодня" / "Завтра" / "понедельник, 8 сентября".
 *
 * Relative labels are worth the branch: on the home screen and in the homework
 * list, "завтра" is the answer to the question the user actually asked.
 *
 * @param today required rather than defaulted, because the only correct answer
 *   is the school's date and this function cannot reach it. Both callers hold
 *   it already — it is on the state their list was built from — and a default
 *   of `LocalDate.now()` would have been the device's, silently, for whichever
 *   caller forgot.
 */
@Composable
internal fun LocalDate.asRelativeDayLabel(today: LocalDate): String = when (this) {
    today -> correctedString(R.string.day_today)
    today.plusDays(1) -> correctedString(R.string.day_tomorrow)
    today.minusDays(1) -> correctedString(R.string.day_yesterday)
    else -> "${asFullWeekday()}, ${asDayMonth()}"
}

/**
 * "Обновлено в 14:32" / "Обновлено 07.09 в 14:32", or the never-synced text.
 *
 * Shown as a quiet footer so a user staring at a stale timetable can tell that
 * it is stale without having to guess.
 */
@Composable
// device clock: unlike every other date in the app this one is about the phone,
// not the school — "обновлено в 14:32" means the clock the reader is holding.
internal fun syncedAtLabel(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0L) return correctedString(R.string.sync_never)
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
    val today = LocalDate.now(zone)
    return if (moment.toLocalDate() == today) {
        correctedString(R.string.sync_at_time, moment.toLocalTime().asClock())
    } else {
        correctedString(
            R.string.sync_at_date_time,
            moment.toLocalDate().asShortDate(),
            moment.toLocalTime().asClock(),
        )
    }
}

/** "15 мин" / "1 ч" / "3 ч" for the sync-interval chips. */
@Composable
internal fun syncIntervalLabel(minutes: Int): String =
    if (minutes < 60) {
        correctedString(R.string.interval_minutes, minutes)
    } else {
        correctedString(R.string.interval_hours, minutes / 60)
    }
