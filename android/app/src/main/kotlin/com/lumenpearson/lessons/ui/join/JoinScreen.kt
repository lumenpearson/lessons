package com.lumenpearson.lessons.ui.join

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LessonsSans
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.emphasised
import com.lumenpearson.lessons.ui.common.ClassCodeLengths
import com.lumenpearson.lessons.ui.common.ServerUrlSheet
import com.lumenpearson.lessons.ui.onboarding.OnboardingActions

/**
 * Turn a class code into a session: the eight-character code a class hands out,
 * or the ten-character personal one from the bot's «📱 Подключить телефон» —
 * one field takes both (`ClassCodeLengths`).
 *
 * Shaped like the first-run flow in
 * [Essentials](https://github.com/sameerasw/essentials): a centred mark, a
 * rounded headline, the choices as grouped rows, and one full-width action
 * pinned to the bottom edge carrying its label on the left and an arrow on the
 * right. The content above it scrolls; the action does not move.
 *
 * A successful join writes a session. Raised from settings, the app shell
 * reacts to that alone; in the first run the shell holds the screen instead,
 * and [onJoined] is how the flow hears of it. The screen's own jobs are to say
 * what the app will do once it has a class, to take the code, and to let a
 * pupil point the app at the class's server — the APK carries none.
 *
 * @param onBack the first run's way back to the chooser. `null` where there is
 *   nothing behind the screen, so the square is absent rather than dead.
 * @param onJoined the class just joined, once per join this screen started;
 *   the first run decides there whether the class's diary is offered next.
 *   `AddClassSheet` reads the same one-shot through [rememberJoinSubmit].
 */
@Composable
fun JoinScreen(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    onJoined: ((classId: Long) -> Unit)? = null,
    viewModel: JoinViewModel = viewModel(factory = JoinViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showServerSheet by rememberSaveable { mutableStateOf(false) }
    val submit = rememberJoinSubmit(viewModel, state.joined) { classId -> onJoined?.invoke(classId) }

    // Back is held while the code is out. The join cannot be called back —
    // the server has the code — so leaving let it land under whatever came
    // next: the first run's chooser, which then offered «Найти свою школу» to
    // a family whose class, with its own diary, had just been joined behind
    // it. Taken rather than disabled, so the gesture does not fall through to
    // the flow's own handler; composed after that one, so it is asked first.
    BackHandler(enabled = onBack != null && state.isSubmitting) {}

    if (showServerSheet) {
        ServerUrlSheet(
            initialUrl = state.baseUrl,
            onDismiss = { showServerSheet = false },
            onConfirm = { url ->
                viewModel.onServerUrlChange(url)
                showServerSheet = false
            },
        )
    }

    // A plain surface rather than a `Scaffold`. The scaffold applied the safe
    // drawing insets to its content, and the action row applies the navigation
    // bar inset itself, so the button sat one gesture bar too high — visible as
    // a wide dead strip under it on a gesture-navigation phone.
    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // On the outer column, so the keyboard shrinks the scrolling
                // area and lifts the action, instead of growing the content
                // inside a viewport that never moved.
                .imePadding(),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = ScreenPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Spacer(Modifier.statusBarsPadding())
                Spacer(Modifier.height(32.dp))
                Icon(
                    imageVector = Icons.Rounded.School,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(72.dp),
                )

                Spacer(Modifier.height(18.dp))
                Text(
                    text = correctedString(R.string.join_title),
                    // The app's own face at a real weight — see the note in
                    // `OnboardingParts` about the rounded family this replaced.
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontFamily = LessonsSans,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = correctedString(R.string.join_subtitle),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )

                Spacer(Modifier.height(28.dp))
                ClassCodeField(
                    code = state.code,
                    error = state.error,
                    enabled = !state.isSubmitting,
                    onCodeChange = viewModel::onCodeChange,
                    onSubmit = submit,
                )

                Spacer(Modifier.height(GroupSpacing))
                WhatYouGet()

                Spacer(Modifier.height(GroupSpacing))
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = correctedString(R.string.join_server_section))
                    RoundedCardContainer {
                        GroupLinkItem(
                            title = correctedString(R.string.settings_server_url),
                            subtitle = state.baseUrl.takeIf { it.isNotBlank() }
                                ?: correctedString(R.string.join_server_unset),
                            icon = Icons.Rounded.Dns,
                            tone = accentTone(0),
                            onClick = { showServerSheet = true },
                        )
                    }
                }
                Spacer(Modifier.height(GroupSpacing))
            }

            // The same row the first-run steps end with, so the class-code step
            // does not change shape under the finger that has pressed its way
            // through the introduction's identical ones.
            OnboardingActions(
                label = correctedString(R.string.join_action),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                enabled = state.canSubmit,
                busy = state.isSubmitting,
                onClick = submit,
            )
        }
    }
}

/**
 * The caller's half of [JoinViewModel.submit]: the submit to wire to its
 * button, and [onJoined] once for the join that submit started.
 *
 * The view model is the activity's and its one-shot outlives every screen that
 * reads it, so a join is acted on only by the caller holding its ticket — kept
 * saveable, so a rotation between the press and the answer still finds it. A
 * join somebody else started is consumed and dropped: its caller was disposed
 * before it reported (the shell swaps on the session, which the join writes
 * before its first sync), and nobody else has any use for it.
 */
@Composable
internal fun rememberJoinSubmit(
    viewModel: JoinViewModel,
    joined: JoinedClass?,
    onJoined: (classId: Long) -> Unit,
): () -> Unit {
    var ticket by rememberSaveable { mutableStateOf<String?>(null) }
    val latest by rememberUpdatedState(onJoined)
    LaunchedEffect(joined) {
        val landed = joined ?: return@LaunchedEffect
        viewModel.consumeJoined(landed)
        if (landed.ticket == ticket) {
            ticket = null
            latest(landed.classId)
        }
    }
    return { viewModel.submit()?.let { ticket = it } }
}

/**
 * The three rows that say what a class code actually buys.
 *
 * A first-run screen that is one field and one button tells a pupil nothing
 * about what they are joining. Essentials spends a whole onboarding step on
 * this; one group of rows is this app's share of it.
 */
@Composable
private fun WhatYouGet(modifier: Modifier = Modifier) {
    Column(modifier = modifier.fillMaxWidth()) {
        SectionHeader(title = correctedString(R.string.join_what_you_get))
        RoundedCardContainer {
            GroupItem(
                title = correctedString(R.string.join_feature_timetable),
                subtitle = correctedString(R.string.join_feature_timetable_description),
                icon = Icons.Rounded.CalendarMonth,
                tone = accentTone(1),
            )
            GroupItem(
                title = correctedString(R.string.join_feature_homework),
                subtitle = correctedString(R.string.join_feature_homework_description),
                icon = Icons.AutoMirrored.Rounded.MenuBook,
                tone = accentTone(3),
            )
            GroupItem(
                title = correctedString(R.string.join_feature_widget),
                subtitle = correctedString(R.string.join_feature_widget_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(5),
            )
        }
    }
}

/**
 * The code input.
 *
 * Oversized, centred and letter-spaced on purpose: it is copied character by
 * character off a board, and a big field makes a mistyped character obvious
 * before the request is sent.
 */
@Composable
private fun ClassCodeField(
    code: String,
    error: JoinError?,
    enabled: Boolean,
    onCodeChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val errorText = error.asText()
    // Held only to know whether the field has focus: the label is bold while
    // it does, which is the one accent a text field gets.
    val interactionSource = remember { MutableInteractionSource() }
    val focused by interactionSource.collectIsFocusedAsState()

    OutlinedTextField(
        value = code,
        onValueChange = onCodeChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        interactionSource = interactionSource,
        singleLine = true,
        isError = error != null,
        shape = MaterialTheme.shapes.large,
        textStyle = MaterialTheme.typography.displaySmall.copy(
            textAlign = TextAlign.Center,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 12.sp,
        ),
        label = {
            Text(
                text = correctedString(R.string.join_code_label),
                style = LocalTextStyle.current.emphasised(focused),
            )
        },
        supportingText = {
            Text(
                text = errorText ?: correctedString(R.string.join_code_hint, ClassCodeLengths.first, ClassCodeLengths.last),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        },
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Characters,
            // fallback: on foundation < 1.7 this parameter is named `autoCorrect`.
            autoCorrectEnabled = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                keyboard?.hide()
                onSubmit()
            },
        ),
    )
}

/**
 * Localizes the inline error, or `null` when the field is fine.
 *
 * Internal rather than private because the class group in settings raises the
 * same join in a sheet: two copies of this mapping would agree today and drift
 * the first time either error case gains a third branch.
 */
@Composable
internal fun JoinError?.asText(): String? = when (this) {
    null -> null
    JoinError.InvalidCode -> correctedString(R.string.join_error_invalid_code, ClassCodeLengths.first, ClassCodeLengths.last)
    JoinError.UnknownCode -> correctedString(R.string.join_error_unknown_code)
    JoinError.NoServer -> correctedString(R.string.join_error_no_server)
    JoinError.InviteOnly -> correctedString(R.string.join_error_invite_only)
    is JoinError.TooManyAttempts -> minutes
        ?.let { pluralStringResource(R.plurals.join_error_too_many_wait, it, it) }
        ?: correctedString(R.string.join_error_too_many)
    is JoinError.Rejected ->
        detail?.let { correctedString(R.string.join_error_rejected, it) }
            ?: correctedString(R.string.join_error_generic)
}
