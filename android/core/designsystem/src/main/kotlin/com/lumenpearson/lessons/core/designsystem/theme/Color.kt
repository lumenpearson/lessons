package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/*
 * Tonal ramps.
 *
 * They exist so the two schemes below are obviously derived from one source
 * palette instead of being two unrelated lists of hex codes that drift apart the
 * first time somebody tweaks a single role.
 *
 * primary   — deep indigo: the "notebook ink" colour, carries lessons.
 * secondary — cool teal: everything restful or non-academic (столовая, перемены
 *             before the day starts).
 * tertiary  — warm amber: the pause / celebration accent, warm against indigo.
 */

private val Indigo10 = Color(0xFF14205F)
private val Indigo20 = Color(0xFF1B2878)
private val Indigo30 = Color(0xFF2F3E9E)
private val Indigo40 = Color(0xFF4453BE)
private val Indigo80 = Color(0xFFB9C3FF)
private val Indigo90 = Color(0xFFDDE1FF)

private val Teal10 = Color(0xFF002019)
private val Teal20 = Color(0xFF00382C)
private val Teal30 = Color(0xFF005141)
private val Teal40 = Color(0xFF1D6B57)
private val Teal80 = Color(0xFF7CD9BC)
private val Teal90 = Color(0xFF99F5D7)

private val Amber10 = Color(0xFF2A1700)
private val Amber20 = Color(0xFF472A00)
private val Amber30 = Color(0xFF663E00)
private val Amber40 = Color(0xFF8A5400)
private val Amber80 = Color(0xFFFFB86B)
private val Amber90 = Color(0xFFFFDDB8)

private val Red40 = Color(0xFFBA1A1A)
private val Red80 = Color(0xFFFFB4AB)
private val RedDim = Color(0xFF690005)
private val RedContainerDark = Color(0xFF93000A)
private val RedContainerLight = Color(0xFFFFDAD6)
private val RedOnContainerLight = Color(0xFF410002)

// Neutrals carry a faint violet cast so paper-white surfaces sit under the
// indigo primary without looking grey-green next to it.
private val Neutral06 = Color(0xFF0C0C12)
private val Neutral10 = Color(0xFF121219)
private val Neutral12 = Color(0xFF1A1A21)
private val Neutral17 = Color(0xFF1E1E26)
private val Neutral22 = Color(0xFF292930)
private val Neutral24 = Color(0xFF34343B)
private val Neutral30 = Color(0xFF383840)
private val Neutral32 = Color(0xFF2F2F38)
private val Neutral90 = Color(0xFFE4E1EC)
private val Neutral92 = Color(0xFFEAE7F1)
private val Neutral94 = Color(0xFFF0EDF7)
private val Neutral96 = Color(0xFFF6F2FD)
private val Neutral98 = Color(0xFFFCF8FF)
private val Neutral100 = Color(0xFFFFFFFF)
private val NeutralDim = Color(0xFFDCD9E4)
private val NeutralInk = Color(0xFF1A1A22)
private val NeutralInkInverse = Color(0xFFF2EFF9)

private val NeutralVariant30 = Color(0xFF45455A)
private val NeutralVariant50 = Color(0xFF767688)
private val NeutralVariant60 = Color(0xFF90909D)
private val NeutralVariant80 = Color(0xFFC7C5D8)
private val NeutralVariant90 = Color(0xFFE3E1F0)

/**
 * Static light palette, used verbatim on Android 11 and below and whenever the
 * user turns dynamic colour off — the app must never fall back to Compose's
 * baseline purple, which reads as "unfinished".
 */
val LessonsLightColorScheme: ColorScheme = lightColorScheme(
    primary = Indigo40,
    onPrimary = Neutral100,
    primaryContainer = Indigo90,
    onPrimaryContainer = Indigo10,
    inversePrimary = Indigo80,
    secondary = Teal40,
    onSecondary = Neutral100,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,
    tertiary = Amber40,
    onTertiary = Neutral100,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,
    error = Red40,
    onError = Neutral100,
    errorContainer = RedContainerLight,
    onErrorContainer = RedOnContainerLight,
    background = Neutral98,
    onBackground = NeutralInk,
    surface = Neutral98,
    onSurface = NeutralInk,
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,
    surfaceTint = Indigo40,
    inverseSurface = Neutral32,
    inverseOnSurface = NeutralInkInverse,
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,
    scrim = Color.Black,
    surfaceBright = Neutral98,
    surfaceDim = NeutralDim,
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,
)

/** Static dark palette; the counterpart of [LessonsLightColorScheme]. */
val LessonsDarkColorScheme: ColorScheme = darkColorScheme(
    primary = Indigo80,
    onPrimary = Indigo20,
    primaryContainer = Indigo30,
    onPrimaryContainer = Indigo90,
    inversePrimary = Indigo40,
    secondary = Teal80,
    onSecondary = Teal20,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,
    tertiary = Amber80,
    onTertiary = Amber20,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,
    error = Red80,
    onError = RedDim,
    errorContainer = RedContainerDark,
    onErrorContainer = RedContainerLight,
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,
    surfaceVariant = NeutralVariant30,
    onSurfaceVariant = NeutralVariant80,
    surfaceTint = Indigo80,
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral32,
    outline = NeutralVariant60,
    outlineVariant = NeutralVariant30,
    scrim = Color.Black,
    surfaceBright = Neutral30,
    surfaceDim = Neutral10,
    surfaceContainerLowest = Neutral06,
    surfaceContainerLow = Neutral12,
    surfaceContainer = Neutral17,
    surfaceContainerHigh = Neutral22,
    surfaceContainerHighest = Neutral24,
)

/**
 * Turns a server-supplied `#RRGGBB` / `#AARRGGBB` string into a [Color].
 *
 * Hand-rolled rather than delegating to `android.graphics.Color.parseColor`
 * because that one throws on malformed input, and a single typo in one school's
 * subject list must not be able to crash the timetable. Returns `null` so the
 * caller can fall back to the scheme instead of drawing something arbitrary.
 */
fun parseSubjectColor(hex: String?): Color? {
    val digits = hex?.trim()?.removePrefix("#") ?: return null
    val value = digits.toLongOrNull(radix = 16) ?: return null
    return when (digits.length) {
        6 -> Color(value or 0xFF000000L)
        8 -> Color(value)
        else -> null
    }
}

/**
 * Flattens every "page" surface of a dark scheme to true black so an OLED panel
 * can switch those pixels off.
 *
 * Written as one function rather than a wall of inline `.copy()` calls at each
 * call site: the dynamic and the static dark scheme must black out *identically*,
 * otherwise the two paths drift and only one of them actually saves power.
 * [surfaceBright] deliberately stays a near-black grey — with everything at
 * #000000 cards lose their edges and the layout collapses into one void.
 */
fun ColorScheme.toPitchBlack(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color.Black,
    surfaceContainer = Color.Black,
    surfaceContainerHigh = Neutral12,
    surfaceContainerHighest = Neutral17,
    surfaceBright = Neutral22,
)
