package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.ui.graphics.Color
import kotlin.math.abs

/**
 * The arithmetic behind the accent colours, with no Compose theme attached.
 *
 * It is split out from [accentTone] and friends because the widget needs the
 * same answers and cannot ask the same question: Glance has no `ColorScheme` and
 * no `MaterialTheme`, so every `@Composable` colour helper in this package is
 * unreachable from it. Before this existed the widget had no per-subject colour
 * at all — it used whatever hex the server happened to send and fell back to one
 * flat grey, so every row of the timeline was the same shape in the same colour.
 *
 * Keeping it in one place is the point. A subject's colour has to be the same in
 * the timetable, in the homework list and on the home screen, or it stops being
 * information and becomes decoration.
 */
object AccentMath {

    /**
     * Six hues spread around the wheel, as offsets from whatever the palette's
     * own primary is rather than as fixed hex codes: a wallpaper-derived palette
     * then rotates the whole set together instead of clashing with it.
     */
    val HueOffsets: FloatArray = floatArrayOf(0f, 42f, 96f, 158f, 214f, 292f)

    /** How many distinct hues the system has; also the modulus for [slotFor]. */
    val SlotCount: Int = HueOffsets.size

    /**
     * Stable slot for a free-form key.
     *
     * Hand-rolled instead of [String.hashCode] so the mapping cannot change when
     * the platform's hashing does: the same subject has to get the same colour
     * on every device and after every reinstall.
     */
    fun slotFor(key: String): Int {
        var hash = 0
        for (char in key.trim().lowercase()) {
            hash = hash * 31 + char.code
        }
        return hash.mod(SlotCount)
    }

    /** The hue a key lands on, given the palette's own primary hue. */
    fun hueFor(key: String, primaryHue: Float): Float =
        (primaryHue + HueOffsets[slotFor(key)]).mod(360f)

    /**
     * The pastel tile for a hue.
     *
     * Dark schemes need the tile dark and the glyph bright; light schemes the
     * reverse. One formula rather than two colour tables, so a hue that did not
     * exist when the tables were written still gets a usable pair.
     */
    fun container(hue: Float, dark: Boolean): Color =
        if (dark) hsl(hue, 0.34f, 0.20f) else hsl(hue, 0.72f, 0.90f)

    /** The glyph or label that sits on [container]. */
    fun content(hue: Float, dark: Boolean): Color =
        if (dark) hsl(hue, 0.66f, 0.74f) else hsl(hue, 0.55f, 0.38f)

    /**
     * A saturated version of the hue, for a mark rather than a surface.
     *
     * The timeline's accent bar is three pixels wide; a pastel that reads well
     * as a 40 dp tile disappears at that size, in either scheme.
     */
    fun mark(hue: Float, dark: Boolean): Color =
        if (dark) hsl(hue, 0.62f, 0.66f) else hsl(hue, 0.62f, 0.46f)

    /** Hue of an existing colour, in degrees. */
    fun hueOf(color: Color): Float {
        val max = maxOf(color.red, color.green, color.blue)
        val min = minOf(color.red, color.green, color.blue)
        val delta = max - min
        if (delta < 0.0001f) return 0f
        val degrees: Float = when (max) {
            color.red -> 60f * (((color.green - color.blue) / delta).mod(6f))
            color.green -> 60f * ((color.blue - color.red) / delta + 2f)
            else -> 60f * ((color.red - color.green) / delta + 4f)
        }
        return degrees.mod(360f)
    }

    /** Perceived brightness, 0f..1f. Enough to tell a dark surface from a light one. */
    fun brightnessOf(color: Color): Float =
        0.2126f * color.red + 0.7152f * color.green + 0.0722f * color.blue

    /**
     * A colour from hue (degrees), saturation and lightness, all wrapped or
     * clamped.
     *
     * Compose ships `Color.hsl`, but the whole accent system depends on this
     * arithmetic and ten lines of textbook conversion are cheaper than a
     * dependency on which spelling the installed Compose exposes.
     */
    fun hsl(hue: Float, saturation: Float, lightness: Float): Color {
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
}
