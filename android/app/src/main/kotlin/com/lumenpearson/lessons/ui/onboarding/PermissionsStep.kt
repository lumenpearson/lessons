package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.ui.settings.PermissionCard
import com.lumenpearson.lessons.ui.settings.rememberPermissionPrompts

/**
 * Step four: the three things the app needs from the system, asked one at a
 * time.
 *
 * It comes before the chooser on both ways in, although a family that takes
 * the diary way gets no alerts from it (K23): the chooser comes after the
 * introduction, and an ask moved behind one branch would be an ask on one path.
 *
 * Each permission gets a card of its own rather than a shared group, because
 * each is a separate question with a separate answer, and a group reads as one
 * block to be dealt with in one go — which is exactly the "allow everything"
 * habit this step should not be training. The cards themselves are the settings
 * page's, from [PermissionCard]: the same button, the same wording, and the
 * same handling of a dialog the platform has stopped offering.
 *
 * Nothing here blocks [OnboardingStep.WAY_IN]. The action moves on whatever the
 * answers were — «Потом» while something is missing, «Дальше» once nothing is —
 * and it is never disabled, because a pupil who refuses all three still gets a
 * timetable, a week view and a widget; what they lose is being told about them.
 *
 * The step is not skipped when everything is already granted either. Skipping
 * forward would make the back gesture from [OnboardingStep.WAY_IN] land here and
 * be thrown straight forward again, which is a flow with no way back rather
 * than a shortcut.
 */
@Composable
internal fun PermissionsStep(
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
) {
    val prompts = rememberPermissionPrompts()
    val settled = prompts.missing == 0

    StepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(
                    if (settled) {
                        R.string.onboarding_action_continue
                    } else {
                        R.string.onboarding_action_later
                    },
                ),
                icon = if (settled) Icons.Rounded.Check else Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                onClick = onNext,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_permissions_title),
                subtitle = correctedString(R.string.onboarding_permissions_subtitle),
            )
        }
        Spacer(Modifier.height(24.dp))

        OnboardingReveal(delayMillis = RevealStagger) {
            prompts.states.forEachIndexed { index, state ->
                if (index > 0) Spacer(Modifier.height(GroupSpacing))
                RoundedCardContainer {
                    PermissionCard(state = state, onAct = { prompts.act(state) })
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
