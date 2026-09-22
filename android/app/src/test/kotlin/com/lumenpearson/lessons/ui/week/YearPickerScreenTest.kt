package com.lumenpearson.lessons.ui.week

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Учебный год», drawn.
 *
 * Written after the day ribbon shipped with a crash in it that every test
 * passed over: three suites covered its arithmetic, its focus and its shader
 * ladder, and not one of them ever drew the screen. This picker arrived in the
 * same batch and was in exactly that position — built, never composed.
 *
 * What it has to get right is small and entirely visible: five years around the
 * one this phone is in, each saying whether it is downloaded, and a press that
 * names the year it was on rather than the one next to it.
 */
// marquee clock: `YearPickerSheet` is given years and term names here —
// «2026/27» and the like, the shortest labels in the app, in a full-width
// sheet at 411 dp.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class YearPickerScreenTest {

    @get:Rule val compose = createComposeRule()

    private var picked: Int? = null
    private var dismissed = false

    private fun show(current: Int = 2026, synced: Set<Int> = setOf(2026)) = compose.setContent {
        LessonsTheme {
            YearPickerSheet(
                currentYear = current,
                todayYear = 2026,
                syncedYears = synced,
                loadingYear = null,
                onPick = { picked = it },
                onDismiss = { dismissed = true },
            )
        }
    }

    @Test
    fun `the five years around today are offered, named as a school names them`() {
        show()

        // 2024/25 through 2028/29. Two digits for the second half, because that
        // is what is printed on a timetable.
        compose.onNodeWithText("2026/27").assertIsDisplayed()
        compose.onNodeWithText("2024/25").assertIsDisplayed()
        compose.onNodeWithText("2028/29").assertIsDisplayed()
    }

    @Test
    fun `a year says whether it is here, because the answer changes what a press costs`() {
        show(synced = setOf(2026, 2027))

        // «загружен» beside the years that are, «не загружен» beside the rest —
        // a press on the second is a download over a school's wifi, and a
        // picker that did not say so would look like it had simply hung.
        //
        // Matched as a pair rather than by the word alone: «загружен» is a
        // substring of «не загружен», so asking for one of them finds both and
        // the assertion passes whichever way round the rows are.
        compose.onNode(hasText("2026/27") and hasText("загружен")).assertIsDisplayed()
        compose.onNode(hasText("2024/25") and hasText("не загружен")).assertIsDisplayed()
    }

    @Test
    fun `pressing a year picks that year and closes`() {
        show()

        compose.onNodeWithText("2027/28").performClick()

        assertEquals(2027, picked)
        assertEquals(true, dismissed)
    }

    @Test
    fun `the list is centred on today rather than on the year being shown`() {
        // Scrolled a long way out. The list must not walk away with the
        // reader: the year somebody keeps coming back to is the one they are
        // in, and a window that re-centred on every press would put it off the
        // end after two.
        show(current = 2028)

        compose.onNodeWithText("2026/27").assertIsDisplayed()
        compose.onNodeWithText("2024/25").assertIsDisplayed()
    }
}
