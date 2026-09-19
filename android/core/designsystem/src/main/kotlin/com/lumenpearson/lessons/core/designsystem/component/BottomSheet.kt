package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding

/**
 * Every bottom sheet in the app.
 *
 * A port of `EssentialsBottomSheet` from
 * [Essentials](https://github.com/sameerasw/essentials), including the three
 * decisions that make its sheets behave: the sheet is never partially expanded,
 * it declares zero content insets and adds the navigation-bar spacer itself
 * (so its own content decides what sits above the gesture bar rather than the
 * sheet padding everything), and it is pushed below the status bar so a tall
 * sheet never slides under the clock.
 *
 * @param title drawn above the content, in the sheet's own heading style. Pass
 *   `null` for a sheet that provides its own header.
 */
@Composable
fun LessonsBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    sheetState: SheetState = rememberFullSheetState(),
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismissRequest,
        sheetState = sheetState,
        containerColor = containerColor,
        scrimColor = scrimColor,
        dragHandle = dragHandle,
        contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
        modifier = modifier.statusBarsPadding(),
    ) {
        Column(
            // The sheet declares zero content insets so that it owns its own
            // bottom spacing — which also means nothing else is handling the
            // keyboard. The union of the two insets, rather than one padding on
            // top of the other: while the IME is up it covers the navigation
            // bar, so adding both would leave a gesture bar's worth of dead
            // space between the sheet's last control and the keyboard.
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))
                // Whatever is left after the keyboard has taken its share is
                // what the sheet gets. A tall sheet on a short screen then
                // scrolls instead of pushing its buttons out of the window.
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(
                        start = ScreenPadding,
                        end = ScreenPadding,
                        bottom = 8.dp,
                    ),
                )
            }
            content()
        }
    }
}

/**
 * A sheet that is either hidden or fully expanded — never half-open.
 *
 * Essentials passes `skipPartiallyExpanded = true` to
 * `rememberModalBottomSheetState` for the same effect; that factory is
 * deprecated in this Material build, and its replacement states the intent the
 * other way round, as the set of heights the sheet is *allowed* to rest at.
 */
@Composable
private fun rememberFullSheetState(): SheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
)
