package com.lumenpearson.lessons.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The awkward payloads a real server can produce: lessons that overlap, arrive
 * out of order, have no duration, or are all cancelled.
 *
 * These complement [ScheduleEngineTest], which covers the well-formed day. The
 * engine runs on the widget's tick with no supervision, so "never throws" is as
 * much a requirement here as "gives the right answer".
 */
class ScheduleEngineEdgeCasesTest {

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

    private fun day(
        date: LocalDate = monday,
        lessons: List<Lesson> = emptyList(),
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

    /** Every minute of a day, asserting only that nothing blows up. */
    private fun walkWholeDay(tt: Timetable, date: LocalDate) {
        var time = LocalTime.MIDNIGHT
        repeat(24 * 60) {
            ScheduleEngine.stateAt(tt, date.atTime(time))
            ScheduleEngine.nextTransition(tt, date.atTime(time))
            time = time.plusMinutes(1)
        }
    }

    @Test
    fun `a day whose lessons are all cancelled is a day off, not an empty timeline`() {
        val cancelledDay = day(
            lessons = listOf(
                lesson(1, "Алгебра", "08:30", "09:15", cancelled = true),
                lesson(2, "Физика", "09:25", "10:10", cancelled = true),
            ),
        )
        val tomorrow = day(
            date = tuesday,
            lessons = listOf(lesson(1, "Геометрия", "08:30", "09:15")),
            homework = listOf(HomeworkItem("Геометрия", "№ 1")),
        )

        assertFalse(cancelledDay.hasLessons)
        assertNull(cancelledDay.firstLesson)

        // 09:00 would sit inside the first lesson if it were happening.
        val state = ScheduleEngine.stateAt(timetable(cancelledDay, tomorrow), at(monday, "09:00"))
        assertTrue("all lessons cancelled must read as a day off", state is DayState.DayOff)
        state as DayState.DayOff
        assertEquals(DayKind.NORMAL, state.kind)
        // The homework hand-off still works, which is what makes the day useful.
        assertEquals(tuesday, state.homeworkDay?.date)
        assertEquals(tuesday.atStartOfDay(), state.validUntil)
    }

    @Test
    fun `overlapping lessons resolve to the one containing the moment`() {
        // A substitution that was entered without clearing the lesson it replaces.
        val overlapping = listOf(
            lesson(1, "Алгебра", "08:30", "10:00"),
            lesson(2, "Физика", "09:00", "09:30"),
        )
        val tt = timetable(day(lessons = overlapping))

        val state = ScheduleEngine.stateAt(tt, at(monday, "09:10"))
        assertTrue(state is DayState.InLesson)
        // Sorted by start time, so the earlier-starting lesson wins the moment.
        assertEquals("Алгебра", (state as DayState.InLesson).current.subject)

        walkWholeDay(tt, monday)
    }

    @Test
    fun `lessons arriving out of order are placed on the timeline in time order`() {
        val shuffled = listOf(
            lesson(3, "История", "10:25", "11:10"),
            lesson(1, "Алгебра", "08:30", "09:15"),
            lesson(2, "Физика", "09:25", "10:10"),
        )
        val subject = day(lessons = shuffled)

        assertEquals(
            listOf("Алгебра", "Физика", "История"),
            subject.activeLessons.map { it.subject },
        )
        assertEquals("Алгебра", subject.firstLesson?.subject)
        assertEquals("История", subject.lastLesson?.subject)

        val state = ScheduleEngine.stateAt(timetable(subject), at(monday, "09:20"))
        assertTrue(state is DayState.OnBreak)
        state as DayState.OnBreak
        assertEquals("Алгебра", state.previous?.subject)
        assertEquals("Физика", state.next.subject)
    }

    @Test
    fun `a zero length lesson never reports itself as current`() {
        val tt = timetable(
            day(
                lessons = listOf(
                    lesson(1, "Линейка", "08:30", "08:30"),
                    lesson(2, "Алгебра", "09:00", "09:45"),
                ),
            ),
        )

        // 08:30 is both the start and the end of lesson 1: the bell has rung.
        val state = ScheduleEngine.stateAt(tt, at(monday, "08:30"))
        assertTrue(state is DayState.OnBreak)
        assertEquals("Алгебра", (state as DayState.OnBreak).next.subject)

        walkWholeDay(tt, monday)
    }

    @Test
    fun `a lesson that ends before it starts never throws`() {
        // Nothing validates this on the wire, and the widget must survive it.
        val tt = timetable(
            day(
                lessons = listOf(
                    lesson(1, "Алгебра", "08:30", "09:15"),
                    lesson(2, "Физика", "10:00", "07:00"),
                ),
            ),
        )

        walkWholeDay(tt, monday)

        // Documents today's behaviour: "the last lesson" is the last to start,
        // so a reversed lesson makes the day look finished at its bogus end.
        val state = ScheduleEngine.stateAt(tt, at(monday, "09:30"))
        assertTrue(state is DayState.AfterSchool)
        assertEquals(LocalTime.parse("07:00"), (state as DayState.AfterSchool).finishedAt)
    }

    @Test
    fun `an event on a day with no lessons is still reported`() {
        val excursion = SchoolEvent(
            title = "Экскурсия",
            kind = EventKind.TRIP,
            startsAt = LocalTime.parse("10:00"),
            endsAt = LocalTime.parse("13:00"),
        )
        val tt = timetable(day(events = listOf(excursion)))

        val during = ScheduleEngine.stateAt(tt, at(monday, "11:00"))
        assertTrue(during is DayState.DuringEvent)
        during as DayState.DuringEvent
        assertEquals(EventKind.TRIP, during.event.kind)
        assertNull(during.next)
        assertEquals(at(monday, "13:00"), during.validUntil)

        // Before it starts the day still reads as a day off - there are no
        // lessons - but the widget is told to wake up for the event.
        assertTrue(ScheduleEngine.stateAt(tt, at(monday, "09:00")) is DayState.DayOff)
        assertEquals(at(monday, "10:00"), ScheduleEngine.nextTransition(tt, at(monday, "09:00")))
    }

    @Test
    fun `nextTransition ignores cancelled lessons but not events`() {
        val tt = timetable(
            day(
                lessons = listOf(
                    lesson(1, "Алгебра", "08:30", "09:15", cancelled = true),
                    lesson(2, "Физика", "09:25", "10:10"),
                ),
                events = listOf(
                    SchoolEvent(
                        title = "Обед",
                        kind = EventKind.CANTEEN,
                        startsAt = LocalTime.parse("08:45"),
                        endsAt = LocalTime.parse("09:00"),
                    ),
                ),
            ),
        )

        // 08:30 belongs to the cancelled lesson, so the next boundary is the
        // canteen event rather than the bell that is not going to ring.
        assertEquals(at(monday, "08:45"), ScheduleEngine.nextTransition(tt, at(monday, "08:00")))
    }

    @Test
    fun `remainingLessons drops cancelled lessons as well as finished ones`() {
        val subject = day(
            lessons = listOf(
                lesson(1, "Алгебра", "08:30", "09:15"),
                lesson(2, "Физика", "09:25", "10:10", cancelled = true),
                lesson(3, "История", "10:25", "11:10"),
            ),
        )

        assertEquals(
            listOf("История"),
            ScheduleEngine.remainingLessons(subject, LocalTime.parse("09:30")).map { it.subject },
        )
        assertTrue(ScheduleEngine.remainingLessons(subject, LocalTime.parse("23:00")).isEmpty())
    }

    @Test
    fun `schoolDayAfter skips days whose lessons are all cancelled`() {
        val allCancelled = day(
            date = tuesday,
            lessons = listOf(lesson(1, "Алгебра", "08:30", "09:15", cancelled = true)),
        )
        val real = day(
            date = monday.plusDays(2),
            lessons = listOf(lesson(1, "Химия", "08:30", "09:15")),
        )

        val tt = timetable(day(lessons = emptyList()), allCancelled, real)
        assertEquals(real.date, tt.schoolDayAfter(monday)?.date)
    }

    @Test
    fun `schoolDayAfter prefers a cached day over the server's lookahead`() {
        val cached = day(
            date = tuesday,
            lessons = listOf(lesson(1, "Химия", "08:30", "09:15")),
        )
        val lookahead = day(
            date = LocalDate.of(2026, 11, 5),
            lessons = listOf(lesson(1, "Биология", "08:30", "09:15")),
        )
        val tt = Timetable(
            schoolClass = SchoolClassInfo(id = 1, name = "9А"),
            days = listOf(day(lessons = emptyList()), cached),
            nextSchoolDay = lookahead,
        )

        assertEquals(cached.date, tt.schoolDayAfter(monday)?.date)
        // Past the cached window the lookahead takes over again.
        assertEquals(lookahead.date, tt.schoolDayAfter(tuesday)?.date)
    }

    @Test
    fun `a timetable with no days at all reports NoData and midnight`() {
        val empty = Timetable(schoolClass = SchoolClassInfo(id = 1, name = "9А"))

        val state = ScheduleEngine.stateAt(empty, at(monday, "09:00"))
        assertTrue(state is DayState.NoData)
        assertEquals(monday, (state as DayState.NoData).date)
        assertNull(state.validUntil)
        assertEquals(tuesday.atStartOfDay(), ScheduleEngine.nextTransition(empty, at(monday, "09:00")))
        assertNull(empty.schoolDayAfter(monday))
    }

    @Test
    fun `unknown, empty and oddly cased wire kinds fall back instead of throwing`() {
        assertEquals(DayKind.NORMAL, DayKind.fromWire("quarantine"))
        assertEquals(DayKind.NORMAL, DayKind.fromWire(""))
        assertEquals(DayKind.HOLIDAY, DayKind.fromWire("holiday"))
        assertEquals(DayKind.HOLIDAY, DayKind.fromWire("HoLiDaY"))
        assertEquals(DayKind.REMOTE, DayKind.fromWire("REMOTE"))

        assertEquals(EventKind.EVENT, EventKind.fromWire("olympiad"))
        assertEquals(EventKind.EVENT, EventKind.fromWire(""))
        assertEquals(EventKind.CANTEEN, EventKind.fromWire("canteen"))
        assertEquals(EventKind.MEETING, EventKind.fromWire("MeEtInG"))

        // Every value the server documents must round-trip, not fall back.
        listOf("normal", "holiday", "shortened", "remote").forEach { wire ->
            assertEquals(wire.uppercase(), DayKind.fromWire(wire).name)
        }
        listOf("event", "canteen", "exam", "trip", "meeting").forEach { wire ->
            assertEquals(wire.uppercase(), EventKind.fromWire(wire).name)
        }
    }

    @Test
    fun `a blank or padded zone id falls back to the device zone`() {
        assertEquals(ZoneId.systemDefault(), SchoolClassInfo(1, "9А", timeZoneId = "").zone)
        assertEquals(ZoneId.systemDefault(), SchoolClassInfo(1, "9А", timeZoneId = "  ").zone)
        assertEquals(ZoneId.systemDefault(), SchoolClassInfo(1, "9А", timeZoneId = "Moscow").zone)

        // ...and nowAtSchool still answers rather than propagating the failure.
        val clock = Clock.fixed(Instant.parse("2026-09-07T00:30:00Z"), ZoneOffset.UTC)
        val broken = Timetable(schoolClass = SchoolClassInfo(1, "9А", timeZoneId = "Mars/Olympus"))
        assertEquals(
            LocalDateTime.ofInstant(Instant.parse("2026-09-07T00:30:00Z"), ZoneId.systemDefault()),
            broken.nowAtSchool(clock),
        )
    }

    @Test
    fun `an event that covers a lesson hands the next lesson over correctly`() {
        val assembly = SchoolEvent(
            title = "Линейка",
            kind = EventKind.EVENT,
            startsAt = LocalTime.parse("08:30"),
            endsAt = LocalTime.parse("09:15"),
            coversLesson = true,
        )
        val tt = timetable(
            day(
                lessons = listOf(
                    lesson(1, "Алгебра", "08:30", "09:15"),
                    lesson(2, "Физика", "09:25", "10:10"),
                ),
                events = listOf(assembly),
            ),
        )

        val state = ScheduleEngine.stateAt(tt, at(monday, "08:45"))
        assertTrue(state is DayState.DuringEvent)
        state as DayState.DuringEvent
        assertEquals("Физика", state.next?.subject)
        assertEquals(1f / 3f, state.progress, 0.01f)
        assertEquals(30, state.endsIn.toMinutes())
    }

    @Test
    fun `homeworkFocus only exposes homework in the states that carry it`() {
        val tomorrow = day(
            date = tuesday,
            lessons = listOf(lesson(1, "Геометрия", "08:30", "09:15")),
            homework = listOf(HomeworkItem("Геометрия", "№ 12")),
        )
        val today = day(lessons = listOf(lesson(1, "Алгебра", "08:30", "09:15")))
        val tt = timetable(today, tomorrow)

        assertEquals(tuesday, ScheduleEngine.stateAt(tt, at(monday, "15:00")).homeworkFocus?.date)
        assertNull(ScheduleEngine.stateAt(tt, at(monday, "08:45")).homeworkFocus)
        assertNull(ScheduleEngine.stateAt(tt, at(monday, "07:00")).homeworkFocus)
    }
}
