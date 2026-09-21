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
 * The app's face: Google Sans Flex for Latin and digits, Onest for Cyrillic.
 *
 * **Google Sans Flex has no Cyrillic at all** — its coverage on Google Fonts
 * is latin, latin-ext, vietnamese, math, symbols and five scripts nobody here
 * writes, with zero code points in U+0400–U+04FF. This app's product language
 * is Russian, so while it was the only bundled file every Russian word came
 * out of whatever face the device happened to fall back to, beside digits
 * drawn from the bundle: two typefaces in one row and no way to notice, since
 * nothing fails and nothing is logged. That had been true since the design
 * system was taken from Essentials, which is an English app.
 *
 * Both are bundled now and [ChainFont] puts them in one fallback chain, which
 * is the only construction that chooses by coverage rather than by weight —
 * see its note for why neither `FontFamily` nor a font-family XML can express
 * this, and what API 26 to 28 get instead.
 *
 * Both files carry one variable axis, `wght`, which is the one the app varies;
 * Google Sans Flex is frozen from its six down to that one, because an axis
 * costs a set of outline deltas per glyph and the other five were 3.5 MB of
 * shapes nothing here asks for. `FontAxisTest` holds both halves: that the
 * bundled files between them draw Russian, and that neither carries an axis
 * `Type.kt` never moves.
 *
 * **SIL Open Font License 1.1** for both — Google Sans Flex is Copyright 2015
 * Google LLC, Onest Copyright 2021 The Onest Project Authors. Each claim is
 * taken from that file's own `name` table rather than from where it was
 * downloaded, and the notices the licence requires travel beside them in
 * `src/main/assets/licenses/`. `FontLicenceTest` checks the pairing; the app
 * names both on the «Лицензии» sheet.
 *
 * A weight is registered rather than synthesised, which is what the chain is
 * built per weight for: Essentials declares one `Normal` entry and lets the
 * platform fake the rest, and a faked bold on a variable font smears the
 * stems. This app leans on real weight — the hero headline, the countdown, the
 * numbers on the widget — so each of the five is a real instance of both
 * files.
 */
val LessonsSans: FontFamily = FontFamily(
    ChainFont(FontWeight.Light),
    ChainFont(FontWeight.Normal),
    ChainFont(FontWeight.Medium),
    ChainFont(FontWeight.SemiBold),
    ChainFont(FontWeight.Bold),
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
