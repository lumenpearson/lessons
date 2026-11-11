package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.settings.EdgeFadeRow
import com.lumenpearson.lessons.ui.settings.SettingsUiState
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.settings.SupportsDynamicColor

/**
 * Step three: the preferences that decide how the app feels, before it has had
 * a chance to feel wrong.
 *
 * Not all of them — only the ones a user can judge without having seen a single
 * lesson. Everything else stays in settings, where it belongs; this page is
 * Essentials' "Preferences" step and it has the same job, which is to make the
 * settings screen findable by showing a piece of it.
 */
@Composable
internal fun PreferencesStep(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
) {
    StepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(R.string.onboarding_action_all_set),
                icon = Icons.Rounded.Check,
                onBack = onBack,
                onClick = onNext,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_preferences_title),
                subtitle = correctedString(R.string.onboarding_preferences_subtitle),
            )
        }
        Spacer(Modifier.height(24.dp))

        OnboardingReveal(delayMillis = RevealStagger) {
            AccentSection(title = correctedString(R.string.onboarding_group_app)) {
                GroupSwitchItem(
                    title = correctedString(R.string.settings_haptics),
                    subtitle = correctedString(R.string.settings_haptics_description),
                    icon = Icons.Rounded.Vibration,
                    tone = accentTone(2),
                    checked = state.settings.hapticsEnabled,
                    onCheckedChange = viewModel::setHapticsEnabled,
                )
                GroupSwitchItem(
                    title = correctedString(R.string.settings_dynamic_color),
                    subtitle = if (SupportsDynamicColor) {
                        correctedString(R.string.settings_dynamic_color_description)
                    } else {
                        correctedString(R.string.settings_dynamic_color_unavailable)
                    },
                    icon = Icons.Rounded.Palette,
                    tone = accentTone(0),
                    checked = state.settings.dynamicColor && SupportsDynamicColor,
                    enabled = SupportsDynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
                GroupSwitchItem(
                    title = correctedString(R.string.settings_pitch_black),
                    subtitle = correctedString(R.string.settings_pitch_black_description),
                    icon = Icons.Rounded.DarkMode,
                    tone = accentTone(5),
                    checked = state.settings.pitchBlack,
                    onCheckedChange = viewModel::setPitchBlack,
                )
                EdgeFadeRow(
                    checked = state.settings.edgeBlur,
                    tone = accentTone(1),
                    onCheckedChange = viewModel::setEdgeBlur,
                )
            }
        }

        Spacer(Modifier.height(GroupSpacing))

        AccentSection(title = correctedString(R.string.onboarding_group_content)) {
            GroupSwitchItem(
                title = correctedString(R.string.settings_show_teacher),
                subtitle = correctedString(R.string.settings_show_teacher_description),
                icon = Icons.Rounded.Person,
                tone = accentTone(3),
                checked = state.settings.showTeacher,
                onCheckedChange = viewModel::setShowTeacher,
            )
            GroupSwitchItem(
                title = correctedString(R.string.settings_widget_progress),
                subtitle = correctedString(R.string.settings_widget_progress_description),
                icon = Icons.Rounded.Widgets,
                tone = accentTone(4),
                checked = state.settings.widgetShowProgress,
                onCheckedChange = viewModel::setWidgetShowProgress,
            )
        }

        Spacer(Modifier.height(24.dp))
    }
}
