package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
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
 * forms to keep in step for the sake of a heading.
 *
 * Neither the login nor the password is `rememberSaveable`: saved state is
 * written to the process's saved-instance bundle, and a password does not
 * belong there. The cost is that a rotation mid-typing clears the field, which
 * is the right trade for the one field in this app that is a secret.
 *
 * @param reauth true when the session died upstream and only the password is
 *   being asked for; [knownLogin] is then shown rather than typed.
 */
@Composable
fun DiarySignInScreen(
    reauth: Boolean,
    knownLogin: String,
    busy: Boolean,
    failure: DiaryFailure?,
    onSignIn: (login: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    onEdited: () -> Unit = {},
) {
    var login by remember(knownLogin) { mutableStateOf(knownLogin) }
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    val canSubmit = password.isNotBlank() && login.isNotBlank() && !busy
    fun submit() {
        if (canSubmit) onSignIn(login, password)
    }

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
            title = stringResource(
                if (reauth) R.string.diary_reauth_title else R.string.diary_sign_in_title,
            ),
            subtitle = if (reauth) {
                stringResource(R.string.diary_reauth_message, knownLogin)
            } else {
                stringResource(R.string.diary_sign_in_subtitle)
            },
        )

        RoundedCardContainer {
            GroupRow {
                OutlinedTextField(
                    value = login,
                    onValueChange = {
                        login = it.trim()
                        onEdited()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.diary_login_label)) },
                    singleLine = true,
                    // The login is not asked for again on a re-auth: it is
                    // already known, and a field that can be changed there
                    // invites signing in as somebody else by accident.
                    enabled = !reauth && !busy,
                    isError = failure != null,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                    ),
                )
            }

            GroupRow {
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        onEdited()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.diary_password_label)) },
                    singleLine = true,
                    enabled = !busy,
                    isError = failure != null,
                    visualTransformation = if (revealed) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { revealed = !revealed }) {
                            Icon(
                                imageVector = if (revealed) {
                                    Icons.Rounded.VisibilityOff
                                } else {
                                    Icons.Rounded.Visibility
                                },
                                contentDescription = stringResource(
                                    if (revealed) {
                                        R.string.diary_password_hide
                                    } else {
                                        R.string.diary_password_reveal
                                    },
                                ),
                            )
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
            }

            if (failure != null) {
                GroupRow {
                    Text(
                        text = failure.asSignInText(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            GroupActionItem(
                label = stringResource(R.string.diary_sign_in_action),
                icon = Icons.AutoMirrored.Rounded.Login,
                enabled = canSubmit,
                busy = busy,
                onClick = ::submit,
            )
        }

        // The promise the server actually keeps, said where it matters: on the
        // screen that is asking for the password. It is the one thing about
        // this integration a user has a right to be suspicious about.
        RoundedCardContainer {
            GroupRow(verticalAlignment = Alignment.Top) {
                AccentIconTile(icon = Icons.Rounded.Lock, tone = accentTone(1))
                Text(
                    text = stringResource(R.string.diary_password_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * What a failure says on the sign-in form.
 *
 * [DiaryFailure.SignInRequired] means "the diary refused these credentials"
 * *here* and "your session is gone" everywhere else, which is the one place the
 * two readings of a 401 differ — and the reason this is its own function rather
 * than one shared sentence.
 */
@Composable
private fun DiaryFailure.asSignInText(): String = when (this) {
    DiaryFailure.SignInRequired -> stringResource(R.string.diary_error_credentials)
    else -> asText()
}
