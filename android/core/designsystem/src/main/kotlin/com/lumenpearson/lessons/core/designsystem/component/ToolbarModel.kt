package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * One destination of the toolbar in tabbed mode.
 *
 * @param badge draws Essentials' red dot over the icon; for "there is something
 *   new behind this tab" without spending a label on it.
 */
data class ToolbarItem(
    val icon: ImageVector,
    val label: String,
    val badge: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * The button drawn beside the pill.
 *
 * @param badge as on [ToolbarItem]: a dot over the icon, for "there is something
 *   here" without a label to say what.
 * @param onClick handed the middle of the button in the root composition's
 *   coordinates. Most callers ignore it; the one that does not needs a point for
 *   an effect to start from, and the button is the only thing that knows where
 *   it ended up — the toolbar floats, and its width changes with the tab.
 */
data class ToolbarAction(
    val icon: ImageVector,
    val contentDescription: String,
    val badge: Boolean = false,
    val onClick: (at: Offset) -> Unit,
)

/**
 * The pill's test tag. The pill draws no text of its own, so its height — the
 * one thing #240 got wrong — cannot be asked of any node but this one.
 */
internal const val ToolbarPillTag = "lessons_toolbar_pill"
