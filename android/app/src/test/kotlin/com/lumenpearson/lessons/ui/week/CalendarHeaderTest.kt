package com.lumenpearson.lessons.ui.week

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
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
 * The calendar's header, at the width a phone actually has.
 *
 * Reported from a real build: «Календарь» was drawn as «Календар» with its «ь»
 * alone on a second line, and the subtitle ran to three. The header put the
 * title, a year chip and three icon buttons in one `Row` and gave the title
 * `weight(1f)` — about eight characters at 411 dp. Nothing about that looks
 * wrong in the code; `weight(1f)` is the correct way to share a row, and the
 * row was simply asked to hold more than it has.
 *
 * The second half was movement: «вернуться к сегодня» is offered only when it
 * would do something, and while the button itself was what appeared, both
 * arrows slid 48 dp sideways every time the reader stepped into or out of the
 * current week. A control that moves under the thumb between two presses is
 * worse than a gap.
 *
 * Both are layout, so both need the header composed at a real width rather than
 * reasoned about.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp")
// NATIVE, so text has its real width: under LEGACY a line is almost no width
// at all, and nothing here could ever be too wide to share a row.
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CalendarHeaderTest {

    @get:Rule val compose = createComposeRule()

    /** The header carries marquee lines; see `MarqueeTextTest` for the why. */
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    /**
     * Whether the «сегодня» button is offered, as state rather than as an
     * argument.
     *
     * The interesting case is the *change*: the button appears the moment the
     * reader steps out of the current week and goes again when they come back,
     * and what has to stay still is everything beside it. A second
     * `setContent` is refused by the rule, so the one composition is driven
     * from here instead — which is also closer to what happens on the phone.
     */
    private var showToday by mutableStateOf(false)

    /**
     * What one press of an arrow moves, as state for the same reason.
     *
     * The mode is switched under one composition here because that is what the
     * phone does — «Лента» and «Список» are a picker two rows below this
     * header, not two screens.
     */
    private var step by mutableStateOf(PeriodStep.WEEK)

    /** How many times the panel asked for the year picker. */
    private var yearPicks = 0

    private fun show(width: androidx.compose.ui.unit.Dp = 411.dp) {
        compose.setContent {
            LessonsTheme {
                Column(modifier = Modifier.width(width)) {
                    ScheduleHeader(
                        periodLabel = "Вторник, 22 сентября",
                        termLabel = "1 полугодие",
                        yearLabel = "2026/27",
                        step = step,
                        showTodayAction = showToday,
                        onToday = {},
                        onPrevious = {},
                        onNext = {},
                        onPickYear = { yearPicks++ },
                    )
                }
            }
        }
        settle()
    }

    private fun settle() {
        // The state above is written from the test thread, so it lands in the
        // global snapshot; with the clock held nothing else sends the apply
        // notification the recomposer wakes on.
        Snapshot.sendApplyNotifications()
        repeat(4) { compose.mainClock.advanceTimeByFrame() }
    }

    @Test
    fun `the title is one line, whole`() {
        show()

        // Exact text. «Календар» plus a stray «ь» is still a node whose text
        // contains «Календар», so a substring matcher would have passed on the
        // build this was reported from.
        compose.onNodeWithText("Календарь").assertIsDisplayed()
    }

    @Test
    fun `the period sits between the arrows that step it`() {
        // #416: the period was the title's subtitle, a line above the row that
        // stepped it, and that row left an empty band between the year chip
        // and the arrows. Now the period is the panel's middle.
        show()

        val previous = compose.onNodeWithContentDescription("Предыдущая неделя").getBoundsInRoot()
        val next = compose.onNodeWithContentDescription("Следующая неделя").getBoundsInRoot()
        val period = compose.onNodeWithText("Вторник, 22\u00A0сентября", useUnmergedTree = true)
            .getBoundsInRoot()

        assertTrue("after «‹»", period.left >= previous.right)
        assertTrue("before «›»", period.right <= next.left)
        assertTrue("level with the arrows", period.top < previous.bottom && period.bottom > previous.top)
        compose.onNodeWithText("1 полугодие · 2026/27", useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `pressing the period opens the year picker`() {
        // The year is the panel's middle now rather than a chip of its own,
        // and it is still the way into the years either side of this one.
        show()

        compose.onNodeWithText("2026/27", substring = true).performClick()
        settle()

        assertEquals(1, yearPicks)
    }

    @Test
    @Config(qualifiers = "ru-rRU-w320dp", fontScale = 2.0f)
    fun `at a large font on a narrow phone the pill drops under the title and the panel grows`() {
        // The owner's word for overflow was «растягивать по высоте»: nothing is
        // cut or scrolled. The title keeps its line, «Сегодня» takes the next
        // one rather than squeezing it, and the period wraps inside a panel
        // that gets taller while its arrows stay at the ends.
        showToday = true
        show(width = 320.dp)

        val title = compose.onNodeWithText("Календарь").getBoundsInRoot()
        val today = compose.onNodeWithContentDescription("Текущая неделя").getBoundsInRoot()
        val previous = compose.onNodeWithContentDescription("Предыдущая неделя").getBoundsInRoot()
        val next = compose.onNodeWithContentDescription("Следующая неделя").getBoundsInRoot()
        val period = compose.onNodeWithText("Вторник, 22\u00A0сентября", useUnmergedTree = true)
            .getBoundsInRoot()

        assertTrue("«Сегодня» under the title", today.top >= title.bottom)
        assertTrue("the arrows still flank the period", period.left >= previous.right && period.right <= next.left)
        assertTrue("the period wrapped rather than clipped", (period.bottom - period.top).value > 40f)
        assertTrue("both arrows on screen", next.right.value <= 320f)
    }

    @Test
    fun `the arrows do not move when the today button appears`() {
        // The whole of the second defect, in two compositions. The slot is kept
        // whether or not anything is in it, so «назад» and «вперёд» are in the
        // same place in both.
        show()
        val withoutLeft = compose.onNodeWithContentDescription("Предыдущая неделя")
            .getBoundsInRoot().left
        val withoutRight = compose.onNodeWithContentDescription("Следующая неделя")
            .getBoundsInRoot().left

        showToday = true
        settle()
        val withLeft = compose.onNodeWithContentDescription("Предыдущая неделя")
            .getBoundsInRoot().left
        val withRight = compose.onNodeWithContentDescription("Следующая неделя")
            .getBoundsInRoot().left

        assertEquals(withoutLeft.value, withLeft.value, 0.5f)
        assertEquals(withoutRight.value, withRight.value, 0.5f)
    }

    @Test
    fun `the year and both arrows are reachable on a narrow phone`() {
        // 320 dp is the narrowest Android phone worth drawing for. Everything
        // in the panel has to still be on screen: a year pushed off the end is
        // a year nobody can change.
        showToday = true
        show(width = 320.dp)

        compose.onNodeWithText("2026/27", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Предыдущая неделя").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующая неделя").assertIsDisplayed()
        compose.onNodeWithContentDescription("Текущая неделя").assertIsDisplayed()
    }

    /**
     * The three descriptions, in the three units the arrows actually move.
     *
     * The only part of this header nobody sees, and so the part that went on
     * saying «неделя» in four of the five modes while the line above it named
     * the day or the month correctly. With TalkBack on, «Следующая неделя»
     * stepped one day in «День»/«Лента» and one month in «Месяц» and in
     * «День»/«Список». One composition, three modes — see [step].
     */
    @Test
    fun `the arrows announce the unit they step`() {
        showToday = true
        show()

        compose.onNodeWithContentDescription("Предыдущая неделя").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующая неделя").assertIsDisplayed()
        compose.onNodeWithContentDescription("Текущая неделя").assertIsDisplayed()

        step = PeriodStep.MONTH
        settle()
        compose.onNodeWithContentDescription("Предыдущий месяц").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующий месяц").assertIsDisplayed()
        compose.onNodeWithContentDescription("Текущий месяц").assertIsDisplayed()

        step = PeriodStep.DAY
        settle()
        compose.onNodeWithContentDescription("Предыдущий день").assertIsDisplayed()
        compose.onNodeWithContentDescription("Следующий день").assertIsDisplayed()
        compose.onNodeWithContentDescription("Сегодня").assertIsDisplayed()
    }
}
