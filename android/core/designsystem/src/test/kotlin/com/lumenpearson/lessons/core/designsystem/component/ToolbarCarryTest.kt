package com.lumenpearson.lessons.core.designsystem.component

import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.HapticStrength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import androidx.compose.ui.test.junit4.createComposeRule

/**
 * Carrying a tab that is not the selected one (#225, #226), and the selected
 * tab's label slot (#227).
 *
 * Every drag test before these carried `labels[0]`, the selected tab, so the
 * path the owner reported — any other tab — was never walked.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarCarryTest {

    @get:Rule
    val compose = createComposeRule()

    private val labels = listOf("Сегодня", "Календарь", "Задания")
    private lateinit var view: View

    @Before
    fun setUp() {
        compose.mainClock.autoAdvance = false
        LessonsHaptics.enabled.value = true
        LessonsHaptics.strength.value = HapticStrength.SUBTLE
    }

    private fun setArranging() {
        compose.setContent {
            view = LocalView.current
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = labels.map { ToolbarItem(icon = Icons.Rounded.Settings, label = it) {} },
                    selectedIndex = 0,
                    reorderable = true,
                    reordering = true,
                )
            }
        }
        compose.settle(frames = 120)
    }

    @Test
    fun `picking up a tab that is not selected is felt`() {
        setArranging()

        compose.holdFarRight(labels[1])

        assertEquals(
            "nothing was played when an inactive tab was picked up",
            HapticFeedbackConstants.KEYBOARD_TAP,
            shadowOf(view).lastHapticFeedbackPerformed(),
        )
    }

    @Test
    fun `a tab carried off the end of the row stops on the last slot`() {
        setArranging()
        val slots = compose.drawnCentres(labels)

        compose.holdFarRight(labels[1])

        assertEquals(
            "the carried tab was drawn out past the end of the row",
            slots.getValue(labels[2]),
            compose.drawnCentres(labels).getValue(labels[1]),
            // The tabs left behind jiggle in this mode, a few dp either way;
            // unclamped, the carried one was thousands of dp out.
            5f,
        )
    }

    @Test
    fun `an unselected disc is never the pill's colour`() {
        val scheme = lightColorScheme()
        // Invisible, whatever colour it fades from (#254).
        assertEquals(0f, tabContainerColor(selected = false, held = false, scheme = scheme).alpha)
        assertNotEquals(scheme.primary, tabContainerColor(selected = false, held = true, scheme = scheme))
        assertEquals(scheme.background, tabContainerColor(selected = true, held = false, scheme = scheme))
    }

    @Test
    fun `the selected label may grow into what the row can spare`() {
        // Three tabs and an action on a 411 dp phone leave 139 dp, well past
        // the 80 dp «Календарь» did not fit in; a crowded narrow row never
        // gets less than the 80 dp every label used to have.
        assertEquals(139.dp, spareLabelWidth(411.dp, items = 3, hasAction = true))
        assertEquals(80.dp, spareLabelWidth(320.dp, items = 3, hasAction = true))
    }
}
