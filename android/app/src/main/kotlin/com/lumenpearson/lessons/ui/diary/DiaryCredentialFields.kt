package com.lumenpearson.lessons.ui.diary

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupRow
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString

/**
 * The login, the password and the button that sends them — the one diary
 * sign-in form in the app, used by Settings → «Дневник», by the re-sign-in on
 * the diary home and by the onboarding's sign-in step.
 *
 * One form, because the promises it keeps are about the form and not about the
 * screen around it: the password lives in this composable's `remember` and
 * nowhere else — not `rememberSaveable`, whose bundle the system writes to
 * disk, not a view model, not a callback's closure that outlives the press —
 * and it leaves only as the argument of [onSubmit]. A second copy of the form
 * would be a second place that promise has to be kept by hand. The cost is that
 * a rotation mid-typing clears the password, which is the right trade for the
 * one field in this app that is a secret.
 *
 * The login is hoisted ([login], [onLoginChange]) because it is not a secret
 * and the caller may want it to survive a recreation, which the onboarding does.
 *
 * @param login the login shown, as typed. Not trimmed per keystroke: the
 *   field hands back the value it is given, so a space trimmed off the end is
 *   a space that can never be followed by a letter — and «Сетевой город»
 *   logins may have one inside («Иванова Мария»). The ends are cleaned where
 *   the login is sent (`DiaryLogin.clean`, the server's own rule) and in the
 *   [onSubmit] argument.
 * @param loginEditable `false` on a re-authentication, where the login is known
 *   and a field that could be changed invites signing in as somebody else.
 * @param busy a sign-in is in flight: both fields and the button are held.
 * @param failed the last attempt was refused — the fields go red. What went
 *   wrong is the caller's pop-up to say (`DiaryProblemText`): a line under the
 *   button is under the keyboard on a phone.
 * @param onSubmit the button, or «Готово» on the keyboard, with the login and
 *   the password. Called only when both are filled and nothing is in flight.
 * @param onEdited any keystroke, so the caller can clear [failed].
 * @param loginLabel «Логин» by default; the onboarding says «Электронная почта»
 *   for Petersburg, whose logins are addresses.
 * @param loginKeyboard the keyboard for the login.
 * @param submitLabel «Войти» by default.
 */
@Composable
fun DiaryCredentialFields(
    login: String,
    onLoginChange: (String) -> Unit,
    onSubmit: (login: String, password: String) -> Unit,
    modifier: Modifier = Modifier,
    loginEditable: Boolean = true,
    busy: Boolean = false,
    failed: Boolean = false,
    onEdited: () -> Unit = {},
    loginLabel: String = correctedString(R.string.diary_login_label),
    loginKeyboard: KeyboardType = KeyboardType.Email,
    submitLabel: String = correctedString(R.string.diary_sign_in_action),
) {
    var password by remember { mutableStateOf("") }
    var revealed by remember { mutableStateOf(false) }

    val canSubmit = password.isNotBlank() && login.isNotBlank() && !busy
    fun submit() {
        if (canSubmit) onSubmit(login.trim(), password)
    }

    RoundedCardContainer(modifier = modifier) {
        GroupRow {
            OutlinedTextField(
                value = login,
                onValueChange = {
                    onLoginChange(it)
                    onEdited()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(LoginFieldTag),
                label = { Text(loginLabel) },
                singleLine = true,
                enabled = loginEditable && !busy,
                isError = failed,
                keyboardOptions = KeyboardOptions(
                    keyboardType = loginKeyboard,
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
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag(PasswordFieldTag),
                label = { Text(correctedString(R.string.diary_password_label)) },
                singleLine = true,
                enabled = !busy,
                isError = failed,
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
                            contentDescription = correctedString(
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

        GroupActionItem(
            label = submitLabel,
            icon = Icons.AutoMirrored.Rounded.Login,
            enabled = canSubmit,
            busy = busy,
            onClick = ::submit,
        )
    }
}

/** Test tags for the two fields, so a test can type into each without reading labels. */
const val LoginFieldTag = "diary-login"

/** @see LoginFieldTag */
const val PasswordFieldTag = "diary-password"
