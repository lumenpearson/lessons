package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs

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

/**
 * Dark schemes need the tile dark and the glyph bright; light schemes the reverse.
 *
 * Weighted brightness of the page itself rather than a flag passed down from the
 * theme: a wallpaper-derived scheme can be installed by anything, and the only
 * reliable answer to "is this a dark page" is the page's own colour.
 */
private val ColorScheme.isDarkScheme: Boolean
    get() = surface.brightness() < 0.5f

/**
 * A single row inside a group.
 *
 * `surfaceBright` is the row colour throughout Essentials — brighter than the
 * page in both schemes, which is what lets a stack of rows read as raised
 * without a single border or shadow.
 */
val ColorScheme.rowContainer: Color
    get() = surfaceBright

/** Builds a tone at an arbitrary hue, which is what keeps subject colours in family. */
fun ColorScheme.toneForHue(hue: Float): AccentTone {
    val h = hue.mod(360f)
    return if (isDarkScheme) {
        AccentTone(container = hsl(h, 0.34f, 0.20f), content = hsl(h, 0.66f, 0.74f))
    } else {
        AccentTone(container = hsl(h, 0.72f, 0.90f), content = hsl(h, 0.55f, 0.38f))
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
fun neutralTone(): AccentTone {
    val scheme = MaterialTheme.colorScheme
    return AccentTone(container = scheme.surfaceContainerHighest, content = scheme.onSurfaceVariant)
}

/** The one destructive tone; derived from the scheme's error hue, not hard-coded red. */
@Composable
fun errorTone(): AccentTone {
    // Spelled out through `scheme` rather than `with`: an unqualified `error`
    // there sits next to kotlin.error(), and this is not the place to make a
    // reader work out which one won.
    val scheme = MaterialTheme.colorScheme
    return scheme.toneForHue(scheme.error.hue())
}

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

/*
 * HSL both ways, by hand.
 *
 * Compose ships `Color.hsl` and `luminance`, but the whole accent system depends
 * on this arithmetic, and eight lines of textbook conversion are cheaper than a
 * dependency on which of those two spellings the installed Compose exposes.
 */

/** Hue in degrees. */
private fun Color.hue(): Float {
    val max = maxOf(red, green, blue)
    val min = minOf(red, green, blue)
    val delta = max - min
    if (delta < 0.0001f) return 0f
    val degrees: Float = when (max) {
        red -> 60f * (((green - blue) / delta).mod(6f))
        green -> 60f * ((blue - red) / delta + 2f)
        else -> 60f * ((red - green) / delta + 4f)
    }
    return degrees.mod(360f)
}

/** A colour from hue (degrees), saturation and lightness, all wrapped or clamped. */
private fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
    val h = hue.mod(360f)
    val chroma = (1f - abs(2f * lightness - 1f)) * saturation
    val second = chroma * (1f - abs((h / 60f).mod(2f) - 1f))
    val match = lightness - chroma / 2f
    val (red, green, blue) = when {
        h < 60f -> Triple(chroma, second, 0f)
        h < 120f -> Triple(second, chroma, 0f)
        h < 180f -> Triple(0f, chroma, second)
        h < 240f -> Triple(0f, second, chroma)
        h < 300f -> Triple(second, 0f, chroma)
        else -> Triple(chroma, 0f, second)
    }
    return Color(red = red + match, green = green + match, blue = blue + match)
}

/** Perceived brightness, 0f..1f. Enough to tell a dark page from a light one. */
private fun Color.brightness(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue
