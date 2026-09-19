package com.lumenpearson.lessons.core.data.diagnostics

import java.time.Instant
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * That two crash reports are two files.
 *
 * The name was the timestamp alone, to the second, and `writeText` truncates.
 * One failure taking two threads down with it — the ordinary shape of a crash,
 * and the shape `CrashStampTest` already exercises the formatter for — wrote
 * both reports to one path and the first was gone. The feature exists because
 * there is no Play Console behind this app; a report that quietly replaces the
 * one before it is the feature not existing.
 *
 * Widening the stamp to milliseconds would have been a smaller window rather
 * than no window, so the name is *claimed* instead: `createNewFile` tests and
 * takes in one atomic step.
 */
class CrashReportFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val moment = Instant.parse("2026-09-14T05:07:09Z")

    @Test
    fun `two reports in the same second are two files`() {
        val directory = folder.newFolder("crash_reports")

        val first = CrashReporter.reportFile(directory, moment)
        val second = CrashReporter.reportFile(directory, moment)

        assertTrue("the first name must already be taken", first.exists())
        assertTrue(second.exists())
        assertEquals(
            "one name means the second report overwrites the first",
            2,
            directory.listFiles().orEmpty().size,
        )
        assertTrue(first.name != second.name)
    }

    /**
     * Thirty-two threads, one instant: the shape the bug needed, and the one
     * a lock around a name check would still have got wrong if the check and
     * the write were two steps.
     */
    @Test
    fun `threads claiming the same second never claim the same file`() {
        val directory = folder.newFolder("crash_reports")
        val threads = 32
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val work = List(threads) { Callable { CrashReporter.reportFile(directory, moment).name } }
            val names = pool.invokeAll(work).map { it.get(60, TimeUnit.SECONDS) }

            assertEquals(threads, names.distinct().size)
            assertEquals(threads, directory.listFiles().orEmpty().size)
        } finally {
            pool.shutdownNow()
        }
    }

    /**
     * The half of this that is easy to leave broken. `prune` deletes by the
     * order `reports` returns, so once two reports can share a second the
     * ordering has to break that tie — and a plain `sortedByDescending` on
     * `lastModified` is stable, so the report destroyed becomes whichever one
     * the directory happened to list first.
     */
    @Test
    fun `reports written in the same millisecond still sort newest first`() {
        val directory = folder.newFolder("crash_reports")
        val first = CrashReporter.reportFile(directory, moment)
        val second = CrashReporter.reportFile(directory, moment)
        // The filesystem cannot be relied on to tell these apart, so say out
        // loud that it does not: this is the tie the name has to break.
        val stamp = 1_600_000_000_000L
        assertTrue(first.setLastModified(stamp))
        assertTrue(second.setLastModified(stamp))

        assertEquals(
            listOf(second, first),
            CrashReporter.newestFirst(listOf(first, second)),
        )
        assertEquals(
            "the directory's own order must not decide it either",
            listOf(second, first),
            CrashReporter.newestFirst(listOf(second, first)),
        )
    }

    /**
     * Zero-padding is why the tiebreak works past the tenth report of one
     * second: `_10` sorts between `_1` and `_2` and would put the eleventh
     * report in the middle of the batch.
     */
    @Test
    fun `the counter is padded so ten sorts after nine`() {
        val directory = folder.newFolder("crash_reports")
        val written = (1..12).map { CrashReporter.reportFile(directory, moment) }
        written.forEach { assertTrue(it.setLastModified(1_600_000_000_000L)) }

        assertEquals(written.reversed(), CrashReporter.newestFirst(written.shuffled()))
    }

    /** A different second still sorts by the second, whatever the counters say. */
    @Test
    fun `a later second outranks a higher counter from an earlier one`() {
        val directory = folder.newFolder("crash_reports")
        val older = (1..3).map { CrashReporter.reportFile(directory, moment) }
        val newer = CrashReporter.reportFile(directory, moment.plusSeconds(1))
        (older + newer).forEach { assertTrue(it.setLastModified(1_600_000_000_000L)) }

        assertEquals(newer, CrashReporter.newestFirst(older + newer).first())
    }

    /** The name is still a name a file system will take; see `CrashStampTest`. */
    @Test
    fun `the name carries no character a file name cannot`() {
        val directory = folder.newFolder("crash_reports")
        val name = CrashReporter.reportFile(directory, moment).name

        assertTrue(name.startsWith("crash_"))
        assertTrue(name.endsWith(".log"))
        assertTrue(name.none { it in """/\:*?"<>| """ })
    }
}
