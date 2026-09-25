package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.data.repository.ShellMode
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
 *
 * Outside [ShellMode.CLASS] a [timetable] is ignored, whatever was handed in.
 * The repository already answers null without an active class, so today this
 * changes nothing; it is here so that the rule «no class, no timetable» is
 * this function's rather than a property of a read three modules away, and so
 * the sentence [mode] picks and the lessons drawn beside it can never come
 * from two different answers to the same question.
 */
internal fun snapshotOf(
    timetable: Timetable?,
    now: LocalDateTime,
    mode: ShellMode,
    options: WidgetOptions,
    language: AppLanguage,
): LessonsWidget.Snapshot {
    val drawn = timetable.takeIf { mode == ShellMode.CLASS }
    val today = now.toLocalDate()
    val state = drawn?.let { ScheduleEngine.stateAt(it, now) }
    val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return LessonsWidget.Snapshot(
        now = now,
        state = state,
        mode = mode,
        week = (0 until DAYS_IN_WEEK).map { offset ->
            val date = monday.plusDays(offset.toLong())
            DayLoad(date = date, lessons = drawn?.day(date)?.activeLessons?.size ?: 0)
        },
        today = drawn?.day(today),
        homeworkDay = homeworkDayFor(state, drawn, today),
        language = language,
        options = options,
    )
}
