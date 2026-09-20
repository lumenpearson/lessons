package com.lumenpearson.lessons

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every build property the app reads is passed by the workflow that builds it.
 *
 * A `signingSecret(...)` that nobody sets is not a build failure and not a
 * warning: it is an empty string, and downstream that is a feature switched
 * off. `LESSONS_GITHUB_CLIENT_ID` was read by `app/build.gradle.kts` from the
 * day the GitHub account landed and passed by `apk.yml` on no day at all, so
 * **every APK that workflow ever built had the whole GitHub half of the app
 * missing** — «Войти через GitHub» hidden by `githubConfigured`, filing a bug
 * report from inside the app gone with it, and the corrections pull request
 * unreachable. `LESSONS_CONTACT_EMAIL` was the same story one button along.
 * The first anybody knew was looking on a phone for a row that had never been
 * built.
 *
 * Nothing in the app can catch that — from the app's side an unconfigured
 * feature is indistinguishable from one the builder chose not to switch on,
 * and hiding the row is the deliberate decision (`docs/design.md`, "Signing in
 * through GitHub is for one thing"). What can catch it is the pair of files,
 * read together, which is what this does.
 *
 * It says nothing about whether a secret is *set* — that is the owner's, and
 * the run summary reports it. What it holds is that the wire exists.
 */
class BuildPropertyReachTest {

    @Test
    fun `the walk found both files`() {
        assertTrue("app/build.gradle.kts is empty or missing", buildScript.length > 100)
        assertTrue("apk.yml is empty or missing", workflow.length > 100)
    }

    @Test
    fun `the build script really declares the properties`() {
        assertTrue(
            "This test reads `signingSecret(\"LESSONS_…\")` out of the build " +
                "script, so a rename of that helper turns it into a silent pass. " +
                "Found: $declared",
            declared.size >= 6,
        )
    }

    @Test
    fun `every property the app reads is passed by the APK workflow`() {
        val missing = declared.filterNot { name -> passed.contains(name) }

        assertTrue(
            "Read by app/build.gradle.kts and never passed by " +
                ".github/workflows/apk.yml, so every APK it builds is missing " +
                "whatever this one switches on — silently, because an unset " +
                "property is an empty string and not an error:\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    private companion object {

        /** `LESSONS_…` as the first argument of `signingSecret`. */
        val NAMES = Regex("""signingSecret\("(LESSONS_[A-Z0-9_]+)"""")

        /** `LESSONS_…:` at the head of a line, which is a YAML key and not prose. */
        val ENV_KEY = Regex("""^(LESSONS_[A-Z0-9_]+):\s""")

        /**
         * The repository root, found by walking up for `settings.gradle.kts`.
         *
         * Gradle runs unit tests from the module directory and an IDE sometimes
         * from the repository root; `ResourceTranslationTest` walks up for the
         * same reason. This one goes one further, to the directory holding
         * `.github`, because the workflow is outside the Gradle tree.
         */
        val root: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    return@lazy directory.parentFile ?: directory
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }

        val buildScript: String by lazy { File(root, "android/app/build.gradle.kts").readText() }

        val workflow: String by lazy { File(root, ".github/workflows/apk.yml").readText() }

        /**
         * The names the workflow actually sets, as `NAME:` at the head of a
         * line inside an `env:` block.
         *
         * Searching the file for the bare name passes on a comment mentioning
         * it, and this file's comments name every one of them at length — which
         * is exactly the shape of pass this test exists to refuse. The first
         * version of it did that and had to be caught by breaking the wire on
         * purpose.
         */
        val passed: Set<String> by lazy {
            workflow.lineSequence()
                .mapNotNull { ENV_KEY.find(it.trim())?.groupValues?.get(1) }
                .toSet()
        }

        val declared: List<String> by lazy {
            NAMES.findAll(buildScript).map { it.groupValues[1] }.distinct().sorted().toList()
        }
    }
}
