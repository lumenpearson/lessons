package com.lumenpearson.lessons.widget.tick

import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The alarm and the render have to be talking about the same moment.
 *
 * The widget draws from `Timetable.nowAtSchool()` — the schedule is the school's
 * wall time, and Russia is eleven zones wide — while the scheduler derived the
 * state from the device's clock and armed through the device's zone. For anyone
 * following a school in another zone that is two independent errors: the tick
 * was computed for the wrong instant, and then armed for a third one.
 */
class WidgetTickSchedulerTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private val vladivostok = SchoolClassInfo(id = 1, name = "9А", timeZoneId = "Asia/Vladivostok")

    private val lessons = listOf(
        Lesson(index = 1, subject = "Алгебра", startsAt = LocalTime.of(8, 30), endsAt = LocalTime.of(9, 15)),
        Lesson(index = 2, subject = "Физика", startsAt = LocalTime.of(9, 25), endsAt = LocalTime.of(10, 10)),
    )

    private fun timetable(info: SchoolClassInfo) = Timetable(
        schoolClass = info,
        days = listOf(SchoolDay(date = monday, weekday = monday.dayOfWeek.value, lessons = lessons)),
    )

    /** 2026-09-06T23:10Z is 09:10 on the Monday in Vladivostok: inside lesson one. */
    private val duringFirstLesson: Instant = Instant.parse("2026-09-06T23:10:00Z")

    private fun clockAt(instant: Instant) = Clock.fixed(instant, ZoneId.of("UTC"))

    @Test
    fun `the tick is computed in the school's wall time`() {
        val armed = WidgetTickScheduler.plan(timetable(vladivostok), clockAt(duringFirstLesson))

        // Five minutes to the bell, so the cadence wants a one-minute refresh
        // rather than the bell itself.
        assertEquals(LocalDateTime.of(monday, LocalTime.of(9, 11)), armed.tick.at)
    }

    @Test
    fun `the bell is armed for the instant the school rings it`() {
        // Half a minute before the bell at school, which is inside the cadence's
        // own floor, so it is the bell itself that gets armed for.
        val armed = WidgetTickScheduler.plan(
            timetable(vladivostok),
            clockAt(Instant.parse("2026-09-06T23:14:30Z")),
        )

        assertTrue("expected the bell, got ${armed.tick}", armed.tick.isBoundary)
        assertEquals(LocalDateTime.of(monday, LocalTime.of(9, 15)), armed.tick.at)
        // 09:15 in Vladivostok is 23:15Z the evening before, and that instant is
        // what the alarm has to be set for whatever the phone's own zone says.
        assertEquals(
            Instant.parse("2026-09-06T23:15:00Z").toEpochMilli(),
            armed.triggerAtMillis,
        )
    }

    /** Two phones in different places, following the same school, wake together. */
    @Test
    fun `the armed instant does not depend on the device zone`() {
        val table = timetable(vladivostok)

        val fromUtc = WidgetTickScheduler.plan(table, clockAt(duringFirstLesson))
        val fromMoscow = WidgetTickScheduler.plan(
            table,
            Clock.fixed(duringFirstLesson, ZoneId.of("Europe/Moscow")),
        )

        assertEquals(fromUtc.tick, fromMoscow.tick)
        assertEquals(fromUtc.triggerAtMillis, fromMoscow.triggerAtMillis)
    }

    /** With no cache there is no school zone, and the fallback must still arm. */
    @Test
    fun `an empty cache still produces a wake-up`() {
        val armed = WidgetTickScheduler.plan(timetable = null, clock = clockAt(duringFirstLesson))

        assertEquals(duringFirstLesson.plus(TickCadence.IDLE_FALLBACK).toEpochMilli(), armed.triggerAtMillis)
    }
}
