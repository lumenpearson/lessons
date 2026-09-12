package com.lumenpearson.lessons.ui.translate

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import kotlinx.coroutines.launch

/**
 * Everything the reader has corrected, and the two ways it leaves the phone.
 *
 * The session is not stored anywhere — see [TranslationMode] — so this sheet is
 * the only place the work exists in one piece, and copy and share are the whole
 * point of it rather than an afterthought at the bottom. Both hand over the
 * same text: the `<string>` elements, grouped by the folder they belong in,
 * ready to be pasted into a `strings.xml` or dropped into a message by someone
 * who will paste it there.
 *
 * There is no "submit". Essentials posts its sessions to a discussion thread on
 * its own repository; this app has no such thread and no account of its own to
 * post with, and inventing one would be shipping a button whose failure mode is
 * silence.
 */
@Composable
internal fun TranslationSessionSheet(onDismiss: () -> Unit) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.translation_session_title),
    ) {
        SessionContent()
    }
}

@Composable
private fun ColumnScope.SessionContent() {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.translation_clipboard_label)
    val edits = TranslationMode.session.edits

    if (edits.isEmpty()) {
        Text(
            text = stringResource(R.string.translation_session_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 24.dp),
        )
        Spacer(Modifier.height(8.dp))
        return
    }

    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        edits.forEachIndexed { index, edit ->
            GroupItem(
                // The corrected wording is the headline because it is what the
                // reader is checking; the key and the old wording are the
                // evidence for it and sit underneath in the quieter style.
                title = edit.corrected,
                subtitle = stringResource(
                    R.string.translation_edit_subtitle,
                    edit.key,
                    edit.original,
                ),
                tone = accentTone(index),
                trailing = {
                    IconButton(onClick = { TranslationMode.drop(edit.key, edit.locale) }) {
                        Icon(
                            imageVector = Icons.Rounded.Close,
                            contentDescription = stringResource(
                                R.string.translation_session_remove,
                            ),
                        )
                    }
                },
            )
        }
        GroupActionItem(
            label = stringResource(R.string.translation_session_copy),
            icon = Icons.Rounded.ContentCopy,
            onClick = {
                scope.launch {
                    val fragment = TranslationXml.fragment(edits)
                    clipboard.setClipEntry(ClipData.newPlainText(clipLabel, fragment).toClipEntry())
                }
            },
        )
        GroupActionItem(
            label = stringResource(R.string.translation_session_share),
            icon = Icons.Rounded.Share,
            onClick = { share(context, clipLabel, TranslationXml.fragment(edits)) },
        )
        GroupItem(
            title = stringResource(R.string.translation_session_clear),
            icon = Icons.Rounded.DeleteSweep,
            tone = errorTone(),
            onClick = TranslationMode::clear,
        )
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Hands the fragment to whatever the phone can send text with.
 *
 * As an extra rather than through a file: the text is a handful of lines
 * destined for a chat or an issue, and a `.xml` attachment would arrive
 * somewhere it cannot be read without downloading it first. Wrapped, because a
 * device with nothing at all able to receive text throws here, and losing the
 * settings page over a share sheet would be worse than losing the share.
 */
private fun share(context: Context, subject: String, fragment: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, subject)
            putExtra(Intent.EXTRA_TEXT, fragment)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
