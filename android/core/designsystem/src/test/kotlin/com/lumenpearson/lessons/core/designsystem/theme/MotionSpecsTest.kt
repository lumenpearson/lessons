package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Every animation spec the app takes from here follows «Анимации» and the
 * motion speed (#246, #247): off is a snap or no transition at all, and the
 * speed scales the pace in the direction [MotionSettings] decides once.
 */
@RunWith(RobolectricTestRunner::class)
class MotionSpecsTest {

    @get:Rule
    val compose = createComposeRule()

    // Held, because a revealed row is asked mid-animation and a clock that
    // advanced by itself would have finished it.
    @Before
    fun holdTheClock() {
        compose.mainClock.autoAdvance = false
    }

    private val off = MotionSettings(enabled = false)

    @Test
    fun `with animations off every spec is a snap and every transition none`() {
        assertTrue(off.springSpec<Float>() is SnapSpec)
        assertTrue(off.tweenSpec<Float>(FadeMillis) is SnapSpec)
        assertEquals(EnterTransition.None, off.appear())
        assertEquals(ExitTransition.None, off.disappear())
    }

    @Test
    fun `a spring is stiffer at a quicker speed and its damping is left alone`() {
        val spec = MotionSettings(speed = 2f).springSpec<Float>(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow,
        ) as SpringSpec<Float>

        assertEquals(Spring.StiffnessLow * 2f, spec.stiffness, 0.01f)
        assertEquals(Spring.DampingRatioMediumBouncy, spec.dampingRatio, 0f)
    }

    @Test
    fun `a tween is shorter at a quicker speed and longer at a slower one`() {
        val quick = MotionSettings(speed = 2f).tweenSpec<Float>(400) as TweenSpec<Float>
        val slow = MotionSettings(speed = 0.5f).tweenSpec<Float>(400) as TweenSpec<Float>

        assertEquals(200, quick.durationMillis)
        assertEquals(800, slow.durationMillis)
    }

    @Test
    fun `with animations on, appearing and leaving are transitions`() {
        assertNotEquals(EnterTransition.None, MotionSettings().appear())
        assertNotEquals(ExitTransition.None, MotionSettings().disappear())
    }

    @Test
    fun `a revealed row opens over frames with animations on`() {
        var shown by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides MotionSettings()) {
                Column { Reveal(visible = shown, modifier = Modifier.testTag(Tag)) { Box(Modifier.height(RowHeight)) } }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        shown = true
        Snapshot.sendApplyNotifications()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val early = compose.onNodeWithTag(Tag).fetchSemanticsNode().size.height
        repeat(120) { compose.mainClock.advanceTimeByFrame() }
        val full = compose.onNodeWithTag(Tag).fetchSemanticsNode().size.height

        assertTrue("two frames in the row was $early px of $full", early < full)
    }

    @Test
    fun `a revealed row is whole on the next frame with animations off`() {
        var shown by mutableStateOf(false)
        compose.setContent {
            CompositionLocalProvider(LocalMotion provides off) {
                Column { Reveal(visible = shown, modifier = Modifier.testTag(Tag)) { Box(Modifier.height(RowHeight)) } }
            }
        }
        compose.mainClock.advanceTimeByFrame()

        shown = true
        Snapshot.sendApplyNotifications()
        repeat(2) { compose.mainClock.advanceTimeByFrame() }
        val height = compose.onNodeWithTag(Tag).fetchSemanticsNode().size.height

        assertEquals(with(compose.density) { RowHeight.roundToPx() }, height)
    }

    private companion object {
        const val Tag = "revealed"
        val RowHeight = 56.dp
    }
}
