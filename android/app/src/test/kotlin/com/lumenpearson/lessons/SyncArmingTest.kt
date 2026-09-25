package com.lumenpearson.lessons

import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.data.repository.SyncArming
import com.lumenpearson.lessons.core.data.repository.syncArmingFor
import java.io.File
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #152: every cold start armed the fifteen-minute background sync, on a phone
 * in no class too.
 *
 * `LessonsApplication.observeSyncInterval` collected the interval alone and
 * scheduled the worker on its first emission, so a phone that had left its
 * last class — whose sign-out had just cancelled the worker — was woken again
 * from its next launch on, for ever, for a run that found no class and
 * returned. A diary-only phone would have been woken the same way. What the
 * application runs now is [driveSyncScheduler] over `shellMode.syncArming`.
 */
class SyncArmingTest {

    private class Recorder {
        val calls = mutableListOf<String>()
        fun schedule(minutes: Int) {
            calls += "schedule $minutes"
        }
        fun cancel() {
            calls += "cancel"
        }
    }

    @Test
    fun `a cold start in no class or on the diary cancels rather than arms`() {
        for (mode in listOf(ShellMode.NONE, ShellMode.DIARY)) {
            val recorder = Recorder()
            runBlocking {
                flowOf(syncArmingFor(mode, intervalMinutes = 15))
                    .driveSyncScheduler(recorder::schedule, recorder::cancel)
            }
            assertEquals(mode.name, listOf("cancel"), recorder.calls)
        }
    }

    @Test
    fun `a class is armed at its interval and re-armed when it changes`() {
        val recorder = Recorder()
        runBlocking {
            flowOf(
                SyncArming.Armed(15),
                SyncArming.Armed(60),
                SyncArming.Disarmed,
            ).driveSyncScheduler(recorder::schedule, recorder::cancel)
        }
        assertEquals(listOf("schedule 15", "schedule 60", "cancel"), recorder.calls)
    }

    /**
     * The application's own source, because what it collects is the defect:
     * a collector of the interval alone compiles, runs and arms a phone in no
     * class, and nothing but reading what it collects can tell the two apart.
     */
    @Test
    fun `the application arms the worker from the mode, not from the interval alone`() {
        val source = applicationSource().readText()
        assertTrue(
            "LessonsApplication must collect shellMode.syncArming",
            Regex("""shellMode\s*\.\s*syncArming""").containsMatchIn(source),
        )
        assertFalse(
            "LessonsApplication collects the interval on its own again (#152)",
            Regex("""syncIntervalMinutes""").containsMatchIn(source),
        )
        // One way to the scheduler: through the driver, whose only input is
        // the mode-and-interval rule.
        val direct = Regex("""SyncScheduler\.schedulePeriodic""").findAll(source).count()
        assertEquals(1, direct)
        assertTrue(Regex("""driveSyncScheduler\(""").containsMatchIn(source))
    }

    private fun applicationSource(): File {
        var directory: File? = File("").absoluteFile
        while (directory != null) {
            val candidates = listOf(
                File(directory, "src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt"),
                File(directory, "app/src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt"),
                File(directory, "android/app/src/main/kotlin/com/lumenpearson/lessons/LessonsApplication.kt"),
            )
            candidates.firstOrNull { it.isFile }?.let { return it }
            directory = directory.parentFile
        }
        error("LessonsApplication.kt not found from ${File("").absolutePath}")
    }
}
