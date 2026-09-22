package com.lumenpearson.lessons.ui.settings

import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The «Размытие под шторкой» row, on a phone with no runtime shaders.
 *
 * The setting turns two things on, and only one of them is a shader. The blur
 * arrived in Android 13; the gradient wash under the status bar and behind the
 * toolbar is a `drawRect` and is drawn on everything — `progressiveBlur` says
 * so out loud, because that wash is what keeps a scrolled list from colliding
 * with the clock on the devices that get no blur at all.
 *
 * The row was disabled below Android 13 all the same, and `edgeBlur` defaults
 * to `true`. So on Android 8 to 12 — which this app supports, `minSdk` is 26 —
 * the wash was drawn on every screen while the one switch that names it read
 * «выключено» and could not be pressed.
 *
 * The sdk here is the whole test: at 34 both assertions pass against the broken
 * code, because there the switch really is live.
 */
// marquee clock: one row, one label: «Размытие под шторкой», twenty
// characters across 411 dp. The subject here is the fade at the row's edges,
// which is a modifier and not a marquee.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], qualifiers = "ru-rRU-w411dp")
class EdgeFadeRowTest {

    @get:Rule val compose = createComposeRule()

    @Test
    fun `the row shows what is stored, not what the shader can do`() {
        showStoredOn()

        compose.onNodeWithText(TITLE).assertIsOn()
    }

    @Test
    fun `the wash that is drawn can still be switched off`() {
        val written = showStoredOn()

        compose.onNodeWithText(TITLE).performClick()

        assertEquals(listOf(false), written)
    }

    /** @return what the row asked to be stored, in order. */
    private fun showStoredOn(): List<Boolean> {
        val written = mutableListOf<Boolean>()
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    EdgeFadeRow(
                        checked = true,
                        tone = accentTone(0),
                        onCheckedChange = { written += it },
                    )
                }
            }
        }
        return written
    }

    private companion object {
        /** `settings_edge_blur`, quoted so a rename shows up here as a failure. */
        const val TITLE = "Размытие под шторкой"
    }
}
