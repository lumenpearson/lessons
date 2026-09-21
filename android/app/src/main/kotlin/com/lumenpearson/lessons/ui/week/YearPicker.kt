package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone

/**
 * How far either side of the current year the picker offers to go.
 *
 * Two, which is five years in all. Not a guess: the phone keeps three years at
 * a time, so offering ten would be offering seven that each cost the two either
 * side of the one being looked at. Five is the range somebody actually asks
 * about — last year's marks, next year's first September — and it fits a sheet
 * without scrolling.
 */
private const val YearsEitherSide = 2

/**
 * «Учебный год»: which year the calendar is showing, and which are downloaded.
 *
 * The honest half of scrolling by years. The cache holds a few at a time and
 * fetches one when the calendar steps into it, so a year in this list is either
 * already here or one request away — and the row says which, because a calendar
 * that silently takes four seconds to fill looks broken and one that says it is
 * downloading does not.
 */
@Composable
internal fun YearPickerSheet(
    currentYear: Int,
    todayYear: Int,
    syncedYears: Set<Int>,
    loadingYear: Int?,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(R.string.week_year_pick),
    ) {
        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            // Centred on today rather than on the year being shown: the list
            // would otherwise walk away with every press, and the year
            // somebody keeps coming back to is the one they are in.
            for (year in (todayYear - YearsEitherSide)..(todayYear + YearsEitherSide)) {
                GroupItem(
                    title = yearLabel(year),
                    subtitle = correctedString(
                        when {
                            year == loadingYear -> R.string.week_year_loading_title
                            year in syncedYears -> R.string.week_year_held
                            else -> R.string.week_year_not_held
                        },
                    ),
                    icon = if (year in syncedYears) {
                        Icons.Rounded.CalendarMonth
                    } else {
                        Icons.Rounded.CloudDownload
                    },
                    tone = if (year == currentYear) accentTone(0) else neutralTone(),
                    onClick = {
                        onPick(year)
                        onDismiss()
                    },
                )
            }
        }

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * «2026/27» — a school year as a school writes it.
 *
 * Two digits for the second half, because that is what is printed on a
 * timetable and on a register, and «2026/2027» in a chip beside a month and a
 * quarter is three numbers fighting for one line.
 */
@Composable
internal fun yearLabel(openingYear: Int): String =
    correctedString(R.string.week_year_label, openingYear, (openingYear + 1) % 100)
