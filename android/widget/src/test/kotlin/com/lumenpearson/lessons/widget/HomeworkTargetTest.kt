package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

/**
 * The widget shows homework beside the timeline, not only after the last bell.
 *
 * `DayState.homeworkFocus` answers only once lessons are over, so every state a
 * student actually looks at the widget in — before school, in a lesson, on a
 * break — has to fall back to the next school day. That fallback was written
 * down in two doc comments and implemented in neither, which left the homework
 * block empty all day and looking like missing data.
 */
class HomeworkTargetTest {

    private val monday = LocalDate.of(2026, 9, 14)
    private val tuesday = LocalDate.of(2026, 9, 15)

    private val firstLesson = Lesson(
        index = 1,
        subject = "Алгебра",
        startsAt = LocalTime.of(9, 0),
        endsAt = LocalTime.of(9, 40),
    )

    private fun day(date: LocalDate, weekday: Int, withLessons: Boolean = true) = SchoolDay(
        date = date,
        weekday = weekday,
        lessons = if (withLessons) listOf(firstLesson) else emptyList(),
    )

    private fun timetable(vararg days: SchoolDay) = Timetable(
        schoolClass = SchoolClassInfo(id = 1L, name = "11А"),
        days = days.toList(),
    )

    private fun beforeSchool() = DayState.BeforeSchool(
        next = firstLesson,
        startsIn = Duration.ofHours(2),
        validUntil = null,
    )

    @Test
    fun `before school the next school day supplies the homework`() {
        val next = day(tuesday, weekday = 2)
        val resolved = homeworkDayFor(
            state = beforeSchool(),
            timetable = timetable(day(monday, weekday = 1), next),
            today = monday,
        )
        assertEquals(next, resolved)
    }

    @Test
    fun `during a lesson the widget still has homework to show`() {
        val next = day(tuesday, weekday = 2)
        val resolved = homeworkDayFor(
            state = DayState.InLesson(
                current = firstLesson,
                next = null,
                endsIn = Duration.ofMinutes(10),
                progress = 0.5f,
                validUntil = null,
            ),
            timetable = timetable(day(monday, weekday = 1), next),
            today = monday,
        )
        assertEquals(next, resolved)
    }

    @Test
    fun `after school the state's own homework day wins over the lookup`() {
        val focus = day(tuesday, weekday = 2)
        val resolved = homeworkDayFor(
            state = DayState.AfterSchool(
                finishedAt = LocalTime.of(14, 0),
                homeworkDay = focus,
                validUntil = null,
            ),
            timetable = timetable(day(monday, weekday = 1), day(tuesday, weekday = 2)),
            today = monday,
        )
        assertEquals(focus, resolved)
    }

    @Test
    fun `a day with no lessons is not offered as the next school day`() {
        val resolved = homeworkDayFor(
            state = beforeSchool(),
            timetable = timetable(
                day(monday, weekday = 1),
                day(tuesday, weekday = 2, withLessons = false),
            ),
            today = monday,
        )
        assertNull(resolved)
    }

    @Test
    fun `no timetable at all resolves to nothing rather than throwing`() {
        assertNull(homeworkDayFor(state = null, timetable = null, today = monday))
    }
}
