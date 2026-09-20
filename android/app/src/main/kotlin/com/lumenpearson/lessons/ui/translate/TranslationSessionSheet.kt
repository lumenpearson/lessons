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
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.PullRequestResult
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.text.LocalCorrections
import com.lumenpearson.lessons.core.designsystem.text.NoCorrections
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
 * The third way is a pull request, opened from the reader's own GitHub account
 * against this project. Essentials sends its sessions as a comment on a
 * hard-coded discussion thread, which a workflow then turns into a pull request
 * authored by Actions; the contributor survives only as the commit author. This
 * does the plainer thing — the work arrives under the name of whoever did it —
 * and it needs no thread, no workflow and no account of the app's own.
 *
 * **The button is dark while signed out rather than live and inert.** Essentials
 * leaves it enabled and bounces the press to a sign-in prompt; a control that
 * looks pressable and answers with nothing is the worse of the two.
 *
 * @param onSubmit opens the pull request. Suspending, and owned by the settings
 *   view model, because this sheet can be dismissed while GitHub is still
 *   forking and the work must not be cancelled with the composition.
 */
@Composable
internal fun TranslationSessionSheet(
    onDismiss: () -> Unit,
    isSignedIn: Boolean,
    onSubmit: suspend (List<TranslationChange>) -> PullRequestResult,
) {
    // [NoCorrections] for the same reason the editor takes it: this sheet draws
    // the originals and the corrections themselves, so leaving the mode live
    // inside it would offer to correct a list of corrections.
    CompositionLocalProvider(LocalCorrections provides NoCorrections) {
        LessonsBottomSheet(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.translation_session_title),
        ) {
            SessionContent(isSignedIn = isSignedIn, onSubmit = onSubmit)
        }
    }
}

@Composable
private fun ColumnScope.SessionContent(
    isSignedIn: Boolean,
    onSubmit: suspend (List<TranslationChange>) -> PullRequestResult,
) {
    var submitting by remember { mutableStateOf(false) }
    var outcome by remember { mutableStateOf<String?>(null) }
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
        val submitLabel = stringResource(
            if (isSignedIn) R.string.translation_submit else R.string.translation_submit_signed_out,
        )
        val opened = stringResource(R.string.translation_submit_opened)
        val failed = stringResource(R.string.translation_submit_failed)
        val refusedFormat = stringResource(R.string.translation_submit_refused)
        GroupActionItem(
            label = submitLabel,
            icon = Icons.Rounded.CloudUpload,
            enabled = isSignedIn && edits.isNotEmpty() && !submitting,
            busy = submitting,
            onClick = {
                submitting = true
                outcome = null
                scope.launch {
                    val result = onSubmit(edits.map(TranslationEdit::toChange))
                    submitting = false
                    outcome = when (result) {
                        is PullRequestResult.Opened -> {
                            // The session is dropped only now, with a pull
                            // request to point at. Essentials drops it on a
                            // posted comment, which is not the same thing: if
                            // the workflow behind it fails, the reader's work is
                            // gone and nothing says so.
                            TranslationMode.clear()
                            open(context, result.htmlUrl)
                            if (result.refused.isEmpty()) {
                                opened
                            } else {
                                opened + " · " + refusedFormat.format(result.refused.joinToString())
                            }
                        }
                        is PullRequestResult.Failed -> failed
                    }
                }
            },
        )
        val message = outcome
        if (message != null) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
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

/** A session edit, addressed to the file its key's prefix points at. */
private fun TranslationEdit.toChange(): TranslationChange = TranslationChange(
    path = TranslationXml.valuesFolder(key, locale) + "/strings.xml",
    key = key,
    body = TranslationXml.escape(corrected),
)

/**
 * Opens the pull request that was just made.
 *
 * Wrapped because a device with no browser throws, and the pull request exists
 * either way: the message above already says so, and losing the app over the
 * last step of a successful errand would be the worst possible ending to it.
 */
private fun open(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
