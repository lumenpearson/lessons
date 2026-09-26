package com.lumenpearson.lessons.ui.join

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.ui.settings.AddClassSheet
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A join's report belongs to the screen that sent the code.
 *
 * The join view model is the activity's, and the join writes the session — which
 * swaps the shell — before its first sync, so the screen that pressed
 * «Подключиться» is usually gone by the time the join reports. The report then
 * waited for whoever came next: the «Добавить класс» sheet, which closed itself
 * before a code could be typed, or a later first run's class-code step, which
 * took it for its own join and finished a flow with no class in it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1200dp")
class JoinOneShotTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val rig = JoinRig()

    /** A join started by a screen that is gone — the diary home's sheet, swapped away with its shell. */
    private fun orphanedJoin(model: JoinViewModel) {
        model.onCodeChange("ABCD1234")
        assertNotNull(model.submit())
        compose.waitForIdle()
    }

    @Test
    fun `a join another screen sent does not close the class sheet`() {
        val model = rig.model()
        orphanedJoin(model)
        var dismissed = 0

        compose.setContent {
            LessonsTheme { AddClassSheet(onDismiss = { dismissed++ }, viewModel = model) }
        }
        compose.waitForIdle()

        assertEquals("the sheet closed on a join it never sent", 0, dismissed)
        compose.onNode(hasSetTextAction()).performTextInput("EFGH5678")
        compose.onAllNodesWithText("Подключиться").onLast().performClick()
        compose.waitForIdle()
        assertEquals("its own join closes it", 1, dismissed)
        assertEquals(listOf("ABCD1234", "EFGH5678"), rig.codes)
    }

    @Test
    fun `a join another screen sent is not the first run's own`() {
        val model = rig.model()
        orphanedJoin(model)
        val heard = mutableListOf<Long>()

        compose.setContent {
            LessonsTheme { JoinScreen(onJoined = { heard += it }, viewModel = model) }
        }
        compose.waitForIdle()

        assertEquals("the class-code step heard somebody else's join", emptyList<Long>(), heard)
        compose.onNode(hasSetTextAction()).performTextInput("EFGH5678")
        compose.onAllNodesWithText("Подключиться").onLast().performClick()
        compose.waitForIdle()
        assertEquals(listOf(12L), heard)
    }

    /**
     * Leaving the class-code step while the code is out let the class land
     * under the chooser, which then offered the family's own school as if
     * nothing had been joined.
     */
    @Test
    fun `back is held while the code is out, and given back when it lands`() {
        val gate = CompletableDeferred<Unit>()
        rig.syncGate = gate
        val model = rig.model()
        var backs = 0
        var flowBacks = 0

        compose.setContent {
            LessonsTheme {
                // The first run's own handler, composed before the step as it is there.
                BackHandler { flowBacks++ }
                JoinScreen(onBack = { backs++ }, viewModel = model)
            }
        }
        compose.onNode(hasSetTextAction()).performTextInput("ABCD1234")
        compose.onAllNodesWithText("Подключиться").onLast().performClick()
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Назад").assertIsNotEnabled()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("the square left mid-join", 0, backs)
        assertEquals("the gesture left mid-join", 0, flowBacks)

        gate.complete(Unit)
        compose.waitForIdle()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals("once the join has landed the gesture is the flow's again", 1, flowBacks)
    }
}
