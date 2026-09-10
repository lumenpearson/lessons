package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.core.model.homeworkFocus
import java.time.LocalDate

/**
 * Which day's homework the widget should show.
 *
 * [DayState.homeworkFocus] only answers once lessons are over — it is null all
 * through the school day — so the larger layouts, which show homework *beside*
 * the timeline rather than instead of it, need the next school day resolved for
 * them. This lived inline in the widget's snapshot loader, where the fallback
 * was documented in two other files and implemented in none, and the homework
 * block sat empty every day until the last bell.
 */
internal fun homeworkDayFor(
    state: DayState?,
    timetable: Timetable?,
    today: LocalDate,
): SchoolDay? = state?.homeworkFocus ?: timetable?.schoolDayAfter(today)
