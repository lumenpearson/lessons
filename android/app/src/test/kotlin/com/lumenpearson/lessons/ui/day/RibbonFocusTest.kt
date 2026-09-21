package com.lumenpearson.lessons.ui.day

import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.RibbonEntry
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.ribbonOf
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where «вернуться к текущему» lands.
 *
 * The one piece of this screen that can be wrong without looking wrong: a
 * button that scrolls one row off reads as a scroll that did not quite finish,
 * and nobody reports it. The rule it has to keep is that the row it lands on is
 * the row the ribbon is highlighting — which is why both read [ribbonOf] rather
 * than each counting the day for themselves.
 */
class RibbonFocusTest {

    private val date = LocalDate.parse("2026-09-14")

    private fun lesson(index: Int, from: String, to: String) = Lesson(
        index = index,
        subject = "Алгебра",
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
    )

    private fun day(lessons: List<Lesson>, events: List<SchoolEvent> = emptyList()) =
        SchoolDay(date = date, weekday = 1, lessons = lessons, events = events)

    private val threeLessons = day(
        listOf(
            lesson(1, "08:30", "09:15"),
            lesson(2, "09:25", "10:10"),
            lesson(3, "10:20", "11:05"),
        ),
    )

    @Test
    fun `during a lesson it is that lesson's own row`() {
        val ribbon = ribbonOf(threeLessons)

        val index = ribbonFocusIndex(ribbon, LocalTime.parse("09:30"))

        assertEquals(RibbonEntry.OfLesson(lesson(2, "09:25", "10:10")), ribbon.entries[index!!])
    }

    @Test
    fun `during a break it is the break, not the lesson on either side of it`() {
        // The break is a row of its own here, and it is the row somebody is
        // living through. Landing on the next lesson instead would skip past
        // the thing they were looking for.
        val ribbon = ribbonOf(threeLessons)

        val index = ribbonFocusIndex(ribbon, LocalTime.parse("09:20"))

        assertEquals(
            RibbonEntry.OfBreak(LocalTime.parse("09:15"), LocalTime.parse("09:25")),
            ribbon.entries[index!!],
        )
    }

    @Test
    fun `before the first bell it is the first row of the day`() {
        val ribbon = ribbonOf(threeLessons)

        assertEquals(0, ribbonFocusIndex(ribbon, LocalTime.parse("07:00")))
    }

    @Test
    fun `once the day is over there is nothing to return to`() {
        // Not «the last lesson»: scrolling to it would claim it is now.
        val ribbon = ribbonOf(threeLessons)

        assertNull(ribbonFocusIndex(ribbon, LocalTime.parse("22:00")))
    }

    @Test
    fun `a day that is not today has no now on it`() {
        // The view model hands over a null clock for every date but today, and
        // scrolling Thursday to the row that would be running if it were today
        // is the kind of helpfulness that makes somebody check the date twice.
        val ribbon = ribbonOf(threeLessons)

        assertNull(ribbonFocusIndex(ribbon, null))
    }

    @Test
    fun `an empty day answers nothing rather than the first row it does not have`() {
        assertNull(ribbonFocusIndex(ribbonOf(day(emptyList())), LocalTime.parse("09:00")))
        assertNull(ribbonFocusIndex(ribbonOf(null), LocalTime.parse("09:00")))
    }

    @Test
    fun `an event sharing a lesson's hour is the row that is focused`() {
        // The ribbon lists the event first, for the reason the widget does: an
        // event starting exactly when a lesson does is the thing that replaced
        // it. The button has to agree, or it lands on a lesson that is not
        // happening.
        val ribbon = ribbonOf(
            day(
                lessons = listOf(lesson(1, "08:30", "09:15")),
                events = listOf(
                    SchoolEvent(
                        title = "Экскурсия",
                        kind = EventKind.TRIP,
                        startsAt = LocalTime.parse("08:30"),
                        endsAt = LocalTime.parse("09:15"),
                    ),
                ),
            ),
        )

        val index = ribbonFocusIndex(ribbon, LocalTime.parse("08:45"))

        assertEquals(0, index)
        assert(ribbon.entries[index!!] is RibbonEntry.OfEvent)
    }
}
