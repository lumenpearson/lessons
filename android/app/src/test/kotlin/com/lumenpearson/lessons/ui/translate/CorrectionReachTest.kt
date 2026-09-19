package com.lumenpearson.lessons.ui.translate

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the reach of correction mode.
 *
 * The mode covers the whole app because of two swapped imports, repeated in
 * every file that draws text: `Text` comes from the design system rather than
 * from Material, and a string is read with `correctedString` rather than with
 * `stringResource`. Neither is enforced by anything — both of the originals are
 * still on the classpath, both still compile, and a screen written with them
 * looks exactly like every other screen until somebody switches the mode on and
 * finds one page that cannot be corrected.
 *
 * That is the failure this test exists for, and it is the shape the mode was in
 * before: the machinery had been written, and five calls on one screen used it.
 *
 * Read out of the source tree rather than through `R` or through reflection,
 * for the same reason [com.lumenpearson.lessons.ResourceTranslationTest] is:
 * the question is about what was written, and by the time it is compiled the
 * two imports are indistinguishable.
 */
class CorrectionReachTest {

    /**
     * The three files that are allowed the plain imports, and why.
     *
     * `Corrections.kt` is where `stringResource` is finally called — something
     * has to read the resource. The editor and the session sheet draw the
     * originals and the corrections themselves, so they stay outside the mode
     * on purpose: see the comment on [TranslationEditorSheet].
     *
     * Matched on the path so that the list cannot quietly cover a new file, and
     * checked for staleness below so it cannot outlive the files it names.
     */
    private val exempt = listOf(
        "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/text/Corrections.kt",
        "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/text/Text.kt",
        "app/src/main/kotlin/com/lumenpearson/lessons/ui/translate/TranslationEditorSheet.kt",
        "app/src/main/kotlin/com/lumenpearson/lessons/ui/translate/TranslationSessionSheet.kt",
    )

    @Test
    fun `no screen draws text Material's way`() {
        // Aliased too. `Text.kt` writes `… as MaterialText` and is not in the
        // exempt list — it passed only because the alias defeated the match,
        // which means `… as M3Text` in a new screen would have passed as well,
        // and the staleness check below cannot see a file nothing exempted.
        val offenders = sources.filter { source ->
            OFFENDING_TEXT_IMPORTS.any { it in source.text }
        }
        assertTrue(
            "These files import Material's Text instead of the design system's: " +
                offenders.joinToString { it.path } +
                ". Every text block in the app is a correction target because that one " +
                "import is swapped; a file using Material's directly is a page a " +
                "proofreader cannot touch, and nothing else will say so.",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no screen reads a string past the corrections`() {
        val offenders = sources.filter { "import androidx.compose.ui.res.stringResource\n" in it.text }
            .plus(sources.filter { it.readsResourcesDirectly })
            .distinct()
        assertTrue(
            "These files read strings with stringResource instead of correctedString: " +
                offenders.joinToString { it.path } +
                ". A string read that way is drawn as it ships however it has been " +
                "corrected, and never learns which resource it came from, so a long " +
                "press on it finds nothing.",
            offenders.isEmpty(),
        )
    }

    /**
     * An exemption for a file that no longer exists is a hole nobody can see:
     * a new file moved into that path would inherit it.
     */
    @Test
    fun `every exemption still names a file`() {
        val missing = exempt.filterNot { File(root, it).isFile }
        assertTrue("Exempted, but no longer in the tree: $missing", missing.isEmpty())
    }

    /**
     * The single point at which all of this is switched on.
     *
     * Take [CorrectionHost] out of the activity and everything still compiles,
     * every screen still draws, every string is still read through
     * [com.lumenpearson.lessons.core.designsystem.text.correctedString] — and
     * the switch in the settings does nothing at all, because the default
     * implementation does nothing at all. There is no other symptom.
     */
    @Test
    fun `the host is installed over the app`() {
        val activity = File(root, "app/src/main/kotlin/com/lumenpearson/lessons/MainActivity.kt")
        assertTrue(
            "MainActivity no longer wraps the app in CorrectionHost. Nothing fails " +
                "without it; correction mode simply stops existing.",
            activity.readText().contains("CorrectionHost {"),
        )
    }

    /**
     * The imports that draw text without asking about corrections.
     *
     * `BasicText` is on the classpath, compiles and draws — nothing uses it
     * today, and nothing stops the next screen from using it either.
     */
    private val OFFENDING_TEXT_IMPORTS = listOf(
        "import androidx.compose.material3.Text\n",
        "import androidx.compose.material3.Text as ",
        "import androidx.compose.foundation.text.BasicText",
    )

    private class Source(val path: String, val text: String) {

        /**
         * Whether a composable in this file reads a resource straight off a
         * `Context` or a `Resources`.
         *
         * The two imports are what `correctedString` and this module's `Text`
         * replaced, and a file that uses neither slips past both of them —
         * which is exactly where the countdown on the home screen hid.
         * `Duration.formatCountdown` took a `Context` and called `getString`,
         * so the largest number on that card was the one thing on it
         * correction mode could not touch, beside a caption that had both an
         * outline and an editor.
         *
         * Only files that compose: `:core:data` words its own notifications
         * and the system draws those, so `getString` there is right.
         */
        val readsResourcesDirectly: Boolean
            get() {
                // The two modules the mode reaches. `:widget` is Glance — it
                // cannot host a Compose gesture, so `getString` there is the
                // right call and `docs/design.md` says so; `:core:data` words
                // its own notifications, which the system draws.
                val reachable = path.startsWith("app/") ||
                    path.startsWith("core/designsystem/")
                return reachable &&
                    "import androidx.compose.runtime.Composable" in text &&
                    Regex("""\.get(String|QuantityString)\(""").containsMatchIn(text)
            }
    }

    private val sources: List<Source> by lazy {
        root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/src/main/" in it.toRelative() }
            .map { Source(it.toRelative(), it.readText()) }
            .filterNot { it.path in exempt }
            .sortedBy { it.path }
            .toList()
    }

    private fun File.toRelative(): String = absolutePath.removePrefix(root.absolutePath + "/")

    /**
     * The Gradle root, found from wherever the runner started — the same walk
     * [com.lumenpearson.lessons.ResourceTranslationTest] makes, and for the
     * same reason: Gradle runs unit tests from the module directory and an IDE
     * sometimes runs them from the root.
     */
    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return@lazy directory
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }
}
