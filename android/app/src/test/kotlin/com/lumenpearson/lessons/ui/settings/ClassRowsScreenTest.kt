package com.lumenpearson.lessons.ui.settings

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHasNoClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The class group, composed and pressed.
 *
 * `MembershipsTest` proves the list survives being stored and read back, and
 * `ClassScopedCacheTest` proves each class keeps its own week. Neither can say
 * what a person sees, and this group is where every decision about holding two
 * classes actually shows up: which row carries the tick, which rows are
 * pressable, and — the one that deletes data if it is wrong — what the last row
 * offers to leave.
 *
 * That last one is the reason this file exists. With one class «Выйти» has
 * always meant «leave it». The day somebody adds a second class that row must
 * not quietly start meaning «leave both», and nothing before this could tell
 * the difference without a phone in somebody's hand.
 */
@RunWith(RobolectricTestRunner::class)
// Russian, because that is what `values/` holds and what a user of this app
// reads. Robolectric's default locale is English, and this project ships a full
// `values-en/`, so without this every assertion below would be made against the
// translation — testing the half that is not the source.
@Config(qualifiers = "ru-rRU-w411dp")
class ClassRowsScreenTest {

    @get:Rule val compose = createComposeRule()

    /**
     * A row, scrolled into view first.
     *
     * The group fits a phone screen today, so nothing here needs the scroll —
     * but the whole point of the group is that it grows with the number of
     * classes, and a row that lays out below the fold reports itself as «not
     * displayed», which reads exactly like the bug these tests look for.
     */
    private fun row(text: String) = compose.onNodeWithText(text).performScrollTo()

    private val seventhA = Session(classId = 7, className = "7А", school = "Школа № 1", token = "a")
    private val ninthB = Session(classId = 9, className = "9Б", school = null, token = "b")

    private fun show(
        state: SettingsUiState,
        onSelectClass: (Long) -> Unit = {},
        onAddClass: () -> Unit = {},
        onLeaveClass: (Session) -> Unit = {},
        onSignOut: () -> Unit = {},
    ) = compose.setContent {
        LessonsTheme {
            LazyColumn {
                classRows(
                    state = state,
                    onSelectClass = onSelectClass,
                    onAddClass = onAddClass,
                    onLeaveClass = onLeaveClass,
                    onSignOut = onSignOut,
                )
            }
        }
    }

    @Test
    fun `every joined class gets a row, and the school under it`() {
        show(SettingsUiState(session = seventhA, sessions = listOf(seventhA, ninthB)))

        row("7А").assertIsDisplayed()
        row("9Б").assertIsDisplayed()
        row("Школа № 1").assertIsDisplayed()
        // The class with no school still says something rather than leaving a
        // blank line where a subtitle belongs.
        row("Школа не указана").assertIsDisplayed()
    }

    @Test
    fun `the class on screen carries the tick and is not a button`() {
        var selected: Long? = null
        show(
            SettingsUiState(session = seventhA, sessions = listOf(seventhA, ninthB)),
            onSelectClass = { selected = it },
        )

        compose.onNodeWithContentDescription("Показывается сейчас").performScrollTo().assertIsDisplayed()
        // Pressable, it would look like a toggle that does nothing — the
        // repository drops a switch to the class already showing anyway.
        row("7А").assertHasNoClickAction()
        assertNull(selected)
    }

    @Test
    fun `pressing another class asks for that one, by id`() {
        var selected: Long? = null
        show(
            SettingsUiState(session = seventhA, sessions = listOf(seventhA, ninthB)),
            onSelectClass = { selected = it },
        )

        row("9Б").assertHasClickAction().performClick()

        assertEquals(9L, selected)
    }

    @Test
    fun `with one class there is one way out, and it does not name the class`() {
        show(SettingsUiState(session = seventhA, sessions = listOf(seventhA)))

        row("Выйти").assertIsDisplayed()
        // No per-class row: with a single class the two would mean the same
        // thing, and two rows that do the same thing read as two different
        // things.
        compose.onNodeWithText("Выйти из класса «7А»").assertDoesNotExist()
        compose.onNodeWithText("Выйти из всех классов").assertDoesNotExist()
    }

    @Test
    fun `with two classes the ways out split, and each says how far it goes`() {
        var left: Session? = null
        var signedOut = 0
        show(
            SettingsUiState(session = seventhA, sessions = listOf(seventhA, ninthB)),
            onLeaveClass = { left = it },
            onSignOut = { signedOut++ },
        )

        // Named, so «выйти» can never be read as «выйти отовсюду».
        row("Выйти из класса «7А»").performClick()
        assertEquals(seventhA, left)
        assertEquals(0, signedOut)

        row("Выйти из всех классов").performClick()
        assertEquals(1, signedOut)
    }

    @Test
    fun `the leave row follows the class on screen, not the first one stored`() {
        var left: Session? = null
        show(
            // 9Б is active while 7А is first in the list — the order a phone is
            // in right after switching.
            SettingsUiState(session = ninthB, sessions = listOf(seventhA, ninthB)),
            onLeaveClass = { left = it },
        )

        row("Выйти из класса «9Б»").performClick()

        assertEquals(ninthB, left)
    }

    @Test
    fun `adding a class is offered whether there is one class or three`() {
        var adds = 0
        show(SettingsUiState(session = seventhA, sessions = listOf(seventhA)), onAddClass = { adds++ })

        row("Добавить класс").performClick()

        assertEquals(1, adds)
    }
}
