package com.lumenpearson.lessons.ui.diary

import androidx.compose.ui.test.junit4.createComposeRule
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the sign-in forms say about the password, read as a reader reads it:
 * every paragraph, one after the other (#175).
 *
 * The forms showed the sentence naming the diary's host beside the general
 * notice, and the notice opened by saying the same thing: «по HTTPS, прямо в
 * дневник» twice, one paragraph under the other. Counting the protocol across
 * what is actually drawn is the check that cannot be satisfied by a paragraph
 * that merely looks different.
 */
@RunWith(RobolectricTestRunner::class)
class PasswordPrivacyTest {

    @get:Rule
    val compose = createComposeRule()

    private fun paragraphs(host: String?): List<String> {
        var drawn = emptyList<String>()
        compose.setContent { LessonsTheme { drawn = passwordPrivacyParagraphs(host) } }
        compose.waitForIdle()
        return drawn
    }

    @Test
    @Config(qualifiers = "ru")
    fun `with a host, the Russian card says where the password goes once`() {
        val text = paragraphs("region.obramur.ru").joinToString("\n")

        assertEquals(text, 1, Regex("HTTPS").findAll(text).count())
        assertEquals(text, 1, Regex("прямо в дневник").findAll(text).count())
        assertEquals(text, 1, Regex("region\\.obramur\\.ru").findAll(text).count())
    }

    @Test
    @Config(qualifiers = "en")
    fun `with a host, the English card says where the password goes once`() {
        val text = paragraphs("region.obramur.ru").joinToString("\n")

        assertEquals(text, 1, Regex("HTTPS").findAll(text).count())
        assertEquals(text, 1, Regex("straight to the diary").findAll(text).count())
    }

    @Test
    @Config(qualifiers = "ru")
    fun `without a host, the general notice stands alone and still says it`() {
        val text = paragraphs(null).joinToString("\n")

        assertEquals(text, 1, Regex("HTTPS").findAll(text).count())
    }
}
