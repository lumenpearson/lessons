package com.lumenpearson.lessons.ui.join

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a refused code actually says on screen.
 *
 * `JoinErrorTest` next door pins which failure each status becomes; this pins
 * the sentence that reaches the person, which is the half that decides what
 * they do next. The three refusals need three different actions — check the
 * code with whoever gave it, open the bot, or wait — and a mapping that is
 * right while the wording is wrong helps nobody.
 *
 * This is also the only place the plural is exercised. Russian has three forms
 * and the rule is not «one or many»: 1 минуту, 2 минуты, 5 минут, and then 11
 * минут against 21 минуту. A `%d минут` written by hand is wrong twice in every
 * twenty, which is often enough to look like a typo and rare enough to survive
 * review.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class JoinErrorTextTest {

    @get:Rule val compose = createComposeRule()

    private fun show(error: JoinError) = compose.setContent {
        LessonsTheme { Text(text = error.asText().orEmpty()) }
    }

    @Test
    fun `an invite-only class points at the bot, not at the person who gave the code`() {
        show(JoinError.InviteOnly)

        // The code they hold is real. «Такого кода нет» would send them back to
        // whoever read it out, who cannot help.
        compose.onNodeWithText("только по личному приглашению", substring = true)
            .assertIsDisplayed()
        compose.onNodeWithText("📱 Подключить телефон", substring = true).assertIsDisplayed()
    }

    @Test
    fun `an unknown code is the one that sends them back to check it`() {
        show(JoinError.UnknownCode)

        compose.onNodeWithText("Такого кода нет", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a throttled attempt says how long to wait, in the right form of the word`() {
        show(JoinError.TooManyAttempts(minutes = 1))
        compose.onNodeWithText("через 1 минуту", substring = true).assertIsDisplayed()
    }

    @Test
    fun `two to four minutes take the second form`() {
        show(JoinError.TooManyAttempts(minutes = 3))
        compose.onNodeWithText("через 3 минуты", substring = true).assertIsDisplayed()
    }

    @Test
    fun `five and up take the third, and so do the teens`() {
        show(JoinError.TooManyAttempts(minutes = 15))
        compose.onNodeWithText("через 15 минут", substring = true).assertIsDisplayed()
    }

    @Test
    fun `twenty-one goes back to the first form, which is where a hand-written string breaks`() {
        show(JoinError.TooManyAttempts(minutes = 21))
        compose.onNodeWithText("через 21 минуту", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a wait with no number still says to wait`() {
        show(JoinError.TooManyAttempts(minutes = null))

        compose.onNodeWithText("Слишком много попыток", substring = true).assertIsDisplayed()
        // And does not invent one.
        compose.onNodeWithText("через", substring = true).assertDoesNotExist()
    }
}
