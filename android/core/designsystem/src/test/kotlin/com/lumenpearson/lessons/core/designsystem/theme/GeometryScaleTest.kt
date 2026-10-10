package com.lumenpearson.lessons.core.designsystem.theme

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the one corner scale agreed in
 * `docs/specs/2026-10-10-ui-geometry-design.md`: every radius this app draws is
 * one of four values — 4 (`Row`), 12 (`Cell`), 28 (`Group`/`Hero`/`extraLarge`)
 * and full — read from [LessonsShapeTokens] or [LessonsShapes] by name, never
 * guessed again as a literal or re-declared under a private name. That is the
 * audit's own finding: a 24 dp corner declared seven times under names like
 * `CardCorner` and `FieldCorner`, instead of the one token that already said
 * the same thing.
 *
 * Modelled on `StabilityPromiseTest` (`:core:model`): it reads the source out
 * of the tree rather than the classpath, because a radius literal compiles to
 * exactly the bytecode a token read by name does — only the words tell them
 * apart. It walks up for `settings.gradle.kts` the way `FontAxisTest` and
 * `ResourceTranslationTest` do, because Gradle runs a module's tests from that
 * module's own directory and an IDE sometimes runs them from the repository
 * root instead.
 *
 * `android/theme/Shape.kt` is the one file allowed to declare the scale's own
 * literals — everywhere else is read, `android/app` included, because a
 * literal picked in a screen is exactly how the audit found nine distinct
 * radii in the first place.
 *
 * Two shapes of offender, both read as plain text, comments stripped first so
 * that a sentence quoting one in prose is not mistaken for one:
 * - `RoundedCornerShape(` followed, within the same call and the same line, by
 *   a numeric `.dp` literal or by `percent =` — a radius picked on the screen
 *   instead of read from a token;
 * - a `val` whose name ends in `Corner`, bound to a `.dp` literal — a private
 *   constant re-inventing a token under a name of its own.
 *
 * `@Preview` code is exempt, by the simplest approach that holds for this
 * codebase's previews: once a line carries `@Preview`, every line is skipped
 * until the function it precedes opens its first brace, and then every line is
 * skipped until that brace's own close — counting `{`/`}` per line rather than
 * parsing Kotlin, which is enough for a preview body that never puts a brace
 * character inside a string literal, true of every preview here today.
 *
 * The [allowance] is **pending**, not a target it is comfortable with: it
 * exists only until Tasks 2 and 3 of #404 move each of today's sites onto a
 * token, and the test below that checks it against the source fails the day
 * a count in it no longer matches — so a fixed site has to be taken out of
 * the list by hand, rather than the list quietly going stale while the count
 * it was given keeps passing.
 */
class GeometryScaleTest {

    @Test
    fun `there is source to scan in the first place`() {
        assertTrue(
            "No Kotlin source found under app/src/main/kotlin or " +
                "core/designsystem/src/main/kotlin, from ${File("").absolutePath}. " +
                "That would make every assertion below pass in silence rather than " +
                "because the geometry is actually clean.",
            scannedFiles.isNotEmpty(),
        )
    }

    /**
     * The guard itself: nothing outside the allowance below may be a raw
     * corner literal. A new offender in a file already listed pushes that
     * file's count past what is allowed; a new offender in any other file has
     * nothing to be measured against, so it is flagged outright.
     */
    @Test
    fun `no corner radius outside the pending allowance is a raw literal`() {
        val byFile = offenders().groupBy { it.file }
        val beyondAllowance = byFile.entries.flatMap { (file, found) ->
            val allowed = allowance[file] ?: 0
            if (found.size > allowed) found.drop(allowed) else emptyList()
        }

        assertTrue(
            "A corner radius outside the pending allowance of #404's Task 1 is a " +
                "raw literal rather than a token read from theme/Shape.kt. Either " +
                "point it at LessonsShapeTokens/LessonsShapes, or — if this is " +
                "Task 2 or 3's own work landing — add it to the allowance in the " +
                "same commit that introduces it and remove it again once it reads " +
                "a token:\n" + beyondAllowance.joinToString("\n") { "${it.file}:${it.line}: ${it.text}" },
            beyondAllowance.isEmpty(),
        )
    }

    /**
     * The half of the allowance's promise a count alone cannot keep by
     * itself: a file whose offenders were actually fixed still passes the
     * test above with room to spare, which is exactly how a stale allowance
     * entry survives a cleanup unnoticed. This fails until the entry is
     * corrected — removed if the count is now zero, lowered otherwise.
     */
    @Test
    fun `the allowance names exactly today's offenders, not more and not fewer`() {
        val counted = offenders().groupingBy { it.file }.eachCount()
        val stale = allowance.entries
            .filter { (file, count) -> (counted[file] ?: 0) != count }
            .map { (file, count) -> "$file: allowance says $count, the source now has ${counted[file] ?: 0}" }

        assertTrue(
            "The pending allowance has drifted from the source. A fixed site must " +
                "be taken out of the list — or its count lowered — rather than left " +
                "standing at the old number, which is what keeps the list from " +
                "rotting into a blanket exemption:\n" + stale.joinToString("\n"),
            stale.isEmpty(),
        )
    }

    /** One offending line, by the module-relative path a human would read in a review. */
    private data class Offender(val file: String, val line: Int, val text: String)

    private fun offenders(): List<Offender> = scannedFiles.flatMap { file ->
        val relative = file.relativeTo(root).invariantSeparatorsPath
        nonPreviewLines(file).mapNotNull { (number, line) ->
            val reason = when {
                roundedCornerLiteral.containsMatchIn(line) -> "a raw RoundedCornerShape(...)"
                cornerValLiteral.containsMatchIn(line) -> "a private *Corner constant"
                else -> return@mapNotNull null
            }
            Offender(relative, number, "$reason — ${line.trim()}")
        }
    }

    /**
     * [file]'s lines, numbered from 1, comments stripped, with every line of
     * an `@Preview` function's body removed — see the class doc for the exact
     * approach, which is brace-counting rather than parsing.
     */
    private fun nonPreviewLines(file: File): List<Pair<Int, String>> {
        val lines = stripComments(file.readLines()).mapIndexed { index, line -> (index + 1) to line }
        val kept = mutableListOf<Pair<Int, String>>()
        var mode = Mode.SCANNING
        var depth = 0
        for ((number, line) in lines) {
            when (mode) {
                Mode.SCANNING -> {
                    kept += number to line
                    if (previewAnnotation.containsMatchIn(line)) mode = Mode.SKIPPING_HEADER
                }
                Mode.SKIPPING_HEADER -> {
                    val opens = line.count { it == '{' }
                    val closes = line.count { it == '}' }
                    if (opens > 0) {
                        depth = opens - closes
                        mode = if (depth > 0) Mode.SKIPPING_BODY else Mode.SCANNING
                    }
                    // Otherwise still an annotation line or a signature with no
                    // brace yet — stay here and keep looking.
                }
                Mode.SKIPPING_BODY -> {
                    depth += line.count { it == '{' } - line.count { it == '}' }
                    if (depth <= 0) mode = Mode.SCANNING
                }
            }
        }
        return kept
    }

    private fun stripComments(raw: List<String>): List<String> {
        var inBlock = false
        return raw.map { line ->
            val kept = StringBuilder()
            var index = 0
            while (index < line.length) {
                when {
                    inBlock && line.startsWith("*/", index) -> { inBlock = false; index += 2 }
                    inBlock -> index++
                    line.startsWith("/*", index) -> { inBlock = true; index += 2 }
                    line.startsWith("//", index) -> index = line.length
                    else -> kept.append(line[index++])
                }
            }
            kept.toString()
        }
    }

    private enum class Mode { SCANNING, SKIPPING_HEADER, SKIPPING_BODY }

    /** `android/`, found by walking up for the file that marks the Gradle root. */
    private val root: File by lazy {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            if (File(directory, "settings.gradle.kts").isFile) return@lazy directory
            directory = directory.parentFile
        }
        error("Could not find the Gradle root from ${File("").absolutePath}")
    }

    /**
     * Every `.kt` file under the two trees the geometry pass covers, minus
     * `theme/Shape.kt` — the one file the scale is declared in rather than
     * read from.
     */
    private val scannedFiles: List<File> by lazy {
        listOf("app/src/main/kotlin", "core/designsystem/src/main/kotlin")
            .map { File(root, it) }
            .flatMap { it.walkTopDown().filter { candidate -> candidate.isFile && candidate.extension == "kt" } }
            .filterNot { it.invariantSeparatorsPath.endsWith(shapeKtPath) }
    }

    /** The one file the corner scale is declared in rather than read from. */
    private val shapeKtPath =
        "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/theme/Shape.kt"

    private companion object {
        val previewAnnotation = Regex("""@Preview\b""")

        /**
         * `RoundedCornerShape(` with, somewhere before its matching close on
         * the same line, a numeric `.dp` literal or `percent =`. Non-greedy
         * and bounded by `[^)]*` rather than a real parser, which is the
         * stated trade for not crossing into a nested call's own parens.
         */
        val roundedCornerLiteral = Regex(
            """RoundedCornerShape\([^)]*?(\d+(\.\d+)?\.dp|percent\s*=)""",
        )

        /** A `val` ending in `Corner`, of any visibility, bound to a `.dp` literal. */
        val cornerValLiteral = Regex("""\bval\s+\w*Corner\b[^=]*=\s*\d+(\.\d+)?\.dp""")

        /**
         * Today's offenders, as `module-relative path to count`. Pending until
         * Tasks 2 and 3 of #404 move each onto a token — see the class doc.
         * Every entry here is a private `*Corner` constant or a raw
         * `RoundedCornerShape(...)` literal the 2026-10-10 audit named.
         */
        val allowance: Map<String, Int> = mapOf(
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/day/DayRibbonView.kt" to 2,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/onboarding/OnboardingHero.kt" to 1,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/week/DayAccent.kt" to 2,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/AboutCard.kt" to 2,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/BugReportSheet.kt" to 2,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/settings/UpdateSheet.kt" to 1,
            "app/src/main/kotlin/com/lumenpearson/lessons/ui/translate/TranslationEditorSheet.kt" to 2,
            "core/designsystem/src/main/kotlin/com/lumenpearson/lessons/core/designsystem/text/Corrections.kt" to 1,
        )
    }
}
