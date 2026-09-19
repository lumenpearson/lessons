package com.lumenpearson.lessons.widget.ui

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.ScheduleEngine
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The «Дальше» column of the 4x2 layout, and the rule that decides what goes in it.
 *
 * It is the one rung that draws its own list of what is coming rather than the
 * merged timeline, so it is the one that can disagree with the headline above
 * it — and it did. The list used to be "everything still to come, minus the
 * lesson currently running", and "currently running" was recognised only under
 * [DayState.InLesson]: during an assembly that replaces the third lesson the
 * third lesson was still listed, under a heading that says "next", at a time
 * already in the past. The same widget one rung larger said «Химия», because
 * the stacked layouts ask the state.
 *
 * So the column asks the state too. These tests pin that the two can no longer
 * differ, on every branch a `DayState` has.
 */
class UpcomingLessonsTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 14)

    private fun lesson(index: Int, subject: String, from: String, to: String) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
    )

    private val algebra = lesson(1, "Алгебра", "08:30", "09:15")
    private val physics = lesson(2, "Физика", "09:25", "10:10")
    private val chemistry = lesson(3, "Химия", "10:20", "11:05")

    /** «Собрание» in the assembly hall, in the slot «Физика» would have had. */
    private fun assembly(coversLesson: Boolean) = SchoolEvent(
        title = "Собрание",
        kind = EventKind.MEETING,
        startsAt = LocalTime.parse("09:25"),
        endsAt = LocalTime.parse("10:10"),
        coversLesson = coversLesson,
    )

    private fun day(events: List<SchoolEvent> = emptyList()) = SchoolDay(
        date = monday,
        weekday = 1,
        lessons = listOf(algebra, physics, chemistry),
        events = events,
    )

    private fun at(time: String): LocalDateTime = monday.atTime(LocalTime.parse(time))

    private fun stateAt(day: SchoolDay, time: String): DayState =
        ScheduleEngine.stateAt(day, at(time), nextHomeworkDay = { null })

    /** What the 4x2 layout asks for: `size.timelineRows` is 2 on MEDIUM. */
    private fun column(state: DayState, day: SchoolDay?, time: String, limit: Int = 2) =
        upcomingLessons(state, day, at(time), limit = limit)

    /**
     * The defect this file exists for.
     *
     * An assembly marked as covering lessons runs 09:25–10:10, where «Физика»
     * is. At 09:40 «Физика» is not what comes next — it is what is not
     * happening — and 09:25 is already gone.
     */
    @Test
    fun `an assembly that covers a lesson does not list the lesson it replaced`() {
        val day = day(events = listOf(assembly(coversLesson = true)))
        val state = stateAt(day, "09:40")

        assertTrue("expected DuringEvent, got $state", state is DayState.DuringEvent)
        assertEquals(listOf("Химия"), column(state, day, "09:40").map { it.subject })
    }

    /**
     * The headline and the column are one sentence, so they read the same lesson.
     *
     * This is the assertion the fix is really about: whatever the state says
     * comes next is what the column starts with, in every state that has an
     * answer. Checked across the day rather than at one moment, because the
     * branch that was wrong was the one nobody looked at.
     */
    @Test
    fun `the column always starts at the lesson the state calls next`() {
        val plain = day()
        val covered = day(events = listOf(assembly(coversLesson = true)))
        val uncovered = day(events = listOf(assembly(coversLesson = false)))

        listOf(plain, covered, uncovered).forEach { day ->
            var time = LocalTime.parse("07:00")
            while (time < LocalTime.parse("12:00")) {
                val moment = monday.atTime(time)
                val state = ScheduleEngine.stateAt(day, moment, nextHomeworkDay = { null })
                val first = upcomingLessons(state, day, moment, limit = 2).firstOrNull()

                assertEquals(
                    "at $time the column and the headline disagree about «Дальше»",
                    nextLessonOf(state),
                    first,
                )
                time = time.plusMinutes(1)
            }
        }
    }

    /** `BeforeSchool`: the whole day is still to come, first lesson first. */
    @Test
    fun `before the first bell the column starts at the first lesson`() {
        val day = day()
        val state = stateAt(day, "07:50")

        assertTrue(state is DayState.BeforeSchool)
        assertEquals(listOf("Алгебра", "Физика"), column(state, day, "07:50").map { it.subject })
    }

    /** `InLesson`: the running lesson is the headline, so it is not also "next". */
    @Test
    fun `the lesson being sat in is not listed as what comes next`() {
        val day = day()
        val state = stateAt(day, "08:45")

        assertTrue(state is DayState.InLesson)
        assertEquals(listOf("Физика", "Химия"), column(state, day, "08:45").map { it.subject })
    }

    /** `OnBreak`: the lesson the break leads to, and what follows it. */
    @Test
    fun `on a break the column starts at the lesson the break leads to`() {
        val day = day()
        val state = stateAt(day, "09:20")

        assertTrue(state is DayState.OnBreak)
        assertEquals(listOf("Физика", "Химия"), column(state, day, "09:20").map { it.subject })
    }

    /**
     * `DuringEvent` that does not cover a lesson — a canteen slot on a break.
     *
     * Nothing was replaced, so the lesson after it is exactly what it would
     * have been without the event.
     */
    @Test
    fun `an event that covers nothing leaves the next lesson where it was`() {
        val canteen = SchoolEvent(
            title = "Обед",
            kind = EventKind.CANTEEN,
            startsAt = LocalTime.parse("09:16"),
            endsAt = LocalTime.parse("09:24"),
        )
        val day = day(events = listOf(canteen))
        val state = stateAt(day, "09:20")

        assertTrue(state is DayState.DuringEvent)
        assertEquals(listOf("Физика", "Химия"), column(state, day, "09:20").map { it.subject })
    }

    /** The last lesson of the day has nothing behind it, and says so. */
    @Test
    fun `inside the last lesson the column is empty`() {
        val day = day()
        val state = stateAt(day, "10:40")

        assertTrue(state is DayState.InLesson)
        assertEquals(emptyList<Lesson>(), column(state, day, "10:40"))
    }

    /**
     * The three states with no next lesson at all.
     *
     * None of them reaches the 4x2 layout today — `AfterSchool` and `DayOff`
     * are homework-primary and `NoData` is drawn as the empty body — but the
     * rule is total on purpose, so that a layout which starts asking gets an
     * answer rather than the rest of a day that is over.
     */
    @Test
    fun `a state with no next lesson lists nothing`() {
        val day = day()

        val after = stateAt(day, "11:30")
        assertTrue(after is DayState.AfterSchool)
        assertEquals(null, nextLessonOf(after))
        assertEquals(emptyList<Lesson>(), column(after, day, "11:30"))

        val off = DayState.DayOff(
            date = monday,
            kind = DayKind.HOLIDAY,
            note = null,
            homeworkDay = null,
            validUntil = null,
        )
        assertEquals(null, nextLessonOf(off))
        assertEquals(emptyList<Lesson>(), column(off, day, "09:00"))

        val nothing = DayState.NoData(monday)
        assertEquals(null, nextLessonOf(nothing))
        assertEquals(emptyList<Lesson>(), column(nothing, day, "09:00"))
    }

    /** A day the cache does not hold cannot contradict the state it has none of. */
    @Test
    fun `no cached day lists nothing`() {
        val state = stateAt(day(), "08:45")

        assertEquals(emptyList<Lesson>(), column(state, null, "08:45"))
    }

    /** The column takes what the size class can draw and no more. */
    @Test
    fun `the limit is honoured`() {
        val day = day()
        val state = stateAt(day, "07:50")

        assertEquals(1, column(state, day, "07:50", limit = 1).size)
        assertEquals(3, column(state, day, "07:50", limit = 6).size)
    }
}
