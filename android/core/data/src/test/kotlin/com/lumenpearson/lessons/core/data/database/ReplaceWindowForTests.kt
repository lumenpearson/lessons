package com.lumenpearson.lessons.core.data.database

import com.lumenpearson.lessons.core.model.SchoolYear
import java.time.LocalDate

/**
 * [TimetableDao.replaceWindow] for a test whose subject is not the window.
 *
 * Most of these tests were written when a sync replaced the whole class and
 * took three arguments, and what they are about — the per-class subqueries, the
 * day ids stamped onto children, one class's sync not emptying another's — has
 * not changed. The window has to come from somewhere all the same, so it comes
 * from the days being written: the school year the first of them falls in.
 *
 * That is the honest default rather than a convenient one. It makes two writes
 * of the same class in the same year replace each other, which is what a sync
 * does, and two writes in *different* years sit side by side, which is the
 * thing this whole change exists for. A helper that invented a fixed year
 * would have made the second of those look like the first.
 *
 * [openingYear] is there for the tests that do care.
 */
internal suspend fun TimetableDao.replaceYear(
    schoolClass: SchoolClassEntity,
    days: List<SchoolDayRecord>,
    nextSchoolDay: SchoolDayRecord?,
    openingYear: Int = SchoolYear.openingYearOf(
        days.firstOrNull()?.day?.date ?: nextSchoolDay?.day?.date ?: LocalDate.of(2026, 9, 1),
    ),
    syncedAtEpochMillis: Long = 0L,
) {
    val bounds = SchoolYear.boundsOf(openingYear)
    replaceWindow(
        schoolClass = schoolClass,
        window = SyncedWindowEntity(
            classId = schoolClass.id,
            openingYear = openingYear,
            startsOn = bounds.start,
            endsOn = bounds.endInclusive,
            syncedAtEpochMillis = syncedAtEpochMillis,
        ),
        days = days,
        nextSchoolDay = nextSchoolDay,
    )
}
