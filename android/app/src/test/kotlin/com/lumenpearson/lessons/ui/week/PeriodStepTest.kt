package com.lumenpearson.lessons.ui.week

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * How far the period arrows move, and what they are therefore allowed to say.
 *
 * The arrows had two readers and the readers disagreed. `step` walked a day in
 * «День»/«Лента» and a month in «Месяц» and «День»/«Список»; the content
 * description under both said «Следующая неделя», in four of the five modes.
 * Nothing a sighted reader sees was wrong — the header text beside the arrows
 * has always named the right span — so the only thing that could have caught it
 * was TalkBack or this.
 *
 * Both halves are pinned here against literal dates rather than against each
 * other. A test that asked «does the label agree with the arithmetic» would
 * stay green if both moved together, which is exactly what unifying them made
 * possible.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PeriodStepTest {

    private val years = MutableStateFlow<Set<Int>>(emptySet())

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = years

        override suspend fun snapshot(): Timetable? = null
        override suspend fun snapshotAroundToday(): Timetable? = null
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
        override suspend fun refresh(): SyncResult = SyncResult.Success
        override suspend fun refreshYear(openingYear: Int): SyncResult = SyncResult.Success
    }

    /** A real read-modify-write, because «Список» is a setting rather than a field. */
    private val stored = MutableStateFlow(AppSettings())

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = stored
        override suspend fun update(transform: (AppSettings) -> AppSettings) {
            stored.value = transform(stored.value)
        }

        override fun languageBlocking() = AppLanguage.SYSTEM
    }

    @Before
    fun setUp() {
        // Unconfined, so a `viewModelScope.launch` reaches its first suspension
        // before the line that started it returns. No `runTest`: it would drain
        // this model's `while (true) { emit(now); delay(30s) }` clock for ever
        // — see `YearOnDemandTest`, which pays for the same thing.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A Thursday in the middle of a month, so a day, a week and a month all differ. */
    private val thursday: LocalDate = LocalDate.of(2026, 9, 10)

    /**
     * A model parked on [thursday] in the given mode.
     *
     * `select` then `setView`, because `setView` is what moves the anchor onto
     * the selection — and the anchor is what the arrows step.
     */
    private fun modelAt(view: ScheduleView, dayMode: DayMode): WeekViewModel {
        stored.value = AppSettings(dayMode = dayMode)
        return WeekViewModel(timetables, settings).apply {
            select(thursday)
            setView(view)
        }
    }

    @Test
    fun `the week view steps a week and says so`() {
        val model = modelAt(ScheduleView.WEEK, DayMode.RIBBON)

        assertEquals(PeriodStep.WEEK, model.uiState.value.periodStep)
        model.showNext()
        assertEquals(LocalDate.of(2026, 9, 17), model.uiState.value.anchor)
    }

    @Test
    fun `the month view steps a month and says so`() {
        val model = modelAt(ScheduleView.MONTH, DayMode.RIBBON)

        assertEquals(PeriodStep.MONTH, model.uiState.value.periodStep)
        model.showNext()
        assertEquals(LocalDate.of(2026, 10, 10), model.uiState.value.anchor)
    }

    @Test
    fun `the ribbon steps a day and says so`() {
        val model = modelAt(ScheduleView.DAY, DayMode.RIBBON)

        assertEquals(PeriodStep.DAY, model.uiState.value.periodStep)
        model.showNext()
        assertEquals(LocalDate.of(2026, 9, 11), model.uiState.value.anchor)
    }

    /**
     * The mode the merge of the two «День» tabs widened the defect into: a list
     * of a month, stepped a month at a time, announced as a week.
     */
    @Test
    fun `the day list steps a month and says so`() {
        val model = modelAt(ScheduleView.DAY, DayMode.LIST)

        assertEquals(PeriodStep.MONTH, model.uiState.value.periodStep)
        model.showNext()
        assertEquals(LocalDate.of(2026, 10, 10), model.uiState.value.anchor)
    }

    @Test
    fun `stepping back is the same amount the other way`() {
        val model = modelAt(ScheduleView.DAY, DayMode.RIBBON)

        model.showPrevious()

        assertEquals(LocalDate.of(2026, 9, 9), model.uiState.value.anchor)
    }
}
