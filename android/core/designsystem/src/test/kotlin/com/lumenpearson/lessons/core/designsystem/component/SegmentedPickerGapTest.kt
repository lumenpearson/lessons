package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The space around a segment in a tray is one number (#184).
 *
 * Asked as geometry rather than as a constant, because the defect was two
 * numbers that each looked right in their own file: the callers' 4 dp tray and
 * Material's 2 dp connected gap. What the reader sees is the distance from one
 * backing to the next against the distance from a backing to the tray's edge,
 * so that is what is measured.
 */
@RunWith(RobolectricTestRunner::class)
class SegmentedPickerGapTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        // The labels are `MarqueeText`; see `MarqueeClockTest`.
        compose.mainClock.autoAdvance = false
    }

    private val items = listOf("Неделя", "Месяц", "День")

    @Test
    fun `in a tray, segments are as far apart as they are from its edge`() {
        showPicker(inset = 4)

        val tray = compose.onNodeWithTag(Tray).getUnclippedBoundsInRoot()
        val segments = segmentBounds()

        assertEquals("from the tray's edge", 4f, (segments[0].left - tray.left).value, 0.5f)
        for (i in 1 until segments.size) {
            assertEquals(
                "between «${items[i - 1]}» and «${items[i]}»",
                4f,
                (segments[i].left - segments[i - 1].right).value,
                0.5f,
            )
        }
    }

    @Test
    fun `standing alone, segments keep the connected gap`() {
        // No tray, no inset to match: the settings rows draw the picker bare,
        // and there Material's connected group is the design.
        showPicker(inset = 0)

        val segments = segmentBounds()

        assertEquals(2f, (segments[1].left - segments[0].right).value, 0.5f)
    }

    /** In the merged tree the node holding a label's text is its whole segment. */
    private fun segmentBounds() = items
        .map { compose.onNodeWithText(it).getUnclippedBoundsInRoot() }
        .sortedBy { it.left.value }

    private fun showPicker(inset: Int) {
        compose.setContent {
            LessonsTheme {
                SegmentedPicker(
                    items = items,
                    selectedItem = items[1],
                    onItemSelected = {},
                    labelProvider = { it },
                    containerColor = if (inset > 0) {
                        MaterialTheme.colorScheme.rowContainer
                    } else {
                        Color.Transparent
                    },
                    contentPadding = PaddingValues(inset.dp),
                    modifier = Modifier.testTag(Tray),
                )
            }
        }
        compose.settle()
    }

    private companion object {
        const val Tray = "tray"
    }
}
