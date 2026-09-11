package com.lumenpearson.lessons.ui.settings

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * Whether every permission is in place, recomputed whenever the user might have
 * changed one.
 *
 * There is no listener for this. A permission is revoked in system settings, in
 * another app's process, and the only moment we are told anything is that our
 * own activity comes back to the front — so that is when it is asked again.
 * Essentials does the same from `Activity.onResume`; here it is a lifecycle
 * observer instead, because these are screens inside one activity rather than
 * activities of their own, and an `onResume` on the activity would not fire when
 * the user merely navigates between them.
 *
 * A refresh token rather than a stored list: the answers come from the system
 * and are cheap to ask for, and storing them is how the reference ends up with
 * cards that say "выдать" for something already granted.
 */
@Composable
private fun rememberPermissionRefresh(): Int {
    var token by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) token++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    return token
}

/**
 * How many permissions are missing, as a value the caller can key off.
 *
 * Zero on a device that has granted everything, which is what makes the entry
 * row and the banner disappear on their own rather than needing to be dismissed.
 */
@Composable
fun rememberMissingPermissionCount(): Int {
    val context = LocalContext.current
    val token = rememberPermissionRefresh()
    return remember(token, context) { AppPermission.missingCount(context) }
}

/**
 * The red row that says something is missing, for the top of another page.
 *
 * Draws nothing at all when nothing is missing. That is the whole contract: it
 * is not a permanent entry to a permissions screen, it is a fault light.
 */
@Composable
fun MissingPermissionsRow(
    missing: Int,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (missing <= 0) return

    RoundedCardContainer(modifier = modifier) {
        GroupItem(
            title = stringResource(R.string.permissions_title),
            subtitle = pluralStringResource(R.plurals.permissions_missing, missing, missing),
            icon = Icons.Rounded.Shield,
            tone = errorTone(),
            onClick = onOpen,
        )
    }
}

/** The permissions page itself. */
fun LazyListScope.permissionRows() {
    item(key = "permissions") {
        PermissionsContent()
    }
}

@Composable
private fun PermissionsContent() {
    val context = LocalContext.current
    val token = rememberPermissionRefresh()

    // Bumped by the runtime-permission dialog, which returns without the
    // activity ever leaving the foreground — so the lifecycle observer above
    // never fires for it and the card would otherwise still read "выдать".
    var granted by remember { mutableIntStateOf(0) }
    var manual by remember { mutableIntStateOf(0) }

    val states = remember(token, granted, manual, context) {
        AppPermission.relevant().map { permission -> permission to permission.isGranted(context) }
    }
    val missing = states.count { (_, ok) -> !ok }

    val request = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted++ }

    Column(
        verticalArrangement = Arrangement.spacedBy(SectionGap),
        modifier = Modifier.padding(horizontal = ScreenPadding),
    ) {
        MissingBanner(missing = missing, onRefresh = { manual++ })

        RoundedCardContainer {
            states.forEach { (permission, isGranted) ->
                PermissionCard(
                    permission = permission,
                    isGranted = isGranted,
                    onOpen = {
                        // Ask in place where the platform still allows it. Once
                        // the dialog has been refused twice it never appears
                        // again, and `open` then lands on the settings page
                        // where it can still be turned on by hand.
                        val askable = permission == AppPermission.NOTIFICATIONS &&
                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !hasNotificationPermission(context)
                        if (askable) {
                            request.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            permission.open(context)
                        }
                    },
                )
            }
        }
    }
}

/**
 * The banner at the top, and the only way back from a system page that did not
 * bother to pause us.
 */
@Composable
private fun MissingBanner(missing: Int, onRefresh: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    if (missing <= 0) return

    RoundedCardContainer {
        GroupRow(
            container = scheme.errorContainer,
            contentColor = scheme.onErrorContainer,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.permissions_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = stringResource(R.string.permissions_banner_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onErrorContainer.copy(alpha = SupportingAlpha),
                )
            }
            IconButton(
                onClick = onRefresh,
                colors = IconButtonDefaults.iconButtonColors(contentColor = scheme.onErrorContainer),
            ) {
                Icon(
                    imageVector = Icons.Rounded.Refresh,
                    contentDescription = stringResource(R.string.permissions_refresh),
                    modifier = Modifier.size(RefreshIcon),
                )
            }
        }
    }
}

/**
 * One permission: what it is for, and a button to go and grant it.
 *
 * The button stays when it is granted rather than turning into a tick, because
 * the page's job is not only to fix things — a permission that is on is also
 * the one you come here to turn off. It changes emphasis instead: filled while
 * something is wrong, outlined once it is not.
 */
@Composable
private fun PermissionCard(
    permission: AppPermission,
    isGranted: Boolean,
    onOpen: () -> Unit,
) {
    GroupItem(
        title = stringResource(permission.titleRes),
        subtitle = stringResource(permission.descriptionRes),
        icon = permission.icon,
        tone = if (isGranted) accentTone(1) else errorTone(),
        trailing = {
            if (isGranted) {
                OutlinedButton(onClick = onOpen, contentPadding = ButtonPadding) {
                    Text(
                        text = stringResource(R.string.permissions_action_open),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            } else {
                Button(onClick = onOpen, contentPadding = ButtonPadding) {
                    Text(
                        text = stringResource(R.string.permissions_action_grant),
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        },
    )
}

/** @see AppPermission.NOTIFICATIONS */
private fun hasNotificationPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

private val SectionGap = 12.dp

private val RefreshIcon = 22.dp

private val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)

/** Supporting text on a coloured container, one step down without a second role. */
private const val SupportingAlpha = 0.8f
