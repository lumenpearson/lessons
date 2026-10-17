package com.lumenpearson.lessons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * This repository's name is written into the Kotlin once: as the fallback for
 * a build that names no repository of its own (#469).
 *
 * Every other GitHub address — the releases the update check lists, the
 * tracker a bug report lands in, the guide, the translation pull request, the
 * developer mode's question, «Исходный код» — is built from the repository
 * the build names. While two of them spelled the name out, an APK built from
 * a fork offered this repository's releases, which the installer refuses over
 * the fork's own signature, and filed the fork's users' reports here. A third
 * spelling added later would do it again, one address at a time, and nothing
 * on a phone built from this repository would look wrong.
 */
class UpstreamNameTest {

    @Test
    fun `the walk reaches every module's sources`() {
        val modules = sources.map { it.path.replace('\\', '/').substringBefore("/src/main/") }.toSet()
        assertTrue("found no Kotlin under the modules: $modules", modules.size >= 5)
    }

    @Test
    fun `no address names this repository outside the one fallback`() {
        val found = sources
            .filterNot { file -> ALLOWED.any { file.path.replace('\\', '/').endsWith(it) } }
            .flatMap { file ->
                file.readLines().withIndex()
                    .filter { (_, line) -> !COMMENT.containsMatchIn(line) && UPSTREAM.containsMatchIn(line) }
                    .map { (index, line) -> "${file.relativeTo(root).path}:${index + 1}: ${line.trim()}" }
            }

        assertEquals(
            "These spell out lumenpearson/lessons instead of asking GithubProject, so a fork's " +
                "APK sends them here:\n" + found.joinToString("\n"),
            emptyList<String>(),
            found,
        )
    }

    private companion object {

        /**
         * An address on GitHub under the upstream owner, the owner as a literal,
         * the full name — which is what a `fromBuild("lumenpearson/lessons")`
         * would carry — or an API path built by hand. A package path never
         * matches: it is written with dots, `com.lumenpearson.lessons`.
         */
        val UPSTREAM = Regex(
            """github\.com/lumenpearson|githubusercontent\.com/lumenpearson|"lumenpearson"|""" +
                """lumenpearson/lessons|repos/lumenpearson""",
        )

        /** KDoc and line comments may quote the name; only code builds an address. */
        val COMMENT = Regex("""^\s*(\*|//|/\*)""")

        val ALLOWED = listOf(
            // The fallback itself, which is the one place the name belongs.
            "core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/github/GithubProject.kt",
            // A preview's sample release notes, which a phone never draws: they
            // are what this repository's release notes look like, quoted.
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/UpdateSheet.kt",
        )

        val root: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) return@lazy directory
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }

        val sources: List<File> by lazy {
            root.walk()
                .onEnter { it.name != "build" && it.name != ".gradle" && !it.name.startsWith(".") }
                .filter { it.isFile && it.extension == "kt" && "/src/main/" in it.path.replace('\\', '/') }
                .toList()
        }
    }
}
