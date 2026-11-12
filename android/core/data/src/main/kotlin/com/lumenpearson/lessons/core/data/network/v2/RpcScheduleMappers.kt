package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Timestamp
import com.lumenpearson.lessons.contract.v2.ClassSummary
import com.lumenpearson.lessons.contract.v2.Lesson
import com.lumenpearson.lessons.contract.v2.ScheduleDay
import com.lumenpearson.lessons.contract.v2.ScheduleEvent
import com.lumenpearson.lessons.contract.v2.ScheduleWindow
import com.lumenpearson.lessons.contract.v2.Term
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassSummaryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DayKindWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.DayOffReasonWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.EventKindWireSerializer
import com.lumenpearson.lessons.core.data.network.v2.dto.HolidayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.LessonDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleDayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleEventDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleHomeworkDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleWindowDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TermDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TermKindWireSerializer
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Clock
import java.time.Instant

/** Project lite messages onto the canonical shape; both bindings then share normalization. */
internal fun ScheduleWindow.toTimetable(clock: Clock): Timetable = ScheduleWindowDto(
    schoolClass = schoolClass.takeIf { hasSchoolClass() }?.canonicalClass(),
    generatedAt = generatedAt.takeIf { hasGeneratedAt() }?.canonicalInstant(),
    days = daysList.map { it.canonicalDay() },
).toTimetable(clock)

private fun ClassSummary.canonicalClass() = ClassSummaryDto(
    id, name, grade.takeIf { hasGrade() }, letter.takeIf { hasLetter() }, school.takeIf { hasSchool() },
    city.takeIf { hasCity() }, timezone, TermKindWireSerializer.wireName(termKindValue),
    termsList.map { it.canonicalTerm() },
)

private fun Term.canonicalTerm() = TermDto(index, TermKindWireSerializer.wireName(kindValue), startsOn, endsOn)

private fun ScheduleDay.canonicalDay() = ScheduleDayDto(
    date, weekday, DayKindWireSerializer.wireName(kindValue), lessonsList.map { it.canonicalLesson() },
    eventsList.map { it.canonicalEvent() }, homeworkList.map {
        ScheduleHomeworkDto(it.subject, it.text, it.attachmentUrl.takeIf { _ -> it.hasAttachmentUrl() })
    }, note.takeIf { hasNote() },
    holiday.takeIf { hasHoliday() }?.let { HolidayDto(it.code, it.title, it.stopsLessons) },
    DayOffReasonWireSerializer.wireName(offReasonValue),
)

private fun Lesson.canonicalLesson() = LessonDto(
    index, subject, startsAt, endsAt, room.takeIf { hasRoom() }, teacher.takeIf { hasTeacher() },
    color.takeIf { hasColor() },
    isReplaced, isCancelled, note.takeIf { hasNote() },
)

private fun ScheduleEvent.canonicalEvent() = ScheduleEventDto(
    title, EventKindWireSerializer.wireName(kindValue), startsAt, endsAt,
    location.takeIf { hasLocation() }, coversLesson,
)

private fun Timestamp.canonicalInstant(): String? =
    if (nanos in 0 until NanosPerSecond && seconds in FirstTimestampSecond..LastTimestampSecond) {
        Instant.ofEpochSecond(seconds, nanos.toLong()).toString()
    } else null

private const val NanosPerSecond = 1_000_000_000
private val FirstTimestampSecond = Instant.parse("0001-01-01T00:00:00Z").epochSecond
private val LastTimestampSecond = Instant.parse("9999-12-31T23:59:59Z").epochSecond
