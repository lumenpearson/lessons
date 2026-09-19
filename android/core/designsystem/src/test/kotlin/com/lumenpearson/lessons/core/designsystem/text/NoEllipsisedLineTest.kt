package com.lumenpearson.lessons.core.designsystem.text

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No component in this module cuts a single line off with «…».
 *
 * [MarqueeText] answers that case — the line scrolls, and fades at both ends
 * while it does — but a component only gets the behaviour if it *asks* for it,
 * and nothing about a `Text(maxLines = 1, overflow = Ellipsis)` looks wrong on
 * the way past. That is the same gap `BoundedCacheReadTest` covers in
 * `:widget`: the tests beside this one prove the scrolling line behaves, not
 * that it is the line anybody is drawing.
 *
 * It reads the module's own source, the way `StabilityPromiseTest` and
 * `ResourceTranslationTest` do, because the question is about what is written
 * rather than about what a composition does.
 *
 * **A `maxLines` of two or more is not covered and must not be.** A paragraph
 * that runs past its second line cannot scroll sideways out of the problem —
 * there is no single line to move — so an ellipsis is the right answer there
 * and several components use it deliberately.
 *
 * If a one-line ellipsis is ever genuinely wanted, say why on the line above it
 * and this test will let it past; what it refuses is the silent one.
 */
class NoEllipsisedLineTest {

    private val sources: List<File> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    @Test
    fun `the module has source to check`() {
        assertTrue(
            "No Kotlin source under src/main/kotlin. This test reads the tree " +
                "rather than the classpath, so an empty list is a silent pass.",
            sources.isNotEmpty(),
        )
    }

    @Test
    fun `nothing cuts a single line off with an ellipsis`() {
        val offenders = sources.flatMap { file ->
            val lines = file.readLines()
            lines.withIndex().filter { (index, line) ->
                line.contains("overflow = TextOverflow.Ellipsis") &&
                    lines.getOrNull(index - 1)?.contains("maxLines = 1,") == true &&
                    // The opt-out: a reason written directly above it.
                    lines.getOrNull(index - 2)?.trimStart()?.startsWith("//") != true
            }.map { (index, _) -> "${file.name}:${index + 1}" }
        }

        assertTrue(
            "A line cut off at «…» is a promise that there is more and a refusal " +
                "to show it. `MarqueeText` scrolls it instead, and measures first " +
                "so that a line which fits is left exactly as it was. Use it, or " +
                "write the reason this one is different on the line above:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
