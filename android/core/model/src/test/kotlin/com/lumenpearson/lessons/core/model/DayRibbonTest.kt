package com.lumenpearson.lessons.core.model

import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A day as a ribbon: everything in it, in order, with the gaps made visible.
 *
 * The hour ruler draws a day by position, which turns a forty-minute gap into
 * forty minutes of blank screen. A ribbon makes the gap a row — «Перемена, 20
 * минут» — so the thing somebody actually plans around is something rather
 * than an absence to measure with two clocks.
 */
class DayRibbonTest {

    private val date = LocalDate.parse("2026-09-14")

    private fun lesson(
        index: Int,
        from: String,
        to: String,
        subject: String = "Алгебра",
        cancelled: Boolean = false,
    ) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
        isCancelled = cancelled,
    )

    private fun event(from: String, to: String, title: String = "Экскурсия") = SchoolEvent(
        title = title,
        kind = EventKind.TRIP,
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
    )

    private fun day(
        lessons: List<Lesson> = emptyList(),
        events: List<SchoolEvent> = emptyList(),
    ) = SchoolDay(date = date, weekday = 1, lessons = lessons, events = events)

    @Test
    fun `a gap between two lessons becomes a row of its own`() {
        val ribbon = ribbonOf(
            day(listOf(lesson(1, "08:30", "09:15"), lesson(2, "09:25", "10:10"))),
        )

        val gap = ribbon.entries.filterIsInstance<RibbonEntry.OfBreak>().single()
        assertEquals(LocalTime.parse("09:15"), gap.startsAt)
        assertEquals(LocalTime.parse("09:25"), gap.endsAt)
        assertEquals(Duration.ofMinutes(10), gap.length)
    }

    @Test
    fun `back to back lessons have no break between them`() {
        val ribbon = ribbonOf(
            day(listOf(lesson(1, "08:30", "09:15"), lesson(2, "09:15", "10:00"))),
        )
        assertTrue(ribbon.entries.none { it is RibbonEntry.OfBreak })
    }

    @Test
    fun `overlapping bells do not produce a break of negative length`() {
        // A bell schedule whose rows overlap is somebody's typo, and a break
        // drawn from it reads «-5 минут» — a number that cannot be true and
        // that nobody would know to report.
        val ribbon = ribbonOf(
            day(listOf(lesson(1, "08:30", "09:20"), lesson(2, "09:15", "10:00"))),
        )
        assertTrue(ribbon.entries.none { it is RibbonEntry.OfBreak })
    }

    @Test
    fun `nothing before the first lesson or after the last is called a break`() {
        val ribbon = ribbonOf(day(listOf(lesson(1, "08:30", "09:15"))))
        // One lesson means no pair, so no gap: the evening is not a break.
        assertTrue(ribbon.entries.none { it is RibbonEntry.OfBreak })
        assertEquals(1, ribbon.entries.size)
    }

    @Test
    fun `a cancelled lesson is not on the ribbon, and leaves no gap behind it`() {
        // «What is happening», not «what the timetable says». The struck-through
        // row belongs on the timetable view; here it would be a countdown to
        // something that is not going to happen.
        val ribbon = ribbonOf(
            day(
                listOf(
                    lesson(1, "08:30", "09:15"),
                    lesson(2, "09:25", "10:10", cancelled = true),
                    lesson(3, "10:20", "11:05"),
                ),
            ),
        )

        assertEquals(2, ribbon.entries.count { it is RibbonEntry.OfLesson })
        // One gap, from the end of the first to the start of the third — the
        // cancelled lesson's hour is part of the gap rather than a hole in it.
        val gap = ribbon.entries.filterIsInstance<RibbonEntry.OfBreak>().single()
        assertEquals(LocalTime.parse("09:15"), gap.startsAt)
        assertEquals(LocalTime.parse("10:20"), gap.endsAt)
    }

    @Test
    fun `an event that starts with a lesson is listed before it`() {
        // The widget's own rule, and for its reason: an event starting exactly
        // when a lesson does is the thing that replaced it.
        val ribbon = ribbonOf(
            day(
                lessons = listOf(lesson(1, "08:30", "09:15")),
                events = listOf(event("08:30", "09:15")),
            ),
        )
        assertTrue(ribbon.entries.first() is RibbonEntry.OfEvent)
    }

    @Test
    fun `progress is nothing before, a fraction during, and whole after`() {
        val entry = RibbonEntry.OfLesson(lesson(1, "08:30", "09:30"))

        val before = progressOf(entry, LocalTime.parse("08:00"))
        assertEquals(0f, before.fraction, 0.001f)
        assertEquals(Duration.ofMinutes(60), before.remaining)
        assertFalse(before.hasStarted)

        val middle = progressOf(entry, LocalTime.parse("09:00"))
        assertEquals(0.5f, middle.fraction, 0.001f)
        assertEquals(Duration.ofMinutes(30), middle.elapsed)
        assertEquals(Duration.ofMinutes(30), middle.remaining)
        assertTrue(middle.isRunning)

        val after = progressOf(entry, LocalTime.parse("10:00"))
        assertEquals(1f, after.fraction, 0.001f)
        assertEquals(Duration.ZERO, after.remaining)
        assertTrue(after.isOver)
        assertFalse(after.isRunning)
    }

    @Test
    fun `an entry of no length reads as over rather than dividing by zero`() {
        // Equal times are a data error. Answering «0 %» for ever would draw a
        // bar that never moves; answering «over» at least matches the clock.
        val entry = RibbonEntry.OfLesson(lesson(1, "08:30", "08:30"))
        for (at in listOf("08:00", "08:30", "09:00")) {
            val progress = progressOf(entry, LocalTime.parse(at))
            assertTrue("at $at", progress.isOver)
            assertEquals("at $at", 1f, progress.fraction, 0.001f)
            assertEquals("at $at", Duration.ZERO, progress.remaining)
        }
    }

    @Test
    fun `an entry that ends before it starts never counts down to it`() {
        // A bell row typed backwards. Writing the zero-length guard and then
        // proving it red found this: the guard was unreachable, because the
        // «already over» check above caught the equal case first — and the
        // reversed case slipped past both and produced a *negative* remaining,
        // which draws as «осталось -30 мин» and is a number nobody would know
        // to report.
        val entry = RibbonEntry.OfLesson(lesson(1, "10:00", "09:00"))
        for (at in listOf("08:00", "09:30", "11:00")) {
            val progress = progressOf(entry, LocalTime.parse(at))
            assertTrue("at $at is not counted down to", progress.isOver)
            assertEquals("at $at", Duration.ZERO, progress.remaining)
            assertFalse("at $at", progress.remaining < Duration.ZERO)
        }
    }

    @Test
    fun `the current entry is the one being lived through, ends excluded`() {
        val ribbon = ribbonOf(
            day(listOf(lesson(1, "08:30", "09:15"), lesson(2, "09:25", "10:10"))),
        )

        assertTrue(ribbon.currentAt(LocalTime.parse("08:45")) is RibbonEntry.OfLesson)
        // 09:15 is the end of the first and the start of the break, and a
        // moment belongs to what it is starting rather than what it just left.
        assertTrue(ribbon.currentAt(LocalTime.parse("09:15")) is RibbonEntry.OfBreak)
        assertNull(ribbon.currentAt(LocalTime.parse("07:00")))
        assertNull(ribbon.currentAt(LocalTime.parse("23:00")))
    }

    @Test
    fun `the focus is what is running, else what is next, else nothing`() {
        val ribbon = ribbonOf(day(listOf(lesson(1, "08:30", "09:15"))))

        assertEquals(
            LocalTime.parse("08:30"),
            ribbon.focusAt(LocalTime.parse("07:00"))?.startsAt,
        )
        assertEquals(
            LocalTime.parse("08:30"),
            ribbon.focusAt(LocalTime.parse("08:45"))?.startsAt,
        )
        // Once the day is done there is nothing to return to, and saying «the
        // last lesson» would claim it is now.
        assertNull(ribbon.focusAt(LocalTime.parse("22:00")))
    }

    @Test
    fun `what is left drops the gaps and anything already finished`() {
        val ribbon = ribbonOf(
            day(listOf(lesson(1, "08:30", "09:15"), lesson(2, "09:25", "10:10"))),
        )

        val left = ribbon.remaining(LocalTime.parse("09:20"))
        assertEquals(1, left.size)
        assertEquals(LocalTime.parse("09:25"), left.single().startsAt)
    }

    @Test
    fun `a day nothing is known about is an empty ribbon rather than a crash`() {
        assertEquals(emptyList<RibbonEntry>(), ribbonOf(null).entries)
        assertNull(ribbonOf(null).currentAt(LocalTime.parse("09:00")))
        assertNull(ribbonOf(null).focusAt(LocalTime.parse("09:00")))
    }

    @Test
    fun `the ribbon is in order, whatever order the day arrived in`() {
        val ribbon = ribbonOf(
            day(
                lessons = listOf(lesson(2, "09:25", "10:10"), lesson(1, "08:30", "09:15")),
                events = listOf(event("12:00", "13:00", "Столовая")),
            ),
        )
        val starts = ribbon.entries.map { it.startsAt }
        assertEquals(starts.sorted(), starts)
    }
}
