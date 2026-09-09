package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The Expressive corner scale.
 *
 * Bigger than the Material baseline on purpose: a school diary is read in
 * two-second glances between classes, and generous corners are what make the
 * grouped cards separate at a glance instead of needing dividers.
 */
val LessonsShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/**
 * Shapes that are part of the visual language but have no Material role to live
 * in, so they would otherwise be re-invented as magic numbers in every screen.
 */
object LessonsShapeTokens {

    /** The one card per screen that answers "what is happening right now". */
    val HeroCard: RoundedCornerShape = RoundedCornerShape(32.dp)

    /** Status chips; a fully-rounded capsule regardless of its height. */
    val Pill: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** The numeric badge in front of a lesson, and the timeline rail nodes. */
    val Badge: RoundedCornerShape = RoundedCornerShape(percent = 50)

    /** A single, standalone list row that is not part of a group. */
    val ListRow: RoundedCornerShape = RoundedCornerShape(20.dp)

    private val GroupOuter = CornerSize(24.dp)
    private val GroupInner = CornerSize(6.dp)

    /**
     * Corner treatment for row [index] of [count] inside a grouped section.
     *
     * The Essentials idiom: a stack of near-touching rows whose outer corners are
     * round and inner corners are nearly square, so the group reads as one object
     * without needing an outline around it. Pure function so callers can use it
     * inside `items {}` without recomposition cost.
     */
    fun groupedRow(index: Int, count: Int): RoundedCornerShape = when {
        count <= 1 -> RoundedCornerShape(GroupOuter)
        index == 0 -> RoundedCornerShape(GroupOuter, GroupOuter, GroupInner, GroupInner)
        index == count - 1 -> RoundedCornerShape(GroupInner, GroupInner, GroupOuter, GroupOuter)
        else -> RoundedCornerShape(GroupInner)
    }
}

/** Vertical gap between rows of a grouped section; small enough to read as one card. */
val GroupedRowSpacing: Dp = 2.dp
