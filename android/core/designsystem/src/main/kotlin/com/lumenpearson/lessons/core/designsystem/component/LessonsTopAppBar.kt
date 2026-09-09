package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LargeFlexibleTopAppBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

/**
 * The app's one top bar.
 *
 * Wraps [LargeFlexibleTopAppBar] rather than exposing it directly so that the
 * date-plus-class two-line title, the tonal circular back button and the
 * collapse behaviour are decided once — every screen in Lessons has the same
 * header shape, and screens should not each re-derive it.
 *
 * @param subtitle the secondary line, e.g. the class name under the date.
 * @param scrollBehavior pass the screen's `TopAppBarScrollBehavior` to get the
 *   expressive collapse; `null` renders a static expanded bar, which is what the
 *   previews and short screens want.
 */
@Composable
fun LessonsTopAppBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    scrollBehavior: TopAppBarScrollBehavior? = null,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    onBackClick: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    // The subtitle is composed into the title slot rather than passed to a
    // dedicated parameter: that keeps this wrapper working across the flexible
    // app bar's parameter churn. If a `subtitle` slot is available, moving to it
    // is a local change here and nowhere else.
    LargeFlexibleTopAppBar(
        modifier = modifier.padding(horizontal = 4.dp),
        colors = TopAppBarDefaults.topAppBarColors(containerColor = containerColor),
        expandedHeight = if (subtitle != null) 148.dp else 116.dp,
        collapsedHeight = TopAppBarDefaults.LargeAppBarCollapsedHeight,
        title = {
            Column {
                Text(
                    text = title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
        navigationIcon = {
            if (onBackClick != null) {
                IconButton(
                    onClick = onBackClick,
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = stringResource(R.string.ds_action_back),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Preview(name = "LessonsTopAppBar", showBackground = true, heightDp = 220)
@Composable
private fun LessonsTopAppBarPreview() {
    LessonsTheme {
        LessonsTopAppBar(
            title = "Понедельник, 9 сентября",
            subtitle = "8 «Б» · 5 уроков",
            onBackClick = {},
            actions = {
                IconButton(
                    onClick = {},
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.MoreVert,
                        contentDescription = stringResource(R.string.ds_action_more),
                    )
                }
            },
        )
    }
}
