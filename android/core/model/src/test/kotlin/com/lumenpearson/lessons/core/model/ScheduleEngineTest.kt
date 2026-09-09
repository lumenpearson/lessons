package com.lumenpearson.lessons.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The state engine is what the widget shows every minute of every school day,
 * so it gets the most direct test coverage in the project.
 */
class ScheduleEngineTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)
    private val tuesday: LocalDate = monday.plusDays(1)

    private fun lesson(
        index: Int,
        subject: String,
        from: String,
        to: String,
        cancelled: Boolean = false,
    ) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
        isCancelled = cancelled,
    )

    private val mondayLessons = listOf(
        lesson(1, "Алгебра", "08:30", "09:15"),
        lesson(2, "Физика", "09:25", "10:10"),
        lesson(3, "История", "10:25", "11:10"),
    )

    private fun day(
        date: LocalDate = monday,
        lessons: List<Lesson> = mondayLessons,
        events: List<SchoolEvent> = emptyList(),
        homework: List<HomeworkItem> = emptyList(),
        kind: DayKind = DayKind.NORMAL,
    ) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        kind = kind,
        lessons = lessons,
        events = events,
        homework = homework,
    )

    private fun timetable(vararg days: SchoolDay) = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А"),
        days = days.toList(),
    )

    private fun at(date: LocalDate, time: String): LocalDateTime =
        date.atTime(LocalTime.parse(time))

    @Test
    fun `before the first bell reports a countdown to it`() {
        val state = ScheduleEngine.stateAt(timetable(day()), at(monday, "07:50"))
        assertTrue(state is DayState.BeforeSchool)
        state as DayState.BeforeSchool
        assertEquals("Алгебра", state.next.subject)
        assertEquals(40, state.startsIn.toMinutes())
        assertEquals(at(monday, "08:30"), state.validUntil)
    }

    @Test
    fun `inside a lesson reports the lesson, the next one and progress`() {
        val state = ScheduleEngine.stateAt(timetable(day()), at(monday, "08:45"))
        assertTrue(state is DayState.InLesson)
        state as DayState.InLesson
        assertEquals("Алгебра", state.current.subject)
        assertEquals("Физика", state.next?.subject)
        assertEquals(30, state.endsIn.toMinutes())
        assertEquals(1f / 3f, state.progress, 0.01f)
    }

    @Test
    fun `a lesson boundary belongs to the break, not the lesson`() {
        // 09:15 is the end of lesson 1: the bell has rung, so it is a break.
        val state = ScheduleEngine.stateAt(timetable(day()), at(monday, "09:15"))
        assertTrue(state is DayState.OnBreak)
        state as DayState.OnBreak
        assertEquals("Алгебра", state.previous?.subject)
        assertEquals("Физика", state.next.subject)
        assertEquals(10, state.endsIn.toMinutes())
    }

    @Test
    fun `a canteen event during a break wins over the break`() {
        val canteen = SchoolEvent(
            title = "Обед",
            kind = EventKind.CANTEEN,
            startsAt = LocalTime.parse("10:10"),
            endsAt = LocalTime.parse("10:25"),
        )
        val state = ScheduleEngine.stateAt(
            timetable(day(events = listOf(canteen))),
            at(monday, "10:15"),
        )
        assertTrue(state is DayState.DuringEvent)
        state as DayState.DuringEvent
        assertEquals(EventKind.CANTEEN, state.event.kind)
        assertEquals("История", state.next?.subject)
    }

    @Test
    fun `an event overlapping a lesson only wins when it covers lessons`() {
        val assembly = SchoolEvent(
            title = "Линейка",
            kind = EventKind.EVENT,
            startsAt = LocalTime.parse("08:30"),
            endsAt = LocalTime.parse("09:15"),
            coversLesson = false,
        )
        val ignored = ScheduleEngine.stateAt(
            timetable(day(events = listOf(assembly))),
            at(monday, "08:45"),
        )
        assertTrue("A non-covering event must not hide a running lesson", ignored is DayState.InLesson)

        val covering = ScheduleEngine.stateAt(
            timetable(day(events = listOf(assembly.copy(coversLesson = true)))),
            at(monday, "08:45"),
        )
        assertTrue(covering is DayState.DuringEvent)
    }

    @Test
    fun `after the last bell it hands over tomorrow's homework`() {
        val tomorrow = day(
            date = tuesday,
            lessons = listOf(lesson(1, "Геометрия", "08:30", "09:15")),
            homework = listOf(HomeworkItem("Геометрия", "№ 12–15")),
        )
        val state = ScheduleEngine.stateAt(timetable(day(), tomorrow), at(monday, "15:00"))
        assertTrue(state is DayState.AfterSchool)
        state as DayState.AfterSchool
        assertEquals(LocalTime.parse("11:10"), state.finishedAt)
        assertEquals(tuesday, state.homeworkDay?.date)
        assertEquals("№ 12–15", state.homeworkDay?.homework?.first()?.text)
    }

    @Test
    fun `a weekend skips ahead to Monday's homework`() {
        val saturday = LocalDate.of(2026, 9, 12)
        val nextMonday = LocalDate.of(2026, 9, 14)
        val mondayAhead = day(
            date = nextMonday,
            lessons = listOf(lesson(1, "Алгебра", "08:30", "09:15")),
            homework = listOf(HomeworkItem("Алгебра", "Параграф 3")),
        )
        val state = ScheduleEngine.stateAt(
            timetable(day(date = saturday, lessons = emptyList()), mondayAhead),
            at(saturday, "12:00"),
        )
        assertTrue(state is DayState.DayOff)
        state as DayState.DayOff
        assertEquals(nextMonday, state.homeworkDay?.date)
    }

    @Test
    fun `a holiday is a day off even when the template has lessons`() {
        val state = ScheduleEngine.stateAt(
            timetable(day(lessons = emptyList(), kind = DayKind.HOLIDAY)),
            at(monday, "09:00"),
        )
        assertTrue(state is DayState.DayOff)
        assertEquals(DayKind.HOLIDAY, (state as DayState.DayOff).kind)
    }

    @Test
    fun `cancelled lessons are excluded from the timeline`() {
        val lessons = mondayLessons.toMutableList().also { it[0] = it[0].copy(isCancelled = true) }
        val state = ScheduleEngine.stateAt(timetable(day(lessons = lessons)), at(monday, "08:45"))
        // Lesson 1 is cancelled, so 08:45 sits before the day's real first lesson.
        assertTrue(state is DayState.BeforeSchool)
        assertEquals("Физика", (state as DayState.BeforeSchool).next.subject)
    }

    @Test
    fun `a date with no cached day reports NoData rather than guessing`() {
        val state = ScheduleEngine.stateAt(timetable(day()), at(tuesday, "09:00"))
        assertTrue(state is DayState.NoData)
    }

    @Test
    fun `nextTransition returns the upcoming bell`() {
        val tt = timetable(day())
        assertEquals(at(monday, "08:30"), ScheduleEngine.nextTransition(tt, at(monday, "07:50")))
        assertEquals(at(monday, "09:15"), ScheduleEngine.nextTransition(tt, at(monday, "08:45")))
        // Past the last bell the only remaining boundary is the day rollover.
        assertEquals(
            tuesday.atStartOfDay(),
            ScheduleEngine.nextTransition(tt, at(monday, "15:00")),
        )
    }

    @Test
    fun `remainingLessons drops the ones already finished`() {
        val remaining = ScheduleEngine.remainingLessons(day(), LocalTime.parse("09:30"))
        assertEquals(listOf("Физика", "История"), remaining.map { it.subject })
    }

    @Test
    fun `schoolDayAfter falls back to the server-resolved next school day`() {
        val faraway = day(
            date = LocalDate.of(2026, 11, 5),
            lessons = listOf(lesson(1, "Химия", "08:30", "09:15")),
        )
        val tt = Timetable(
            schoolClass = SchoolClassInfo(id = 1, name = "9А"),
            days = listOf(day(date = monday, lessons = emptyList())),
            nextSchoolDay = faraway,
        )
        assertEquals(faraway.date, tt.schoolDayAfter(monday)?.date)
        assertNull(tt.schoolDayAfter(LocalDate.of(2026, 12, 1)))
    }

    @Test
    fun `walking a whole school day never throws and always yields a state`() {
        val tt = timetable(day())
        var time = LocalTime.of(0, 0)
        repeat(24 * 60) {
            ScheduleEngine.stateAt(tt, monday.atTime(time))
            time = time.plusMinutes(1)
        }
    }
}

/** The schedule is the school's wall time, so "now" must be too. */
class SchoolZoneTest {

    private val vladivostok = SchoolClassInfo(id = 1, name = "9А", timeZoneId = "Asia/Vladivostok")

    private fun timetableIn(info: SchoolClassInfo) = Timetable(schoolClass = info)

    @Test
    fun `nowAtSchool uses the school's zone rather than the device's`() {
        // 2026-09-07T00:30Z is 10:30 in Vladivostok (UTC+10) but still the 6th
        // in Moscow - the date itself differs, not only the hour.
        val clock = java.time.Clock.fixed(
            java.time.Instant.parse("2026-09-07T00:30:00Z"),
            java.time.ZoneOffset.UTC,
        )
        val now = timetableIn(vladivostok).nowAtSchool(clock)

        assertEquals(LocalDate.of(2026, 9, 7), now.toLocalDate())
        assertEquals(LocalTime.of(10, 30), now.toLocalTime())

        val moscow = timetableIn(vladivostok.copy(timeZoneId = "Europe/Moscow")).nowAtSchool(clock)
        assertEquals(LocalTime.of(3, 30), moscow.toLocalTime())
    }

    @Test
    fun `an unknown zone falls back instead of throwing`() {
        val broken = SchoolClassInfo(id = 1, name = "9А", timeZoneId = "Mars/Olympus")
        assertEquals(java.time.ZoneId.systemDefault(), broken.zone)
    }

    @Test
    fun `every Russian school zone the server offers is resolvable here`() {
        val zones = listOf(
            "Europe/Kaliningrad", "Europe/Moscow", "Europe/Samara", "Asia/Yekaterinburg",
            "Asia/Omsk", "Asia/Krasnoyarsk", "Asia/Irkutsk", "Asia/Yakutsk",
            "Asia/Vladivostok", "Asia/Magadan", "Asia/Kamchatka",
        )
        zones.forEach { id ->
            assertEquals(id, SchoolClassInfo(id = 1, name = "9А", timeZoneId = id).zone.id)
        }
    }
}
