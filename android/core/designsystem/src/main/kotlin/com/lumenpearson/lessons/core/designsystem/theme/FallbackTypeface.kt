package com.lumenpearson.lessons.core.designsystem.theme

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.os.Build
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import com.lumenpearson.lessons.core.designsystem.R

/**
 * One typeface out of two files: Google Sans Flex for Latin and digits, Onest
 * for Cyrillic.
 *
 * **Compose cannot express this, and neither can `res/font`.** A `FontFamily`
 * of several `Font`s picks between them by weight and style, never by which of
 * them can draw the character in hand; a font-family XML does the same. What
 * chooses by coverage is the platform's own fallback chain, and the only way to
 * declare a custom one is `Typeface.CustomFallbackBuilder` — a base family,
 * then the fallbacks, then the system's. A character the base cannot draw walks
 * down that chain, which is exactly the rule wanted here: «Алгебра 08:30» takes
 * the word from Onest and the digits from Google Sans Flex, in one line.
 *
 * Compose reaches it through [AndroidFont], which exists for this: a `Font`
 * whose typeface the caller builds. One per weight, because the chain is built
 * at a pinned `wght` — both files are variable on that axis and nothing else,
 * so the weight is set on each half rather than synthesised by Minikin, which
 * is what smears the stems of a variable font.
 *
 * **`CustomFallbackBuilder` is API 29 and this app's floor is 26.** On 26 to 28
 * there is no way to put a second file in front of the system's chain, so those
 * builds are set in Onest alone: it covers Latin, digits and Cyrillic on its
 * own, so the app is correct and merely less like itself. That is the right way
 * round — the older phone loses the Latin face, not the ability to read its
 * own language.
 */
private object ChainLoader : AndroidFont.TypefaceLoader {

    override fun loadBlocking(context: Context, font: AndroidFont): Typeface? {
        val weight = font.weight.weight
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return onestOnly(context, weight)
        return runCatching { chain(context, weight) }.getOrNull() ?: onestOnly(context, weight)
    }

    override suspend fun awaitLoad(context: Context, font: AndroidFont): Typeface? =
        loadBlocking(context, font)

    /**
     * Latin first, Cyrillic behind it, the system behind both.
     *
     * `setSystemFallback("sans-serif")` is kept rather than dropped: between
     * them the two files cover this app's own text, and everything else a
     * reader can paste into a note or a subject name — an emoji, a dash this
     * app does not ship, somebody's name in Georgian — has to come from
     * somewhere, and the alternative to the system's chain is tofu.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun chain(context: Context, weight: Int): Typeface {
        val latin = family(context, R.font.google_sans_flex, weight)
        val cyrillic = family(context, R.font.onest, weight)
        return Typeface.CustomFallbackBuilder(latin)
            .addCustomFallback(cyrillic)
            .setSystemFallback("sans-serif")
            .build()
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun family(context: Context, resId: Int, weight: Int): PlatformFontFamily =
        PlatformFontFamily.Builder(
            PlatformFont.Builder(context.resources, resId)
                // The axis both files carry, and the only one either carries.
                .setFontVariationSettings("'wght' $weight")
                .setWeight(weight)
                .build(),
        ).build()

    /**
     * The floor build, and the fallback if the chain cannot be built at all.
     *
     * `Resources.getFont` rather than `ResourcesCompat`: it landed in API 26,
     * which is this app's floor, and the module would otherwise take a
     * dependency on `androidx.core` to reach the same call.
     */
    private fun onestOnly(context: Context, weight: Int): Typeface? =
        runCatching { context.resources.getFont(R.font.onest) }
            .getOrNull()
            ?.let { Typeface.create(it, weight, false) }
}

/** One weight of the app's face; see [ChainLoader] for why this is not a plain `Font`. */
internal class ChainFont(
    override val weight: FontWeight,
) : AndroidFont(FontLoadingStrategy.Blocking, ChainLoader, FontVariation.Settings()) {
    override val style: FontStyle = FontStyle.Normal
}
