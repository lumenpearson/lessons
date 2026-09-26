package com.lumenpearson.lessons.widget

import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.Timetable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which mode the widget draws for, read the way the render reads it.
 *
 * The sentence a widget shows with nothing to draw is chosen by the mode, and
 * a diary-only phone got the one sending it to join a class while the mode
 * was read off the class session. `EmptyTextTest` holds the sentences for
 * each mode it is handed; this holds that the render hands it the shell's.
 */
class WidgetModeReadTest {

    private val timetable = Timetable(
        schoolClass = SchoolClassInfo(id = 1, name = "9А", timeZoneId = "Europe/Moscow"),
        days = emptyList(),
    )

    private class Shell(private val mode: ShellMode) : ShellModeSource {
        override val state: Flow<ShellState> = flowOf(ShellState(mode, held = false))
        override suspend fun current() = ShellState(mode, held = false)
        override val syncArming: Flow<SyncArming> = emptyFlow()
        override suspend fun hold() = Unit
        override suspend fun release() = Unit
        override suspend fun settleColdStart() = current()
    }

    @Test
    fun `a diary-only phone is drawn as one, and its class cache is never read`() = runBlocking {
        var reads = 0
        val (mode, read) = readModeAndTimetable(Shell(ShellMode.DIARY)) {
            reads += 1
            timetable
        }

        assertEquals(ShellMode.DIARY, mode)
        assertNull(read)
        assertEquals("outside a class Room is not asked at all", 0, reads)
    }

    @Test
    fun `every mode is the shell's, and only a class reads its timetable`() = runBlocking {
        for (shell in ShellMode.entries) {
            var reads = 0
            val (mode, read) = readModeAndTimetable(Shell(shell)) {
                reads += 1
                timetable
            }
            assertEquals(shell, mode)
            if (shell == ShellMode.CLASS) {
                assertSame(timetable, read)
                assertEquals(1, reads)
            } else {
                assertNull("$shell", read)
                assertEquals("$shell", 0, reads)
            }
        }
    }
}
