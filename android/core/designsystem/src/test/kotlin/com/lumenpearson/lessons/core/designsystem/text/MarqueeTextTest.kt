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
import java.io.File
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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

    /**
     * The clock is not advanced on its own, and without this the suite hangs.
     *
     * [MarqueeText] scrolls for as long as the line is on screen — the whole
     * point of it, and the fix for a row that stopped after three passes and
     * sat there clipped. A perpetual animation means Compose's clock is never
     * idle, and `waitForIdle` — which every assertion below calls into — waits
     * for exactly that. With `autoAdvance` left on, the three composed tests
     * here do not fail: they hang, and take the whole Gradle run with them,
     * which this project has already paid for once with `runTest` against
     * `WeekViewModel`'s clock.
     *
     * Nothing here needs animation frames. What is asserted is a layout: how
     * wide the line was measured, and whether it pushed its neighbour out of
     * the row. Recomposition and layout still happen with the clock held.
     */
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    /**
     * Two frames by hand, because the clock above is not running.
     *
     * [MarqueeText] needs a second pass by construction: the first lays the
     * line out and reports the width it was given, and only the recomposition
     * that width causes can decide to scroll. Compose drives recomposition from
     * frames, so with the clock held the decision is never reached and every
     * line looks like one that fits — which is how the first version of this
     * fix «passed» while turning the marquee off everywhere.
     *
     * Four frames rather than two, with nothing resting on the number: it is
     * cheap, and a settle that is one frame short fails as «the line does not
     * scroll», which is indistinguishable from the defect these tests exist to
     * catch.
     */
    private fun settle() {
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

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

        settle()

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

        settle()

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

        settle()

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

        settle()

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

        settle()

        compose.onNodeWithText(tooLong).assertIsDisplayed()
    }

    // -- how long it scrolls for ---------------------------------------------

    /**
     * That the scrolling does not stop while the line is still on screen.
     *
     * `basicMarquee` runs `MarqueeDefaults.Iterations` times — three — and then
     * parks the line at its start, clipped, for as long as it stays composed.
     * The budget is per composition, so a list ends up with rows in both
     * states: the one just scrolled into view moves, the one that has been
     * there since the screen opened does not. That is what was reported from a
     * real phone, and what it looks like in a screenshot is one row mid-scroll
     * beside one sitting still.
     *
     * **This is read off the source rather than watched**, the way
     * `NoEllipsisedLineTest` and `StabilityPromiseTest` read theirs, and the
     * reason is worth stating so nobody replaces it with something that looks
     * stronger and is not. A marquee moves its content at *draw* time: the text
     * node's bounds, its position and its semantics are identical at every
     * point of the scroll, so no matcher here can see the difference between
     * moving and parked. Advancing the clock and looking again finds the same
     * numbers. The only honest JVM-side statement about the iteration count is
     * about the argument, and the argument is the whole defect.
     *
     * The composed tests above are what hold the rest of it: that the line
     * scrolls at all, and that scrolling does not push its row apart.
     */
    @Test
    fun `the marquee is not left on its default three passes`() {
        val source = File(designSystemSources, "text/MarqueeText.kt")
        assertTrue("expected to read ${source.absolutePath}", source.isFile)

        val calls = source.readLines()
            .map { it.trim() }
            .filter { it.startsWith(".basicMarquee(") }

        assertEquals("one marquee in this file, and it is the subject", 1, calls.size)
        assertTrue(
            "`basicMarquee()` with no `iterations` stops after three passes and " +
                "leaves the line parked and clipped, which is the defect this " +
                "component exists to prevent — a line is only given a marquee " +
                "here because it cannot be read at the width it was given. " +
                "Found: ${calls.single()}",
            calls.single().contains("iterations = Int.MAX_VALUE"),
        )
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

        settle()

        compose.onNodeWithText(tooLong).assertIsDisplayed()
    }

    private companion object {

        /**
         * `:core:designsystem`'s own `src/main/kotlin`.
         *
         * Found by walking up for `settings.gradle.kts`, because Gradle runs
         * unit tests with the module directory as the working directory and an
         * IDE sometimes runs them from the repository root. The same walk as
         * `NoEllipsisedLineTest`, kept separate rather than shared: that one
         * discovers every module on purpose, and this one is about one file.
         */
        val designSystemSources: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    return@lazy File(
                        directory,
                        "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/" +
                            "core/designsystem",
                    )
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }
    }
}
