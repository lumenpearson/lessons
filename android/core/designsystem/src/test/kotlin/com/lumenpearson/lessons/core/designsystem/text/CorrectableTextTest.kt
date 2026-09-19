package com.lumenpearson.lessons.core.designsystem.text

import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The guard on the shim.
 *
 * [Text] stands in front of Material's `Text` at every call site in this app,
 * for one added line. Standing in front of something means agreeing with it:
 * a parameter this shim does not declare is a parameter every screen in the
 * app silently stops being able to set, and nothing says so. `minLines` would
 * go first, then `softWrap`, and the only symptom would be a line wrapping
 * somewhere nobody is looking.
 *
 * Material's signature is not stable — `autoSize` arrived in 1.4 — so this
 * cannot be a copy checked by eye once. It is checked against the real thing,
 * by reflection, on the version actually on the classpath: same parameters, in
 * the same order, of the same types. When Material grows a parameter this test
 * fails on the next dependency bump, which is exactly when somebody should be
 * looking at it.
 *
 * What it cannot check is the defaults, which do not survive into bytecode in
 * a readable form. Those are read from Material's source when a parameter is
 * added, and getting one wrong shows up in the app rather than here.
 */
class CorrectableTextTest {

    @Test
    fun `the shim declares every parameter Material declares, for a plain string`() {
        assertSameSignature(String::class.java)
    }

    @Test
    fun `and for one that carries its own styling`() {
        assertSameSignature(Class.forName("androidx.compose.ui.text.AnnotatedString"))
    }

    /**
     * Compose compiles a composable to a method taking the declared parameters
     * plus a `Composer` and a tail of ints that carry which arguments were
     * defaulted. Comparing the whole erased list therefore compares the
     * declared parameters *and* that the two have the same number of them.
     */
    private fun assertSameSignature(first: Class<*>) {
        val theirs = widest("androidx.compose.material3.TextKt", first)
        val ours = widest("com.lumenpearson.lessons.core.designsystem.text.TextKt", first)
        assertEquals(
            "This module's Text no longer takes what Material's Text takes. A screen " +
                "setting the parameter that went missing is now setting nothing, and the " +
                "compiler cannot say so because the call still resolves.",
            theirs.parameterTypes.toList(),
            ours.parameterTypes.toList(),
        )
    }

    /**
     * The real overload, as opposed to the ones kept for binary compatibility.
     *
     * Material leaves a hidden overload behind every time the signature grows,
     * and they differ only by being shorter — so the widest one is the current
     * one. Ours has exactly one per first parameter, and the same rule picks
     * it.
     *
     * Two kinds of method have to be kept out of that comparison first, and
     * both of them are wider than the overload being looked for, so "widest"
     * alone would pick one of them. Compose emits a private
     * `Text_xxx$lambda$n` beside every composable — the same parameters again,
     * plus the `$changed` ints and a `Composer`. And Material declares a second
     * public overload taking a `ColorProducer` where this one takes a
     * `Modifier`: same first parameter, same count, a different second. It ties
     * with the one that is wanted, and which of the two `maxBy` returns is
     * whatever order `getDeclaredMethods` happens to be in — unspecified, and
     * not the same on every JVM. So: public, and a `Modifier` second.
     */
    private fun widest(className: String, first: Class<*>): Method {
        val candidates = Class.forName(className).declaredMethods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) }
            .filter { it.name.startsWith("Text") && it.parameterTypes.firstOrNull() == first }
            .filter { it.parameterTypes.getOrNull(1) == androidx.compose.ui.Modifier::class.java }
        assertTrue("No Text overload taking ${first.simpleName} in $className", candidates.isNotEmpty())
        return candidates.maxBy { it.parameterCount }
    }
}
