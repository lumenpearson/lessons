package com.lumenpearson.lessons.core.data.diagnostics

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The developer mode's activity record (#237): off keeps nothing, on keeps the newest. */
class ActivityLogTest {

    @Before
    fun setUp() = ActivityLog.setRecording(false)

    @After
    fun tearDown() = ActivityLog.setRecording(false)

    @Test
    fun `off, nothing is kept`() {
        ActivityLog.record(ActivityKind.SYNC, "background sync")

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `on, events are kept in order, and the oldest go past the record's size`() {
        ActivityLog.setRecording(true)

        repeat(305) { ActivityLog.record(ActivityKind.SCREEN, "page $it") }

        val texts = ActivityLog.entries.value.map { it.text }
        assertEquals(300, texts.size)
        assertEquals("page 5", texts.first())
        assertEquals("page 304", texts.last())
    }

    @Test
    fun `switching recording off empties the record`() {
        ActivityLog.setRecording(true)
        ActivityLog.record(ActivityKind.WIDGET, "redraw requested")

        ActivityLog.setRecording(false)

        assertTrue(ActivityLog.entries.value.isEmpty())
    }

    @Test
    fun `a step is written with how it ended and how long it took`() {
        val ok = ActivityLog.outcome("register", failure = null, startedNanos = System.nanoTime())
        val failed = ActivityLog.outcome("preflight", IllegalStateException(), startedNanos = System.nanoTime())

        assertTrue(ok, Regex("""register: ok in \d+ ms""").matches(ok))
        assertTrue(failed, Regex("""preflight: IllegalStateException in \d+ ms""").matches(failed))
    }
}
