package com.lumenpearson.lessons.core.designsystem.theme

import android.graphics.Bitmap
import android.provider.Settings
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.hypot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Switching the theme, as a circle opening from the switch that did it.
 *
 * The effect Telegram is known for, and the reason it works there is that it
 * answers a question the instant cut cannot: *what did I just change?* A whole
 * screen swapping colour between one frame and the next is a glitch; the same
 * swap arriving as a wavefront from under your finger is an answer.
 *
 * ### Why it is a photograph
 *
 * There is only one composition, and after the setting is written it is already
 * the new theme — so the old one cannot be re-drawn from state. What is animated
 * is therefore a still of the previous frame, laid over the live tree and wiped
 * away in a growing circle. The order matters and is the whole trick:
 *
 *  1. take the picture, while the old theme is still on screen;
 *  2. write the setting, so the tree under the picture is the new theme;
 *  3. open a hole in the picture from the point that was touched.
 *
 * Which is why this is driven from the call site rather than by watching the
 * settings flow. By the time an observer of the flow hears about the change, the
 * only thing left to photograph is the answer.
 */
@Stable
class ThemeRevealState internal constructor(
    private val scope: CoroutineScope,
    private val capture: () -> ImageBitmap?,
    private val enabled: () -> Boolean,
) {

    /**
     * The wipe being drawn, or null when the screen is simply itself.
     *
     * Replaced whole rather than mutated, which is what makes a second tap
     * during the first one safe: the new wipe carries its own picture *and* its
     * own clock, both starting together. The previous arrangement kept one
     * clock on the state and reset it from the coroutine that drove it, so a
     * tap arriving mid-flight put a fresh photograph on screen with the old
     * animation's progress still under it — a hole that was already half the
     * screen wide opening onto a picture taken a moment ago.
     */
    internal var wipe by mutableStateOf<Wipe?>(null)
        private set

    private var running: Job? = null

    /**
     * Photographs the screen, applies [change], and wipes the photograph away in
     * a circle growing from [origin].
     *
     * Safe to call for a change that turns out not to alter the theme at all —
     * the animation is then a circle opening onto an identical picture, which
     * costs one frame of work and looks like nothing happened. Safe to call
     * again while one is still running, which is the case that matters: somebody
     * flipping between light and dark to compare them gets a wave per tap, each
     * from its own switch, and never a frozen one.
     *
     * @param origin in the root composition's coordinates, which is what
     *   [androidx.compose.ui.layout.LayoutCoordinates.positionInRoot] gives.
     *   Unspecified falls back to the middle of the screen.
     */
    fun reveal(origin: Offset, change: () -> Unit) {
        if (!enabled()) {
            change()
            return
        }
        val shot = capture()
        if (shot == null) {
            change()
            return
        }

        val wipe = Wipe(shot, origin)
        // Cancelled before the new one is published, so the old animation cannot
        // land a frame — or its own cleanup — on top of it.
        running?.cancel()
        this.wipe = wipe
        change()

        running = scope.launch {
            try {
                wipe.progress.animateTo(1f, tween(RevealMillis, easing = FastOutLinearInEasing))
            } finally {
                // Only if it is still ours: a wipe that was superseded has
                // already been dropped, and clearing here would take the
                // replacement down with it.
                if (this@ThemeRevealState.wipe === wipe) this@ThemeRevealState.wipe = null
            }
        }
    }
}

/**
 * One pass of the circle: a picture, a centre, and a clock of its own.
 *
 * Stable rather than immutable: the clock does change, but only through snapshot
 * state that the draw reads directly, which is the contract stability asks for.
 */
@Stable
internal class Wipe(
    val shot: ImageBitmap,
    val origin: Offset,
) {
    val progress = Animatable(0f)
}

/**
 * How long the circle takes to leave the screen.
 *
 * Long enough to be read as a movement rather than a flicker, short enough that
 * somebody flipping the switch twice does not have to wait for it.
 */
private const val RevealMillis = 560

/**
 * How far past the furthest corner the wavefront travels before it is done.
 *
 * Without it the circle ends exactly on the corner, and because the curve of a
 * circle is slowest to cross a corner, the last thing anybody sees is an arc of
 * the old theme sitting in the corner of the screen for a tenth of a second. It
 * looks like the wave ran out of screen rather than out of time. A quarter more
 * radius puts the visible edge off the display well before the clock stops, and
 * the tail of the animation is then a wave crossing ground nobody can see —
 * which is the only place a wave should stop.
 *
 * Paired with an easing that ends at full speed rather than gliding to a halt,
 * for the same reason: a wavefront that decelerates on its way out reads as one
 * that got stuck.
 */
private const val Overshoot = 1.25f

/**
 * How much the photograph is scaled down before it is taken.
 *
 * A full-resolution screenshot of a modern phone is about ten megabytes, held
 * for half a second on the main thread's heap. Half in each direction is a
 * quarter of that, and the difference is invisible under a wavefront moving at
 * two thousand pixels a second.
 */
private const val SnapshotScale = 0.5f

/** The reveal handle in scope, or a no-op one outside [ThemeRevealHost]. */
val LocalThemeReveal = staticCompositionLocalOf<ThemeRevealState?> { null }

/**
 * Hosts the reveal overlay. Wrap the whole app in it, once.
 *
 * The overlay is a sibling drawn after [content], never a parent of it: it must
 * not be able to take a touch, and a `Canvas` with no pointer input cannot. That
 * is not a hypothetical caution in this codebase — a full-screen layer over the
 * settings pages once ate every tap on every row for two releases.
 */
@Composable
fun ThemeRevealHost(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val state = remember(view) {
        ThemeRevealState(
            scope = scope,
            capture = { view.photograph(SnapshotScale) },
            // A user who has turned animations off system-wide has asked for
            // exactly this not to happen.
            enabled = { context.animatorsAreOn() },
        )
    }

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalThemeReveal provides state) {
            content()
        }
        RevealOverlay(state)
    }
}

/**
 * The photograph, and the hole opening in it.
 *
 * Its own composable so that the host's body reads none of the reveal state:
 * read there, taking the picture and dropping it again would recompose the whole
 * app twice for an animation that is a single draw layer.
 */
@Composable
private fun RevealOverlay(state: ThemeRevealState) {
    val wipe = state.wipe ?: return
    val shot = wipe.shot

    Canvas(modifier = Modifier.fillMaxSize()) {
        val centre = if (wipe.origin.isSpecified) {
            wipe.origin
        } else {
            Offset(size.width / 2f, size.height / 2f)
        }
        val hole = Path().apply {
            addOval(
                Rect(
                    center = centre,
                    radius = farthestCorner(centre, size) * Overshoot * wipe.progress.value,
                ),
            )
        }
        // Difference: everything *outside* the circle is still the old theme. The
        // new one is already drawn underneath, so the circle is a hole rather
        // than a second copy of the screen.
        clipPath(hole, clipOp = ClipOp.Difference) {
            drawImage(
                image = shot,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(shot.width, shot.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            )
        }
    }
}

/**
 * Anchors a reveal to the control that triggers it.
 *
 * A control that changes the theme wraps itself in this and calls the `reveal`
 * it is handed instead of the setter directly. The origin is the middle of
 * whatever is inside, which is what makes the circle look like it came out from
 * under the finger.
 */
@Composable
fun ThemeRevealAnchor(
    modifier: Modifier = Modifier,
    content: @Composable (reveal: (change: () -> Unit) -> Unit) -> Unit,
) {
    val host = LocalThemeReveal.current
    var centre by remember { mutableStateOf(Offset.Unspecified) }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            val corner = coordinates.positionInRoot()
            centre = Offset(
                x = corner.x + coordinates.size.width / 2f,
                y = corner.y + coordinates.size.height / 2f,
            )
        },
    ) {
        content { change ->
            if (host == null) change() else host.reveal(centre, change)
        }
    }
}

/** Distance from [from] to the furthest corner of a box of [size]. */
private fun farthestCorner(from: Offset, size: Size): Float = maxOf(
    hypot(from.x, from.y),
    hypot(size.width - from.x, from.y),
    hypot(from.x, size.height - from.y),
    hypot(size.width - from.x, size.height - from.y),
)

/**
 * A still of the view as it is drawn right now, at [scale].
 *
 * A software re-draw rather than a `PixelCopy` of the window: `PixelCopy` is
 * asynchronous, and the whole effect depends on the picture being taken *before*
 * the setting is written. One frame of latency there and the photograph is of
 * the new theme, which animates a circle opening onto itself.
 *
 * The cost is that render effects do not survive: the app's two blur shaders are
 * hardware-only, so the still has crisp edges where the live tree has soft ones.
 * Under a wavefront that crosses the screen in half a second, nobody has ever
 * seen it.
 *
 * Null on any failure. A theme switch must still work on a device where this
 * does not.
 */
private fun View.photograph(scale: Float): ImageBitmap? = runCatching {
    val targetWidth = (width * scale).toInt()
    val targetHeight = (height * scale).toInt()
    if (targetWidth <= 0 || targetHeight <= 0) return null

    // The platform factory rather than the androidx extension: this module does
    // not depend on androidx.core, and a one-line convenience is not worth a
    // dependency that only the release variant would go looking for.
    val bitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    canvas.scale(scale, scale)
    draw(canvas)
    bitmap.asImageBitmap()
}.getOrNull()

/** False when the user has switched animations off in developer or accessibility settings. */
private fun android.content.Context.animatorsAreOn(): Boolean = runCatching {
    Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
}.getOrDefault(true)
