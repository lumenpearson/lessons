package com.lumenpearson.lessons.ui.docs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.AdminPanelSettings
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.ViewAgenda
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.AccentTile
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace

/**
 * One page of the documentation.
 *
 * Deliberately the same shape as a settings page: no `Scaffold`, no top app
 * bar, the status-bar inset inside the list's content padding so the first
 * words scroll up under the softened strip, and the page's name repeated in the
 * pill at the bottom. The documentation is not a special place in the app, and
 * a screen that looked like one would be the first thing a reader had to learn
 * about instead of reading.
 *
 * There is no navigation here at all — not a row, not a "next page" button.
 * Every way between pages is the floating toolbar, which is the point of the
 * arrangement: one bar, always under the thumb, carrying the whole table of
 * contents.
 */
@Composable
fun DocsScreen(
    page: DocsPage,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)

    LazyColumn(
        state = listState,
        modifier = modifier
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
            ScreenHeader(
                title = correctedString(page.titleRes),
                subtitle = correctedString(page.summaryRes),
            )
        }
        // Keyed by position rather than by content: two pages may legitimately
        // quote the same string, and a key that repeated inside one list is a
        // crash rather than a cosmetic problem.
        itemsIndexed(
            items = docsRuns(page.blocks),
            key = { index, _ -> index },
            contentType = { _, run -> docsContentType(run) },
        ) { _, run ->
            DocsRun(run)
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
 * slot; the longest page is forty-seven blocks, which is enough scrolling for
 * it to be worth saying.
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

/** One run: either a stack of steps in one container, or a single block. */
@Composable
private fun DocsRun(run: List<DocsBlock>) {
    if (run.size > 1 || run.first() is DocsBlock.Step) {
        RoundedCardContainer {
            run.forEachIndexed { index, block ->
                val step = block as DocsBlock.Step
                StepRow(step = step, tone = index)
            }
        }
        return
    }

    when (val block = run.first()) {
        is DocsBlock.Paragraph -> DocsParagraph(block.textRes)
        is DocsBlock.Points -> DocsPoints(block.itemsRes)
        is DocsBlock.Note -> DocsNote(block.textRes)
        // Unreachable: a lone step took the branch above.
        is DocsBlock.Step -> Unit
    }
}

/**
 * Prose, on the page itself rather than in a container.
 *
 * A paragraph in a card reads as a row of a list, which is what every other
 * container in this app is; the documentation is the one screen whose default
 * content is text, and the text should look like text. The 8 dp inset is
 * [ScreenHeader]'s own, so the first line of a paragraph starts under the first
 * letter of the heading.
 */
@Composable
private fun DocsParagraph(textRes: Int) {
    Text(
        text = correctedString(textRes),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
    )
}

/** Several peers, each on a row of the group, marked by a coloured dot. */
@Composable
private fun DocsPoints(itemsRes: List<Int>) {
    RoundedCardContainer {
        itemsRes.forEachIndexed { index, textRes ->
            GroupRow(verticalAlignment = Alignment.Top) {
                // A dot rather than the 40 dp icon tile every settings row has:
                // the points of a list are not eight different things needing
                // eight different glyphs, and a column of tiles would be the
                // loudest part of a page made of sentences. The hue still
                // walks the accent slots, which is what makes a point findable
                // again after it has been read once.
                AccentTile(
                    tone = accentTone(index),
                    size = DotSize,
                    modifier = Modifier.padding(top = DotOffset),
                ) {}
                Text(
                    text = correctedString(textRes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * The sentence a skimmer must not skim past.
 *
 * Given a container and a glyph precisely because the prose around it has
 * neither: on a page of unadorned paragraphs, a card is the loudest thing
 * available and costs nothing to read.
 */
@Composable
private fun DocsNote(textRes: Int) {
    RoundedCardContainer {
        GroupRow(
            container = MaterialTheme.colorScheme.surfaceContainerHigh,
            verticalAlignment = Alignment.Top,
        ) {
            AccentIconTile(icon = Icons.Rounded.Info, tone = accentTone(5))
            Text(
                text = correctedString(textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** One numbered stage: the number on the tile, then its own title and prose. */
@Composable
private fun StepRow(step: DocsBlock.Step, tone: Int) {
    GroupRow(verticalAlignment = Alignment.Top) {
        AccentTile(tone = accentTone(tone)) {
            Text(
                text = step.number.toString(),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = accentTone(tone).content,
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = correctedString(step.titleRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = correctedString(step.textRes),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Diameter of a point's dot, and how far down the row it sits to meet the first line. */
private val DotSize = 10.dp
private val DotOffset = 5.dp

/**
 * The glyph a page carries in the toolbar.
 *
 * Here and not on [DocsPage] so that the enum stays free of Compose; see its
 * own note. Each icon is the one the same subject already wears elsewhere in
 * the app — the diary's book, the notification bell, the badge of the
 * management page — because a reader who has seen the settings list has
 * already learned them.
 */
internal val DocsPage.icon: ImageVector
    get() = when (this) {
        DocsPage.START -> Icons.AutoMirrored.Rounded.Login
        DocsPage.TABS -> Icons.Rounded.ViewAgenda
        DocsPage.WIDGET -> Icons.Rounded.Widgets
        DocsPage.ALERTS -> Icons.Rounded.NotificationsActive
        DocsPage.LANGUAGE -> Icons.Rounded.Language
        DocsPage.TELEGRAM -> Icons.Rounded.Link
        DocsPage.DIARY -> Icons.AutoMirrored.Rounded.MenuBook
        DocsPage.ADMIN -> Icons.Rounded.AdminPanelSettings
    }

/**
 * Which toolbar item reads as current, or `-1` for none.
 *
 * Trivial, and worth naming anyway: the shell asks the same question of the
 * tabs and of this screen, and the two answers must be the same *kind* of
 * answer — an index into the list the bar was handed. Writing it out is what
 * lets a test say that the documentation's selection tracks its page.
 */
fun docsToolbarSelection(page: DocsPage?): Int =
    page?.let { DocsPage.entries.indexOf(it) } ?: -1

/**
 * The whole table of contents, as the toolbar's items.
 *
 * Every page, always, in declaration order: a bar that showed only some of
 * them would make "which sections are there" a question the reader has to
 * navigate to answer, and the bar is the only navigation this screen has.
 */
@Composable
fun docsToolbarItems(onOpen: (DocsPage) -> Unit): List<ToolbarItem> =
    DocsPage.entries.map { page ->
        ToolbarItem(
            icon = page.icon,
            label = correctedString(page.labelRes),
            onClick = { onOpen(page) },
        )
    }
