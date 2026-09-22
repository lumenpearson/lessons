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
 * What a layer does to the touches of its own rows — and why the shell still
 * does not use one.
 *
 * The settings pages were drawn as a layer over the tabs, and the layer
 * swallowed pointer events so they could not reach the pager still composed
 * underneath. Every row inside it then stopped responding — the settings screen
 * shipped twice with no way into any of its six sections, and it took a user
 * with a phone to find out both times.
 *
 * These tests were written to pin that as permanent: Compose dispatched each
 * pass over the whole of one subtree before the next, so a layer that consumed
 * early enough to stop its sibling consumed early enough to cancel its own
 * children, and there was no pass that reached one and not the other. They
 * asserted the failure because there was no fix.
 *
 * **That stopped being true at compose-bom 2026.09.00.** The same modifier over
 * the same rows now lets their clicks through; bisected against the bom alone,
 * with navigation 2.10.1 and room 2.8.5 held at the new versions and only the
 * bom moved back, the old behaviour returns. So the assertions below are
 * inverted from what they were, and they now measure the new dispatch rather
 * than the old one.
 *
 * The shell is **not** going back to a layer on the strength of this. What
 * changed is unspecified gesture-dispatch ordering between two subtrees, which
 * this bump shows can change under a dependency bump and say nothing while it
 * does; the same finger on the same row would then silently stop working again,
 * exactly as it did the two times it shipped. Dropping the tabs from the
 * composition leaves nothing to block and no ordering to depend on, so that is
 * what `LessonsApp` keeps doing.
 *
 * Not covered: whether the layer still stops the *sibling* underneath. These
 * tests only ever measured the children, and that question is what the shell's
 * design makes moot.
 */
// marquee clock: this is about what is drawn *over* what — z-order, not
// text. The rows carry «Тема, цвета, чёрный фон» and shorter, each owning its
// own full-width row.
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
     * second attempt at making it harmless to the children; for two releases of
     * Compose it was not, and from compose-bom 2026.09.00 it is.
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
    fun `a swallowing layer no longer cancels the clicks of its own rows`() {
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

        // One, not zero. Through compose-bom 2026.06.01 this was zero: the row
        // was under the finger, drawn, enabled and clickable, and the click
        // never arrived. The layer consumes exactly as it always did.
        assertEquals(1, clicks)
    }

    /** And the same for a switch row, which claims its gesture differently. */
    @Test
    fun `a swallowing layer no longer cancels a switch row either`() {
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

        assertEquals(true, value)
    }
}
