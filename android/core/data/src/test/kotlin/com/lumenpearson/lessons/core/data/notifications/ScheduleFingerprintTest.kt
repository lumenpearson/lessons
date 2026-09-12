package com.lumenpearson.lessons.core.data.notifications

import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * «Расписание изменилось» is the one alert with nobody's clock behind it, which
 * means every false positive is a notification the user cannot explain — and the
 * fastest way to teach somebody to turn a channel off is to buzz them about
 * nothing every morning.
 */
class ScheduleFingerprintTest {

    private val monday: LocalDate = LocalDate.of(2026, 9, 7)

    private fun lesson(index: Int, subject: String, at: String, room: String? = null) = Lesson(
        index = index,
        subject = subject,
        startsAt = LocalTime.parse(at),
        endsAt = LocalTime.parse(at).plusMinutes(45),
        room = room,
    )

    private fun day(date: LocalDate, vararg lessons: Lesson) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        lessons = lessons.toList(),
    )

    /** Ten days, so a window anchored anywhere in the first three is full. */
    private fun timetable(edit: (LocalDate) -> List<Lesson> = { emptyList() }) = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А"),
        days = (0L until 10L).map { offset ->
            val date = monday.plusDays(offset)
            day(date, *(edit(date) + lesson(1, "Алгебра", "08:30", room = "214")).toTypedArray())
        },
    )

    /**
     * The bug this shape exists for. The window is seven days from *today*, so
     * at every midnight it drops a day off the back and picks one up at the
     * front — and a single hash over the whole window changed with it, with
     * nothing in the schedule having moved. The first sync of every morning
     * announced a change that had not happened.
     */
    @Test
    fun `the window sliding to the next day is not a change`() {
        val table = timetable()

        val yesterday = ScheduleFingerprint.of(table, monday)
        val today = ScheduleFingerprint.of(table, monday.plusDays(1))

        assertFalse(
            "the window slid but the schedule did not move",
            ScheduleFingerprint.changed(yesterday, today),
        )
    }

    @Test
    fun `a lesson moving inside the window is a change`() {
        val before = ScheduleFingerprint.of(timetable(), monday)
        val after = ScheduleFingerprint.of(
            timetable { date -> if (date == monday.plusDays(2)) listOf(lesson(2, "Физика", "09:25")) else emptyList() },
            monday,
        )

        assertTrue(ScheduleFingerprint.changed(before, after))
    }

    /** Same lessons, a later sync: identical data must never buzz anybody. */
    @Test
    fun `re-syncing the same schedule is not a change`() {
        val table = timetable()

        assertFalse(
            ScheduleFingerprint.changed(
                ScheduleFingerprint.of(table, monday),
                ScheduleFingerprint.of(table.copy(syncedAtEpochMillis = 1_234L), monday),
            ),
        )
    }

    /** A change outside the seven days is a correction, not news for tonight. */
    @Test
    fun `a lesson beyond the window is ignored`() {
        val before = ScheduleFingerprint.of(timetable(), monday)
        val after = ScheduleFingerprint.of(
            timetable { date -> if (date == monday.plusDays(8)) listOf(lesson(2, "Физика", "09:25")) else emptyList() },
            monday,
        )

        assertFalse(ScheduleFingerprint.changed(before, after))
    }

    /** There is nothing to have changed from, so the first sync stays quiet. */
    @Test
    fun `the first reading says nothing`() {
        assertFalse(ScheduleFingerprint.changed(null, ScheduleFingerprint.of(timetable(), monday)))
    }

    /**
     * The format this replaced was one bare `hashCode` for the whole window. It
     * has no dates in it, so it has none in common with a new reading — which is
     * the answer we want on the first sync after the update, rather than one
     * change alert per install.
     */
    @Test
    fun `a fingerprint stored in the old format says nothing`() {
        assertFalse(ScheduleFingerprint.changed("-1830552667", ScheduleFingerprint.of(timetable(), monday)))
    }
}
