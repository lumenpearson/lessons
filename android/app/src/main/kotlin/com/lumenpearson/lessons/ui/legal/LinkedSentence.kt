package com.lumenpearson.lessons.ui.legal

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import com.lumenpearson.lessons.core.data.legal.LegalDocument

/** `%1$s`, `%2$s` … — the only placeholder a linked sentence may carry. */
private val PLACEHOLDER = Regex("%(\\d+)\\\$s")

/**
 * [template] with its `%N$s` placeholders replaced by the Nth label, each drawn
 * as its link. `null` when the template does not use every label exactly once.
 *
 * A sentence with a link inside it used to be three strings — the words
 * before, the link, the words after — and aapt2 trims the spaces at a string's
 * edges, so the credit on «О приложении» shipped as «по мотивамEssentials»
 * (#155). One template keeps the spaces where a translator typed them, lets a
 * language put the links in its own order, and lets Russian inflect the words
 * around them.
 *
 * `null` rather than a best effort because the template may be a reader's
 * correction, and one that lost a placeholder would otherwise draw «%2$s» on
 * the first screen or silently drop a link the reader is accepting. The caller
 * then draws the formatted sentence as plain text, which `correctedString`
 * already falls back to the shipped pattern for.
 */
internal fun linkedSentence(
    template: String,
    links: List<Pair<String, LinkAnnotation>>,
): AnnotatedString? {
    val matches = PLACEHOLDER.findAll(template).toList()
    val numbers = matches.map { it.groupValues[1].toIntOrNull() ?: return null }
    if (numbers.sorted() != (1..links.size).toList()) return null
    return buildAnnotatedString {
        var at = 0
        for (match in matches) {
            append(template.substring(at, match.range.first))
            val (label, link) = links[match.groupValues[1].toInt() - 1]
            withLink(link) { append(label) }
            at = match.range.last + 1
        }
        append(template.substring(at))
    }
}

/**
 * «Продолжая, вы принимаете Условия использования и Политику
 * конфиденциальности», with both documents as links. `null` as [linkedSentence].
 *
 * Each link is a [LinkAnnotation.Clickable] handing its document to [onOpen],
 * not a [LinkAnnotation.Url]. A URL goes straight to the platform's handler,
 * and every tap here must be able to end at the copy bundled in the APK
 * instead — offline, on a phone with no browser, on a build that knows no
 * address. A clickable annotation is still announced as a link by a screen
 * reader, which is the other half of why a plain click handler on the text was
 * not used.
 */
internal fun legalNoticeText(
    template: String,
    termsLabel: String,
    privacyLabel: String,
    linkStyles: TextLinkStyles,
    onOpen: (LegalDocument) -> Unit,
): AnnotatedString? = linkedSentence(
    template = template,
    links = listOf(
        termsLabel to legalLink(LegalDocument.TERMS, linkStyles, onOpen),
        privacyLabel to legalLink(LegalDocument.PRIVACY, linkStyles, onOpen),
    ),
)

private fun legalLink(
    document: LegalDocument,
    styles: TextLinkStyles,
    onOpen: (LegalDocument) -> Unit,
): LinkAnnotation = LinkAnnotation.Clickable(
    tag = document.slug,
    styles = styles,
    linkInteractionListener = { onOpen(document) },
)
