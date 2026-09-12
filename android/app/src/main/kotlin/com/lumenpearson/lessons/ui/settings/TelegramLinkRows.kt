package com.lumenpearson.lessons.ui.settings

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import kotlinx.coroutines.launch

/**
 * The «Telegram» group on the class page: whether this phone is tied to an
 * account, and the code that ties it while it is not.
 *
 * The app has no login of its own, and this is deliberately not one either.
 * The bot already knows who is a редактор and who is not; what the phone
 * needs is to be recognised as *somebody's*, and a six-character code typed
 * into a chat the user is already in is the shortest path to that. Once
 * linked, every write the app makes is judged by the server against that
 * account's role at the moment of the request, so there is nothing here to
 * keep in sync and nothing that can go stale.
 */
internal fun androidx.compose.foundation.lazy.LazyListScope.telegramLinkRows(
    state: DeviceLinkState,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
) = item(key = "telegram_link") {
    SettingsGroup(title = stringResource(R.string.telegram_group)) {
        when (state) {
            DeviceLinkState.Idle, is DeviceLinkState.Loading -> {
                val known = (state as? DeviceLinkState.Loading)?.known
                if (known != null) {
                    LinkBody(known, busy = true, onRefresh = onRefresh, onUnlink = onUnlink)
                } else {
                    GroupItem(
                        title = stringResource(R.string.telegram_checking),
                        icon = Icons.Rounded.Link,
                        tone = accentTone(0),
                        trailing = { LoadingIndicator(modifier = Modifier.size(24.dp)) },
                    )
                }
            }

            is DeviceLinkState.Ready -> LinkBody(state.link, busy = false, onRefresh, onUnlink)

            is DeviceLinkState.Failed -> {
                // A failed refresh keeps the last answer on screen: a code the
                // user is halfway through typing must not vanish because the
                // lift had no signal.
                if (state.known != null) {
                    LinkBody(state.known, busy = false, onRefresh = onRefresh, onUnlink = onUnlink)
                }
                GroupItem(
                    title = stringResource(R.string.telegram_check_failed),
                    subtitle = state.cause.message?.takeIf { it.isNotBlank() }
                        ?: stringResource(R.string.sync_error_generic),
                    icon = Icons.Rounded.LinkOff,
                    tone = errorTone(),
                )
                GroupActionItem(
                    label = stringResource(R.string.telegram_retry),
                    icon = Icons.Rounded.Refresh,
                    onClick = onRefresh,
                )
            }
        }
    }
}

@Composable
private fun LinkBody(
    link: DeviceLink,
    busy: Boolean,
    onRefresh: () -> Unit,
    onUnlink: () -> Unit,
) {
    if (link.linked) {
        LinkedRows(link, busy, onUnlink)
    } else {
        UnlinkedRows(link, busy, onRefresh)
    }
}

@Composable
private fun LinkedRows(link: DeviceLink, busy: Boolean, onUnlink: () -> Unit) {
    val role = link.role
    val subtitle = when {
        role == null -> stringResource(R.string.telegram_linked_no_membership)
        link.canEdit -> stringResource(R.string.telegram_linked_can_edit, stringResource(role.labelRes))
        else -> stringResource(R.string.telegram_linked_read_only, stringResource(role.labelRes))
    }
    GroupItem(
        title = stringResource(R.string.telegram_linked),
        subtitle = subtitle,
        icon = Icons.Rounded.Link,
        tone = accentTone(0),
    )
    GroupActionItem(
        label = stringResource(R.string.telegram_unlink),
        icon = Icons.Rounded.LinkOff,
        onClick = onUnlink,
        enabled = !busy,
        busy = busy,
    )
}

@Composable
private fun UnlinkedRows(link: DeviceLink, busy: Boolean, onRefresh: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.telegram_clipboard_label)
    val code = link.linkCode

    GroupItem(
        title = stringResource(R.string.telegram_not_linked),
        subtitle = stringResource(R.string.telegram_not_linked_description),
        icon = Icons.Rounded.LinkOff,
        tone = accentTone(0),
    )
    if (code != null) {
        // The code is the whole point of this state, so it is drawn as a value
        // in the row's own weight rather than tucked into a subtitle - and it
        // copies with one tap, because "I mistyped it" is the failure mode.
        GroupItem(
            title = stringResource(R.string.telegram_code_title),
            subtitle = stringResource(R.string.telegram_code_hint, code),
            icon = Icons.Rounded.Send,
            tone = accentTone(2),
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = code,
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(
                        onClick = {
                            scope.launch {
                                val clip = ClipData.newPlainText(clipLabel, code)
                                clipboard.setClipEntry(clip.toClipEntry())
                            }
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.telegram_copy_code),
                        )
                    }
                }
            },
        )
        val deepLink = link.botDeepLink
        if (deepLink != null) {
            GroupActionItem(
                label = stringResource(R.string.telegram_open_bot),
                icon = Icons.Rounded.OpenInNew,
                // A phone with no browser and no Telegram throws here; an
                // ornament must not take the settings page down with it.
                onClick = { runCatching { uriHandler.openUri(deepLink) } },
                enabled = !busy,
            )
        }
    }
    GroupActionItem(
        label = stringResource(R.string.telegram_check_again),
        icon = Icons.Rounded.Refresh,
        onClick = onRefresh,
        enabled = !busy,
        busy = busy,
    )
}

/** Confirmation before the phone is untied: it drops to read-only at once. */
@Composable
internal fun UnlinkSheet(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.telegram_unlink_title),
    ) {
        Text(
            text = stringResource(R.string.telegram_unlink_message),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                ),
            ) {
                Text(text = stringResource(R.string.telegram_unlink))
            }
        }
    }
}

/** The role's name as the bot itself prints it. */
internal val ClassRole.labelRes: Int
    get() = when (this) {
        ClassRole.VIEWER -> R.string.role_viewer
        ClassRole.EDITOR -> R.string.role_editor
        ClassRole.ADMIN -> R.string.role_admin
        ClassRole.OWNER -> R.string.role_owner
    }
