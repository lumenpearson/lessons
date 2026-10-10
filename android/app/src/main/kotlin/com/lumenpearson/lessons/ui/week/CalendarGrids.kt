package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CollectionInfo
import androidx.compose.ui.semantics.collectionInfo
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.InlineGap
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.DayOffReason
import com.lumenpearson.lessons.ui.common.asShortWeekday
import java.time.LocalDate

/**
 * The week strip.
 *
 * When the days fit — every tile at least [WeekdayTileMinWidth] wide, with
 * [InlineGap] between them, inside the screen's padding — they share the
 * width equally, so the strip ends on the same edge as the switcher and the
 * chips above it whatever the language and however many days are drawn.
 * Five days fit any phone. Seven need 7 × 48 + 6 × 8 = 384 dp between the
 * margins, a window of 416, so on most phones a full week does not fit and
 * the strip scrolls as it always has: its last tile reaches the edge once it
 * is scrolled to its end, and the scroll-to below brings the selected day on
 * screen, which without it could not be seen at all. Where the overflow is a
 * sliver — 5 dp at 411 — a selection past the first day parks the strip at its
 * end, so its right edge is on the line and its first tile starts that far
 * into the left margin.
 *
 * The width comes from [onSizeChanged] rather than a `BoxWithConstraints`,
 * which is a `SubcomposeLayout` and cannot answer an intrinsic measurement —
 * the crash `docs/design.md` records under «Nothing is cut off, and nothing
 * subcomposes to find out how wide it is». The price is the first frame,
 * which is drawn as the scrolling strip before the width is known.
 */
@Composable
internal fun WeekdaySelector(
    days: List<WeekDayUi>,
    selected: LocalDate,
    showLoad: Boolean,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val fits = widthPx > 0 && with(density) {
        val needed = WeekdayTileMinWidth * days.size + InlineGap * (days.size - 1)
        needed.roundToPx() <= widthPx - (ScreenPadding * 2).roundToPx()
    }

    Box(modifier = modifier.fillMaxWidth().onSizeChanged { widthPx = it.width }) {
        if (fits) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = ScreenPadding)
                    // What the LazyRow tells a screen reader about itself,
                    // kept: a group to move through, a row of n days. The
                    // scroll semantics it would add have nothing to scroll.
                    .semantics {
                        isTraversalGroup = true
                        collectionInfo = CollectionInfo(rowCount = 1, columnCount = days.size)
                    },
                horizontalArrangement = Arrangement.spacedBy(InlineGap),
            ) {
                days.forEach { day ->
                    key(day.date) {
                        WeekdayTile(
                            day = day,
                            selected = day.date == selected,
                            showLoad = showLoad,
                            onClick = { onSelect(day.date) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        } else {
            ScrollingWeekStrip(days = days, selected = selected, showLoad = showLoad, onSelect = onSelect)
        }
    }
}

/**
 * The strip when its days do not fit: a [LazyRow] that brings the selected day
 * on screen.
 *
 * Its own composable so that the scroll-to belongs to the list it scrolls, and
 * is cancelled with it when the width turns out to fit after all.
 */
@Composable
private fun ScrollingWeekStrip(
    days: List<WeekDayUi>,
    selected: LocalDate,
    showLoad: Boolean,
    onSelect: (LocalDate) -> Unit,
) {
    val listState = rememberLazyListState()
    val selectedIndex = days.indexOfFirst { it.date == selected }
    LaunchedEffect(selectedIndex, days.size) {
        if (selectedIndex >= 0) listState.animateScrollToItem(selectedIndex)
    }

    LazyRow(
        state = listState,
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(InlineGap),
    ) {
        itemsIndexed(items = days, key = { _, day -> day.date.toString() }) { _, day ->
            WeekdayTile(
                day = day,
                selected = day.date == selected,
                showLoad = showLoad,
                onClick = { onSelect(day.date) },
            )
        }
    }
}

/**
 * The narrowest a weekday tile is drawn: Material's 48 dp touch target, so the
 * tile a finger sees is the tile it presses. At 12 dp a side a date draws 42
 * to 44 dp wide in Russian, and its target was Compose's own reach into the gaps
 * beside it rather than the tile.
 */
private val WeekdayTileMinWidth: Dp = 48.dp

/**
 * One day of the strip: the weekday over the date, in a rounded tile.
 *
 * Two lines rather than one chip because "чт" alone is ambiguous the moment the
 * user steps away from the current week.
 */
@Composable
private fun WeekdayTile(
    day: WeekDayUi,
    selected: Boolean,
    showLoad: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val container = if (selected) scheme.primary else scheme.rowContainer
    val content = if (selected) scheme.onPrimary else scheme.onSurfaceVariant
    val weekday = day.date.asShortWeekday()
    val dayOfMonth = day.date.dayOfMonth.toString()
    val lessonCount = if (showLoad) day.day?.activeLessons?.size ?: 0 else 0
    val isToday = day.isToday

    Column(
        modifier = modifier
            .widthIn(min = WeekdayTileMinWidth)
            .clip(LessonsShapeTokens.Cell)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = weekday,
            style = MaterialTheme.typography.labelSmall,
            color = content,
        )
        Text(
            text = dayOfMonth,
            // Bold for the day in view and for today, which has no fill of its
            // own in the strip and would otherwise carry no mark at all.
            style = MaterialTheme.typography.titleMedium.emphasised(selected || isToday),
            color = if (selected) scheme.onPrimary else scheme.onSurface,
        )
        LoadDots(
            count = lessonCount,
            color = if (selected) scheme.onPrimary else scheme.primary,
        )
    }
}

/**
 * How busy a day is, as up to three dots.
 *
 * A number would be read; dots are seen. Three is the cap because the difference
 * that matters in a grid is none / a few / a full day, and a fourth dot only
 * makes the cell taller.
 */
@Composable
private fun LoadDots(
    count: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val dots = count.coerceAtMost(MaxLoadDots)
    Row(
        modifier = modifier.height(DotSize),
        // 4, not the 3 this was: on the grid.
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(dots) {
            Box(
                modifier = Modifier
                    .size(DotSize)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(color),
            )
        }
    }
}

private const val MaxLoadDots = 3
private val DotSize: Dp = 4.dp

/**
 * A month as a seven-column grid.
 *
 * The corners are filled with the neighbouring months' days rather than left
 * blank: a grid that starts mid-row is harder to read than one that does not,
 * and a blank cell is a cell nobody can tap. They are drawn faint, and tapping
 * one steps the month rather than selecting a date the grid does not own.
 *
 * A tap selects; a second tap on the same day opens its sheet. One gesture for
 * "show me this day below" and one for "show me everything", which is what the
 * month view is for — the grid itself has room for a number and three dots.
 */
@Composable
internal fun MonthGrid(
    days: List<WeekDayUi>,
    selected: LocalDate,
    showLoad: Boolean,
    onSelect: (LocalDate) -> Unit,
    onOpen: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    // Computed over the whole month rather than per row, so a stretch that
    // wraps from Sunday to Monday is one run and draws as one bar.
    val accents = remember(days) { days.map { it.accent() } }
    val runs = remember(accents) { accents.runPositions() }
    val weeks = remember(days) { days.indices.toList().chunked(DaysPerRow) }

    // A whole month with nothing in it is a month somebody scrolls past, and
    // sixty faintly-tinted cells do not say what they are. The label does.
    // Only when *every* day of the month agrees: one September day at the
    // bottom of an August grid means the year has started, and writing
    // «Летние каникулы» across it would be wrong about the day that matters.
    val wholeMonthOff = remember(days, accents) {
        val inMonth = days.indices.filter { days[it].inPeriod }
        inMonth.isNotEmpty() && inMonth.all {
            accents[it] == DayAccent.OUT_OF_YEAR || accents[it] == DayAccent.BETWEEN_TERMS
        }
    }
    val bannerText = when {
        !wholeMonthOff -> null
        days.any { it.inPeriod && it.day?.offReason == DayOffReason.OUT_OF_YEAR } ->
            correctedString(R.string.calendar_year_over)
        else -> correctedString(R.string.calendar_between_terms)
    }

    Box(modifier = modifier.fillMaxWidth()) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            days.take(DaysPerRow).forEach { day ->
                Text(
                    text = day.date.asShortWeekday(),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.outline,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        weeks.forEach { week ->
            // Zero spacing inside a run, so the cells of one stretch touch and
            // read as a single bar; the 4.dp gap is drawn by the cell instead,
            // on the sides where its run actually ends.
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                week.forEach { index ->
                    val day = days[index]
                    MonthCell(
                        day = day,
                        accent = accents[index],
                        run = runs[index],
                        selected = day.date == selected,
                        showLoad = showLoad,
                        onClick = {
                            if (day.date == selected) onOpen(day.date) else onSelect(day.date)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

        if (bannerText != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = ScreenPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = bannerText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        // Cell, not Row: this banner is a standalone label
                        // over the grid, not a row inside a group — the same
                        // reasoning decision 2 gives the weekday tile.
                        .clip(LessonsShapeTokens.Cell)
                        .background(MaterialTheme.colorScheme.tertiaryContainer)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private const val DaysPerRow = 7

/** One cell of the month grid. */
@Composable
private fun MonthCell(
    day: WeekDayUi,
    accent: DayAccent,
    run: RunPosition,
    selected: Boolean,
    showLoad: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val accentColors = accent.colors()
    val container = when {
        selected -> scheme.primary
        day.isToday -> scheme.secondaryContainer
        day.inPeriod -> accentColors.container
        else -> Color.Transparent
    }
    val content = when {
        selected -> scheme.onPrimary
        day.isToday -> scheme.onSecondaryContainer
        day.inPeriod -> accentColors.content
        else -> scheme.outline
    }
    // A selected day is its own shape: it is one cell being pointed at, and
    // squaring its corners to join a run would lose the one thing the
    // selection is for. Cell, the same standalone-cell radius the month grid
    // and the weekday tile both read elsewhere — a selected day is a cell on
    // its own by definition, not a 4 dp row.
    val shape = if (selected || day.isToday) LessonsShapeTokens.Cell else run.shape()

    // Filtered out: dimmed, never removed. A grid with holes in it stops
    // lining up with its own weekday header. Today and the selection keep
    // their full weight either way — the filter narrows what is interesting,
    // it does not move where you are.
    val dimmed = !day.matchesFilters && !selected && !day.isToday

    Column(
        modifier = modifier
            // The gap the Row used to space with, moved here so it can be left
            // out between two cells of the same run.
            .padding(
                start = if (run.first || selected || day.isToday) 2.dp else 0.dp,
                end = if (run.last || selected || day.isToday) 2.dp else 0.dp,
            )
            .clip(shape)
            .background(if (dimmed) container.copy(alpha = 0.25f) else container)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium.emphasised(selected || day.isToday),
            color = if (dimmed) content.copy(alpha = 0.38f) else content,
        )
        LoadDots(
            count = if (showLoad) day.day?.activeLessons?.size ?: 0 else 0,
            color = when {
                selected -> scheme.onPrimary
                dimmed -> scheme.primary.copy(alpha = 0.3f)
                else -> scheme.primary
            },
        )
    }
}
