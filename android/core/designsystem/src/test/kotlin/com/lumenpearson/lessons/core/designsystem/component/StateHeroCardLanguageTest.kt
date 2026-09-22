package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.model.DayState
import com.lumenpearson.lessons.core.model.Lesson
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The biggest number on the home screen, read in both languages.
 *
 * The caption beside it — «до конца» / "left" — has always come out of
 * `values-en/`, and the figure it captions did not: it was built by a function
 * with the Russian words written into it, so an English phone drew "12 мин
 * left" at `displaySmall`, in the one element of this app a pupil is meant to
 * be able to glance at and put the phone down. Nothing could report it, because
 * a hard-coded string is not a missing translation — `ResourceTranslationTest`
 * compares two folders and this word was in neither.
 *
 * Both languages are asserted, because a fix that only moved the words would
 * pass an English-only test while printing "12 min" on a Russian screen.
 */
// marquee clock: this asks which *language* a caption came out in, so the
// strings are deliberately the short ones — «less than a minute» is the
// longest — on a card that gives its title the full 411 dp.
@RunWith(RobolectricTestRunner::class)
class StateHeroCardLanguageTest {

    @get:Rule val compose = createComposeRule()

    private val lesson = Lesson(
        index = 2,
        subject = "Алгебра",
        startsAt = LocalTime.of(9, 25),
        endsAt = LocalTime.of(10, 10),
    )

    private fun showLessonEndingIn(minutes: Long) = compose.setContent {
        LessonsTheme {
            StateHeroCard(
                state = DayState.InLesson(
                    current = lesson,
                    next = null,
                    endsIn = Duration.ofMinutes(minutes),
                    progress = 0.5f,
                    validUntil = LocalDateTime.of(2026, 9, 14, 10, 10),
                ),
            )
        }
    }

    @Test
    @Config(qualifiers = "ru-rRU-w411dp")
    fun `the Russian card counts down in Russian`() {
        showLessonEndingIn(12)

        compose.onNodeWithText("12 мин").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `the English card counts down in English`() {
        showLessonEndingIn(12)

        // The Russian first, so a regression is reported as the word that is on
        // screen rather than as the word that is missing: before this was a
        // resource the card drew «12 мин» here, under an English caption.
        compose.onNodeWithText("12 мин").assertDoesNotExist()
        compose.onNodeWithText("12 min").assertIsDisplayed()
    }

    /**
     * Past an hour the card switches shape, and the minutes stay zero-padded so
     * the figure does not change width between 09 and 10 at `displaySmall`.
     */
    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `the English card says hours and minutes the same way`() {
        showLessonEndingIn(65)

        compose.onNodeWithText("1 h 05 min").assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "en-rGB-w411dp")
    fun `the last seconds are a sentence, and an English one`() {
        showLessonEndingIn(0)

        compose.onNodeWithText("less than a minute").assertIsDisplayed()
    }
}
