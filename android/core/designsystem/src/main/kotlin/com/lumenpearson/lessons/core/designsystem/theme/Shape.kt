package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The corner scale, taken from `sameerasw/essentials` `ui/theme/Shapes.kt`.
 *
 * Essentials overrides only three roles and leaves `extraSmall` and
 * `extraLarge` at the Material defaults; both are spelled out here because
 * `extraSmall` is load-bearing in this design language — it is the corner of a
 * *row*, and rows are deliberately near-square so that the 24 dp corner of the
 * group container around them is the only large radius the eye picks up.
 */
val LessonsShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Shapes that are part of the visual language but have no Material role to live
 * in, so they would otherwise be re-invented as magic numbers in every screen.
 */
object LessonsShapeTokens {

    /**
     * The container that holds a stack of rows.
     *
     * 24 dp is `RoundedCardContainer`'s default in Essentials, and the rows
     * inside it are clipped by it rather than rounded themselves — that is what
     * makes a group read as one slab with soft ends instead of a pile of cards.
     */
    val Group: RoundedCornerShape = RoundedCornerShape(24.dp)

    /** One row inside a group. Near-square; the group's clip does the rounding. */
    val Row: RoundedCornerShape = RoundedCornerShape(4.dp)

    /** The one card per screen that answers "what is happening right now". */
    val Hero: RoundedCornerShape = RoundedCornerShape(24.dp)

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
