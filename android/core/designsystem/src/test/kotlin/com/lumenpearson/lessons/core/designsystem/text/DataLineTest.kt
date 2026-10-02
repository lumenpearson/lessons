package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * A line of the app's words and data scrolls only its data (#251).
 *
 * The split is read off the string resource's pattern — everything before its
 * first argument is the app's — and a one-line component lays the lead out at
 * its own width and gives the data what is left. Robolectric draws text at
 * about a pixel a letter, so the data is a long string and the box is narrow.
 */
@RunWith(RobolectricTestRunner::class)
class DataLineTest {

    @get:Rule
    val compose = createComposeRule()

    // The data overflows on purpose, which starts a marquee whose clock never
    // idles; held, and every frame here is stepped by hand.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `the lead is the words before the first argument`() {
        assertEquals("Домашнее задание на ", leadOf("Домашнее задание на %1\$s"))
        assertEquals("Схема ", leadOf("Схема %1\$s"))
        assertEquals("", leadOf("%1\$d мин"))
        assertEquals("100% done, ", leadOf("100%% done, %1\$s"))
        assertEquals("No arguments at all", leadOf("No arguments at all"))
    }

    @Test
    fun `a line is split where its lead ends, and never anywhere else`() {
        assertEquals(
            DataLine("Домашнее задание на ", "понедельник, 5 октября"),
            splitLine("Домашнее задание на %1\$s", "Домашнее задание на понедельник, 5 октября"),
        )
        // A line that starts with its data is data from its first letter.
        assertEquals(DataLine.of("10 мин"), splitLine("%1\$d мин", "10 мин"))
        // A correction that did not format fell back to the shipped pattern, so
        // the words drawn are not the words of the pattern read: drawn whole.
        assertEquals(DataLine.of("Homework for Monday"), splitLine("Задание на %1\$s", "Homework for Monday"))
    }

    @Test
    fun `correctedLine splits a real resource at its argument`() {
        var line by mutableStateOf<DataLine?>(null)
        compose.setContent {
            LessonsTheme { line = correctedLine(R.string.ds_state_next_subject, "Algebra") }
        }
        compose.mainClock.advanceTimeByFrame()

        val shown = checkNotNull(line)
        assertEquals("Algebra", shown.data)
        assertTrue("the lead is the resource's own words: «${shown.lead}»", shown.lead.isNotBlank())
        assertEquals(shown.lead + "Algebra", shown.whole)
    }

    @Test
    fun `a line drawn in two parts is one sentence to a reader`() {
        showLine()

        compose.onNodeWithText(Lead + LongData).assertExists()
        assertTrue(
            "the lead is a second thing to read",
            compose.onAllNodesWithText(Lead.trimEnd()).fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Asked of the pixels, because the parts have no semantics of their own:
     * two moments apart while the data scrolls, the lead's columns are the same
     * and the data's are not. A line scrolled as one piece moves both.
     */
    @Test
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `the lead stands still while the data scrolls`() {
        showLine()
        compose.mainClock.advanceTimeBy(ScrollingBy)
        val first = compose.onNodeWithText(Lead + LongData).captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(Apart)
        val second = compose.onNodeWithText(Lead + LongData).captureToImage().toPixelMap()

        val leadEnd = first.width * LeadShare / 100
        val dataStart = first.width - first.width * DataShare / 100
        val leadMoved = (0 until leadEnd).count { x -> column(first, x) != column(second, x) }
        val dataMoved = (dataStart until first.width).count { x -> column(first, x) != column(second, x) }

        assertEquals("columns of the lead changed while the data scrolled", 0, leadMoved)
        assertTrue("the data did not scroll at all", dataMoved > 0)
    }

    private fun showLine() {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.width(Narrow)) {
                    MarqueeText(line = DataLine(Lead, LongData))
                }
            }
        }
        repeat(Frames) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun column(pixels: PixelMap, x: Int): List<Color> = (0 until pixels.height).map { y -> pixels[x, y] }

    private companion object {
        const val Lead = "Homework for "
        val LongData = "Monday the fifth of October ".repeat(20).trim()
        val Narrow = 220.dp
        const val Frames = 4

        /** Past the marquee's first pause, so the data is moving. */
        const val ScrollingBy = 3_000L

        /** Long enough for the data to travel some pixels. */
        const val Apart = 500L

        /** The share of the line, from the left, that is surely the lead's. */
        const val LeadShare = 20

        /** The share, from the right, that is surely the data's. */
        const val DataShare = 40
    }
}
