package com.lumenpearson.lessons.core.designsystem.text

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing this app draws in Compose is cut off.
 *
 * An ellipsis is a promise that there is more and a refusal to show it, and
 * every block that had one now answers the question a different way: a line
 * pinned to one line scrolls ([MarqueeText]), and a block that is allowed to
 * wrap is simply not capped, because every one of them sits in something that
 * scrolls. Neither is visible on the way past — nothing about
 * `Text(maxLines = 2, overflow = Ellipsis)` looks wrong — so the rule is held
 * here rather than by review.
 *
 * A bare `maxLines = 1` is held to the same rule, and it is the worse of the
 * two: it clips with no «…» at all, so the reader is not even told that
 * something was cut. That is what the countdown's label in `StateHeroCard` was
 * doing — the row wraps its content, so at a large font scale the number took
 * the width and the label lost its end in silence.
 *
 * It reads the source tree, the way `StabilityPromiseTest` and
 * `ResourceTranslationTest` do, because the question is about what is written
 * rather than about what a composition does. And it reads **every** module, not
 * the one it lives in: that is the mistake `ResourceTranslationTest` had to be
 * corrected for, and these components are spread across `:core:designsystem`
 * and `:app` in exactly the same way.
 *
 * **`:widget` is outside both rules, and the pass it gets is worth nothing.**
 * Glance has no `TextOverflow` at all, so the first rule finds nothing there;
 * `maxLines` hard-clips at a line break, which is why `WidgetStrings.ellipsize`
 * puts the «…» into the string by hand, so the second would find almost every
 * line it draws. A marquee is not an option either — RemoteViews has no frame
 * loop, so a widget cannot animate anything. The home screen keeps its
 * ellipsis because the platform leaves no other answer, not because anybody
 * preferred it.
 *
 * If either cut is ever genuinely wanted, write the reason on the line above it
 * and this test will let it past; what it refuses is the silent one. Six do
 * that today: `UpdateSheet`'s pill and `GithubSignInSheet`'s device code, both
 * of which shrink to fit with `autoSize` before any cap is reached; the
 * countdown itself, bounded by its own format and rebuilt every tick, so a
 * marquee would be handed a new string each second and restart from the left
 * for ever; and [MarqueeText]'s own three, which are this component's whole
 * subject.
 */
class NoEllipsisedLineTest {

    @Test
    fun `the walk found the modules that draw`() {
        val names = sourceTrees.map { it.first }
        assertTrue(
            "This test reads the tree rather than the classpath, so a walk that " +
                "finds nothing is a silent pass. Found: $names",
            names.containsAll(listOf("app", "core/designsystem", "widget")),
        )
    }

    @Test
    fun `nothing is cut off with an ellipsis`() {
        val offenders = offenders(sourceTrees) { it.contains("TextOverflow.Ellipsis") }

        assertTrue(
            "Cut off at «…»: the reader is told there is more and not shown it. " +
                "A line that has to stay on one line is `MarqueeText`, which " +
                "scrolls it and measures first, so a line which fits is left " +
                "exactly as it was. A block that may wrap should not be capped — " +
                "all of them are inside something that scrolls. Or write the " +
                "reason this one is different on the line above:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `nothing is clipped at one line without saying why`() {
        val offenders = offenders(sourceTrees.filterNot { it.first == GLANCE }) {
            ONE_LINE.containsMatchIn(it)
        }

        assertTrue(
            "Pinned to one line with nothing to say so. This clips without even " +
                "an «…» — the silent version of the same refusal — and the row " +
                "it is in decides which end goes. `MarqueeText` scrolls the line " +
                "instead. If the cap is right, because the text shrinks to fit or " +
                "is bounded by its own format, say so on the line above:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    /**
     * Every line of [trees] matching [cut], minus the ones with a reason above.
     *
     * The opt-out is a `//` comment on the line directly above, which is where
     * this codebase writes the why of a decision anyway. It is deliberately
     * cheap to satisfy: the point is that nobody does it by accident.
     */
    private fun offenders(
        trees: List<Pair<String, File>>,
        cut: (String) -> Boolean,
    ): List<String> = trees.flatMap { (module, tree) ->
        tree.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { file ->
                val lines = file.readLines()
                lines.withIndex()
                    .filter { (index, line) ->
                        cut(line) &&
                            lines.getOrNull(index - 1)?.trimStart()?.startsWith("//") != true
                    }
                    .map { (index, _) -> "$module/${file.name}:${index + 1}" }
            }
    }

    private companion object {

        /** The Glance module, which neither rule can reach; see the class note. */
        const val GLANCE = "widget"

        /**
         * `maxLines = 1` as an argument.
         *
         * The word boundary keeps `maxLines = 10` out, and the `=` without a
         * type keeps out `maxLines: Int = 1`, which is a parameter's default and
         * says nothing about what any caller draws.
         */
        val ONE_LINE = Regex("\\bmaxLines = 1\\b")

        /**
         * Every module's `src/main/kotlin`, paired with its Gradle path.
         *
         * Gradle runs unit tests with the module directory as the working
         * directory, and an IDE sometimes runs them from the repository root,
         * so the walk is upwards for the directory holding
         * `settings.gradle.kts`. Modules are discovered rather than listed, so
         * a new one is covered the day it is added.
         */
        val sourceTrees: List<Pair<String, File>> by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    val root = directory
                    val candidates = root.listFiles().orEmpty().flatMap { child ->
                        listOf(child) + child.listFiles().orEmpty().toList()
                    }
                    return@lazy candidates
                        .filter { File(it, "src/main/kotlin").isDirectory }
                        .map {
                            it.relativeTo(root).invariantSeparatorsPath to
                                File(it, "src/main/kotlin")
                        }
                        .sortedBy { it.first }
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }
    }
}
