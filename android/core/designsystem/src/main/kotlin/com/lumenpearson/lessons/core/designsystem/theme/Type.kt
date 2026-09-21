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
 * Google Sans Flex, the typeface Essentials is set in, bundled as the single
 * variable font file the reference ships.
 *
 * It is not in `res/font`. The file as it was downloaded is
 * `core/designsystem/fonts/google_sans_flex.ttf`, a source the build reads: at
 * six variation axes it is 3.81 MB, of which 3.41 MB is outline deltas for
 * shapes this app never asks for, so `instance<Variant>Font` freezes the four
 * it does not move and the 0.29 MB result is what `R.font.google_sans_flex`
 * resolves to. `-Plessons.font.axes=all` ships the file untouched, for a
 * machine with no Python; `FontAxisTest` says what each setting promises.
 *
 * **SIL Open Font License 1.1, Copyright 2015 Google LLC.** This comment used
 * to say "MIT from sameerasw/essentials", which was wrong twice over: a
 * repository's licence covers what its author may license, and the typeface is
 * Google's, released by Google under the OFL. The file says so itself — entry
 * 13 of its `name` table — and that is where the claim now comes from rather
 * than from where the file was copied. The notice the OFL requires to travel
 * with every copy is packaged beside it, in
 * `src/main/assets/licenses/google_sans_flex_OFL.txt`, and the app names the
 * licence on the «Лицензии» sheet.
 *
 * The build does rewrite the file, which the OFL allows: it permits
 * modification outright, and the rename it requires of a derivative applies
 * only to a Reserved Font Name, which this font declares none of. What the
 * licence does require travels with the copy — the instancer leaves the `name`
 * table alone, so the copyright and the licence entry in the shipped file are
 * the downloaded file's own, and `FontLicenceTest` reads them from what ships.
 *
 * Essentials declares the family with one `Normal` entry and lets the platform
 * synthesise everything heavier. That is fine for a settings app, but this one
 * leans on real weight — the hero card's headline, the countdown, the numbers on
 * the widget — and a synthesised bold on a variable font smears the stems. So
 * each weight is registered as its own instance of the same file with the `wght`
 * axis pinned, which is what a variable font is for and costs no extra bytes.
 */
private fun flexFont(weight: FontWeight): Font = Font(
    resId = R.font.google_sans_flex,
    weight = weight,
    variationSettings = FontVariation.Settings(
        FontVariation.weight(weight.weight),
    ),
)

/** The app's typeface. */
val GoogleSansFlex: FontFamily = FontFamily(
    flexFont(FontWeight.Light),
    flexFont(FontWeight.Normal),
    flexFont(FontWeight.Medium),
    flexFont(FontWeight.SemiBold),
    flexFont(FontWeight.Bold),
)

/**
 * The same face with the rounded axis pushed all the way over.
 *
 * Essentials keeps this as a second family (`GoogleSansFlexRounded`) and uses it
 * where the type sits inside something already round — a pill, a tile, a widget
 * cell. It is exposed for the same reason rather than being applied globally:
 * at body size the rounding costs legibility.
 */
val GoogleSansFlexRounded: FontFamily = FontFamily(
    Font(
        resId = R.font.google_sans_flex,
        variationSettings = FontVariation.Settings(
            FontVariation.Setting("ROND", 100f),
        ),
    ),
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
 * are now settings: a literal `fontFamily = GoogleSansFlex` repeated fifteen
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
 * @param family [GoogleSansFlex], or the device's own face. See [AppFont].
 * @param scale multiplies every size in the scale; 1 is the designed one.
 */
fun lessonsTypography(
    family: FontFamily = GoogleSansFlex,
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
    AppFont.BUNDLED -> GoogleSansFlex
    AppFont.SYSTEM -> FontFamily.SansSerif
}
