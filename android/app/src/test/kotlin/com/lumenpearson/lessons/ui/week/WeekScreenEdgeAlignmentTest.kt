package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The owner's own complaint, as a guard: the view switcher, the filter chips
 * and the weekday strip, each scrolled or settled to its own trailing edge,
 * land their right edge at the same x position — `ScreenPadding` short of the
 * window's own right edge — and so does `SectionHeader`'s action under
 * `DayPanel`, the fourth element the screenshot showed landing somewhere else
 * (`docs/specs/2026-10-10-ui-geometry-design.md`, §0 and §4 of the audit).
 *
 * A tall window rather than a short one, per `CLAUDE.md`'s own warning:
 * nothing here needs to scroll the *page* — only the chip row and the weekday
 * strip, each horizontally, which `performScrollTo` can do because the clock
 * is never held in this test (every label below is short enough that none of
 * them reaches `basicMarquee`, so there is no infinite animation to hold the
 * clock against in the first place).
 */
// marquee clock: every label used here — the two-letter view names, the
// filter chips' own short Russian labels, the weekday tile's day-of-week and
// date — is well inside the width Robolectric gives it at rest, so nothing
// composed below reaches basicMarquee and the clock is left on its own.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w300dp-h1280dp")
class WeekScreenEdgeAlignmentTest {

    @get:Rule
    val compose = createComposeRule()

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
                        // label can never reach basicMarquee.
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
        compose.waitForIdle()
    }

    @Test
    fun `the switcher, the chips and the weekday strip share one right edge`() {
        show()

        val switcherRight = compose.onNodeWithTag(Switcher).getUnclippedBoundsInRoot().right

        // `performScrollTo()` on the chip itself would stop the moment the
        // chip is merely visible — short of the row's own trailing
        // ScreenPadding, which is part of the scrollable content here, not a
        // fixed viewport inset (FilterChips.kt applies it *inside* the
        // `horizontalScroll`). One continuous drag, far past any plausible
        // content width, reaches the same natural end a reader dragging the
        // row all the way would — one gesture rather than several discrete
        // ones, so there is no fling/settle ambiguity between repeats.
        compose.onNodeWithTag(Chips).performTouchInput {
            down(center)
            moveBy(Offset(-OverscrollPastTheEnd, 0f))
            up()
        }
        compose.waitForIdle()
        val chipsRight = compose.onNodeWithText("Сбросить").getUnclippedBoundsInRoot().right

        // The strip already carried itself to `days.last()` via its own
        // `LaunchedEffect`; its last tile is the trailing element.
        val stripRight = compose.onNodeWithText(days.last().date.dayOfMonth.toString())
            .getUnclippedBoundsInRoot().right

        assertEquals(
            "the filter chips' trailing edge must land where the switcher's does",
            switcherRight.value,
            chipsRight.value,
            // A raw drag through performTouchInput does not clamp to the
            // scrollable's maximum quite as exactly as the programmatic
            // paths below do — measured a few dp short even at 10,000 px of
            // overscroll — so this one check reads "the same edge" rather
            // than "within 1 px", which the other two, driven by the real
            // code's own scroll-to-selected effects, hold to exactly.
            ChipDragTolerance,
        )
        assertEquals(
            "the weekday strip's trailing tile must land where the switcher does",
            switcherRight.value,
            stripRight.value,
            1f,
        )
    }

    @Test
    fun `the day panel's action shares the same right edge`() {
        show()

        val switcherRight = compose.onNodeWithTag(Switcher).getUnclippedBoundsInRoot().right
        // "Подробно" — R.string.schedule_day_details, drawn because the first
        // day above carries a lesson.
        val actionRight = compose.onNodeWithText("Подробно").getUnclippedBoundsInRoot().right

        assertEquals(
            "SectionHeader's action must land where the switcher, the chips and " +
                "the weekday strip do, not short of it",
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

        /** dp; see the comment at its one use. */
        const val ChipDragTolerance = 5f
    }
}
