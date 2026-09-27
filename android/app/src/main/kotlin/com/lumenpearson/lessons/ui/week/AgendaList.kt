package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.DayOrder
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.ui.common.asShortWeekday
import java.time.LocalDate

/**
 * A month as a list, and the only view an order can apply to.
 *
 * Filtered days are dropped here rather than dimmed, which is the opposite of
 * the grid and right for the same reason: a list has no shape to preserve, so
 * a day that does not match is a row worth not drawing. The grid keeps its
 * holes filled because a month with gaps stops lining up with its own header.
 *
 * Empty is a real answer and says which kind it is — a month with nothing in
 * it at all reads differently from one where the filter is hiding everything,
 * and telling somebody «ничего не найдено» when they have narrowed to «с ДЗ»
 * in July would be blaming the month for the chip.
 *
 * Four kinds, not two, and for a long time it drew one. The same distinction
 * the month grid and the ribbon already make: an empty list here is a year
 * nobody has asked for, a year on its way, a month the filters have emptied,
 * or a month that is genuinely blank — and the one sentence it had blamed the
 * chips for all four, including a fresh install with no filter set at all.
 * `days` cannot tell them apart on its own, which is why the other three
 * arrive beside it.
 */
@Composable
internal fun AgendaList(
    days: List<SchoolDay>,
    today: LocalDate,
    selected: LocalDate,
    order: DayOrder,
    /** The chips currently narrowing the month; empty is nobody's fault. */
    filters: Set<DayFilter>,
    /** Whether the month's school year is in the cache at all. */
    isFetched: Boolean,
    /** Whether a fetch of it is in flight right now. */
    loadingYear: Boolean,
    /** «2026/27», for the sentence that names the year being waited on. */
    yearName: String,
    onOrder: (DayOrder) -> Unit,
    onOpen: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DayOrder.entries.forEach { candidate ->
                PillChip(
                    text = candidate.asLabel(),
                    selected = candidate == order,
                    onClick = { onOrder(candidate) },
                )
            }
        }

        if (days.isEmpty()) {
            EmptyState(
                title = correctedString(
                    when {
                        !isFetched && loadingYear -> R.string.week_year_loading_title
                        !isFetched -> R.string.week_year_missing_title
                        filters.isEmpty() -> R.string.calendar_agenda_blank_title
                        else -> R.string.calendar_agenda_empty_title
                    },
                ),
                description = if (!isFetched) {
                    correctedString(
                        if (loadingYear) {
                            R.string.week_year_loading_description
                        } else {
                            R.string.week_year_missing_description
                        },
                        yearName,
                    )
                } else {
                    correctedString(
                        if (filters.isEmpty()) {
                            R.string.calendar_agenda_blank_description
                        } else {
                            R.string.calendar_agenda_empty_description
                        },
                    )
                },
            )
            return@Column
        }

        days.forEach { day ->
            AgendaRow(
                day = day,
                isToday = day.date == today,
                isSelected = day.date == selected,
                onClick = { onOpen(day.date) },
            )
        }
    }
}

/** One day of the agenda: what it is, and how much of it there is. */
@Composable
private fun AgendaRow(
    day: SchoolDay,
    isToday: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val accent = day.agendaAccent()
    val accentColors = accent.colors()
    val container = when {
        isToday -> scheme.secondaryContainer
        isSelected -> scheme.rowContainer
        else -> accentColors.container
    }
    val content = when {
        isToday -> scheme.onSecondaryContainer
        isSelected -> scheme.onSurface
        else -> accentColors.content
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(LessonsShapeTokens.Row)
            .background(container)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${day.date.dayOfMonth} · ${day.date.asShortWeekday()}",
                style = MaterialTheme.typography.titleSmall.emphasised(isToday),
                color = content,
            )
            val subtitle = day.holiday?.title
                ?: day.kind.takeIf { it != DayKind.NORMAL }?.asLabel()
                ?: day.offReason.takeIf { !day.hasLessons }?.asEmptyDescription()
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = content.copy(alpha = 0.75f),
                )
            }
        }
        Text(
            text = pluralStringResource(
                R.plurals.lessons_count,
                day.activeLessons.size,
                day.activeLessons.size,
            ),
            style = MaterialTheme.typography.labelMedium,
            color = content.copy(alpha = 0.75f),
        )
    }
}

/** The accent an agenda row carries — the same vocabulary the grid uses. */
private fun SchoolDay.agendaAccent(): DayAccent = WeekDayUi(
    date = date,
    day = this,
    isToday = false,
).accent()

/** What each order is called, out of resources so both languages have it. */
@Composable
internal fun DayOrder.asLabel(): String = correctedString(
    when (this) {
        DayOrder.DATE_ASC -> R.string.calendar_order_date_asc
        DayOrder.DATE_DESC -> R.string.calendar_order_date_desc
        DayOrder.BUSIEST_FIRST -> R.string.calendar_order_busiest
    },
)
