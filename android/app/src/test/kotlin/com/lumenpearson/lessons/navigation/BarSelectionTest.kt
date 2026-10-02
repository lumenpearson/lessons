package com.lumenpearson.lessons.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * What the bar's selection is read from, and why it is the page being travelled
 * to rather than the page in front (#260).
 *
 * A tap two tabs away runs `animateScrollToPage`, and on the way the page in
 * front is the one between. While the bar read `currentPage` it selected that
 * tab for part of the scroll — its label began to open and closed again, and the
 * selection's pill turned towards it and back. `HomeShell` reads `targetPage`
 * now; this holds the two facts that choice rests on, on the pager the shell
 * uses, so that a Compose that changed either would say so here rather than in
 * a flinch on somebody's phone.
 */
@RunWith(RobolectricTestRunner::class)
class BarSelectionTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun `a scroll two pages away names the page between in front, and never as its target`() {
        lateinit var pager: PagerState
        lateinit var scope: CoroutineScope
        compose.mainClock.autoAdvance = false
        compose.setContent {
            pager = rememberPagerState { 3 }
            scope = rememberCoroutineScope()
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.fillMaxSize())
            }
        }
        compose.mainClock.advanceTimeByFrame()

        val inFront = mutableListOf<Int>()
        val targets = mutableListOf<Int>()
        scope.launch { pager.animateScrollToPage(2) }
        repeat(Frames) {
            compose.mainClock.advanceTimeByFrame()
            inFront += pager.currentPage
            targets += pager.targetPage
        }

        assertEquals("the scroll did not arrive", 2, pager.currentPage)
        assertTrue("the page between was never in front, so nothing here was tested: $inFront", 1 in inFront)
        assertFalse("the page between was named as the target: $targets", 1 in targets)
    }

    private companion object {

        /** Longer than the pager's own scroll animation. */
        const val Frames = 90
    }
}
