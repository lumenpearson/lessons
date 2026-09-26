package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import java.time.LocalDate

/**
 * Which days the import asks for, worked out from «today» and nothing else so
 * it can be held by a test.
 *
 * **Weeks:** this week and the next, Monday to Sunday — two whole weeks, one
 * request per resource, each a range the cache keeps
 * ([DiaryWindows.isWholeWeeks]) and the week view asks for week by week.
 *
 * **Marks:** [DiaryWindows.gradeWindow] of the current term — the window the
 * marks tab asks for, so it opens from the cache. That costs more upstream
 * than four weeks would (about nine «Сетевой город» weeks for a long quarter),
 * and it is the price of the tab not asking again the moment it is opened.
 *
 * Every span is inside `DiaryRepository.MAX_RANGE_DAYS`, so the repository's
 * own range check never refuses what the import plans.
 */
data class DiaryImportPlan(
    val today: LocalDate,
    val weeksFrom: LocalDate,
    val weeksTo: LocalDate,
) {

    /** The Mondays of the weeks the plan fetches. */
    val mondays: List<LocalDate>
        get() = generateSequence(weeksFrom) { it.plusWeeks(1) }
            .takeWhile { !it.isAfter(weeksTo) }
            .toList()

    /** The marks window once the current term is known — or not: `null` is «no term». */
    fun marksWindow(currentPeriod: DiaryPeriod?): DiaryDateRange =
        DiaryWindows.gradeWindow(today, currentPeriod)

    companion object {
        /** This week and the next. */
        const val WEEKS: Long = 2

        fun of(today: LocalDate): DiaryImportPlan {
            val monday = DiaryWindows.weekStart(today)
            return DiaryImportPlan(
                today = today,
                weeksFrom = monday,
                weeksTo = monday.plusWeeks(WEEKS).minusDays(1),
            )
        }
    }
}
