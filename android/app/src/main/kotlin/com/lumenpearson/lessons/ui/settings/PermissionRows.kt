package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

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
            title = correctedString(R.string.permissions_title),
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

/**
 * All three permissions in one group, under a banner that only exists while
 * something is wrong.
 *
 * The asking itself is [rememberPermissionPrompts] and the card is
 * [PermissionCard]; what is left here is the page's own arrangement, which is
 * the only thing this screen does not share with the first-run step.
 */
@Composable
private fun PermissionsContent() {
    val prompts = rememberPermissionPrompts()

    Column(
        verticalArrangement = Arrangement.spacedBy(SectionGap),
        modifier = Modifier.padding(horizontal = ScreenPadding),
    ) {
        MissingBanner(missing = prompts.missing, onRefresh = prompts.refresh)

        RoundedCardContainer {
            prompts.states.forEach { state ->
                PermissionCard(state = state, onAct = { prompts.act(state) })
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
                    text = correctedString(R.string.permissions_banner_title),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = correctedString(R.string.permissions_banner_description),
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
                    contentDescription = correctedString(R.string.permissions_refresh),
                    modifier = Modifier.size(RefreshIcon),
                )
            }
        }
    }
}

private val SectionGap = 12.dp

private val RefreshIcon = 22.dp

/** Supporting text on a coloured container, one step down without a second role. */
private const val SupportingAlpha = 0.8f
