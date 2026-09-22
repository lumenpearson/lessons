package com.lumenpearson.lessons.ui.day

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertAny
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.EventKind
import com.lumenpearson.lessons.core.model.Lesson
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.SchoolDay
import com.lumenpearson.lessons.core.model.SchoolEvent
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * «Календарь → День», composed at the sizes a phone actually has.
 *
 * Reported from a real build: opening this view crashed the app, and in
 * portrait it could be used while showing nothing. Neither is visible from a
 * pure test — the whole defect is what the layout does when it is measured —
 * so this one composes the ribbon for real, inside the `Column` with a
 * `weight(1f)` that `RibbonPage` puts it in, and at a landscape size as well as
 * a portrait one.
 *
 * The landscape case is the one the crash was reported on, and it is the
 * cheapest thing to hold: a phone on its side has about 250 dp of height left
 * after the status bar, the header, the picker and the bottom bar, and every
 * measurement in this view has to survive that.
 */
// marquee clock: what this measures is heights, and the only text it hands
// `DayRibbonView` is a date («2026-09-14») and one-word subjects. Nothing here
// is a label that could outgrow a landscape ribbon's width.
@RunWith(RobolectricTestRunner::class)
class DayRibbonLayoutTest {

    @get:Rule val compose = createComposeRule()

    private val date: LocalDate = LocalDate.parse("2026-09-14")

    private fun lesson(index: Int, from: String, to: String) = Lesson(
        index = index,
        subject = "Алгебра",
        startsAt = LocalTime.parse(from),
        endsAt = LocalTime.parse(to),
    )

    private val day = SchoolDay(
        date = date,
        weekday = 1,
        lessons = listOf(
            lesson(1, "08:30", "09:15"),
            lesson(2, "09:25", "10:10"),
            lesson(3, "10:20", "11:05"),
        ),
        events = listOf(
            SchoolEvent(
                title = "Столовая",
                kind = EventKind.CANTEEN,
                startsAt = LocalTime.parse("11:05"),
                endsAt = LocalTime.parse("11:25"),
            ),
        ),
    )

    /**
     * The ribbon in the box `RibbonPage` gives it.
     *
     * A `Column` of a fixed height with the ribbon under `weight(1f)`, which is
     * the shape that matters: the ribbon is the one view in this app that is
     * handed the height that is left rather than taking the height it wants.
     */
    private fun showIn(height: androidx.compose.ui.unit.Dp) = compose.setContent {
        LessonsTheme {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(modifier = Modifier.height(height)) {
                    DayRibbonView(
                        day = day,
                        nowAt = LocalTime.parse("09:30"),
                        flow = RibbonFlow.DOWNWARD,
                        snap = true,
                        depth = true,
                        showTeacher = true,
                        showHomework = true,
                        isFetched = true,
                        loadingYear = false,
                        yearName = "2026/27",
                        onLessonClick = {},
                        onSettings = {},
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    @Test
    fun `the ribbon composes in the height a portrait phone leaves it`() {
        showIn(600.dp)

        // Composing without throwing is not the assertion. The reported defect
        // had two halves and the second one was «можно пользоваться, но ничего
        // не видно» — a screen that is there and shows nothing passes every
        // test that only asks whether it was built.
        //
        // Two rows rather than three: the third is below the fold at this
        // height, which is what a lazy list is for. What matters is that the
        // rows that *are* on screen carry their subject and their times.
        compose.onAllNodesWithText("Алгебра").assertCountEquals(2)
        compose.onNodeWithText("08:30").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "land")
    fun `the ribbon composes in the height a phone on its side leaves it`() {
        // About what is left of a landscape phone after the status bar, the
        // header, the picker and the bottom bar — and less than the 96 dp the
        // top and bottom fades want between them.
        showIn(150.dp)

        // One row is all that fits, and one row is what must be readable.
        compose.onAllNodesWithText("Алгебра").assertAny(hasText("Алгебра"))
        compose.onNodeWithText("08:30").assertIsDisplayed()
    }

    @Test
    fun `the ribbon survives a height smaller than its own edge fades`() {
        // The fades are 48 dp each. A box shorter than both of them together is
        // not hypothetical: it is a phone on its side with the keyboard up, or
        // a foldable's cover screen.
        showIn(60.dp)
        compose.waitForIdle()
    }
}
