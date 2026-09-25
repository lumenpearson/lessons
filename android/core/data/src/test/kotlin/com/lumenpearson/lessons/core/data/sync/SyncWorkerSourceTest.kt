package com.lumenpearson.lessons.core.data.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Nothing that runs without somebody looking at the app reads the diary.
 *
 * It is a rule about the product rather than about code. A diary read is what
 * keeps our server's copy of a family's session alive (`last_used_at`), and the
 * owner's rule is that «activity» means a person using the app — so a worker
 * or a widget that read the diary would keep a session alive for ever with
 * nobody behind it, and would also wake the phone to do it. The foreground
 * refresh (`DiaryImport.refreshIfStale`) is the one sanctioned reader.
 *
 * No behaviour test can see a read that is simply never supposed to exist,
 * so this reads the sources the way `DaoDeletePolicyTest` reads the SQL: the
 * sync worker, every file of the widget module, and the alerts — the three
 * things the system wakes on its own. What it cannot see: a diary read reached
 * through some other name, or a new background entry point outside these
 * folders.
 */
class SyncWorkerSourceTest {

    private companion object {
        /** The types and the container properties that reach the diary. */
        val DIARY = Regex("""\b(DiaryRepository|DiaryImport|DiaryCache|DiaryApi|diaryRepository|diaryImport|diaryCache)\b""")
    }

    private val worker = File("src/main/kotlin/com/lumenpearson/lessons/core/data/sync/SyncWorker.kt")
    private val widget = File("../../widget/src/main")
    private val alerts = File("src/main/kotlin/com/lumenpearson/lessons/core/data/notifications")

    private fun kotlinUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test
    fun `the sources are where this test thinks they are`() {
        assertTrue(worker.absolutePath, worker.isFile)
        assertTrue(widget.absolutePath, kotlinUnder(widget).size >= 5)
        assertTrue(alerts.absolutePath, kotlinUnder(alerts).size >= 3)
    }

    @Test
    fun `the worker and the widget never reference the diary`() {
        val offenders = (listOf(worker) + kotlinUnder(widget) + kotlinUnder(alerts))
            .flatMap { file ->
                file.readLines().mapIndexedNotNull { index, line ->
                    DIARY.find(line)?.let { "${file.path}:${index + 1}: ${line.trim()}" }
                }
            }
        assertEquals(
            "A background path reaches the diary; see the class note for why it may not:\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    /** The guard's own regex, so a rename of the diary types is noticed here first. */
    @Test
    fun `the guard recognises what it is looking for`() {
        assertTrue(DIARY.containsMatchIn("Graph.container.diaryRepository.students()"))
        assertTrue(DIARY.containsMatchIn("val import: DiaryImport"))
        assertTrue(!DIARY.containsMatchIn("DiaryRepositoryImplementation"))
    }
}
