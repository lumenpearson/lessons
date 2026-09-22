package com.lumenpearson.lessons

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A Compose test that can draw a marquee has to say what it does about the clock.
 *
 * Three suites here hold Compose's clock by hand, and each says the same thing
 * about why: `MarqueeText` scrolls for as long as the line is on screen
 * (`basicMarquee(iterations = Int.MAX_VALUE)` — the fix for a row that stopped
 * after three passes and sat there clipped), so the clock is never idle, so
 * `waitForIdle` — which every assertion on a `ComposeTestRule` calls into —
 * never returns. Not a red test: a hung one, and a Gradle run hung with it.
 * `MarqueeText`'s own KDoc says so, `MarqueeTextTest` says so at length, and
 * `HANDOVER.md` repeats it.
 *
 * **On this toolchain it does not reproduce, and that is written here rather
 * than assumed either way.** Taking the `@Before` out of `MarqueeTextTest` —
 * so that an overflowing line really is composed with `autoAdvance` on — leaves
 * all ten of its tests green in six seconds, the three composed ones included,
 * every one of them asserting through `waitForIdle`. Compose's test environment
 * appears to exclude an infinite animation from idleness rather than wait on
 * it, which would make the hang a property of some earlier version. That was
 * measured, not read.
 *
 * So this file does **not** claim the hang. What it holds is narrower and still
 * worth holding:
 *
 *  * The rule is the project's, it is written in three places, and thirteen
 *    Compose tests were in neither position — not holding the clock, and not
 *    saying why they did not need to. «Not having thought about it» and «having
 *    thought about it» looked identical, which is the state a marker fixes.
 *  * Holding the clock has a cost of its own that is entirely real today: with
 *    it held, `performScrollTo` moves nothing, because it drives the
 *    scrollable's own animation — so every node below the fold reports «not
 *    displayed», which is indistinguishable from the node being absent. That
 *    cost two rounds once. Two of the exemptions below are exactly that, and
 *    naming them is what stops somebody «fixing» those files by holding the
 *    clock.
 *  * If the idleness behaviour ever goes back, the exemptions are the list of
 *    files to revisit, already written, with each one's reason beside it.
 *
 * Same shape as `DeviceClockTest`: not «never», but «say so». Hold the clock,
 * or write [MARKER] with the reason this file cannot reach a scrolling line.
 */
class MarqueeClockTest {

    @Test
    fun `every compose test that can draw a marquee holds the clock or says why not`() {
        val unexplained = composeTests
            .filter { (_, drawn) -> drawn.isNotEmpty() }
            .filterNot { (file, _) -> holdsTheClock(file) || isExplained(file) }
            .map { (file, drawn) -> "${file.name} composes ${drawn.joinToString(", ")}" }

        assertTrue(
            "These compose a component that can draw a `MarqueeText` and say nothing " +
                "about Compose's clock — so whether they were written knowing that, or " +
                "without ever asking, cannot be told apart. Either set " +
                "`mainClock.autoAdvance = false` in a `@Before` and advance frames by " +
                "hand, or write `$MARKER <why this one cannot reach a scrolling line>` " +
                "in the file. Do not hold the clock in a test that uses " +
                "`performScrollTo`; read this file's note first:\n" +
                unexplained.joinToString("\n"),
            unexplained.isEmpty(),
        )
    }

    /**
     * The scan is looking for something that is mostly absent, so the parts it
     * depends on are pinned here rather than trusted.
     *
     * A regex that stopped matching `@Composable fun` would empty the marquee
     * set, and then the test above would pass over a suite that had stopped
     * being checked — the exact shape of silent pass this file is about.
     */
    @Test
    fun `the scan found the components and the tests it is about`() {
        assertTrue(
            "no @Composable draws a MarqueeText — the declaration regex has stopped " +
                "matching, and the check above now passes on everything",
            marqueeBearing.isNotEmpty(),
        )
        // The direct users, by name. Transitive closure is what finds the rest,
        // and a closure over an empty seed is silent.
        for (component in listOf("LessonRow", "SegmentedPicker", "ScreenHeader", "StateHeroCard")) {
            assertTrue("$component draws a MarqueeText and was not found", component in marqueeBearing)
        }
        assertTrue(
            "the closure found only the direct users, so a screen that draws one " +
                "through a component is not being checked",
            marqueeBearing.size > 20,
        )
        assertTrue("no Compose test was found at all", composeTests.isNotEmpty())
        assertTrue(
            "no Compose test composes a marquee-bearing component, so the rule above " +
                "is checking nothing",
            composeTests.any { (_, drawn) -> drawn.isNotEmpty() },
        )
    }

    /** The marker only counts with a reason after it, or it is a rubber stamp. */
    @Test
    fun `the exemption marker is honoured and an empty one is not`() {
        val withReason = File.createTempFile("marquee", ".kt").apply {
            writeText("// $MARKER every label here is one word, so nothing can overflow.")
            deleteOnExit()
        }
        val bare = File.createTempFile("marquee", ".kt").apply {
            writeText("// $MARKER\n")
            deleteOnExit()
        }
        assertEquals(true, isExplained(withReason))
        assertEquals(false, isExplained(bare))
    }

    private companion object {

        /** Written into a Compose test that provably cannot reach the marquee. */
        const val MARKER = "marquee clock:"

        /** Enough words after the marker to be a reason rather than a token. */
        const val REASON_CHARS = 20

        /**
         * `@Composable … fun Name(`, in every spelling this codebase uses.
         *
         * The optional pieces each cost a component when they are left out, and
         * one of them was: `fun <T> SegmentedPicker(` is generic, so a pattern
         * that goes straight from `fun` to a capital letter walks past the one
         * component whose labels this whole mechanism was first written for.
         * The extension-receiver form is allowed for the same reason — a
         * `fun RowScope.Something(` is a composable like any other, and a
         * component missing from the set is a file this check passes over in
         * silence.
         */
        val COMPOSABLE = Regex(
            """@Composable\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*""" +
                """(?:private\s+|internal\s+|public\s+)?fun\s+""" +
                """(?:<[^>]*>\s*)?(?:\w+(?:<[^>]*>)?\.)?([A-Z]\w*)\s*\(""",
        )

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

        /**
         * Every module, found rather than listed — `DeviceClockTest` says at
         * length why a listed set of modules is a scan that shrinks in silence.
         */
        fun sourcesUnder(sourceSet: String): List<File> =
            androidRoot.listFiles().orEmpty()
                .flatMap { child -> listOf(child) + child.listFiles().orEmpty().toList() }
                .map { File(it, "src/$sourceSet/kotlin") }
                .filter { it.isDirectory }
                .flatMap { it.walkTopDown().filter { file -> file.extension == "kt" } }
                .also { check(it.isNotEmpty()) { "No $sourceSet sources under $androidRoot" } }

        /**
         * Every `@Composable` that can end up drawing a `MarqueeText`.
         *
         * Direct users first, then the closure: a screen that draws a
         * `SectionHeader` draws a marquee, and the test that composes the
         * screen is the one the rule is about. The body of a composable is
         * taken as the text up to the next `@Composable` in the same file,
         * which is approximate and errs towards *including* a caller — a false
         * alarm costs one sentence of exemption, a miss costs the whole point.
         */
        val marqueeBearing: Set<String> by lazy {
            val bodies = mutableMapOf<String, StringBuilder>()
            for (file in sourcesUnder("main")) {
                val text = file.readText()
                val declarations = COMPOSABLE.findAll(text).toList()
                declarations.forEachIndexed { index, match ->
                    val end = declarations.getOrNull(index + 1)?.range?.first ?: text.length
                    bodies.getOrPut(match.groupValues[1]) { StringBuilder() }
                        .append(text.substring(match.range.last + 1, end))
                }
            }
            val found = bodies.filterValues { it.contains("MarqueeText(") }.keys.toMutableSet()
            var grew = true
            while (grew) {
                grew = false
                for ((name, body) in bodies) {
                    if (name in found) continue
                    if (found.any { Regex("""\b${Regex.escape(it)}\s*\(""").containsMatchIn(body) }) {
                        found += name
                        grew = true
                    }
                }
            }
            found
        }

        /** Every Compose test, and which marquee-bearing components it composes. */
        val composeTests: List<Pair<File, List<String>>> by lazy {
            sourcesUnder("test")
                .filter { it.readText().contains("createComposeRule") }
                .map { file ->
                    val text = file.readText()
                    file to marqueeBearing
                        .filter { Regex("""\b${Regex.escape(it)}\s*\(""").containsMatchIn(text) }
                        .sorted()
                }
        }

        fun holdsTheClock(file: File): Boolean =
            file.readText().contains("mainClock.autoAdvance = false")

        fun isExplained(file: File): Boolean = file.readLines().any { line ->
            val said = line.substringAfter(MARKER, "").trim()
            line.contains(MARKER) && said.length >= REASON_CHARS
        }
    }
}
