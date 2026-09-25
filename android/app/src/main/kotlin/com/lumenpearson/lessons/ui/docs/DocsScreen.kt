package com.lumenpearson.lessons.ui.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DocsFailure
import com.lumenpearson.lessons.core.data.repository.DocsState
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.LessonsPullToRefreshBox
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.text.rememberMarkupStyles
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.core.model.DocsBlock
import com.lumenpearson.lessons.core.model.DocsGuidePage
import com.lumenpearson.lessons.core.model.DocsOrigin
import com.lumenpearson.lessons.core.model.DocsRelease

/**
 * The guide: every page in one pager, and one way out.
 *
 * **Sections are swiped, not entered.** They used to be a screen each, pushed
 * and popped, so the bar was a list of destinations and "back" walked a history
 * nobody had asked for. They are peers — eight sections of one document — and
 * the app already has the gesture for peers, on its three home tabs. The
 * toolbar now scrolls the pager instead of pushing a screen, so back has one
 * meaning here: leave the documentation.
 *
 * The pager state is the shell's, not this screen's. The toolbar is composed by
 * the shell — it rides above this content and outlives it during the slide —
 * and a bar that could not say which section is current, or scroll to another,
 * would be a table of contents that does not know where you are.
 */
@Composable
fun DocsScreen(
    state: DocsState,
    pagerState: PagerState,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val library = state.library
    LessonsPullToRefreshBox(
        isRefreshing = state.refreshing,
        onRefresh = onRefresh,
        modifier = modifier.fillMaxSize(),
    ) {
        if (library == null) {
            // Only before the first read finishes, and only for as long as
            // opening two files takes. There is no "no documentation" state:
            // every install carries the guide in its assets.
            DocsMissing(refreshing = state.refreshing)
            return@LessonsPullToRefreshBox
        }
        HorizontalPager(
            state = pagerState,
            // The neighbours stay composed, so a swipe back to a section shows
            // it where it was left rather than at the top again — the same
            // reason the home tabs keep theirs.
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(),
        ) { index ->
            val page = library.guide.pages.getOrNull(index)
            if (page != null) {
                DocsPageContent(
                    page = page,
                    release = library.release,
                    failure = state.failure,
                    current = index == pagerState.currentPage,
                )
            }
        }
    }
}

/** One section of the guide, scrolling on its own. */
@Composable
private fun DocsPageContent(
    page: DocsGuidePage,
    release: DocsRelease,
    failure: DocsFailure?,
    current: Boolean,
) {
    val listState = rememberLazyListState()
    // Only the page in front reports where it is scrolled to. Its neighbours
    // are composed but not visible, and a bar that dimmed because the page to
    // the right happens to be scrolled would be reporting somebody else's.
    if (current) ReportScrollOffset(listState)

    val styles = rememberMarkupStyles()
    val runs = remember(page) { docsRuns(page.blocks) }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .appScrollMotionBlur(listState),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = statusBarSpace() + 8.dp,
            bottom = LocalBottomBarSpace.current,
        ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        item(key = "header") {
            ScreenHeader(title = page.title, subtitle = page.summary)
        }
        item(key = "release") {
            DocsReleaseCard(release = release, failure = failure)
        }
        // Keyed by position rather than by content: two pages may legitimately
        // quote the same string, and a key that repeated inside one list is a
        // crash rather than a cosmetic problem.
        itemsIndexed(
            items = runs,
            key = { index, _ -> index },
            contentType = { _, run -> docsContentType(run) },
        ) { _, run ->
            DocsRun(run = run, styles = styles)
        }
    }
}

/**
 * Which documentation this is, and whether it is the newest there is.
 *
 * On every page rather than only on the first, because the pages are swiped:
 * a reader who lands on the widget section from the toolbar would otherwise
 * never meet the one sentence that explains why the screen in front of them is
 * not the screen described.
 *
 * It states the version and the app version whatever happened — that is the
 * card's job — and adds a line about the copy only when there is something to
 * say about it. A banner that shouted "offline" at somebody who is simply up to
 * date would be the thing readers learn to ignore.
 */
@Composable
private fun DocsReleaseCard(release: DocsRelease, failure: DocsFailure?) {
    val version = correctedString(
        R.string.docs_release_version,
        release.version,
        release.updated,
        release.appVersion,
    )
    val note = when {
        failure == DocsFailure.OFFLINE -> correctedString(R.string.docs_release_offline)
        failure == DocsFailure.UNREADABLE -> correctedString(R.string.docs_release_unreadable)
        release.origin == DocsOrigin.BUNDLED -> correctedString(R.string.docs_release_bundled)
        else -> null
    }
    RoundedCardContainer {
        GroupRow(
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            verticalAlignment = Alignment.Top,
        ) {
            AccentIconTile(
                icon = if (failure == null) Icons.Rounded.MenuBook else Icons.Rounded.CloudOff,
                tone = accentTone(if (failure == null) 2 else 4),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = version,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (note != null) {
                    Text(
                        text = note,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Before the first read finishes, and in the state the build forgot the assets. */
@Composable
private fun DocsMissing(refreshing: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(ScreenPadding),
        contentAlignment = Alignment.Center,
    ) {
        if (!refreshing) {
            Text(
                text = correctedString(R.string.docs_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The blocks of a page, with consecutive steps gathered into single runs.
 *
 * Steps are the one block that is meaningless alone: "шаг 3" is a claim about
 * the two above it, and drawing each in a card of its own puts a gap where the
 * sequence is. Everything else stands on its own and stays one run of one.
 *
 * Pure, and separate from the drawing, because it is the only layout decision
 * on this screen that can be wrong in a way a reader would notice — and the
 * only one that can be checked without a device.
 */
internal fun docsRuns(blocks: List<DocsBlock>): List<List<DocsBlock>> {
    val runs = mutableListOf<List<DocsBlock>>()
    for (block in blocks) {
        val previous = runs.lastOrNull()
        if (block is DocsBlock.Step && previous?.last() is DocsBlock.Step) {
            runs[runs.lastIndex] = previous + block
        } else {
            runs += listOf(block)
        }
    }
    return runs
}

/**
 * Which of [DocsRun]'s four shapes a run will be drawn as.
 *
 * A lazy list reuses the slot a scrolled-off item left behind, and it picks the
 * slot by content type. Every item here is a different type by default —
 * `null` — so a paragraph, which is one `Text`, is offered the slot a card of
 * eight point rows has just vacated, and the whole subtree is thrown away and
 * built again. Naming the shape is what lets a paragraph land in a paragraph's
 * slot; the longest page is eleven blocks, which is enough scrolling for it to
 * be worth saying.
 *
 * It is a function rather than a `when` inlined at the call site because the
 * branches have to agree with [DocsRun]'s, and two `when`s over the same sealed
 * interface drift: a type that claims a shape the drawing does not use is worse
 * than no type at all, since the slot then arrives holding the wrong subtree.
 * `DocsContentTest` holds them level.
 */
internal fun docsContentType(run: List<DocsBlock>): String {
    if (run.size > 1 || run.first() is DocsBlock.Step) return "steps"
    return when (run.first()) {
        is DocsBlock.Paragraph -> "paragraph"
        is DocsBlock.Points -> "points"
        is DocsBlock.Note -> "note"
        // Unreachable: a lone step took the branch above, exactly as in DocsRun.
        is DocsBlock.Step -> "steps"
    }
}

/**
 * Which toolbar item reads as current, or `-1` for none.
 *
 * Trivial, and worth naming anyway: the shell asks the same question of the
 * tabs and of this screen, and the two answers must be the same *kind* of
 * answer — an index into the list the bar was handed. Writing it out is what
 * lets a test say that the documentation's selection tracks its page.
 */
fun docsToolbarSelection(pageIndex: Int, pageCount: Int): Int =
    if (pageIndex in 0 until pageCount) pageIndex else -1

/**
 * The whole table of contents, as the toolbar's items.
 *
 * Every page the guide has, in the order it was written: a bar that showed only
 * some of them would make "which sections are there" a question the reader has
 * to navigate to answer, and the bar is the only navigation this screen has.
 *
 * The labels come from the guide rather than from `values/`, which is the point
 * of fetching it: a section added to the documentation appears in the bar of an
 * app that was built before it was written.
 */
fun docsToolbarItems(
    pages: List<DocsGuidePage>,
    onOpen: (Int) -> Unit,
): List<ToolbarItem> = pages.mapIndexed { index, page ->
    ToolbarItem(
        icon = docsIcon(page.id),
        label = page.label,
        onClick = { onOpen(index) },
    )
}
