package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Lesson
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * How a day marks its moment (#219): the «сейчас» line above the running
 * lesson as well as in a break, and the diary's marks at a row's trailing end.
 *
 * Asked with the real group on screen, because what changed is where the word
 * is drawn — in the row it was in before, it sat on the row's own line, and
 * every assertion about «is there a now» would still have passed.
 */
// marquee clock: the subjects and times here fit their rows at this width, so no line scrolls.
@RunWith(RobolectricTestRunner::class)
class LessonGroupNowTest {

    @get:Rule
    val compose = createComposeRule()

    private val day = listOf(
        Lesson(index = 1, subject = "Алгебра", startsAt = LocalTime.of(9, 0), endsAt = LocalTime.of(9, 40)),
        Lesson(index = 2, subject = "Физика", startsAt = LocalTime.of(9, 50), endsAt = LocalTime.of(10, 30)),
        Lesson(index = 3, subject = "История", startsAt = LocalTime.of(10, 50), endsAt = LocalTime.of(11, 30)),
    )

    @Test
    fun `the line goes above the running lesson, not into its row`() {
        compose.setContent {
            LessonsTheme { LessonGroup(lessons = day, now = LocalTime.of(10, 0)) }
        }

        val now = compose.onAllNodesWithText("now", substring = false)
        now.assertCountEquals(1)
        val line = now[0].getUnclippedBoundsInRoot()
        val subject = compose.onNodeWithText("Физика").getUnclippedBoundsInRoot()
        val before = compose.onNodeWithText("Алгебра").getUnclippedBoundsInRoot()
        assertTrue("«now» must sit above the running row, not on it", line.bottom <= subject.top)
        assertTrue("…and below the row before it", line.top >= before.bottom)
    }

    @Test
    fun `a mark from the diary is drawn in its lesson's row`() {
        compose.setContent {
            LessonsTheme {
                LessonGroup(
                    lessons = day,
                    now = LocalTime.of(10, 0),
                    grades = mapOf(day[1] to listOf("5")),
                )
            }
        }

        val mark = compose.onNodeWithText("5", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val subject = compose.onNodeWithText("Физика").getUnclippedBoundsInRoot()
        assertTrue("the mark shares the row of its lesson", mark.top < subject.bottom && mark.bottom > subject.top)
        assertTrue("…at its trailing end", mark.left > subject.left)
    }

    @Test
    fun `the marker lands on the running lesson, in a break, and after the day`() {
        assertEquals(NowMarker(currentIndex = 1, separatorBefore = 1), nowMarkerOf(day, LocalTime.of(10, 0)))
        assertEquals(NowMarker(currentIndex = -1, separatorBefore = 2), nowMarkerOf(day, LocalTime.of(10, 40)))
        assertEquals(NowMarker(currentIndex = -1, separatorBefore = 0), nowMarkerOf(day, LocalTime.of(7, 46)))
        assertEquals(NowMarker(currentIndex = -1, separatorBefore = 3), nowMarkerOf(day, LocalTime.of(15, 0)))
        assertEquals(NowMarker(currentIndex = -1, separatorBefore = -1), nowMarkerOf(day, null))
    }
}
