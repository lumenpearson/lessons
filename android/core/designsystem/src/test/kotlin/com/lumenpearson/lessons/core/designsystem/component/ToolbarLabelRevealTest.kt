package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Selecting a tab reveals its label rather than squeezing it (#246), and the bar
 * moves at the reader's motion settings (#246, #247).
 *
 * The label's pill grows on a spring, and for its first frames it is narrower
 * than the label. `MarqueeText` decides whether to scroll from the width it is
 * given, so a label laid out at the spring's width was told it overflowed —
 * fading edges on, then off a frame later: a gradient over the label on every
 * tap. The label is laid out at its final width from the first frame now, and
 * the growing pill cuts it; asked here of the label's own laid-out width while
 * the tab around it is still growing.
 *
 * 411 dp wide, because below 330 dp three tabs and a button draw no label.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp")
class ToolbarLabelRevealTest {

    @get:Rule
    val compose = createComposeRule()

    // The labels are drawn through `MarqueeText`, whose clock never idles once a
    // line overflows — which is the very thing this file is about. Held.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private var selected by mutableIntStateOf(0)

    @Test
    fun `a label being revealed is laid out at its final width from the first frame`() {
        show(MotionSettings())
        compose.settle(frames = 60)

        selected = 1
        // The first frame the label is drawn in at all — a frame or two after
        // the tap, while the spring has barely started.
        var frames = 0
        compose.settle(frames = 1)
        while (!labelDrawn() && frames < MaxFramesToAppear) {
            compose.settle(frames = 1)
            frames++
        }
        val earlyLabel = labelWidth()
        val earlyTab = tabWidth()

        compose.settle(frames = 240)
        val finalLabel = labelWidth()
        val finalTab = tabWidth()

        assertTrue(
            "the tab was $earlyTab px two frames in and $finalTab px at rest, so it was not growing",
            earlyTab < finalTab,
        )
        assertEquals(
            "the label was laid out at $earlyLabel px while its tab grew, and $finalLabel px at rest",
            finalLabel,
            earlyLabel,
        )
    }

    @Test
    fun `with animations off the selected tab is whole within three frames`() {
        show(MotionSettings(enabled = false))
        compose.settle(frames = 60)

        selected = 1
        compose.settle(frames = 3)
        val next = tabWidth()

        compose.settle(frames = 240)
        val final = tabWidth()

        // Three frames: the time a snap takes to land through `animateDpAsState`'s
        // own coroutine. The spring takes dozens.
        assertEquals("the tab was $next px three frames after the tap and $final px at rest", final, next)
    }

    @Test
    fun `at twice the speed the tab is further along after the same frames`() {
        show(MotionSettings(speed = 2f))
        compose.settle(frames = 60)
        selected = 1
        compose.settle(frames = 3)
        val fast = tabWidth()

        // Back to rest, then the same three frames at the normal pace.
        selected = 0
        compose.settle(frames = 240)
        motion = MotionSettings(speed = 1f)
        compose.settle(frames = 2)
        selected = 1
        compose.settle(frames = 3)
        val normal = tabWidth()

        assertTrue("three frames in: $fast px at twice the speed, $normal px at the normal one", fast > normal)
    }

    private var motion by mutableStateOf(MotionSettings())

    private fun show(initial: MotionSettings) {
        motion = initial
        compose.setContent {
            LessonsTheme {
                CompositionLocalProvider(LocalMotion provides motion) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LessonsFloatingToolbar(
                            items = Labels.map { label ->
                                ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                            },
                            selectedIndex = selected,
                            action = ToolbarAction(Icons.Rounded.Settings, "Настройки") {},
                        )
                    }
                }
            }
        }
    }

    private fun labelDrawn(): Boolean =
        compose.onAllNodesWithText(Labels[1], useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun labelWidth(): Int =
        compose.onNodeWithText(Labels[1], useUnmergedTree = true).fetchSemanticsNode().size.width

    private fun tabWidth(): Int = compose
        .onNode(hasText(Labels[1]) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
        .fetchSemanticsNode()
        .size.width

    private companion object {

        /** How long the label may take to be composed after the tap. */
        const val MaxFramesToAppear = 10

        /**
         * The second is long, so that at Robolectric's pixel or so a letter it
         * takes a spring several frames to open round it. Latin, because the
         * engine Robolectric draws text with gives Cyrillic no width at all.
         */
        val Labels = listOf("Today", "Calendar of the whole school year", "Homework")
    }
}
