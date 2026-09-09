package com.lumenpearson.lessons.widget.tick

import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The widget's wake-up schedule. This is what decides whether the home screen
 * says "Урок" a moment after the bell has already rung, so the boundary cases
 * are covered explicitly rather than assumed.
 */
class TickCadenceTest {

    private val day: LocalDate = LocalDate.of(2026, 9, 7)

    private fun at(time: String): LocalDateTime = day.atTime(LocalTime.parse(time))

    private val algebra = Lesson(
        index = 1,
        subject = "Алгебра",
        startsAt = LocalTime.parse("08:30"),
        endsAt = LocalTime.parse("09:15"),
    )

    private fun inLesson(endsIn: Duration, validUntil: LocalDateTime) = DayState.InLesson(
        current = algebra,
        next = null,
        endsIn = endsIn,
        progress = 0.5f,
        validUntil = validUntil,
    )

    // ---- the precision case the whole thing exists for ------------------

    @Test
    fun `a bell closer than the countdown floor is not postponed past itself`() {
        val now = at("09:14:55")
        val bell = at("09:15:00") // five seconds away, inside MIN_LEAD

        val tick = TickCadence.nextWakeUp(
            state = inLesson(Duration.ofSeconds(5), bell),
            transition = bell,
            now = now,
        )

        assertTrue("the bell must be recognised as a boundary", tick.isBoundary)
        assertEquals("the alarm must land on the bell, not after it", bell, tick.at)
        assertFalse("scheduling after the bell would show a stale state", tick.at.isAfter(bell))
    }

    @Test
    fun `a bell one second away is still scheduled exactly`() {
        val bell = at("09:15:00")
        val tick = TickCadence.nextWakeUp(
            state = inLesson(Duration.ofSeconds(1), bell),
            transition = bell,
            now = at("09:14:59"),
        )
        assertEquals(bell, tick.at)
        assertTrue(tick.isBoundary)
    }

    @Test
    fun `a countdown refresh is still held to the floor`() {
        // No boundary at all, so whatever is chosen is a countdown tick and the
        // loop guard must apply.
        val now = at("10:00:00")
        val tick = TickCadence.nextWakeUp(state = null, transition = null, now = now)

        assertFalse(tick.isBoundary)
        assertTrue(
            "a non-boundary tick must be at least MIN_LEAD away",
            !tick.at.isBefore(now.plus(TickCadence.MIN_LEAD)),
        )
    }

    // ---- cadence tiers ---------------------------------------------------

    @Test
    fun `under ten minutes left the countdown refreshes every minute`() {
        val now = at("09:07:00")
        val next = TickCadence.nextTick(inLesson(Duration.ofMinutes(8), at("09:15")), now)
        assertEquals(now.plus(TickCadence.URGENT_TICK), next)
    }

    @Test
    fun `under an hour left it refreshes every five minutes`() {
        val now = at("08:45:00")
        val next = TickCadence.nextTick(inLesson(Duration.ofMinutes(30), at("09:15")), now)
        assertEquals(now.plus(TickCadence.NEAR_TICK), next)
    }

    @Test
    fun `beyond an hour it refreshes every fifteen minutes`() {
        val now = at("07:00:00")
        val next = TickCadence.nextTick(inLesson(Duration.ofMinutes(90), at("08:30")), now)
        assertEquals(now.plus(TickCadence.FAR_TICK), next)
    }

    @Test
    fun `a state with nothing to count down asks for no refresh`() {
        val now = at("16:00:00")
        val afterSchool = DayState.AfterSchool(
            finishedAt = LocalTime.parse("15:05"),
            homeworkDay = null,
            validUntil = day.plusDays(1).atStartOfDay(),
        )
        val dayOff = DayState.DayOff(
            date = day,
            kind = DayKind.HOLIDAY,
            note = null,
            homeworkDay = null,
            validUntil = day.plusDays(1).atStartOfDay(),
        )

        assertNull(TickCadence.nextTick(afterSchool, now))
        assertNull(TickCadence.nextTick(dayOff, now))
        assertNull(TickCadence.nextTick(DayState.NoData(day), now))
        assertNull(TickCadence.nextTick(null, now))
    }

    @Test
    fun `a late render counts as urgent rather than going backwards`() {
        // The device dozed through the bell: the countdown is negative.
        val now = at("09:16:00")
        val next = TickCadence.nextTick(inLesson(Duration.ofMinutes(-1), at("09:15")), now)
        assertEquals("a negative remainder must fall into the fastest tier",
            now.plus(TickCadence.URGENT_TICK), next)
    }

    // ---- choosing between a bell and a refresh --------------------------

    @Test
    fun `the earlier of bell and refresh wins`() {
        val now = at("08:40:00")
        val bell = at("09:15:00") // 35 min away, refresh tier is 5 min

        val tick = TickCadence.nextWakeUp(
            state = inLesson(Duration.ofMinutes(35), bell),
            transition = bell,
            now = now,
        )
        assertEquals(now.plus(TickCadence.NEAR_TICK), tick.at)
        assertFalse("a refresh is not a boundary", tick.isBoundary)
    }

    @Test
    fun `a tie goes to the bell`() {
        val now = at("09:14:00")
        val bell = at("09:15:00") // exactly one minute, same as the urgent tick

        val tick = TickCadence.nextWakeUp(
            state = inLesson(Duration.ofMinutes(1), bell),
            transition = bell,
            now = now,
        )
        assertEquals(bell, tick.at)
        assertTrue("on a tie the bell must win, so it is never late", tick.isBoundary)
    }

    @Test
    fun `a boundary already in the past is ignored`() {
        val now = at("09:20:00")
        val stale = at("09:15:00")

        val tick = TickCadence.nextWakeUp(state = null, transition = stale, now = now)

        assertFalse(tick.isBoundary)
        assertTrue("a passed bell must never be scheduled", tick.at.isAfter(now))
    }

    @Test
    fun `validUntil counts as a boundary when there is no transition`() {
        val now = at("16:00:00")
        val midnight = day.plusDays(1).atStartOfDay()
        val state = DayState.AfterSchool(
            finishedAt = LocalTime.parse("15:05"),
            homeworkDay = null,
            validUntil = midnight,
        )

        val tick = TickCadence.nextWakeUp(state = state, transition = null, now = now)

        assertEquals("the day rollover is the only thing left to wake for", midnight, tick.at)
        assertTrue(tick.isBoundary)
    }

    @Test
    fun `with nothing at all to wake for it falls back to an idle poke`() {
        val now = at("12:00:00")
        val tick = TickCadence.nextWakeUp(state = null, transition = null, now = now)

        assertEquals(now.plus(TickCadence.IDLE_FALLBACK), tick.at)
        assertFalse(tick.isBoundary)
    }

    @Test
    fun `every wake-up is strictly in the future`() {
        val now = at("09:14:59")
        val cases = listOf(
            TickCadence.nextWakeUp(null, null, now),
            TickCadence.nextWakeUp(null, at("09:15:00"), now),
            TickCadence.nextWakeUp(inLesson(Duration.ofSeconds(1), at("09:15")), at("09:15"), now),
            TickCadence.nextWakeUp(inLesson(Duration.ofMinutes(-5), at("09:10")), at("09:10"), now),
        )
        cases.forEach { assertTrue("wake-up $it must be after $now", it.at.isAfter(now)) }
    }
}
