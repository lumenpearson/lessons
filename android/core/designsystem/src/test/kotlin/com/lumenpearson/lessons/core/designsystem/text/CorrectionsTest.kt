package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What [correctedString] actually draws, composed.
 *
 * The rules here are about the order of two branches and one `runCatching`,
 * and all three are invisible to a unit test of [Corrections] itself: the
 * implementation is asked the same question either way, and what changes is
 * whether the composable asks it. That is the shape the first version got
 * wrong — it returned the shipped string whenever the mode was off, which
 * meant switching the mode off reverted every correction on screen while the
 * session sheet went on listing them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class CorrectionsTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `a corrected string is drawn corrected`() {
        show(FakeCorrections(enabled = true, corrections = mapOf(R.string.ds_state_break to "Отдых"))) {
            Text(correctedString(R.string.ds_state_break))
        }
        compose.onNodeWithText("Отдых").assertIsDisplayed()
    }

    @Test
    fun `and stays corrected once the mode is switched off`() {
        // Switching the mode off is how a reader takes the outlines away and
        // reads the app in their own wording. Reverting the corrections at
        // that moment takes away the only way to check them.
        show(FakeCorrections(enabled = false, corrections = mapOf(R.string.ds_state_break to "Отдых"))) {
            Text(correctedString(R.string.ds_state_break))
        }
        compose.onNodeWithText("Отдых").assertIsDisplayed()
    }

    @Test
    fun `nothing is registered while the mode is off`() {
        val corrections = FakeCorrections(enabled = false)
        show(corrections) { Text(correctedString(R.string.ds_state_break)) }
        assertEquals(emptyList<Int>(), corrections.noted)
    }

    @Test
    fun `what is registered is the sentence, not the pattern`() {
        val corrections = FakeCorrections(enabled = true)
        show(corrections) { Text(correctedString(R.string.ds_state_next_subject, "Алгебра")) }
        compose.onNodeWithText("Далее — Алгебра").assertIsDisplayed()
        assertEquals(listOf("Далее — Алгебра"), corrections.notedText)
    }

    @Test
    fun `a correction on a pattern is formatted with the arguments`() {
        show(
            FakeCorrections(
                enabled = true,
                corrections = mapOf(R.string.ds_state_next_subject to "Потом %1\$s"),
            ),
        ) {
            Text(correctedString(R.string.ds_state_next_subject, "Алгебра"))
        }
        compose.onNodeWithText("Потом Алгебра").assertIsDisplayed()
    }

    /**
     * The one way a correction can take a screen down with it.
     *
     * A pattern typed over — the `%1$s` deleted, or turned into something
     * Java's formatter refuses — throws `IllegalFormatException` out of the
     * composable drawing it. From inside a proofreading tool that would take
     * down the screen being proofread, so the shipped pattern is used instead
     * and the worst a bad correction does is not appear.
     */
    @Test
    fun `a correction that breaks the arguments falls back to the shipped line`() {
        show(
            FakeCorrections(
                enabled = true,
                corrections = mapOf(R.string.ds_state_next_subject to "Потом %2\$s"),
            ),
        ) {
            Text(correctedString(R.string.ds_state_next_subject, "Алгебра"))
        }
        compose.onNodeWithText("Далее — Алгебра").assertIsDisplayed()
    }

    private fun show(corrections: Corrections, content: @Composable () -> Unit) =
        compose.setContent {
            LessonsTheme {
                CompositionLocalProvider(LocalCorrections provides corrections, content = content)
            }
        }

    private class FakeCorrections(
        override val enabled: Boolean,
        private val corrections: Map<Int, String> = emptyMap(),
    ) : Corrections {

        val noted = mutableListOf<Int>()
        val notedText = mutableListOf<String>()

        override fun correctionOf(id: Int, shipped: String): String = corrections[id] ?: shipped

        override fun noteOnScreen(id: Int, shown: String) {
            noted += id
            notedText += shown
        }

        override fun forget(id: Int, shown: String) {
            noted -= id
            notedText -= shown
        }

        override fun keysBehind(shown: String): List<Int> = emptyList()

        override fun edit(ids: List<Int>) = Unit
    }
}
