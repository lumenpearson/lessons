package com.lumenpearson.lessons.core.designsystem.modifier

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A wave answers a tap, so it must not be played by anything that is not one.
 *
 * [LiquidRippleState] belongs to the shell and outlives the modifier that draws
 * from it: the modifier disappears whenever the effect is switched off in
 * settings, and comes back when it is switched on. Keyed only on "trigger above
 * zero", coming back was indistinguishable from a tap — so turning the setting
 * on replayed the last wave, from wherever on the screen it had started minutes
 * earlier.
 */
class LiquidRippleTriggerTest {

    @Test
    fun `nothing is played on first composition`() {
        assertFalse(firesWave(trigger = 0, enteredAt = 0))
    }

    @Test
    fun `a tap after the modifier entered plays`() {
        assertTrue(firesWave(trigger = 1, enteredAt = 0))
        assertTrue(firesWave(trigger = 8, enteredAt = 7))
    }

    /** The case the guard exists for: re-entering with waves already behind it. */
    @Test
    fun `re-entering on a counter that has already run plays nothing`() {
        assertFalse(firesWave(trigger = 7, enteredAt = 7))
    }

    /**
     * A fresh state alongside an old counter cannot happen from [fire], but the
     * modifier takes the two as separate arguments and a caller could hand it
     * anything; zero and below is never a wave.
     */
    @Test
    fun `a counter at or below zero never plays`() {
        assertFalse(firesWave(trigger = 0, enteredAt = 3))
        assertFalse(firesWave(trigger = -1, enteredAt = 3))
    }
}
