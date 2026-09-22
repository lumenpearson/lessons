package com.lumenpearson.lessons.core.data.database

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayOffReason
import com.lumenpearson.lessons.core.model.Holiday
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate

/**
 * Domain <-> entity conversion.
 *
 * Kept out of the entities themselves so that `:core:model` stays a pure JVM
 * module with no idea that a database exists.
 */

/** Placeholder used until [TimetableDao.replaceWindow] knows the day's row id. */
private const val UNSAVED_DAY_ID = 0L

internal fun SchoolClassInfo.toEntity(syncedAtEpochMillis: Long): SchoolClassEntity =
    SchoolClassEntity(
        id = id,
        name = name,
        grade = grade,
        letter = letter,
        school = school,
        timeZoneId = timeZoneId,
        termKind = termKind.name.lowercase(),
        terms = encodeTerms(terms),
        syncedAtEpochMillis = syncedAtEpochMillis,
    )

internal fun SchoolClassEntity.toDomain(): SchoolClassInfo = SchoolClassInfo(
    id = id,
    name = name,
    grade = grade,
    letter = letter,
    school = school,
    timeZoneId = timeZoneId,
    termKind = TermKind.fromWire(termKind),
    terms = decodeTerms(terms),
)

/**
 * `index|kind|start|end` per line, and a line that does not parse is dropped.
 *
 * Dropped rather than defaulted: a term with an invented date would shade the
 * wrong weeks of the calendar and name the wrong term, and the cache is
 * rebuilt from the server on the next sync anyway. Nothing here is the source
 * of truth.
 */
internal fun decodeTerms(raw: String): List<Term> = raw.lineSequence()
    .mapNotNull { line ->
        val parts = line.split('|')
        if (parts.size != 4) return@mapNotNull null
        runCatching {
            Term(
                index = parts[0].toInt(),
                kind = TermKind.fromWire(parts[1]),
                startsOn = LocalDate.parse(parts[2]),
                endsOn = LocalDate.parse(parts[3]),
            )
        }.getOrNull()
    }
    .sortedBy { it.index }
    .toList()

/** @see decodeTerms */
internal fun encodeTerms(terms: List<Term>): String = terms.joinToString("\n") { term ->
    "${term.index}|${term.kind.name.lowercase()}|${term.startsOn}|${term.endsOn}"
}

/**
 * Flattens a day into the rows that represent it.
 *
 * @param classId the class this day belongs to. Passed in rather than left as a
 * placeholder the way `dayId` is: the row id genuinely does not exist until the
 * insert, but the class is known here, and a cache holding two classes at once
 * has no margin for a day that does not know whose it is.
 * @param isNextSchoolDay marks the lookahead day so it can be read back
 * separately instead of appearing twice in the calendar.
 */
internal fun SchoolDay.toRecord(classId: Long, isNextSchoolDay: Boolean): SchoolDayRecord =
    SchoolDayRecord(
        day = SchoolDayEntity(
            classId = classId,
            date = date,
            weekday = weekday,
            kind = kind.name,
            note = note,
            isNextSchoolDay = isNextSchoolDay,
            offReason = offReason?.name,
            holidayCode = holiday?.code,
            holidayTitle = holiday?.title,
            holidayStopsLessons = holiday?.stopsLessons ?: false,
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
    // Read back through `fromWire` for the same reason the kinds are: a row
    // written by a newer build that knew a reason this one does not must load
    // as «no reason given» rather than take the day with it.
    offReason = DayOffReason.fromWire(day.offReason),
    holiday = day.holidayCode?.let { code ->
        day.holidayTitle?.let { title ->
            Holiday(code = code, title = title, stopsLessons = day.holidayStopsLessons)
        }
    },
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
