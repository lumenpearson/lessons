package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.lumenpearson.lessons.core.model.AppFont
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale is now built from two settings, and both of them are capable
 * of making the whole app unreadable.
 *
 * The scale itself is a table of numbers copied from the design, so there is
 * nothing to test in any one of them. What is worth testing is the arithmetic
 * around them: that a size and its line height move together, that a stored
 * value nobody can type any more cannot produce a zero-sized font, and that the
 * chosen face actually reaches all fifteen roles rather than the three somebody
 * remembered to update.
 */
class TypographyScaleTest {

    /** Every role in the Material scale, so a new one cannot be left behind. */
    private fun roles(typography: Typography): List<TextStyle> = with(typography) {
        listOf(
            displayLarge, displayMedium, displaySmall,
            headlineLarge, headlineMedium, headlineSmall,
            titleLarge, titleMedium, titleSmall,
            bodyLarge, bodyMedium, bodySmall,
            labelLarge, labelMedium, labelSmall,
        )
    }

    @Test
    fun `at scale one the roles are the sizes the app was drawn at`() {
        val typography = lessonsTypography(LessonsSans, scale = 1f)

        assertEquals(16f, typography.bodyLarge.fontSize.value, EPSILON)
        assertEquals(24f, typography.bodyLarge.lineHeight.value, EPSILON)
        assertEquals(57f, typography.displayLarge.fontSize.value, EPSILON)
        assertEquals(64f, typography.displayLarge.lineHeight.value, EPSILON)
        assertEquals(11f, typography.labelSmall.fontSize.value, EPSILON)
    }

    /**
     * The failure this is here for: scaling the size and leaving the line
     * height behind.
     *
     * It does not look broken at 1.15 and it is unreadable at 1.3 — descenders
     * cut into the line below — and because the line height is only visible in
     * multi-line text, the screens that show it are exactly the ones a
     * developer scrolls past.
     */
    @Test
    fun `size and line height scale together, for every role`() {
        val base = lessonsTypography(LessonsSans, scale = 1f)
        val large = lessonsTypography(LessonsSans, scale = 1.3f)

        roles(base).zip(roles(large)).forEach { (before, after) ->
            assertEquals(before.fontSize.value * 1.3f, after.fontSize.value, EPSILON)
            assertEquals(before.lineHeight.value * 1.3f, after.lineHeight.value, EPSILON)
            // Stated as the ratio as well as the product, because it is the
            // ratio that the eye reads and the thing the design fixed.
            assertEquals(
                before.lineHeight.value / before.fontSize.value,
                after.lineHeight.value / after.fontSize.value,
                EPSILON,
            )
        }
    }

    /** Tracking is part of the proportion too: type that grows gets looser, not tighter. */
    @Test
    fun `letter spacing scales with the size`() {
        val large = lessonsTypography(LessonsSans, scale = 1.3f)

        assertEquals(0.5f * 1.3f, large.bodyLarge.letterSpacing.value, EPSILON)
        assertEquals(-0.25f * 1.3f, large.displayLarge.letterSpacing.value, EPSILON)
    }

    /**
     * The scale arrives from a preferences file, and a preferences file can hold
     * anything a previous build or a half-finished write left in it. Zero is the
     * one that matters: it renders the entire app invisible with no way back,
     * because the settings screen is drawn in the same type.
     */
    @Test
    fun `an impossible stored scale is clamped rather than honoured`() {
        val nothing = lessonsTypography(LessonsSans, scale = 0f)
        val enormous = lessonsTypography(LessonsSans, scale = 99f)

        assertEquals(16f * MinTextScale, nothing.bodyLarge.fontSize.value, EPSILON)
        assertEquals(16f * MaxTextScale, enormous.bodyLarge.fontSize.value, EPSILON)
        assertTrue(nothing.bodyLarge.fontSize.value > 0f)
    }

    /** All fifteen roles, not the three anybody would check by eye. */
    @Test
    fun `the chosen face reaches every role`() {
        val system = lessonsTypography(FontFamily.SansSerif, scale = 1f)

        assertTrue(roles(system).all { it.fontFamily == FontFamily.SansSerif })
        assertTrue(roles(lessonsTypography(LessonsSans, 1f)).all { it.fontFamily == LessonsSans })
    }

    /** The two stored answers, and which face each one means. */
    @Test
    fun `the stored font choice resolves to a family`() {
        assertEquals(LessonsSans, fontFamilyOf(AppFont.BUNDLED))
        assertEquals(FontFamily.SansSerif, fontFamilyOf(AppFont.SYSTEM))
    }

    private companion object {
        /** Sizes are sp floats; a thousandth of one is well below a pixel. */
        const val EPSILON = 0.001f
    }
}
