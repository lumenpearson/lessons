package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Language
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealAnchor
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.ui.legal.LegalAcceptanceLine
import com.lumenpearson.lessons.ui.settings.SettingsUiState
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.settings.labelRes

private const val WelcomeAccentTone = 4

/**
 * Step one: the mark, the name, and the one preference worth setting before the
 * user has seen a single screen.
 *
 * Essentials puts its language picker here, and so does this — now that there
 * is a second language to put in it. It shares the step with the theme, the
 * other setting whose effect is visible on the very next frame and the one a
 * user opening an app at night wants before they are three screens deep in it.
 *
 * The language belongs on *this* step and not on [OnboardingStep.PREFERENCES]
 * two screens later, because everything between the two is prose: the
 * acknowledgement step is four paragraphs saying what the timetable is and what
 * it is not, and it is the single most useful thing a new user reads. Somebody
 * who does not read Russian has to be able to change the language before that,
 * not after it. It is the same setter the settings page uses and the same
 * strings, so what is set here is already stored by the time the flow ends and
 * the row reads identically in both places.
 */
@Composable
internal fun WelcomeStep(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onNext: () -> Unit,
) {
    StepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(R.string.onboarding_action_proceed),
                icon = Icons.AutoMirrored.Rounded.ArrowForward,
                onClick = onNext,
                // Always drawn, whatever the build says about where its
                // documents are published: with no address the line opens the
                // copies bundled in the APK, so there is never a first screen
                // that asks to continue without saying on what terms.
                footer = { LegalAcceptanceLine() },
            )
        },
    ) {
        // Fixed spacing rather than weighted spacers around the mark. The body
        // scrolls, so its height constraint is unbounded, and a weight inside an
        // unbounded column is handed nothing to distribute — the reference does
        // exactly this and the spacers there are decorative.
        Spacer(Modifier.height(48.dp))

        SpinnableAppMark()

        Spacer(Modifier.height(28.dp))
        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(
                    R.string.onboarding_welcome_title,
                    correctedString(R.string.app_name),
                ),
                subtitle = correctedString(R.string.onboarding_welcome_subtitle),
            )
        }

        Spacer(Modifier.height(40.dp))

        OnboardingReveal(delayMillis = RevealStagger) {
            RoundedCardContainer {
                ThemeRevealAnchor { reveal ->
                    GroupSegmentedItem(
                        title = correctedString(R.string.settings_theme_mode),
                        icon = Icons.Rounded.Contrast,
                        tone = accentTone(WelcomeAccentTone),
                        items = ThemeMode.entries,
                        selectedItem = state.settings.themeMode,
                        onItemSelected = { mode -> reveal { viewModel.setThemeMode(mode) } },
                        labelProvider = { mode -> correctedString(mode.labelRes) },
                    )
                }
                // In the same card as the theme rather than a card of its own: the
                // two are one question — "how should this look and read to me" —
                // asked before anything else, and a second card would give a screen
                // whose whole job is a mark and a greeting two separate blocks to
                // read. No subtitle either, unlike the settings page: «Системный» is
                // one of the three labels right beside it, so the sentence that
                // explains it there would only be repeating a word that is visible.
                GroupSegmentedItem(
                    title = correctedString(R.string.settings_language),
                    icon = Icons.Rounded.Language,
                    tone = accentTone(1),
                    items = AppLanguage.entries,
                    selectedItem = state.settings.language,
                    onItemSelected = viewModel::setLanguage,
                    labelProvider = { language -> correctedString(language.labelRes) },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}
