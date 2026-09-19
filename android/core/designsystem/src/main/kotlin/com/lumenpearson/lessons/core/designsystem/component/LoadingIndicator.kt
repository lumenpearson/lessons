package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshState
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme

/** How far the loader sits from the edges when it is given a whole screen. */
private val LoadingBoxPadding: Dp = 24.dp

/**
 * The indeterminate loader this app waits with.
 *
 * Material 3 Expressive's `LoadingIndicator`, which morphs through a sequence
 * of shapes rather than sweeping an arc. The reason to prefer it over
 * `CircularProgressIndicator` is not novelty: an arc at 60 fps reads as one
 * unchanging object, so a wait of two seconds and a wait of ten look the same
 * from the first frame, while a shape that has visibly become a different shape
 * says time has passed. It is the loader
 * [GMS Flags Reborn](https://github.com/polodarb/GMS-Flags-Reborn) uses
 * throughout, and there it is this same androidx component — there is nothing
 * to port, only a default to change.
 *
 * **This does not replace [SkeletonGroup].** The two answer different
 * questions. A skeleton says what is about to appear and keeps the page from
 * changing shape when it does, so it belongs wherever the layout is already
 * known: a list of rows, a card with a title and two lines. A loader says only
 * that something is happening, which is all that can honestly be said when the
 * shape of the answer is not known yet — a sheet that has not read its first
 * response, the session being restored before the first screen is chosen.
 * Where a skeleton fits, it is still the better answer.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LessonsLoadingIndicator(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    LoadingIndicator(
        modifier = modifier,
        color = color,
    )
}

/**
 * [LessonsLoadingIndicator] centred in whatever space it is handed.
 *
 * The padding is there for the smallest case — a loader inside a sheet that is
 * one row tall — so that the shapes never touch the container's corners while
 * they morph outwards.
 */
@Composable
fun LessonsLoadingBox(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(LoadingBoxPadding),
        contentAlignment = Alignment.Center,
    ) {
        LessonsLoadingIndicator(color = color)
    }
}

/**
 * Pull-to-refresh with the same loader at the top of it.
 *
 * `PullToRefreshBox` draws a circular arrow by default, which is the one place
 * in the app where a wait would have looked like a different product from every
 * other wait. `PullToRefreshDefaults.LoadingIndicator` is the expressive
 * indicator wired to the gesture: it takes shape as the finger travels and
 * keeps morphing while the refresh runs.
 *
 * It exists here rather than at each call site because there are two screens
 * that pull to refresh and they must not drift apart — the same reason the
 * bells' row lives here and not in two sheets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun LessonsPullToRefreshBox(
    isRefreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    state: PullToRefreshState = rememberPullToRefreshState(),
    content: @Composable BoxScope.() -> Unit,
) {
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = onRefresh,
        modifier = modifier,
        state = state,
        indicator = {
            PullToRefreshDefaults.LoadingIndicator(
                state = state,
                isRefreshing = isRefreshing,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        },
        content = content,
    )
}

@Preview
@Composable
private fun LoadingIndicatorPreview() {
    LessonsTheme {
        LessonsLoadingBox(modifier = Modifier.padding(32.dp))
    }
}
