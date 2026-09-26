package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The bar under a caller shaped like the shell's, on the one-touch gesture.
 *
 * `LessonsApp` does three things no other test's caller does: it latches the
 * list the mode opened with, it writes the new order to storage and gets it
 * back a few frames later, and it names the selected tab by what it is rather
 * than by an index. A drop on an emulator once looked as if it had been
 * committed twice; this is the same arrangement without the emulator, where
 * nobody else's finger can be on the screen, and it asks for exactly one
 * commit and a row that stays where it was dropped.
 */
@RunWith(RobolectricTestRunner::class)
class ToolbarShellCallerTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private val calendar = "Календарь"
    private val homework = "Задания"
    private val today = "Сегодня"

    private var stored by mutableStateOf(listOf(calendar, homework, today))
    private var arranging by mutableStateOf<List<String>?>(null)
    private var keepOn by mutableStateOf<String?>(null)
    private val commits = mutableListOf<List<String>>()

    @Test
    fun `one touch commits once, and the row stays as it was dropped`() {
        showShell()

        compose.longPressAndDragFarRight(calendar)
        compose.settle(frames = 200)

        val expected = listOf(homework, today, calendar)
        assertEquals("the order was committed other than once", listOf(expected), commits)
        assertEquals(expected, stored)
        assertEquals(expected, compose.drawnOrder(expected))
    }

    private fun showShell() {
        compose.setContent {
            val scope = rememberCoroutineScope()
            val barTabs = arranging ?: stored
            // The shell lets go of the tab it kept the pager on once the new
            // order is back out of storage.
            LaunchedEffect(stored) { keepOn = null }
            LessonsTheme {
                LessonsFloatingToolbar(
                    items = barTabs.map { ToolbarItem(icon = Icons.Rounded.Settings, label = it) {} },
                    selectedIndex = (keepOn ?: calendar).let(barTabs::indexOf),
                    reorderable = true,
                    reordering = arranging != null,
                    onReorderingChange = { on -> arranging = if (on) stored else null },
                    onReorder = { moved ->
                        keepOn = calendar
                        val next = moved.map { barTabs[it] }
                        commits += next
                        // A round trip through DataStore: a few frames, not none.
                        scope.launch {
                            delay(StorageMillis)
                            stored = next
                        }
                    },
                )
            }
        }
        compose.settle()
    }

    private companion object {
        const val StorageMillis = 48L
    }
}
