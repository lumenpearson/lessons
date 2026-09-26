package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.down
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.moveBy
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.up
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The layer that closes the arranging mode from anywhere but the bar (#182),
 * asked with the real bar on top of it and a page underneath that counts its
 * own taps — because what can go wrong here is not the layer on its own but
 * which of the three a touch reaches.
 */
@RunWith(RobolectricTestRunner::class)
class ArrangingDismissLayerTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        // The jiggle and `MarqueeText` run for ever; see `MarqueeClockTest`.
        compose.mainClock.autoAdvance = false
    }

    private val labels = listOf("Сегодня", "Календарь", "Задания")

    private var dismissed = 0
    private var pageTapped = false
    private var reordering by mutableStateOf(true)
    private var reported: List<Int>? = null

    @Test
    fun `a tap on the page closes the mode and does not reach the page`() {
        setShell()

        compose.onNodeWithTag(Page).performTouchInput { click(Offset(20f, 20f)) }
        compose.settle()

        assertEquals("the tap outside the bar did not close the mode", 1, dismissed)
        assertFalse("the tap reached the page under the layer", pageTapped)
    }

    @Test
    fun `a drag on the page closes the mode when it lifts, not when it lands`() {
        setShell()

        compose.onNodeWithTag(Page).performTouchInput {
            down(Offset(20f, 20f))
            moveBy(Offset(0f, 300f))
        }
        compose.settle()
        assertEquals("the mode closed while the finger was still down", 0, dismissed)

        compose.onNodeWithTag(Page).performTouchInput { up() }
        compose.settle()
        assertEquals(1, dismissed)
    }

    @Test
    fun `a tap on a tab is still the bar's, and the layer does not see it`() {
        // The bar closes the mode on a tap of its own; the layer raising a
        // second close for the same tap would be harmless today and a double
        // action the day closing does anything more than flip a flag.
        setShell()

        compose.onNodeWithContentDescription(labels[1]).performClick()
        compose.settle()

        assertFalse("the bar's own tap did not close the mode", reordering)
        assertEquals("the layer also took a tap that landed on the bar", 0, dismissed)
    }

    @Test
    fun `a tab can still be carried with the layer under the bar`() {
        setShell()

        compose.dragFarRight(labels[0])

        assertEquals(listOf(1, 2, 0), reported)
        assertEquals("the layer took a drag that started on the bar", 0, dismissed)
    }

    private fun setShell() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .testTag(Page)
                            .clickable { pageTapped = true },
                    )
                    if (reordering) ArrangingDismissLayer(onDismiss = { dismissed++ })
                    LessonsFloatingToolbar(
                        modifier = Modifier.align(Alignment.BottomCenter),
                        items = labels.map { label ->
                            ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                        },
                        selectedIndex = 0,
                        reorderable = true,
                        reordering = reordering,
                        onReorderingChange = { reordering = it },
                        onReorder = { reported = it },
                    )
                }
            }
        }
        compose.settle()
    }

    private companion object {
        const val Page = "page"
    }
}
