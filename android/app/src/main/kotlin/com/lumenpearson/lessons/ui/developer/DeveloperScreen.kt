package com.lumenpearson.lessons.ui.developer

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.developer.DeveloperAccess
import com.lumenpearson.lessons.core.data.developer.DeveloperRole
import com.lumenpearson.lessons.core.data.developer.DeveloperTool
import com.lumenpearson.lessons.core.designsystem.component.GroupActionItem
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.ScreenHeader
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.text.correctedLine
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.ReportScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.errorTone
import com.lumenpearson.lessons.core.designsystem.theme.neutralTone
import com.lumenpearson.lessons.core.designsystem.theme.statusBarSpace
import com.lumenpearson.lessons.ui.settings.GithubSignInSheet
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * The developer section (#237): the GitHub gate, and behind it the tools,
 * the checks and the two records.
 *
 * The page is shaped like every other settings page — a header, then labelled
 * groups — so that it reads as part of the app rather than as a debug screen
 * bolted on. Everything below the access group exists only while the access
 * stands; the way out, «Скрыть», is there either way.
 */
@Composable
fun DeveloperScreen(
    modifier: Modifier = Modifier,
    viewModel: DeveloperViewModel = viewModel(factory = DeveloperViewModel.Factory),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val activity by viewModel.activity.collectAsStateWithLifecycle()
    val draft by viewModel.draft.collectAsStateWithLifecycle()
    val console by viewModel.consoleState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    var signingIn by rememberSaveable { mutableStateOf(false) }

    if (signingIn) {
        GithubSignInSheet(
            flow = state.signIn,
            onDismiss = {
                signingIn = false
                viewModel.cancelSignIn()
            },
            onOpenLogin = uriHandler::openUri,
            onRetry = viewModel::signIn,
        )
    }
    // The sheet has done its job once an account is there: the access group
    // below takes over, with GitHub's answer about it.
    LaunchedEffect(state.account) {
        if (state.account != null) signingIn = false
    }

    val copied = correctedString(R.string.developer_report_copied)
    val answerCopied = correctedString(R.string.developer_console_copied)
    val listState = rememberLazyListState()
    ReportScrollOffset(listState)
    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .appScrollMotionBlur(listState),
        contentPadding = PaddingValues(
            start = ScreenPadding,
            end = ScreenPadding,
            top = statusBarSpace() + TopGap,
            bottom = LocalBottomBarSpace.current,
        ),
        verticalArrangement = Arrangement.spacedBy(GroupSpacing),
    ) {
        item(key = "header") {
            ScreenHeader(
                title = correctedString(R.string.developer_section),
                subtitle = correctedString(R.string.developer_section_summary),
            )
        }
        if (!state.mode.revealed) {
            item(key = "hidden") {
                DeveloperGroup(title = correctedString(R.string.developer_access_group)) {
                    // A title and a sentence under it, for the reason the
                    // signed-out row has them (#256).
                    GroupItem(
                        title = correctedString(R.string.developer_hidden_title),
                        subtitle = correctedString(R.string.developer_hidden),
                        tone = neutralTone(),
                    )
                }
            }
        } else {
            item(key = "access") {
                AccessGroup(
                    access = state.mode.access,
                    canSignIn = state.githubConfigured,
                    onSignIn = {
                        signingIn = true
                        viewModel.signIn()
                    },
                    onVerify = viewModel::verify,
                )
            }
            if (state.mode.granted) {
                item(key = "tools") { ToolsGroup(chosen = state.mode.chosen, onToggle = viewModel::setTool) }
                item(key = "console") {
                    ConsoleGroup(
                        draft = draft,
                        origins = viewModel.consoleOrigins,
                        state = console,
                        onEdit = viewModel::editDraft,
                        onSend = viewModel::sendConsole,
                        onCopy = { text ->
                            copyToClipboard(context, text, label = "lessons console answer")
                            Toast.makeText(context, answerCopied, Toast.LENGTH_SHORT).show()
                        },
                    )
                }
                item(key = "checks") { ChecksGroup(checks = state.checks, onRun = viewModel::runChecks) }
                item(key = "network") {
                    NetworkGroup(
                        entries = network,
                        recording = DeveloperTool.NETWORK_LOG in state.mode.tools,
                        onClear = viewModel::clearNetwork,
                    )
                }
                item(key = "activity") {
                    ActivityGroup(
                        entries = activity,
                        recording = DeveloperTool.ACTIVITY_LOG in state.mode.tools,
                        onClear = viewModel::clearActivity,
                    )
                }
                item(key = "report") {
                    ReportGroup(
                        onCopy = {
                            copyToClipboard(context, viewModel.report())
                            Toast.makeText(context, copied, Toast.LENGTH_SHORT).show()
                        },
                        onShare = { share(context, viewModel.report()) },
                    )
                }
            }
            item(key = "hide") {
                DeveloperGroup(title = correctedString(R.string.developer_hide)) {
                    GroupItem(
                        title = correctedString(R.string.developer_hide),
                        subtitle = correctedString(R.string.developer_hide_summary),
                        icon = Icons.Rounded.VisibilityOff,
                        tone = errorTone(),
                        onClick = viewModel::hide,
                    )
                }
            }
        }
    }
}

/**
 * A labelled group, with the header's own action where the group has one —
 * «Очистить» on a record. The settings' `SettingsGroup` without the action.
 */
@Composable
internal fun DeveloperGroup(
    title: String,
    subtitle: String? = null,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title, subtitle = subtitle, actionLabel = actionLabel, onActionClick = onAction)
        RoundedCardContainer(content = content)
    }
}

/** Who is signed in to GitHub here, and what GitHub said about them. */
@Composable
private fun AccessGroup(
    access: DeveloperAccess,
    canSignIn: Boolean,
    onSignIn: () -> Unit,
    onVerify: () -> Unit,
) {
    DeveloperGroup(title = correctedString(R.string.developer_access_group)) {
        when (access) {
            DeveloperAccess.SignedOut -> {
                // The rule is a sentence and a row's title is one line that
                // scrolls: drawn there, it was read a few words at a time and
                // cut at both ends (#256). Below a title of its own it wraps.
                GroupItem(
                    title = correctedString(R.string.developer_access_signed_out_title),
                    subtitle = correctedString(R.string.developer_access_signed_out),
                    icon = Icons.Rounded.AccountCircle,
                    tone = neutralTone(),
                )
                if (canSignIn) {
                    GroupActionItem(
                        label = correctedString(R.string.developer_sign_in),
                        icon = Icons.AutoMirrored.Rounded.Login,
                        onClick = onSignIn,
                    )
                } else {
                    GroupItem(title = correctedString(R.string.developer_github_unavailable), tone = neutralTone())
                }
            }
            is DeveloperAccess.Checking -> GroupItem(
                title = correctedLine(R.string.developer_access_checking, access.login),
                icon = Icons.Rounded.AccountCircle,
                tone = neutralTone(),
            )
            is DeveloperAccess.Granted -> GroupItem(
                title = correctedLine(R.string.developer_access_granted, access.login, roleLabel(access.role)),
                subtitle = correctedString(R.string.developer_access_checked_at, clockTime(access.checkedAtMillis)),
                icon = Icons.Rounded.AccountCircle,
                tone = accentTone(1),
            )
            is DeveloperAccess.Denied -> GroupItem(
                title = correctedLine(R.string.developer_access_denied, access.login),
                icon = Icons.Rounded.AccountCircle,
                tone = errorTone(),
            )
            is DeveloperAccess.Unknown -> GroupItem(
                title = access.reason?.let { correctedString(R.string.developer_access_failed, access.login, it) }
                    ?: correctedString(R.string.developer_access_unknown, access.login),
                icon = Icons.Rounded.AccountCircle,
                tone = neutralTone(),
            )
        }
        if (access !is DeveloperAccess.SignedOut) {
            GroupActionItem(
                label = correctedString(R.string.developer_verify),
                icon = Icons.Rounded.Refresh,
                enabled = access !is DeveloperAccess.Checking,
                busy = access is DeveloperAccess.Checking,
                onClick = onVerify,
            )
        }
    }
}

@Composable
private fun roleLabel(role: DeveloperRole): String = correctedString(
    when (role) {
        DeveloperRole.ADMIN -> R.string.developer_role_admin
        DeveloperRole.MAINTAIN -> R.string.developer_role_maintain
        DeveloperRole.WRITE -> R.string.developer_role_write
    },
)

/** `14:32:07`, in the phone's zone; see [deviceZone]. */
internal fun clockTime(millis: Long): String =
    ClockTime.format(Instant.ofEpochMilli(millis).atZone(deviceZone()))

private val ClockTime: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

private val TopGap = 8.dp

private fun copyToClipboard(context: Context, text: String, label: String = "lessons developer report") {
    context.getSystemService(ClipboardManager::class.java)
        ?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** To the share sheet; a phone with nothing to share to keeps the copy button. */
private fun share(context: Context, text: String) {
    runCatching {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(intent, null))
    }
}
