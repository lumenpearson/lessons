package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.FloatingToolbarDefaults
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The pill is as tall without a button beside it as with one (#240).
 *
 * The two cases go through different Material overloads, and only the one
 * without a button balances its content's padding against the interactive
 * insets it reads off the content's alignment lines. While the pill's ends were
 * drawn inside the rows and its top and bottom outside them, the back button
 * read as 8 dp further in from the side than from the top, and Material padded
 * the pill by twice that: 80 dp on every settings page of a reader who does not
 * manage the class, 64 dp on the same page with the debug button beside it.
 *
 * Asked of the pill's own bounds, because the bar around it measures the same
 * either way: the overload with a button is a slot of 80 dp with a 64 dp pill
 * centred in it, which is exactly the height the broken pill had on its own.
 * That is also why only the cases without a button are here: with one, the
 * modifier the tag rides on lands on that slot rather than on the pill, and
 * the pill inside it is held at [FloatingToolbarDefaults.ContainerSize] by
 * Material itself — the height these assert the pill without one keeps.
 *
 * And it stands where it stands beside a button. That slot is taller than the
 * pill, so a bar without one that merely wrapped a 64 dp pill was 16 dp shorter
 * and, sitting on the bottom of the screen, carried its pill 8 dp lower: the
 * pill dropped as settings opened, which the 16 dp of padding had hidden.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarPillHeightTest {

    @get:Rule
    val compose = createComposeRule()

    // The back mode draws its title through `MarqueeText`, whose clock never
    // idles once a title overflows; held, so no title can hang the test.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private val tabs = listOf("Сегодня", "Календарь", "Задания").map { label ->
        ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
    }

    @Test
    fun `a page with no button beside the pill keeps the pill's height`() {
        assertPillHeight(back = true)
    }

    @Test
    fun `tabs with no button beside them keep the pill's height`() {
        assertPillHeight(back = false)
    }

    @Test
    fun `a page with no button holds its pill where a page with one does`() {
        // Both bars in one composition — a rule composes once per test — each at
        // the top of a box of its own, the second box one box lower.
        compose.setContent {
            LessonsTheme {
                Column(modifier = Modifier.fillMaxSize()) {
                    for (action in listOf(null, ToolbarAction(Icons.Rounded.BugReport, "Отладка") {})) {
                        Box(modifier = Modifier.fillMaxWidth().height(BoxHeight)) {
                            LessonsFloatingToolbar(
                                title = "Синхронизация",
                                onBackClick = {},
                                action = action,
                            )
                        }
                    }
                }
            }
        }
        compose.settle(frames = 30)

        // With a button the tag is on Material's slot rather than on the pill,
        // and Material centres the pill in it, so the slot's centre is the pill's.
        val (alone, beside) = compose.onAllNodesWithTag(ToolbarPillTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .map { it.boundsInRoot.center.y }
        val box = with(compose.density) { BoxHeight.toPx() }

        assertEquals(
            "the pill's centre without a button beside it is $alone px from the top of " +
                "its box, and ${beside - box} px with one",
            beside - box,
            alone,
            0.5f,
        )
    }

    private fun assertPillHeight(back: Boolean) {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    LessonsFloatingToolbar(
                        items = if (back) emptyList() else tabs,
                        selectedIndex = if (back) -1 else 0,
                        title = if (back) "Синхронизация" else null,
                        onBackClick = if (back) ({}) else null,
                    )
                }
            }
        }
        compose.settle(frames = 30)

        val pill = compose.onNodeWithTag(ToolbarPillTag, useUnmergedTree = true)
            .fetchSemanticsNode().size.height
        val expected = with(compose.density) { FloatingToolbarDefaults.ContainerSize.roundToPx() }
        assertEquals(
            "the pill (back mode $back, no button beside it) is $pill px, " +
                "where Material's container is $expected px",
            expected,
            pill,
        )
    }

    private companion object {

        /** Taller than either bar, so each sits at the top of its own. */
        val BoxHeight = 200.dp
    }
}
