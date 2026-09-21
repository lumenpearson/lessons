package com.lumenpearson.lessons.ui.debug

import android.content.Context
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A stack trace being read survives the phone being turned.
 *
 * This is the screen where a rotation costs the most, and the reason is the
 * screen's own subject: the report is open because somebody is copying the
 * important line of it into a message to whoever can fix it. Dropping them back
 * to the list of five identical-looking timestamps is dropping them at exactly
 * the wrong moment — and the same rule the rest of the app follows is what it
 * broke, `remember` where every other sheet in the settings tree has
 * `rememberSaveable`.
 *
 * A `File` cannot go into a bundle, so what is saved is the report's name and
 * the file is looked up again in the list, which is re-read from disk — the
 * same shape as the calendar's lesson sheet.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class DebugReportRotationTest {

    @get:Rule
    val compose = createComposeRule()

    private val rotation = StateRestorationTester(compose)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** Written by hand rather than through `CrashReporter`, which refuses while
     * the feature is off — and what is being tested is the sheet, not the
     * writer. The directory is the one `CrashReporter.directory` builds. */
    private val directory: File
        get() = requireNotNull(context.getExternalFilesDir("crash_reports"))

    private val older = "МАРКЕР СТАРОГО ОТЧЁТА"
    private val newer = "МАРКЕР НОВОГО ОТЧЁТА"

    private lateinit var olderFile: File
    private lateinit var newerFile: File

    @Before
    fun writeTwoReports() {
        directory.listFiles()?.forEach { it.delete() }
        olderFile = report("crash-old.log", older, at = 1_700_000_000_000)
        newerFile = report("crash-new.log", newer, at = 1_700_000_600_000)
    }

    @Test
    fun `the report being read is still open after a rotation`() {
        showSheet()

        compose.onNodeWithText(titleOf(olderFile)).performClick()
        compose.onNodeWithText(older, substring = true).assertExists()

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(older, substring = true).assertExists()
    }

    /**
     * A name in a bundle can outlive the file it names: `CrashReporter` prunes
     * to five reports and «Очистить» deletes the lot.
     *
     * **What this does and does not hold.** It holds the outcome — nothing is
     * shown and nothing throws. It does *not* discriminate between looking the
     * name up in the list and building a `File` from it blind: the body is read
     * with `runCatching`, so a deleted file comes back as an empty string
     * either way, and this test passes against both. I checked, rather than
     * assuming it was proving the design.
     *
     * The lookup is still the right implementation, for a reason no test here
     * reaches: the name comes back from a bundle another build wrote, and
     * `reports.firstOrNull { it.name == … }` can only ever yield a file this
     * app listed, while `File(directory, name)` is a path built out of restored
     * data.
     */
    @Test
    fun `a report that is no longer on disk opens nothing`() {
        showSheet()

        compose.onNodeWithText(titleOf(newerFile)).performClick()
        compose.onNodeWithText(newer, substring = true).assertExists()

        newerFile.delete()
        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(newer, substring = true).assertDoesNotExist()
        // The sheet itself is still there — nothing was reopened blind, and
        // nothing threw on the way past.
        compose.onNodeWithText("Отладка").assertExists()
    }

    private fun showSheet() {
        rotation.setContent {
            LessonsTheme {
                DebugSheet(enabled = true, onEnabledChange = {}, onDismiss = {})
            }
        }
    }

    /** The row's title, built the way the sheet builds it. */
    private fun titleOf(file: File): String =
        SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault()).format(Date(file.lastModified()))

    private fun report(name: String, marker: String, at: Long): File =
        File(directory, name).apply {
            writeText("Время: когда-то\nЗаметка:\n$marker\n")
            setLastModified(at)
        }
}
