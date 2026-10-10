package com.lumenpearson.lessons.ui.week

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolClassInfo
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.Timetable
import java.time.LocalDate
import java.time.LocalTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's own complaint, as a guard, on the real «Календарь»: the view
 * switcher, the filter chips, the weekday strip and the ink of the day panel's
 * «Подробно» end on one right edge — `ScreenPadding` short of the window's —
 * the four things the screenshot showed ending in four places
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, «What the audit found»).
 *
 * The real [WeekScreen] over a real [WeekViewModel], fed the fakes
 * `DayModeScrollReportTest` and `PeriodStepTest` build, rather than a column
 * copied out of it: the copy had already drifted from the screen once, and it
 * drew a ten-day strip the screen never draws, long enough to overflow, which
 * is the one case in which a start-aligned strip reaches the edge. The weeks
 * here are the real ones, seven days and five with the weekends hidden, under
 * the `ru` locale, at the two widths that decide whether a week fits: 360 dp,
 * the narrowest phone, and 411, the emulator's.
 *
 * Five days fit both widths and the strip fills them with equal tiles, so its
 * last tile is on the edge as drawn. Seven fit neither — 7 × 48 + 6 × 8 = 384 dp
 * between the margins wants a 416 dp window — so the strip scrolls, and its last
 * tile is on the edge once scrolled to its end, which is how it is measured.
 * The chips overflow in Russian at both widths and are measured the same way.
 * Against the content-sized strip that came before, the five-day weeks and the
 * seven at 411 end short and fail; the seven at 360 overflowed then too.
 *
 * [GraphicsMode.Mode.NATIVE], not Robolectric's `LEGACY` default: under
 * `LEGACY` Cyrillic text records at a pixel or so a letter
 * (`ToolbarLabelRevealTest`'s own note), which leaves the chips and the tiles
 * no real width to assert about. With real metrics every edge here is a whole
 * pixel at this density, which is why the tolerance is half of one.
 *
 * The clock is held (`mainClock.autoAdvance = false`), per `CLAUDE.md`'s note
 * on a Compose test that holds the clock: the pickers carry marquee labels,
 * which never let it idle, and every write from the test thread is followed by
 * `Snapshot.sendApplyNotifications()` and frames, through [settle]. The strip's
 * scroll-to-selected is an animation and needs those frames to finish, and so
 * does the period's own slide in. A raw drag stands in for `performScrollTo`,
 * which drives an animation the held clock would not run.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class WeekScreenEdgeAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() {
        // viewModelScope runs on Main; unconfined, so the state is built before
        // the line that asked for it returns, and the model's thirty-second
        // clock parks on a delay nothing advances rather than ticking under
        // the test.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** Monday 14 – Sunday 20 September 2026, with Wednesday in focus. */
    private val monday: LocalDate = LocalDate.of(2026, 9, 14)
    private val wednesday: LocalDate = monday.plusDays(2)

    /** A lesson on every day of the month, so the panel has «Подробно» to draw. */
    private val september = Timetable(
        schoolClass = SchoolClassInfo(id = 1L, name = "9А"),
        days = (1..30).map { dayOfMonth ->
            val date = LocalDate.of(2026, 9, dayOfMonth)
            SchoolDay(
                date = date,
                weekday = date.dayOfWeek.value,
                lessons = listOf(
                    Lesson(
                        index = 1,
                        subject = "Алгебра",
                        startsAt = LocalTime.of(8, 30),
                        endsAt = LocalTime.of(9, 15),
                    ),
                ),
            )
        },
    )

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(september)
        override val syncedYears: Flow<Set<Int>> = MutableStateFlow(setOf(2026))

        override suspend fun snapshot(): Timetable? = september
        override suspend fun snapshotAroundToday(): Timetable? = september
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
        override suspend fun refresh(): SyncResult = SyncResult.Success
        override suspend fun refreshYear(openingYear: Int): SyncResult = SyncResult.Success
    }

    private fun settings(showWeekends: Boolean) = object : SettingsRepository {
        // A filter on, so «Сбросить» is drawn: it is the chip row's last item.
        override val settings: Flow<AppSettings> = MutableStateFlow(
            AppSettings(weekShowWeekends = showWeekends, calendarFilters = setOf(DayFilter.MARKED)),
        )
        override suspend fun update(transform: (AppSettings) -> AppSettings) = Unit
        override fun languageBlocking() = AppLanguage.SYSTEM
    }

    /**
     * A write made from here, then enough frames for it to be answered:
     * `sendApplyNotifications` first, because a write from the test thread
     * otherwise lands in the global snapshot with nobody to wake the
     * recomposer or `snapshotFlow` to read it.
     */
    private fun settle(frames: Int = 6) {
        Snapshot.sendApplyNotifications()
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    /**
     * The calendar's week of [monday], Wednesday selected — `select` and then
     * `setView`, because `setView` is what moves the anchor onto the selection.
     */
    private fun show(showWeekends: Boolean) {
        val model = WeekViewModel(timetables, settings(showWeekends)).apply {
            select(wednesday)
            setView(ScheduleView.WEEK)
        }
        compose.setContent {
            LessonsTheme {
                WeekScreen(viewModel = model)
            }
        }
        // Generous: the period slides in over 260 ms, the strip springs to
        // the selected day, and the strip learns its width a frame late.
        settle(frames = 60)
    }

    /** One continuous drag far past any content width, from [node], to the scrollable's end. */
    private fun dragToTheEnd(node: SemanticsNodeInteraction) {
        node.performTouchInput {
            down(center)
            moveBy(Offset(-OverscrollPastTheEnd, 0f))
            up()
        }
        settle(frames = 30)
    }

    private fun tile(date: LocalDate): SemanticsNodeInteraction =
        compose.onNode(hasText(date.dayOfMonth.toString()) and hasClickAction())

    private fun assertOneRightEdge(lastDay: LocalDate) {
        // The switcher has no node of its own; its last segment does, inside
        // the tray's inset.
        val edge = compose.onNodeWithText("День").getUnclippedBoundsInRoot().right + SwitcherTrayInset

        dragToTheEnd(compose.onNodeWithText("С уроками"))
        val chips = compose.onNodeWithText("Сбросить").getUnclippedBoundsInRoot().right

        // From the selected tile, which the scroll-to has put on screen. A
        // strip that fits does not scroll, and the drag, ending far outside
        // the tile, is not a press.
        dragToTheEnd(tile(wednesday))
        val last = tile(lastDay).getUnclippedBoundsInRoot()

        // «Подробно» — R.string.schedule_day_details. Its node is the
        // TextButton's, whose box sits TextButtonEndPadding outside the label
        // and the arrow actually drawn; taking it off turns "the button's box
        // lines up" into "what the reader sees lines up".
        val action = compose.onNodeWithText("Подробно").getUnclippedBoundsInRoot().right - TextButtonEndPadding

        assertEquals("the chips' trailing edge must land on the switcher's", edge.value, chips.value, Tolerance)
        assertEquals(
            "the strip's last tile must land on the switcher's edge",
            edge.value,
            last.right.value,
            Tolerance,
        )
        assertEquals(
            "the day panel's action must put its ink on the switcher's edge",
            edge.value,
            action.value,
            Tolerance,
        )
        assertTrue(
            "a weekday tile is its own 48 dp touch target; the last one is ${last.width.value} dp wide",
            last.width.value >= WeekdayTileMinWidth.value - Tolerance,
        )
    }

    private val DpRect.width: Dp get() = right - left

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h1600dp")
    fun `a week of seven shares one right edge at 360 dp`() {
        show(showWeekends = true)
        assertOneRightEdge(lastDay = monday.plusDays(6))
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp-h1600dp")
    fun `a week of seven shares one right edge at 411 dp`() {
        show(showWeekends = true)
        assertOneRightEdge(lastDay = monday.plusDays(6))
    }

    @Test
    @Config(qualifiers = "ru-rRU-w360dp-h1600dp")
    fun `a week of five shares one right edge at 360 dp`() {
        show(showWeekends = false)
        assertOneRightEdge(lastDay = monday.plusDays(4))
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp-h1600dp")
    fun `a week of five shares one right edge at 411 dp`() {
        show(showWeekends = false)
        assertOneRightEdge(lastDay = monday.plusDays(4))
    }

    private companion object {
        /** Pixels, not dp: far past any content width, so one drag clamps at the true end. */
        const val OverscrollPastTheEnd = 10_000f

        /** Half a pixel at this density, mdpi, where every edge here is a whole pixel. */
        const val Tolerance = 0.5f

        /** `SegmentedPicker`'s default `contentPadding`, the tray the real call draws. */
        val SwitcherTrayInset = 4.dp

        /** Material's `TextButtonContentPadding` end, the 12 dp `SectionHeader` gives back. */
        val TextButtonEndPadding = 12.dp

        /** `CalendarGrids.kt`'s private floor, named again rather than reached into. */
        val WeekdayTileMinWidth = 48.dp
    }
}
