package com.lumenpearson.lessons.core.data.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * That two threads can stamp a crash report at once.
 *
 * These formatters used to be `SimpleDateFormat`, which keeps a `Calendar`
 * between calls and is documented as not thread-safe. Nothing serialised them:
 * `log` formats under the buffer's lock, `write` formats under none, and the
 * threads are whichever ones logged and whichever one died. The visible results
 * are a garbled timestamp, a report filename with a stray digit — or an
 * exception raised inside the uncaught-exception handler, which is the one
 * place in the app that cannot afford one.
 */
class CrashStampTest {

    private val moment = Instant.parse("2026-09-14T05:07:09Z")
    private val moscow = ZoneId.of("Europe/Moscow")

    @Test
    fun `the report stamp is the wall time of the zone it is given`() {
        assertEquals(
            "2026-09-14 08:07:09",
            CrashReporter.stamp(CrashReporter.reportStamp, moment, moscow),
        )
    }

    @Test
    fun `the filename stamp carries no character a file name cannot`() {
        val stamp = CrashReporter.stamp(CrashReporter.nameStamp, moment, moscow)
        assertEquals("2026-09-14_08-07-09", stamp)
        assertTrue(stamp.none { it in """/\:*?"<>| """ })
    }

    /**
     * Each thread stamps its own moment, which is the shape the bug needed.
     *
     * Formatting one shared instant from many threads is not enough to catch it
     * — `SimpleDateFormat.format` sets its `Calendar` and then reads it back, so
     * with identical input the interleaving still produces identical digits, and
     * a test written that way passes on the broken code. It corrupts when the
     * instants differ: one thread writes its time into the shared `Calendar`
     * and another formats it. That is exactly the app's own shape, where `log`
     * stamps the moment it was called and `write` stamps the moment of the
     * crash.
     *
     * Measured on the code this replaced: 40060 of 64000 answers came back
     * belonging to another thread. Against an immutable `DateTimeFormatter` the
     * count is zero, and can only be zero.
     */
    @Test
    fun `threads stamping different instants never get each other's answer`() {
        val threads = 32
        val each = 2000
        val pool = Executors.newFixedThreadPool(threads)
        try {
            val work = List(threads) { index ->
                // One distinct day each, so a stolen answer is unmistakable.
                val mine = moment.plus(index.toLong(), ChronoUnit.DAYS)
                val expected = CrashReporter.stamp(CrashReporter.reportStamp, mine, moscow)
                Callable {
                    (1..each).count {
                        CrashReporter.stamp(CrashReporter.reportStamp, mine, moscow) != expected
                    }
                }
            }
            val wrong = pool.invokeAll(work).sumOf { it.get(60, TimeUnit.SECONDS) }

            assertEquals(0, wrong)
        } finally {
            pool.shutdownNow()
        }
    }
}
