package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.model.ThemeMode
import kotlin.math.sqrt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The tab losing the selection fades its disc out as it narrows, rather than
 * dropping it on the first frame (#249).
 *
 * While the disc flipped, that tab stayed as wide as its label for the length
 * of the spring with nothing behind it — its icon alone at the left of an
 * empty stretch of the bar. Asked of the pixels at the top middle of the tab:
 * two frames after the tap they are still part of a disc, and at rest they are
 * the bar's own colour. Native graphics, because the legacy engine draws
 * nothing a capture can read.
 *
 * And it fades through nothing but the bar and the disc (#254): in the light
 * theme a disc faded towards `Color.Transparent`, which is black, went grey on
 * the way out and dark on the way in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ToolbarDiscFadeTest {

    @get:Rule
    val compose = createComposeRule()

    // The selected tab's label is a `MarqueeText`, whose clock never idles once
    // a line overflows; held, and every frame here is stepped by hand.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private var selected by mutableIntStateOf(0)

    @Test
    fun `the tab losing the selection still wears its disc two frames after the tap`() {
        show(MotionSettings())
        compose.settle(frames = 60)

        selected = 1
        compose.settle(frames = 2)
        val during = discOfFirstTab()
        compose.settle(frames = 240)
        val atRest = discOfFirstTab()

        assertNotEquals("two frames after the tap the first tab was already the bar's colour", atRest, during)
    }

    @Test
    fun `with animations off the disc goes at once`() {
        show(MotionSettings(enabled = false))
        compose.settle(frames = 60)

        selected = 1
        compose.settle(frames = 3)
        val during = discOfFirstTab()
        compose.settle(frames = 240)
        val atRest = discOfFirstTab()

        assertEquals("with animations off the disc lingered", atRest, during)
    }

    @Test
    fun `in the light theme both discs fade between the bar and white, never through grey`() {
        show(MotionSettings(), ThemeMode.LIGHT)
        compose.settle(frames = 60)
        val disc = discOf(0)
        val bar = discOf(1)

        selected = 1
        val off = (1..FadeFrames).flatMap { frame ->
            compose.settle(frames = 1)
            listOf(0, 1).mapNotNull { tab ->
                val seen = discOf(tab)
                val away = distanceFromLine(seen, bar, disc)
                "frame $frame, tab $tab: $seen is ${away.toInt()} off the line".takeIf { away > Tolerance }
            }
        }

        assertTrue("a disc left the line from the bar to white: $off", off.isEmpty())
    }

    private fun show(motion: MotionSettings, theme: ThemeMode = ThemeMode.SYSTEM) {
        compose.setContent {
            LessonsTheme(themeMode = theme) {
                CompositionLocalProvider(LocalMotion provides motion) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        LessonsFloatingToolbar(
                            items = Labels.map { label ->
                                ToolbarItem(icon = Icons.Rounded.Settings, label = label) {}
                            },
                            selectedIndex = selected,
                            action = ToolbarAction(Icons.Rounded.Settings, "Settings") {},
                        )
                    }
                }
            }
        }
    }

    private fun discOfFirstTab(): Color = discOf(0)

    /** The colour a few pixels inside the top middle of a tab, clear of its icon. */
    private fun discOf(tab: Int): Color {
        val image = compose
            .onNode(
                hasContentDescription(Labels[tab]) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .captureToImage()
        val pixels = image.toPixelMap()
        return pixels[image.width / 2, DiscInsetPx]
    }

    private companion object {

        val Labels = listOf("Today", "Calendar", "Homework")

        /** Below the disc's top edge, above the icon. */
        const val DiscInsetPx = 6

        /** Longer than the colour's tween, which is 260 ms. */
        const val FadeFrames = 24

        /**
         * In steps of one 255th per channel. Grey halfway through the fade was
         * about thirty off; antialiasing and rounding are a few.
         */
        const val Tolerance = 6f

        /** How far [colour] is from the segment [from]–[to], in steps of one 255th. */
        fun distanceFromLine(colour: Color, from: Color, to: Color): Float {
            val p = floatArrayOf(colour.red, colour.green, colour.blue)
            val a = floatArrayOf(from.red, from.green, from.blue)
            val b = floatArrayOf(to.red, to.green, to.blue)
            val ab = FloatArray(3) { b[it] - a[it] }
            val length = ab.sumOf { (it * it).toDouble() }.toFloat()
            val t = (FloatArray(3) { (p[it] - a[it]) * ab[it] }.sum() / length).coerceIn(0f, 1f)
            val gap = FloatArray(3) { p[it] - (a[it] + t * ab[it]) }
            return sqrt(gap.sumOf { (it * it).toDouble() }).toFloat() * 255f
        }
    }
}
