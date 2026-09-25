package com.lumenpearson.lessons.ui.legal

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.legal.LegalDocument
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.text.rememberMarkupStyles

/**
 * «Продолжая, вы принимаете Условия использования и Политику
 * конфиденциальности» — the line under the first screen's button.
 *
 * **Always drawn**, whatever the build was told. A build with no address still
 * carries both texts in its assets, so there is always something to open, and
 * a line that came and went with a Gradle property would make what a reader
 * accepted depend on who compiled their APK.
 *
 * Small and quiet on purpose, after GMS Flags Reborn's: it sits under the
 * button rather than in a dialog in front of it, because nothing is recorded
 * when somebody continues — there is no acceptance to collect, only a sentence
 * to be told. The full-size way to the same two documents is «О приложении».
 *
 * Correction mode cannot reach it by a long press, the known limit it shares
 * with the design credit: the lookup matches the whole drawn text, and this
 * text is built from three strings.
 *
 * @param onOpen what a tap on a link does. Left `null` it opens the document
 *   the way [rememberLegalOpener] does — the browser when it can, the bundled
 *   copy when it cannot — which is what every caller but a test wants.
 */
@Composable
fun LegalAcceptanceLine(
    modifier: Modifier = Modifier,
    onOpen: ((LegalDocument) -> Unit)? = null,
) {
    val opener = rememberLegalOpener()
    // The listener lives inside an AnnotatedString that is remembered, so it
    // must read the newest callback rather than the one it was built with.
    val open by rememberUpdatedState(onOpen ?: opener::open)
    val link = rememberMarkupStyles().link

    val template = correctedString(R.string.onboarding_legal_notice)
    val terms = correctedString(R.string.onboarding_legal_terms)
    val privacy = correctedString(R.string.onboarding_legal_privacy)
    // The whole sentence as plain text, for the one case the links cannot be
    // placed: a proofreader's correction that typed over a placeholder. The
    // formatted read falls back to the shipped pattern when the corrected one
    // will not format, and the reader is still told what continuing means;
    // the two documents stay reachable from «О приложении».
    val plain = correctedString(R.string.onboarding_legal_notice, terms, privacy)

    val text = remember(template, plain, terms, privacy, link) {
        legalNoticeText(template, terms, privacy, link) { open(it) } ?: AnnotatedString(plain)
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth(),
    )
}
