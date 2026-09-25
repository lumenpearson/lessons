package com.lumenpearson.lessons.ui.onboarding

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.ui.legal.LegalAcceptanceLine
import java.util.Locale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The words of the provider step's «Почему?», and where the welcome step's
 * legal line lands — the two parts of the flow a pure test cannot read.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1200dp")
class OnboardingTextTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    private fun inEnglish(): Context {
        val configuration = android.content.res.Configuration(context.resources.configuration)
        configuration.setLocale(Locale.ENGLISH)
        return context.createConfigurationContext(configuration)
    }

    private fun why(reason: WhyReason) = WhyUi(
        reason = reason,
        modifiers = WhyModifier.entries.toSet(),
        confidence = "medium",
        regionRu = "Region",
        regionEn = "Region",
        systemRu = "System",
        systemEn = "System",
        mainSystemRu = "Main",
        mainSystemEn = "Main",
        schoolYear = "2026/27",
        surveyMonth = "2026-09",
        host = "diary.example",
    )

    /**
     * Every reason the catalog can give, in both languages: the sentence formats
     * with the arguments it is handed, and names the region — a placeholder the
     * translation lost, or one it added, would print a bare `%2$s` on a page
     * whose whole job is to explain.
     */
    @Test
    fun `every reason reads whole in both languages`() {
        for (language in listOf(context, inEnglish())) {
            for (reason in WhyReason.entries) {
                val lines = whyLines(why(reason), "Region", "System", "Main", "September 2026")
                val text = lines.joinToString("\n") { line -> language.getString(line.id, *line.args.toTypedArray()) }
                assertFalse("$reason: $text", "%" in text)
                assertTrue("$reason: $text", "Region" in text)
                assertTrue("$reason names the host", "diary.example" in text)
                assertTrue("$reason cites the survey", "September 2026" in text)
            }
        }
    }

    @Test
    fun `a sign-in reason names the system and the school year`() {
        val text = whyLines(why(WhyReason.PRIMARY), "Region", "System", null, null)
            .first()
            .let { line -> context.getString(line.id, *line.args.toTypedArray()) }
        assertTrue(text, "System" in text && "2026/27" in text)
    }

    @Test
    fun `the survey month is written out in the reader's language`() {
        assertTrue(surveyMonthText("2026-09", Locale.ENGLISH).orEmpty().startsWith("September"))
        assertTrue(surveyMonthText("2026-09", Locale.forLanguageTag("ru")).orEmpty().startsWith("сентябрь"))
        assertFalse(surveyMonthText("autumn", Locale.ENGLISH) != null)
    }

    /**
     * The legal line sits under the button, inside the same inset: below the
     * button, and not under the gesture bar — which is where a line placed
     * after the row, rather than in it, would have gone.
     */
    @Test
    fun `the legal line is drawn under the button, inside the page`() {
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    OnboardingActions(
                        label = "Continue",
                        icon = Icons.AutoMirrored.Rounded.ArrowForward,
                        onClick = {},
                        modifier = Modifier.testTag("actions"),
                        footer = { LegalAcceptanceLine(modifier = Modifier.testTag("legal")) },
                    )
                }
            }
        }

        compose.onNodeWithText("Continue").assertIsDisplayed()
        compose.onNodeWithTag("legal").assertIsDisplayed()
        val button = compose.onNodeWithText("Continue").getBoundsInRoot()
        val legal = compose.onNodeWithTag("legal").getBoundsInRoot()
        val root = compose.onRoot().getBoundsInRoot()
        assertTrue("the line starts at ${legal.top}, the button ends at ${button.bottom}", legal.top >= button.bottom)
        assertTrue("the line runs off the page", legal.bottom <= root.bottom)
    }
}
