package com.lumenpearson.lessons.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
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
 */

/** 24-hour clock. Russian schools never write 8:30 AM. */
private val ClockFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

/** "8 сентября" — the long form used in section headers. */
private val DayMonthFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")

/** "08.09" — the compact form used in the weekday selector. */
private val ShortDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM")

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

/** "понедельник" — used as a page title in the week view. */
internal fun LocalDate.asFullWeekday(locale: Locale = Locale.getDefault()): String =
    dayOfWeek.getDisplayName(TextStyle.FULL, locale)

/**
 * "Сегодня" / "Завтра" / "понедельник, 8 сентября".
 *
 * Relative labels are worth the branch: on the home screen and in the homework
 * list, "завтра" is the answer to the question the user actually asked.
 */
@Composable
internal fun LocalDate.asRelativeDayLabel(today: LocalDate = LocalDate.now()): String = when (this) {
    today -> stringResource(R.string.day_today)
    today.plusDays(1) -> stringResource(R.string.day_tomorrow)
    today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> "${asFullWeekday()}, ${asDayMonth()}"
}

/**
 * "Обновлено в 14:32" / "Обновлено 07.09 в 14:32", or the never-synced text.
 *
 * Shown as a quiet footer so a user staring at a stale timetable can tell that
 * it is stale without having to guess.
 */
@Composable
internal fun syncedAtLabel(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    if (epochMillis <= 0L) return stringResource(R.string.sync_never)
    val moment = Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDateTime()
    val today = LocalDate.now(zone)
    return if (moment.toLocalDate() == today) {
        stringResource(R.string.sync_at_time, moment.toLocalTime().asClock())
    } else {
        stringResource(
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
        stringResource(R.string.interval_minutes, minutes)
    } else {
        stringResource(R.string.interval_hours, minutes / 60)
    }
