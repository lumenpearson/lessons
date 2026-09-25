package com.lumenpearson.lessons.core.data.legal

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where a legal link goes, and what the bundled reader makes of what it finds.
 *
 * The address is built from two things nobody on the phone chose — the build's
 * folder and the phone's language — so every way the two can be odd is asked
 * here rather than discovered on the first screen of an install.
 */
class LegalLinksTest {

    private val base = "https://github.com/lumenpearson/lessons/blob/HEAD/docs/legal"

    @Test
    fun `a build with no folder links nothing`() {
        assertNull(legalUrl("", LegalDocument.TERMS, "ru"))
        assertNull(legalUrl("   ", LegalDocument.PRIVACY, "en"))
    }

    @Test
    fun `a folder that is not https links nothing`() {
        // Gradle refuses one already; this is for a caller that got the folder
        // from anywhere else. A policy fetched in the clear is one the café's
        // network can rewrite before it is read.
        assertNull(legalUrl("http://example.org/legal", LegalDocument.TERMS, "ru"))
        assertNull(legalUrl("https://", LegalDocument.TERMS, "ru"))
    }

    @Test
    fun `a trailing slash does not become a double one`() {
        assertEquals("$base/terms.ru.md", legalUrl("$base/", LegalDocument.TERMS, "ru"))
    }

    @Test
    fun `the slug and the language name the file`() {
        assertEquals("$base/terms.ru.md", legalUrl(base, LegalDocument.TERMS, "ru-RU"))
        assertEquals("$base/privacy.en.md", legalUrl(base, LegalDocument.PRIVACY, "en"))
    }

    @Test
    fun `a language with no text of its own reads the English one`() {
        // Ukrainian is the case worth naming: close enough to Russian that a
        // guess might pick it, and not the language the phone asked for.
        assertEquals("$base/privacy.en.md", legalUrl(base, LegalDocument.PRIVACY, "uk"))
        assertEquals("$base/terms.ru.md", legalUrl(base, LegalDocument.TERMS, "RU"))
    }

    @Test
    fun `a manifest that does not parse is no manifest`() {
        assertNull(parseLegalManifest(null))
        assertNull(parseLegalManifest(""))
        assertNull(parseLegalManifest("not json"))
    }

    @Test
    fun `an edition of zero or an unreadable date is not drawn`() {
        // «Редакция 0 от …» would be a claim about a document nobody made.
        assertNull(LegalManifest(edition = 0, effective = "2026-09-25").toEdition())
        assertNull(LegalManifest(edition = 1, effective = "25.09.2026").toEdition())
        assertEquals(
            LegalEdition(2, LocalDate.of(2026, 9, 25)),
            LegalManifest(edition = 2, effective = "2026-09-25").toEdition(),
        )
    }

    @Test
    fun `a file the manifest does not name falls back to the convention the link uses`() {
        assertEquals("terms.ru.md", legalFileName(null, LegalDocument.TERMS, "ru"))
        val renamed = LegalManifest(files = mapOf("privacy" to mapOf("en" to "policy.en.md")))
        assertEquals("policy.en.md", legalFileName(renamed, LegalDocument.PRIVACY, "en-GB"))
        assertEquals("privacy.ru.md", legalFileName(renamed, LegalDocument.PRIVACY, "ru"))
    }

    @Test
    fun `a missing document is missing rather than an empty page`() {
        assertNull(readLegal(open = { null }, document = LegalDocument.TERMS, language = "ru"))
        // Text that parses to no sections: a sheet titled «Условия
        // использования» over nothing would read as terms that say nothing.
        assertNull(
            readLegal(
                open = { name -> if (name == MANIFEST) null else "# Только заголовок\n\nи ни одного раздела" },
                document = LegalDocument.TERMS,
                language = "ru",
            ),
        )
    }

    @Test
    fun `a document opens without its manifest, with no edition to show`() {
        val text = readLegal(
            open = { name ->
                if (name == "privacy.en.md") "# Privacy\n\n## Who\n<!-- id: WHO; label: Who -->\n\nUs." else null
            },
            document = LegalDocument.PRIVACY,
            language = "en",
        )
        assertEquals(listOf("WHO"), text?.guide?.pages?.map { it.id })
        assertNull(text?.edition)
    }
}
