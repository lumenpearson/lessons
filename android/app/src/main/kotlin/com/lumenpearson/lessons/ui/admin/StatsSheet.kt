package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.QueryStats
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import java.time.format.DateTimeFormatter

/**
 * «📊 Статистика»: the numbers, without the sentence around them.
 *
 * The same figures the bot prints, in the same order, because the two are read
 * side by side and a different order would read as a different answer. Halves
 * are kept — a lesson that alternates weeks counts as half — which is how a
 * school's own paperwork writes «часов в неделю».
 */
@Composable
fun StatsSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.stats.value

    ManagementSheet(
        title = stringResource(R.string.admin_stats_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when {
            stats == null && state.stats.loading -> SkeletonGroup(
                modifier = Modifier.padding(horizontal = ScreenPadding),
                rows = 5,
            )

            stats == null -> ManagementFailureCard(
                failure = state.stats.failure,
                modifier = Modifier.padding(horizontal = ScreenPadding),
                onRetry = viewModel::loadStats,
            )

            else -> StatsBody(stats)
        }
    }
}

@Composable
private fun StatsBody(stats: ClassStats) {
    stats.today?.let { SheetNote(text = stringResource(R.string.admin_stats_today, it.format(DayFormatter))) }

    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        NumberRow(
            title = stringResource(R.string.admin_stats_lessons_per_week),
            value = stats.lessonsPerWeek.asHours(),
            icon = Icons.Rounded.Schedule,
            tone = 0,
        )
        NumberRow(
            title = stringResource(R.string.admin_stats_subjects_count),
            value = stats.subjectsCount.toString(),
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            tone = 1,
        )
        NumberRow(
            title = stringResource(R.string.admin_stats_homework),
            value = stringResource(
                R.string.admin_stats_homework_value,
                stats.homeworkOpen,
                stats.homeworkTotal,
            ),
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            tone = 2,
        )
        NumberRow(
            title = stringResource(R.string.admin_stats_devices),
            value = stats.devicesActive.toString(),
            icon = Icons.Rounded.PhoneAndroid,
            tone = 3,
        )
        NumberRow(
            title = stringResource(R.string.admin_stats_overrides),
            value = stats.overridesUpcoming.toString(),
            icon = Icons.Rounded.CalendarMonth,
            tone = 4,
        )
        NumberRow(
            title = stringResource(R.string.admin_stats_events),
            value = stats.eventsUpcoming.toString(),
            icon = Icons.Rounded.Event,
            tone = 5,
        )
    }

    if (stats.membersByRole.isNotEmpty()) {
        SheetSection(title = stringResource(R.string.admin_stats_members))
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            // Strongest first, which is the order the bot lists them in and the
            // order somebody looking for "who can delete my class" reads.
            stats.membersByRole.entries.sortedByDescending { it.key.ordinal }.forEach { (role, count) ->
                NumberRow(
                    title = role.roleName(),
                    value = count.toString(),
                    icon = Icons.Rounded.Groups,
                    tone = role.ordinal,
                )
            }
        }
    }

    if (stats.subjects.isEmpty()) {
        EmptyState(
            title = stringResource(R.string.admin_stats_empty_title),
            description = stringResource(R.string.admin_stats_empty_text),
            icon = Icons.Rounded.QueryStats,
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )
    } else {
        SheetSection(title = stringResource(R.string.admin_stats_hours_section))
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            stats.subjects.forEach { subject ->
                GroupItem(
                    title = subject.name,
                    tone = subjectTone(subject.name),
                    trailing = {
                        Text(
                            text = stringResource(
                                R.string.admin_stats_hours,
                                subject.hours.asHours(),
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun NumberRow(
    title: String,
    value: String,
    icon: ImageVector,
    tone: Int,
) {
    GroupItem(
        title = title,
        icon = icon,
        tone = accentTone(tone),
        trailing = {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
    )
}

/**
 * "4" for four, "4,5" for four and a half.
 *
 * A whole number keeps no decimal point: «4,0 ч» reads as a measurement and
 * these are counted lessons. The half is the only fraction the server can send.
 */
private fun Double.asHours(): String =
    if (this % 1.0 == 0.0) toInt().toString() else "%.1f".format(this)

/** "12 сентября", declined by java.time as everywhere else in the app. */
private val DayFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM")
