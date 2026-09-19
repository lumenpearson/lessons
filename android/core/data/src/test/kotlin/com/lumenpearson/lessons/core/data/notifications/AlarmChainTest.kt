package com.lumenpearson.lessons.core.data.notifications

import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * That the alarm chain cannot end itself while the user still wants alerts.
 *
 * The chain is one alarm long: each firing plans the next one, and nothing
 * else arms it except an app start, a reboot, a settings change and a sync
 * that found new data. `AlertPlanner.next` answers within a fixed horizon — a
 * week and a day — and the chain used to read its `null` as "there is nothing
 * to wake for" and cancel.
 *
 * Those are two different sentences. A fortnight of winter holidays is longer
 * than the horizon, so the last alert before it fired, the planner saw nothing
 * within eight days, the alarm was cancelled, and the first morning back was
 * silent. Nothing reported it, because from outside a cancelled chain looks
 * exactly like a chain with nothing due yet — and on a phone whose window has
 * not changed every sync is a `304`, so the one path that could have noticed
 * was the one that deliberately said nothing.
 */
class AlarmChainTest {

    private val everything = AlertPreferences(
        lessonSoon = true,
        morningSummary = true,
        homeworkReminder = true,
    )

    private fun schoolDay(date: LocalDate, at: LocalTime = LocalTime.of(8, 30)) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        lessons = listOf(
            Lesson(index = 1, subject = "Алгебра", startsAt = at, endsAt = at.plusMinutes(45)),
        ),
    )

    private fun timetable(vararg days: SchoolDay) = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А"),
        days = days.toList(),
    )

    /** The ordinary case: tomorrow's first lesson, armed for its lead time. */
    @Test
    fun `a lesson inside the horizon is what gets armed`() {
        val today = LocalDate.parse("2026-09-07")
        val next = SchoolAlerts.nextAlarm(
            timetable = timetable(schoolDay(today.plusDays(1))),
            preferences = AlertPreferences(lessonSoon = true, lessonLeadMinutes = 10),
            after = today.atTime(18, 0),
        )

        assertEquals(NextAlarm.Alert(today.plusDays(1).atTime(8, 20)), next)
    }

    /**
     * The winter break, to the day: 28 December is the last lesson and the
     * next one is 12 January. The planner cannot see it from here and never
     * will be able to — its horizon is another module's constant — so the
     * chain has to come back and ask rather than end.
     */
    @Test
    fun `a gap wider than the planner's horizon leaves an alarm standing`() {
        val lastDay = LocalDate.parse("2026-12-28")
        val backToSchool = LocalDate.parse("2027-01-12")
        val evening = lastDay.atTime(20, 0)

        val next = SchoolAlerts.nextAlarm(
            timetable = timetable(schoolDay(lastDay), schoolDay(backToSchool)),
            preferences = everything,
            after = evening,
        )

        assertEquals(
            "cancelling here is what made the first morning back silent",
            NextAlarm.LookAgain(evening.plusHours(12)),
            next,
        )
    }

    /**
     * And the retry is short enough to matter: by the time it fires, and then
     * fires again, the horizon has moved over the first lesson back. The point
     * of the number is that the chain reaches the other side of a holiday, so
     * the test measures that rather than the constant.
     */
    @Test
    fun `looking again eventually finds the other side of the holiday`() {
        val lastDay = LocalDate.parse("2026-12-28")
        val backToSchool = LocalDate.parse("2027-01-12")
        val cached = timetable(schoolDay(lastDay), schoolDay(backToSchool))

        var at: LocalDateTime = lastDay.atTime(20, 0)
        var wakeUps = 0
        while (wakeUps < 100) {
            when (val next = SchoolAlerts.nextAlarm(cached, everything, after = at)) {
                is NextAlarm.LookAgain -> {
                    at = next.at
                    wakeUps += 1
                }

                is NextAlarm.Alert -> {
                    assertEquals(backToSchool, next.at.toLocalDate())
                    // Armed before the lesson it is about, which is the only
                    // thing that makes the wake-ups worth anything.
                    assertEquals(true, next.at < backToSchool.atTime(8, 30))
                    return
                }

                NextAlarm.None -> error("the alerts are switched on")
            }
        }
        error("the chain never reached the first lesson back after $wakeUps wake-ups")
    }

    /**
     * The one honest cancel, and it must stay one: a user who has switched
     * every alert off has said there is nothing to come back for, and waking
     * twice a day to confirm that would be the app disagreeing with them.
     */
    @Test
    fun `switching every alert off arms nothing at all`() {
        val today = LocalDate.parse("2026-09-07")
        val next = SchoolAlerts.nextAlarm(
            timetable = timetable(schoolDay(today.plusDays(1))),
            preferences = AlertPreferences(),
            after = today.atTime(18, 0),
        )

        assertEquals(NextAlarm.None, next)
    }

    /**
     * An empty cache is the same shape as a long holiday and gets the same
     * answer. A class whose timetable nobody has filled in yet must not cost
     * the pupil the chain for good the moment they switch an alert on.
     */
    @Test
    fun `a timetable with no days is looked at again rather than given up on`() {
        val at = LocalDate.parse("2026-09-07").atTime(18, 0)

        assertEquals(
            NextAlarm.LookAgain(at.plusHours(12)),
            SchoolAlerts.nextAlarm(timetable(), everything, after = at),
        )
    }
}
