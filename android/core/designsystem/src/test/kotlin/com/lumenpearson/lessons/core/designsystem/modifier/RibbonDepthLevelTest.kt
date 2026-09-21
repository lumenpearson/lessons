package com.lumenpearson.lessons.core.designsystem.modifier

import android.os.Build
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which devices are given what, checked at every version this app runs on.
 *
 * `minSdk` is 26 and `RuntimeShader` arrives at 33, so seven of the ten API
 * levels this app is installable on get no shader at all. That is the reason
 * the rule is a function taking the version rather than an `if` written out
 * where it is needed: an `if` can only ever be tested at whichever version the
 * runner emulates, and the six versions below it would have been checked by
 * nobody.
 *
 * The rule proved red by removing the guard: with the version test gone, every
 * level below Android 13 asks a `RuntimeShader` constructor that does not exist
 * there, which is a `NoClassDefFoundError` rather than a missing ornament.
 */
class RibbonDepthLevelTest {

    /** Every API level between `minSdk` and the newest this build knows. */
    private val ladder = 26..Build.VERSION_CODES.VANILLA_ICE_CREAM

    @Test
    fun `android 13 and above are lit by the shader`() {
        for (sdk in Build.VERSION_CODES.TIRAMISU..ladder.last) {
            assertEquals("sdk $sdk", RibbonDepthLevel.SHADER, ribbonDepthLevel(sdk, enabled = true))
        }
    }

    @Test
    fun `everything below it still has gradients and a tilt`() {
        // Not NONE: the degradation is graceful rather than an absence. A
        // vertical gradient and a perspective rotation are plain Compose, so
        // an Android 8 phone gets a card with a near edge and a far one — it
        // simply has no light running across it.
        for (sdk in 26 until Build.VERSION_CODES.TIRAMISU) {
            assertEquals(
                "sdk $sdk",
                RibbonDepthLevel.GRADIENT,
                ribbonDepthLevel(sdk, enabled = true),
            )
        }
    }

    @Test
    fun `switched off is nothing, at every version`() {
        // The reader's own answer outranks the device's. Somebody who turned
        // the depth off did it because the phone was warm, and giving them the
        // tilt "because it is cheap" is an argument with the person holding it.
        for (sdk in ladder) {
            assertEquals("sdk $sdk", RibbonDepthLevel.NONE, ribbonDepthLevel(sdk, enabled = false))
        }
    }

    @Test
    fun `only the shader level draws a shader, and only NONE draws nothing`() {
        assertTrue(RibbonDepthLevel.SHADER.drawsShader)
        assertFalse(RibbonDepthLevel.GRADIENT.drawsShader)
        assertFalse(RibbonDepthLevel.NONE.drawsShader)

        assertTrue(RibbonDepthLevel.SHADER.drawsDepth)
        assertTrue(RibbonDepthLevel.GRADIENT.drawsDepth)
        assertFalse(RibbonDepthLevel.NONE.drawsDepth)
    }
}
