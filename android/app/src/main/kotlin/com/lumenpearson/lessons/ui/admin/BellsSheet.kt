package com.lumenpearson.lessons.ui.admin

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Star
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.designsystem.component.EmptyState
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupTimeItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SkeletonGroup
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import java.time.LocalTime
import java.util.Locale

/** What the bells sheet is doing. */
internal sealed interface BellsMode {
    data object List : BellsMode
    data object Add : BellsMode
    data class Rename(val schedule: BellSchedule) : BellsMode
    data class Periods(val schedule: BellSchedule) : BellsMode
    data class Delete(val schedule: BellSchedule) : BellsMode
}

/**
 * Which of the sheet's screens is up — the half of [BellsMode] that can be saved.
 *
 * [BellsMode] carries a [BellSchedule], which is not `Parcelable` and should not
 * become one for the sake of a rotation. So what survives is this and the
 * schedule's id, and [bellsModeOf] puts the two back together.
 */
internal enum class BellsScreen { LIST, ADD, RENAME, PERIODS, DELETE }

/**
 * The mode [screen] and [id] stand for, against the schedules actually on hand.
 *
 * Resolved rather than restored, and the difference is what makes saving an id
 * enough: the sheet reloads on every open, so the rest of a [BellSchedule] has
 * to come from the list anyway, and a schedule that was renamed in the bot in
 * the meantime is then drawn as it is now rather than as it was before the
 * rotation.
 *
 * A screen that needs a schedule and has none falls back to the list. That is
 * two cases at once and both want the same answer: the list has not arrived yet
 * — where the list screen draws its own skeleton, and this resolves again when
 * it does — and the schedule is gone, deleted from the bot while this sheet sat
 * in the background. A rename form over a schedule that no longer exists is a
 * «Сохранить» that can only fail, under a name nobody can correct.
 */
internal fun bellsModeOf(
    screen: BellsScreen,
    id: Long?,
    schedules: List<BellSchedule>?,
): BellsMode {
    // Adding needs no schedule, so it must not be refused for want of one.
    if (screen == BellsScreen.ADD) return BellsMode.Add
    val schedule = schedules?.firstOrNull { it.id == id } ?: return BellsMode.List
    return when (screen) {
        BellsScreen.RENAME -> BellsMode.Rename(schedule)
        BellsScreen.PERIODS -> BellsMode.Periods(schedule)
        BellsScreen.DELETE -> BellsMode.Delete(schedule)
        BellsScreen.LIST, BellsScreen.ADD -> BellsMode.List
    }
}

/**
 * «🔔 Звонки»: the schedules a class keeps, and the times in them.
 *
 * Editing the rows is a whole-schedule operation and is drawn as one: the times
 * of every lesson after the one that moved shift with it, so sending the six
 * rows that are now true is one intention and one button.
 */
@Composable
fun BellsSheet(
    state: ManagementUiState,
    viewModel: ManagementViewModel,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The screen and the id, not the mode: a rotation — and, below API 33, the
    // `recreate()` the language picker three pages away performs — rebuilds this
    // composable from nothing, and a `remember` here put the reader back on the
    // list with whatever they had typed or set gone. See [bellsModeOf].
    var screen by rememberSaveable { mutableStateOf(BellsScreen.LIST) }
    var openId by rememberSaveable { mutableStateOf<Long?>(null) }
    val schedules = state.bells.value
    val mode = remember(screen, openId, schedules) { bellsModeOf(screen, openId, schedules) }

    fun show(next: BellsScreen, schedule: BellSchedule? = null) {
        screen = next
        openId = schedule?.id
    }

    ManagementSheet(
        title = correctedString(R.string.admin_bells_title),
        onDismiss = onDismiss,
        modifier = modifier,
    ) {
        when (val current = mode) {
            BellsMode.Add -> ScheduleNameForm(
                title = correctedString(R.string.admin_bells_add_title),
                initial = "",
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { show(BellsScreen.LIST) },
                onSave = { name ->
                    // Created empty: the rows are the next screen, and a
                    // schedule with no times is still a schedule a day can be
                    // pointed at once it has them.
                    viewModel.addBellSchedule(name, emptyList())
                    show(BellsScreen.LIST)
                },
            )

            is BellsMode.Rename -> ScheduleNameForm(
                title = correctedString(R.string.admin_bells_rename_title),
                initial = current.schedule.name,
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { show(BellsScreen.LIST) },
                onSave = { name ->
                    viewModel.renameBellSchedule(current.schedule.id, name)
                    show(BellsScreen.LIST)
                },
            )

            is BellsMode.Periods -> PeriodsForm(
                schedule = current.schedule,
                busy = state.working,
                failure = state.writeFailure,
                onCancel = { show(BellsScreen.LIST) },
                onSave = { periods ->
                    viewModel.saveBellPeriods(current.schedule.id, periods)
                    show(BellsScreen.LIST)
                },
            )

            is BellsMode.Delete -> {
                SheetSection(
                    title = correctedString(R.string.admin_bells_delete_title, current.schedule.name),
                )
                SheetNote(text = correctedString(R.string.admin_bells_delete_message))
                SheetFailure(failure = state.writeFailure)
                SheetButtons(
                    confirmLabel = correctedString(R.string.admin_bells_delete),
                    onConfirm = {
                        viewModel.deleteBellSchedule(current.schedule)
                        show(BellsScreen.LIST)
                    },
                    onCancel = { show(BellsScreen.LIST) },
                    busy = state.working,
                    destructive = true,
                )
            }

            BellsMode.List -> {
                SheetNotice(text = state.notice?.takeIf { it.isAboutBells }?.asText())
                SheetFailure(failure = state.writeFailure)
                when {
                    schedules == null && state.bells.loading -> SkeletonGroup(
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        rows = 3,
                    )

                    schedules == null -> ManagementFailureCard(
                        failure = state.bells.failure,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                        onRetry = viewModel::loadBells,
                    )

                    schedules.isEmpty() -> EmptyState(
                        title = correctedString(R.string.admin_bells_empty_title),
                        description = correctedString(R.string.admin_bells_empty_text),
                        icon = Icons.Rounded.NotificationsActive,
                        modifier = Modifier.padding(horizontal = ScreenPadding),
                    )

                    else -> schedules.forEach { schedule ->
                        ScheduleRows(
                            schedule = schedule,
                            busy = state.working,
                            onRename = { show(BellsScreen.RENAME, schedule) },
                            onPeriods = { show(BellsScreen.PERIODS, schedule) },
                            onMakeDefault = { viewModel.makeBellScheduleDefault(schedule.id) },
                            onDelete = { show(BellsScreen.DELETE, schedule) },
                        )
                    }
                }
                GroupActionItem(
                    label = correctedString(R.string.admin_bells_add),
                    icon = Icons.Rounded.Add,
                    onClick = { show(BellsScreen.ADD) },
                    busy = state.working,
                    modifier = Modifier.padding(horizontal = ScreenPadding),
                )
            }
        }
    }
}

private val ManagementNotice.isAboutBells: Boolean
    get() = this is ManagementNotice.BellsSaved ||
        this is ManagementNotice.BellsDefault ||
        this is ManagementNotice.BellsDeleted

/** One schedule: what it is called, what is in it, and what can be done to it. */
@Composable
private fun ScheduleRows(
    schedule: BellSchedule,
    busy: Boolean,
    onRename: () -> Unit,
    onPeriods: () -> Unit,
    onMakeDefault: () -> Unit,
    onDelete: () -> Unit,
) {
    SheetSection(
        title = if (schedule.isDefault) {
            "${schedule.name} · ${correctedString(R.string.admin_bells_default)}"
        } else {
            schedule.name
        },
    )
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = correctedString(R.string.admin_bells_periods),
            subtitle = periodsSubtitle(schedule),
            icon = Icons.Rounded.Schedule,
            tone = accentTone(0),
            enabled = !busy,
            onClick = onPeriods,
        )
        GroupItem(
            title = correctedString(R.string.admin_bells_rename),
            icon = Icons.Rounded.NotificationsActive,
            tone = accentTone(2),
            enabled = !busy,
            onClick = onRename,
        )
        // The default cannot be unset, only moved, so the row that would unset
        // it is simply absent from the schedule that already is one.
        if (!schedule.isDefault) {
            GroupItem(
                title = correctedString(R.string.admin_bells_make_default),
                icon = Icons.Rounded.Star,
                tone = accentTone(4),
                enabled = !busy,
                onClick = onMakeDefault,
            )
            GroupItem(
                title = correctedString(R.string.admin_bells_delete),
                tone = errorTone(),
                enabled = !busy,
                onClick = onDelete,
            )
        }
    }
}

/** "уроков: 6 · 8:30 – 14:00", or just the count for a schedule with no rows. */
@Composable
private fun periodsSubtitle(schedule: BellSchedule): String {
    val count = correctedString(R.string.admin_bells_lessons, schedule.periods.size)
    val first = schedule.periods.firstOrNull() ?: return count
    val last = schedule.periods.last()
    return "$count · ${first.startsAt.asBellClock()} – ${last.endsAt.asBellClock()}"
}

@Composable
private fun ScheduleNameForm(
    title: String,
    initial: String,
    busy: Boolean,
    failure: ManageFailure?,
    onCancel: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable(initial) { mutableStateOf(initial) }
    val problem = remember(name) { bellScheduleNameProblem(name) }

    SheetSection(title = title)
    SheetField(
        value = name,
        onValueChange = { name = it },
        label = correctedString(R.string.admin_bells_name_label),
        enabled = !busy,
    )
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = correctedString(R.string.action_save),
        onConfirm = { onSave(name.trim()) },
        onCancel = onCancel,
        enabled = problem == null,
        busy = busy,
    )
}

/**
 * The rows of one schedule, edited as a block.
 *
 * The list is held as `mutableStateListOf` and renumbered from 1 on every save:
 * lesson numbers must be unique and the only thing a school ever means by them
 * is the order, so letting a gap appear after removing a row would be offering
 * a mistake the server would then refuse.
 */
@Composable
private fun PeriodsForm(
    schedule: BellSchedule,
    busy: Boolean,
    failure: ManageFailure?,
    onCancel: () -> Unit,
    onSave: (List<BellPeriod>) -> Unit,
) {
    val rows = remember(schedule.id, schedule.periods) {
        schedule.periods.map { it.startsAt.minutes() to it.endsAt.minutes() }.toMutableStateList()
    }
    val periods = rows.mapIndexed { index, (start, end) ->
        BellPeriod(index = index + 1, startsAt = minutesToTime(start), endsAt = minutesToTime(end))
    }
    val problem = bellPeriodsProblem(periods)

    SheetSection(title = correctedString(R.string.admin_bells_periods_title, schedule.name))
    SheetNote(text = correctedString(R.string.admin_bells_periods_note))
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        rows.forEachIndexed { index, (start, end) ->
            GroupTimeItem(
                title = correctedString(R.string.admin_bells_period, index + 1),
                subtitle = correctedString(R.string.admin_bells_period_start),
                tone = accentTone(index),
                minutesOfDay = start,
                onMinutesOfDayChange = { rows[index] = it to end },
                enabled = !busy,
            )
            GroupTimeItem(
                title = correctedString(R.string.admin_bells_period, index + 1),
                subtitle = correctedString(R.string.admin_bells_period_end),
                tone = accentTone(index),
                minutesOfDay = end,
                onMinutesOfDayChange = { rows[index] = start to it },
                enabled = !busy,
            )
        }
    }
    RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
        GroupItem(
            title = correctedString(R.string.admin_bells_add_period),
            icon = Icons.Rounded.Add,
            tone = accentTone(1),
            enabled = !busy && rows.size < MaxPeriods,
            // A new row starts where the last one ended, which is what the next
            // lesson after a break actually does; an empty 00:00 row would be a
            // time nobody meant and the server would take it.
            onClick = { rows.add(nextRowAfter(rows.lastOrNull())) },
        )
        GroupItem(
            title = correctedString(R.string.admin_bells_remove_period),
            tone = errorTone(),
            enabled = !busy && rows.isNotEmpty(),
            onClick = { rows.removeAt(rows.lastIndex) },
        )
    }
    SheetProblem(problem = problem)
    SheetFailure(failure = failure)
    SheetButtons(
        confirmLabel = correctedString(R.string.action_save),
        onConfirm = { onSave(periods) },
        onCancel = onCancel,
        enabled = problem == null,
        busy = busy,
    )
}

/** `BellPeriodsIn`'s own ceiling, mirrored so the button dims instead of failing. */
private const val MaxPeriods = 20

/**
 * A 45-minute lesson after a 10-minute break; the shape of a Russian school
 * day.
 *
 * The break belongs *between* two lessons, so the first row does not get one:
 * adding the break unconditionally meant [DefaultFirstBell] could never
 * actually be produced, and the first row of an empty schedule — the one
 * «Добавить расписание» creates on purpose — opened at 08:40 instead of 08:30
 * with every row after it inheriting the shift. Saved unnoticed, that is a
 * class whose lessons ring ten minutes late in the widget's countdown, in
 * `AlertPlanner`'s «через 10 минут урок» and in every drawn lesson time,
 * because all three take their times from the bell row of the same number.
 */
private fun nextRowAfter(previous: Pair<Int, Int>?): Pair<Int, Int> {
    val start = previous?.let { (it.second + BreakMinutes).coerceAtMost(LastMinute) }
        ?: DefaultFirstBell
    return start to (start + LessonMinutes).coerceAtMost(LastMinute)
}

private const val DefaultFirstBell = 8 * 60 + 30
private const val LessonMinutes = 45
private const val BreakMinutes = 10
private const val LastMinute = 23 * 60 + 59

private fun LocalTime.minutes(): Int = hour * 60 + minute

private fun minutesToTime(minutes: Int): LocalTime =
    LocalTime.of(minutes / 60 % 24, minutes % 60)

/**
 * "08:30", the same clock the rest of the app reads a bell on.
 *
 * [Locale.ROOT], not the default: `"…".format(…)` formats through
 * `Locale.getDefault()`, which on a phone whose locale asks for Eastern Arabic
 * numerals turns a bell into «٠٨:٣٠» in the middle of a Russian sheet. A bell is
 * a number the school wrote down, not a quantity to be spelled in the reader's
 * language — `WidgetStrings.time` has said the same thing since the widget was
 * written.
 */
private fun LocalTime.asBellClock(): String =
    String.format(Locale.ROOT, "%02d:%02d", hour, minute)
