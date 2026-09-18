package com.lumenpearson.lessons.ui.week

import androidx.compose.material3.Text
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.Term
import com.lumenpearson.lessons.core.model.TermKind
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The half of the calendar's subtitle that used to be in one language only.
 *
 * The period beside it — «Октябрь 2026», "October 2026" — has always been
 * resolved per locale, and the term was not: it was a string built inside
 * `:core:model`, a pure JVM module with no resources, so the header read
 * "October 2026 · 1 четверть" on every phone set to English.
 * [ResourceTranslationTest] could not see it, because comparing `values/`
 * against `values-en/` says nothing about a word that is in neither.
 *
 * Both languages are asserted for the same reason as the hero card's
 * countdown: a fix that only moved the words would pass an English test and
 * print "quarter 1" on the Russian screen this app is written for.
 */
@RunWith(RobolectricTestRunner::class)
class TermLabelTest {

    @get:Rule val compose = createComposeRule()

    private fun show(term: Term) = compose.setContent {
        LessonsTheme { Text(text = term.label()) }
    }

    private fun quarter(index: Int) = Term(
        index = index,
        kind = TermKind.QUARTER,
        startsOn = LocalDate.of(2026, 9, 1),
        endsOn = LocalDate.of(2026, 10, 31),
    )

    private fun semester(index: Int) = Term(
        index = index,
        kind = TermKind.SEMESTER,
        startsOn = LocalDate.of(2026, 9, 1),
        endsOn = LocalDate.of(2026, 12, 31),
    )

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `a Russian screen names the quarter in Russian`() {
        show(quarter(1))

        compose.onNodeWithText("1 четверть").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `an English screen does not fall back to the Russian quarter`() {
        show(quarter(1))

        // The Russian first, so a regression is reported as the word that is on
        // screen rather than as the word that is missing: this is the exact
        // string the header drew under an English month before the wording was
        // a resource.
        compose.onNodeWithText("1 четверть").assertDoesNotExist()
        compose.onNodeWithText("quarter 1").assertIsDisplayed()
    }

    /**
     * The other scheme. A class taught in полугодия says so, and the two words
     * are not interchangeable — «2 полугодие» and «2 четверть» are different
     * halves of a different year.
     */
    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `a class taught in semesters is not told it is in quarters`() {
        show(semester(2))

        compose.onNodeWithText("2 полугодие").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `the semester has an English twin too`() {
        show(semester(2))

        compose.onNodeWithText("semester 2").assertIsDisplayed()
    }
}
