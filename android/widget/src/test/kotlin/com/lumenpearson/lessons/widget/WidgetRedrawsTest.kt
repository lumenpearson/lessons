package com.lumenpearson.lessons.widget

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A widget already on screen re-reads for every redraw asked for after its
 * snapshot, and for no other (#168).
 *
 * What went wrong on a device: Glance answers a redraw inside a live session by
 * recomposing, and the snapshot had been read once for the whole session, so a
 * class switch ten seconds after another drew the class the phone had left.
 * What this holds is the edge that fixes it — a counter the redraw bumps and a
 * composition that re-reads when it moves. Whether Glance itself then pushes
 * the new frame is the launcher's side and was checked on an emulator.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WidgetRedrawsTest {

    @Test
    fun `nothing is re-read until a redraw is asked for`() = runTest {
        val generation = MutableStateFlow(7L)
        var reads = 0
        val seen = mutableListOf<Int>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            rereadsAfter(generation, readAt = 7L) { ++reads }.toList(seen)
        }
        advanceUntilIdle()

        assertEquals("the snapshot on screen already answers generation 7", 0, reads)
        job.cancel()
    }

    @Test
    fun `every redraw after the snapshot reads again`() = runTest {
        val generation = MutableStateFlow(7L)
        var reads = 0
        val seen = mutableListOf<Int>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            rereadsAfter(generation, readAt = 7L) { ++reads }.toList(seen)
        }
        generation.value = 8L
        generation.value = 9L
        advanceUntilIdle()

        assertEquals(listOf(1, 2), seen)
        job.cancel()
    }

    /**
     * The race the counter is read *before* the snapshot for: a redraw that
     * lands while the first read is still running, before anything collects.
     */
    @Test
    fun `a redraw between the read and the collection is not lost`() = runTest {
        val generation = MutableStateFlow(7L)
        val readAt = generation.value
        generation.value = 8L // asked for while the first snapshot was being read
        var reads = 0
        val seen = mutableListOf<Int>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            rereadsAfter(generation, readAt) { ++reads }.toList(seen)
        }
        advanceUntilIdle()

        assertEquals(listOf(1), seen)
        job.cancel()
    }

    @Test
    fun `a newer redraw replaces a read still in progress`() = runTest {
        val generation = MutableStateFlow(0L)
        val slow = CompletableDeferred<String>()
        val seen = mutableListOf<String>()

        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            rereadsAfter(generation, readAt = 0L) {
                if (generation.value == 1L) slow.await() else "generation ${generation.value}"
            }.toList(seen)
        }
        generation.value = 1L // starts a read that hangs
        generation.value = 2L // and this one supersedes it
        slow.complete("stale")
        advanceUntilIdle()

        assertEquals(listOf("generation 2"), seen)
        job.cancel()
    }

    @Test
    fun `asking bumps the process-wide counter`() {
        val before = WidgetRedraws.current.value
        WidgetRedraws.ask()
        assertEquals(before + 1, WidgetRedraws.current.value)
    }
}
