package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The calendar's two sheets, turned sideways.
 *
 * Both flags were plain `remember`s while every other sheet in the app is a
 * `rememberSaveable`, so a phone turned while «История» was open came back to
 * the grid — and the lesson sheet is the only place its room, its teacher and
 * the homework set for that subject that day can be read at all.
 *
 * What is saved is the lesson's date and number rather than the lesson, because
 * [Lesson] is a `:core:model` type and that module has no Android in it; the
 * sheet looks the lesson up again in the week the screen is holding. The third
 * test is the one that fix has to earn: a lesson that is no longer in the week
 * when the state comes back must open nothing, and the same rule closes the
 * sheet when a reload withdraws the lesson under it.
 *
 * Composed against [ScheduleSheets] rather than `WeekScreen`, for the reason
 * `BellRowsRotationTest` composes `PeriodsForm`: the state that has to survive
 * lives there, and the screen around it needs a view model and a clock.
 */
@RunWith(RobolectricTestRunner::class)
// Russian and a phone-sized screen, for the reason `ClassRowsScreenTest` gives.
@Config(qualifiers = "ru-rRU-w411dp")
class ScheduleSheetRotationTest {

    @get:Rule val compose = createComposeRule()

    private val rotation = StateRestorationTester(compose)

    /**
     * The week on screen. A plain `var`, deliberately: a reload has to change it
     * without the composition being torn down, which is what [reload]'s counter
     * is for — the same pair `BellRowsRotationTest` uses to swap a schedule.
     */
    private var lessons: List<Lesson> = emptyList()

    /** Bumped by [reload]; nothing else on screen depends on its value. */
    private var reloads by mutableStateOf(0)

    private fun lesson(index: Int, subject: String): Lesson {
        val start = LocalTime.of(8, 30).plusMinutes((index - 1) * 55L)
        return Lesson(
            index = index,
            subject = subject,
            startsAt = start,
            endsAt = start.plusMinutes(45),
            room = "20$index",
        )
    }

    private fun week(of: List<Lesson>): List<WeekDayUi> = listOf(
        WeekDayUi(
            date = THURSDAY,
            day = SchoolDay(date = THURSDAY, weekday = THURSDAY.dayOfWeek.value, lessons = of),
            isToday = false,
        ),
    )

    /**
     * The lessons of one day, the two ways of opening a sheet about them, and
     * the sheets themselves — which is everything of the calendar that a
     * rotation was losing.
     */
    private fun show(initial: List<Lesson>) {
        lessons = initial
        rotation.setContent {
            LessonsTheme {
                // `reloads` is read so that a reload recomposes; `lessons` on
                // its own is a plain var and changing it moves nothing.
                val days = week(if (reloads >= 0) lessons else emptyList())
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    val sheets = rememberScheduleSheets()
                    LessonGroup(
                        lessons = days.first().day?.activeLessons.orEmpty(),
                        onLessonClick = { tapped -> sheets.show(THURSDAY, tapped) },
                    )
                    Text(
                        text = DETAILS,
                        modifier = Modifier.clickable { sheets.day = THURSDAY },
                    )
                    ScheduleSheets(
                        sheets = sheets,
                        days = days,
                        showTeacher = true,
                        showEvents = true,
                        showHomework = true,
                    )
                }
            }
        }
    }

    /** A sync lands, and the week is not what it was. */
    private fun reload(next: List<Lesson>) {
        lessons = next
        reloads++
        compose.waitForIdle()
    }

    @Test
    fun `the lesson sheet is still open after a rotation`() {
        show(listOf(lesson(1, "Алгебра"), lesson(2, "История")))

        compose.onNodeWithText("История").performClick()
        compose.onNodeWithText(SECOND).assertIsDisplayed()

        rotation.emulateSavedInstanceStateRestore()

        // The same lesson, not merely a sheet: the number is the sheet's own
        // pill, and the first lesson's would be «Урок 1».
        compose.onNodeWithText(SECOND).assertIsDisplayed()
        compose.onNodeWithText(FIRST).assertDoesNotExist()
    }

    @Test
    fun `the day sheet is still open after a rotation`() {
        show(listOf(lesson(1, "Алгебра")))

        compose.onNodeWithText(DETAILS).performClick()
        compose.onNodeWithText(DAY_TITLE).assertIsDisplayed()

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(DAY_TITLE).assertIsDisplayed()
    }

    /**
     * The case the saved key is chosen for.
     *
     * A substitution is withdrawn in the bot while the sheet about it is up. The
     * sheet is drawn from the week rather than from a lesson it captured, so it
     * closes there and then — and the key that survives the rotation finds
     * nothing to reopen either, which is better than a room and a teacher for a
     * lesson the class is no longer having.
     */
    @Test
    fun `a lesson the week has lost does not come back with its sheet`() {
        show(listOf(lesson(1, "Алгебра"), lesson(2, "История")))

        compose.onNodeWithText("История").performClick()
        compose.onNodeWithText(SECOND).assertIsDisplayed()

        reload(listOf(lesson(1, "Алгебра")))
        compose.onNodeWithText(SECOND).assertDoesNotExist()

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(SECOND).assertDoesNotExist()
        // The week itself is still on screen: it is the sheet that did not come
        // back, not the composition that failed to.
        compose.onNodeWithText("Алгебра").assertIsDisplayed()
    }

    private companion object {
        /** A Thursday, so «Четверг» cannot be read off the wrong day. */
        val THURSDAY: LocalDate = LocalDate.of(2026, 3, 12)

        /** `schedule_lesson_index`, which only the lesson sheet draws as text. */
        const val FIRST = "Урок 1"
        const val SECOND = "Урок 2"

        /** `schedule_day_details`, the action the week's panel opens a day with. */
        const val DETAILS = "Подробно"

        /** The day sheet's own title. */
        const val DAY_TITLE = "Четверг, 12 марта"
    }
}
