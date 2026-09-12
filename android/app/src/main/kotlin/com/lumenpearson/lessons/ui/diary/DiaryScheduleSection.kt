package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.HomeworkRow
import com.lumenpearson.lessons.core.designsystem.component.LessonRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.RowText
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.model.HomeworkItem
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday

/**
 * A week of the diary's timetable, with each day's homework under it.
 *
 * Shaped after `ui/week/`: the period label and its three controls in one row,
 * then a card per day. The difference is what a day is made of — this schedule
 * has homework attached to it, and the whole point of having it on the same
 * card is that "what is tomorrow" and "what is due tomorrow" are one question
 * asked twice.
 */
internal fun LazyListScope.diarySchedule(
    state: DiaryUiState,
    viewModel: DiaryViewModel,
) {
    item(key = "week-header") {
        DiaryWeekHeader(
            label = stringResource(
                R.string.week_range,
                state.weekStart.asDayMonth(),
                state.weekStart.plusDays(6).asDayMonth(),
            ),
            showThisWeek = state.canReturnToThisWeek,
            onThisWeek = viewModel::showCurrentWeek,
            onPrevious = viewModel::showPreviousWeek,
            onNext = viewModel::showNextWeek,
        )
    }

    when {
        state.scheduleLoading -> item(key = "schedule-loading") { SkeletonGroup(rows = 4) }

        state.scheduleError != null -> item(key = "schedule-error") {
            DiaryFailureCard(state.scheduleError, viewModel::retry)
        }

        state.days.isEmpty() -> item(key = "schedule-empty") {
            EmptyState(
                title = stringResource(R.string.diary_schedule_empty_title),
                description = stringResource(R.string.diary_schedule_empty_text),
            )
        }

        else -> state.days.forEach { day ->
            item(key = "day-${day.date}") {
                DiaryDayCard(day = day, isToday = day.date == state.today)
            }
        }
    }
}

/** The week label, and the three controls that move it. */
@Composable
private fun DiaryWeekHeader(
    label: String,
    showThisWeek: Boolean,
    onThisWeek: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SectionHeader(
            title = label,
            modifier = Modifier.weight(1f),
            titleColor = MaterialTheme.colorScheme.onSurface,
        )
        // Offered only when it would move something, as on the class calendar.
        if (showThisWeek) {
            IconButton(onClick = onThisWeek) {
                Icon(
                    imageVector = Icons.Rounded.Today,
                    contentDescription = stringResource(R.string.week_current),
                )
            }
        }
        IconButton(onClick = onPrevious) {
            Icon(
                imageVector = Icons.Rounded.ChevronLeft,
                contentDescription = stringResource(R.string.week_previous),
            )
        }
        IconButton(onClick = onNext) {
            Icon(
                imageVector = Icons.Rounded.ChevronRight,
                contentDescription = stringResource(R.string.week_next),
            )
        }
    }
}

/** One date: its lessons, then whatever is due on it. */
@Composable
private fun DiaryDayCard(
    day: DiaryDayUi,
    isToday: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(
            title = day.date.asFullWeekday().replaceFirstChar { it.uppercase() },
            subtitle = day.date.asDayMonth(),
            titleColor = if (isToday) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        RoundedCardContainer {
            day.lessons.forEach { lesson ->
                val timed = lesson.asTimedLesson()
                if (timed != null) {
                    LessonRow(lesson = timed)
                } else {
                    // The diary publishes lessons with no bell time often
                    // enough that dropping them would misreport the day, and
                    // `LessonRow` cannot draw one: its whole meta line is the
                    // time range. So this row says what is known and says the
                    // time is not.
                    UntimedLessonRow(lesson = lesson)
                }
            }

            if (day.homework.isNotEmpty()) {
                GroupRow {
                    Text(
                        text = stringResource(R.string.diary_homework_section),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                day.homework.forEach { item ->
                    HomeworkRow(
                        item = HomeworkItem(subject = item.subject, text = item.text),
                    )
                }
            }
        }
    }
}

/**
 * The diary's lesson as the design system's, when it can be one.
 *
 * `null` when the bell times are missing, which is the only thing
 * [LessonRow] cannot do without. Everything else degrades quietly: no number
 * becomes a zero in the tile, and no room or teacher simply shortens the meta
 * line — exactly as it does for the class timetable.
 */
@Composable
private fun DiaryLesson.asTimedLesson(): Lesson? {
    val from = startsAt ?: return null
    val to = endsAt ?: return null
    return Lesson(
        index = number ?: 0,
        subject = subject,
        startsAt = from,
        endsAt = to,
        room = room,
        teacher = teacher,
        // The row draws `note` as its second line, which is where the topic of
        // the lesson belongs — labelled, because "Квадратные уравнения" on its
        // own under a subject could be anything.
        note = topic?.let { stringResource(R.string.diary_lesson_topic, it) },
    )
}

/** A lesson the diary published without a time. */
@Composable
private fun UntimedLessonRow(
    lesson: DiaryLesson,
    modifier: Modifier = Modifier,
) {
    GroupRow(modifier = modifier) {
        AccentIconTile(
            icon = Icons.AutoMirrored.Rounded.MenuBook,
            tone = subjectTone(lesson.subject),
        )
        RowText(
            title = lesson.subject,
            modifier = Modifier.weight(1f),
            subtitle = listOfNotNull(
                stringResource(R.string.diary_lesson_no_time),
                lesson.room?.let { stringResource(R.string.diary_lesson_room, it) },
                lesson.teacher,
            ).joinToString(" · "),
        )
    }
}
