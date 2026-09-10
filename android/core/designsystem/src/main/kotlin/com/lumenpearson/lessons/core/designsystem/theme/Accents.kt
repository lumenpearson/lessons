package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * The pastel tile and the glyph that sits on it — the per-row colour that makes
 * a list of grouped rows scannable by hue rather than by reading every title.
 */
@Immutable
data class AccentTone(
    val container: Color,
    val content: Color,
)

/*
 * Six hues spread around the wheel, stored as offsets from the *scheme's own*
 * primary rather than as fixed hex codes: a wallpaper-derived palette then
 * rotates the whole set together instead of clashing with it, and dark mode is
 * handled by the lightness formula below rather than by a second table.
 */
private val AccentHueOffsets = floatArrayOf(0f, 42f, 96f, 158f, 214f, 292f)

/** How many distinct row hues the system has; also the modulus for [accentSlotFor]. */
val AccentSlotCount: Int = AccentHueOffsets.size

/** Dark schemes need the tile dark and the glyph bright; light schemes the reverse. */
private val ColorScheme.isDarkScheme: Boolean
    get() = surface.luminance() < 0.5f

/**
 * The background a group of rows sits on. One step away from the page so the
 * group reads as an object even before the reader notices its corners.
 */
val ColorScheme.groupContainer: Color
    get() = if (isDarkScheme) surfaceContainerLow else surfaceContainer

/** A single row inside a group: white on a light page, raised grey on a dark one. */
val ColorScheme.rowContainer: Color
    get() = if (isDarkScheme) surfaceContainerHigh else surfaceContainerLowest

/** The floating navigation pill; same fill as a row so the two read as one family. */
val ColorScheme.floatingContainer: Color
    get() = if (isDarkScheme) surfaceContainerHigh else surfaceContainerLowest

/** Builds a tone at an arbitrary hue, which is what keeps subject colours in family. */
fun ColorScheme.toneForHue(hue: Float): AccentTone {
    val h = hue.mod(360f)
    return if (isDarkScheme) {
        AccentTone(container = Color.hsl(h, 0.34f, 0.20f), content = Color.hsl(h, 0.66f, 0.74f))
    } else {
        AccentTone(container = Color.hsl(h, 0.72f, 0.90f), content = Color.hsl(h, 0.55f, 0.38f))
    }
}

/** One of the [AccentSlotCount] standard row hues. Out-of-range slots wrap. */
@Composable
fun accentTone(slot: Int): AccentTone {
    val scheme = MaterialTheme.colorScheme
    val offset = AccentHueOffsets[slot.mod(AccentSlotCount)]
    return scheme.toneForHue(scheme.primary.hue() + offset)
}

/**
 * The colour of a subject, everywhere it appears.
 *
 * A school that sends its own `colorHex` wins, but it is re-derived through
 * [toneForHue] rather than used raw — otherwise one school's saturated blue sits
 * next to five soft pastels and looks like a rendering bug. Without a colour the
 * hue comes from the subject name, so «Алгебра» is the same green in the
 * timetable, in the homework list and after the app is reinstalled.
 */
@Composable
fun subjectTone(subject: String, colorHex: String? = null): AccentTone {
    val scheme = MaterialTheme.colorScheme
    val explicit = parseSubjectColor(colorHex)
    return when {
        explicit != null -> scheme.toneForHue(explicit.hue())
        else -> accentTone(accentSlotFor(subject))
    }
}

/** For rows that carry no colour of their own: disabled, cancelled, missing data. */
@Composable
fun neutralTone(): AccentTone = with(MaterialTheme.colorScheme) {
    AccentTone(container = surfaceContainerHighest, content = onSurfaceVariant)
}

/** The one destructive tone; derived from the scheme's error hue, not hard-coded red. */
@Composable
fun errorTone(): AccentTone = with(MaterialTheme.colorScheme) { toneForHue(error.hue()) }

/**
 * Stable slot for a free-form key.
 *
 * Hand-rolled instead of [String.hashCode] so the mapping cannot change when the
 * platform's hashing does: the same subject has to get the same colour on every
 * device and after every reinstall, or the colour stops being information.
 */
fun accentSlotFor(key: String): Int {
    var hash = 0
    for (char in key.trim().lowercase()) {
        hash = hash * 31 + char.code
    }
    return hash.mod(AccentSlotCount)
}

/** Hue in degrees. Written out because Compose has no rgb→hsl accessor. */
private fun Color.hue(): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta < 0.0001f) return 0f
    val degrees = when (max) {
        red -> 60f * (((green - blue) / delta).mod(6f))
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return degrees.mod(360f)
}
