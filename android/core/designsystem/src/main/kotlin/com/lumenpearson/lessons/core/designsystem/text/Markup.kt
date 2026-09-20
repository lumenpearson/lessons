package com.lumenpearson.lessons.core.designsystem.text

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration

/**
 * What bold, code and a link look like inside a line of prose.
 *
 * Two screens draw text with marks in it — a release body, which is markdown
 * from GitHub parsed as it is drawn, and the guide, which is markdown from this
 * repository parsed before it ever reaches a composition. They read the same
 * kind of text and they must not look like two different applications, which is
 * the whole reason this is here rather than private to either of them. The
 * tokenizers stay apart, because their inputs are genuinely different shapes;
 * the appearance is one decision and is made once.
 */
@Immutable
class MarkupStyles(
    val bold: SpanStyle,
    val code: SpanStyle,
    val link: TextLinkStyles,
)

/**
 * [MarkupStyles] resolved against the current theme.
 *
 * Keyed on the two colours it reads rather than on the scheme: `ColorScheme`
 * has no value equality, so keying on the scheme itself would rebuild the
 * styles on every recomposition.
 */
@Composable
fun rememberMarkupStyles(): MarkupStyles {
    val scheme = MaterialTheme.colorScheme
    return remember(scheme.primary, scheme.surfaceContainer) {
        MarkupStyles(
            bold = SpanStyle(fontWeight = FontWeight.Bold),
            // The page colour, not a "highest" surface. This text sits on a
            // surfaceBright card, and the tonal step just above that is
            // invisible in a dark scheme. A hole through which the page shows
            // is the same trick the 2 dp gaps between rows play, and it reads
            // in both schemes.
            code = SpanStyle(fontFamily = FontFamily.Monospace, background = scheme.surfaceContainer),
            link = TextLinkStyles(
                style = SpanStyle(
                    color = scheme.primary,
                    fontWeight = FontWeight.Medium,
                    textDecoration = TextDecoration.Underline,
                ),
            ),
        )
    }
}
