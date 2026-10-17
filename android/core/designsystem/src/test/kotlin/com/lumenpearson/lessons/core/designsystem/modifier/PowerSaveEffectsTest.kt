package com.lumenpearson.lessons.core.designsystem.modifier

import android.content.Context
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.ScrollBlurSettings
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealHost
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealState
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.rememberThemeRevealState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Every heavy effect switches itself off under battery saver and comes back
 * when it ends (#476).
 *
 * Only the blur under the status bar used to ask. «Волна» is a runtime shader
 * over the whole window, the scroll blur another over every list, and the
 * theme reveal photographs the window and wipes it — and all three went on
 * while Android was asking apps to save power.
 *
 * Battery saver comes from [LocalPowerSaveMode], provided by hand: that is the
 * fake source. Every effect is asked for — its own switch on — throughout, so
 * what changes is battery saver alone. "Draws nothing" is asked of the layout
 * node: each of these effects is a graphics layer, and with the effect off the
 * modifier is `Modifier` and there is no layer on the node to draw anything.
 */
@RunWith(RobolectricTestRunner::class)
// Legacy for the shaders: native graphics compiles AGSL for real, with a Skia
// that refuses `content.eval` ("cannot swizzle value of type 'shader'"), so
// under it no shader effect could be shown to come back at all. Legacy never
// compiles the source, which is all these tests need — they ask whether the
// layer is there, not what it draws.
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class PowerSaveEffectsTest {

    @get:Rule
    val compose = createComposeRule()

    private var saving by mutableStateOf(true)

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    /** The reveal also honours the system's animator scale; that is not what is asked here. */
    @Before
    fun animatorsOn() {
        Settings.Global.putFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
    }

    private fun batterySaver(on: Boolean) {
        saving = on
        Snapshot.sendApplyNotifications()
        compose.waitForIdle()
    }

    /** Whether the node tagged [Tag] carries a graphics layer, which is how each effect draws. */
    private fun drawsALayer(): Boolean = compose.onNodeWithTag(Tag).fetchSemanticsNode()
        .layoutInfo.getModifierInfo().any { it.modifier.javaClass == LayerElement }

    @Test
    fun `the ripple draws nothing under battery saver and returns after`() {
        val ripple = LiquidRippleState()
        compose.setContent {
            CompositionLocalProvider(LocalPowerSaveMode provides saving) {
                Box(Modifier.size(100.dp).testTag(Tag).liquidRipple(ripple, enabled = true))
            }
        }

        compose.runOnIdle { ripple.fire(Offset.Unspecified) }
        assertFalse("the wave is drawn under battery saver", drawsALayer())

        batterySaver(false)
        assertTrue("the wave did not come back when battery saver ended", drawsALayer())
    }

    @Test
    fun `the scroll blur draws nothing under battery saver and returns after`() {
        compose.setContent {
            CompositionLocalProvider(
                LocalPowerSaveMode provides saving,
                LocalScrollBlur provides ScrollBlurSettings(enabled = true),
            ) {
                Box(Modifier.size(100.dp).testTag(Tag).appScrollMotionBlur(rememberScrollState()))
            }
        }

        assertFalse("the scroll blur is drawn under battery saver", drawsALayer())

        batterySaver(false)
        assertTrue("the scroll blur did not come back when battery saver ended", drawsALayer())
    }

    /** The one effect that already listened, now listening to the same source as the rest. */
    @Test
    fun `the edge blur draws nothing under battery saver and returns after`() {
        compose.setContent {
            CompositionLocalProvider(LocalPowerSaveMode provides saving) {
                Box(
                    Modifier.size(100.dp).testTag(Tag).progressiveBlur(
                        blurRadius = StatusBarBlurRadius,
                        topHeight = 20f,
                        showGradientOverlay = false,
                    ),
                )
            }
        }

        assertFalse("the edge blur is drawn under battery saver", drawsALayer())

        batterySaver(false)
        assertTrue("the edge blur did not come back when battery saver ended", drawsALayer())
    }

    /** A theme change under battery saver is instant: no photograph, no wipe. */
    @Test
    // Native, because legacy graphics hands back no bitmap for the photograph,
    // and the reveal does nothing without one.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `a theme change is instant under battery saver and wipes again after`() {
        lateinit var state: ThemeRevealState
        compose.setContent {
            CompositionLocalProvider(LocalPowerSaveMode provides saving) {
                state = rememberThemeRevealState(enabled = true)
                MaterialTheme {
                    ThemeRevealHost(state) { Box(Modifier.fillMaxSize()) }
                }
            }
        }
        var changes = 0

        compose.runOnIdle {
            state.reveal(Offset.Unspecified) { changes++ }
            assertFalse("a photograph went up under battery saver", state.revealing)
        }
        assertEquals("the change itself was not applied", 1, changes)

        batterySaver(false)
        compose.runOnIdle {
            state.reveal(Offset.Unspecified) { changes++ }
            assertTrue("the wipe did not come back when battery saver ended", state.revealing)
        }
        assertEquals(2, changes)
    }

    private companion object {
        const val Tag = "effect"

        /** What `Modifier.graphicsLayer` puts on a node; one element, so the modifier is it. */
        val LayerElement: Class<*> = Modifier.graphicsLayer {}.javaClass
    }
}
