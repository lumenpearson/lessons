package com.lumenpearson.lessons

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on "now" being the school's and not the phone's.
 *
 * The timetable is stored as the school's wall time, in the class's own zone —
 * the project serves schools across eleven of them, and a bell rings at 08:30
 * whether or not the phone agrees. `LocalDateTime.now()` and
 * `ZoneId.systemDefault()` therefore answer a question nothing on these screens
 * is asking, and they answer it plausibly: on a phone in the same zone as the
 * class, which is most of them, every one of these is correct, and nothing at
 * all goes wrong until somebody travels or leaves automatic time off.
 *
 * That is why it is a test and not a review note. The audit in `docs/design.md`
 * confirmed two of these; a later sweep found three more, in the week strip, in
 * the homework filter's cut-off and in the diary. Each had been written by
 * somebody who knew the rule.
 *
 * The rule is not "never": a read that has no timetable to take a zone from has
 * nowhere else to go, and the diary keeps Moscow time because it is one city's
 * service. So a line may say so. It must say so *on the line above it*, which
 * is the point — the comment is what makes the next reader stop, and a reviewer
 * who cannot write one has found a defect rather than an exception.
 */
class DeviceClockTest {

    @Test
    fun `every device-clock read is one somebody argued for`() {
        val unexplained = sources
            .flatMap { file -> reads(file).map { file to it } }
            .map { (file, line) -> "${file.name}:${line.first}  ${line.second.trim()}" }
        assertTrue(
            "These read the device's clock where the school's zone is what the " +
                "timetable is stored in. Route them through `Timetable.atSchool`, " +
                "or put `$MARKER` on the line above with the reason:\n" +
                unexplained.joinToString("\n"),
            unexplained.isEmpty(),
        )
    }

    /** The marker itself has to be reachable, or the test above can never pass. */
    @Test
    fun `the exemption marker is honoured`() {
        val file = File.createTempFile("clock", ".kt").apply {
            writeText(
                """
                fun a() = LocalDate.now()
                // $MARKER nothing to take a zone from here.
                fun b() = LocalDate.now()
                """.trimIndent(),
            )
            deleteOnExit()
        }
        assertTrue(reads(file).map { it.first } == listOf(1))
    }

    private companion object {

        /** Written into the line above a deliberate device-clock read. */
        const val MARKER = "device clock:"

        /**
         * A call, not a mention. `LocalDateTime.now()` inside a KDoc paragraph
         * explaining why it is wrong is the opposite of the thing being looked
         * for, and there are several of those.
         */
        val CALL = Regex(
            """\b(LocalDate|LocalDateTime|LocalTime)\.now\(\s*\)""" +
                """|\bZoneId\.systemDefault\(\s*\)""",
        )

        /**
         * Whether a line is prose rather than code.
         *
         * Crude on purpose — a `*` in column one is a KDoc body and a `//` is a
         * comment, and that is every mention in this repository. Treating a
         * string literal as code costs a false alarm the marker answers;
         * treating a comment as code would make the test unanswerable.
         */
        fun isProse(line: String): Boolean = line.trimStart().let {
            it.startsWith("*") || it.startsWith("/*") || it.startsWith("//")
        }

        /**
         * Line number (1-based) and text of every unexplained read in [file].
         *
         * "Above" means anywhere in the comment block the line sits under, not
         * only the line immediately before it — a reason worth writing rarely
         * fits on one line, and requiring it to would make the marker the
         * thing being satisfied rather than the explanation.
         */
        fun reads(file: File): List<Pair<Int, String>> {
            val lines = file.readLines()
            return lines.withIndex().mapNotNull { (index, line) ->
                if (isProse(line)) return@mapNotNull null
                if (!CALL.containsMatchIn(line.substringBefore("//"))) return@mapNotNull null
                if (line.contains(MARKER) || isExplained(lines, index)) null else index + 1 to line
            }
        }

        /** Whether the comment block directly above [index] carries the marker. */
        fun isExplained(lines: List<String>, index: Int): Boolean {
            var above = index - 1
            while (above >= 0) {
                val line = lines[above]
                // Annotations sit between a comment and what it explains, and
                // a blank line does not end the block a KDoc opened.
                val skippable = line.isBlank() || line.trimStart().startsWith("@")
                if (!isProse(line) && !skippable) return false
                if (line.contains(MARKER)) return true
                above--
            }
            return false
        }

        /**
         * Every Kotlin file the app and the widget draw a date with.
         *
         * `:core:model` is left out on purpose: it is where `atSchool` and the
         * zone fallback live, which is to say it is the one module whose job is
         * to hold the exception.
         */
        val sources: List<File> by lazy {
            listOf("app", "widget", "core/data")
                .map { File(androidRoot, "$it/src/main/kotlin") }
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" } }
                .also { check(it.isNotEmpty()) { "No sources found under $androidRoot" } }
        }

        /**
         * The `android/` directory, from wherever the runner started. Gradle
         * uses the module directory; an IDE sometimes uses the repository root.
         */
        val androidRoot: File by lazy {
            var directory: File? = File("").absoluteFile
            while (directory != null) {
                val here: File = directory
                if (File(here, "settings.gradle.kts").isFile) return@lazy here
                val nested = File(here, "android")
                if (File(nested, "settings.gradle.kts").isFile) return@lazy nested
                directory = here.parentFile
            }
            error("Could not find android/ from ${File("").absolutePath}")
        }
    }
}
