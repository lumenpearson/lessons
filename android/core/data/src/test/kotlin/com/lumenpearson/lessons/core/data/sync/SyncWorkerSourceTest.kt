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
 * sync worker, every file of the widget module, the alerts, and every class a
 * manifest names as the application, a receiver or a service — each of which
 * the system starts on its own. The application's `onCreate` is the one most
 * easily missed: it runs on every cold start, including the one a widget
 * update or an alarm causes with the screen off, and it is the obvious place
 * to put an «on app start» refresh. Activities are left out on purpose: they
 * are the one kind of entry point with a person in front of it.
 *
 * What it cannot see: a diary read reached through some other name, or a
 * background entry point no manifest declares (a WorkManager worker other than
 * the sync worker).
 */
class SyncWorkerSourceTest {

    private companion object {
        /** The types and the container properties that reach the diary. */
        val DIARY = Regex("""\b(DiaryRepository|DiaryImport|DiaryCache|DiaryApi|diaryRepository|diaryImport|diaryCache)\b""")

        /** `<application|receiver|service … android:name="…"`, attributes in any order and across lines. */
        val DECLARED = Regex("""<(application|receiver|service)\b[^>]*?android:name\s*=\s*"([^"]+)"""")

        val NAMESPACE = Regex("""namespace\s*=\s*"([^"]+)"""")

        /** What the manifests declare today; a missing one means the discovery below broke, not the rule. */
        val KNOWN = setOf(
            "com.lumenpearson.lessons.LessonsApplication",
            "com.lumenpearson.lessons.core.data.notifications.SchoolAlertReceiver",
            "com.lumenpearson.lessons.widget.LessonsWidgetReceiver",
            "com.lumenpearson.lessons.widget.tick.WidgetTickReceiver",
        )
    }

    private val android = File("../..")
    private val worker = File("src/main/kotlin/com/lumenpearson/lessons/core/data/sync/SyncWorker.kt")
    private val widget = File("../../widget/src/main")
    private val alerts = File("src/main/kotlin/com/lumenpearson/lessons/core/data/notifications")

    private fun kotlinUnder(root: File): List<File> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    /** Every module's own `src/main`, found rather than listed, so a sixth module is read too. */
    private fun modules(): List<File> =
        android.walkTopDown()
            .onEnter { it.name !in setOf("build", ".gradle", ".idea", "kotlin", "res", "test", "androidTest") }
            .filter { it.isFile && it.invariantSeparatorsPath.endsWith("/src/main/AndroidManifest.xml") }
            .map { it.parentFile.parentFile.parentFile }
            .toList()

    /** Class name → its source file, for every application, receiver and service a manifest of ours declares. */
    private fun declaredEntryPoints(): Map<String, File> = modules().flatMap { module ->
        val manifest = File(module, "src/main/AndroidManifest.xml").readText()
        val namespace = File(module, "build.gradle.kts").takeIf { it.isFile }
            ?.let { NAMESPACE.find(it.readText())?.groupValues?.get(1) }
        DECLARED.findAll(manifest).mapNotNull { match ->
            val name = match.groupValues[2]
            val qualified = when {
                name.startsWith(".") -> (namespace ?: error("${module.path} declares $name without a namespace")) + name
                name.startsWith("com.lumenpearson.") -> name
                else -> return@mapNotNull null // a library's class, not ours to read
            }
            qualified to File(module, "src/main/kotlin/" + qualified.replace('.', '/') + ".kt")
        }.toList()
    }.toMap()

    @Test
    fun `the sources are where this test thinks they are`() {
        assertTrue(worker.absolutePath, worker.isFile)
        assertTrue(widget.absolutePath, kotlinUnder(widget).size >= 5)
        assertTrue(alerts.absolutePath, kotlinUnder(alerts).size >= 3)
        val declared = declaredEntryPoints()
        assertTrue("found ${declared.keys}", declared.keys.containsAll(KNOWN))
        for ((name, file) in declared) {
            assertTrue("$name is declared, but its source is not at ${file.path}; teach this test where it is", file.isFile)
        }
    }

    @Test
    fun `the worker, the widget and every declared background entry point never reference the diary`() {
        val offenders = (listOf(worker) + kotlinUnder(widget) + kotlinUnder(alerts) + declaredEntryPoints().values)
            .distinctBy { it.canonicalPath }
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
        val application = """<application
            android:name=".LessonsApplication"
            android:label="x">"""
        assertEquals(".LessonsApplication", DECLARED.find(application)?.groupValues?.get(2))
    }
}
