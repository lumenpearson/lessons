package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.EventNote
import androidx.compose.material.icons.rounded.CalendarViewWeek
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Reorder
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material.icons.rounded.ViewDay
import androidx.compose.material.icons.rounded.Weekend
import androidx.compose.material.icons.rounded.Widgets
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart

/**
 * Three groups rather than one list of twelve rows.
 *
 * The first group is what every surface shares. The other two are one screen
 * each, in the order the bottom bar draws them, so somebody who came here to
 * change the home screen reads five titles about the home screen instead of
 * twelve about everything.
 */
internal fun LazyListScope.contentRows(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
) {
    item(key = "content") {
        SettingsGroup(title = correctedString(R.string.settings_content_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_show_teacher),
                subtitle = correctedString(R.string.settings_show_teacher_description),
                icon = Icons.Rounded.Person,
                tone = accentTone(3),
                checked = state.settings.showTeacher,
                onCheckedChange = viewModel::setShowTeacher,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_widget_progress),
                subtitle = correctedString(R.string.settings_widget_progress_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(1),
                checked = state.settings.widgetShowProgress,
                onCheckedChange = viewModel::setWidgetShowProgress,
            )
        }
    }

    item(key = "content_home") {
        SettingsGroup(title = correctedString(R.string.settings_home_group)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_today_hero),
                subtitle = correctedString(R.string.settings_today_hero_description),
                icon = Icons.Rounded.Timer,
                tone = accentTone(0),
                checked = state.settings.todayShowHero,
                onCheckedChange = viewModel::setTodayShowHero,
            )
            GroupSegmentedItem(
                title = correctedString(R.string.settings_today_layout),
                subtitle = correctedString(R.string.settings_today_layout_description),
                icon = Icons.Rounded.Reorder,
                tone = accentTone(2),
                items = TodayLayout.entries,
                selectedItem = state.settings.todayLayout,
                onItemSelected = viewModel::setTodayLayout,
                labelProvider = { layout -> correctedString(layout.labelRes) },
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_today_whole_day),
                subtitle = correctedString(R.string.settings_today_whole_day_description),
                icon = Icons.Rounded.ViewDay,
                tone = accentTone(4),
                checked = state.settings.todayWholeDay,
                onCheckedChange = viewModel::setTodayWholeDay,
            )
            GroupSegmentedItem(
                title = correctedString(R.string.settings_today_homework_count),
                subtitle = correctedString(R.string.settings_today_homework_count_description),
                icon = Icons.Rounded.EditNote,
                tone = accentTone(5),
                items = AppSettings.HOMEWORK_PREVIEW_OPTIONS,
                // No nearest-match here, unlike the text-size picker above: the
                // store snaps this number to one of the three steps on the way
                // in and on the way out, so what it hands over is always one of
                // the segments.
                selectedItem = state.settings.todayHomeworkPreview,
                onItemSelected = viewModel::setTodayHomeworkPreview,
                labelProvider = { count -> count.toString() },
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_today_events),
                subtitle = correctedString(R.string.settings_today_events_description),
                icon = Icons.AutoMirrored.Rounded.EventNote,
                tone = accentTone(1),
                checked = state.settings.todayShowEvents,
                onCheckedChange = viewModel::setTodayShowEvents,
            )
        }
    }

    item(key = "content_calendar") {
        SettingsGroup(title = correctedString(R.string.settings_calendar_group)) {
            GroupSegmentedItem(
                title = correctedString(R.string.settings_week_start),
                subtitle = correctedString(R.string.settings_week_start_description),
                icon = Icons.Rounded.CalendarViewWeek,
                tone = accentTone(3),
                items = WeekStart.entries,
                selectedItem = state.settings.weekStart,
                onItemSelected = viewModel::setWeekStart,
                labelProvider = { start -> correctedString(start.labelRes) },
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_week_weekends),
                subtitle = correctedString(R.string.settings_week_weekends_description),
                icon = Icons.Rounded.Weekend,
                tone = accentTone(0),
                checked = state.settings.weekShowWeekends,
                onCheckedChange = viewModel::setWeekShowWeekends,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_week_load),
                subtitle = correctedString(R.string.settings_week_load_description),
                icon = Icons.Rounded.MoreHoriz,
                tone = accentTone(2),
                checked = state.settings.weekShowLoad,
                onCheckedChange = viewModel::setWeekShowLoad,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_week_events),
                subtitle = correctedString(R.string.settings_week_events_description),
                icon = Icons.AutoMirrored.Rounded.EventNote,
                tone = accentTone(4),
                checked = state.settings.weekShowEvents,
                onCheckedChange = viewModel::setWeekShowEvents,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_week_homework),
                subtitle = correctedString(R.string.settings_week_homework_description),
                icon = Icons.Rounded.EditNote,
                tone = accentTone(5),
                checked = state.settings.weekShowHomework,
                onCheckedChange = viewModel::setWeekShowHomework,
            )
        }
    }
}

/** Label of a home-screen order in the segmented picker. */
private val TodayLayout.labelRes: Int
    get() = when (this) {
        TodayLayout.AUTOMATIC -> R.string.settings_today_layout_auto
        TodayLayout.LESSONS_FIRST -> R.string.settings_today_layout_lessons
        TodayLayout.HOMEWORK_FIRST -> R.string.settings_today_layout_homework
    }

/** Label of a week start in the segmented picker. */
private val WeekStart.labelRes: Int
    get() = when (this) {
        WeekStart.MONDAY -> R.string.settings_week_start_monday
        WeekStart.TODAY -> R.string.settings_week_start_today
    }
