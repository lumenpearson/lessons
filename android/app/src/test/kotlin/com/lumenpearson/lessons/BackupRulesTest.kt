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
        assertWritesOnlyToTheReportsFolder(text)
        checkNotNull(name)
    }

    /**
     * The two places the reporter writes, and nowhere else — so a third one
     * added there is a question this test has to be taught rather than one it
     * misses. A presence check of the two could not fail when a third
     * appeared beside them: a `File(context.filesDir, "last_crash.txt")` sits in
     * the `file` domain outside `crash_reports`, which the rules do not exclude.
     *
     * Every storage root Android hands an app is looked for, with comments
     * taken out first so a sentence about one is not a write; what remains once
     * the two known expressions are removed must name none. A path spelt out as
     * a string is refused for the same reason.
     */
    private fun assertWritesOnlyToTheReportsFolder(source: String) {
        val code = source
            .replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""//[^\n]*"""), " ")
        for (known in KNOWN_LOCATIONS) {
            assertTrue("CrashReporter no longer writes to $known; teach this test where it writes", known in code)
        }
        val rest = KNOWN_LOCATIONS.fold(code) { text, known -> text.replace(known, " ") }
        val other = STORAGE_ROOTS.findAll(rest).map { it.value }.toList() + LITERAL_PATH.findAll(rest).map { it.value }
        assertTrue(
            "CrashReporter writes somewhere other than ${KNOWN_LOCATIONS.joinToString(" and ")}: $other — " +
                "the backup rules exclude only the reports folder, so a file anywhere else goes to Google",
            other.isEmpty(),
        )
    }

    private companion object {
        /** Where `CrashReporter.directory` puts the reports folder: external files, else internal. */
        val KNOWN_LOCATIONS = listOf("getExternalFilesDir(REPORTS_DIR)", "File(context.filesDir, REPORTS_DIR)")

        /** Every directory, file and store an app's `Context` or `Environment` can name. */
        val STORAGE_ROOTS = Regex(
            """\b(getExternalFilesDirs?|getExternalCacheDirs?|externalCacheDirs?|externalMediaDirs|filesDir|""" +
                """cacheDir|noBackupFilesDir|codeCacheDir|dataDir|obbDirs?|getDir|openFileOutput|getDatabasePath|""" +
                """getSharedPreferences|getExternalStorageDirectory|getExternalStoragePublicDirectory|dataStore)\b""",
        )

        /** A `File("/…")` or `File("…")` whose root is a literal rather than one of the above. */
        val LITERAL_PATH = Regex("""\bFile\(\s*"""")
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
