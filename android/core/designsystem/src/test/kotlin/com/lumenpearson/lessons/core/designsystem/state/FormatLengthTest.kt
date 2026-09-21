package com.lumenpearson.lessons.core.designsystem.state

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import java.time.Duration
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A length is not a countdown, and the difference is the rounding.
 *
 * [Duration.formatCountdown] rounds up on purpose: a truncating «0 мин» that
 * sits there for a whole minute is the worst thing a countdown can say. Reusing
 * it for the ribbon's «сколько всего» would have printed forty-six minutes
 * beside a lesson from 08:30 to 09:15, which is wrong in a way nobody could
 * explain and which no other screen would have contradicted.
 *
 * Both languages, because the words come out of resources and a length that
 * only got them right in Russian is the defect `StateHeroCardLanguageTest` was
 * written for, one function along.
 */
@RunWith(RobolectricTestRunner::class)
class FormatLengthTest {

    @get:Rule val compose = createComposeRule()

    private fun show(seconds: Long) = compose.setContent {
        LessonsTheme {
            Text(text = Duration.ofSeconds(seconds).formatLength())
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `a lesson of forty five minutes is forty five minutes`() {
        show(45 * 60)

        compose.onNodeWithText("45 мин").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `a part minute is dropped rather than rounded up to the next one`() {
        // 45:30. The countdown would say 46; a length says what has been
        // measured.
        show(45 * 60 + 30)

        compose.onNodeWithText("45 мин").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `nothing is nothing, not «меньше минуты»`() {
        // An entry of no length is a data error. «Меньше минуты» about it reads
        // as a very short lesson rather than as something being wrong.
        show(0)

        compose.onNodeWithText("0 мин").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `a length can never be negative, whatever the clock did`() {
        show(-600)

        compose.onNodeWithText("0 мин").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `the English length is in English`() {
        show(45 * 60)

        compose.onNodeWithText("45 мин").assertDoesNotExist()
        compose.onNodeWithText("45 min").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `past an hour it says hours and minutes, zero padded`() {
        show(65 * 60)

        compose.onNodeWithText("1 h 05 min").assertIsDisplayed()
    }
}
