package com.lumenpearson.lessons.core.data.database

import androidx.room.Embedded
import androidx.room.Relation

/**
 * A whole day in one read.
 *
 * Without this POJO the timetable screen would issue four queries per day and
 * fourteen days would mean fifty-odd round trips through the Flow machinery on
 * every single change. With it, `@Transaction`-annotated DAO queries return the
 * complete window consistently, in four statements total.
 */
internal data class SchoolDayWithDetails(
    @Embedded val day: SchoolDayEntity,
    @Relation(parentColumn = "id", entityColumn = "day_id")
    val lessons: List<LessonEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "day_id")
    val events: List<EventEntity> = emptyList(),
    @Relation(parentColumn = "id", entityColumn = "day_id")
    val homework: List<HomeworkEntity> = emptyList(),
)

/**
 * A day about to be written, before its row id exists.
 *
 * Children carry `dayId = 0` until [TimetableDao.replaceAll] inserts the parent
 * and stamps the generated id onto them; keeping that in one small type is what
 * lets `replaceAll` stay a single flat transaction.
 */
internal data class SchoolDayRecord(
    val day: SchoolDayEntity,
    val lessons: List<LessonEntity> = emptyList(),
    val events: List<EventEntity> = emptyList(),
    val homework: List<HomeworkEntity> = emptyList(),
)

/**
 * One consistent view of the cache: the class, its days, and the next school
 * day, all read inside a single transaction.
 *
 * @see TimetableDao.snapshot
 */
internal data class TimetableSnapshot(
    val schoolClass: SchoolClassEntity,
    val days: List<SchoolDayWithDetails>,
    val nextSchoolDay: SchoolDayWithDetails?,
)
