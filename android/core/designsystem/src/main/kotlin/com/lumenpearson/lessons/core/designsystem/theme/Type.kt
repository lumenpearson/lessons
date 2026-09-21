package com.lumenpearson.lessons.core.designsystem.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.lumenpearson.lessons.core.designsystem.R
import com.lumenpearson.lessons.core.model.AppFont

/**
 * Onest, bundled as the single variable font file Google Fonts publishes.
 *
 * **It replaced Google Sans Flex, and the reason is the only one that matters
 * for this product: that typeface has no Cyrillic at all.** Its own coverage
 * metadata on Google Fonts lists ten subsets — latin, latin-ext, vietnamese,
 * math, symbols and five scripts nobody here writes — and not one code point
 * in U+0400–U+04FF. So every Russian word in an app whose product language is
 * Russian was drawn by the device's fallback face while the digits and the
 * Latin beside it came from the bundled file: two typefaces in one row, at
 * different x-heights, and 0.29 MB shipped to draw «каб.» in somebody else's
 * font. That had been true since the design system was taken from Essentials,
 * which is an English app and had no way to notice.
 *
 * Onest is a geometric sans in the same family of shapes, drawn with Cyrillic
 * from the start: 780 code points, 162 of them Cyrillic, 876 glyphs, in
 * 193 056 bytes — smaller than the instanced file it replaces, and able to
 * draw the app.
 *
 * **Nothing instances it, and nothing needs to.** Google Sans Flex carried six
 * variation axes and the app moved two, which is what the build-time instancer
 * in `fonts/instance.py` existed to fix; Onest carries exactly one, `wght`,
 * and the app varies it. There is nothing to freeze, so the task, the script
 * and the Python that every Android build needed for them are gone —
 * `FontAxisTest` keeps the guard that would catch a multi-axis file arriving
 * here again, and git holds the machinery for the day it does.
 *
 * **SIL Open Font License 1.1, Copyright 2021 The Onest Project Authors.**
 * Taken from the file's own `name` table, entry 13, rather than from where it
 * was downloaded — this module has already carried a licence claim that was
 * wrong for months because it was copied from a repository's README. The
 * notice the OFL requires to travel with every copy is packaged beside it, in
 * `src/main/assets/licenses/onest_OFL.txt`, and the app names the licence on
 * the «Лицензии» sheet. The file ships byte for byte as downloaded, so nothing
 * here is a modified version in the licence's sense.
 *
 * Essentials declares its family with one `Normal` entry and lets the platform
 * synthesise everything heavier. That is fine for a settings app, but this one
 * leans on real weight — the hero card's headline, the countdown, the numbers
 * on the widget — and a synthesised bold on a variable font smears the stems.
 * So each weight is registered as its own instance of the same file with the
 * `wght` axis pinned, which is what a variable font is for and costs no extra
 * bytes.
 */
private fun onestFont(weight: FontWeight): Font = Font(
    resId = R.font.onest,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
    ),
)

/** The app's typeface. */
val LessonsSans: FontFamily = FontFamily(
    onestFont(FontWeight.Light),
    onestFont(FontWeight.Normal),
    onestFont(FontWeight.Medium),
    onestFont(FontWeight.SemiBold),
    onestFont(FontWeight.Bold),
)

/**
 * The Essentials type scale, verbatim.
 *
 * Copied from `sameerasw/essentials` `ui/theme/Type.kt` rather than tuned again
 * here: sizes, line heights and tracking are what make a screen of grouped rows
 * read as that app, and a "small improvement" to one of the fifteen roles is how
 * a borrowed design system stops looking borrowed.
 *
 * It is a table rather than fifteen `TextStyle`s because the family and the size
 * are now settings: a literal `fontFamily = LessonsSans` repeated fifteen
 * times is fifteen places for one of them to keep the old value.
 *
 * @property size the role's size at scale 1, in sp.
 * @property lineHeight its line height at scale 1, in sp.
 * @property tracking letter spacing at scale 1, in sp.
 */
private data class Role(
    val size: Float,
    val lineHeight: Float,
    val tracking: Float,
    val weight: FontWeight = FontWeight.Normal,
)

/**
 * Every size in the scale, multiplied together.
 *
 * Size, line height and tracking are scaled by the same factor and never
 * separately: a line height left behind by a grown font size is a paragraph
 * whose descenders sit on the line below, and tracking left behind is type that
 * looks tighter the larger it gets. The whole style is one proportion.
 *
 * [scale] is clamped rather than trusted, because it arrives from a preferences
 * file that a previous version — or a corrupt write — may have put anything in,
 * and a `0f` here would render the entire app invisible.
 */
private fun Role.toTextStyle(family: FontFamily, scale: Float): TextStyle {
    val factor = scale.coerceIn(MinTextScale, MaxTextScale)
    return TextStyle(
        fontFamily = family,
        fontWeight = weight,
        fontSize = (size * factor).sp,
        lineHeight = (lineHeight * factor).sp,
        letterSpacing = (tracking * factor).sp,
    )
}

/** Ends of the text-size range this module will honour; see [Role.toTextStyle]. */
const val MinTextScale: Float = 0.5f

/** @see MinTextScale */
const val MaxTextScale: Float = 2f

/**
 * The app's type scale, at a chosen [family] and [scale].
 *
 * A function rather than the constant it used to be, because both of its inputs
 * are now settings and a `Typography` is immutable: the theme rebuilds this when
 * either changes and hands the result to Material, which is the only way the
 * choice can reach the fifteen roles at once.
 *
 * Callers should not call it on every recomposition — it allocates fifteen
 * `TextStyle`s — which is why [LessonsTheme] keeps it inside a `remember` keyed
 * on the two arguments.
 *
 * @param family [LessonsSans], or the device's own face. See [AppFont].
 * @param scale multiplies every size in the scale; 1 is the designed one.
 */
fun lessonsTypography(
    family: FontFamily = LessonsSans,
    scale: Float = 1f,
): Typography = Typography(
    displayLarge = Role(57f, 64f, -0.25f).toTextStyle(family, scale),
    displayMedium = Role(45f, 52f, 0f).toTextStyle(family, scale),
    displaySmall = Role(36f, 44f, 0f).toTextStyle(family, scale),
    headlineLarge = Role(32f, 40f, 0f).toTextStyle(family, scale),
    headlineMedium = Role(28f, 36f, 0f).toTextStyle(family, scale),
    headlineSmall = Role(24f, 32f, 0f).toTextStyle(family, scale),
    titleLarge = Role(22f, 28f, 0f).toTextStyle(family, scale),
    titleMedium = Role(16f, 24f, 0.15f, FontWeight.Medium).toTextStyle(family, scale),
    titleSmall = Role(14f, 20f, 0.1f, FontWeight.Medium).toTextStyle(family, scale),
    bodyLarge = Role(16f, 24f, 0.5f).toTextStyle(family, scale),
    bodyMedium = Role(14f, 20f, 0.25f).toTextStyle(family, scale),
    bodySmall = Role(12f, 16f, 0.4f).toTextStyle(family, scale),
    labelLarge = Role(14f, 20f, 0.1f, FontWeight.Medium).toTextStyle(family, scale),
    labelMedium = Role(12f, 16f, 0.5f, FontWeight.Medium).toTextStyle(family, scale),
    labelSmall = Role(11f, 16f, 0.5f, FontWeight.Medium).toTextStyle(family, scale),
)

/**
 * The face behind a stored [AppFont].
 *
 * [FontFamily.SansSerif] rather than [FontFamily.Default] for the system
 * choice: Default is whatever Compose resolves last, which on a themed device
 * is usually the same thing but is not promised to be, and the setting says
 * "системный шрифт" out loud.
 */
fun fontFamilyOf(font: AppFont): FontFamily = when (font) {
    AppFont.BUNDLED -> LessonsSans
    AppFont.SYSTEM -> FontFamily.SansSerif
}
