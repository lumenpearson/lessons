package com.lumenpearson.lessons.ui.week

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.DayOrder
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolYear
import com.lumenpearson.lessons.core.model.matches
import com.lumenpearson.lessons.core.model.Term
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * How much of the timetable the screen shows at once.
 *
 * Three scales of the same data rather than three screens: the week is what a
 * pupil looks at most, the month answers "when is that trip" without stepping
 * through four weeks, and the day laid out against an hour ruler is the only
 * view in which a forty-minute gap looks like a gap.
 */
enum class ScheduleView {
    WEEK,
    MONTH,

    /**
     * One day, read either as a ribbon or as the month's days in a list.
     *
     * There were four of these and the fourth was `AGENDA`, a month as a list
     * rather than a grid. It was never a fourth *scale*: it answers the same
     * question as this one over a different span, which is why it is a
     * [DayMode] inside this view now rather than a tab beside it. What it
     * brought with it is kept — a grid cannot be sorted and stay a calendar,
     * the 14th before the 3rd is not a month any more, so «по загруженности»
     * and a filter that genuinely drops days both need somewhere that is
     * really a list. That somewhere is [DayMode.LIST].
     */
    DAY,
}

/**
 * One date on screen.
 *
 * @property day `null` when the cached timetable does not reach this date, which
 *   is a different thing from "no lessons" and is rendered differently.
 * @property inPeriod false for the days of the neighbouring months that fill out
 *   the corners of a month grid. They are shown, faintly, because a grid that
 *   starts mid-row is harder to read than one that does not.
 */
data class WeekDayUi(
    val date: LocalDate,
    val day: SchoolDay?,
    val isToday: Boolean,
    val inPeriod: Boolean = true,
    /**
     * False for a day the current filters exclude.
     *
     * Dimmed rather than removed, and that is the whole decision: a month grid
     * with holes in it stops lining up with the weekday header and with every
     * calendar anybody has ever read. The filter answers «where is the
     * homework», and a month that still looks like that month is what makes
     * the answer findable.
     */
    val matchesFilters: Boolean = true,
    /**
     * Whether the school year this date belongs to has been fetched at all.
     *
     * `day == null` used to mean one thing — «the cache does not reach here» —
     * because the cache reached exactly one year and everything outside it was
     * outside it. Now it means two: a date inside a fetched year with nothing
     * on it, and a date in a year nobody has asked for yet. The first is «нет
     * уроков» and the second is «ещё не загружено», and telling a reader the
     * first about the second is how a timetable looks like it stops.
     */
    val isFetched: Boolean = true,
)

/**
 * @property anchor a date inside the shown period; stepping moves this.
 * @property selected the day whose detail is shown under the week or month.
 * @property days every date the current view draws, in order — which is not
 *   every date of the period: with weekends hidden the week strip draws five of
 *   its seven.
 * @property showLoad draw the lesson-count dots under each date.
 * @property showEvents draw the selected day's events under its lessons.
 * @property showHomework draw the selected day's homework under its lessons.
 */
data class ScheduleUiState(
    val isLoading: Boolean = true,
    val view: ScheduleView = ScheduleView.WEEK,
    // device clock: the placeholder the grid is built on for the one frame
    // before the first emission, while isLoading is still true and nothing
    // reads it. Every later value comes from `Timetable.atSchool`.
    val today: LocalDate = LocalDate.now(),
    val anchor: LocalDate = today,
    val selected: LocalDate = today,
    val periodStart: LocalDate = today,
    val periodEnd: LocalDate = today,
    val days: List<WeekDayUi> = emptyList(),
    /** The facets currently narrowing the calendar; empty is no filter. */
    val filters: Set<DayFilter> = emptySet(),
    /** The order a list view draws its days in. */
    val order: DayOrder = DayOrder.DATE_ASC,
    /** Which reading of «День» is on screen — the ribbon or the month's list. */
    val dayMode: DayMode = DayMode.RIBBON,
    /** The list the agenda view draws: filtered, then ordered. Empty elsewhere. */
    val agenda: List<SchoolDay> = emptyList(),
    /** Which way the day ribbon's progress runs; see [RibbonFlow]. */
    val ribbonFlow: RibbonFlow = RibbonFlow.DOWNWARD,
    /** Whether the ribbon settles on a whole entry when a fling stops. */
    val ribbonSnap: Boolean = true,
    /** Whether the ribbon draws its depth. */
    val ribbonDepth: Boolean = true,
    val showTeacher: Boolean = true,
    val showLoad: Boolean = true,
    val showEvents: Boolean = true,
    val showHomework: Boolean = true,
    val nowAt: LocalTime? = null,
    /**
     * Which school years the cache actually holds, by opening year.
     *
     * Read from the repository rather than counted off the days: a class made
     * in March has no rows before it either way, and counting would report its
     * first autumn as «not loaded» for ever.
     */
    val syncedYears: Set<Int> = emptySet(),
    /** The year a fetch is in flight for, or null. One at a time. */
    val loadingYear: Int? = null,
    /**
     * The terms of the class's own year, as the school runs them.
     *
     * Carried rather than derived: the dates move — the holidays shift, a region
     * starts its spring break early — so the server keeps rows an admin edits
     * and the app reads them. Empty for a class whose server predates them.
     */
    val terms: List<Term> = emptyList(),
) {
    /** The term the selected day falls in, or `null` during the holidays. */
    val selectedTerm: Term? get() = terms.firstOrNull { selected in it }

    /** The day the detail panel and the hour ruler render. */
    val selectedDay: WeekDayUi? get() = days.firstOrNull { it.date == selected }

    /**
     * Whether "back to today" would do anything.
     *
     * Today has to be *drawn* for the second half to be worth offering: with
     * weekends hidden, a Saturday is in the period and not on screen, and the
     * button would light up for the whole weekend and do nothing when pressed.
     */
    val canReturnToToday: Boolean
        get() = today !in periodStart..periodEnd ||
            (selected != today && days.any { it.date == today })

    /** The school year the period on screen belongs to, by its opening year. */
    val anchorYear: Int get() = SchoolYear.openingYearOf(anchor)

    /** Whether that year is in the cache at all. */
    val anchorYearFetched: Boolean get() = anchorYear in syncedYears

    /** Whether the period on screen is the one currently being fetched. */
    val anchorYearLoading: Boolean get() = loadingYear == anchorYear
}

/**
 * Where the reader is, and what the cache holds around them.
 *
 * A holder rather than four flows in the `combine` below, because `combine`
 * has typed overloads up to five and this screen wants seven. Grouped by what
 * they are about rather than by convenience: all four answer «which dates is
 * this screen showing, and are they here».
 */
private data class CalendarFocus(
    val anchor: LocalDate?,
    val selected: LocalDate?,
    val syncedYears: Set<Int>,
    val loadingYear: Int?,
)

/**
 * Calendar state holder.
 *
 * It keeps an *anchor date* rather than an offset from today, because the three
 * views step by different amounts and a single offset cannot mean a week here
 * and a month there. Today is re-read on a timer, so leaving the app open past
 * midnight moves the highlight instead of stranding it on yesterday.
 */
class WeekViewModel(
    private val timetableRepository: TimetableRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    private val view = MutableStateFlow(ScheduleView.WEEK)

    /**
     * The year a fetch is in flight for, or null.
     *
     * Held here rather than in the repository because it is a fact about this
     * screen's own request: the widget and the home screen have no use for it.
     * What reads it is the empty state — «загружаю год» rather than «нет
     * данных» — which is the whole difference between a calendar that is
     * working and one that looks broken.
     */
    private val loadingYear = MutableStateFlow<Int?>(null)

    /**
     * Years this screen has already asked for and been refused, in this session.
     *
     * Without it a year the server has nothing for — a class created after it,
     * a device with no network — is asked for again on every step into it, and
     * stepping back and forth across 1 September is a request per press. The
     * set is deliberately not persisted: «try again» is what re-opening the app
     * means, and a failure here is usually the network rather than the year.
     */
    private val refused = MutableStateFlow<Set<Int>>(emptySet())

    /** Null until the user moves: the anchor follows today while it is untouched. */
    private val anchor = MutableStateFlow<LocalDate?>(null)
    private val selected = MutableStateFlow<LocalDate?>(null)

    /**
     * Coarse clock, truncated to the minute.
     *
     * One timer rather than two. The date is what most of the state depends on,
     * but the hour ruler draws a "now" line, and a line that only moves at
     * midnight is not a now line. A minute is as fine as this screen gets: the
     * state object is rebuilt when it ticks, so a shorter period would be
     * recomposition bought with nothing.
     *
     * An [Instant], not a `LocalDateTime`: the timetable is stored in the
     * school's wall time, and the school's zone is known where the state is
     * built, not here. `Timetable.atSchool` exists for exactly this crossing —
     * emitting `LocalDateTime.now()` instead silently made every date on this
     * screen the *device's*, so a phone an hour behind the class drew the wrong
     * day as today on the strip and put the now line an hour out on the ruler.
     */
    private val now: Flow<Instant> = flow {
        while (true) {
            emit(Instant.now().truncatedTo(ChronoUnit.MINUTES))
            delay(CLOCK_TICK_MILLIS)
        }
    }.distinctUntilChanged()

    /**
     * The four screen-owned inputs, as one flow.
     *
     * `combine` has typed overloads up to five flows and this screen wants
     * seven, so the four that belong to *where the reader is* travel together:
     * the date in focus, and what the cache holds around it.
     */
    private val focus: Flow<CalendarFocus> = combine(
        anchor,
        selected,
        timetableRepository.syncedYears,
        loadingYear,
    ) { anchor, selected, years, loading -> CalendarFocus(anchor, selected, years, loading) }

    val uiState: StateFlow<ScheduleUiState> = combine(
        timetableRepository.timetable,
        now,
        view,
        focus,
        settingsRepository.settings,
    ) { timetable, instant, view, focus, settings ->
        // Without a timetable there is no school zone to be in, and the only
        // thing the date decides is which week the empty grid is labelled with.
        val now = timetable?.atSchool(instant)
            // device clock: no timetable, so no school zone to be in — see above.
            ?: LocalDateTime.ofInstant(instant, ZoneId.systemDefault())
        val today = now.toLocalDate()
        val storedAnchor = focus.anchor
        val storedSelection = focus.selected
        val anchorDate = storedAnchor ?: today
        val selectedDate = storedSelection ?: today

        val (start, end) = view.periodOf(anchorDate, settings.weekStart, settings.dayMode)
        val dates = view.datesOf(start, end, settings.weekShowWeekends)
        // A selection outside the drawn dates would leave the detail panel
        // rendering a day the grid does not contain; stepping the period
        // therefore drags the selection with it.
        val selection = clampSelection(dates, selectedDate)

        ScheduleUiState(
            isLoading = false,
            view = view,
            today = today,
            anchor = anchorDate,
            selected = selection,
            periodStart = start,
            periodEnd = end,
            days = dates.map { date ->
                WeekDayUi(
                    date = date,
                    day = timetable?.day(date),
                    isToday = date == today,
                    inPeriod = view != ScheduleView.MONTH || date.month == anchorDate.month,
                    matchesFilters = timetable?.day(date).matches(settings.calendarFilters),
                    isFetched = SchoolYear.openingYearOf(date) in focus.syncedYears,
                )
            },
            syncedYears = focus.syncedYears,
            loadingYear = focus.loadingYear,
            terms = timetable?.schoolClass?.terms.orEmpty(),
            filters = settings.calendarFilters,
            order = settings.calendarOrder,
            // Built here rather than in the composable: the order and the
            // filter are one decision about what the list *is*, and a screen
            // that re-sorted on every recomposition would be doing that
            // decision twice.
            agenda = settings.calendarOrder.sort(
                dates.mapNotNull { date -> timetable?.day(date) }
                    .filter { it.matches(settings.calendarFilters) },
            ),
            dayMode = settings.dayMode,
            ribbonFlow = settings.dayRibbonFlow,
            ribbonSnap = settings.dayRibbonSnap,
            ribbonDepth = settings.dayRibbonDepth,
            showTeacher = settings.showTeacher,
            showLoad = settings.weekShowLoad,
            showEvents = settings.weekShowEvents,
            showHomework = settings.weekShowHomework,
            // Only for the day whose ruler could show it; every other day's
            // timeline has no "now" on it and drawing one would be a lie.
            nowAt = now.toLocalTime().takeIf { selection == today },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MILLIS),
        initialValue = ScheduleUiState(),
    )

    /** Switches scale, keeping the day in focus rather than jumping to today. */
    fun setView(next: ScheduleView) {
        val focus = selected.value ?: anchor.value ?: uiState.value.today
        anchor.value = focus
        selected.value = focus
        view.value = next
    }

    /** Steps back one week, month or day, whichever the current view shows. */
    fun showPrevious() = step(-1)

    /** Steps forward by the same amount. */
    fun showNext() = step(1)

    /** Returns to today, in whatever view is open. */
    fun showToday() {
        anchor.value = null
        selected.value = null
    }

    /** Puts a date in focus without changing the period. */
    fun select(date: LocalDate) {
        selected.value = date
    }

    /**
     * Shows the school year opening in [openingYear], fetching it if need be.
     *
     * Lands on 1 September rather than on the same date a year away: the year
     * picker is «покажи мне тот год», and the answer to that is its beginning.
     */
    fun openYear(openingYear: Int) {
        val start = SchoolYear.start(openingYear)
        anchor.value = start
        selected.value = start
    }

    /**
     * Turns one filter facet on or off, and remembers it.
     *
     * Stored rather than held here: somebody who narrowed the calendar to
     * «с ДЗ» meant it, and losing that on the walk from the calendar to the
     * homework tab and back would make the chips something to press twice.
     */
    fun toggleFilter(filter: DayFilter) {
        viewModelScope.launch {
            val current = uiState.value.filters
            val next = if (filter in current) current - filter else current + filter
            settingsRepository.update { it.copy(calendarFilters = next) }
        }
    }

    /**
     * Switches «День» between the ribbon and the month's list, and remembers it.
     *
     * The period changes with it — one date against a month — so the anchor is
     * left exactly where it is and the new period is built around it. Pressing
     * «Список» on 22 September opens September, and pressing «Лента» again
     * comes back to the 22nd rather than to the 1st.
     */
    fun setDayMode(mode: DayMode) {
        viewModelScope.launch { settingsRepository.update { it.copy(dayMode = mode) } }
    }

    /** Changes the order the list view draws its days in, and remembers it. */
    fun setOrder(order: DayOrder) {
        viewModelScope.launch { settingsRepository.update { it.copy(calendarOrder = order) } }
    }

    init {
        // One place, watching the period that is actually drawn.
        //
        // The anchor is moved by the arrows, by a tap on a day, by the year
        // picker and by a widget tap, and a fetch hung off each of those is
        // four chances to forget one — the first draft did exactly that, and
        // it also had to guess at «неделя начинается с» to work out the
        // period's bounds outside a coroutine. The state already carries those
        // bounds, computed from the real setting, so watching it is both
        // simpler and more correct.
        //
        // **Both ends**, because a period can straddle 31 May: a week that
        // begins in one school year and ends in the next needs both, and
        // asking about the anchor alone leaves half the strip saying «ещё не
        // загружено» with nothing on its way to it.
        viewModelScope.launch {
            uiState
                .map { state -> state.periodStart to state.periodEnd }
                .distinctUntilChanged()
                .collect { (from, to) ->
                    // Awaited, not launched, and that is what serialises the
                    // fetches: this collector is one coroutine, so a request
                    // in flight parks the collector itself. Stepping quickly
                    // through 2026 → 2027 → 2028 therefore asks for 2027,
                    // waits, and then collects the *latest* state — a
                    // `StateFlow` conflates, so what it resumes on is where
                    // the reader ended up — and asks for that. Launching each
                    // fetch instead would fire three at once and is the
                    // obvious-looking change that breaks it.
                    ensureYear(from)
                    if (SchoolYear.openingYearOf(to) != SchoolYear.openingYearOf(from)) {
                        ensureYear(to)
                    }
                }
        }
    }

    /**
     * Asks for a school year this device has not fetched, if it has not already.
     *
     * Two things stop it asking twice: a year already held needs nothing, and a
     * year already refused in this session is not asked again — the usual cause
     * is the network rather than the year, and stepping back and forth over
     * 1 September would otherwise be a request per press.
     *
     * There is deliberately no «already in flight» check. One was written here
     * first and proved unreachable: the only caller is a single collector that
     * awaits this, so a second call cannot begin while the first is running.
     * A guard that can never fire is a claim about the code that stops being
     * true silently, and the serialisation it was guessing at is worth stating
     * where it happens instead.
     */
    private suspend fun ensureYear(date: LocalDate) {
        val openingYear = SchoolYear.openingYearOf(date)
        if (openingYear in uiState.value.syncedYears) return
        if (openingYear in refused.value) return
        loadingYear.value = openingYear
        try {
            if (timetableRepository.refreshYear(openingYear) !is SyncResult.Success) {
                refused.value = refused.value + openingYear
            }
        } finally {
            // In a `finally`, because a cancelled fetch — the screen left, the
            // class switched — must not leave the year marked as loading for
            // the life of the view model, which would stop every later request
            // for every other year.
            loadingYear.value = null
        }
    }

    /** Turns the day ribbon's progress the other way up, and remembers it. */
    fun setRibbonFlow(flow: RibbonFlow) {
        viewModelScope.launch { settingsRepository.update { it.copy(dayRibbonFlow = flow) } }
    }

    /** Switches the ribbon's magnetic scrolling, and remembers it. */
    fun setRibbonSnap(snap: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(dayRibbonSnap = snap) } }
    }

    /** Switches the ribbon's depth — the tilt, the gradients, the shader. */
    fun setRibbonDepth(depth: Boolean) {
        viewModelScope.launch { settingsRepository.update { it.copy(dayRibbonDepth = depth) } }
    }

    /** Clears every filter, which is the resting state rather than «show nothing». */
    fun clearFilters() {
        viewModelScope.launch { settingsRepository.update { it.copy(calendarFilters = emptySet()) } }
    }

    private fun step(direction: Long) {
        // uiState.value.today, not LocalDate.now(): "today" on this screen is
        // the school's, and stepping a week from the device's date lands on a
        // different week for a phone in another zone than the one the strip is
        // drawing. Only ever reached from the UI, which is collecting, so the
        // state has the answer the grid was built from.
        val from = anchor.value ?: uiState.value.today
        val moved = when (view.value) {
            ScheduleView.WEEK -> from.plusWeeks(direction)
            ScheduleView.MONTH -> from.plusMonths(direction)
            // A day at a time for the ribbon, which draws one; a month at a
            // time for the list, which is a list of one. The arrow steps
            // whatever is on screen, which is the only rule that needs no
            // explaining to the person pressing it.
            // `uiState.value`, for the same reason `today` above is read from
            // it: this is only ever reached from a UI that is collecting.
            ScheduleView.DAY -> when (uiState.value.dayMode) {
                DayMode.RIBBON -> from.plusDays(direction)
                DayMode.LIST -> from.plusMonths(direction)
            }
        }
        anchor.value = moved
        // In the day view the anchor *is* the selection; in the other two the
        // period moved out from under it, so it lands on the new period's start.
        selected.value = moved
    }

    companion object {
        private const val CLOCK_TICK_MILLIS = 30_000L
        private const val STOP_TIMEOUT_MILLIS = 5_000L

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                WeekViewModel(
                    timetableRepository = Graph.container.timetableRepository,
                    settingsRepository = Graph.container.settingsRepository,
                )
            }
        }
    }
}
