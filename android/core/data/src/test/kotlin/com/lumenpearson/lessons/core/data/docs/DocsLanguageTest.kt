package com.lumenpearson.lessons.core.data.docs

import com.lumenpearson.lessons.core.model.DocsOrigin
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guide is two documents and the manifest names one version for both.
 *
 * That is the whole of the trap. A refresh fetches the markdown of **one**
 * language and then records the version it fetched, so a phone that has read
 * the English guide at version 2 has recorded version 2 while its Russian file
 * is still the version 1 it downloaded in September. Asked again in Russian,
 * «stored version >= manifest version» is true, the answer is «ничего нового»,
 * and the reader is shown last term's guide under this term's date — for ever,
 * because every later refresh makes the same comparison and reaches the same
 * answer. There is no state in the app that says so and no gesture that
 * clears it.
 *
 * The storage here is deliberately the worst case: one release record for the
 * phone, exactly as the DataStore held it. What a stored release is *about* is
 * the thing being checked, so a test that modelled a tidier store would be
 * testing the model.
 */
class DocsLanguageTest {

    private class FakeStorage : DocsStorage {

        /** One record for the phone, whatever language last wrote it. */
        var record: StoredRelease? = null

        val guides = mutableMapOf<String, String>()

        override suspend fun storedRelease(language: String): StoredRelease? = record

        override suspend fun storedGuide(language: String): String? = guides[language]

        override fun bundledGuide(language: String): String = guideFor(language, version = 1)

        override fun bundledManifest(): String = manifestJson(version = 1)

        override suspend fun write(
            language: String,
            markdown: String,
            release: StoredRelease,
        ): Boolean {
            guides[language] = markdown
            record = release
            return true
        }
    }

    /** Serves whatever the repository asks for at [version]; counts the asking. */
    private class FakeRepositoryFiles(var version: Int) {
        val asked = mutableListOf<String>()

        fun download(url: String): String? {
            asked += url
            return when {
                url.endsWith("manifest.json") -> manifestJson(version)
                url.endsWith("guide.ru.md") -> guideFor("ru", version)
                url.endsWith("guide.en.md") -> guideFor("en", version)
                else -> null
            }
        }
    }

    @Test
    fun `a refresh in one language does not freeze the other`() = runTest {
        val storage = FakeStorage()
        val files = FakeRepositoryFiles(version = 1)
        val docs = DocsRepositoryImpl(storage, files::download)

        // September: the reader is in Russian and takes version 1.
        docs.load("ru")
        docs.refresh("ru")
        assertEquals(1, docs.state.value.library?.release?.version)

        // A new version is published, and the reader happens to be reading the
        // English guide when they next pull to refresh.
        files.version = 2
        docs.load("en")
        docs.refresh("en")
        assertEquals(2, docs.state.value.library?.release?.version)

        // Back to Russian. The file on this phone is still version 1, so the
        // refresh has to fetch — and what is drawn has to be the guide that
        // was fetched, not the September one under a September date.
        files.asked.clear()
        docs.load("ru")
        docs.refresh("ru")

        assertTrue(
            "the Russian guide was never fetched: ${files.asked}",
            files.asked.any { it.endsWith("guide.ru.md") },
        )
        assertEquals(guideFor("ru", version = 2), storage.guides["ru"])
        val library = requireNotNull(docs.state.value.library)
        assertEquals("ru", library.guide.language)
        assertEquals(2, library.release.version)
        assertEquals(DocsOrigin.NETWORK, library.release.origin)
    }

    @Test
    fun `the language a stored release is about is the version it answers for`() {
        // The rule on its own, without the three round trips above it: a record
        // left by the English fetch says nothing about the Russian file, and
        // treating it as an answer is what froze that file.
        val english = StoredRelease(version = 2, updated = "2026-09-20", appVersion = "0.6.0", language = "en")

        assertEquals(
            false,
            storedCopyIsCurrent(language = "ru", stored = english, hasMarkdown = true, manifestVersion = 2),
        )
        assertEquals(
            true,
            storedCopyIsCurrent(language = "en", stored = english, hasMarkdown = true, manifestVersion = 2),
        )
        // The two rules it already had are untouched: a record with no file
        // beside it, and a file older than the manifest, are both a fetch.
        assertEquals(
            false,
            storedCopyIsCurrent(language = "en", stored = english, hasMarkdown = false, manifestVersion = 2),
        )
        assertEquals(
            false,
            storedCopyIsCurrent(language = "en", stored = english, hasMarkdown = true, manifestVersion = 3),
        )
    }

    private companion object {

        fun manifestJson(version: Int): String = """
            {
              "version": $version,
              "updated": "2026-09-20",
              "appVersion": "0.6.0",
              "files": {"ru": "guide.ru.md", "en": "guide.en.md"}
            }
        """.trimIndent()

        /** One page, and the version written into it so two copies can be told apart. */
        fun guideFor(language: String, version: Int): String = """
            # Книга

            ## Страница
            <!-- id: P; label: П; summary: $language $version -->

            Версия $version на языке $language.
        """.trimIndent()
    }
}
