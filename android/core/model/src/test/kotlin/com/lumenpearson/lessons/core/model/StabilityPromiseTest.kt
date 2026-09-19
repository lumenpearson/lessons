package com.lumenpearson.lessons.core.model

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on what `compose-stability.conf` promises about this module.
 *
 * That file tells the Compose compiler that everything in
 * `com.lumenpearson.lessons.core.model` is stable, because this module is
 * deliberately pure JVM — the Compose plugin is not applied here, which is why
 * its tests run in seconds — and so it carries no stability information of its
 * own. Without the promise `SchoolDay` is unstable, and one unstable field is
 * enough to make every screen state that holds a day unstable with it.
 *
 * A promise the compiler cannot verify is one a future `var` can quietly turn
 * into a lie, and the symptom is the worst kind: nothing fails to build,
 * nothing throws, a value simply changes and the screen keeps the old one,
 * because Compose was told it need not look. This reads the module's own source
 * and fails on the first mutable property, which is the only way that lie can
 * be told.
 *
 * It reads the source rather than the compiled classes on purpose, the same way
 * `ResourceTranslationTest` reads `values/` out of the tree: a `var` with a
 * private setter, a `lateinit`, a `by Delegates.observable` all compile to
 * different shapes and read identically here — the word is the rule.
 */
class StabilityPromiseTest {

    private val sources: List<File> = File("src/main/kotlin")
        .walkTopDown()
        .filter { it.isFile && it.extension == "kt" }
        .toList()

    /** Any `var`, wherever it is declared, including inside a function body. */
    private val varDeclaration = Regex("""(^|[^\w.])var\s+\w""")

    @Test
    fun `the module has source to check`() {
        assertTrue(
            "No Kotlin source under src/main/kotlin. This test reads the tree " +
                "rather than the classpath, so an empty list is a silent pass.",
            sources.isNotEmpty(),
        )
    }

    @Test
    fun `nothing in the domain module is mutable`() {
        val offenders = sources.mapNotNull { file ->
            val lines = file.readLines()
                .asSequence()
                .withIndex()
                .filterNot { (_, line) -> line.trimStart().startsWith("*") }
                .filterNot { (_, line) -> line.trimStart().startsWith("//") }
                .filter { (_, line) -> varDeclaration.containsMatchIn(line) }
                .map { (index, line) -> "${file.name}:${index + 1}: ${line.trim()}" }
                .toList()
            lines.takeIf { it.isNotEmpty() }
        }.flatten()

        assertTrue(
            "`compose-stability.conf` promises the Compose compiler that this " +
                "whole package is stable, and a `var` makes that promise false. " +
                "Either make it a `val`, or move the type out of this module and " +
                "take the package off that file — do not leave both standing:\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }
}
