package com.lumenpearson.lessons.core.designsystem.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * The single theme wrapper for the app, the widget's Compose previews and every
 * `@Preview` in this module.
 *
 * Uses [MaterialExpressiveTheme] rather than plain `MaterialTheme` so the
 * Expressive motion scheme is actually installed: without it the wavy progress
 * indicator, the flexible top app bar and the button shape morphs fall back to
 * the standard springs and the app looks like a stock M3 sample.
 *
 * @param dynamicColor take the palette from the user's wallpaper (Android 12+).
 *   Off falls back to [LessonsLightColorScheme] / [LessonsDarkColorScheme].
 * @param pitchBlack flatten dark surfaces to true black for OLED panels. Has no
 *   effect in light mode, so callers can bind it straight to a setting.
 */
@Composable
fun LessonsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    pitchBlack: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current

    // Resolved inside a single remember keyed on the inputs, not on the scheme:
    // dynamicDarkColorScheme() allocates a fresh ColorScheme on every call and
    // ColorScheme has no value equality, so keying on its result would rebuild
    // the whole palette on every recomposition.
    val colorScheme: ColorScheme = remember(context, darkTheme, dynamicColor, pitchBlack) {
        val base = when {
            // The SDK_INT check is inlined rather than delegated to
            // [supportsDynamicColor] so lint can see the API-31 guard.
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> LessonsDarkColorScheme
            else -> LessonsLightColorScheme
        }
        if (darkTheme && pitchBlack) base.toPitchBlack() else base
    }

    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        // If MotionScheme.expressive() moved, the stable fallback is
        // MotionScheme.standard(); dropping the argument entirely also compiles
        // because MaterialExpressiveTheme defaults every parameter to null.
        motionScheme = MotionScheme.expressive(),
        shapes = LessonsShapes,
        typography = LessonsTypography,
        content = content,
    )
}

/**
 * Whether wallpaper-derived colour is even an option here.
 *
 * Exposed because the settings screen has to grey out the "dynamic colour"
 * switch on older devices, and duplicating the SDK check there is how the two
 * drift apart.
 */
val supportsDynamicColor: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
