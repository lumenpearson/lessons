package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
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
import kotlin.math.pow
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
 *
 * And the icon never fades into it (#258). A selected tab is an unselected
 * one inverted — the bar's colour on white against white on the bar's colour —
 * so a cross-fade of both colours at once met in the middle, icon and disc
 * alike, and each tab's icon went missing for a frame or two. One pill slides
 * from the old tab to the new instead, and every tab is drawn the selected way
 * only where the pill covers it — the tabs between the two included.
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

    @Test
    fun `both icons keep their contrast through the change, in the light theme`() {
        assertIconsKeepContrast(ThemeMode.LIGHT)
    }

    @Test
    fun `both icons keep their contrast through the change, in the dark theme`() {
        assertIconsKeepContrast(ThemeMode.DARK)
    }

    @Test
    fun `the disc leaves towards the tab the selection goes to`() {
        show(MotionSettings())
        compose.settle(frames = 60)

        selected = 1
        compose.settle(frames = MidFrames)
        val (near, far) = discAcross(0)

        assertNotEquals("the side of «Today» away from «Calendar» still wore the disc", far, near)
        compose.settle(frames = 240)
        assertEquals("the side of «Today» towards «Calendar» had lost the disc already", discOf(1), near)
    }

    @Test
    fun `and the other way, towards the start of the row`() {
        selected = 1
        show(MotionSettings())
        compose.settle(frames = 60)

        selected = 0
        compose.settle(frames = MidFrames)
        val (near, far) = discAcross(1, towardsEnd = false)

        assertNotEquals("the side of «Calendar» away from «Today» still wore the disc", far, near)
        compose.settle(frames = 240)
        assertEquals("the side of «Calendar» towards «Today» had lost the disc already", discOf(0), near)
    }

    @Test
    fun `the pill passes over the tabs between`() {
        show(MotionSettings())
        compose.settle(frames = 60)
        val bar = discOf(1)

        selected = 2
        val covered = (1..FadeFrames).any {
            compose.settle(frames = 1)
            discOf(1) != bar
        }
        compose.settle(frames = 240)

        assertTrue("«Calendar», between «Today» and «Homework», was never under the pill", covered)
        assertEquals("«Calendar» kept something of the pill after it had passed", bar, discOf(1))
    }

    /**
     * The carried tab is glass (#259). Carried off the end of the row it hangs
     * over the bar alone — the tab it displaced has moved into its slot — and
     * its body there is the carried colour laid over the bar's, not the
     * carried colour alone, which hid every tab that slid beneath it.
     */
    @Test
    fun `the bar shows through the carried tab`() {
        lateinit var scheme: ColorScheme
        compose.setContent {
            LessonsTheme(themeMode = ThemeMode.LIGHT) {
                scheme = MaterialTheme.colorScheme
                Box(modifier = Modifier.fillMaxSize()) {
                    LessonsFloatingToolbar(
                        items = Labels.map { label -> ToolbarItem(icon = Icons.Rounded.Settings, label = label) {} },
                        selectedIndex = 0,
                        reorderable = true,
                        reordering = true,
                    )
                }
            }
        }
        compose.settle(frames = 120)

        compose.holdFarRight(Labels[1])
        val body = discOf(1)
        val bar = scheme.primary
        val opaque = scheme.primaryContainer
        compose.lift(Labels[1])

        assertTrue(
            "the carried tab's body was its colour alone, $body, with nothing beneath showing",
            distance(body, opaque) > Tolerance,
        )
        assertTrue(
            "the carried tab's body, $body, was not its colour over the bar's",
            distanceFromLine(body, bar, opaque) <= Tolerance,
        )
    }

    /**
     * The top band of a tab near the side the selection is going to, and near
     * the side it is leaving: the first still the disc, the second the bar.
     */
    private fun discAcross(tab: Int, towardsEnd: Boolean = true): Pair<Color, Color> {
        val image = compose
            .onNode(
                hasContentDescription(Labels[tab]) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
            )
            .captureToImage()
        val pixels = image.toPixelMap()
        val end = pixels[(image.width * NearEnd).toInt(), DiscInsetPx]
        val start = pixels[(image.width * (1 - NearEnd)).toInt(), DiscInsetPx]
        return if (towardsEnd) end to start else start to end
    }

    private fun assertIconsKeepContrast(theme: ThemeMode) {
        show(MotionSettings(), theme)
        compose.settle(frames = 60)

        selected = 1
        val faded = (1..FadeFrames).flatMap { frame ->
            compose.settle(frames = 1)
            listOf(0, 1).mapNotNull { tab ->
                val contrast = contrastInIcon(tab)
                "frame $frame, tab $tab: ${"%.2f".format(contrast)}".takeIf { contrast < IconContrast }
            }
        }

        assertTrue("an icon faded into what is behind it: $faded", faded.isEmpty())
    }

    /**
     * The contrast between the lightest and the darkest pixel of a tab's icon
     * box. An icon drawn in the colour under it leaves the box one colour, and
     * the ratio falls to 1.
     */
    private fun contrastInIcon(tab: Int): Float {
        val pixels = compose
            .onNode(
                hasContentDescription(Labels[tab]) and
                    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image),
                useUnmergedTree = true,
            )
            .captureToImage()
            .toPixelMap()
        val luminances = (0 until pixels.width).flatMap { x ->
            (0 until pixels.height).map { y -> luminance(pixels[x, y]) }
        }
        return (luminances.max() + 0.05f) / (luminances.min() + 0.05f)
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

        /** About halfway: the pill has left the far side of the tab and not yet the near one. */
        const val MidFrames = 4

        /** How far along a tab its sides are sampled, clear of the round ends. */
        const val NearEnd = 0.85f

        /** Longer than the colour's tween, which is 260 ms. */
        const val FadeFrames = 24

        /**
         * In steps of one 255th per channel. Grey halfway through the fade was
         * about thirty off; antialiasing and rounding are a few.
         */
        const val Tolerance = 6f

        /**
         * The least an icon may stand out from its box. White on the light
         * theme's bar is about six; the cross-fade fell to about one.
         */
        const val IconContrast = 2.5f

        /** WCAG's relative luminance. */
        fun luminance(colour: Color): Float {
            fun linear(channel: Float) =
                if (channel <= 0.04045f) channel / 12.92f else ((channel + 0.055f) / 1.055f).pow(2.4f)
            return 0.2126f * linear(colour.red) + 0.7152f * linear(colour.green) + 0.0722f * linear(colour.blue)
        }

        /** How far apart two colours are, in steps of one 255th. */
        fun distance(one: Color, other: Color): Float {
            val gap = floatArrayOf(one.red - other.red, one.green - other.green, one.blue - other.blue)
            return sqrt(gap.sumOf { (it * it).toDouble() }).toFloat() * 255f
        }

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
