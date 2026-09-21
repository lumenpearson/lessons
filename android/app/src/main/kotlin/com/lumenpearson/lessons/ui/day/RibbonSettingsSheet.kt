package com.lumenpearson.lessons.ui.day

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.SwipeVertical
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.RibbonFlow

/**
 * The three things this screen lets a reader decide, and nothing else.
 *
 * Its own sheet rather than three more rows in «Настройки», because all three
 * are about a surface somebody is looking at while they change them: the
 * direction the progress runs, whether the scroll has detents, and whether the
 * cards have depth. Each shows its effect the moment it is pressed, which is an
 * argument nothing in the settings tree can make.
 *
 * They are stored all the same — see `AppSettings.dayRibbonFlow`. A sheet is
 * where they are *asked*; where they live is a different question, and a
 * `remember` here would lose them the first time the calendar changed view.
 */
@Composable
internal fun RibbonSettingsSheet(
    flow: RibbonFlow,
    snap: Boolean,
    depth: Boolean,
    onFlow: (RibbonFlow) -> Unit,
    onSnap: (Boolean) -> Unit,
    onDepth: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(R.string.ribbon_settings),
    ) {
        SectionHeader(
            title = correctedString(R.string.ribbon_flow_title),
            modifier = Modifier.padding(horizontal = ScreenPadding - 16.dp),
        )
        SegmentedPicker(
            items = RibbonFlow.entries,
            selectedItem = flow,
            onItemSelected = onFlow,
            labelProvider = { it.asLabel() },
            containerColor = MaterialTheme.colorScheme.rowContainer,
            contentPadding = PaddingValues(4.dp),
            // The picker rounds its own tray from the buttons inside it; a
            // radius chosen here could only agree with them by coincidence.
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            GroupSwitchItem(
                title = correctedString(R.string.ribbon_snap_title),
                subtitle = correctedString(R.string.ribbon_snap_description),
                icon = Icons.Rounded.SwipeVertical,
                tone = accentTone(2),
                checked = snap,
                onCheckedChange = onSnap,
            )
            GroupSwitchItem(
                title = correctedString(R.string.ribbon_depth_title),
                subtitle = correctedString(R.string.ribbon_depth_description),
                icon = Icons.Rounded.Layers,
                tone = accentTone(4),
                checked = depth,
                onCheckedChange = onDepth,
            )
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** The picker's two words. */
@Composable
internal fun RibbonFlow.asLabel(): String = correctedString(
    when (this) {
        RibbonFlow.DOWNWARD -> R.string.ribbon_flow_downward
        RibbonFlow.UPWARD -> R.string.ribbon_flow_upward
    },
)
