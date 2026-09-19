package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.ui.DayLoad
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.temporal.TemporalAdjusters

/** Monday through Sunday; the week strip has no other shape. */
private const val DAYS_IN_WEEK = 7

/**
 * Everything a render takes out of the cached timetable, as a pure function of
 * it and of one instant.
 *
 * Lifted out of `LessonsWidget.loadSnapshot` so that the questions the widget
 * asks a [Timetable] are written in one place that a test can call. They are
 * exactly four, and the list is load-bearing rather than descriptive — the
 * cache is now read through `snapshotAroundToday()`, which hands back a
 * timetable whose `days` cover a fortnight and answers «no data» for every
 * date outside it:
 *
 *  - [ScheduleEngine.stateAt] for [now], which reads `day(today)` and, for the
 *    homework it points at, `schoolDayAfter(today)`;
 *  - the week strip, `monday .. monday + 6` — and `monday` is never later than
 *    today, so the strip ends at most six days ahead, inside the bound, while
 *    its left edge is the Monday the bound itself reaches back to;
 *  - `day(today)`;
 *  - [homeworkDayFor], which is `schoolDayAfter(today)` again.
 *
 * Every one of those dates is inside the bound except `schoolDayAfter`, which
 * is carried across it as the timetable's `nextSchoolDay`. `BoundedSnapshotParityTest`
 * walks both readings over a school year and fails if they ever part company.
 * Anything added here that reaches a further date has to be checked against
 * that bound first; a widget that silently draws an empty week is worse than
 * one that reads too much.
 */
internal fun snapshotOf(
    timetable: Timetable?,
    now: LocalDateTime,
    signedIn: Boolean,
    options: WidgetOptions,
    language: AppLanguage,
): LessonsWidget.Snapshot {
    val today = now.toLocalDate()
    val state = timetable?.let { ScheduleEngine.stateAt(it, now) }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return LessonsWidget.Snapshot(
        now = now,
        state = state,
        signedIn = signedIn,
        week = (0 until DAYS_IN_WEEK).map { offset ->
            val date = monday.plusDays(offset.toLong())
            DayLoad(date = date, lessons = timetable?.day(date)?.activeLessons?.size ?: 0)
        },
        today = timetable?.day(today),
        homeworkDay = homeworkDayFor(state, timetable, today),
        language = language,
        options = options,
    )
}
