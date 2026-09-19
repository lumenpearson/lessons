package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding

/**
 * "Включить пре-релизы?" — asked before the switch is allowed to flip.
 *
 * A switch that simply flips gives nobody a moment to learn what a pre-release
 * is, and here it is an APK that has not yet been through a whole school. So
 * the row asks first, the way Essentials' "Check for pre-releases" does.
 *
 * Essentials ends its version of this question with an app restart, because
 * its update channel is read once at startup. Here the channel is a setting
 * read at check time, so nothing restarts, and the buttons say what actually
 * happens: the check runs at once. A sheet rather than a dialog, like every
 * other interruption in the app.
 *
 * The sheet does not flip the setting itself: [onConfirm] is the caller's, so
 * the switch and the check it triggers stay in one place.
 */
@Composable
fun PrereleaseSheet(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LessonsBottomSheet(onDismissRequest = onDismiss, modifier = modifier) {
        PrereleaseContent(onDismiss = onDismiss, onConfirm = onConfirm)
    }
}

/** Under the drag handle; separate so the preview can draw it without a window. */
@Composable
private fun PrereleaseContent(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = ScreenPadding, end = ScreenPadding, bottom = SheetBottomInset),
        verticalArrangement = Arrangement.spacedBy(SheetBlockGap),
    ) {
        Text(
            text = correctedString(R.string.prerelease_sheet_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = TitleTop, bottom = TitleGap),
        )
        Text(
            text = correctedString(R.string.prerelease_sheet_body_risk),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = correctedString(R.string.prerelease_sheet_body_revert),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // The consequence, in the accent colour: the one line of the three
        // that describes something the app will do rather than something the
        // user should know.
        Text(
            text = correctedString(R.string.prerelease_sheet_note),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = ButtonsTop),
            horizontalArrangement = Arrangement.spacedBy(SheetPillGap),
        ) {
            // Neither button has a wave to fire, so the origin is ignored.
            SheetPill(
                label = correctedString(R.string.prerelease_sheet_cancel),
                filled = false,
                onClick = { onDismiss() },
                modifier = Modifier.weight(1f),
            )
            SheetPill(
                label = correctedString(R.string.prerelease_sheet_confirm),
                filled = true,
                onClick = { onConfirm() },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Under the drag handle, which the sheet draws with a margin of its own. */
private val TitleTop = 8.dp

private val TitleGap = 4.dp

private val ButtonsTop = 4.dp

@Preview(name = "PrereleaseSheet", showBackground = true)
@Composable
private fun PrereleaseSheetPreview() {
    LessonsTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            PrereleaseContent(onDismiss = {}, onConfirm = {})
        }
    }
}
