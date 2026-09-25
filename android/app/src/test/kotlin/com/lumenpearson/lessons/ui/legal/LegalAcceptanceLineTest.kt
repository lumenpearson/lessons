package com.lumenpearson.lessons.ui.legal

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.legal.LegalDocument
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The line under the first screen's button, as it ships.
 *
 * What it has to get right is small and entirely visible: the sentence a reader
 * is told, word for word, with exactly the two document names as links, each
 * opening its own document. The words are read from the shipped resources
 * rather than typed here, so a translation that dropped a placeholder or a
 * space fails in the language it broke.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class LegalAcceptanceLineTest {

    @get:Rule val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    private fun notice(onOpen: (LegalDocument) -> Unit = {}): AnnotatedString? = legalNoticeText(
        template = context.getString(R.string.onboarding_legal_notice),
        termsLabel = context.getString(R.string.onboarding_legal_terms),
        privacyLabel = context.getString(R.string.onboarding_legal_privacy),
        linkStyles = TextLinkStyles(),
        onOpen = onOpen,
    )

    @Test
    fun `the Russian sentence is the one the design gives`() {
        assertEquals(
            "Продолжая, вы принимаете Условия использования и Политику конфиденциальности",
            notice()?.text,
        )
    }

    @Test
    @Config(qualifiers = "en-w411dp")
    fun `the English sentence keeps its spaces and both links`() {
        val text = checkNotNull(notice())
        assertEquals("By continuing, you accept the Terms of Use and the Privacy Policy", text.text)
        assertEquals(listOf("Terms of Use", "Privacy Policy"), linkedWords(text))
    }

    @Test
    fun `exactly the two document names are links`() {
        val text = checkNotNull(notice())
        assertEquals(listOf("Условия использования", "Политику конфиденциальности"), linkedWords(text))
    }

    @Test
    fun `each link opens its own document`() {
        val opened = mutableListOf<LegalDocument>()
        val text = checkNotNull(notice { opened += it })
        text.getLinkAnnotations(0, text.length).forEach { range ->
            val link = range.item as LinkAnnotation.Clickable
            checkNotNull(link.linkInteractionListener).onClick(link)
        }
        assertEquals(listOf(LegalDocument.TERMS, LegalDocument.PRIVACY), opened)
    }

    @Test
    fun `a template that lost a placeholder is refused rather than drawn half-linked`() {
        // The template may be a reader's correction. One that dropped «%2$s»
        // would otherwise tell somebody they accept a policy with no way to
        // open it.
        assertNull(legalNoticeText("Продолжая, вы принимаете %1\$s", "a", "b", TextLinkStyles()) {})
        assertNull(legalNoticeText("%1\$s и %1\$s", "a", "b", TextLinkStyles()) {})
        assertNull(legalNoticeText("%1\$s и %3\$s", "a", "b", TextLinkStyles()) {})
    }

    @Test
    fun `a language may put the links in its own order`() {
        val text = checkNotNull(legalNoticeText("%2\$s, then %1\$s", "terms", "privacy", TextLinkStyles()) {})
        assertEquals("privacy, then terms", text.text)
        assertEquals(listOf("privacy", "terms"), linkedWords(text))
    }

    @Test
    fun `the line is drawn, and says the whole sentence`() {
        compose.setContent {
            LessonsTheme {
                LegalAcceptanceLine(onOpen = {})
            }
        }
        compose.onNodeWithText(
            "Продолжая, вы принимаете Условия использования и Политику конфиденциальности",
        ).assertIsDisplayed()
    }

    @Test
    fun `the design credit on the about card no longer runs into its link`() {
        // #155: «по мотивам » lost its trailing space to aapt2 and the card said
        // «по мотивамEssentials». One template keeps the space where it was typed.
        val link = LinkAnnotation.Url("https://example.org")
        val credit = linkedSentence(
            context.getString(R.string.about_design_credit),
            listOf(context.getString(R.string.about_design_credit_link) to link),
        )
        assertEquals(
            "Оформление, шрифт и компоненты — по мотивам Essentials, лицензия MIT.",
            credit?.text,
        )
    }

    private fun linkedWords(text: AnnotatedString): List<String> =
        text.getLinkAnnotations(0, text.length).map { text.text.substring(it.start, it.end) }
}
