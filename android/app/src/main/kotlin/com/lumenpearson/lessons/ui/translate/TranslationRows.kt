package com.lumenpearson.lessons.ui.translate

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Login
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.TranslationChange
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone

/**
 * The way in and out of correction mode, as a settings group.
 *
 * The whole feature hangs off this one call, which is why it is the only public
 * thing in the package that the settings page has to know about: the editor and
 * the session sheet are opened from the text being corrected and from this row
 * respectively, and neither needs wiring anywhere else.
 */
internal fun LazyListScope.translationRows(
    account: GithubAccount?,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
    submit: TranslationSubmit,
    onSubmit: (List<TranslationChange>) -> Unit,
    onAcknowledge: () -> Unit,
) = item(key = "translation") {
    TranslationGroup(account, canSignIn, onSignIn, submit, onSubmit, onAcknowledge)
}

@Composable
private fun TranslationGroup(
    account: GithubAccount?,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
    submit: TranslationSubmit,
    onSubmit: (List<TranslationChange>) -> Unit,
    onAcknowledge: () -> Unit,
) {
    // Saveable, not remembered, and this one carries more than the reader's
    // place in a list. [TranslationSessionSheet.onAcknowledge] is what forgets
    // a finished pull request, and it runs when the sheet is *dismissed* — a
    // swipe, the scrim, the back gesture. A rotation is none of those: held in
    // a plain `remember` the sheet went away without anybody being told, the
    // outcome stayed `Opened` in the view model, and the next press on this row
    // opened the pull request in a browser all over again.
    var showSession by rememberSaveable { mutableStateOf(false) }
    val session = TranslationMode.session

    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = correctedString(R.string.translation_group))
        RoundedCardContainer {
            // The account sits above the switch rather than in the group about
            // GitHub, because this is where it is needed and where a reader
            // looking for it will be: the mode below produces corrections, and
            // an account is what carries them anywhere. Essentials puts it in
            // the same place for the same reason.
            //
            // Signing in is not required to *make* corrections — the mode, the
            // editor and the session are local and work offline. It is required
            // only to send them, and the button that sends them is the one that
            // goes dark without it.
            when {
                account != null -> GroupItem(
                    title = correctedString(R.string.settings_github_signed_in_as, account.login),
                    subtitle = correctedString(R.string.translation_sign_in_description),
                    icon = Icons.Rounded.Code,
                    tone = accentTone(3),
                )
                canSignIn -> GroupLinkItem(
                    title = correctedString(R.string.settings_github_sign_in),
                    subtitle = correctedString(R.string.translation_sign_in_description),
                    icon = Icons.Rounded.Login,
                    tone = accentTone(3),
                    onClick = onSignIn,
                )
            }
            // Read through [correctedString] like anything else, and not as a
            // joke: the switch that turns the mode on is the first row a
            // proofreader meets while the mode is on, so it had better be
            // correctable too.
            GroupSwitchItem(
                title = correctedString(R.string.translation_mode),
                subtitle = correctedString(R.string.translation_mode_description),
                icon = Icons.Rounded.Translate,
                tone = accentTone(4),
                checked = TranslationMode.enabled,
                onCheckedChange = { TranslationMode.enabled = it },
            )
            // Hidden while there is nothing to see and no way to make anything:
            // a row that opens an empty list is a row that has to be opened to
            // learn that it is empty.
            if (TranslationMode.enabled || !session.isEmpty) {
                GroupLinkItem(
                    title = correctedString(R.string.translation_session),
                    subtitle = correctedString(R.string.translation_session_description),
                    icon = Icons.Rounded.EditNote,
                    tone = accentTone(5),
                    value = session.size.toString(),
                    onClick = { showSession = true },
                )
            }
        }
    }

    if (showSession) {
        TranslationSessionSheet(
            onDismiss = {
                showSession = false
                onAcknowledge()
            },
            isSignedIn = account != null,
            submit = submit,
            onSubmit = onSubmit,
        )
    }
}
