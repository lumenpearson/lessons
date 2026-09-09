package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Baseline = Typography()

/**
 * Type scale for Lessons.
 *
 * Deliberately built from the platform default rather than a bundled font file:
 * the app ships a widget, and pulling a variable font into every process is a
 * cost we would pay on every home-screen redraw for a difference nobody notices
 * at 4 dp of text.
 *
 * What is changed is weight and tracking. The Expressive display and headline
 * roles get real weight so a single glance at the hero card resolves the state
 * before the reader has parsed any word; body and label roles stay at their
 * default weights so long homework text does not turn into a wall of bold.
 */
val LessonsTypography: Typography = Baseline.copy(
    displayLarge = Baseline.displayLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.5).sp,
    ),
    displayMedium = Baseline.displayMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.25).sp,
    ),
    displaySmall = Baseline.displaySmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    headlineLarge = Baseline.headlineLarge.copy(
        fontWeight = FontWeight.Bold,
        letterSpacing = (-0.25).sp,
    ),
    headlineMedium = Baseline.headlineMedium.copy(
        fontWeight = FontWeight.Bold,
    ),
    headlineSmall = Baseline.headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleLarge = Baseline.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    titleMedium = Baseline.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
    ),
    // Labels are the chips and the overline above the hero headline: a little
    // extra tracking is what keeps three uppercase Cyrillic words legible at 11sp.
    labelLarge = Baseline.labelLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.2.sp,
    ),
    labelMedium = Baseline.labelMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.6.sp,
    ),
    labelSmall = Baseline.labelSmall.copy(
        fontWeight = FontWeight.Medium,
        letterSpacing = 0.6.sp,
    ),
)
