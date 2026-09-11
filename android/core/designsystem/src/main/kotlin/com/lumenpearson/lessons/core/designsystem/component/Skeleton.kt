package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.modifier.shimmer
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/** Height of the title bar of a placeholder row. */
private val TitleBarHeight: Dp = 14.dp

/** …and of the subtitle under it. */
private val SubtitleBarHeight: Dp = 11.dp

/**
 * What a group looks like while its data is still being read.
 *
 * A shimmering copy of the layout rather than a spinner, which is the pattern
 * Essentials uses everywhere it waits: the page does not change shape when the
 * real rows arrive, so nothing jumps and the wait reads as "this is loading"
 * rather than as "something is wrong".
 *
 * The whole block is hidden from accessibility — a screen reader announcing
 * three empty rows is worse than silence — so callers must keep whatever they
 * were already announcing about the loading state.
 */
@Composable
fun SkeletonGroup(
    modifier: Modifier = Modifier,
    rows: Int = 3,
) {
    RoundedCardContainer(modifier = modifier.clearAndSetSemantics {}) {
        repeat(rows) { index ->
            SkeletonRow(
                // Descending widths, so the block reads as text rather than as
                // three identical grey bars.
                titleFraction = 0.7f - index * 0.08f,
                subtitleFraction = 0.45f - index * 0.05f,
            )
        }
    }
}

/** One placeholder row: a tile and two bars of text. */
@Composable
private fun SkeletonRow(
    titleFraction: Float,
    subtitleFraction: Float,
) {
    GroupRow(container = MaterialTheme.colorScheme.rowContainer) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(LessonsShapeTokens.Tile)
                .shimmer(),
        )
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SkeletonBar(fraction = titleFraction, height = TitleBarHeight)
            SkeletonBar(fraction = subtitleFraction, height = SubtitleBarHeight)
        }
    }
}

/** One shimmering bar, standing in for a line of text. */
@Composable
private fun SkeletonBar(fraction: Float, height: Dp) {
    Box(
        modifier = Modifier
            .fillMaxWidth(fraction.coerceIn(0.2f, 1f))
            .height(height)
            .clip(LessonsShapeTokens.Pill)
            .shimmer(),
    )
}

@Preview(name = "SkeletonGroup", showBackground = true)
@Composable
private fun SkeletonGroupPreview() {
    LessonsTheme {
        Box(
            modifier = Modifier.padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            SkeletonGroup()
        }
    }
}
