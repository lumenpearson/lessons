package com.lumenpearson.lessons.ui.translate

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Исправления», turned sideways.
 *
 * The sheet is the only place a session of corrections exists in one piece —
 * nothing about it is stored, by design — so it is the one sheet in the app
 * whose closing on its own costs the reader their place in a list they are
 * checking. It was also the one sheet held in a plain `remember`, while every
 * other flag in the settings tree is a `rememberSaveable`.
 *
 * The second test is the reason the first is worth more than tidiness.
 * `onAcknowledge` is what forgets a finished pull request, and it runs when the
 * sheet is dismissed — a swipe, the scrim, the back gesture. A rotation is none
 * of those: it took the sheet away without telling anybody, the outcome stayed
 * `Opened`, and the next press on «Исправления» ran the effect that opens the
 * browser all over again — at a reader who had turned their phone and then gone
 * to check what they had sent.
 */
@RunWith(RobolectricTestRunner::class)
// Russian and a phone-sized screen, for the reason `ClassRowsScreenTest` gives.
@Config(qualifiers = "ru-rRU-w411dp")
class TranslationSessionRotationTest {

    @get:Rule val compose = createComposeRule()

    private val rotation = StateRestorationTester(compose)

    /** The mode is a process-wide object; a test that turns it on must turn it off. */
    @Before fun switchOn() {
        TranslationMode.enabled = true
    }

    @After fun switchOff() {
        TranslationMode.enabled = false
        TranslationMode.clear()
    }

    @Test
    fun `the session sheet is still open after a rotation`() {
        rotation.setContent {
            LessonsTheme {
                LazyColumn {
                    translationRows(
                        account = null,
                        canSignIn = false,
                        onSignIn = {},
                        submit = TranslationSubmit.Idle,
                        onSubmit = {},
                        onAcknowledge = {},
                    )
                }
            }
        }

        compose.onNodeWithText(ROW).performClick()
        compose.onNodeWithText(SHEET).assertIsDisplayed()

        rotation.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(SHEET).assertIsDisplayed()
    }

    /**
     * The browser is opened by an effect, and an effect runs again when its
     * composition is built again. Keying it on the URL is not enough — that only
     * covers recomposition — so the latch has to be something a rotation
     * restores rather than something it restarts.
     */
    @Test
    fun `a pull request already opened is not opened again by a rotation`() {
        val opened = mutableListOf<String>()
        rotation.setContent {
            LessonsTheme { OpenOnce(url = PULL_REQUEST) { opened += it } }
        }

        compose.waitForIdle()
        assertEquals(listOf(PULL_REQUEST), opened)

        rotation.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(listOf(PULL_REQUEST), opened)
    }

    private companion object {
        /** `translation_session`, and `translation_session_title` under it. */
        const val ROW = "Исправления"
        const val SHEET = "Исправления перевода"
        const val PULL_REQUEST = "https://github.com/lumenpearson/lessons/pull/70"
    }
}
