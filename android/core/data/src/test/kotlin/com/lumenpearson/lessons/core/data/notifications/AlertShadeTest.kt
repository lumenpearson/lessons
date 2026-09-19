package com.lumenpearson.lessons.core.data.notifications

import com.lumenpearson.lessons.core.model.AlertPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What switching an alert off does to the one already on the shade.
 *
 * It did nothing: turning «Напоминания об уроках» off cancelled the armed
 * alarm and left «Через 10 минут: Алгебра» sitting there — still tappable,
 * still opening the app on that day — because the alarm and the shade are two
 * different states and only one of them was ever answered.
 *
 * The rule has to be per kind rather than "clear everything when the alerts go
 * quiet". [AlertPreferences.silent] is about the three that are *planned*, so
 * reading it here would drop «расписание изменилось» for somebody who turned
 * the other three off while still wanting it — and keep it for somebody who
 * turned it off and left the others on.
 */
class AlertShadeTest {

    private val everything = AlertPreferences(
        lessonSoon = true,
        morningSummary = true,
        homeworkReminder = true,
        scheduleChanges = true,
    )

    @Test
    fun `nothing is cleared while everything is switched on`() {
        assertEquals(emptyList<Int>(), AlertNotifier.disabledIds(everything))
    }

    @Test
    fun `switching one off clears that one and leaves the other three`() {
        assertEquals(
            listOf(AlertNotifier.ID_LESSON),
            AlertNotifier.disabledIds(everything.copy(lessonSoon = false)),
        )
        assertEquals(
            listOf(AlertNotifier.ID_MORNING),
            AlertNotifier.disabledIds(everything.copy(morningSummary = false)),
        )
        assertEquals(
            listOf(AlertNotifier.ID_HOMEWORK),
            AlertNotifier.disabledIds(everything.copy(homeworkReminder = false)),
        )
    }

    /**
     * «Расписание изменилось» is not one of the three the planner produces, so
     * it does not go quiet with them. A pupil who wants to know that a lesson
     * moved and nothing else is a real setting, and it is the one this rule
     * would have got wrong if it had read `silent`.
     */
    @Test
    fun `the change alert survives the other three being silenced`() {
        val onlyChanges = AlertPreferences(
            lessonSoon = false,
            morningSummary = false,
            homeworkReminder = false,
            scheduleChanges = true,
        )

        assertEquals(true, onlyChanges.silent)
        assertEquals(
            "everything the planner produces goes, and the one thing it does not stays",
            listOf(AlertNotifier.ID_LESSON, AlertNotifier.ID_MORNING, AlertNotifier.ID_HOMEWORK),
            AlertNotifier.disabledIds(onlyChanges),
        )
    }

    @Test
    fun `the change alert goes on its own when it is the one switched off`() {
        assertEquals(
            listOf(AlertNotifier.ID_CHANGES),
            AlertNotifier.disabledIds(everything.copy(scheduleChanges = false)),
        )
    }

    /** Everything off clears the shade completely, and names every id to do it. */
    @Test
    fun `switching everything off leaves nothing behind`() {
        val nothing = AlertPreferences(
            lessonSoon = false,
            morningSummary = false,
            homeworkReminder = false,
            scheduleChanges = false,
        )

        assertEquals(AlertNotifier.NotificationIds, AlertNotifier.disabledIds(nothing))
    }
}
