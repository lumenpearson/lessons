package com.lumenpearson.lessons.ui.week

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.SchoolYear
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
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
 * What the calendar asks for when it is scrolled out of the year it holds.
 *
 * The cache keeps a few school years and fetches one when the calendar steps
 * into it, which is the half of «прокрутка по годам» that can go wrong quietly:
 * a request per press while somebody steps back and forth over 1 September, or
 * no request at all because the fetch was hung off one of the four ways the
 * anchor moves and not the others.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class YearOnDemandTest {

    /** Every year this view model asked the repository for, in order. */
    private val asked = mutableListOf<Int>()

    private val years = MutableStateFlow<Set<Int>>(emptySet())

    /** Set to park the next fetch, so «already in flight» can be a test line. */
    private var gate: CompletableDeferred<Unit>? = null

    private var answer: SyncResult = SyncResult.Success

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = years

        override suspend fun snapshot(): Timetable? = null
        override suspend fun snapshotAroundToday(): Timetable? = null
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit

        override suspend fun refresh(): SyncResult = refreshYear(
            SchoolYear.openingYearOf(LocalDate.now()),
        )

        override suspend fun refreshYear(openingYear: Int): SyncResult {
            asked += openingYear
            gate?.await()
            if (answer is SyncResult.Success) {
                years.value = years.value + openingYear
            }
            return answer
        }
    }

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        override fun languageBlocking() = AppLanguage.SYSTEM
    }

    @Before
    fun setUp() {
        // viewModelScope runs on Dispatchers.Main; unconfined so a launch
        // reaches its first suspension before the line that started it returns.
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    // Deliberately no `runTest` anywhere below, and that is not a style choice.
    // `runTest` shares its scheduler with `Dispatchers.Main` when Main is a test
    // dispatcher, and then drains every pending delay by advancing virtual time
    // — which against this view model never finishes, because its clock is
    // `while (true) { emit(now); delay(30s) }`. The first version of this file
    // hung the whole Gradle run. Nothing here needs virtual time: the fetches
    // are launched eagerly by the unconfined Main and the assertions are about
    // what was asked for, not about when.

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun model() = WeekViewModel(timetables, settings)

    /** Today's school year, whatever day this test is run on. */
    private val thisYear: Int get() = SchoolYear.openingYearOf(LocalDate.now())

    @Test
    fun `the year on screen is asked for as soon as the screen has one`() {
        val model = model()
        model.uiState.value

        // The collector in `init` watches the period that is drawn, so the
        // first emission is already a request: a calendar that only fetched
        // when *stepped* would open on an empty grid after a fresh install.
        assertEquals(listOf(thisYear), asked)
    }

    @Test
    fun `stepping into another year asks for that year once`() {
        val model = model()
        asked.clear()

        model.openYear(thisYear + 1)

        assertEquals(listOf(thisYear + 1), asked)
    }

    @Test
    fun `a year already held is not asked for again`() {
        val model = model()
        model.openYear(thisYear + 1)
        asked.clear()

        // Back and forth over 1 September. Both years are in the cache now, so
        // neither press is a request — this is the case that turns a calendar
        // into a phone that downloads a school year per tap.
        model.openYear(thisYear)
        model.openYear(thisYear + 1)
        model.openYear(thisYear)

        assertEquals(emptyList<Int>(), asked)
    }

    @Test
    fun `a year already in flight is not asked for twice`() {
        val model = model()
        asked.clear()
        // After the model exists, so the year it opens on is fetched normally
        // and it is the *next* request that parks.
        gate = CompletableDeferred()

        model.openYear(thisYear + 1)
        model.openYear(thisYear + 1)
        model.openYear(thisYear + 1)

        assertEquals(listOf(thisYear + 1), asked)
        gate?.complete(Unit)
    }

    @Test
    fun `a year asked for while another was in flight is not lost`() {
        val model = model()
        asked.clear()
        val parked = CompletableDeferred<Unit>()
        gate = parked

        // Stepping quickly: the first fetch parks, so the second is dropped —
        // one at a time is the rule. What must not happen is that it stays
        // dropped: the period has not changed since, so nothing would ask
        // again and the reader would sit on «год не загружен» with no request
        // on its way.
        model.openYear(thisYear + 1)
        model.openYear(thisYear + 2)
        assertEquals(listOf(thisYear + 1), asked)

        gate = null
        parked.complete(Unit)

        assertEquals(listOf(thisYear + 1, thisYear + 2), asked)
    }

    @Test
    fun `a year the server had nothing for is not asked for on every press`() {
        answer = SyncResult.Failed("offline")
        val model = model()
        asked.clear()

        model.openYear(thisYear + 2)
        model.openYear(thisYear)
        model.openYear(thisYear + 2)

        // Once, not twice. The usual cause is the network rather than the year,
        // and «попробовать снова» is what re-opening the app means — but a
        // request per press while somebody scrolls is a phone that spends its
        // battery on the same refusal.
        assertEquals(listOf(thisYear + 2), asked)
    }

    @Test
    fun `the state says which year is on screen and whether it is here`() {
        val model = model()

        assertEquals(thisYear, model.uiState.value.anchorYear)
        assertEquals(true, model.uiState.value.anchorYearFetched)

        answer = SyncResult.Failed("offline")
        model.openYear(thisYear + 1)

        // The screen has to be able to tell «нет уроков» from «ещё не
        // загружено», which is the whole reason `syncedYears` is carried
        // rather than counted off the days.
        assertEquals(thisYear + 1, model.uiState.value.anchorYear)
        assertEquals(false, model.uiState.value.anchorYearFetched)
    }
}
