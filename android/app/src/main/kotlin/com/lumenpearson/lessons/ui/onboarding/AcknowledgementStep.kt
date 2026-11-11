package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.RowPadding
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.settings.SettingsUiState
import com.lumenpearson.lessons.ui.settings.SettingsViewModel

/**
 * Step two: what the app is, what it is not, and the one thing it can record.
 *
 * The text is deliberately the whole screen. It is the only place the app gets
 * to say that the timetable is a copy of somebody else's data and may be behind
 * it, which is the single most useful thing a pupil can know about it before
 * they start trusting it at eight in the morning.
 */
@Composable
internal fun AcknowledgementStep(
    state: SettingsUiState,
    viewModel: SettingsViewModel,
    onBack: (() -> Unit)?,
    onNext: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))

        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_ack_title),
                modifier = Modifier.padding(horizontal = ScreenPadding),
            )
        }

        Spacer(Modifier.height(16.dp))

        // The prose scrolls inside a fixed box rather than the page scrolling
        // under a fixed button: the crash-report choice below it is part of the
        // question being asked, and it has to stay visible while the answer is
        // being read.
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = ScreenPadding),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = ProseTint),
        ) {
            Column(
                modifier = Modifier
                    .verticalScroll(rememberScrollState())
                    // RowPadding (16 x 12), not CardPadding: CardPadding's
                    // uniform 20 dp puts the prose at 16 + 20 = 36 dp, the
                    // double inset decision 5 removes. Every other group's
                    // row text sits at 16 + 16 = 32, which is where the
                    // crash-report row directly below this card starts its
                    // own tile. RowPadding's horizontal half is what makes
                    // that true; its 12 dp vertical is unrelated and merely
                    // convenient.
                    .padding(RowPadding),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = correctedString(R.string.onboarding_ack_body),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = correctedString(R.string.onboarding_ack_warning),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = correctedString(R.string.onboarding_ack_footer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        RoundedCardContainer(modifier = Modifier.padding(horizontal = ScreenPadding)) {
            GroupSegmentedItem(
                title = correctedString(R.string.onboarding_ack_reports),
                subtitle = correctedString(R.string.onboarding_ack_reports_description),
                icon = Icons.Rounded.BugReport,
                tone = accentTone(2),
                items = CrashReportChoices,
                selectedItem = state.settings.debugMode,
                onItemSelected = viewModel::setDebugMode,
                labelProvider = { keep ->
                    correctedString(
                        if (keep) R.string.onboarding_reports_on else R.string.onboarding_reports_off,
                    )
                },
            )
        }

        OnboardingActions(
            label = correctedString(R.string.onboarding_action_understood),
            icon = Icons.Rounded.Check,
            onBack = onBack,
            onClick = onNext,
        )
    }
}

/** The two answers to "may the app keep a crash report", in picker order. */
private val CrashReportChoices = listOf(false, true)

/** How far the prose panel is lifted off the page behind it. */
private const val ProseTint = 0.5f
