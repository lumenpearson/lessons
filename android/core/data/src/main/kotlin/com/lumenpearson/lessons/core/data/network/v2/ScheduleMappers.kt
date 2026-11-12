package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import com.lumenpearson.lessons.core.data.network.dto.WireFormats
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassSummaryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.HolidayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.LessonDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleDayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleEventDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleWindowDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TermDto
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayOffReason
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.Holiday
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Clock

internal fun ScheduleWindowDto.toTimetable(clock: Clock, status: Int = 200): Timetable {
    val school = schoolClass ?: throw malformedV2Payload("The schedule response has no class", status)
    if (school.id <= 0) throw malformedV2Payload("The schedule response has no class identity", status)
    return Timetable(
        schoolClass = school.domainClass(),
        days = days.mapNotNull { it.domainDay() }.distinctBy { it.date }.sortedBy { it.date },
        nextSchoolDay = null,
        syncedAtEpochMillis = generatedAt?.let(WireFormats::parseEpochMillis) ?: clock.millis(),
    )
}

internal fun malformedV2Payload(message: String, status: Int = 200): RemoteError =
    RemoteError(Code.INTERNAL_ERROR, message = message, httpStatus = status)

private fun ClassSummaryDto.domainClass() = SchoolClassInfo(
    id = id.toLong(), name = name.trim(), grade = grade?.takeIf { it in 1..MaxSchoolGrade },
    letter = letter.cleanOptional(), school = school.cleanOptional(),
    timeZoneId = WireFormats.sanitiseZoneId(timezone),
    termKind = TermKind.fromWire(termKind.removePrefix("TERM_KIND_").lowercase()),
    terms = terms.mapNotNull { it.domainTerm() }.sortedBy { it.index },
)

private fun TermDto.domainTerm(): Term? {
    val starts = WireFormats.parseDate(startsOn)
    val ends = WireFormats.parseDate(endsOn)
    return if (starts != null && ends != null && ends >= starts) {
        Term(index, TermKind.fromWire(kind.removePrefix("TERM_KIND_").lowercase()), starts, ends)
    } else null
}

private fun ScheduleDayDto.domainDay(): SchoolDay? {
    val parsedDate = WireFormats.parseDate(date) ?: return null
    return SchoolDay(
        date = parsedDate, weekday = if (weekday in 1..7) weekday else parsedDate.dayOfWeek.value,
        kind = DayKind.fromWire(kind.removePrefix("DAY_KIND_").lowercase()),
        lessons = lessons.mapNotNull { it.domainLesson() }.sortedWith(compareBy({ it.startsAt }, { it.index })),
        events = events.mapNotNull { it.domainEvent() }.sortedBy { it.startsAt },
        homework = homework.map { HomeworkItem(it.subject.trim(), it.text.trim(), it.attachmentUrl.cleanOptional()) },
        note = note.cleanOptional(), holiday = holiday?.domainHoliday(),
        offReason = DayOffReason.fromWire(offReason.removePrefix("DAY_OFF_REASON_").lowercase()),
    )
}

private fun LessonDto.domainLesson(): Lesson? {
    val start = WireFormats.parseTime(startsAt)
    val end = WireFormats.parseTime(endsAt)
    if (start == null || end == null) return null
    return Lesson(index, subject.trim(), start, end, room.cleanOptional(), teacher.cleanOptional(),
        color.cleanOptional(), isReplaced, isCancelled, note.cleanOptional())
}

private fun ScheduleEventDto.domainEvent(): SchoolEvent? {
    val start = WireFormats.parseTime(startsAt)
    val end = WireFormats.parseTime(endsAt)
    if (start == null || end == null) return null
    return SchoolEvent(title.trim(), EventKind.fromWire(kind.removePrefix("EVENT_KIND_").lowercase()),
        start, end, location.cleanOptional(), coversLesson)
}

private fun HolidayDto.domainHoliday(): Holiday? = title.trim().takeIf { it.isNotEmpty() && code.isNotBlank() }?.let {
    Holiday(code, it, stopsLessons)
}

private const val MaxSchoolGrade = 11
