package com.lumenpearson.lessons.ui.legal

import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.core.data.legal.BundledLegal
import com.lumenpearson.lessons.core.data.legal.LegalDocument
import com.lumenpearson.lessons.core.data.legal.LegalEdition
import com.lumenpearson.lessons.core.data.legal.LegalText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuide
import com.lumenpearson.lessons.core.model.DocsGuidePage
import com.lumenpearson.lessons.core.model.DocsSpan
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The copy of the terms and the policy the APK carries, and the sheet that
 * shows it when the browser is not an answer.
 *
 * The first two tests are about the build rather than the screen: they read
 * the documents through the app's own asset manager, which only works when
 * `app/build.gradle.kts` really mounts `docs/legal/` — so an offline reader is
 * never shown «не удалось открыть документ» because a source set was dropped.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
class LegalDocumentSheetTest {

    @get:Rule val compose = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    fun holdTheClock() {
        // The loader in the sheet is a perpetual animation; see AboutCardTest.
        compose.mainClock.autoAdvance = false
    }

    @Test
    fun `the APK carries both documents in both languages`() {
        LegalDocument.entries.forEach { document ->
            listOf("ru", "en").forEach { language ->
                val text = BundledLegal.read(context, document, language)
                assertTrue("${document.slug}.$language.md is not in the APK's assets", text != null)
                assertTrue(
                    "${document.slug}.$language.md parsed to too few sections",
                    (text?.guide?.pages?.size ?: 0) >= 5,
                )
            }
        }
    }

    @Test
    fun `the bundled copy names the edition it is`() {
        val edition = BundledLegal.read(context, LegalDocument.PRIVACY, "ru")?.edition
        assertTrue("legal.json is not in the APK's assets, or names no edition", edition != null)
        assertTrue("an edition starts at 1", (edition?.edition ?: 0) >= 1)
    }

    private val sample = LegalText(
        guide = DocsGuide(
            title = "Условия",
            language = "ru",
            pages = listOf(
                DocsGuidePage(
                    id = "SCOPE",
                    title = "О чём этот документ",
                    label = "Охват",
                    summary = "",
                    blocks = listOf(DocsBlock.Paragraph(listOf(DocsSpan("Первый абзац.")))),
                ),
                DocsGuidePage(
                    id = "CONTACT",
                    title = "Контакты",
                    label = "Контакты",
                    summary = "",
                    blocks = listOf(DocsBlock.Paragraph(listOf(DocsSpan("Второй абзац.")))),
                ),
            ),
        ),
        edition = LegalEdition(1, LocalDate.of(2026, 9, 25)),
    )

    private fun show(load: LegalLoad, url: String?) {
        compose.setContent {
            LessonsTheme {
                LegalDocumentBody(load = load, url = url)
            }
        }
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `every section is on one page, under its heading`() {
        // A policy is read top to bottom; the guide's pager would hide eleven
        // sections behind a swipe from somebody being asked to accept them.
        show(LegalLoad.Ready(sample), url = null)

        compose.onNodeWithText("О чём этот документ").assertIsDisplayed()
        compose.onNodeWithText("Первый абзац.").assertIsDisplayed()
        compose.onNodeWithText("Контакты").assertIsDisplayed()
        compose.onNodeWithText("Второй абзац.").assertIsDisplayed()
    }

    @Test
    fun `the edition and its date are said above the text`() {
        show(LegalLoad.Ready(sample), url = null)

        compose.onNodeWithText("Редакция 1 от 25 сентября 2026", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a sheet shown in place of the browser says so and offers the link`() {
        show(LegalLoad.Ready(sample), url = "https://example.org/terms.ru.md")

        compose.onNodeWithText("Открыть актуальную редакцию").assertIsDisplayed()
        compose.onNodeWithText("Это копия, встроенная в приложение", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a build with no address does not offer a link it does not have`() {
        show(LegalLoad.Ready(sample), url = null)

        compose.onAllNodesWithText("Открыть актуальную редакцию").assertCountEquals(0)
    }

    @Test
    fun `a missing document says so rather than drawing an empty policy`() {
        show(LegalLoad.Missing, url = null)

        compose.onNodeWithText("Не удалось открыть документ.").assertIsDisplayed()
    }

    @Test
    fun `the browser is used only with an address and a network that reaches past the router`() {
        assertEquals(LegalTarget.Web("https://a/b"), legalTarget("https://a/b", online = true))
        assertEquals(LegalTarget.Bundled, legalTarget("https://a/b", online = false))
        assertEquals(LegalTarget.Bundled, legalTarget(null, online = true))
        assertEquals(LegalTarget.Bundled, legalTarget(null, online = false))
    }
}
