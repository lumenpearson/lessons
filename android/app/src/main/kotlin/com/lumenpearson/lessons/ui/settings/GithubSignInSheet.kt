package com.lumenpearson.lessons.ui.settings

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.designsystem.component.AccentIconTile
import com.lumenpearson.lessons.core.designsystem.component.LessonsBottomSheet
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.theme.LessonsTheme
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The device flow, as two numbered steps.
 *
 * A port of the "Sign in with GitHub" sheet from
 * [Essentials](https://github.com/sameerasw/essentials). The shape of it is
 * dictated by the grant itself: GitHub hands the app a short code, the person
 * types that code on a GitHub page in their browser, and the app finds out by
 * polling. So the sheet has exactly two things to ask for — copy this, then
 * open that — and one thing to say while it waits.
 *
 * The sheet owns no state of the flow. It draws whatever [DeviceFlow] it is
 * given and asks the caller to open the page or start over; the polling, the
 * code and the account live in the repository, which is why closing this sheet
 * and reopening it shows the same code rather than requesting a second one.
 *
 * @param onOpenLogin the verification URL, to be opened in a browser.
 * @param onRetry after a failure: request a fresh code.
 */
@Composable
fun GithubSignInSheet(
    flow: DeviceFlow,
    onDismiss: () -> Unit,
    onOpenLogin: (url: String) -> Unit,
    onRetry: () -> Unit,
) {
    LessonsBottomSheet(onDismissRequest = onDismiss) {
        GithubSignInContent(
            flow = flow,
            onDone = onDismiss,
            onOpenLogin = onOpenLogin,
            onRetry = onRetry,
        )
    }
}

/**
 * The sheet's body, split out so that a `@Preview` can render it without the
 * modal host: `ModalBottomSheet` lives in its own window and draws nothing in
 * the design tool.
 */
@Composable
private fun ColumnScope.GithubSignInContent(
    flow: DeviceFlow,
    onDone: () -> Unit,
    onOpenLogin: (url: String) -> Unit,
    onRetry: () -> Unit,
) {
    Text(
        text = stringResource(R.string.github_sign_in_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding, vertical = 8.dp),
    )

    when (flow) {
        DeviceFlow.Idle, DeviceFlow.Requesting -> RequestingBlock()
        is DeviceFlow.AwaitingUser -> AwaitingBlock(flow = flow, onOpenLogin = onOpenLogin)
        is DeviceFlow.SignedIn -> SignedInBlock(account = flow.account, onDone = onDone)
        is DeviceFlow.Failed -> FailedBlock(reason = flow.reason, onRetry = onRetry)
    }
    Spacer(Modifier.height(8.dp))
}

/**
 * Before there is a code. `Idle` is drawn the same way as `Requesting`: the
 * sheet is only ever opened by a tap that starts the flow, so an idle flow on
 * screen is one the repository has not yet got round to.
 */
@Composable
private fun ColumnScope.RequestingBlock() {
    Spacer(Modifier.height(StatusGap))
    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
    Text(
        text = stringResource(R.string.github_requesting),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(StatusGap))
}

/**
 * The code, the two steps, and the wait.
 *
 * The code is the biggest thing on the sheet because it is the one thing the
 * person has to carry to another screen, possibly by reading it off this one.
 * It auto-sizes down from `displayLarge` rather than wrapping: a code broken
 * across two lines is a code somebody types with the hyphen in the wrong place.
 */
@Composable
private fun ColumnScope.AwaitingBlock(
    flow: DeviceFlow.AwaitingUser,
    onOpenLogin: (url: String) -> Unit,
) {
    val view = rememberHapticView()
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val clipLabel = stringResource(R.string.github_clipboard_label)

    // Android 13 and later show their own "copied" toast; earlier versions
    // show nothing, so the button says it itself for a moment. Keyed on the
    // code so a fresh code does not inherit the previous one's tick.
    var copied by remember(flow.userCode) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(CopiedFlashMillis)
            copied = false
        }
    }

    StepLabel(R.string.github_step_copy)
    Text(
        text = flow.userCode,
        style = MaterialTheme.typography.displayLarge.copy(
            fontWeight = FontWeight.Bold,
            letterSpacing = CodeTracking,
        ),
        color = MaterialTheme.colorScheme.primary,
        textAlign = TextAlign.Center,
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = CodeMinSize, maxFontSize = CodeMaxSize),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
    FilledTonalButton(
        onClick = {
            LessonsHaptics.press(view)
            scope.launch {
                val clip = ClipData.newPlainText(clipLabel, flow.userCode)
                clipboard.setClipEntry(clip.toClipEntry())
                copied = true
            }
        },
        shape = CircleShape,
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .height(PillHeight),
    ) {
        Icon(
            imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
            contentDescription = null,
            modifier = Modifier.size(ButtonDefaults.IconSize),
        )
        Spacer(Modifier.width(ButtonDefaults.IconSpacing))
        Text(text = stringResource(if (copied) R.string.github_copied else R.string.github_copy))
    }

    Spacer(Modifier.height(StepGap))
    StepLabel(R.string.github_step_paste)
    Button(
        onClick = {
            LessonsHaptics.press(view)
            onOpenLogin(flow.verificationUrl)
        },
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        Text(text = stringResource(R.string.github_open_login))
    }

    WaitingLine()
    ExpiryLine(expiresAtMillis = flow.expiresAtMillis)
}

/** "1. …" / "2. …": the step, in the body colour, centred over its control. */
@Composable
private fun StepLabel(@StringRes text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
}

/** The quiet line that says the app is still listening. */
@Composable
private fun ColumnScope.WaitingLine() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ButtonDefaults.IconSpacing),
        modifier = Modifier.align(Alignment.CenterHorizontally),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(WaitingIndicator),
            strokeWidth = WaitingStroke,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = stringResource(R.string.github_waiting),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * How long the code is still good for.
 *
 * A clock that ticks twice a minute rather than every second: the figure is in
 * whole minutes, rounded up, so anything finer would recompose a line nobody
 * is watching for a digit that does not change. The repository reports the
 * expiry itself as [DeviceFlow.Failed]; this line only warns before it does.
 */
@Composable
private fun ExpiryLine(expiresAtMillis: Long) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(expiresAtMillis) {
        while (isActive) {
            now = System.currentTimeMillis()
            delay(CountdownTickMillis)
        }
    }

    val remaining = expiresAtMillis - now
    // Rounded up: a code with forty seconds left is still good for "a minute".
    val minutesLeft = if (remaining > 0) {
        ((remaining + MinuteMillis - 1) / MinuteMillis).toInt()
    } else {
        0
    }

    Text(
        text = if (minutesLeft > 0) {
            stringResource(R.string.github_code_valid, minutesLeft)
        } else {
            stringResource(R.string.github_code_expiring)
        },
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.outline,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

/** The account is in. One line, one button, and the sheet is done. */
@Composable
private fun ColumnScope.SignedInBlock(
    account: GithubAccount,
    onDone: () -> Unit,
) {
    val view = rememberHapticView()

    Spacer(Modifier.height(StatusGap))
    AccentIconTile(
        icon = Icons.Rounded.CheckCircle,
        tone = accentTone(SignedInTone),
        size = StatusTile,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        text = stringResource(R.string.github_signed_in_as, account.login),
        style = MaterialTheme.typography.titleMedium,
        textAlign = TextAlign.Center,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
    Spacer(Modifier.height(StatusGap))
    Button(
        onClick = {
            LessonsHaptics.press(view)
            onDone()
        },
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        Text(text = stringResource(R.string.github_done))
    }
}

/**
 * It did not work, and as much of why as is useful.
 *
 * GitHub's two named reasons get their own sentence because each has a
 * different fix — a new code, or trying again on the GitHub page. Everything
 * else is a network of some kind and gets the network line; the raw message is
 * for the debug log, not for a sheet.
 */
@Composable
private fun ColumnScope.FailedBlock(
    reason: String?,
    onRetry: () -> Unit,
) {
    val view = rememberHapticView()

    Spacer(Modifier.height(StatusGap))
    AccentIconTile(
        icon = Icons.Rounded.ErrorOutline,
        tone = errorTone(),
        size = StatusTile,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Text(
        text = stringResource(R.string.github_failed_title),
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
    Text(
        text = stringResource(explanationFor(reason)),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding),
    )
    Spacer(Modifier.height(StatusGap))
    Button(
        onClick = {
            LessonsHaptics.press(view)
            onRetry()
        },
        shape = CircleShape,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = ScreenPadding)
            .height(PillHeight),
    ) {
        Text(text = stringResource(R.string.github_retry))
    }
}

/** @see FailedBlock */
@StringRes
private fun explanationFor(reason: String?): Int = when (reason) {
    ReasonExpired -> R.string.github_failed_expired
    ReasonDenied -> R.string.github_failed_denied
    else -> R.string.github_failed_generic
}

/** GitHub's own error codes for the device flow, as [DeviceFlow.Failed] carries them. */
private const val ReasonExpired = "expired_token"

private const val ReasonDenied = "access_denied"

/** Height of a full-width pill button on a sheet. */
private val PillHeight = 56.dp

/** Wide tracking on the code: it is read glyph by glyph, not as a word. */
private val CodeTracking = 4.sp

/** `displayLarge`'s own size; the ceiling the code shrinks down from. */
private val CodeMaxSize = 57.sp

private val CodeMinSize = 32.sp

private val WaitingIndicator = 16.dp

private val WaitingStroke = 2.dp

private val StatusTile = 64.dp

/** Room around a status block, which has far less in it than the two steps. */
private val StatusGap = 8.dp

private val StepGap = 8.dp

/** Any slot but the error hue; the tick is a row tile, not a verdict. */
private const val SignedInTone = 2

private const val CopiedFlashMillis = 2_000L

private const val CountdownTickMillis = 30_000L

private const val MinuteMillis = 60_000L

@Preview(name = "GitHub sign-in", showBackground = true)
@Composable
private fun GithubSignInSheetPreview() {
    LessonsTheme {
        Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GithubSignInContent(
                    flow = DeviceFlow.AwaitingUser(
                        userCode = "9CC3-0D34",
                        verificationUrl = "https://github.com/login/device",
                        expiresAtMillis = System.currentTimeMillis() + PreviewCodeLifetimeMillis,
                    ),
                    onDone = {},
                    onOpenLogin = {},
                    onRetry = {},
                )
            }
        }
    }
}

/** GitHub's codes live for fifteen minutes; the preview shows one just issued. */
private const val PreviewCodeLifetimeMillis = 15 * MinuteMillis
