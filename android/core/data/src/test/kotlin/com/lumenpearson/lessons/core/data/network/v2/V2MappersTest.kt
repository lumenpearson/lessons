package com.lumenpearson.lessons.core.data.network.v2

import com.google.protobuf.Timestamp
import com.lumenpearson.lessons.contract.v2.ClassSummary
import com.lumenpearson.lessons.contract.v2.CreateDeviceResponse
import com.lumenpearson.lessons.contract.v2.DiaryBinding
import com.lumenpearson.lessons.contract.v2.ScheduleWindow
import com.lumenpearson.lessons.core.data.network.v2.dto.ClassSummaryDto
import com.lumenpearson.lessons.core.data.network.v2.dto.CreateDeviceResponseDto
import com.lumenpearson.lessons.core.data.network.v2.dto.DiaryBindingDto
import com.lumenpearson.lessons.core.data.network.v2.dto.LessonDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleDayDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleEventDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleHomeworkDto
import com.lumenpearson.lessons.core.data.network.v2.dto.ScheduleWindowDto
import com.lumenpearson.lessons.core.data.network.v2.dto.TermDto
import com.lumenpearson.lessons.core.data.network.v2.dto.HolidayDto
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.TermKind
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class V2MappersTest {
    private val clock = Clock.fixed(Instant.parse("2026-11-12T07:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `binary projection keeps every modeled optional field and known enum`() {
        val school = ClassSummary.newBuilder().setId(7).setName("9A").setGrade(9).setLetter("A").setSchool("S")
            .setTimezone("Asia/Yakutsk").setTermKindValue(2).addTerms(
                com.lumenpearson.lessons.contract.v2.Term.newBuilder().setIndex(1).setKindValue(2)
                    .setStartsOn("2026-09-01").setEndsOn("2026-12-31"))
        val day = com.lumenpearson.lessons.contract.v2.ScheduleDay.newBuilder().setDate("2026-11-12").setWeekday(4)
            .setKindValue(3).setNote("Day note").setOffReasonValue(3)
            .setHoliday(com.lumenpearson.lessons.contract.v2.Holiday.newBuilder()
                .setCode("holiday").setTitle("Holiday").setStopsLessons(true))
            .addLessons(com.lumenpearson.lessons.contract.v2.Lesson.newBuilder().setIndex(2).setSubject("Maths")
                .setStartsAt("09:00").setEndsAt("09:45").setRoom("R").setTeacher("T").setColor("#abcdef")
                .setIsReplaced(true).setIsCancelled(true).setNote("Lesson note"))
            .addEvents(com.lumenpearson.lessons.contract.v2.ScheduleEvent.newBuilder().setTitle("Exam").setKindValue(3)
                .setStartsAt("10:00").setEndsAt("11:00").setLocation("Hall").setCoversLesson(true))
            .addHomework(com.lumenpearson.lessons.contract.v2.ScheduleHomework.newBuilder().setSubject("Maths")
                .setText("Task").setAttachmentUrl("https://school.invalid/task"))
        val actual = ScheduleWindow.newBuilder().setSchoolClass(school).addDays(day)
            .setGeneratedAt(Timestamp.getDefaultInstance()).build().toTimetable(clock)
        val expected = ScheduleWindowDto(ClassSummaryDto(7, "9A", 9, "A", "S", timezone = "Asia/Yakutsk",
            termKind = "TERM_KIND_SEMESTER", terms = listOf(TermDto(1, "TERM_KIND_SEMESTER",
                "2026-09-01", "2026-12-31"))), "1970-01-01T00:00:00Z", days = listOf(
                ScheduleDayDto("2026-11-12", 4, "DAY_KIND_SHORTENED", lessons = listOf(
                    LessonDto(2, "Maths", "09:00", "09:45", "R", "T", "#abcdef", true, true, "Lesson note")),
                    events = listOf(ScheduleEventDto("Exam", "EVENT_KIND_EXAM", "10:00", "11:00", "Hall", true)),
                    homework = listOf(ScheduleHomeworkDto("Maths", "Task", "https://school.invalid/task")),
                    note = "Day note", holiday = HolidayDto("holiday", "Holiday", true),
                    offReason = "DAY_OFF_REASON_PUBLIC_HOLIDAY"))).toTimetable(clock)
        assertEquals(expected, actual)
    }

    @Test
    fun `both join shapes preserve a wide diary school id and trim optional text`() {
        val rest = CreateDeviceResponseDto(token = "t", classId = 7, className = "9A", school = " S ",
            diary = DiaryBindingDto("netschool", " 77 ", 9007199254740993L, " Diary school ")).toSession()
        val rpc = CreateDeviceResponse.newBuilder().setToken("t").setClassId(7).setClassName("9A").setSchool(" S ")
            .setDiary(DiaryBinding.newBuilder().setProvider("netschool").setRegion(" 77 ")
                .setSchoolId(9007199254740993L).setSchoolName(" Diary school ")).build().toSession()
        assertEquals(rest, rpc)
        assertEquals(9007199254740993L, rest.diary?.schoolId)
        assertEquals("S", rest.school)
        assertEquals("77", rest.diary?.region)
        assertEquals("Diary school", rest.diary?.schoolName)
        assertEquals(DiaryProviderKey.NETSCHOOL, rest.diary?.provider)
    }

    @Test
    fun `unknown bindings cost the binding while absent or blank fields stay absent`() {
        val rest = CreateDeviceResponseDto(token = "t", classId = 7, className = "9A", school = " ",
            diary = DiaryBindingDto("future")).toSession()
        val rpc = CreateDeviceResponse.newBuilder().setToken("t").setClassId(7).setClassName("9A")
            .setDiary(DiaryBinding.newBuilder().setProvider("future")).build().toSession()
        assertEquals(Session(7, "9A", null, "t"), rest)
        assertEquals(rest, rpc)
    }

    @Test
    fun `a present epoch timestamp differs from an absent or malformed timestamp`() {
        val school = ClassSummaryDto(id = 7, name = "9A")
        for (raw in listOf(null, "bad")) {
            assertEquals(clock.millis(), ScheduleWindowDto(school, raw).toTimetable(clock).syncedAtEpochMillis)
        }
        assertEquals(0L, ScheduleWindowDto(school, "1970-01-01T00:00:00Z").toTimetable(clock).syncedAtEpochMillis)
        val builder = ScheduleWindow.newBuilder().setSchoolClass(ClassSummary.newBuilder().setId(7).setName("9A"))
        assertEquals(clock.millis(), builder.build().toTimetable(clock).syncedAtEpochMillis)
        assertEquals(0L, builder.setGeneratedAt(Timestamp.getDefaultInstance()).build()
            .toTimetable(clock).syncedAtEpochMillis)
        assertEquals(clock.millis(), builder.setGeneratedAt(Timestamp.newBuilder().setNanos(-1)).build()
            .toTimetable(clock).syncedAtEpochMillis)
    }

    @Test
    fun `normalization keeps usable neighbors optional fields and school terms without consulting the device zone`() {
        val day = ScheduleDayDto(date = "2026-11-12", kind = "FUTURE_DAY_KIND", lessons = listOf(
            LessonDto(index = 2, subject = " Maths ", startsAt = "09:00", endsAt = "09:45", teacher = " T ",
                room = " R ", color = " #abcdef ", isReplaced = true, isCancelled = true, note = " note "),
            LessonDto(index = 1, startsAt = "bad", endsAt = "10:00")),
            events = listOf(ScheduleEventDto(" Trip ", "FUTURE_EVENT_KIND", "10:00", "11:00", " Hall ", true)),
            homework = listOf(ScheduleHomeworkDto(" Maths ", " Task ", " https://school.invalid/task ")))
        val window = ScheduleWindowDto(ClassSummaryDto(7, " 9A ", grade = 99, letter = " A ", school = " S ",
            timezone = "Asia/Yakutsk", termKind = "TERM_KIND_SEMESTER", terms = listOf(
                TermDto(2, "TERM_KIND_SEMESTER", "2027-01-01", "2027-05-31"),
                TermDto(1, "TERM_KIND_SEMESTER", "2026-09-01", "2026-12-31"),
                TermDto(3, "TERM_KIND_SEMESTER", "bad", "bad"))), days = listOf(day, day, ScheduleDayDto(date = "bad")))

        val actual = window.toTimetable(clock)

        assertEquals("Asia/Yakutsk", actual.schoolClass.timeZoneId)
        assertNull(actual.schoolClass.grade)
        assertEquals("A", actual.schoolClass.letter)
        assertEquals(TermKind.SEMESTER, actual.schoolClass.termKind)
        assertEquals(listOf(1, 2), actual.schoolClass.terms.map { it.index })
        val result = actual.days.single()
        assertEquals(DayKind.NORMAL, result.kind)
        assertEquals(LocalTime.of(9, 0), result.lessons.single().startsAt)
        assertEquals("T", result.lessons.single().teacher)
        assertEquals("R", result.lessons.single().room)
        assertEquals("#abcdef", result.lessons.single().colorHex)
        assertEquals("note", result.lessons.single().note)
        assertEquals(true, result.lessons.single().isCancelled)
        assertEquals(true, result.lessons.single().isReplaced)
        assertEquals(EventKind.EVENT, result.events.single().kind)
        assertEquals("Hall", result.events.single().location)
        assertEquals(true, result.events.single().coversLesson)
        assertEquals("Task", result.homework.single().text)
        assertEquals("https://school.invalid/task", result.homework.single().attachmentUrl)
        assertNull(actual.nextSchoolDay)
    }
}
