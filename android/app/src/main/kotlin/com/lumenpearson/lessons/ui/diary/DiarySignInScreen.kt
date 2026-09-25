package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace

/**
 * Two screens in one composable: the first sign-in, and the one that asks for
 * the password again after the diary has closed the session on its side.
 *
 * They are one because they differ by exactly two things — the heading, and
 * whether the login field can be edited — and because the second one has to
 * arrive with the login already filled in. Splitting them would have been two
 * forms to keep in step for the sake of a heading. The fields themselves are
 * [DiaryCredentialFields], which the onboarding's sign-in uses too.
 *
 * The form says, before anything is typed, which diary it signs in to and the
 * one host the password will go to ([place]) — the privacy policy promises
 * exactly that, and it is the one thing about this integration a family has a
 * right to be suspicious about.
 *
 * @param reauth true when the session died upstream and only the password is
 *   being asked for; [knownLogin] is then shown rather than typed.
 * @param place what the catalog says about the diary this form signs in to;
 *   `null` until it has been read, and then no host is named rather than a
 *   guessed one.
 * @param schoolName the school, when the target carries one («Сетевой город»).
 * @param onChangeDiary opens the diary picker; `null` hides the way to it —
 *   on a re-authentication, which is to the same account by definition.
 * @param failed whether the last attempt was refused — the fields go red and
 *   nothing else. *What* went wrong is said by the pop-up the caller hosts, not
 *   here: a line of text below two fields and above a button is under the
 *   keyboard on a phone, which is the one place it was guaranteed not to be
 *   read. The red outline is worth keeping anyway, because it is the half that
 *   survives dismissing the pop-up and says which form the answer was about.
 */
@Composable
fun DiarySignInScreen(
    reauth: Boolean,
    knownLogin: String,
    /** Whether the configured server address is plain `http://`; see below. */
    insecureServer: Boolean,
    busy: Boolean,
    failed: Boolean,
    onSignIn: (login: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    onEdited: () -> Unit = {},
    place: DiaryPlace? = null,
    schoolName: String? = null,
    onChangeDiary: (() -> Unit)? = null,
) {
    var login by remember(knownLogin) { mutableStateOf(knownLogin) }

    Column(
        modifier = modifier
            .fillMaxSize()
            // On the outer column, as on the join screen: the keyboard shrinks
            // the scrolling area rather than growing the content inside it.
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(
                start = ScreenPadding,
                end = ScreenPadding,
                top = statusBarSpace() + 8.dp,
                bottom = LocalBottomBarSpace.current,
            ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        ScreenHeader(
            title = correctedString(
                if (reauth) R.string.diary_reauth_title else R.string.diary_sign_in_title,
            ),
            subtitle = if (reauth) {
                correctedString(R.string.diary_reauth_message, knownLogin)
            } else {
                correctedString(R.string.diary_sign_in_subtitle)
            },
        )

        DiaryWhereRow(
            place = place,
            schoolName = schoolName,
            onChange = onChangeDiary.takeUnless { reauth || busy },
        )

        // Said before the password is typed, not after it has been sent.
        //
        // `network_security_config.xml` permits cleartext for every host on
        // purpose — the realistic deployment is uvicorn on a machine in the
        // school, reached by LAN address, and no public CA issues a
        // certificate for one of those. The password is not what that
        // costs: it goes over https to the diary's own origin and nowhere
        // else. What crosses an http:// server is what the diary hands back —
        // the session this form registers with our server, and the key to it
        // — and on http:// anybody on the same Wi-Fi can take either.
        if (insecureServer) {
            RoundedCardContainer {
                GroupRow {
                    Text(
                        text = correctedString(R.string.diary_insecure_server),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        DiaryCredentialFields(
            login = login,
            onLoginChange = { login = it },
            onSubmit = onSignIn,
            loginEditable = !reauth,
            busy = busy,
            failed = failed,
            onEdited = onEdited,
        )

        // The promise the app actually keeps, said where it matters: on the
        // screen that is asking for the password, with the one host it goes to.
        RoundedCardContainer {
            GroupRow(verticalAlignment = Alignment.Top) {
                AccentIconTile(icon = Icons.Rounded.Lock, tone = accentTone(1))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    place?.host?.let { host ->
                        Text(
                            text = correctedString(R.string.diary_password_destination, host),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = correctedString(R.string.diary_password_notice),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * Which diary the form signs in to — the system, then the region and the
 * school — with the way to another when there is one.
 */
@Composable
private fun DiaryWhereRow(
    place: DiaryPlace?,
    schoolName: String?,
    onChange: (() -> Unit)?,
) {
    val system = place?.systemName()
    val detail = listOfNotNull(place?.regionName(), schoolName?.takeIf { it.isNotBlank() })
        .joinToString(" · ")
        .ifBlank { null }
    // Nothing to name and nothing to change: the row would be a blank card.
    if (system == null && onChange == null) return
    val title = system?.let { correctedString(R.string.diary_sign_in_where, it) }
        ?: correctedString(R.string.diary_pick_title)
    RoundedCardContainer {
        if (onChange != null) {
            GroupLinkItem(
                title = title,
                subtitle = detail,
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(4),
                value = correctedString(R.string.diary_pick_change),
                onClick = onChange,
            )
        } else {
            GroupItem(
                title = title,
                subtitle = detail,
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(4),
            )
        }
    }
}

/**
 * The region's name in the language the app is showing. Names live in the
 * catalog in both languages rather than in string resources (K16), so the
 * choice between them is made here, by the composition — which below Android
 * 13 is the only place that knows which language the reader chose.
 */
@Composable
internal fun DiaryPlace.regionName(): String? = localized(regionRu, regionEn)

/** @see regionName */
@Composable
internal fun DiaryPlace.systemName(): String? = localized(systemRu, systemEn)

@Composable
internal fun localized(ru: String?, en: String?): String? {
    val russian = Locale.current.language == "ru"
    val first = if (russian) ru else en
    return first?.takeIf { it.isNotBlank() } ?: (if (russian) en else ru)?.takeIf { it.isNotBlank() }
}
