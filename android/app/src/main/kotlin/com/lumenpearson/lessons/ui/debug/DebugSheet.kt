package com.lumenpearson.lessons.ui.debug

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.lumenpearson.lessons.BuildConfig
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.diagnostics.CrashReporter
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The crash reports, and what to do with them.
 *
 * A port of Essentials' `CrashLogsBottomSheet`: the same five-report list, the
 * same share-or-delete pair on each, the same clear-everything at the bottom.
 * It is reached from the bug button beside the toolbar's pill, which is where
 * Essentials puts its own.
 *
 * The point is that this app has no crash service behind it. It is handed out as
 * an APK inside one school, so "оно закрылось" has to be answerable from the
 * phone it closed on — by opening this and sending the file.
 */
@Composable
fun DebugSheet(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var reports by remember { mutableStateOf(CrashReporter.reports(context)) }
    var opened by remember { mutableStateOf<File?>(null) }
    val stamp = remember { SimpleDateFormat("d MMMM, HH:mm", Locale.getDefault()) }

    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.debug_title),
    ) {
        Text(
            text = stringResource(
                if (enabled) R.string.debug_on_description else R.string.debug_off_description,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            GroupItem(
                title = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
                subtitle = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} " +
                    "· Android ${android.os.Build.VERSION.RELEASE}",
                icon = Icons.Rounded.BugReport,
                tone = accentTone(4),
            )
        }

        SectionHeader(
            title = stringResource(R.string.debug_reports),
            subtitle = if (reports.isEmpty()) {
                stringResource(R.string.debug_reports_empty)
            } else {
                null
            },
            modifier = Modifier.padding(horizontal = ScreenPadding - 16.dp),
        )

        if (reports.isNotEmpty()) {
            RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                reports.forEach { file ->
                    GroupItem(
                        title = stamp.format(Date(file.lastModified())),
                        subtitle = stringResource(R.string.debug_report_size, file.length() / 1024),
                        icon = Icons.Rounded.BugReport,
                        tone = errorTone(),
                        onClick = { opened = if (opened == file) null else file },
                        trailing = {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { share(context, file) }) {
                                    Text(text = stringResource(R.string.debug_share))
                                }
                            }
                        },
                    )
                }
            }
        }

        // The report itself, when one is opened. Monospaced and scrollable in
        // both directions, because a stack trace that is wrapped or truncated
        // is a stack trace nobody can read the important line of.
        opened?.let { file ->
            val text = remember(file) { runCatching { file.readText() }.getOrElse { "" } }
            RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .heightIn(max = ReportPreviewHeight)
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()),
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Button(
                onClick = { onEnabledChange(!enabled) },
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = stringResource(
                        if (enabled) R.string.debug_turn_off else R.string.debug_turn_on,
                    ),
                )
            }
            if (reports.isNotEmpty()) {
                Button(
                    onClick = {
                        CrashReporter.clear(context)
                        reports = emptyList()
                        opened = null
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    ),
                ) {
                    Text(text = stringResource(R.string.debug_clear))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/** Tall enough for a stack trace's first frames, short enough to scroll past. */
private val ReportPreviewHeight = 260.dp

/**
 * Hands one report to the share sheet.
 *
 * Through `FileProvider`, so the receiving app gets a one-shot read grant on
 * that single file rather than any access to the app's storage.
 */
private fun share(context: android.content.Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
