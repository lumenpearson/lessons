package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.model.DayFilter

/**
 * The four facets, as chips over whatever the calendar is drawing.
 *
 * Horizontally scrollable rather than wrapped onto two rows: four Russian
 * labels do not fit one phone width, and a row that grows taller when a filter
 * is on moves the grid under the reader's thumb at the moment they press.
 *
 * «Сбросить» appears only when something is on. A permanent clear button on a
 * calendar nobody has filtered is a button that does nothing, and the row is
 * already competing with the grid for attention.
 */
@Composable
internal fun FilterChips(
    active: Set<DayFilter>,
    onToggle: (DayFilter) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = ScreenPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DayFilter.entries.forEach { filter ->
            PillChip(
                text = filter.asLabel(),
                selected = filter in active,
                onClick = { onToggle(filter) },
            )
        }
        if (active.isNotEmpty()) {
            PillChip(text = correctedString(R.string.calendar_filter_clear), onClick = onClear)
        }
    }
}

/** What each facet is called, out of resources so both languages have it. */
@Composable
internal fun DayFilter.asLabel(): String = correctedString(
    when (this) {
        DayFilter.HAS_LESSONS -> R.string.calendar_filter_lessons
        DayFilter.HAS_HOMEWORK -> R.string.calendar_filter_homework
        DayFilter.HAS_EVENTS -> R.string.calendar_filter_events
        DayFilter.MARKED -> R.string.calendar_filter_marked
    },
)
