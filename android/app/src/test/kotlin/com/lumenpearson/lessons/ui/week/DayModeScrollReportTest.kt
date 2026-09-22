package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.junit4.createComposeRule
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Which scrollable «День» reports to the shell, in each of its two readings.
 *
 * The shell fades the status bar on the offset the screen in front of it
 * reports, and «День» has two scrollables and reported one. Scroll the ribbon,
 * press «Список», and the blur stayed fully applied over a list sitting at its
 * top — and nothing the list did afterwards could change it, because the
 * ribbon's state survives the switch and a list scrolled past its first item
 * reports `Float.MAX_VALUE` rather than its true offset.
 *
 * Both states are hoisted into [RibbonPage] so that this can scroll one without
 * a gesture: the clock is held for the pickers' marquees, and a driven scroll
 * would move nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h640dp")
@OptIn(ExperimentalCoroutinesApi::class)
class DayModeScrollReportTest {

    @get:Rule val compose = createComposeRule()

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = MutableStateFlow(setOf(2026))

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
        // The pickers carry marquee labels, which never stop, so the clock is
        // never idle and every wait below would be for an idleness that cannot
        // arrive. Frames are advanced by hand instead.
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val thursday: LocalDate = LocalDate.of(2026, 9, 10)

    /** A day with enough lessons for the ribbon to have somewhere to scroll to. */
    private fun busyDay(date: LocalDate) = SchoolDay(
        date = date,
        weekday = date.dayOfWeek.value,
        lessons = (1..8).map { index ->
            Lesson(
                index = index,
                subject = "Предмет $index",
                startsAt = LocalTime.of(8, 0).plusMinutes((index - 1) * 45L),
                endsAt = LocalTime.of(8, 45).plusMinutes((index - 1) * 45L),
            )
        },
    )

    /** A month of days, so the list is longer than the window it sits in. */
    private val month: List<SchoolDay> =
        (1..30).map { day -> busyDay(LocalDate.of(2026, 9, day)) }

    private var mode by mutableStateOf(DayMode.RIBBON)

    private val holder = ScrollOffsetHolder()
    private val ribbonState = LazyListState()
    private val agendaState = ScrollState(initial = 0)

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalScrollOffset provides holder) {
                LessonsTheme {
                    RibbonPage(
                        state = ScheduleUiState(
                            isLoading = false,
                            view = ScheduleView.DAY,
                            dayMode = mode,
                            today = thursday,
                            anchor = thursday,
                            selected = thursday,
                            periodStart = LocalDate.of(2026, 9, 1),
                            periodEnd = LocalDate.of(2026, 9, 30),
                            syncedYears = setOf(2026),
                            agenda = month,
                        ),
                        viewModel = WeekViewModel(timetables, settings),
                        day = busyDay(thursday),
                        onLessonClick = {},
                        onOpenDay = {},
                        ribbonState = ribbonState,
                        agendaState = agendaState,
                    )
                }
            }
        }
        settle()
    }

    /**
     * A write made from here, then enough frames for it to be answered.
     *
     * `sendApplyNotifications` first, and it is not optional: a scroll or a
     * mode switch performed from the test thread lands in the global snapshot,
     * and with the clock held nothing else sends the apply notification that
     * both the recomposer and `snapshotFlow` wake on. Without it the offset
     * this test is about is reported after the last assertion has read it.
     */
    private fun settle() {
        Snapshot.sendApplyNotifications()
        repeat(6) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `the list page reports its own scroll`() {
        mode = DayMode.LIST
        show()

        assertEquals("at rest", 0f, holder.value, 0f)

        // `dispatchRawDelta` rather than a gesture or `performScrollTo`: both
        // of those need the clock this test is holding.
        agendaState.dispatchRawDelta(200f)
        settle()

        assertTrue("reported ${holder.value}", holder.value > 0f)
    }

    @Test
    fun `switching to the list drops the ribbon's stale offset`() {
        // The reported symptom, in order: scroll the ribbon, press «Список».
        show()
        runBlocking { ribbonState.scrollToItem(3) }
        settle()
        assertEquals("the ribbon is scrolled", Float.MAX_VALUE, holder.value, 0f)

        mode = DayMode.LIST
        settle()

        // The list is at its top, so the blur over it has to be too.
        assertEquals(0f, holder.value, 0f)
    }
}
