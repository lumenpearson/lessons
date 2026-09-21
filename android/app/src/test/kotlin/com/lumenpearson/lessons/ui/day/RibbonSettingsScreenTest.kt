package com.lumenpearson.lessons.ui.day

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.RibbonFlow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ribbon's own three settings, drawn.
 *
 * The last of this batch's screens that nothing had ever composed. The day
 * ribbon shipped with a crash that three passing suites never touched, because
 * every one of them asked about its arithmetic rather than about the screen;
 * this sheet was in the same position, and it is the surface a reader opens
 * precisely when the ribbon is not behaving.
 *
 * All three settings show their effect the moment they are pressed, which is
 * the argument for the sheet existing at all — so what has to be true here is
 * that a press reports the value the row was showing, and not its opposite.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class RibbonSettingsScreenTest {

    @get:Rule val compose = createComposeRule()

    private var flow: RibbonFlow? = null
    private var snap: Boolean? = null
    private var depth: Boolean? = null

    private fun show(
        current: RibbonFlow = RibbonFlow.DOWNWARD,
        snapOn: Boolean = true,
        depthOn: Boolean = true,
    ) = compose.setContent {
        LessonsTheme {
            RibbonSettingsSheet(
                flow = current,
                snap = snapOn,
                depth = depthOn,
                onFlow = { flow = it },
                onSnap = { snap = it },
                onDepth = { depth = it },
                onDismiss = {},
            )
        }
    }

    @Test
    fun `all three settings are on the sheet, in Russian`() {
        show()

        compose.onNodeWithText("Сверху вниз").assertIsDisplayed()
        compose.onNodeWithText("Снизу вверх").assertIsDisplayed()
        compose.onNodeWithText("Магнитная прокрутка").assertIsDisplayed()
        compose.onNodeWithText("Объём").assertIsDisplayed()
    }

    @Test
    fun `turning the progress the other way up reports that direction`() {
        show()

        compose.onNodeWithText("Снизу вверх").performClick()

        assertEquals(RibbonFlow.UPWARD, flow)
    }

    @Test
    fun `a switch reports the value it is being moved to, not the one it had`() {
        // The mistake this catches is one character wide and invisible on the
        // screen: a row wired to its own current value turns itself back on
        // every time it is turned off.
        show(snapOn = true, depthOn = true)

        compose.onNodeWithText("Магнитная прокрутка").performClick()
        compose.onNodeWithText("Объём").performClick()

        assertEquals(false, snap)
        assertEquals(false, depth)
    }
}
