package com.lumenpearson.lessons.ui.translate

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.text.LocalCorrections
import com.lumenpearson.lessons.core.designsystem.text.NoCorrections
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.core.designsystem.theme.rowContainer

/**
 * One string, opened up.
 *
 * Three things are on the sheet and all three are needed to write a good
 * correction: the key, so the fix can be found again in a file; the value as it
 * ships, so the reader can see what they are changing rather than what they
 * have already changed; and the field. The key is shown rather than hidden
 * because the person using this is by definition reading the app closely, and
 * because a correction submitted with "the text on the second settings page"
 * for an address costs somebody an afternoon.
 *
 * Usually one string; sometimes several, when the words the reader pressed are
 * written more than once in `values/` — see [AppCorrections.edit]. They share
 * one original and get one correction, and the card names every key.
 *
 * The sheet gives itself [NoCorrections], which is the editor's own rule
 * enforced rather than remembered: the value being corrected is also on the
 * screen underneath, so without this the card showing it would become a
 * correction target of its own and a long press inside the editor would open
 * another editor. Nothing under this sheet is correctable, including the
 * sheet's own copy.
 */
@Composable
internal fun TranslationEditorSheet(
    targets: List<CorrectionTarget>,
    locale: String,
    onDismiss: () -> Unit,
) {
    CompositionLocalProvider(LocalCorrections provides NoCorrections) {
        LessonsBottomSheet(
            onDismissRequest = onDismiss,
            title = stringResource(R.string.translation_editor_title),
        ) {
            EditorContent(targets = targets, locale = locale, onDismiss = onDismiss)
        }
    }
}

@Composable
private fun ColumnScope.EditorContent(
    targets: List<CorrectionTarget>,
    locale: String,
    onDismiss: () -> Unit,
) {
    val keys = targets.map { it.key }
    val original = targets.first().original
    val existing = TranslationMode.session.correctionOf(keys.first(), locale)
    // Saveable: the field survives the rotation that a long keyboard session on
    // a short screen invites, and there is nowhere else for the words to live.
    var draft by rememberSaveable(keys.first(), locale) { mutableStateOf(existing ?: original) }
    val view = rememberHapticView()
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    IdentityCard(
        stringKeys = keys,
        folder = TranslationXml.valuesFolder(locale),
        original = original,
    )

    OutlinedTextField(
        value = draft,
        onValueChange = { draft = it },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
        label = {
            Text(
                text = stringResource(R.string.translation_editor_label),
                style = LocalTextStyle.current.emphasised(focused),
            )
        },
        minLines = FieldMinLines,
        shape = RoundedCornerShape(FieldCorner),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Default,
        ),
        interactionSource = interactionSource,
    )

    Button(
        onClick = {
            LessonsHaptics.press(view)
            targets.forEach { target ->
                TranslationMode.record(
                    key = target.key,
                    locale = locale,
                    original = target.original,
                    corrected = draft,
                )
            }
            onDismiss()
        },
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        Text(stringResource(R.string.translation_editor_save))
    }

    // Only offered once there is something to undo. A button that restores what
    // is already on screen would be a button that does nothing, and the reader
    // would have to press it to find that out.
    if (existing != null) {
        OutlinedButton(
            onClick = {
                LessonsHaptics.press(view)
                keys.forEach { TranslationMode.drop(it, locale) }
                onDismiss()
            },
            shape = CircleShape,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding)
                .height(PillHeight),
        ) {
            Text(stringResource(R.string.translation_editor_revert))
        }
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Where the string is and what it currently says.
 *
 * The key and the folder are monospaced because they are not prose — they are
 * two things that will be typed into a search box, and a proportional font is
 * where `l` and `1` stop being different characters.
 */
@Composable
private fun IdentityCard(stringKeys: List<String>, folder: String, original: String) {
    Surface(
        shape = RoundedCornerShape(CardCorner),
        color = MaterialTheme.colorScheme.rowContainer,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    ) {
        Column(
            modifier = Modifier.padding(CardPadding),
            verticalArrangement = Arrangement.spacedBy(LineGap),
        ) {
            Labelled(
                label = stringResource(R.string.translation_editor_key),
                // One per line when the words belong to several strings at
                // once. All of them are about to get the same correction, so
                // all of them have to be readable before it is written.
                value = stringKeys.joinToString("\n"),
                monospace = true,
            )
            Labelled(
                label = stringResource(R.string.translation_editor_file),
                value = "$folder/",
                monospace = true,
            )
            Labelled(
                label = stringResource(R.string.translation_editor_current),
                value = original,
                monospace = false,
            )
        }
    }
}

@Composable
private fun Labelled(label: String, value: String, monospace: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            text = value,
            style = if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
        )
    }
}

private val CardCorner = 24.dp

private val CardPadding = 20.dp

private val LineGap = 8.dp

private val FieldCorner = 24.dp

/** Room for a sentence that grew in translation without the field jumping. */
private const val FieldMinLines = 3

/** Height of a full-width pill button on a sheet; the bug report's own. */
private val PillHeight = 56.dp
