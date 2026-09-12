package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Notes
import androidx.compose.material.icons.rounded.EventBusy
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryMarkKind
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.RowText
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.subjectTone
import com.lumenpearson.lessons.ui.common.asDayMonth
import java.util.Locale

/**
 * The register, one card per subject.
 *
 * Two things make this screen different from a list of numbers. The average is
 * computed over the marks that are numbers and nothing else, so a term full of
 * absences does not read as a term of zeroes; and the entries that are not
 * marks — absences, lateness, remarks — are drawn as rows that say what they
 * are, with their date and their reason, instead of as more chips in the row of
 * marks. Both rules live in `DiaryPresentation`, where they are tested; this
 * file only draws the answer.
 */
internal fun LazyListScope.diaryGrades(
    state: DiaryUiState,
    viewModel: DiaryViewModel,
) {
    val range = state.gradeRange
    if (range != null) {
        item(key = "grades-range") {
            SectionHeader(
                title = stringResource(
                    R.string.diary_grades_range,
                    range.from.asDayMonth(),
                    range.to.asDayMonth(),
                ),
            )
        }
    }

    when {
        state.gradesLoading -> item(key = "grades-loading") { SkeletonGroup(rows = 4) }

        state.gradesError != null -> item(key = "grades-error") {
            DiaryFailureCard(state.gradesError, viewModel::retry)
        }

        state.subjects.isEmpty() -> item(key = "grades-empty") {
            EmptyState(
                title = stringResource(R.string.diary_grades_empty_title),
                description = stringResource(R.string.diary_grades_empty_text),
            )
        }

        else -> state.subjects.forEach { subject ->
            item(key = "subject-${subject.subject}") {
                DiarySubjectCard(subject = subject)
            }
        }
    }
}

/** One subject: its average, its marks, and everything that is not a mark. */
@Composable
private fun DiarySubjectCard(
    subject: DiarySubjectMarks,
    modifier: Modifier = Modifier,
) {
    RoundedCardContainer(modifier = modifier) {
        GroupRow {
            AccentIconTile(icon = Icons.Rounded.Star, tone = subjectTone(subject.subject))
            RowText(
                title = subject.subject,
                modifier = Modifier.weight(1f),
                subtitle = pluralStringResource(
                    R.plurals.diary_marks_count,
                    subject.grades.size,
                    subject.grades.size,
                ),
            )
            PillChip(
                text = subject.average
                    ?.let { stringResource(R.string.diary_average, it.formatAverage()) }
                    ?: stringResource(R.string.diary_no_average),
            )
        }

        if (subject.grades.isNotEmpty()) {
            GroupRow {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    subject.grades.forEach { mark ->
                        PillChip(text = mark.value)
                    }
                }
            }
        }

        subject.notes.forEach { mark -> DiaryNoteRow(mark = mark) }
    }
}

/**
 * An absence, a late arrival or a remark.
 *
 * Its own row rather than a chip among the marks: "Н" in a row of fours reads
 * as a mark of some kind, and the one thing this screen must not do is average
 * it with them.
 */
@Composable
private fun DiaryNoteRow(
    mark: DiaryMark,
    modifier: Modifier = Modifier,
) {
    GroupRow(modifier = modifier, verticalAlignment = Alignment.Top) {
        AccentIconTile(
            icon = mark.kind.icon(),
            tone = accentTone(mark.kind.toneIndex()),
        )
        Column(modifier = Modifier.weight(1f)) {
            RowText(
                title = stringResource(mark.kind.labelRes()),
                subtitle = listOfNotNull(
                    mark.date?.asDayMonth(),
                    mark.reason,
                    mark.comment,
                ).joinToString(" · ").ifBlank { null },
            )
        }
        PillChip(text = mark.value)
    }
}

/** The glyph for a kind of entry; the colour comes from `toneIndex`. */
private fun DiaryMarkKind.icon() = when (this) {
    DiaryMarkKind.GRADE -> Icons.Rounded.Star
    DiaryMarkKind.ABSENCE -> Icons.Rounded.EventBusy
    DiaryMarkKind.LATE -> Icons.Rounded.Schedule
    DiaryMarkKind.REMARK -> Icons.Rounded.Campaign
    DiaryMarkKind.OTHER -> Icons.AutoMirrored.Rounded.Notes
}

/**
 * "4,25" in Russian and "4.25" in English.
 *
 * Through the default locale rather than a fixed pattern: the average sits next
 * to dates that java.time has already localised, and one of the two spellings
 * of a decimal point would look imported.
 */
private fun Double.formatAverage(): String = String.format(Locale.getDefault(), "%.2f", this)
