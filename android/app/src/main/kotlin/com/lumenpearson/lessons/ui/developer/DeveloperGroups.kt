package com.lumenpearson.lessons.ui.developer

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.FormatSize
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.NetworkCheck
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.developer.CheckKind
import com.lumenpearson.lessons.core.data.developer.CheckOutcome
import com.lumenpearson.lessons.core.data.developer.CheckResult
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone

/** One switch per tool, in the order the enum declares them. */
@Composable
internal fun ToolsGroup(chosen: Set<DeveloperTool>, onToggle: (DeveloperTool, Boolean) -> Unit) {
    DeveloperGroup(title = correctedString(R.string.developer_tools_group)) {
        DeveloperTool.entries.forEach { tool ->
            val face = toolFace(tool)
            GroupSwitchItem(
                title = correctedString(face.title),
                subtitle = correctedString(face.summary),
                icon = face.icon,
                tone = accentTone(face.tone),
                checked = tool in chosen,
                onCheckedChange = { onToggle(tool, it) },
            )
        }
    }
}

/** What a tool's switch says and shows. */
private class ToolFace(val title: Int, val summary: Int, val icon: ImageVector, val tone: Int)

private fun toolFace(tool: DeveloperTool): ToolFace = when (tool) {
    DeveloperTool.NETWORK_LOG -> ToolFace(
        title = R.string.developer_tool_network,
        summary = R.string.developer_tool_network_summary,
        icon = Icons.Rounded.NetworkCheck,
        tone = 0,
    )
    DeveloperTool.ACTIVITY_LOG -> ToolFace(
        title = R.string.developer_tool_activity,
        summary = R.string.developer_tool_activity_summary,
        icon = Icons.Rounded.History,
        tone = 1,
    )
    DeveloperTool.LAYOUT_GRID -> ToolFace(
        title = R.string.developer_tool_grid,
        summary = R.string.developer_tool_grid_summary,
        icon = Icons.Rounded.GridOn,
        tone = 2,
    )
    DeveloperTool.STRETCHED_STRINGS -> ToolFace(
        title = R.string.developer_tool_stretch,
        summary = R.string.developer_tool_stretch_summary,
        icon = Icons.Rounded.UnfoldMore,
        tone = 3,
    )
    DeveloperTool.LARGE_TEXT -> ToolFace(
        title = R.string.developer_tool_large_text,
        summary = R.string.developer_tool_large_text_summary,
        icon = Icons.Rounded.FormatSize,
        tone = 4,
    )
}

/** The button, and once run, one row per check with its verdict and what it saw. */
@Composable
internal fun ChecksGroup(checks: ChecksState, onRun: () -> Unit) {
    DeveloperGroup(
        title = correctedString(R.string.developer_checks_group),
        subtitle = correctedString(R.string.developer_checks_hint),
    ) {
        GroupActionItem(
            label = correctedString(R.string.developer_checks_run),
            icon = Icons.Rounded.TaskAlt,
            enabled = checks != ChecksState.Running,
            busy = checks == ChecksState.Running,
            onClick = onRun,
        )
        if (checks is ChecksState.Done) {
            checks.results.forEach { result -> CheckRow(result) }
        }
    }
}

@Composable
private fun CheckRow(result: CheckResult) {
    val title = when (result.kind) {
        CheckKind.DIARY_HOST -> correctedString(R.string.developer_check_diary_host, result.subject.orEmpty())
        else -> correctedString(checkTitle(result.kind))
    }
    GroupItem(
        title = title,
        subtitle = result.tookMillis?.let { correctedString(R.string.developer_took_ms, result.detail, it) }
            ?: result.detail,
        icon = outcomeIcon(result.outcome),
        tone = outcomeTone(result.outcome),
        trailing = {
            Text(
                text = correctedString(outcomeLabel(result.outcome)),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

private fun checkTitle(kind: CheckKind): Int = when (kind) {
    CheckKind.SERVER -> R.string.developer_check_server
    CheckKind.DIARY_HOST -> R.string.developer_check_diary_host
    CheckKind.GITHUB -> R.string.developer_check_github
    CheckKind.KEYSTORE -> R.string.developer_check_keystore
    CheckKind.NETWORK -> R.string.developer_check_network
    CheckKind.NOTIFICATIONS -> R.string.developer_check_notifications
    CheckKind.EXACT_ALARMS -> R.string.developer_check_exact_alarms
    CheckKind.BACKGROUND_SYNC -> R.string.developer_check_background_sync
    CheckKind.WIDGET -> R.string.developer_check_widget
    CheckKind.ACCOUNTS -> R.string.developer_check_accounts
    CheckKind.BUILD -> R.string.developer_check_build
}

private fun outcomeLabel(outcome: CheckOutcome): Int = when (outcome) {
    CheckOutcome.PASS -> R.string.developer_outcome_pass
    CheckOutcome.WARN -> R.string.developer_outcome_warn
    CheckOutcome.FAIL -> R.string.developer_outcome_fail
    CheckOutcome.SKIP -> R.string.developer_outcome_skip
}

private fun outcomeIcon(outcome: CheckOutcome): ImageVector = when (outcome) {
    CheckOutcome.PASS -> Icons.Rounded.CheckCircle
    CheckOutcome.WARN -> Icons.Rounded.Warning
    CheckOutcome.FAIL -> Icons.Rounded.Error
    CheckOutcome.SKIP -> Icons.Rounded.Block
}

@Composable
private fun outcomeTone(outcome: CheckOutcome): AccentTone = when (outcome) {
    CheckOutcome.PASS -> accentTone(0)
    CheckOutcome.WARN -> accentTone(WarnSlot)
    CheckOutcome.FAIL -> errorTone()
    CheckOutcome.SKIP -> neutralTone()
}

/** The accent slot a warning takes; the palette has no amber of its own. */
private const val WarnSlot = 5

/** Copy and share, the two ways the report leaves the phone. */
@Composable
internal fun ReportGroup(onCopy: () -> Unit, onShare: () -> Unit) {
    DeveloperGroup(
        title = correctedString(R.string.developer_report_group),
        subtitle = correctedString(R.string.developer_report_hint),
    ) {
        GroupActionItem(
            label = correctedString(R.string.developer_report_copy),
            icon = Icons.Rounded.ContentCopy,
            onClick = onCopy,
        )
        GroupActionItem(
            label = correctedString(R.string.developer_report_share),
            icon = Icons.Rounded.Share,
            onClick = onShare,
        )
    }
}
