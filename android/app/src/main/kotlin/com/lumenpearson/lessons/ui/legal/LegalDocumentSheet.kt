package com.lumenpearson.lessons.ui.legal

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.legal.BundledLegal
import com.lumenpearson.lessons.core.data.legal.LegalDocument
import com.lumenpearson.lessons.core.data.legal.LegalText
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.component.LessonsLoadingBox
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.text.rememberMarkupStyles
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.ui.docs.DocsRun
import com.lumenpearson.lessons.ui.docs.docsRuns
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the sheet has to draw: nothing yet, nothing at all, or the document. */
internal sealed interface LegalLoad {
    data object Loading : LegalLoad
    data object Missing : LegalLoad
    data class Ready(val text: LegalText) : LegalLoad
}

/** The heading a document is shown under, in the sheet and on «О приложении». */
@StringRes
internal fun legalTitle(document: LegalDocument): Int = when (document) {
    LegalDocument.TERMS -> R.string.legal_title_terms
    LegalDocument.PRIVACY -> R.string.legal_title_privacy
}

/**
 * The copy of [document] the APK was built with, read from its assets.
 *
 * This is what makes the line on the first screen honest offline: the reader
 * is told that continuing accepts two documents, and they can read both with no
 * network and on a build that was given no address. Read on the IO dispatcher
 * because an asset is a file, and the sheet opens with a loader rather than a
 * frame's delay spent reading forty kilobytes on the main thread.
 *
 * @param url the current edition's address, when there is one. The sheet then
 *   says that it is showing the bundled copy and offers the link, because it
 *   only appears with an address when the browser could not be used.
 */
@Composable
internal fun LegalDocumentSheet(
    document: LegalDocument,
    url: String?,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val language = Locale.current.language
    val load by produceState<LegalLoad>(LegalLoad.Loading, document, language) {
        val text = withContext(Dispatchers.IO) { BundledLegal.read(context, document, language) }
        value = if (text != null) LegalLoad.Ready(text) else LegalLoad.Missing
    }
    LessonsBottomSheet(
        onDismissRequest = onDismiss,
        title = correctedString(legalTitle(document)),
    ) {
        LegalDocumentBody(load = load, url = url)
    }
}

/**
 * The sheet's content, apart from the sheet, so a test can draw it without a
 * window of its own.
 */
@Composable
internal fun LegalDocumentBody(
    load: LegalLoad,
    url: String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = rememberHapticView()
    val styles = rememberMarkupStyles()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .padding(bottom = ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(BlockGap),
    ) {
        when (load) {
            LegalLoad.Loading -> LessonsLoadingBox(modifier = Modifier.fillMaxWidth())

            LegalLoad.Missing -> Text(
                text = correctedString(R.string.legal_missing),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            is LegalLoad.Ready -> {
                load.text.edition?.let { edition ->
                    Text(
                        text = correctedString(
                            R.string.legal_edition,
                            edition.edition,
                            formatEffective(edition.effective),
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (url != null) {
                    Text(
                        text = correctedString(R.string.legal_bundled_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = {
                            LessonsHaptics.press(view)
                            openInBrowser(context, url)
                        },
                    ) {
                        Text(
                            text = correctedString(R.string.legal_open_current),
                            style = MaterialTheme.typography.labelLarge,
                        )
                    }
                }
                load.text.guide.pages.forEach { page ->
                    // Every section in one scroll rather than the guide's pager:
                    // a policy is read top to bottom, and a reader who is being
                    // asked to accept it should not have to find out that there
                    // are eleven more pages to swipe.
                    Text(
                        text = page.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.padding(top = 8.dp, start = 8.dp, end = 8.dp),
                    )
                    val runs = remember(page) { docsRuns(page.blocks) }
                    runs.forEach { run -> DocsRun(run = run, styles = styles) }
                }
            }
        }
    }
}

/** «25 сентября 2026 г.» — the date in the words of the language on screen. */
@Composable
private fun formatEffective(date: LocalDate): String {
    val locale = LocalResources.current.configuration.locales[0] ?: java.util.Locale.getDefault()
    return remember(date, locale) {
        DateTimeFormatter.ofLocalizedDate(FormatStyle.LONG).withLocale(locale).format(date)
    }
}

private val BlockGap = 12.dp
