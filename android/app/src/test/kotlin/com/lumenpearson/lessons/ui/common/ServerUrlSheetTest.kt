package com.lumenpearson.lessons.ui.common

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.lumenpearson.lessons.core.data.network.CleartextPolicy
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Адрес сервера», drawn, in the two builds (#202).
 *
 * Every way in to the server address is this sheet — the join screen,
 * settings, the first run's diary steps — so it is where a release build says
 * it will not use an `http://` one: before anything is sent, in a sentence
 * about https, and with «Сохранить» held until the address is one it will
 * use. Which build is which is a [CleartextPolicy]; which hosts each refuses is
 * `NetworkSecurityConfigTest`'s.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
class ServerUrlSheetTest {

    @get:Rule val compose = createComposeRule()

    private val release = CleartextPolicy { host -> host == "localhost" || host == "127.0.0.1" }
    private val debug = CleartextPolicy { true }

    private var confirmed: String? = null

    private fun show(initial: String, build: CleartextPolicy) = compose.setContent {
        LessonsTheme {
            ServerUrlSheet(initialUrl = initial, onDismiss = {}, onConfirm = { confirmed = it }, cleartext = build)
        }
    }

    @Test
    fun `a release build refuses an http address where it is typed, and says why`() {
        show("", release)

        compose.onNode(hasSetTextAction()).performTextInput("http://192.168.1.50:8000/")

        compose.onNodeWithText(REFUSAL, substring = true).assertIsDisplayed()
        compose.onNodeWithText("Сохранить").assertIsNotEnabled().performClick()
        assertNull("nothing was stored, so nothing will be sent to it", confirmed)
    }

    /** A phone that kept an http:// address from an older version reads why as soon as it opens the sheet. */
    @Test
    fun `an address kept from before is explained on opening, and an https one takes its place`() {
        show("http://192.168.1.50:8000/", release)
        compose.onNodeWithText(REFUSAL, substring = true).assertIsDisplayed()

        compose.onNode(hasSetTextAction()).performTextReplacement("https://lessons.example.com/")

        compose.onNodeWithText(REFUSAL, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Сохранить").assertIsEnabled().performClick()
        assertEquals("https://lessons.example.com/", confirmed)
    }

    @Test
    fun `a server on the phone itself is still allowed in a release build`() {
        show("http://127.0.0.1:8000", release)

        compose.onNodeWithText(REFUSAL, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("http://127.0.0.1:8000", confirmed)
    }

    @Test
    fun `a debug build takes the same LAN address without a word`() {
        show("http://192.168.1.50:8000/", debug)

        compose.onNodeWithText(REFUSAL, substring = true).assertDoesNotExist()
        compose.onNodeWithText("Сохранить").performClick()
        assertEquals("http://192.168.1.50:8000/", confirmed)
    }

    @Test
    fun `clearing the address is still allowed`() {
        // The empty field is how a mistyped address is undone; a refusal must
        // not take that away.
        show("http://192.168.1.50:8000/", release)

        compose.onNode(hasSetTextAction()).performTextReplacement("")
        compose.onNodeWithText("Сохранить").performClick()

        assertEquals("", confirmed)
    }

    private companion object {
        /** The start of `server_needs_https`, as a reader sees it. */
        const val REFUSAL = "только по защищённому адресу"
    }
}
