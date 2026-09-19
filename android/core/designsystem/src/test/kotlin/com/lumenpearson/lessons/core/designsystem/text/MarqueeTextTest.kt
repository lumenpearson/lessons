package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A line too long for its box scrolls instead of ending in «…».
 *
 * The decision is the whole of this component and it is the part that cannot be
 * read off the screen: `basicMarquee` hands the text unbounded width, so a text
 * node inside one never reports visual overflow and anything asking
 * `onTextLayout` is told `false` for ever. That is why [overflows] is a
 * function of its own and why it is measured here directly, rather than by
 * looking at a composed line and trying to tell a scrolling one from a still
 * one.
 *
 * The composed tests below hold the two things the measurement is in service
 * of: a line that fits is laid out exactly as it was before, and a line that
 * does not is still confined to its box rather than pushing the row apart.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class MarqueeTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val long = "По этому предмету ничего не задано"
    private val short = "Урок 3"

    // -- the decision ---------------------------------------------------------

    @Test
    fun `a line wider than its box has to scroll`() {
        assertTrue(overflows(measured = 601, available = 600))
    }

    @Test
    fun `a line that fits exactly does not`() {
        assertFalse(overflows(measured = 600, available = 600))
        assertFalse(overflows(measured = 1, available = 600))
    }

    /**
     * A line with no stated width cannot overflow — and the reason it cannot is
     * worth pinning, because it is not the reason it looks like.
     *
     * `Constraints.Infinity` means «nobody has said how wide this may be»,
     * which is what a `Row` hands a child with no `weight`. The branch in
     * [overflows] reads as though it is what stops such a line scrolling. It is
     * not: `Constraints.Infinity` *is* `Int.MAX_VALUE`, so `measured >
     * available` is already false for every string that could ever be measured.
     * Removing the branch changes no answer today, and a test asserting the
     * two calls below would pass without it — which is why this asserts the
     * identity instead.
     *
     * So the branch is there for the day that stops being true, and this is
     * what fails first if it does.
     */
    @Test
    fun `an unbounded width is the largest one, which is why nothing overflows it`() {
        assertEquals(
            "`overflows` treats Constraints.Infinity as «no bound» rather than as " +
                "a width. While it is also the largest Int, the comparison alone " +
                "would do; if Compose ever changes it, that stops being true and " +
                "the branch is what keeps a Row's unweighted child from scrolling.",
            Int.MAX_VALUE,
            Constraints.Infinity,
        )
        assertFalse(overflows(measured = 10_000, available = Constraints.Infinity))
    }

    // -- what the decision is in service of -----------------------------------

    @Test
    fun `a line that does not fit stays inside its box`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(120.dp)) {
                    MarqueeText(text = long)
                }
            }
        }

        val bounds = compose.onNodeWithText(long).getBoundsInRoot()
        assertTrue(
            "A scrolling line is given unbounded width to scroll through, and if " +
                "that width reached the layout it would push its row apart rather " +
                "than travel inside it. Measured ${bounds.right - bounds.left}.",
            (bounds.right - bounds.left).value <= 120f,
        )
    }

    /**
     * The common case, and the one a change here would break silently: almost
     * every line in the app fits, and none of them may move or fade.
     */
    @Test
    fun `a line that fits is laid out where an ordinary one would be`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(240.dp)) {
                    MarqueeText(text = short)
                }
            }
        }

        val bounds = compose.onNodeWithText(short).getBoundsInRoot()
        assertEquals("the text starts at the box's leading edge", 0f, bounds.left.value, 0.5f)
        assertTrue("and does not fill it", (bounds.right - bounds.left).value < 240f)
    }

    /** The text is the text: scrolling must not truncate what a reader copies. */
    @Test
    fun `the whole string is on screen whether it scrolls or not`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(120.dp)) {
                    MarqueeText(text = long)
                }
            }
        }

        compose.onNodeWithText(long).assertIsDisplayed()
    }
}
