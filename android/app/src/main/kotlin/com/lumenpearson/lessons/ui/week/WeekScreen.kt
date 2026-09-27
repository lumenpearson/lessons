package com.lumenpearson.lessons.ui.week

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolYear
import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.TermKind
import com.lumenpearson.lessons.ui.common.asDayMonth
import com.lumenpearson.lessons.ui.common.asFullWeekday
import com.lumenpearson.lessons.ui.common.asMonthYear
import com.lumenpearson.lessons.ui.day.DayRibbonView
import com.lumenpearson.lessons.ui.day.RibbonSettingsSheet
import java.time.LocalDate

/**
 * The calendar tab: the same timetable at three scales.
 *
 * The week is what a pupil looks at most; the month answers "when is that trip"
 * without stepping through four weeks; the day against an hour ruler is the only
 * one of the three in which a forty-minute gap between lessons looks like a gap
 * rather than like two rows next to each other.
 *
 * There is no pager inside this screen. There used to be — seven day pages under
 * the shell's own pager — and the inner one took every horizontal swipe, so on
 * this tab the gesture paged to Wednesday instead of moving to Homework, and
 * turning "swipe between tabs" off in settings did not disable it either. The
 * day is chosen by tapping, and the period is stepped with the arrows.
 */
@Composable
fun WeekScreen(
    modifier: Modifier = Modifier,
    viewModel: WeekViewModel = viewModel(factory = WeekViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // One scroll for the whole page: the title, the picker and the grid all move
    // under the status bar, which is what the fade up there is for.
    val scrollState = rememberScrollState()

    val sheets = rememberScheduleSheets()

    val selected = state.selectedDay

    ScheduleSheets(
        sheets = sheets,
        days = state.days,
        showTeacher = state.showTeacher,
        showEvents = state.showEvents,
        showHomework = state.showHomework,
    )

    // The ribbon is the one view that owns the page's height instead of adding
    // to its length. It has to: a scroll can only be magnetic, and a row can
    // only know where it is in the viewport, if the list *is* the viewport. So
    // the calendar stops being one long column here, and the header and the
    // picker sit above a ribbon that takes the rest.
    if (state.view == ScheduleView.DAY) {
        RibbonPage(
            state = state,
            viewModel = viewModel,
            day = selected?.day,
            onLessonClick = { lesson -> sheets.show(state.selected, lesson) },
            onOpenDay = { date -> sheets.day = date },
            modifier = modifier,
        )
        return
    }

    ReportScrollOffset(scrollState)

    var yearPickerOpen by rememberSaveable { mutableStateOf(false) }
    if (yearPickerOpen) {
        YearPickerSheet(
            currentYear = state.anchorYear,
            todayYear = SchoolYear.openingYearOf(state.today),
            syncedYears = state.syncedYears,
            loadingYear = state.loadingYear,
            onPick = viewModel::openYear,
            onDismiss = { yearPickerOpen = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .appScrollMotionBlur(scrollState)
            .verticalScroll(scrollState)
            .padding(
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        ScheduleHeader(
            periodLabel = state.periodLabel(),
            termLabel = state.termLabel(),
            yearLabel = yearLabel(state.anchorYear),
            step = state.periodStep,
            showTodayAction = state.canReturnToToday,
            onToday = viewModel::showToday,
            onPrevious = viewModel::showPrevious,
            onNext = viewModel::showNext,
            onPickYear = { yearPickerOpen = true },
        )

        SegmentedPicker(
            items = ScheduleView.entries,
            selectedItem = state.view,
            onItemSelected = viewModel::setView,
            labelProvider = { view -> correctedString(view.labelRes) },
            containerColor = MaterialTheme.colorScheme.rowContainer,
            contentPadding = PaddingValues(4.dp),
            // No `clip` here any more: the picker rounds its own tray, from the
            // corner of the buttons inside it plus this padding. A radius
            // chosen here could only ever agree with them by coincidence.
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        // Keyed on the period as well as the view, so stepping a week slides the
        // new one in from the side the arrow pointed at.
        AnimatedContent(
            targetState = state.view to state.periodStart,
            transitionSpec = {
                val forward = targetState.second >= initialState.second
                val enter = slideInHorizontally(tween(PeriodTransitionMillis)) { width ->
                    if (forward) width / 6 else -width / 6
                } + fadeIn(tween(PeriodTransitionMillis))
                val exit = slideOutHorizontally(tween(PeriodTransitionMillis)) { width ->
                    if (forward) -width / 6 else width / 6
                } + fadeOut(tween(PeriodTransitionMillis))
                enter togetherWith exit
            },
            label = "schedule_period",
        ) { (view, _) ->
            Column(verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
                FilterChips(
                    active = state.filters,
                    onToggle = viewModel::toggleFilter,
                    onClear = viewModel::clearFilters,
                )
                when (view) {
                    ScheduleView.WEEK -> WeekdaySelector(
                        days = state.days,
                        selected = state.selected,
                        showLoad = state.showLoad,
                        onSelect = viewModel::select,
                    )

                    ScheduleView.MONTH -> MonthGrid(
                        days = state.days,
                        selected = state.selected,
                        showLoad = state.showLoad,
                        onSelect = viewModel::select,
                        onOpen = { date -> sheets.day = date },
                    )

                    // «День» never reaches here: it owns the page's height,
                    // so `WeekScreen` returns `RibbonPage` above rather than
                    // adding to this column.
                    ScheduleView.DAY -> Unit
                }
            }
        }

        DayPanel(
            day = selected,
            date = state.selected,
            loadingYear = state.loadingYear == SchoolYear.openingYearOf(state.selected),
            showTeacher = state.showTeacher,
            showEvents = state.showEvents,
            showHomework = state.showHomework,
            onLessonClick = { lesson -> sheets.show(state.selected, lesson) },
            onOpenDay = { sheets.day = state.selected },
        )
    }
}

/**
 * The «День» view: the same header and picker over a ribbon that fills the rest.
 *
 * A second layout of one screen rather than a second screen. The header and the
 * picker are the calendar's, and a reader who stepped to Thursday and then
 * pressed «День» expects both to still be there — a full-screen day would
 * answer a change of scale with a change of place.
 *
 * `internal` so the tests can compose it: what this page gets wrong is which
 * of its two scrollables the shell is told about and what its list says when
 * it is empty, and neither is visible from the screen above.
 */
@Composable
internal fun RibbonPage(
    state: ScheduleUiState,
    viewModel: WeekViewModel,
    day: SchoolDay?,
    onLessonClick: (Lesson) -> Unit,
    onOpenDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    // Hoisted for the same reason `DayRibbonView` hoists its own: the shell's
    // top fade reads whichever of these is being scrolled, and a test of that
    // has to be able to scroll one without a gesture.
    ribbonState: LazyListState = rememberLazyListState(),
    agendaState: ScrollState = rememberScrollState(),
) {
    // Whichever scrollable this mode actually owns — not always the ribbon's.
    // The shell fades the status bar on the offset reported here, and while
    // only the ribbon reported, pressing «Список» after scrolling the ribbon
    // left the blur fully applied over a list sitting at its top, with nothing
    // the list did afterwards able to change it: `ribbonState` survives the
    // switch, so the stale maximum was what the list wore.
    when (state.dayMode) {
        DayMode.RIBBON -> ReportScrollOffset(ribbonState)
        DayMode.LIST -> ReportScrollOffset(agendaState)
    }

    var yearPickerOpen by rememberSaveable { mutableStateOf(false) }
    if (yearPickerOpen) {
        YearPickerSheet(
            currentYear = state.anchorYear,
            todayYear = SchoolYear.openingYearOf(state.today),
            syncedYears = state.syncedYears,
            loadingYear = state.loadingYear,
            onPick = viewModel::openYear,
            onDismiss = { yearPickerOpen = false },
        )
    }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    if (settingsOpen) {
        RibbonSettingsSheet(
            flow = state.ribbonFlow,
            snap = state.ribbonSnap,
            depth = state.ribbonDepth,
            onFlow = viewModel::setRibbonFlow,
            onSnap = viewModel::setRibbonSnap,
            onDepth = viewModel::setRibbonDepth,
            onDismiss = { settingsOpen = false },
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        ScheduleHeader(
            periodLabel = state.periodLabel(),
            termLabel = state.termLabel(),
            yearLabel = yearLabel(state.anchorYear),
            step = state.periodStep,
            showTodayAction = state.canReturnToToday,
            onToday = viewModel::showToday,
            onPrevious = viewModel::showPrevious,
            onNext = viewModel::showNext,
            onPickYear = { yearPickerOpen = true },
        )

        SegmentedPicker(
            items = ScheduleView.entries,
            selectedItem = state.view,
            onItemSelected = viewModel::setView,
            labelProvider = { view -> correctedString(view.labelRes) },
            containerColor = MaterialTheme.colorScheme.rowContainer,
            contentPadding = PaddingValues(4.dp),
            // No `clip` here any more: the picker rounds its own tray, from the
            // corner of the buttons inside it plus this padding. A radius
            // chosen here could only ever agree with them by coincidence.
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        // The two readings of «День», switched here rather than in the tabs
        // above. They used to be two tabs — «День» and «Лента» — and they are
        // not two views: both answer «что идёт», one for the day in front of
        // you and one for the month around it. Four four-letter labels across a
        // 360 dp phone is also most of what the picker's marquee was for.
        SegmentedPicker(
            items = DayMode.entries,
            selectedItem = state.dayMode,
            onItemSelected = viewModel::setDayMode,
            labelProvider = { mode -> correctedString(mode.labelRes) },
            containerColor = MaterialTheme.colorScheme.rowContainer,
            contentPadding = PaddingValues(4.dp),
            modifier = Modifier.padding(horizontal = ScreenPadding),
        )

        when (state.dayMode) {
            DayMode.RIBBON -> DayRibbonView(
                day = day,
                nowAt = state.nowAt,
                flow = state.ribbonFlow,
                snap = state.ribbonSnap,
                depth = state.ribbonDepth,
                showTeacher = state.showTeacher,
                showHomework = state.showHomework,
                isFetched = state.selectedDay?.isFetched != false,
                loadingYear = state.loadingYear == SchoolYear.openingYearOf(state.selected),
                yearName = yearLabel(SchoolYear.openingYearOf(state.selected)),
                listState = ribbonState,
                onLessonClick = onLessonClick,
                onSettings = { settingsOpen = true },
                modifier = Modifier.weight(1f),
                header = { DayChips(day = state.selectedDay, date = state.selected) },
            )

            // The list keeps the page's height too, so a month of rows scrolls
            // inside it rather than making the whole page longer. That is also
            // what keeps the header and both pickers in place while it moves —
            // the thing this tab was rebuilt around.
            DayMode.LIST -> Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(agendaState),
                verticalArrangement = Arrangement.spacedBy(GroupSpacing),
            ) {
                FilterChips(
                    active = state.filters,
                    onToggle = viewModel::toggleFilter,
                    onClear = viewModel::clearFilters,
                )
                AgendaList(
                    days = state.agenda,
                    today = state.today,
                    selected = state.selected,
                    order = state.order,
                    filters = state.filters,
                    // The anchor's year, not the selection's: this list is the
                    // anchor's month, and a calendar month never straddles the
                    // 1 September or 1 June a school year turns on.
                    isFetched = state.anchorYearFetched,
                    loadingYear = state.anchorYearLoading,
                    yearName = yearLabel(state.anchorYear),
                    onOrder = viewModel::setOrder,
                    onOpen = onOpenDay,
                )
            }
        }
    }
}

/** Label of a day mode in the picker inside «День». */
private val DayMode.labelRes: Int
    get() = when (this) {
        DayMode.RIBBON -> R.string.day_mode_ribbon
        DayMode.LIST -> R.string.day_mode_list
    }

/** How long a period change takes to slide across. */
private const val PeriodTransitionMillis = 260

/** Label of a view in the segmented picker. */
private val ScheduleView.labelRes: Int
    get() = when (this) {
        ScheduleView.WEEK -> R.string.schedule_view_week
        ScheduleView.MONTH -> R.string.schedule_view_month
        ScheduleView.DAY -> R.string.schedule_view_day
    }

/**
 * What the header says the screen is showing.
 *
 * The week names its first and last *drawn* date rather than the period's ends,
 * so a strip with weekends hidden is headed "8 – 12 сентября" instead of
 * promising a Sunday that is not on it.
 */
@Composable
private fun ScheduleUiState.periodLabel(): String = when (view) {
    ScheduleView.WEEK -> correctedString(
        R.string.week_range,
        (days.firstOrNull()?.date ?: periodStart).asDayMonth(),
        (days.lastOrNull()?.date ?: periodEnd).asDayMonth(),
    )

    ScheduleView.MONTH -> anchor.asMonthYear()

    // «День» names whichever span it is drawing: the date for the ribbon, the
    // month for the list. The header has to agree with the arrows beside it,
    // and the arrows step a day in one mode and a month in the other.
    ScheduleView.DAY -> when (dayMode) {
        DayMode.RIBBON ->
            "${selected.asFullWeekday().replaceFirstChar { it.uppercase() }}, " +
                selected.asDayMonth()

        DayMode.LIST -> anchor.asMonthYear()
    }
}

/**
 * The term the selected day belongs to, appended to the header.
 *
 * Empty during the holidays and for a class whose server has no terms — in both
 * cases the header is just the period, because «—» in place of a term name
 * claims the school has one and the app forgot it.
 */
@Composable
private fun ScheduleUiState.termLabel(): String? = selectedTerm?.label()

/**
 * «2 четверть» / «1 полугодие», and their English twins.
 *
 * Here rather than on [Term], which lives in `:core:model` — a pure JVM module
 * that has no resources and cannot have any. The sentence was written into that
 * type, so this header drew a Russian term name next to an English month for
 * every phone reading the app in English, and no folder-comparison could see it
 * because the words were in neither folder.
 *
 * `internal` so the test can compose it: the header this feeds is private, and
 * the one thing worth pinning is that both languages come out of resources.
 */
@Composable
internal fun Term.label(): String = when (kind) {
    TermKind.QUARTER -> correctedString(R.string.term_quarter, index)
    TermKind.SEMESTER -> correctedString(R.string.term_semester, index)
}
