package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
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

    /** Narrower than [tooLong] and wider than [short]; the window in both cases. */
    private val boxWidth = 120.dp

    /** Wide enough that a weighted line and a neighbour both fit in it. */
    private val rowWidth = 300.dp

    @get:Rule
    val compose = createComposeRule()

    private val long = "По этому предмету ничего не задано"
    private val short = "Урок 3"

    /**
     * A line that overflows *here*, which the sentence above does not.
     *
     * Robolectric lays text out without fonts: every glyph costs about a pixel,
     * so «По этому предмету ничего не задано» measures 35 px and sits inside a
     * 120 dp box with room to spare. Three tests below were written against it
     * and passed without ever reaching the scrolling they are named for. No
     * width measured in this environment says anything about a phone; what a
     * test here can hold is the decision, and for that the string only has to
     * be wider than its window whatever a glyph happens to cost.
     */
    private val tooLong = List(20) { long }.joinToString(" · ")

    /** A neighbour, so that a row has something a marquee could push out of it. */
    private val tail = "Конец"

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

    /**
     * That the line really is set scrolling, and not merely confined.
     *
     * Nothing in the semantics says «this one moves», and the bounds of a line
     * that scrolls and a line cut with «…» are the same to every matcher here.
     * What differs is the layout underneath: a scrolling line is laid out at the
     * width the string wants and drawn through a narrower window, an ellipsised
     * one is laid out at the window's width and cut. The text node's own width
     * is where the two are told apart without watching an animation, and it is
     * what fails if the width never reaches the decision — which is the way this
     * component breaks silently.
     */
    @Test
    fun `a line that does not fit is laid out at its full width`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(boxWidth)) {
                    MarqueeText(text = tooLong)
                }
            }
        }

        val window = with(compose.density) { boxWidth.roundToPx() }
        val laidOut = compose.onNodeWithText(tooLong).fetchSemanticsNode().size.width
        assertTrue(
            "A line that has to scroll is laid out at the width of the string and " +
                "scrolled through its window; one that fell back to an ellipsis is " +
                "laid out at the window's width and cut there. Laid out $laidOut, " +
                "window $window.",
            laidOut > window,
        )
    }

    /** And a line that fits is not given room it does not need. */
    @Test
    fun `a line that fits is laid out no wider than the window`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(boxWidth)) {
                    MarqueeText(text = short)
                }
            }
        }

        val window = with(compose.density) { boxWidth.roundToPx() }
        assertTrue(compose.onNodeWithText(short).fetchSemanticsNode().size.width < window)
    }

    /**
     * The unbounded width a marquee is measured at stays inside the marquee.
     *
     * This is what the previous version of this test tried to say by measuring
     * the line's own bounds, and those are the wrong number: the text node
     * really is as wide as the string — that is the test above — and it is the
     * slot the row gave it that has to stay put. A neighbour after it is the
     * only thing that can say so, because it is the thing that would move.
     */
    @Test
    fun `a scrolling line does not push its row apart`() {
        compose.setContent {
            LessonsTheme {
                Row(Modifier.width(rowWidth)) {
                    MarqueeText(text = tooLong, modifier = Modifier.weight(1f))
                    Text(text = tail)
                }
            }
        }

        val row = with(compose.density) { rowWidth.roundToPx() }
        val neighbour = compose.onNodeWithText(tail).fetchSemanticsNode()
        assertTrue(
            "the line after a scrolling one is still in the row: it starts at " +
                "${neighbour.positionInRoot.x} of $row",
            neighbour.positionInRoot.x < row,
        )
        assertTrue("and is not squeezed out of it", neighbour.size.width > 0)
    }

    /**
     * The common case, and the one a change here would break silently: almost
     * every line in the app fits, and none of them may move or fade.
     */
    @Test
    fun `a line that fits is laid out where an ordinary one would be`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(boxWidth)) {
                    MarqueeText(text = short)
                }
            }
        }

        val bounds = compose.onNodeWithText(short).getBoundsInRoot()
        assertEquals("the text starts at the box's leading edge", 0f, bounds.left.value, 0.5f)
        assertTrue("and does not fill it", (bounds.right - bounds.left).value < boxWidth.value)
    }

    /**
     * The crash this component shipped with, and the reason it no longer wraps
     * itself in a `BoxWithConstraints`.
     *
     * A `BoxWithConstraints` is a `SubcomposeLayout`, and a `SubcomposeLayout`
     * cannot answer «how tall would you be at this width» — asking throws
     * `IllegalStateException: Asking for intrinsic measurements of
     * SubcomposeLayout layouts is not supported`. Nothing in this repository
     * spells `IntrinsicSize`, so this looked safe; Material spells it inside
     * its own rows, and the app died on the main thread with an R8-obfuscated
     * stack that named neither this file nor a caller of it.
     *
     * `Row(Modifier.height(IntrinsicSize.Min))` is that question in one line.
     * It is the whole of the regression: this throws on the version that
     * measured inside a `BoxWithConstraints` and passes on the one that takes
     * its width from the layout it is already in. The string is one that
     * scrolls, so the marquee is asked the question too.
     */
    @Test
    fun `a parent may ask how tall this line would be`() {
        compose.setContent {
            LessonsTheme {
                Row(Modifier.width(boxWidth).height(IntrinsicSize.Min)) {
                    MarqueeText(text = tooLong)
                }
            }
        }

        compose.onNodeWithText(tooLong).assertIsDisplayed()
    }

    /** The text is the text: scrolling must not truncate what a reader copies. */
    @Test
    fun `the whole string is on screen whether it scrolls or not`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.width(boxWidth)) {
                    MarqueeText(text = tooLong)
                }
            }
        }

        compose.onNodeWithText(tooLong).assertIsDisplayed()
    }
}
