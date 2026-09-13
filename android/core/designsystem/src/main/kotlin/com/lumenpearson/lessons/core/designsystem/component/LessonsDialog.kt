package com.lumenpearson.lessons.core.designsystem.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.AccentTone
import com.lumenpearson.lessons.core.designsystem.theme.LessonsShapeTokens
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * The app's one pop-up: a thing that happened, said in the middle of the screen.
 *
 * Everything else that interrupts in this app arrives from the bottom edge —
 * the server address, the update notes, a day's lessons, the clock — and that
 * is deliberate: a sheet is a place you went, and it keeps its own room while
 * you decide. This is the other kind of interruption and it needed the other
 * shape. It reports an outcome. There is nothing to decide, nothing underneath
 * worth looking at, and the one thing it must not do is be missable.
 *
 * The failure it was written for is worth naming, because it is the argument
 * for the whole file. Signing in to the Petersburg diary put its answer — wrong
 * password, diary down, no network — in a line of red text inside the form,
 * below two fields and above a button. On a phone with the keyboard up that
 * line is off the bottom of the screen: you press «Войти», the spinner comes
 * and goes, and nothing changes anywhere you are looking. A correct password
 * looked exactly the same, because success said nothing at all.
 *
 * So: centred, modal, dismissed only on purpose. No "cancel" — an outcome is
 * not a question, and a second button would only ask which way to acknowledge
 * a fact.
 *
 * @param onDismiss called for the button, the scrim and the back gesture
 *   alike. The caller must clear whatever made the dialog appear, or it comes
 *   straight back on the next recomposition.
 * @param tone the icon's colours. [LessonsDialogTone] holds the two this app
 *   actually uses; the parameter stays open for a caller with its own accent.
 */
@Composable
fun LessonsDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Rounded.ErrorOutline,
    tone: AccentTone = LessonsDialogTone.failure(),
) {
    val view = rememberHapticView()
    // Once, when it appears, and keyed on the words rather than on nothing: a
    // second failure with a different reason is a second event, and a dialog
    // that swaps its text in silence reads as the same one still standing.
    LaunchedEffect(title, message) { LessonsHaptics.press(view) }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        shape = LessonsShapeTokens.Group,
        icon = { AccentIconTile(icon = icon, tone = tone, size = 56.dp) },
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmLabel) }
        },
    )
}

/** The two tones an outcome comes in. */
object LessonsDialogTone {

    /** Green-ish: it worked. Slot 3 is the app's "done" hue. */
    @Composable
    fun success(): AccentTone = accentTone(3)

    /**
     * The error colours, not an accent slot.
     *
     * A wrong password is the one moment in this app where the theme's error
     * role is the honest colour: it is not a category of thing, it is a thing
     * that went wrong, and every phone's palette already agrees what that
     * looks like.
     */
    @Composable
    fun failure(): AccentTone = errorTone()
}

/** Success, with the tick the tone is named for. */
@Composable
fun LessonsSuccessDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) = LessonsDialog(
    title = title,
    message = message,
    confirmLabel = confirmLabel,
    onDismiss = onDismiss,
    modifier = modifier,
    icon = Icons.Rounded.CheckCircle,
    tone = LessonsDialogTone.success(),
)

@Preview(name = "Диалог · ошибка", showBackground = true)
@Composable
private fun LessonsDialogFailurePreview() {
    LessonsTheme {
        LessonsDialog(
            title = "Не удалось войти",
            message = "Неверный логин или пароль",
            confirmLabel = "Понятно",
            onDismiss = {},
        )
    }
}

@Preview(name = "Диалог · успех", showBackground = true)
@Composable
private fun LessonsDialogSuccessPreview() {
    LessonsTheme {
        LessonsSuccessDialog(
            title = "Вход выполнен",
            message = "Вы вошли как parent@example.com",
            confirmLabel = "Понятно",
            onDismiss = {},
        )
    }
}
