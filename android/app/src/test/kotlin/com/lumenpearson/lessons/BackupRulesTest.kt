package com.lumenpearson.lessons

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Crash reports stay out of every copy the system makes of this app (#156).
 *
 * The first run tells a reader that a report «остаётся на телефоне» until they
 * send it, and the privacy policy repeats the promise. A report holds the
 * phone's model, versions, a stack trace and the latest log lines; Android's
 * Auto Backup copies an app's files to Google by default, `external` domain
 * included, unless the rules say otherwise — and until this test the rules
 * excluded the databases and the preferences and said nothing about reports.
 *
 * There are two rule files because there are two formats: `backup_rules.xml`
 * is read up to Android 11, `data_extraction_rules.xml` from Android 12, where
 * the cloud and a transfer to a new phone are two lists. A report must be out
 * of all three, and in both of the places `CrashReporter` may write it — the
 * external files folder, and the internal one it falls back to when there is
 * no external storage.
 *
 * The folder name is read out of `CrashReporter` itself rather than typed here,
 * so renaming the folder there fails this instead of quietly letting the new
 * folder into the backup. What this cannot say is whether a real phone obeys
 * the rules: that needs a device and `bmgr`, and has not been done.
 */
class BackupRulesTest {

    private val xml = File("src/main/res/xml")

    private val reportsFolder: String by lazy {
        val source = File(
            "../core/data/src/main/kotlin/com/lumenpearson/lessons/core/data/diagnostics/CrashReporter.kt",
        )
        assertTrue("${source.path} is missing — the walk went wrong", source.isFile)
        val text = source.readText()
        val name = Regex("""REPORTS_DIR\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
        assertTrue(
            "CrashReporter no longer declares REPORTS_DIR, so this test cannot tell " +
                "where the reports are",
            name != null,
        )
        // Both places the reporter writes, so a third one added there is a
        // question this test has to be taught rather than one it misses.
        assertTrue(
            "CrashReporter writes somewhere other than getExternalFilesDir and filesDir",
            text.contains("getExternalFilesDir(REPORTS_DIR)") &&
                text.contains("File(context.filesDir, REPORTS_DIR)"),
        )
        checkNotNull(name)
    }

    /** Every `<exclude>` under [parent] (or the whole file), as domain to path. */
    private fun excludes(file: String, parent: String?): Set<Pair<String, String>> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, file))
        val scope = if (parent == null) {
            document.documentElement
        } else {
            val nodes = document.getElementsByTagName(parent)
            assertTrue("$file has no <$parent>", nodes.length == 1)
            nodes.item(0) as Element
        }
        val found = scope.getElementsByTagName("exclude")
        return (0 until found.length).map { index ->
            val element = found.item(index) as Element
            element.getAttribute("domain") to element.getAttribute("path").trimEnd('/')
        }.toSet()
    }

    private fun assertReportsExcluded(where: String, rules: Set<Pair<String, String>>) {
        listOf("external", "file").forEach { domain ->
            assertTrue(
                "$where lets crash reports under the «$domain» domain into the copy, " +
                    "although the app promises they stay on the phone: $rules",
                (domain to reportsFolder) in rules,
            )
        }
    }

    @Test
    fun `reports are out of the backup up to Android 11`() {
        assertReportsExcluded("backup_rules.xml", excludes("backup_rules.xml", parent = null))
    }

    @Test
    fun `reports are out of the cloud backup from Android 12`() {
        assertReportsExcluded(
            "data_extraction_rules.xml <cloud-backup>",
            excludes("data_extraction_rules.xml", parent = "cloud-backup"),
        )
    }

    @Test
    fun `reports are out of a transfer to a new phone from Android 12`() {
        assertReportsExcluded(
            "data_extraction_rules.xml <device-transfer>",
            excludes("data_extraction_rules.xml", parent = "device-transfer"),
        )
    }

    @Test
    fun `the three lists exclude the same things`() {
        // One reasoning, written once in backup_rules.xml: a list that drifts
        // from the other two is a promise kept on some Android versions only.
        val legacy = excludes("backup_rules.xml", parent = null)
        val cloud = excludes("data_extraction_rules.xml", parent = "cloud-backup")
        val transfer = excludes("data_extraction_rules.xml", parent = "device-transfer")
        assertTrue("backup_rules.xml $legacy vs cloud $cloud", legacy == cloud)
        assertTrue("cloud $cloud vs device transfer $transfer", cloud == transfer)
    }
}
