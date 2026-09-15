package com.lumenpearson.lessons.ui.admin

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The switch that turns a class code off, pressed for real.
 *
 * `ManagementViewModelTest` proves the write goes through and the card comes
 * back; this proves the thing in front of it. The whole design of this row is
 * about what a person sees before they commit, and none of it was checkable
 * without a phone: that one direction asks first and the other does not, that
 * the question carries the three facts nobody can verify from this screen, and
 * that cancelling writes nothing.
 *
 * It matters more than most sheets because the result is invisible from here.
 * Nothing on this phone changes when the class code stops working; the person
 * who finds out is a pupil somewhere else typing a code that worked yesterday.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
// Russian and a phone-sized screen — see `ClassRowsScreenTest` for why the
// locale is not left at Robolectric's default.
@Config(qualifiers = "ru-rRU-w411dp")
class ClassJoinModeScreenTest {

    @get:Rule val compose = createComposeRule()

    private val repository = FakeManageRepository()
    private val links = FakeDeviceLinkRepository(ClassRole.ADMIN)
    private val session = FakeSessionRepository()

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun show(mode: ClassJoinMode) {
        val model = ManagementViewModel(repository, links, session)
        val card = FakeSessionRepository.CLASS_CARD.copy(joinMode = mode)
        compose.setContent {
            LessonsTheme {
                ClassCardSheet(
                    state = ManagementUiState(classCard = Remote(value = card)),
                    viewModel = model,
                    onDismiss = {},
                    canDelete = false,
                )
            }
        }
    }

    private fun row(text: String) = compose.onNodeWithText(text, substring = true).performScrollTo()

    @Test
    fun `an open class says the code admits anybody, and offers the other way`() {
        show(ClassJoinMode.OPEN)

        row("Вход по коду класса").assertIsDisplayed()
        row("Код класса подключает любой телефон").assertIsDisplayed()
    }

    @Test
    fun `an invite-only class says the code admits nobody`() {
        show(ClassJoinMode.INVITE)

        row("Вход по приглашению из бота").assertIsDisplayed()
        row("Код класса никого не подключает").assertIsDisplayed()
    }

    @Test
    fun `turning the class code off asks first, and writes nothing until it is answered`() {
        show(ClassJoinMode.OPEN)

        row("Вход по коду класса").performClick()

        // The question, not the write.
        compose.onNodeWithText("Перейти на личные приглашения").assertIsDisplayed()
        assertEquals(emptyList<ClassJoinMode>(), repository.joinModeCalls)
    }

    @Test
    fun `the question carries the three things nobody can check from this screen`() {
        show(ClassJoinMode.OPEN)
        row("Вход по коду класса").performClick()

        // Not «вы уверены?». Each of these is a fact the admin has no way to
        // verify from here, and the first one is the reason people either never
        // switch or switch and spend the evening being asked where the
        // timetable went.
        row("ни один не отключится").assertIsDisplayed()
        row("Перестанет работать только код класса").assertIsDisplayed()
        row("📱 Подключить телефон").assertIsDisplayed()
    }

    @Test
    fun `answering no writes nothing and puts the card back`() {
        show(ClassJoinMode.OPEN)
        row("Вход по коду класса").performClick()

        compose.onNodeWithText("Отмена").performScrollTo().performClick()

        assertEquals(emptyList<ClassJoinMode>(), repository.joinModeCalls)
        row("Вход по коду класса").assertIsDisplayed()
    }

    @Test
    fun `answering yes is what asks for invites`() {
        show(ClassJoinMode.OPEN)
        row("Вход по коду класса").performClick()

        compose.onNodeWithText("Перейти на приглашения").performScrollTo().performClick()

        assertEquals(listOf(ClassJoinMode.INVITE), repository.joinModeCalls)
    }

    @Test
    fun `giving the class code back does not ask, because it takes nothing away`() {
        show(ClassJoinMode.INVITE)

        row("Вход по приглашению из бота").performClick()

        // No confirm face in between: a question with one sensible answer is an
        // extra tap, not a safeguard.
        assertEquals(listOf(ClassJoinMode.OPEN), repository.joinModeCalls)
    }
}
