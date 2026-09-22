package com.lumenpearson.lessons.ui.debug

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Whether a crash report can be reached by the person whose phone wrote it.
 *
 * The switch that records them has always been on «О приложении», where
 * everybody can reach it. The two ways to *read* one were the manager's: the
 * bug button beside the toolbar's pill and the management page's own row. So a
 * reader who was not an administrator could turn the feature on, crash, and
 * have no way to see or send the file — it is written to a folder Android 11
 * stopped file managers from opening, and no screen they could reach would show
 * it.
 *
 * That was found the hard way: a crash that happens seconds after this phone is
 * linked to Telegram, which is both the moment the manager's routes appear and
 * the moment the app stops staying open long enough to use them.
 */
// marquee clock: this asks whether a `DebugRow` is reachable at all, with
// the labels the product ships — every one of them a settings row that owns
// the full 411 dp width. The long strings in this file are its own assertion
// messages and never reach a composable.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class DebugReachTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the row opens the reports`() {
        compose.setContent {
            LessonsTheme { DebugRow(enabled = true, onEnabledChange = {}) }
        }

        compose.onNodeWithText("Отчёты о сбоях").assertIsDisplayed().performClick()
        compose.waitForIdle()

        // The sheet's own heading, which only exists once it is open.
        compose.onNodeWithText("Отладка").assertIsDisplayed()
    }

    /**
     * Read from the source, like `CorrectionReachTest` and
     * `NoEllipsisedLineTest`: the question is not whether the row draws — the
     * test above asks that — but whether it is *on the page everybody has*. A
     * composition test cannot tell, because it is handed the row it is asked to
     * compose.
     */
    @Test
    fun `the way in is on the page that is not gated on a role`() {
        val screen = sourceOf("ui/settings/SettingsScreen.kt")
        val about = screen.substringAfter("private fun LazyListScope.aboutRows(")
            .substringBefore("\nprivate fun ")

        assertTrue(
            "«О приложении» carries the switch that writes crash reports, so it " +
                "has to carry the way to read them: nothing else a non-manager " +
                "can reach does.",
            about.contains("DebugRow("),
        )
        assertTrue(
            "the row must not be gated on the role — that gate is the defect " +
                "this test exists for",
            !about.contains("isClassManager"),
        )
    }

    private fun sourceOf(path: String): String {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val file = File(root, "app/src/main/kotlin/com/lumenpearson/lessons/$path")
        assertTrue("${file.path} is missing", file.isFile)
        return file.readText()
    }
}
