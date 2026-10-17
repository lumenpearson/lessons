package com.lumenpearson.lessons.core.data.docs

import com.lumenpearson.lessons.core.data.github.GithubProject
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The guide is fetched from the repository the build names (#469).
 *
 * A fork that rewrites `docs/app/` for its own deployment would otherwise
 * bundle its own guide in the APK and then replace it, on the first refresh,
 * with this repository's — whose server addresses and screens are not its.
 */
class DocsSourceTest {

    private object EmptyStorage : DocsStorage {
        override suspend fun storedRelease(language: String): StoredRelease? = null
        override suspend fun storedGuide(language: String): String? = null
        override fun bundledGuide(language: String): String? = null
        override fun bundledManifest(): String? = null
        override suspend fun write(language: String, markdown: String, release: StoredRelease): Boolean = false
    }

    @Test
    fun `a refresh asks the named repository for the manifest`() = runTest {
        val asked = mutableListOf<String>()
        val docs = DocsRepositoryImpl(
            store = EmptyStorage,
            download = { url -> asked += url; null },
            project = GithubProject("somebody", "lessons-fork"),
        )

        docs.refresh("ru")

        assertEquals(
            listOf("https://raw.githubusercontent.com/somebody/lessons-fork/main/docs/app/manifest.json"),
            asked,
        )
    }
}
