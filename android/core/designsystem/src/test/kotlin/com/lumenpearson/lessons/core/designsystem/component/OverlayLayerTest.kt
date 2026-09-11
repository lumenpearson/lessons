package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Why a layer cannot block the touches of what is under it.
 *
 * The settings pages were drawn as a layer over the tabs, and the layer
 * swallowed pointer events so they could not reach the pager still composed
 * underneath. Every row inside it then stopped responding — the settings screen
 * shipped twice with no way into any of its six sections, and it took a user
 * with a phone to find out both times.
 *
 * These tests pin the reason, and they assert the failure rather than a fix:
 * there is no fix. Compose dispatches each pass over the whole of one subtree
 * before the next subtree, so a layer that consumes early enough to stop its
 * sibling consumes early enough to cancel its own children, and one that waits
 * until the children are safe has already let the sibling through. There is no
 * pass that reaches the sibling and not the children.
 *
 * Which is why the shell does not use a layer for this at all: it drops the tabs
 * from the composition once a settings page covers them. Nothing is left to
 * block.
 */
@RunWith(RobolectricTestRunner::class)
class OverlayLayerTest {

    @get:Rule
    val compose = createComposeRule()

    /**
     * The modifier as it was shipped, kept here and nowhere else.
     *
     * It leaves the first press alone — `Modifier.clickable` claims the release,
     * not the press — and swallows everything after it, which is what was meant
     * to stop a drag reaching a list underneath. Leaving the press alone was the
     * second attempt at making it harmless to the children; these tests are what
     * shows that it is not, and that the whole approach is a dead end.
     */
    private fun Modifier.swallowGestures(): Modifier = pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Main)
                event.changes.forEach { change -> if (!change.isConsumed) change.consume() }
                if (event.changes.none { it.pressed }) break
            }
        }
    }

    @Test
    fun `a swallowing layer cancels the clicks of its own rows`() {
        var clicks = 0
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swallowGestures(),
                    ) {
                        RoundedCardContainer {
                            GroupLinkItem(
                                title = "Оформление",
                                subtitle = "Тема, цвета, чёрный фон",
                                icon = Icons.Rounded.Palette,
                                tone = accentTone(0),
                                onClick = { clicks++ },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Оформление").performClick()

        // Zero, not one. The row is under the finger, drawn, enabled and
        // clickable, and the click never arrives.
        assertEquals(0, clicks)
    }

    /** And the same for a switch row, which claims its gesture differently. */
    @Test
    fun `a swallowing layer cancels a switch row too`() {
        var value = false
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swallowGestures(),
                    ) {
                        RoundedCardContainer {
                            GroupSwitchItem(
                                title = "Чёрный фон",
                                icon = Icons.Rounded.Palette,
                                tone = accentTone(0),
                                checked = value,
                                onCheckedChange = { value = it },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Чёрный фон").performClick()

        assertEquals(false, value)
    }
}
