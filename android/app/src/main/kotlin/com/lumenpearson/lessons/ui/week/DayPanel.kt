package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.LessonGroup
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.model.DayKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolYear
import com.lumenpearson.lessons.core.model.DayOffReason
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import java.time.LocalDate

/** The detail under the week strip or the month grid. */
@Composable
internal fun DayPanel(
    day: WeekDayUi?,
    date: LocalDate,
    /** Whether a fetch of this date's school year is in flight right now. */
    loadingYear: Boolean,
    showTeacher: Boolean,
    showEvents: Boolean,
    showHomework: Boolean,
    onLessonClick: (Lesson) -> Unit,
    onOpenDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val schoolDay = day?.day
    val lessons = schoolDay?.activeLessons.orEmpty()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        SectionHeader(
            title = "${date.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
                date.asDayMonth(),
            subtitle = schoolDay?.let {
                pluralStringResource(
                    R.plurals.lessons_count,
                    it.activeLessons.size,
                    it.activeLessons.size,
                )
            },
            actionLabel = schoolDay?.let { correctedString(R.string.schedule_day_details) },
            onActionClick = schoolDay?.let { { onOpenDay() } },
        )

        DayChips(day = day, date = date)

        when {
            // Three ways to be empty, and they were one until the cache learned
            // to hold more than a year. «Нет данных» about a year nobody has
            // asked for is a timetable that looks like it stops; «загружаю» is
            // the same screen with the truth on it.
            schoolDay == null && day?.isFetched == false -> EmptyState(
                title = correctedString(
                    if (loadingYear) {
                        R.string.week_year_loading_title
                    } else {
                        R.string.week_year_missing_title
                    },
                ),
                description = correctedString(
                    if (loadingYear) {
                        R.string.week_year_loading_description
                    } else {
                        R.string.week_year_missing_description
                    },
                    yearLabel(SchoolYear.openingYearOf(date)),
                ),
            )

            schoolDay == null -> EmptyState(
                title = correctedString(R.string.week_no_data_title),
                description = correctedString(R.string.week_no_data_description),
            )

            lessons.isEmpty() -> EmptyState(
                title = correctedString(R.string.week_day_off_title),
                description = schoolDay.offReason.asEmptyDescription(),
            )

            else -> LessonGroup(
                lessons = lessons,
                now = null,
                showTeacher = showTeacher,
                onLessonClick = onLessonClick,
            )
        }

        DayExtras(
            day = schoolDay,
            showEvents = showEvents,
            showHomework = showHomework,
        )
    }
}

/** Today / kind / note, as a row of pills. */
@Composable
internal fun DayChips(
    day: WeekDayUi?,
    date: LocalDate,
    modifier: Modifier = Modifier,
) {
    val kind = day?.day?.kind?.takeIf { it != DayKind.NORMAL }
    val note = day?.day?.note
    val holiday = day?.day?.holiday
    val isToday = day?.isToday == true
    if (!isToday && kind == null && note == null && holiday == null) return

    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (isToday) {
            PillChip(
                text = correctedString(R.string.day_today),
                selected = true,
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )
        }
        if (holiday != null) {
            // The server's own words. A date this build has never heard of
            // still has a name, which is the whole reason the title travels
            // beside the code rather than the code travelling alone.
            PillChip(
                text = holiday.title,
                containerColor = if (holiday.stopsLessons) {
                    MaterialTheme.colorScheme.errorContainer
                } else {
                    MaterialTheme.colorScheme.tertiaryContainer
                },
                contentColor = if (holiday.stopsLessons) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onTertiaryContainer
                },
            )
        }
        if (kind != null) PillChip(text = kind.asLabel())
        if (note != null) PillChip(text = note)
    }
}

/**
 * Why this day is empty, in words somebody can act on.
 *
 * «Уроков нет» is true of four different days and useful on one. A blank
 * Tuesday in November is the holidays, a public holiday, or a timetable with
 * nothing on it, and those are three different things to do next.
 */
@Composable
internal fun DayOffReason?.asEmptyDescription(): String = correctedString(
    when (this) {
        DayOffReason.OUT_OF_YEAR -> R.string.week_day_off_year_over
        DayOffReason.BETWEEN_TERMS -> R.string.week_day_off_between_terms
        DayOffReason.PUBLIC_HOLIDAY -> R.string.week_day_off_public_holiday
        null -> R.string.week_day_off_description
    },
)

/** Localized name of a non-normal day kind. */
@Composable
internal fun DayKind.asLabel(): String = correctedString(
    when (this) {
        DayKind.NORMAL -> R.string.day_kind_normal
        DayKind.HOLIDAY -> R.string.day_kind_holiday
        DayKind.SHORTENED -> R.string.day_kind_shortened
        DayKind.REMOTE -> R.string.day_kind_remote
        DayKind.SELF_STUDY -> R.string.day_kind_self_study
        DayKind.DAY_OFF -> R.string.day_kind_day_off
    },
)
