package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The handle on top of every sheet (#222): a long press on it opened Material's
 * «Маркер перемещения» tooltip over a rippling grey rectangle, because this
 * Material build wraps anything in its drag-handle slot in a TooltipBox and a
 * clickable. The sheet now draws its own.
 */
@RunWith(RobolectricTestRunner::class)
class SheetHandleTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `the handle is announced but not clickable, and a long press opens nothing`() {
        compose.setContent {
            LessonsTheme {
                LessonsBottomSheet(onDismissRequest = {}) { Text("Алгебра") }
            }
        }
        compose.waitForIdle()

        val handle = compose.onNodeWithContentDescription("Drag handle")
        handle.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        handle.performTouchInput { longClick() }
        compose.waitForIdle()

        compose.onAllNodesWithText("Drag handle").assertCountEquals(0)
    }
}
