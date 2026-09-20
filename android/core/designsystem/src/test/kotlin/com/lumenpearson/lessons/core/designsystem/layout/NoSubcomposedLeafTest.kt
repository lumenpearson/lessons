package com.lumenpearson.lessons.core.designsystem.layout

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing in the design system may be a `SubcomposeLayout`.
 *
 * A `SubcomposeLayout` cannot answer «how tall would you be at this width».
 * Asking one throws `IllegalStateException: Asking for intrinsic measurements
 * of SubcomposeLayout layouts is not supported`, and everything in this module
 * is drawn inside somebody else's row — so whether the question gets asked is
 * not decided here. Material asks it inside its own components, no call site
 * mentions `IntrinsicSize`, and in a release build the stack names neither the
 * component nor the screen: `MarqueeText` wrapped itself in a
 * `BoxWithConstraints` to learn its width and took the app down on the main
 * thread from a `Layout` nobody in this repository wrote.
 *
 * That is why the rule is held here rather than by review. A
 * `BoxWithConstraints` is the way anybody would reach for a width, it compiles,
 * it looks right, and every screen it is on works until one of them is put in a
 * row that measures.
 *
 * **`:app` and `:widget` are outside it, and for a reason rather than for
 * convenience.** A lazy list or a `Scaffold` on a screen is the root of its own
 * layout: it is handed a slot and fills it, and nothing above asks it to
 * predict a size. Seven screens hold a `LazyColumn` on those terms. The rule is
 * about a component written to be placed inside a layout it does not own, and
 * that is this module.
 *
 * The opt-out is the same as `NoEllipsisedLineTest`'s: a `//` comment on the
 * line above. Cheap on purpose — what it refuses is doing it by accident. It is
 * also why comment lines are skipped rather than matched: this rule has to be
 * explainable in the file it governs, and `MarqueeText`'s own note names both
 * words at length.
 */
class NoSubcomposedLeafTest {

    @Test
    fun `the walk found the module it governs`() {
        assertTrue(
            "This test reads the tree rather than the classpath, so a walk that " +
                "finds nothing is a silent pass. Found ${sources.size} files under " +
                "$tree.",
            sources.size > 20,
        )
    }

    @Test
    fun `no component subcomposes`() {
        val offenders = sources.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex()
                .filter { (index, line) ->
                    val code = line.trimStart()
                    !code.startsWith("*") && !code.startsWith("//") &&
                        SUBCOMPOSING.containsMatchIn(line) &&
                        lines.getOrNull(index - 1)?.trimStart()?.startsWith("//") != true
                }
                .map { (index, _) -> "${file.name}:${index + 1}" }
        }

        assertTrue(
            "A component that subcomposes cannot be asked how tall it would be, " +
                "and the asking is done by whoever draws it — Material does it " +
                "inside its own rows. Take the width from the layout this is " +
                "already in (`Modifier.onSizeChanged`, as `MarqueeText` does) " +
                "instead of subcomposing to learn it. If this one can never be " +
                "measured that way, say why on the line above:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    private companion object {

        /**
         * The two spellings that reach a `SubcomposeLayout` from here.
         *
         * `TabRow` and the lazy lists are the others Compose names in that
         * exception, and none of them is in this module; they would be caught
         * the day one arrived only if they were listed, so they are.
         */
        val SUBCOMPOSING = Regex(
            "\\b(BoxWithConstraints|SubcomposeLayout|TabRow|ScrollableTabRow|" +
                "Lazy(Column|Row|VerticalGrid|HorizontalGrid|VerticalStaggeredGrid))\\s*\\(",
        )

        /**
         * `:core:designsystem`'s own `src/main/kotlin`.
         *
         * Found by walking up for the directory holding `settings.gradle.kts`,
         * because Gradle runs unit tests from the module directory and an IDE
         * sometimes runs them from the repository root — the same reason
         * `NoEllipsisedLineTest` does it.
         */
        val tree: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                if (File(directory, "settings.gradle.kts").isFile) {
                    return@lazy File(directory, "core/designsystem/src/main/kotlin")
                }
                directory = directory.parentFile
            }
            error("Could not find the Gradle root from ${File("").absolutePath}")
        }

        val sources: List<File> by lazy {
            tree.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }
    }
}
