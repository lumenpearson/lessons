package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.designsystem.theme.springSpec

/**
 * The pill the selection slides on, from the tab it leaves to the tab it goes
 * to (#258).
 *
 * It sets off from where it was — the old tab, or wherever an interrupted
 * slide had got to — and heads for the new tab's bounds as they are on each
 * frame, so it arrives on a tab whose label is still opening and lands exactly
 * where that tab's own disc then takes over.
 *
 * [follow] runs in composition, because the frame a selection changes on has
 * to know at once that a slide has begun — before the effect that runs it has
 * started — or the new tab wears its own disc for a frame before the pill has
 * moved. What it writes is snapshot state, so that drawing which read it is
 * redrawn: while these were plain fields, the tabs learned of the slide from
 * their recomposition and the row's pill, whose drawing nothing invalidated,
 * only once the effect had moved it — and for those frames each tab was inked
 * the bar's colour over a pill that was not there. It reads without observing,
 * so writing what it has just read is not a write back into its own scope.
 */
internal class SelectionPill {

    private var target by mutableStateOf<String?>(null)
    private var from by mutableStateOf<Rect?>(null)
    private var started by mutableStateOf(false)
    private val progress = Animatable(1f)
    private val arrived = derivedStateOf { progress.value >= 1f }

    /** Whether the pill is on its way; read from composition and from drawing. */
    val sliding: Boolean get() = from != null && (!started || !arrived.value)

    fun follow(selected: String?, slide: Boolean, bounds: Map<String, Rect>) {
        val (was, setOff) = Snapshot.withoutReadObservation { target to now(bounds) }
        if (selected == was) return
        val arriving = selected?.let(bounds::get)
        from = if (slide && was != null && arriving != null) setOff else null
        target = selected
        started = false
    }

    suspend fun run(spec: AnimationSpec<Float>) {
        if (from == null) {
            progress.snapTo(1f)
            return
        }
        progress.snapTo(0f)
        started = true
        progress.animateTo(1f, spec)
        from = null
    }

    /** Where the pill is, in the row's coordinates. */
    fun now(bounds: Map<String, Rect>): Rect? {
        val to = target?.let(bounds::get)
        val start = from
        return when {
            start == null -> to
            to == null -> start
            else -> lerp(start, to, if (started) progress.value else 0f)
        }
    }

    /**
     * The pill drawn behind the row while it slides. Last in the row's chain,
     * so it draws in the coordinates the tabs are placed in, and behind them:
     * while it slides, no tab draws a disc of its own.
     */
    fun draw(bounds: Map<String, Rect>, color: Color): Modifier = Modifier.drawBehind {
        if (!sliding) return@drawBehind
        val at = now(bounds) ?: return@drawBehind
        drawRoundRect(color = color, topLeft = at.topLeft, size = at.size, cornerRadius = CornerRadius(at.height / 2))
    }

    /** Whether a tab wears its own disc: the selected one, unless the pill is sliding to it. */
    fun wearsDisc(selected: Boolean): Boolean = selected && !sliding

    /** Where the sliding pill is, in the coordinates of the tab labelled [label]; null at rest. */
    fun over(label: String, bounds: Map<String, Rect>): Rect? {
        val tab = bounds[label]
        val at = if (sliding) now(bounds) else null
        return if (tab == null || at == null) null else at.translate(-tab.left, -tab.top)
    }
}

/** Keeps where a tab was placed, writing only what changed, so an unchanged place redraws nothing. */
internal fun MutableMap<String, Rect>.record(label: String, at: Rect) {
    if (this[label] != at) this[label] = at
}

/**
 * The spring the selection's pill slides on, as a fraction of the way.
 *
 * Without a bounce, and stiffer than the labels' [toolbarSpring]: it has to
 * reach the arriving tab before that tab's label has finished opening, and then
 * ride the label's own spring for the rest. At [toolbarSizeSpring]'s pace it
 * trailed the label, whose last letter stood off the end of the pill for a
 * tenth of a second before the tab's own disc took over with a step.
 */
internal fun MotionSettings.pillSpring() = springSpec(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = PillStiffness,
    // A thousandth of the way, not the default hundredth: the spring stops
    // there and snaps to its end, and on a slide two tabs long a hundredth is
    // three pixels the pill jumped by on its last frame.
    visibilityThreshold = PillThreshold,
)

/** See [pillSpring]. */
private const val PillThreshold = 0.001f

/** Between `StiffnessMediumLow` and `StiffnessMedium`; see [pillSpring]. */
private const val PillStiffness = 700f

/**
 * Draws the content a second time, tinted [content], where the sliding [pill]
 * covers it — which, with the pill drawn white beneath, is exactly the
 * selected tab, because the icon and the label are one colour each. One layer
 * and one tint rather than a second composition of the row, so the label's
 * marquee is the same marquee on both sides of the pill's edge.
 *
 * The tint is all-or-nothing: a tab's red badge would take [content] under the
 * pill while it passes. No tab carries a badge today.
 */
internal fun Modifier.inkedUnder(pill: () -> Rect?, content: Color): Modifier = drawWithContent {
    drawContent()
    val at = pill() ?: return@drawWithContent
    if (!at.overlaps(Rect(Offset.Zero, size))) return@drawWithContent
    val shape = Path().apply { addRoundRect(RoundRect(at, CornerRadius(at.height / 2))) }
    clipPath(shape) {
        drawIntoCanvas { canvas ->
            val tint = Paint().apply { colorFilter = ColorFilter.tint(content, BlendMode.SrcIn) }
            canvas.saveLayer(Rect(Offset.Zero, size), tint)
            this@drawWithContent.drawContent()
            canvas.restore()
        }
    }
}
