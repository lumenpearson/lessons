package com.lumenpearson.lessons.ui.settings

import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.errorTone

/**
 * The per-permission asking machinery, shared by the settings page and the
 * first-run step.
 *
 * One copy rather than two on purpose. The two callers lay the cards out
 * differently — a single group on a settings page, one card per permission on a
 * setup screen — but the question each card asks, the button it shows and what
 * that button does are the same question, and a second copy of that is a second
 * place for the "denied twice" case to be forgotten.
 */

/**
 * Whether every permission is in place, recomputed whenever the user might have
 * changed one.
 *
 * There is no listener for this. A permission is revoked in system settings, in
 * another app's process, and the only moment we are told anything is that our
 * own activity comes back to the front — so that is when it is asked again.
 * Essentials does the same from `Activity.onResume`; here it is a lifecycle
 * observer instead, because these are screens inside one activity rather than
 * activities of their own, and an `onResume` on the activity would not fire when
 * the user merely navigates between them.
 *
 * A refresh token rather than a stored list: the answers come from the system
 * and are cheap to ask for, and storing them is how the reference ends up with
 * cards that say "выдать" for something already granted.
 */
@Composable
internal fun rememberPermissionRefresh(): Int {
    var token by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current

    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) token++
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }

    return token
}

/** One permission as the system describes it this instant. */
@Immutable
internal data class PermissionState(
    val permission: AppPermission,
    val isGranted: Boolean,
    val prompt: PermissionPrompt,
    val deniedPermanently: Boolean,
    /** `null` when there is no dialog to show; see [AppPermission.runtimePermission]. */
    val runtimePermission: String?,
)

/**
 * Every relevant permission, plus the two things a screen can do about them.
 *
 * [act] takes the state it was handed rather than looking anything up again, so
 * that the button can never do something other than what its own label says —
 * the label and the action are read off the same snapshot.
 */
@Stable
internal class PermissionPrompts(
    val states: List<PermissionState>,
    val refresh: () -> Unit,
    val act: (PermissionState) -> Unit,
) {
    /** Zero is what makes a banner or a nagging label disappear on its own. */
    val missing: Int get() = states.count { !it.isGranted }
}

/**
 * Reads every relevant permission from the system and keeps doing so.
 *
 * Three things can change an answer and none of them is a callback we own: the
 * user comes back from a system page (the lifecycle token), the runtime dialog
 * returns without the activity ever having paused (the launcher result), and
 * the refresh button exists for the OEM settings pages that somehow manage not
 * to pause us at all.
 */
@Composable
internal fun rememberPermissionPrompts(): PermissionPrompts {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val resumes = rememberPermissionRefresh()

    // Bumped by the runtime dialog and by the refresh button, neither of which
    // moves the activity, so the lifecycle observer never fires for them.
    var answers by remember { mutableIntStateOf(0) }

    // Which permissions this process has actually shown a dialog for. The
    // platform does not report it and `shouldShowRequestPermissionRationale`
    // cannot stand in: it answers false both before the first request and after
    // the last one it will ever honour. Deliberately not saved across a
    // configuration change — the worst that costs is one tap that opens nothing,
    // and the result of that tap puts the entry back.
    val requested = remember { mutableStateMapOf<AppPermission, Unit>() }

    // Which permission the dialog currently on screen belongs to. Recorded on
    // the result rather than at launch, so the card behind the open dialog does
    // not change its label while the user is reading it.
    var awaiting by remember { mutableStateOf<AppPermission?>(null) }

    // Registered unconditionally, at the top of the composition, whatever this
    // device's API level: a launcher registered inside an `if` is disposed and
    // re-registered as the condition moves and throws IllegalStateException when
    // its result arrives. Only its *use* is gated, further down.
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) {
        awaiting?.let { requested[it] = Unit }
        awaiting = null
        answers++
    }

    // `requested` is read here without being a key. It is only ever written in
    // the callback above, one line before `answers` is bumped, so every write to
    // it arrives with a key change of its own.
    val states = remember(resumes, answers, context, activity) {
        AppPermission.relevant().map { permission ->
            val runtime = permission.runtimePermission(context)
            val granted = permission.isGranted(context)
            val rationaleShown = runtime != null && activity != null &&
                ActivityCompat.shouldShowRequestPermissionRationale(activity, runtime)

            PermissionState(
                permission = permission,
                isGranted = granted,
                prompt = PermissionPrompt.of(
                    relevant = true,
                    granted = granted,
                    runtime = runtime != null,
                    requested = requested.containsKey(permission),
                    rationaleShown = rationaleShown,
                ),
                deniedPermanently = PermissionPrompt.deniedPermanently(
                    runtime = runtime != null,
                    granted = granted,
                    requested = requested.containsKey(permission),
                    rationaleShown = rationaleShown,
                ),
                runtimePermission = runtime,
            )
        }
    }

    return remember(states) {
        PermissionPrompts(
            states = states,
            refresh = { answers++ },
            act = { state ->
                val runtime = state.runtimePermission
                if (state.prompt == PermissionPrompt.RequestRuntime && runtime != null) {
                    awaiting = state.permission
                    launcher.launch(runtime)
                } else {
                    state.permission.open(context)
                }
            },
        )
    }
}

/**
 * One permission: what it is for, and the single button that does something
 * about it.
 *
 * The button stays when it is granted rather than turning into a tick, because
 * the page's job is not only to fix things — a permission that is on is also
 * the one you come here to turn off. It changes emphasis instead: filled while
 * something is wrong, outlined once it is not.
 *
 * The subtitle is normally what breaks without the permission. It is replaced
 * only in the one case where that is no longer the useful sentence: the dialog
 * has been refused for good, the button has just changed from «Выдать» to
 * «Открыть настройки», and without a word about why it looks like the same tap
 * suddenly doing something else.
 *
 * The caller supplies the container. That is the whole of the difference
 * between the settings page, which puts all three in one group, and the
 * first-run step, which gives each its own card.
 */
@Composable
internal fun PermissionCard(
    state: PermissionState,
    onAct: () -> Unit,
) {
    val label = when (state.prompt) {
        PermissionPrompt.RequestRuntime -> R.string.permissions_action_grant
        PermissionPrompt.OpenSettings -> R.string.permissions_action_open_settings
        PermissionPrompt.Done -> R.string.permissions_action_open
    }

    GroupItem(
        title = correctedString(state.permission.titleRes),
        subtitle = if (state.deniedPermanently) {
            correctedString(R.string.permission_denied_forever)
        } else {
            correctedString(state.permission.descriptionRes)
        },
        icon = state.permission.icon,
        tone = if (state.isGranted) accentTone(1) else errorTone(),
        trailing = {
            val text: @Composable () -> Unit = {
                Text(
                    text = correctedString(label),
                    style = MaterialTheme.typography.labelMedium,
                    textAlign = TextAlign.Center,
                )
            }
            // Capped rather than left to its intrinsic width: «Открыть
            // настройки» measures wider than the title beside it and would push
            // the permission's own name into an ellipsis. Bounded, it wraps onto
            // two lines and the row keeps reading as a row about a permission.
            val shape = Modifier.widthIn(max = ActionWidth)

            if (state.isGranted) {
                OutlinedButton(onClick = onAct, modifier = shape, contentPadding = ButtonPadding) {
                    text()
                }
            } else {
                Button(onClick = onAct, modifier = shape, contentPadding = ButtonPadding) {
                    text()
                }
            }
        },
    )
}

/** @see PermissionCard */
private val ActionWidth = 116.dp

internal val ButtonPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
