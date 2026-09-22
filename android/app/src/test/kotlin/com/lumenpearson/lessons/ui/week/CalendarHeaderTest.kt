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
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
                        onPickYear = {},
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
    fun `the subtitle is one line too`() {
        show()

        compose.onNodeWithText("Вторник, 22 сентября · 1 полугодие").assertIsDisplayed()
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
    fun `the year chip and both arrows are reachable on a narrow phone`() {
        // 320 dp is the narrowest Android phone worth drawing for. Everything
        // in the control row has to still be on screen: a chip pushed off the
        // end is a year nobody can change.
        showToday = true
        show(width = 320.dp)

        compose.onNodeWithText("2026/27").assertIsDisplayed()
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
