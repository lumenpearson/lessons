package com.lumenpearson.lessons.ui.diary

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.ui.join.JoinRig
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The diary as a home, composed: what the home leaves out, what the account
 * page in settings holds instead, and the sentences a failed read now says.
 *
 * #151 is the reason the account page matters. A phone signed in to a diary
 * and in no class used to land on the join screen, where neither the diary nor
 * «Выйти из дневника» could be reached; now it lands on the diary, and the way
 * out of it is this page's.
 */
// marquee clock: this one *cannot* hold it. It reaches the account page's rows
// and the section's sign-out with `performScrollTo`, which drives the list's own
// animation and moves nothing while the clock is held — every row below the
// fold would then read as absent, which is the thing these tests assert about.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1200dp")
class DiaryHomeScreenTest {

    @get:Rule val compose = createComposeRule()

    private val pupil = DiaryStudent(
        id = 1,
        firstName = "Пётр",
        lastName = "Иванов",
        middleName = null,
        fullName = "Иванов Пётр Сергеевич",
        school = "Школа № 5",
        className = "7А",
    )

    private val repository = FakeDiaryRepository().apply {
        students = listOf(pupil)
        sessions.value = DiarySession(
            login = "ivanova",
            token = "t",
            target = DiaryTarget.netschool("samara", 5, "Школа № 5", "ivanova", "Europe/Samara"),
        )
    }

    private fun model() = DiaryViewModel(repository)

    @Test
    fun `the diary home carries no tab switch and no sign-out of its own`() {
        val model = model()
        compose.setContent {
            LessonsTheme { DiaryScreen(asHome = true, viewModel = model) }
        }

        compose.onNodeWithText("Иванов Пётр Сергеевич").assertIsDisplayed()
        // The toolbar carries «Расписание» and «Оценки» on the home.
        compose.onAllNodesWithText("Оценки").assertCountEquals(0)
        compose.onAllNodesWithText("Выйти из дневника").assertCountEquals(0)
    }

    @Test
    fun `the section in a class keeps both`() {
        val model = model()
        compose.setContent {
            LessonsTheme { DiaryScreen(asHome = false, viewModel = model) }
        }

        compose.onAllNodesWithText("Оценки").assertCountEquals(1)
        compose.onNodeWithText("Выйти из дневника").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `the account page reaches the sign-out, and says the phone goes back to the start`() {
        val model = model()
        compose.setContent {
            LessonsTheme { DiaryAccountPage(viewModel = model) }
        }

        compose.onNodeWithText("ivanova").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Подключиться к классу по коду").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Выйти из дневника").performScrollTo().performClick()
        compose.onNodeWithText("Телефон вернётся к началу", substring = true).assertIsDisplayed()

        compose.onAllNodesWithText("Выйти из дневника").onLast().performClick()
        compose.waitForIdle()
        assertNull(repository.sessions.value)
    }

    /**
     * The sign-out is local first: offline, the goodbye never reaches the
     * server, which keeps the session until the idle purge. The sheet used to
     * promise the forgetting outright; it now says what the privacy policy
     * says, in both halves.
     */
    @Test
    fun `the sign-out sheet says what the server does when the phone is offline`() {
        val model = model()
        compose.setContent {
            LessonsTheme { DiaryAccountPage(viewModel = model) }
        }

        compose.onNodeWithText("Выйти из дневника").performScrollTo().performClick()
        compose.onNodeWithText("сервер удалит свою копию входа", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Если телефон сейчас не в сети", substring = true).assertIsDisplayed()
        compose.onNodeWithText("через 30 дней", substring = true).assertIsDisplayed()
    }

    /**
     * The mode follows the stored target, not a live session: after a bare
     * `401` the phone stays on the diary home with the login known and the
     * pupils gone, and the page said «Вход выполнен» about it.
     */
    @Test
    fun `a lapsed sign-in is not called a sign-in on the account page`() {
        val target = DiaryTarget.netschool("samara", 5, "Школа № 5", "ivanova", "Europe/Samara")
        val lapsed = FakeDiaryRepository().apply { targets.value = target }
        val model = DiaryViewModel(lapsed)
        compose.setContent {
            LessonsTheme { DiaryAccountPage(viewModel = model) }
        }

        compose.onAllNodesWithText("Вход выполнен", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Сессия дневника закончилась").assertIsDisplayed()
        compose.onNodeWithText("ivanova").performScrollTo().assertIsDisplayed()
    }

    /** This page exists only on a phone in no class, so the sheet asks for a first one. */
    @Test
    fun `the account page asks for a first class, not a second`() {
        val model = model()
        compose.setContent {
            LessonsTheme { DiaryAccountPage(viewModel = model, joinFactory = JoinRig().factory()) }
        }

        compose.onNodeWithText("Подключиться к классу по коду").performScrollTo().performClick()
        compose.onNodeWithText("Введите код класса от администратора", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("второго класса", substring = true).assertCountEquals(0)
    }

    /**
     * The card under a week that could not be read. It said «Дневник не
     * отвечает. Попробуйте позже.» for a throttle and for a diary switched off
     * on the server alike (#153), and offered «Повторить» for both.
     */
    @Test
    fun `a throttled read says so, and offers no retry`() {
        compose.setContent {
            LessonsTheme { DiaryFailureCard(DiaryFailure.Throttled(retryAfterSeconds = 120), onRetry = {}) }
        }

        compose.onNodeWithText("Слишком много попыток. Попробуйте снова через 2 минуты.").assertIsDisplayed()
        compose.onAllNodesWithText("Повторить").assertCountEquals(0)
    }

    @Test
    fun `a diary switched off on the server says so, and offers no retry`() {
        compose.setContent {
            LessonsTheme { DiaryFailureCard(DiaryFailure.Disabled, onRetry = {}) }
        }

        compose.onNodeWithText("Дневник выключен на этом сервере", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Повторить").assertCountEquals(0)
    }

    /** Our server's 502: the sentence says the fix is on the server, so no button says try again. */
    @Test
    fun `an answer nobody could read says so, and offers no retry`() {
        compose.setContent {
            LessonsTheme { DiaryFailureCard(DiaryFailure.Unreadable, onRetry = {}) }
        }

        compose.onNodeWithText("Дневник ответил непонятно", substring = true).assertIsDisplayed()
        compose.onAllNodesWithText("Повторить").assertCountEquals(0)
    }

    @Test
    fun `a diary that is down still offers a retry`() {
        compose.setContent {
            LessonsTheme { DiaryFailureCard(DiaryFailure.Unavailable, onRetry = {}) }
        }

        compose.onNodeWithText("Дневник не отвечает. Попробуйте позже.").assertIsDisplayed()
        compose.onNodeWithText("Повторить").assertIsDisplayed()
    }
}
