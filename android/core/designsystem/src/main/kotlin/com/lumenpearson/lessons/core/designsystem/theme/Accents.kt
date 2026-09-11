package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * The pastel tile and the glyph that sits on it — the per-row colour that makes
 * a list of grouped rows scannable by hue rather than by reading every title.
 */
@Immutable
data class AccentTone(
    val container: Color,
    val content: Color,
)

/**
 * How many distinct row hues the system has; also the modulus for [accentSlotFor].
 *
 * The hues themselves, and every formula that turns one into a colour, live in
 * [AccentMath] — the widget needs the same answers and has no `ColorScheme` to
 * ask them of.
 */
val AccentSlotCount: Int = AccentMath.SlotCount

/**
 * Dark schemes need the tile dark and the glyph bright; light schemes the reverse.
 *
 * Weighted brightness of the page itself rather than a flag passed down from the
 * theme: a wallpaper-derived scheme can be installed by anything, and the only
 * reliable answer to "is this a dark page" is the page's own colour.
 */
private val ColorScheme.isDarkScheme: Boolean
    get() = AccentMath.brightnessOf(surface) < 0.5f

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
    val dark = isDarkScheme
    return AccentTone(
        container = AccentMath.container(hue, dark),
        content = AccentMath.content(hue, dark),
    )
}

/** One of the [AccentSlotCount] standard row hues. Out-of-range slots wrap. */
@Composable
fun accentTone(slot: Int): AccentTone {
    val scheme = MaterialTheme.colorScheme
    val offset = AccentMath.HueOffsets[slot.mod(AccentSlotCount)]
    return scheme.toneForHue(AccentMath.hueOf(scheme.primary) + offset)
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
        explicit != null -> scheme.toneForHue(AccentMath.hueOf(explicit))
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
    return scheme.toneForHue(AccentMath.hueOf(scheme.error))
}

/** @see AccentMath.slotFor */
fun accentSlotFor(key: String): Int = AccentMath.slotFor(key)
