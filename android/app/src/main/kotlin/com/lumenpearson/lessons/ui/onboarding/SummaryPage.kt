package com.lumenpearson.lessons.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.annotation.PluralsRes
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.DiarySessionIdleDays
import com.lumenpearson.lessons.core.designsystem.component.GroupItem
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.ui.diary.regionName
import com.lumenpearson.lessons.ui.diary.systemName
import com.lumenpearson.lessons.ui.settings.SettingsUiState
import com.lumenpearson.lessons.ui.settings.labelRes
import com.lumenpearson.lessons.ui.settings.rememberPermissionPrompts

/**
 * «Всё готово»: the pupil, the diary and what keeping it costs, the class if
 * there is one, and the settings the introduction set — then the app.
 *
 * Read-only. Everything on it is changed in settings, and saying so is the
 * page's job; a switch here would be a second copy of a settings row.
 *
 * The notifications row is left out without a class (K23): in the diary-only
 * home nothing sends a notification, and a row saying they are allowed would
 * promise reminders that never come. The page says instead what the
 * diary-only home does not have, and that the widget does not keep the
 * session alive (G8) — a family that only looks at the widget loses the
 * sign-in after [DiarySessionIdleDays] days, and should hear it now.
 */
@Composable
internal fun SummaryPage(
    settings: SettingsUiState,
    viewModel: OnboardingViewModel,
) {
    val summary by viewModel.summary.collectAsStateWithLifecycle()
    // The floor is here, so back has nowhere to go but out of the flow — and
    // out of the flow is the home, which is what the button does too.
    BackHandler { viewModel.finish() }

    StepScaffold(
        actions = {
            OnboardingActions(
                label = correctedString(R.string.onboarding_action_open),
                icon = Icons.Rounded.Check,
                onClick = viewModel::finish,
            )
        },
    ) {
        Spacer(Modifier.height(24.dp))
        OnboardingReveal {
            OnboardingTitle(
                title = correctedString(R.string.onboarding_summary_title),
                subtitle = correctedString(R.string.onboarding_summary_subtitle),
            )
        }
        Spacer(Modifier.height(24.dp))

        val current = summary
        OnboardingReveal(delayMillis = RevealStagger) {
            if (current != null) {
                current.studentName?.let { name ->
                    AccentSection(title = correctedString(R.string.onboarding_summary_student)) {
                        GroupItem(
                            title = name,
                            subtitle = listOfNotNull(current.studentClass, current.studentSchool)
                                .filter { it.isNotBlank() }
                                .joinToString(", ")
                                .ifBlank { null },
                            icon = Icons.Rounded.Person,
                            tone = accentTone(3),
                        )
                    }
                    Spacer(Modifier.height(GroupSpacing))
                }

                AccentSection(title = correctedString(R.string.onboarding_summary_diary)) {
                    GroupItem(
                        title = listOfNotNull(current.place?.systemName(), current.place?.regionName())
                            .joinToString(" · "),
                        subtitle = current.login?.let { correctedString(R.string.diary_signed_in_as, it) },
                        icon = Icons.AutoMirrored.Rounded.MenuBook,
                        tone = accentTone(2),
                    )
                    KeepAliveItem(inClass = current.inClass, petersburg = current.petersburg)
                }
                Spacer(Modifier.height(GroupSpacing))

                AccentSection(title = correctedString(R.string.onboarding_summary_class)) {
                    if (current.inClass) {
                        GroupItem(
                            title = current.className.orEmpty(),
                            subtitle = current.classSchool,
                            icon = Icons.Rounded.Groups,
                            tone = accentTone(1),
                        )
                    } else {
                        GroupItem(
                            title = correctedString(R.string.onboarding_summary_no_class_title),
                            subtitle = correctedString(R.string.onboarding_summary_no_class_text),
                            icon = Icons.Rounded.Groups,
                            tone = accentTone(1),
                        )
                    }
                }
                Spacer(Modifier.height(GroupSpacing))
            }

            AccentSection(title = correctedString(R.string.onboarding_summary_settings)) {
                GroupItem(
                    title = correctedString(R.string.settings_theme_mode),
                    subtitle = correctedString(settings.settings.themeMode.labelRes),
                    icon = Icons.Rounded.Contrast,
                    tone = accentTone(4),
                )
                GroupItem(
                    title = correctedString(R.string.settings_language),
                    subtitle = correctedString(settings.settings.language.labelRes),
                    icon = Icons.Rounded.Language,
                    tone = accentTone(1),
                )
                if (current?.inClass == true) {
                    val missing = rememberPermissionPrompts().missing
                    GroupItem(
                        title = correctedString(R.string.onboarding_summary_notifications),
                        subtitle = correctedString(
                            if (missing == 0) {
                                R.string.onboarding_summary_notifications_on
                            } else {
                                R.string.onboarding_summary_notifications_off
                            },
                        ),
                        icon = Icons.Rounded.Notifications,
                        tone = accentTone(5),
                    )
                }
                GroupItem(
                    title = correctedString(R.string.onboarding_ack_reports),
                    subtitle = correctedString(
                        if (settings.settings.debugMode) R.string.onboarding_reports_on else R.string.onboarding_reports_off,
                    ),
                    icon = Icons.Rounded.BugReport,
                    tone = accentTone(2),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * What keeping the diary's sign-in costs (G8), under a short title.
 *
 * The sentence is the subtitle, which wraps: a row's title is one line that
 * scrolls when it does not fit, and two sentences of warning crawling past
 * one line at a time is a warning nobody reads.
 */
@Composable
internal fun KeepAliveItem(inClass: Boolean, petersburg: Boolean) {
    val days = DiarySessionIdleDays.toInt()
    GroupItem(
        title = correctedString(R.string.onboarding_summary_keepalive_title),
        subtitle = listOfNotNull(
            pluralStringResource(keepAliveText(inClass), days, days),
            correctedString(R.string.onboarding_summary_petersburg_note).takeIf { petersburg },
        ).joinToString(" "),
        icon = Icons.Rounded.Timer,
        tone = accentTone(4),
    )
}

/**
 * Which sentence says what keeps the sign-in. Only a diary read moves the
 * server's idle clock, and the one read nobody presses for is the diary
 * home's refresh on start: a phone in a class opens on the class's timetable,
 * and there it is opening the diary — «Настройки → Дневник» — that counts.
 */
@PluralsRes
internal fun keepAliveText(inClass: Boolean): Int =
    if (inClass) R.plurals.onboarding_summary_keepalive_class else R.plurals.onboarding_summary_keepalive
