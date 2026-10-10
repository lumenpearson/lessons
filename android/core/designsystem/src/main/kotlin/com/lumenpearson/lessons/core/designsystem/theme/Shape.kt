package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The one declaration of the large-container radius, so `extraLarge` and
 * [LessonsShapeTokens.Group]/[LessonsShapeTokens.Hero] read the same number
 * instead of three independent "28.dp" literals that could drift apart —
 * which is exactly how a card ended up at 24 dp under a sheet edge at 28 dp
 * before the 2026-10-10 geometry pass. Declared before [LessonsShapes]: a
 * top-level property is initialised in file order, and a forward reference
 * here would read the backing field before its own initialiser ran.
 */
private val LargeContainerCorner: Dp = 28.dp

/**
 * The corner scale, taken from `sameerasw/essentials` `ui/theme/Shapes.kt` and
 * then checked, on 2026-10-10, against Material 3 Expressive's own scale (0, 4,
 * 8, 12, 16, 20, 28, 32, 48, full): every radius this app draws is now one of
 * four values — 4 (`Row`), 12 (`Cell`), 28 (`Group`/`Hero`/`extraLarge`) and
 * full — and each is a Material token rather than a guess.
 *
 * Essentials overrides only three roles and leaves `extraSmall` and
 * `extraLarge` at the Material defaults; both are spelled out here because
 * `extraSmall` is load-bearing in this design language — it is the corner of a
 * *row*, and rows are deliberately near-square so that the 28 dp corner of the
 * group container around them is the only large radius the eye picks up.
 */
val LessonsShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(LargeContainerCorner),
)

/**
 * Shapes that are part of the visual language but have no Material role to live
 * in, so they would otherwise be re-invented as magic numbers in every screen.
 */
object LessonsShapeTokens {

    /**
     * The container that holds a stack of rows.
     *
     * 28 dp, the same corner as [LessonsShapes.extraLarge] — the owner chose 24,
     * but every sheet and dialog in this theme already sits at 28, so a card
     * inside a sheet used to show two large radii side by side. Equal to
     * [LargeContainerCorner] rather than its own literal so the two cannot
     * disagree. The rows inside it are clipped by it rather than rounded
     * themselves — that is what makes a group read as one slab with soft ends
     * instead of a pile of cards.
     */
    val Group: RoundedCornerShape = RoundedCornerShape(LargeContainerCorner)

    /**
     * One row inside a group. 4 dp, the bottom rung of the scale — near-square,
     * because the group's own 28 dp clip is the only rounding the eye should
     * register.
     */
    val Row: RoundedCornerShape = RoundedCornerShape(4.dp)

    /**
     * The one card per screen that answers "what is happening right now".
     * Shares [LargeContainerCorner] with [Group] for the same reason.
     */
    val Hero: RoundedCornerShape = RoundedCornerShape(LargeContainerCorner)

    /**
     * A standalone tile or cell that is not a row of a group — the weekday
     * tile, the month-grid cell, an icon tile. Material's medium, which this
     * theme's own roles call `small`.
     */
    val Cell: RoundedCornerShape = RoundedCornerShape(12.dp)

    /** The circular colour tile in front of a row. */
    val Tile: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** Status chips; a fully-rounded capsule regardless of its height. */
    val Pill: RoundedCornerShape = RoundedCornerShape(percent = 50)
}

/** Side margin of every screen; the one number that sets the app's rhythm. */
val ScreenPadding: Dp = 16.dp

/** Empty background between two groups. */
val GroupSpacing: Dp = 16.dp

/**
 * Gap between two rows of a group.
 *
 * 2 dp, the `RoundedCardContainer` default: wide enough to draw a hairline of
 * the page between two rows, too narrow to break the slab apart.
 */
val GroupRowSpacing: Dp = 2.dp

/**
 * A list row's padding: Material's own list-item rhythm (12 dp top and
 * bottom, 16 at the start and end), named once so every row of a group reads
 * it instead of each re-declaring its own answer to "how tall is one row".
 */
val RowPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 12.dp)

/**
 * A hand-built row's floor, not its fixed height, so it grows with the system
 * font size instead of clipping it, and is always a touch target past
 * Material's 48 dp. 56 dp, Material's one-line list item.
 *
 * Read by the rows that lay themselves out — `GroupRow`, the slider and the
 * segmented items — and deliberately not by the ones built on `ListItem`, which
 * floors itself by line count (56, 72 or 88 dp). A minimum handed to `ListItem`
 * from outside replaces that figure rather than adding to it, so this one on a
 * two-line row drew it at 64 dp instead of 72.
 */
val RowMinHeight: Dp = 56.dp

/** Gap between a row's leading tile and its text, Material's own list-item measure. */
val RowLeadingGap: Dp = 12.dp

/** Height of a full-width pill button — Material's own Medium button. */
val PillButtonHeight: Dp = 56.dp

/** Inner padding of a card that holds text rather than rows: a sheet's notes, and the hero. */
val CardPadding: Dp = 20.dp

/** Gap between the items of a row or a chip strip — today's most common `spacedBy`. */
val InlineGap: Dp = 8.dp

/**
 * The radius a block should have to sit concentrically inside [outer].
 *
 * Two nested rounded rectangles only look right when the gap between their
 * curves is the same all the way round, and that happens for exactly one pair:
 * the inner radius plus the padding equals the outer one. Any other pair leaves
 * the gap wider at the corner than along the edge — which reads as a wonky
 * corner rather than as a wrong radius, so it is easy to see and hard to name.
 *
 * The arithmetic rather than the [Shape], for the two places that cannot use a
 * shape: a Glance widget, whose `cornerRadius` takes a [Dp] and nothing else,
 * and any radius that has to be *declared* rather than measured. [ConcentricShape]
 * is the same rule applied at draw time, for the case where the inner radius is
 * a percentage and so is not known until the box has a size.
 *
 * @param minimum a floor, because a block inset by more than the outer radius
 *   would otherwise come out square. Square is a legitimate answer — it is what
 *   a rectangle inset past the curve really should be — but a *slightly* square
 *   block beside rounded siblings reads as a mistake, so callers that have
 *   siblings pass a floor and the ones that do not leave it at zero.
 */
fun concentricCorner(outer: Dp, inset: Dp, minimum: Dp = 0.dp): Dp =
    (outer - inset).coerceAtLeast(minimum)

/**
 * A tray whose corner is concentric with the corners of what it holds.
 *
 * Two nested rounded rectangles only look right when the gap between their
 * curves is the same all the way round, and that happens for exactly one outer
 * radius: the inner radius plus the padding between them. Any other number
 * leaves the gap wider at the corner than along the edge — which reads as a
 * wonky corner rather than as a wrong radius, so it is easy to see and hard to
 * name. The segmented picker had it: a 24 dp tray token around Material's
 * connected buttons with 4 dp of padding, three numbers chosen in three places.
 *
 * It is a [Shape] rather than [concentricCorner] because the inner radius is not
 * always a length. Material's connected button shapes are
 * expressed as a percentage of the button's own height, so the answer is only
 * known once the tray has been measured, and [createOutline] is where that
 * happens. The same reason makes it testable: it is a function of a size and a
 * density and nothing else.
 *
 * @param inner the shape of the things inside. A shape that has no corner size
 *   to read — Material's morphing polygons, for one — falls back to [fallback].
 * @param inset the padding between [inner] and this, on one side.
 */
@Immutable
class ConcentricShape(
    private val inner: Shape,
    private val inset: Dp,
    private val fallback: Dp = 0.dp,
) : Shape {

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val gap = with(density) { inset.toPx() }
        // What the inner shape is measured against is the box it actually gets,
        // which is this one minus the padding on both sides. It matters because
        // a percentage corner reads its own height: asking the button's shape
        // about the tray's size would round it by four more pixels than the
        // button is rounded by, and the gap would be wrong in the direction
        // this class exists to fix.
        val innerSize = Size(
            width = (size.width - gap * 2f).coerceAtLeast(0f),
            height = (size.height - gap * 2f).coerceAtLeast(0f),
        )
        val innerRadius = (inner as? CornerBasedShape)
            ?.topStart
            ?.toPx(innerSize, density)
            ?: with(density) { fallback.toPx() }
        val radius = (innerRadius + gap)
            // A radius larger than half the shorter side is not a rounder
            // rectangle, it is a malformed outline: the two corners of one edge
            // would overlap. A very short tray simply becomes a capsule.
            .coerceAtMost(minOf(size.width, size.height) / 2f)
        return Outline.Rounded(RoundRect(size.toRect(), CornerRadius(radius)))
    }
}
