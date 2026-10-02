package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryProviderKey
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the owner's phone showed on the first run (#230, #232): a step's body
 * cut along a line under the hero, and a gap before «Сервер» half as large
 * again as every other one.
 */
// marquee clock: it reads bounds and one pixel row after an idle frame; the sign-in page's only
// marquee is the server row, whose address fits at this width.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "ru-rRU-w411dp-h1200dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@OptIn(ExperimentalCoroutinesApi::class)
class OnboardingLayoutTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a body scrolled under the hero dissolves rather than being cut along a line`() {
        compose.setContent {
            LessonsTheme {
                Box(Modifier.background(Color.White)) {
                    StepScaffold(actions = {}) {
                        Box(Modifier.fillMaxWidth().height(4_000.dp).background(Color.Black))
                    }
                }
            }
        }
        compose.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        compose.waitForIdle()

        val pixels = compose.onRoot().captureToImage().toPixelMap()
        val edge = pixels[pixels.width / 2, 1]
        assertTrue("the top row is drawn at full strength: $edge", edge.red > 0.5f)
    }

    @Test
    fun `the forgotten-password link is followed by the gap every section has`() {
        val rig = OnboardingRig()
        val model = OnboardingViewModel(rig.saved, rig.deps())
        model.start(introduced = true)
        model.chooseWay(WayIn.CLASS_CODE)
        model.proceedFromWayIn()
        rig.joinedClass(DiaryBinding(DiaryProviderKey.PETERSBURG, null, null, null))
        model.onJoined()

        compose.setContent { LessonsTheme { SignInPage(viewModel = model, onBack = null) } }
        compose.waitForIdle()

        val link = compose.onNodeWithText("Забыли пароль", substring = true, useUnmergedTree = true)
            .getUnclippedBoundsInRoot()
        val header = compose.onNodeWithText("Сервер", useUnmergedTree = true).getUnclippedBoundsInRoot()
        val gap = header.top - link.bottom
        assertTrue("$gap between the link and «Сервер»", gap <= GroupSpacing + 8.dp)
    }
}
