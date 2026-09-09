package com.lumenpearson.lessons.core.data.network.dto

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Timetable

/**
 * Wire -> domain conversion. This is the only place in the app that knows about
 * snake_case, `HH:MM:SS` strings and nullable-vs-blank server fields; everything
 * downstream sees `:core:model` types only.
 *
 * The whole file is deliberately tolerant. A schedule that renders 29 of 30
 * lessons is far more useful to a pupil than an error screen, so anything that
 * cannot be understood is dropped and the rest is kept.
 */

/** Blank strings from the server mean "not set" and become `null` here. */
private fun String?.orNullIfBlank(): String? = this?.trim()?.ifBlank { null }

/**
 * A lesson, or `null` when its times are unreadable - a lesson without a
 * position on the timeline cannot be drawn or counted down to.
 */
internal fun LessonDto.toDomain(): Lesson? {
    val start = WireFormats.parseTime(startsAt) ?: return null
    val end = WireFormats.parseTime(endsAt) ?: return null
    return Lesson(
        index = index,
        subject = subject.trim(),
        startsAt = start,
        endsAt = end,
        room = room.orNullIfBlank(),
        teacher = teacher.orNullIfBlank(),
        colorHex = color.orNullIfBlank(),
        isReplaced = isReplaced,
        isCancelled = isCancelled,
        note = note.orNullIfBlank(),
    )
}

/**
 * An event, or `null` when its times are unreadable. Unknown [EventDto.kind]
 * values survive as [EventKind.EVENT] instead of dropping the row: a school trip
 * the client has never heard of still belongs on the timeline.
 */
internal fun EventDto.toDomain(): SchoolEvent? {
    val start = WireFormats.parseTime(startsAt) ?: return null
    val end = WireFormats.parseTime(endsAt) ?: return null
    return SchoolEvent(
        title = title.trim(),
        kind = EventKind.fromWire(kind),
        startsAt = start,
        endsAt = end,
        location = location.orNullIfBlank(),
        coversLesson = coversLesson,
    )
}

/** Homework carries no times, so it can never fail to map. */
internal fun HomeworkDto.toDomain(): HomeworkItem = HomeworkItem(
    subject = subject.trim(),
    text = text.trim(),
    attachmentUrl = attachmentUrl.orNullIfBlank(),
)

/**
 * A day, or `null` when its date is unreadable - a day without a date has
 * nowhere to live in the cache, since `date` is the key everything is looked up
 * by.
 *
 * Lessons are sorted by start time (then index) so the UI never has to; the
 * server already sends them in order, but sorting here makes the ordering a
 * property of the data layer rather than a server promise.
 */
internal fun DayDto.toDomain(): SchoolDay? {
    val parsedDate = WireFormats.parseDate(date) ?: return null
    return SchoolDay(
        date = parsedDate,
        weekday = if (weekday in 1..7) weekday else parsedDate.dayOfWeek.value,
        kind = DayKind.fromWire(kind),
        lessons = lessons.mapNotNull { it.toDomain() }
            .sortedWith(compareBy({ it.startsAt }, { it.index })),
        events = events.mapNotNull { it.toDomain() }.sortedBy { it.startsAt },
        homework = homework.map { it.toDomain() },
        note = note.orNullIfBlank(),
    )
}

/** Class identity, with an unusable time zone replaced by the server default. */
internal fun SchoolClassDto.toDomain(): SchoolClassInfo = SchoolClassInfo(
    id = id,
    name = name.trim(),
    school = school.orNullIfBlank(),
    timeZoneId = WireFormats.sanitiseZoneId(timezone),
)

/**
 * The full snapshot.
 *
 * @param fallbackSyncedAtEpochMillis used when the server's `generated_at` is
 * missing or unparseable, so "last synced" is never 1970. Pass the wall clock at
 * the moment the response arrived.
 */
internal fun BundleDto.toDomain(fallbackSyncedAtEpochMillis: Long): Timetable = Timetable(
    schoolClass = schoolClass.toDomain(),
    days = days.mapNotNull { it.toDomain() }.sortedBy { it.date },
    nextSchoolDay = nextSchoolDay?.toDomain(),
    syncedAtEpochMillis = WireFormats.parseEpochMillis(generatedAt) ?: fallbackSyncedAtEpochMillis,
)
