package com.lumenpearson.lessons.widget

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The widget reads the fortnight around today, never the school year.
 *
 * Both call sites — the render in `LessonsWidget.loadSnapshot` and the alarm
 * chain in `WidgetTickScheduler.reschedule` — go through `Graph`, from a
 * suspend function that needs a `Context` and from a broadcast handler; neither
 * can be handed a repository by a JVM test, so nothing else can notice if one
 * of them goes back to `snapshot()`. `BoundedSnapshotParityTest` proves the
 * bounded reading answers the same thing, and this proves that is the reading
 * in use.
 *
 * It reads the source rather than the classpath, the way `StabilityPromiseTest`
 * and `ResourceTranslationTest` do: the question is which call is written, and
 * a compiled call through an interface does not say.
 *
 * Going back is not forbidden — it is a decision. A reader here that genuinely
 * needs a date outside the bound has to read the whole cache, and then this
 * test is the place to say so, in a sentence naming the date.
 */
class BoundedCacheReadTest {

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
    fun `nothing in the widget reads the whole cached year`() {
        val offenders = sources.filter { file ->
            file.readLines().any { line ->
                // The comment above `reschedule`'s read mentions the bounded
                // name; only the call itself is being looked for.
                Regex("""timetableRepository\s*\.\s*snapshot\s*\(""").containsMatchIn(line)
            }
        }

        assertEquals(
            "These read the whole school year. Two hundred days of lessons, " +
                "events and homework per redraw, and a redraw happens on every " +
                "tick of a countdown — use snapshotAroundToday().",
            emptyList<String>(),
            offenders.map { it.path },
        )
    }

    /**
     * And the reads are still there. A bound cannot be wrong if nothing reads
     * the cache at all, which is exactly how the test above would keep passing
     * through a refactor that lost one of them.
     */
    @Test
    fun `both call sites read the bounded window`() {
        val bounded = sources.filter {
            it.readText().contains("timetableRepository.snapshotAroundToday()")
        }

        assertEquals(
            "expected the render and the tick scheduler, found ${bounded.map { it.name }}",
            setOf("LessonsWidget.kt", "WidgetTickScheduler.kt"),
            bounded.map { it.name }.toSet(),
        )
    }
}
