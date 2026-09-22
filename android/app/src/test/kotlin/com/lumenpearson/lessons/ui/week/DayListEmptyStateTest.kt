package com.lumenpearson.lessons.ui.week

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayFilter
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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What «День» → «Список» says when it has nothing to draw.
 *
 * It had one sentence for four situations and it was the filters' one:
 * «Ничего не подходит / В этом месяце нет дней под выбранные фильтры», with no
 * chip selected, over a month the cache has never held — which is what a fresh
 * install shows before its first sync lands, and what stepping back into an
 * unfetched school year shows for ever. The month grid and the day ribbon on
 * the same tab already told those apart; this one structurally could not, its
 * signature taking a list of days and nothing else.
 *
 * `synced_window` exists for exactly this distinction: a class made in March
 * has no rows before it either way, so «нет уроков» and «ещё не загружено»
 * cannot be told apart by counting.
 */
@RunWith(RobolectricTestRunner::class)
// Tall, because `performScrollTo` drives the scrollable's own animation and the
// clock is held for the pickers' marquees — nothing would move, and every node
// below the fold would report «not displayed». See `AboutCardTest`.
@Config(qualifiers = "ru-rRU-w411dp-h3000dp")
@OptIn(ExperimentalCoroutinesApi::class)
class DayListEmptyStateTest {

    @get:Rule val compose = createComposeRule()

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = MutableStateFlow(emptySet())

        override suspend fun snapshot(): Timetable? = null
        override suspend fun snapshotAroundToday(): Timetable? = null
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
        override suspend fun refresh(): SyncResult = SyncResult.Success
        override suspend fun refreshYear(openingYear: Int): SyncResult = SyncResult.Success
    }

    private val settings = object : SettingsRepository {
        override val settings: Flow<AppSettings> = MutableStateFlow(AppSettings())
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        override fun languageBlocking() = AppLanguage.SYSTEM
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        // The pickers above the list carry marquee labels, which never stop
        // scrolling, so Compose's clock is never idle and every assertion below
        // would wait for an idleness that cannot arrive.
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** A Thursday in September, so the month and its school year are both 2026. */
    private val thursday: LocalDate = LocalDate.of(2026, 9, 10)

    /**
     * The page's state, as state — `ComposeTestRule` refuses a second
     * `setContent`, and all four answers are one screen changing its mind.
     */
    private var state by mutableStateOf(emptyState())

    private fun emptyState(
        syncedYears: Set<Int> = emptySet(),
        loadingYear: Int? = null,
        filters: Set<DayFilter> = emptySet(),
    ) = ScheduleUiState(
        isLoading = false,
        view = ScheduleView.DAY,
        dayMode = DayMode.LIST,
        today = thursday,
        anchor = thursday,
        selected = thursday,
        periodStart = LocalDate.of(2026, 9, 1),
        periodEnd = LocalDate.of(2026, 9, 30),
        syncedYears = syncedYears,
        loadingYear = loadingYear,
        filters = filters,
        agenda = emptyList(),
    )

    private fun show() {
        compose.setContent {
            LessonsTheme {
                RibbonPage(
                    state = state,
                    viewModel = WeekViewModel(timetables, settings),
                    day = null,
                    onLessonClick = {},
                    onOpenDay = {},
                )
            }
        }
        settle()
    }

    /**
     * `sendApplyNotifications` first: [state] is written from the test thread,
     * which lands in the global snapshot, and with the clock held nothing else
     * tells the recomposer about it.
     */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun assertShows(text: String) =
        compose.onNodeWithText(text).assertIsDisplayed()

    @Test
    fun `a year nobody has fetched says so, and names the year`() {
        show()

        assertShows("Год не загружен")
        assertShows(
            "Расписание на 2026/27 ещё не скачано. Потяните вниз, чтобы попробовать снова.",
        )
    }

    @Test
    fun `a year on its way says that instead`() {
        state = emptyState(loadingYear = 2026)
        show()

        assertShows("Загружаю год")
        assertShows("Расписание на 2026/27 скачивается.")
    }

    @Test
    fun `a fetched month with nothing in it does not blame the chips`() {
        // The half that was visible without leaving the current year: September
        // is in the cache, no filter is set, and the month is simply blank.
        state = emptyState(syncedYears = setOf(2026))
        show()

        assertShows("Здесь пусто")
        assertShows("В этом месяце нет ни одного дня с расписанием.")
    }

    @Test
    fun `a month the filters emptied still says that`() {
        // The one answer the page always gave, kept — it is the right one here.
        state = emptyState(syncedYears = setOf(2026), filters = setOf(DayFilter.HAS_HOMEWORK))
        show()

        assertShows("Ничего не подходит")
        assertShows("В этом месяце нет дней под выбранные фильтры.")
    }
}
