package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.School
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupLinkItem
import com.lumenpearson.lessons.core.designsystem.component.PillChip
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.common.ServerUrlSheet

/**
 * The chooser: by class code, as the app always worked, or on one's own — the
 * region, the school and the family's own diary.
 *
 * Nothing on it can fail, and nothing on it needs a server. The address is
 * shown, and settable, because both ways end up needing one; but it is asked
 * for where it is first needed rather than here (DESIGN decision 7), because a
 * family whose diary is a Госуслуги handoff needs none at all, and there is no
 * address the APK could suggest (#154).
 */
@Composable
internal fun WayInStep(
    viewModel: OnboardingViewModel,
    onBack: (() -> Unit)?,
) {
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val baseUrl by viewModel.baseUrl.collectAsStateWithLifecycle()
    val chosen = flow?.choices?.wayIn
    var editingServer by rememberSaveable { mutableStateOf(false) }

    if (editingServer) {
        ServerUrlSheet(
            initialUrl = baseUrl,
            onDismiss = { editingServer = false },
            onConfirm = { url ->
                viewModel.setServer(url)
                editingServer = false
            },
        )
    }

    StepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(
                    when (chosen) {
                        WayIn.CLASS_CODE -> R.string.onboarding_action_code
                        WayIn.FIND_SCHOOL -> R.string.onboarding_action_find
                        null -> R.string.onboarding_action_continue
                    },
                ),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onBack = onBack,
                enabled = chosen != null,
                onClick = viewModel::proceedFromWayIn,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_way_title),
                subtitle = correctedString(R.string.onboarding_way_subtitle),
            )
        }
        Spacer(Modifier.height(24.dp))

        OnboardingReveal(delayMillis = RevealStagger) {
            Column(verticalArrangement = Arrangement.spacedBy(GroupSpacing)) {
                RoundedCardContainer {
                    WayRow(
                        way = WayIn.CLASS_CODE,
                        chosen = chosen,
                        onChoose = viewModel::chooseWay,
                    )
                }
                RoundedCardContainer {
                    WayRow(
                        way = WayIn.FIND_SCHOOL,
                        chosen = chosen,
                        onChoose = viewModel::chooseWay,
                    )
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    SectionHeader(title = correctedString(R.string.join_server_section))
                    RoundedCardContainer {
                        GroupLinkItem(
                            title = correctedString(R.string.settings_server_url),
                            subtitle = baseUrl.takeIf { it.isNotBlank() }
                                ?: correctedString(R.string.onboarding_way_server_unset),
                            icon = Icons.Rounded.Dns,
                            tone = accentTone(0),
                            onClick = { editingServer = true },
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun WayRow(
    way: WayIn,
    chosen: WayIn?,
    onChoose: (WayIn) -> Unit,
) {
    val code = way == WayIn.CLASS_CODE
    ChoiceRow(
        title = correctedString(if (code) R.string.onboarding_way_code_title else R.string.onboarding_way_school_title),
        subtitle = correctedString(if (code) R.string.onboarding_way_code_text else R.string.onboarding_way_school_text),
        icon = if (code) Icons.Rounded.Key else Icons.Rounded.School,
        tone = accentTone(if (code) 1 else 3),
        selected = chosen == way,
        onClick = { onChoose(way) },
        below = {
            Spacer(Modifier.height(4.dp))
            PillChip(
                text = correctedString(if (code) R.string.onboarding_way_code_badge else R.string.onboarding_way_school_badge),
            )
        },
    )
}
