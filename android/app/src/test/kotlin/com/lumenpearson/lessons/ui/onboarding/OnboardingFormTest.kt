package com.lumenpearson.lessons.ui.onboarding

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiarySessionIdleDays
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.ui.diary.DiaryCredentialFields
import com.lumenpearson.lessons.ui.diary.LoginFieldTag
import com.lumenpearson.lessons.ui.diary.PasswordFieldTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The sign-in form and the summary, drawn: what a keystroke leaves in the
 * field, what a link does on a phone with no browser, and whether a warning
 * can be read without watching it crawl.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1200dp")
// Real text measurement: whether a sentence wraps is the question one of these
// tests asks, and the legacy shadows lay every string out on one line.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OnboardingFormTest {

    @get:Rule
    val compose = createComposeRule()

    private val context get() = ApplicationProvider.getApplicationContext<Context>()

    /**
     * The form trimmed the login on every keystroke. A field hands back the
     * value it is given, so a space typed at the end vanished and the next
     * letter joined the word: «par ent», which the shared vectors hold to be a
     * valid «Сетевой город» login, could only ever be typed as «parent».
     */
    @Test
    fun `a login with a space inside can be typed, and the ends are trimmed on the way out`() {
        var login by mutableStateOf("")
        var sent: String? = null
        compose.setContent {
            LessonsTheme {
                DiaryCredentialFields(
                    login = login,
                    onLoginChange = { login = it },
                    onSubmit = { typed, _ -> sent = typed },
                    loginKeyboard = androidx.compose.ui.text.input.KeyboardType.Text,
                )
            }
        }

        val field = compose.onNodeWithTag(LoginFieldTag)
        field.performTextInput("par")
        field.performTextInput(" ")
        field.performTextInput("ent")
        field.performTextInput(" ")
        assertEquals("par ent ", login)

        val password = compose.onNodeWithTag(PasswordFieldTag)
        password.performTextInput("secret")
        password.performImeAction()
        assertEquals("par ent", sent)
    }

    /**
     * The keep-alive warning is two sentences. As a row's title it was one
     * line scrolling for ever; as its subtitle it wraps. The clock is held
     * because a marquee — which is what the defect drew — never lets it idle.
     */
    @Test
    fun `the keep-alive warning wraps rather than crawling along one line`() {
        compose.mainClock.autoAdvance = false
        compose.setContent {
            LessonsTheme { KeepAliveItem(inClass = false, petersburg = true) }
        }
        repeat(4) {
            Snapshot.sendApplyNotifications()
            compose.mainClock.advanceTimeByFrame()
        }

        val days = DiarySessionIdleDays.toInt()
        val sentence = context.resources.getQuantityString(R.plurals.onboarding_summary_keepalive, days, days)
        val node = compose.onNodeWithText(sentence, substring = true, useUnmergedTree = true).fetchSemanticsNode()
        val layouts = mutableListOf<TextLayoutResult>()
        node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
        val lines = layouts.single().lineCount
        assertTrue("the warning is drawn on $lines line(s)", lines > 1)
        compose.onNodeWithText(context.getString(R.string.onboarding_summary_keepalive_title)).fetchSemanticsNode()
    }

    /**
     * «Забыли пароль?» and the Госуслуги «Открыть» dropped what
     * `openInBrowser` answered, so on a phone with nothing to open a link the
     * tap did nothing and said nothing. The handoff sheet says why; so does
     * the sign-in now.
     */
    @Test
    fun `a link nothing on the phone can open is remembered, so the page can say so`() {
        shadowOf(RuntimeEnvironment.getApplication()).checkActivities(true)
        val links = BrowserLinks(context)
        assertFalse(links.failed)

        links.open("https://dnevnik2.petersburgedu.ru")

        assertTrue("the tap that opened nothing is not dropped", links.failed)
    }
}
