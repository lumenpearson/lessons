package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «🔔 Звонки» → «Уроки», turned sideways.
 *
 * `SheetModeTest` proves the sheet comes back on the screen it was on. That was
 * half the fix and the expensive half was still missing: the rows themselves sat
 * in a plain `remember`, so the reader was returned to the Periods form with the
 * six bells they had just set one by one replaced by whatever the server holds.
 * Nothing about that is visible from a pure test — the whole defect is what the
 * composition does when it is torn down and built again — so this one is
 * composed for real and rotated with [StateRestorationTester], which saves the
 * registry, disposes the content and composes it back exactly as a
 * configuration change does.
 *
 * The rows are changed by pressing «Добавить урок» and «Убрать последний»
 * rather than through the time picker: they mutate the same list a picked time
 * does, and a Material time picker is a dial this harness cannot turn.
 *
 * One row is two lines on screen — «начало» and «конец» — which is why the
 * counts here are two per lesson.
 */
@RunWith(RobolectricTestRunner::class)
// Russian and a phone-sized screen, for the reason `ClassRowsScreenTest` gives.
@Config(qualifiers = "ru-rRU-w411dp")
class BellRowsRotationTest {

    @get:Rule val compose = createComposeRule()

    private val rotation = StateRestorationTester(compose)

    /** A schedule of [count] ordinary lessons, 45 minutes with 10-minute breaks. */
    private fun schedule(id: Long, count: Int): BellSchedule = BellSchedule(
        id = id,
        name = "Обычные уроки",
        isDefault = true,
        periods = (1..count).map { index ->
            val start = LocalTime.of(8, 30).plusMinutes((index - 1) * 55L)
            BellPeriod(index = index, startsAt = start, endsAt = start.plusMinutes(45))
        },
    )

    private fun show(schedule: BellSchedule) {
        rotation.setContent {
            LessonsTheme {
                // Scrollable so a press lands on a row six lessons down; the
                // form itself is drawn inside a sheet that scrolls.
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    PeriodsForm(
                        schedule = schedule,
                        busy = false,
                        failure = null,
                        onCancel = {},
                        onSave = {},
                    )
                }
            }
        }
    }

    private fun press(label: String) =
        compose.onNodeWithText(label).performScrollTo().performClick()

    private fun lesson(number: Int) = compose.onAllNodesWithText("Урок $number")

    @Test
    fun `a lesson added before a rotation is still there after it`() {
        show(schedule(id = 1L, count = 1))

        press("Добавить урок")
        lesson(2).assertCountEquals(2)

        rotation.emulateSavedInstanceStateRestore()

        lesson(2).assertCountEquals(2)
    }

    /**
     * The direction the server's own rows cannot fake: a schedule of six that
     * the reader has cut to five must not come back as six.
     */
    @Test
    fun `a lesson removed before a rotation does not come back`() {
        show(schedule(id = 1L, count = 6))

        press("Убрать последний")
        lesson(6).assertCountEquals(0)

        rotation.emulateSavedInstanceStateRestore()

        lesson(6).assertCountEquals(0)
        lesson(5).assertCountEquals(2)
    }

    /**
     * What the schedule's id in the save is for.
     *
     * `rememberSaveable` keys its entry by call site, and this form is one call
     * site for every schedule a class has. Here the form is on screen when the
     * state is saved and gone when it is restored — which is what a rotation
     * does whenever the bells have not been reloaded yet, or when the schedule
     * that was open has just been deleted from the bot — so the save sits
     * unclaimed until the next schedule opens the same form. Untagged, it would
     * be claimed: the second schedule would open holding the first one's times,
     * over «Сохранить».
     */
    @Test
    fun `times set for one schedule are not poured into another`() {
        // A plain `var`, deliberately: which schedule is open has to change
        // without recomposing, so that the save is taken while the first one's
        // form is still what is on screen. `reopened` is the state that puts
        // the form back afterwards.
        var open: BellSchedule? = schedule(id = 1L, count = 1)
        var reopened by mutableStateOf(0)
        rotation.setContent {
            LessonsTheme {
                val current = open.takeIf { reopened >= 0 }
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    current?.let {
                        PeriodsForm(
                            schedule = it,
                            busy = false,
                            failure = null,
                            onCancel = {},
                            onSave = {},
                        )
                    }
                }
            }
        }

        press("Добавить урок")
        lesson(2).assertCountEquals(2)

        open = null
        rotation.emulateSavedInstanceStateRestore()
        open = schedule(id = 2L, count = 1)
        reopened++
        compose.waitForIdle()

        // The second schedule's own single lesson, not the first one's two.
        lesson(1).assertCountEquals(2)
        lesson(2).assertCountEquals(0)
    }
}
