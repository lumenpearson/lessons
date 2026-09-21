package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import java.io.IOException
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the «Telegram» group says when the check did not get through.
 *
 * The failure carries a `Throwable`, and the card used to print its `message`
 * straight onto the row. That message is written by OkHttp or by the platform:
 * it is in English on a Russian screen, it names a host the reader never typed,
 * and when it is blank — which `IOException()` is, and a cancelled call often
 * is — the card fell through to «Не удалось обновить расписание», a sentence
 * about the timetable on a card about Telegram. Both are held here.
 */
@RunWith(RobolectricTestRunner::class)
// Russian: `values/` is the source, and Robolectric otherwise runs in English.
@Config(qualifiers = "ru-rRU-w411dp")
class TelegramLinkRowsTest {

    @get:Rule val compose = createComposeRule()

    private fun showFailure(cause: Throwable) {
        compose.setContent {
            LessonsTheme {
                LazyColumn {
                    telegramLinkRows(
                        state = DeviceLinkState.Failed(cause, known = null),
                        onRefresh = {},
                        onUnlink = {},
                    )
                }
            }
        }
    }

    @Test
    fun `the failed card does not print the exception it was handed`() {
        showFailure(IOException("Unable to resolve host \"example.invalid\""))

        compose.onNodeWithText("Не удалось проверить привязку").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Проверьте связь и попробуйте ещё раз.").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Unable to resolve host", substring = true).assertDoesNotExist()
    }

    @Test
    fun `a failure with nothing to say is not blamed on the timetable`() {
        showFailure(IOException())

        compose.onNodeWithText("Проверьте связь и попробуйте ещё раз.").performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText("Не удалось обновить расписание").assertDoesNotExist()
    }
}
