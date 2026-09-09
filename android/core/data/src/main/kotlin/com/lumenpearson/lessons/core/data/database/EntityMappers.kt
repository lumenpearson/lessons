package com.lumenpearson.lessons.core.data.database

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Timetable

/**
 * Domain <-> entity conversion.
 *
 * Kept out of the entities themselves so that `:core:model` stays a pure JVM
 * module with no idea that a database exists.
 */

/** Placeholder used until [TimetableDao.replaceAll] knows the day's row id. */
private const val UNSAVED_DAY_ID = 0L

internal fun SchoolClassInfo.toEntity(syncedAtEpochMillis: Long): SchoolClassEntity =
    SchoolClassEntity(
        id = id,
        name = name,
        school = school,
        timeZoneId = timeZoneId,
        syncedAtEpochMillis = syncedAtEpochMillis,
    )

internal fun SchoolClassEntity.toDomain(): SchoolClassInfo = SchoolClassInfo(
    id = id,
    name = name,
    school = school,
    timeZoneId = timeZoneId,
)

/**
 * Flattens a day into the rows that represent it.
 *
 * @param isNextSchoolDay marks the lookahead day so it can be read back
 * separately instead of appearing twice in the calendar.
 */
internal fun SchoolDay.toRecord(isNextSchoolDay: Boolean): SchoolDayRecord = SchoolDayRecord(
    day = SchoolDayEntity(
        date = date,
        weekday = weekday,
        kind = kind.name,
        note = note,
        isNextSchoolDay = isNextSchoolDay,
    ),
    lessons = lessons.map { lesson ->
        LessonEntity(
            dayId = UNSAVED_DAY_ID,
            index = lesson.index,
            subject = lesson.subject,
            startsAt = lesson.startsAt,
            endsAt = lesson.endsAt,
            room = lesson.room,
            teacher = lesson.teacher,
            colorHex = lesson.colorHex,
            isReplaced = lesson.isReplaced,
            isCancelled = lesson.isCancelled,
            note = lesson.note,
        )
    },
    events = events.map { event ->
        EventEntity(
            dayId = UNSAVED_DAY_ID,
            title = event.title,
            kind = event.kind.name,
            startsAt = event.startsAt,
            endsAt = event.endsAt,
            location = event.location,
            coversLesson = event.coversLesson,
        )
    },
    homework = homework.map { item ->
        HomeworkEntity(
            dayId = UNSAVED_DAY_ID,
            subject = item.subject,
            text = item.text,
            attachmentUrl = item.attachmentUrl,
        )
    },
)

/**
 * Rebuilds a day from its rows.
 *
 * Children are re-sorted here because `@Relation` makes no ordering promise, and
 * the UI draws them in the order it receives them. Kinds go through `fromWire`
 * again: a row written by a newer build that knew more kinds must still load.
 */
internal fun SchoolDayWithDetails.toDomain(): SchoolDay = SchoolDay(
    date = day.date,
    weekday = day.weekday,
    kind = DayKind.fromWire(day.kind),
    lessons = lessons
        .sortedWith(compareBy({ it.startsAt }, { it.index }))
        .map { entity ->
            Lesson(
                index = entity.index,
                subject = entity.subject,
                startsAt = entity.startsAt,
                endsAt = entity.endsAt,
                room = entity.room,
                teacher = entity.teacher,
                colorHex = entity.colorHex,
                isReplaced = entity.isReplaced,
                isCancelled = entity.isCancelled,
                note = entity.note,
            )
        },
    events = events
        .sortedBy { it.startsAt }
        .map { entity ->
            SchoolEvent(
                title = entity.title,
                kind = EventKind.fromWire(entity.kind),
                startsAt = entity.startsAt,
                endsAt = entity.endsAt,
                location = entity.location,
                coversLesson = entity.coversLesson,
            )
        },
    homework = homework.map { entity ->
        HomeworkItem(
            subject = entity.subject,
            text = entity.text,
            attachmentUrl = entity.attachmentUrl,
        )
    },
    note = day.note,
)

/** Assembles the single value the whole app renders from. */
internal fun buildTimetable(
    schoolClass: SchoolClassEntity,
    days: List<SchoolDayWithDetails>,
    nextSchoolDay: SchoolDayWithDetails?,
): Timetable = Timetable(
    schoolClass = schoolClass.toDomain(),
    days = days.map { it.toDomain() }.sortedBy { it.date },
    nextSchoolDay = nextSchoolDay?.toDomain(),
    syncedAtEpochMillis = schoolClass.syncedAtEpochMillis,
)
