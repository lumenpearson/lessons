package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.component.SegmentedPicker
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.SchoolDay
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The owner's own complaint, as a guard: the view switcher, the filter chips
 * and the weekday strip, each scrolled or settled to its own trailing edge,
 * land their right edge at the same x position — `ScreenPadding` short of the
 * window's own right edge — and so does the ink of `SectionHeader`'s action
 * under `DayPanel`, the fourth element the screenshot showed landing
 * somewhere else (`docs/specs/2026-10-10-ui-geometry-design.md`, §0 and §4 of
 * the audit).
 *
 * [GraphicsMode.Mode.NATIVE], not Robolectric's `LEGACY` default: under
 * `LEGACY` Cyrillic text records at a pixel or so a letter
 * (`ToolbarLabelRevealTest`'s own note), which measured every chip and tile
 * here with no real width to assert about — the first fix round's 5 dp
 * tolerance on the chip row was covering exactly that artefact, not a real
 * few-dp slack in the geometry. `NATIVE`, at 411 dp wide, gives the custom
 * bundled font its real metrics, wide enough that the day panel's title
 * still fits on one line and narrow enough that the filter chips and the
 * weekday strip still overflow and have somewhere to scroll to.
 *
 * The clock is held (`mainClock.autoAdvance = false`), per `CLAUDE.md`'s own
 * note on a Compose test that holds the clock: every write made from the
 * test thread is followed by `Snapshot.sendApplyNotifications()` and a
 * handful of frames, through [settle]. Held rather than left to
 * auto-advance because the weekday strip's own `LaunchedEffect` drives an
 * *animated* scroll (`animateScrollToItem`) that needs real frames to
 * finish, and because the day panel's title, under `NATIVE` metrics, is
 * long enough that whether it marquees depends on exactly how wide this
 * window is — held, it cannot hang either way, which is what makes guessing
 * that correctly unnecessary. It never affects what either test asserts:
 * the title is a plain `MarqueeText`, but the action beside it is drawn
 * through the design system's plain `Text`, which never marquees, and the
 * filter chips size to their own content rather than being squeezed into a
 * marquee-triggering width in the first place.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1280dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WeekScreenEdgeAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private val today: LocalDate = LocalDate.of(2026, 9, 14)

    /** Ten days, so the weekday strip has somewhere to scroll to. */
    private val days: List<WeekDayUi> = (0 until 10).map { offset ->
        val date = today.plusDays(offset.toLong())
        WeekDayUi(
            date = date,
            day = SchoolDay(
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
            ),
            isToday = offset == 0,
        )
    }

    /**
     * A write made from here, then enough frames for it to be answered — the
     * same pattern `DayModeScrollReportTest` uses for the same reason:
     * `sendApplyNotifications` first, because a write from the test thread
     * otherwise lands in the global snapshot with nobody to wake the
     * recomposer or `snapshotFlow` to read it.
     */
    private fun settle(frames: Int = 6) {
        Snapshot.sendApplyNotifications()
        repeat(frames) { compose.mainClock.advanceTimeByFrame() }
    }

    private fun show() {
        compose.setContent {
            LessonsTheme {
                Column(verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
                    SegmentedPicker(
                        items = ScheduleView.entries,
                        selectedItem = ScheduleView.WEEK,
                        onItemSelected = {},
                        // Short and fixed, not the real resource strings: this
                        // test is about geometry, not wording, and a two-letter
                        // label can never overflow its own segment.
                        labelProvider = { it.name.take(2) },
                        containerColor = MaterialTheme.colorScheme.rowContainer,
                        contentPadding = PaddingValues(4.dp),
                        modifier = Modifier
                            .padding(horizontal = ScreenPadding)
                            .testTag(Switcher),
                    )
                    FilterChips(
                        active = setOf(DayFilter.MARKED),
                        onToggle = {},
                        onClear = {},
                        modifier = Modifier.testTag(Chips),
                    )
                    WeekdaySelector(
                        days = days,
                        // The last day, so the strip's own scroll-to-selected
                        // effect carries it to the end on its own — the same
                        // mechanism the real screen relies on, not a fake.
                        selected = days.last().date,
                        showLoad = false,
                        onSelect = {},
                    )
                    DayPanel(
                        day = days.first(),
                        date = days.first().date,
                        loadingYear = false,
                        showTeacher = true,
                        showEvents = true,
                        showHomework = true,
                        onLessonClick = {},
                        onOpenDay = {},
                    )
                }
            }
        }
        // The weekday strip's own LaunchedEffect animates to days.last(); 60
        // frames is generous for a spring that settles in a few dozen at most
        // (ToolbarLabelRevealTest's own tests settle the same family of
        // animation within fewer).
        settle(frames = 60)
    }

    /**
     * A forward guard, said plainly rather than implied: these three
     * elements' own `ScreenPadding` usage predates this whole geometry pass
     * and was never the defect, so this test cannot fail on any code this
     * repository's history actually shipped — only [DayPanel]'s action,
     * covered below, does that. What this proves instead is something
     * `GeometryScaleTest`'s source scan structurally cannot: that three
     * independently-padded elements' right edges actually land at the same
     * x position once laid out, not merely that each one's own literal
     * reads `ScreenPadding` by name.
     */
    @Test
    fun `the switcher, the chips and the weekday strip share one right edge`() {
        show()

        val switcherRight = compose.onNodeWithTag(Switcher).getUnclippedBoundsInRoot().right

        // One continuous drag, far past any plausible content width, reaches
        // the row's own natural end the same way a reader dragging it all
        // the way would — a raw touch sequence, not `performScrollTo`, which
        // drives the scrollable through an *animation* and would need the
        // clock this test holds to be advanced by exactly the right amount
        // first; a plain drag's own scroll response is synchronous with the
        // gesture, so `settle` only has to let the recomposition that
        // followed it be read back.
        compose.onNodeWithTag(Chips).performTouchInput {
            down(center)
            moveBy(Offset(-OverscrollPastTheEnd, 0f))
            up()
        }
        settle()
        val chipsRight = compose.onNodeWithText("Сбросить").getUnclippedBoundsInRoot().right

        // The strip already carried itself to `days.last()` via its own
        // `LaunchedEffect`, settled above; its last tile is the trailing
        // element.
        val stripRight = compose.onNodeWithText(days.last().date.dayOfMonth.toString())
            .getUnclippedBoundsInRoot().right

        assertEquals(
            "the filter chips' trailing edge must land where the switcher's does",
            switcherRight.value,
            chipsRight.value,
            1f,
        )
        assertEquals(
            "the weekday strip's trailing tile must land where the switcher does",
            switcherRight.value,
            stripRight.value,
            1f,
        )
    }

    @Test
    fun `the day panel's action shares the same right edge, ink to ink`() {
        show()

        val switcherRight = compose.onNodeWithTag(Switcher).getUnclippedBoundsInRoot().right

        // "Подробно" — R.string.schedule_day_details, drawn because the
        // first day above carries a lesson. `onNodeWithText` resolves to the
        // merged `TextButton` node, i.e. its own bounds — which still sit
        // `TextButtonEndPadding` outside the label and the arrow actually
        // drawn, because a `TextButton` pads its own content on every side.
        // Subtracting it is what turns "the button's box lines up" into
        // "what the reader sees lines up", the distinction the first fix
        // round's version of this test missed entirely.
        val buttonRight = compose.onNodeWithText("Подробно").getUnclippedBoundsInRoot().right
        val actionRight = buttonRight - TextButtonEndPadding

        assertEquals(
            "SectionHeader's action must put its ink where the switcher, the " +
                "chips and the weekday strip put their own edge, not merely its " +
                "button's invisible bounds there",
            switcherRight.value,
            actionRight.value,
            1f,
        )
    }

    private companion object {
        const val Switcher = "switcher"
        const val Chips = "chips"

        /** Pixels, not dp: far past any plausible content width, so the one
         *  drag clamps at the scrollable's true end rather than approaching it. */
        const val OverscrollPastTheEnd = 10_000f

        /** Material's own `TextButtonContentPadding` end value — the same
         *  number `SectionHeader`'s own `TextButtonEndPadding` reads, named
         *  again here so this test does not have to reach into a private
         *  constant to state what it is measuring around. */
        val TextButtonEndPadding = 12.dp
    }
}
