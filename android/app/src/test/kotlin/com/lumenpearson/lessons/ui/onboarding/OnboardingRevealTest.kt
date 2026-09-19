package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [OnboardingReveal] stacks what it is given, rather than piling it up.
 *
 * This is a layout fact and nothing else in the project could see it. The
 * wrapper was a `Box` taking a plain `@Composable () -> Unit`, which is
 * invisible at six of its seven call sites because they pass one child. The
 * seventh is «Разрешения», which passes a loop — so the three permission cards
 * were placed at `TopStart` on top of one another, the last one drawn took
 * every tap, and two of the permissions the whole alerts feature depends on
 * could neither be read nor pressed on step four of five of the first run.
 *
 * `./gradlew test`, `assembleDebug` and `assembleRelease` were all green over
 * it: nothing crashed, nothing was logged, and the «Далее / Позже» button even
 * kept saying «Позже» correctly, because it reads the permission state rather
 * than the screen. A composable that puts its children in the wrong place is
 * only visible to something that measures where they landed.
 *
 * So this measures it. Two children of known height, and the second must begin
 * at or below where the first ends.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class OnboardingRevealTest {

    @get:Rule
    val compose = createComposeRule()

    private val childHeight = 40.dp

    @Test
    fun `two children are stacked, not drawn on top of each other`() {
        compose.setContent {
            LessonsTheme {
                OnboardingReveal {
                    Box(Modifier.testTag("first").height(childHeight).width(100.dp))
                    Box(Modifier.testTag("second").height(childHeight).width(100.dp))
                }
            }
        }

        compose.onNodeWithTag("first").assertIsDisplayed()
        compose.onNodeWithTag("second").assertIsDisplayed()

        val first = compose.onNodeWithTag("first").getBoundsInRoot()
        val second = compose.onNodeWithTag("second").getBoundsInRoot()

        assertTrue(
            "The second child starts at ${second.top} while the first ends at ${first.bottom}: " +
                "they overlap, which is what a Box does to a loop of cards.",
            second.top.value >= first.bottom.value,
        )
    }

    /**
     * The common case is still one child, and it must not have moved.
     *
     * Six of the seven call sites pass a single title or a single card. A
     * `Column` lays one child out exactly where a `Box` did — top-start, wrapped
     * to the child — and this says so rather than leaving it to be assumed.
     */
    @Test
    fun `one child is laid out where it always was`() {
        compose.setContent {
            LessonsTheme {
                OnboardingReveal {
                    Box(Modifier.testTag("only").height(childHeight).width(100.dp))
                }
            }
        }

        val bounds = compose.onNodeWithTag("only").getBoundsInRoot()

        assertTrue("Expected the child at the top, found ${bounds.top}", bounds.top.value == 0f)
        assertTrue("Expected the child at the start, found ${bounds.left}", bounds.left.value == 0f)
    }
}
