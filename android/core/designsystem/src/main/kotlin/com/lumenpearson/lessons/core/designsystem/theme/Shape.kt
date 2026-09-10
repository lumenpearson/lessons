package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The corner scale.
 *
 * Bigger than the Material baseline on purpose: a school diary is read in
 * two-second glances between classes, and generous corners are what make the
 * grouped cards separate at a glance instead of needing dividers.
 */
val LessonsShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Shapes that are part of the visual language but have no Material role to live
 * in, so they would otherwise be re-invented as magic numbers in every screen.
 */
object LessonsShapeTokens {

    /** The container that holds a stack of rows. */
    val Group: RoundedCornerShape = RoundedCornerShape(28.dp)

    /** One row inside a group; smaller than [Group] so it nests visibly. */
    val Row: RoundedCornerShape = RoundedCornerShape(20.dp)

    /** The one card per screen that answers "what is happening right now". */
    val Hero: RoundedCornerShape = RoundedCornerShape(28.dp)

    /** The circular colour tile in front of a row. */
    val Tile: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** Status chips; a fully-rounded capsule regardless of its height. */
    val Pill: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** The floating navigation bar. */
    val Floating: RoundedCornerShape = RoundedCornerShape(32.dp)
}

/** Side margin of every screen; the one number that sets the app's rhythm. */
val ScreenPadding: Dp = 16.dp

/** Empty background between two groups. */
val GroupSpacing: Dp = 16.dp

/** Gap between rows of a group: enough to separate them, too little to break the group. */
val GroupRowSpacing: Dp = 3.dp

/** Padding of the group container around its rows. */
val GroupInset: Dp = 4.dp
