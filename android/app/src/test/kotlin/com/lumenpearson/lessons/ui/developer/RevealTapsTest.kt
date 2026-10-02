package com.lumenpearson.lessons.ui.developer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Seven quick taps on the version find the developer section (#237); slow ones do not. */
class RevealTapsTest {

    private fun RevealTaps.tapsAt(vararg times: Long): List<Boolean> = times.map { tap(it) }

    @Test
    fun `the seventh quick tap reveals, and none before it`() {
        val taps = RevealTaps()

        val answers = taps.tapsAt(0, 300, 600, 900, 1_200, 1_500, 1_800)

        assertEquals(List(6) { false } + true, answers)
    }

    @Test
    fun `a pause longer than the window starts the count again`() {
        val taps = RevealTaps()

        val answers = taps.tapsAt(0, 300, 600, 900, 1_200, 1_500, 10_000)

        assertFalse(answers.last())
    }

    @Test
    fun `once revealed it counts from zero, so the next seven say it again`() {
        val taps = RevealTaps()
        taps.tapsAt(0, 1, 2, 3, 4, 5, 6)

        assertFalse(taps.tap(7))
        assertTrue(taps.tapsAt(8, 9, 10, 11, 12, 13).last())
    }

    @Test
    fun `a clock that moved back restarts the count rather than finishing it`() {
        val taps = RevealTaps()
        taps.tapsAt(5_000, 5_100, 5_200, 5_300, 5_400, 5_500)

        assertFalse(taps.tap(0))
    }
}
