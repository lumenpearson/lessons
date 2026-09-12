package com.lumenpearson.lessons.ui.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * The way in and out of correction mode, as a settings group.
 *
 * The whole feature hangs off this one call, which is why it is the only public
 * thing in the package that the settings page has to know about: the editor and
 * the session sheet are opened from the text being corrected and from this row
 * respectively, and neither needs wiring anywhere else.
 */
fun LazyListScope.translationRows() = item(key = "translation") { TranslationGroup() }

@Composable
private fun TranslationGroup() {
    var showSession by remember { mutableStateOf(false) }
    val session = TranslationMode.session

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = stringResource(R.string.translation_group))
        RoundedCardContainer {
            // Wrapped in [Correctable] like anything else, and not as a joke:
            // the switch that turns the mode on is the first row a proofreader
            // meets while the mode is on, so it had better be correctable too.
            Correctable(R.string.translation_mode) { title ->
                GroupSwitchItem(
                    title = title,
                    subtitle = stringResource(R.string.translation_mode_description),
                    icon = Icons.Rounded.Translate,
                    tone = accentTone(4),
                    checked = TranslationMode.enabled,
                    onCheckedChange = { TranslationMode.enabled = it },
                )
            }
            // Hidden while there is nothing to see and no way to make anything:
            // a row that opens an empty list is a row that has to be opened to
            // learn that it is empty.
            if (TranslationMode.enabled || !session.isEmpty) {
                GroupLinkItem(
                    title = stringResource(R.string.translation_session),
                    subtitle = stringResource(R.string.translation_session_description),
                    icon = Icons.Rounded.EditNote,
                    tone = accentTone(5),
                    value = session.size.toString(),
                    onClick = { showSession = true },
                )
            }
        }
    }

    if (showSession) {
        TranslationSessionSheet(onDismiss = { showSession = false })
    }
}
