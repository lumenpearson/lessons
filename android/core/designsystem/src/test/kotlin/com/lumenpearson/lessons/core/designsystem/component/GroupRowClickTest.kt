package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The rows of a group have to be pressable.
 *
 * This exists because they were not, and nothing in the build noticed: the
 * settings screen is six of these rows and nothing else, so a row that does not
 * report its click is a screen with no way into it — and the only way anyone
 * found out was by installing the APK and pressing one.
 */
@RunWith(RobolectricTestRunner::class)
class GroupRowClickTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a link row reports its click`() {
        var clicks = 0
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupLinkItem(
                        title = "Оформление",
                        subtitle = "Тема, цвета, чёрный фон",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        onClick = { clicks++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Оформление").performClick()

        assertEquals(1, clicks)
    }

    /**
     * The subtitle is its own node inside the row, and a finger lands on
     * whichever node is under it. A row whose click only works on the title is a
     * row that misses half the time.
     */
    @Test
    fun `the subtitle is part of the same target`() {
        var clicks = 0
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupLinkItem(
                        title = "Синхронизация",
                        subtitle = "Расписание обновлений и адрес сервера",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        onClick = { clicks++ },
                    )
                }
            }
        }

        compose.onNodeWithText("Расписание обновлений и адрес сервера").performClick()

        assertEquals(1, clicks)
    }

    /** A switch row toggles from anywhere on it, not only from the switch. */
    @Test
    fun `a switch row toggles from its title`() {
        var value = false
        compose.setContent {
            LessonsTheme {
                RoundedCardContainer {
                    GroupSwitchItem(
                        title = "Чёрный фон",
                        icon = Icons.Rounded.Palette,
                        tone = accentTone(0),
                        checked = value,
                        onCheckedChange = { value = it },
                    )
                }
            }
        }

        compose.onNodeWithText("Чёрный фон").performClick()

        assertEquals(true, value)
    }

    /**
     * A row drawn inside a full-screen layer is still pressable.
     *
     * Every settings page is exactly that — a page laid over the tabs — and the
     * layer used to swallow the gesture before it reached the row inside it.
     */
    @Test
    fun `a row inside a full-screen layer is still pressable`() {
        var clicks = 0
        compose.setContent {
            LessonsTheme {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        RoundedCardContainer {
                            GroupLinkItem(
                                title = "Класс",
                                icon = Icons.Rounded.Palette,
                                tone = accentTone(0),
                                onClick = { clicks++ },
                            )
                        }
                    }
                }
            }
        }

        compose.onNodeWithText("Класс").performClick()

        assertEquals(1, clicks)
    }
}
