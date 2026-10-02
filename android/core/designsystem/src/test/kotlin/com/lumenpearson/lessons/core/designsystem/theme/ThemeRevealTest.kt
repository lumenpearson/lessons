package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The circular theme reveal on «Оформление» (#229): a switch flipped again
 * while the circle is still opening must not freeze it on screen.
 */
@RunWith(RobolectricTestRunner::class)
// Native, because legacy graphics hands back no bitmap for the photograph.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThemeRevealTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a second toggle during a wipe does not photograph the wipe`() {
        compose.mainClock.autoAdvance = false
        var shots = 0
        var dark by mutableStateOf(false)
        lateinit var state: ThemeRevealState
        compose.setContent {
            val scope = rememberCoroutineScope()
            state = remember {
                ThemeRevealState(scope, capture = { shots++; ImageBitmap(2, 2) }, enabled = { true })
            }
            MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                ThemeRevealHost(state) { Box(Modifier.fillMaxSize()) }
            }
        }

        compose.runOnUiThread { state.reveal(Offset.Unspecified) { dark = true } }
        compose.mainClock.advanceTimeBy(200)
        compose.runOnUiThread { state.reveal(Offset.Unspecified) { dark = false } }

        assertEquals("the wipe on screen was photographed into a second still", 1, shots)
        compose.mainClock.advanceTimeBy(2_000)
        compose.runOnUiThread { assertFalse("the still never came down", state.revealing) }
    }

    @Test
    fun `the photograph comes down even when its animation never gets to run`() {
        val gone = CoroutineScope(Job().apply { cancel() })
        val state = ThemeRevealState(gone, capture = { ImageBitmap(2, 2) }, enabled = { true })
        var changed = false

        state.reveal(Offset.Unspecified) { changed = true }

        assertTrue(changed)
        assertFalse("a still stranded with nothing left to take it down", state.revealing)
    }
}
