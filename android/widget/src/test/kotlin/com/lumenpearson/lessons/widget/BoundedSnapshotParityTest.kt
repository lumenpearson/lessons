package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.widget.tick.WidgetTickScheduler
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget reads a fortnight of the cache instead of a school year, and this
 * is the proof that it may.
 *
 * `snapshotAroundToday()` hands back a `Timetable` whose `days` stop at the
 * bound, and a date outside it reads as «no data» — which inside the bound is
 * the truth and outside it is a lie. The widget is the most frequent reader
 * there is (every tick, every redraw), so it is the one that had most to gain
 * and most to lose: a widget that silently draws an empty week is worse than
 * one that reads too much.
 *
 * So rather than assume, this walks a whole school year past both readings and
 * demands the same answer from [snapshotOf] — which is every question a render
 * asks the cache — and from [WidgetTickScheduler.plan], which is every question
 * the alarm chain asks it. Four moments are named below because they are where
 * a bound breaks, and the sweep at the end covers the fifteen months around
 * them so a fifth cannot hide.
 *
 * ## Why the bounded reading is rebuilt here rather than read
 *
 * `CachedWindow` and `TimetableRepositoryImpl` are `internal` to `:core:data`,
 * and its `InMemoryTimetableDao` lives in that module's own test source set —
 * none of the three can be reached from `:widget`, in a test or anywhere else.
 * So [boundedAround] below reproduces what the repository and
 * `TimetableDao.snapshotBetween` do between them, the way `WidgetSizeClassTest`
 * reproduces the launcher's nearest-breakpoint rule: the days inside
 * `min(Monday of this week, yesterday) .. today + 8`, and the first day with
 * lessons past that edge carried over as `nextSchoolDay`.
 *
 * That the *repository* implements that rule is `CachedWindowTest`'s question,
 * and it asks it against the real DAO. This one is the other half: given that
 * bound, the widget's own questions stay inside it.
 */
class BoundedSnapshotParityTest {

    private val zone: ZoneId = ZoneId.of("Asia/Vladivostok")

    /**
     * The Sunday the week strip's Monday is six days behind.
     *
     * A bound spelled `today .. today + n` loses six of the strip's seven
     * day-load dots here, on the one day of the week somebody is most likely
     * to be looking at the week ahead.
     */
    @Test
    fun `on a Sunday the week strip is the same week through either reading`() {
        val sunday = LocalDate.of(2026, 9, 13)
        assertEquals(DayOfWeek.SUNDAY, sunday.dayOfWeek)
        val now = sunday.atTime(11, 0)

        val bounded = snapshotOf(year.boundedAround(sunday), now)

        assertEquals(snapshotOf(year, now), bounded)
        assertEquals(LocalDate.of(2026, 9, 7), bounded.week.first().date)
        assertEquals(
            "five taught days, drawn from behind today",
            5,
            bounded.week.count { it.lessons > 0 },
        )
    }

    /** Late on a Friday: everything the widget points at is on Monday. */
    @Test
    fun `on a Friday evening the next school day is Monday through either reading`() {
        val friday = LocalDate.of(2026, 9, 11)
        assertEquals(DayOfWeek.FRIDAY, friday.dayOfWeek)
        val now = friday.atTime(21, 40)

        val bounded = snapshotOf(year.boundedAround(friday), now)

        assertEquals(snapshotOf(year, now), bounded)
        assertTrue(
            "expected the day to be over, got ${bounded.state}",
            bounded.state is DayState.AfterSchool,
        )
        assertEquals(LocalDate.of(2026, 9, 14), bounded.homeworkDay?.date)
        assertEquals(plan(year, now), plan(year.boundedAround(friday), now))
    }

    /**
     * The last day before a holiday wider than the bound.
     *
     * The first lesson back is fourteen days away and the bound reaches eight,
     * so this is the answer that exists only because `snapshotBetween`
     * resolves it past its own edge and hands it over as `nextSchoolDay`.
     */
    @Test
    fun `a holiday wider than the bound does not empty the homework card`() {
        val lastDayBefore = LocalDate.of(2026, 12, 28)
        val now = lastDayBefore.atTime(19, 0)
        val bounded = year.boundedAround(lastDayBefore)

        assertEquals(
            "nothing taught inside the bound after today",
            emptyList<SchoolDay>(),
            bounded.days.filter { it.date > lastDayBefore },
        )
        assertEquals(snapshotOf(year, now), snapshotOf(bounded, now))
        assertEquals(LocalDate.of(2027, 1, 11), snapshotOf(bounded, now).homeworkDay?.date)
        assertEquals(plan(year, now), plan(bounded, now))
    }

    /**
     * July, where the whole cache is outside any bound around today and «когда
     * снова в школу» still has an answer the phone shows all summer.
     */
    @Test
    fun `in July the first of September is still what the widget points at`() {
        val july = LocalDate.of(2026, 7, 15)
        val now = july.atTime(9, 0)
        val bounded = year.boundedAround(july)

        assertEquals("the bound holds nothing, which is correct", emptyList<SchoolDay>(), bounded.days)
        assertEquals(snapshotOf(year, now), snapshotOf(bounded, now))
        assertEquals(LocalDate.of(2026, 9, 1), snapshotOf(bounded, now).homeworkDay?.date)
        assertEquals(plan(year, now), plan(bounded, now))
    }

    /**
     * And the same two questions on every date of fifteen months, at six times
     * of day, because the four above are the shapes that were thought of.
     *
     * The times straddle every boundary the fixture has: before the first
     * bell, inside a lesson, on the break, during the canteen event, after the
     * last bell, and ten minutes before midnight — where `validUntil` is the
     * nearest thing to the tick and the day the strip is drawn from is about
     * to change.
     */
    @Test
    fun `the widget sees the same thing through the bound as through the year`() {
        val times = listOf(
            LocalTime.of(7, 0),
            LocalTime.of(8, 45),
            LocalTime.of(9, 20),
            LocalTime.of(11, 10),
            LocalTime.of(15, 0),
            LocalTime.of(23, 50),
        )
        var date = LocalDate.of(2026, 6, 1)
        val until = LocalDate.of(2027, 8, 31)
        while (date <= until) {
            val bounded = year.boundedAround(date)
            for (time in times) {
                val now = date.atTime(time)
                assertEquals("render at $now", snapshotOf(year, now), snapshotOf(bounded, now))
                assertEquals("tick at $now", plan(year, now), plan(bounded, now))
            }
            date = date.plusDays(1)
        }
    }

    // -- the two readings ---------------------------------------------------

    /**
     * What `snapshotAroundToday()` would return for [today], built out of the
     * whole-year reading.
     *
     * The two halves of the class comment, in code: the days inside
     * `CachedWindow.around(today)`, and `nextSchoolDay` resolved as
     * `TimetableDao.snapshotBetween` resolves it — the first day with lessons
     * after the bound's far edge, falling back to the stored lookahead row.
     */
    private fun Timetable.boundedAround(today: LocalDate): Timetable {
        val monday = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        val from = minOf(monday, today.minusDays(1))
        val to = today.plusDays(DAYS_AHEAD)
        return copy(
            days = days.filter { it.date in from..to },
            nextSchoolDay = days.filter { it.date > to && it.hasLessons }.minByOrNull { it.date }
                ?: nextSchoolDay,
        )
    }

    // -- the widget's two questions -----------------------------------------

    private fun snapshotOf(timetable: Timetable, now: LocalDateTime) = snapshotOf(
        timetable = timetable,
        now = now,
        signedIn = true,
        options = WidgetOptions(),
        language = AppLanguage.SYSTEM,
    )

    /**
     * [WidgetTickScheduler.plan] reads its «now» off a clock, so the moment is
     * handed over as the instant the school's wall clock reads it at — which
     * is what the scheduler converts back out of when it arms.
     */
    private fun plan(timetable: Timetable, now: LocalDateTime) =
        WidgetTickScheduler.plan(timetable, Clock.fixed(now.atZone(zone).toInstant(), zone))

    // -- the cache ----------------------------------------------------------

    /**
     * A school year as the sync caches it: every weekday from the first of
     * September to the end of May, with the winter holidays cut out.
     *
     * `nextSchoolDay` is null, as it is on a phone whose cached window runs to
     * the end of the year and has nothing beyond to look ahead at. That is the
     * hard case for the bound, not the easy one: it means every answer about a
     * far-away day has to come out of the days themselves.
     */
    private val year: Timetable = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А", timeZoneId = zone.id),
        days = generateSequence(LocalDate.of(2026, 9, 1)) { it.plusDays(1) }
            .takeWhile { it <= LocalDate.of(2027, 5, 29) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }
            .filterNot { it in LocalDate.of(2026, 12, 29)..LocalDate.of(2027, 1, 10) }
            .map { schoolDay(it) }
            .toList(),
        nextSchoolDay = null,
    )

    private fun schoolDay(date: LocalDate) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        lessons = listOf(
            Lesson(
                index = 1,
                subject = "Алгебра",
                startsAt = LocalTime.of(8, 30),
                endsAt = LocalTime.of(9, 15),
            ),
            Lesson(
                index = 2,
                subject = "Физика",
                startsAt = LocalTime.of(9, 25),
                endsAt = LocalTime.of(10, 10),
            ),
        ),
        events = listOf(
            SchoolEvent(
                title = "Столовая",
                kind = EventKind.CANTEEN,
                startsAt = LocalTime.of(11, 0),
                endsAt = LocalTime.of(11, 20),
            ),
        ),
        // Homework on every day, so «чьё домашнее задание» has an answer to be
        // wrong about wherever the next school day falls.
        homework = listOf(HomeworkItem(subject = "Алгебра", text = "§12")),
    )

    private companion object {

        /**
         * How far ahead the bound reaches, in days — `CachedWindow.DaysAhead`,
         * which is internal to `:core:data` and cannot be imported.
         */
        const val DAYS_AHEAD = 8L
    }
}
