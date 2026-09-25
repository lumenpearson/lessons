package com.lumenpearson.lessons.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contrast
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.Widgets
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.designsystem.component.GroupSegmentedItem
import com.lumenpearson.lessons.core.designsystem.component.GroupSwitchItem
import com.lumenpearson.lessons.core.designsystem.component.RoundedCardContainer
import com.lumenpearson.lessons.core.designsystem.component.SectionHeader
import com.lumenpearson.lessons.core.designsystem.text.Text
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.GroupSpacing
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.ScreenPadding
import com.lumenpearson.lessons.core.designsystem.theme.ThemeRevealAnchor
import com.lumenpearson.lessons.core.designsystem.theme.accentTone
import com.lumenpearson.lessons.core.designsystem.theme.appSlideMotionBlur
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.legal.LegalAcceptanceLine
import com.lumenpearson.lessons.ui.settings.EdgeFadeRow
import com.lumenpearson.lessons.ui.settings.PermissionCard
import com.lumenpearson.lessons.ui.settings.SettingsUiState
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.settings.SupportsDynamicColor
import com.lumenpearson.lessons.ui.settings.labelRes
import com.lumenpearson.lessons.ui.settings.rememberPermissionPrompts

/** How long one step takes to slide the next one in, before the reader's motion setting. */
private const val StepTransitionMillis = 400

/**
 * How far behind its title a step's first block arrives.
 *
 * Short on purpose. The point is that the eye is given the heading before
 * the controls under it, not that the page is assembled in front of the
 * user: anything long enough to notice as a sequence reads as the screen
 * being slow.
 */
internal const val RevealStagger = 90

/** The top of the path, which is what the transition animates between. */
private data class PathTop(val step: OnboardingStep, val depth: Int)

/**
 * The first run: the introduction, the chooser, and whichever way in is chosen.
 *
 * There is no navigation graph behind it. The path is [OnboardingViewModel]'s
 * saved state ([OnboardingState]); every preference on the introduction is
 * written straight through [SettingsViewModel] — the same instance the settings
 * screen uses, so anything set here is already stored by the time the flow
 * ends — and the class-code step is the real join screen rather than a copy.
 *
 * The flow, not a credential, decides when it ends. The join and the diary's
 * registration each write one mid-way, and the shell's hold (set on entering
 * those steps) keeps this screen up until [OnboardingViewModel.finish].
 *
 * @param introduced the introduction was seen before — the flow opens at the
 *   chooser. Latched by the caller: the flow records it on reaching the
 *   chooser, and re-reading it live would restart the flow under the user.
 */
@Composable
fun OnboardingScreen(
    introduced: Boolean,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = viewModel(factory = OnboardingViewModel.Factory),
    settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory),
) {
    LaunchedEffect(Unit) { viewModel.start(introduced) }

    val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val flow by viewModel.flow.collectAsStateWithLifecycle()
    val plan by viewModel.plan.collectAsStateWithLifecycle()
    val motion = LocalMotion.current

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        // One frame after the splash, while a saved flow or the stored hold is
        // read: a surface, rather than a guessed first step and a jump.
        val state = flow ?: return@Surface
        val progress = remember(state, plan) { OnboardingFlow.progress(state, plan) }

        // The system gesture walks the same path as the back square, so the
        // flow has one way back rather than two that disagree. At the floor it
        // is left alone, which lets it do what it means there: leave the app.
        //
        // `back()` is bounded by the floor rather than trusting `enabled` to
        // have caught up. That flag reaches the callback in a SideEffect, after
        // the composition is applied, while the write lands in the snapshot at
        // once — so two back events drained in one input pass both see it
        // true. Reachable by tapping back twice during the slide, which is
        // exactly where this screen is slowest.
        BackHandler(enabled = state.canGoBack) { viewModel.back() }
        val onBack: (() -> Unit)? = if (state.canGoBack) viewModel::back else null

        Column(modifier = Modifier.fillMaxSize()) {
            // Above the transition rather than inside a step, because that is
            // what lets one shape become the next one instead of a dozen shapes
            // fading past each other. See OnboardingHero.
            OnboardingHero(state = state, progress = progress)

            AnimatedContent(
                targetState = PathTop(state.current, state.depth),
                transitionSpec = {
                    val millis = motion.durationMillis(StepTransitionMillis)
                    // Direction carries the meaning: forward pushes the old
                    // screen off to the left, back pulls it in from there.
                    // Depth, not ordinal — the flow branches, and the provider
                    // step's way to the class code is plainly forward.
                    if (OnboardingFlow.isForward(initialState.depth, targetState.depth)) {
                        (slideInHorizontally(tween(millis)) { it } + fadeIn(tween(millis)))
                            .togetherWith(slideOutHorizontally(tween(millis)) { -it } + fadeOut(tween(millis)))
                    } else {
                        (slideInHorizontally(tween(millis)) { -it } + fadeIn(tween(millis)))
                            .togetherWith(slideOutHorizontally(tween(millis)) { it } + fadeOut(tween(millis)))
                    }
                },
                label = "onboarding_step",
            ) { top ->
                // Each step travels a full screen width, which is the biggest
                // single movement in the app; blurring it is what the
                // scroll-blur setting means here. Driven by the transition's
                // own fraction, so the shader and the slide can never disagree
                // about where the page is.
                val slide = transition.animateFloat(
                    transitionSpec = { tween(motion.durationMillis(StepTransitionMillis)) },
                    label = "onboarding_slide",
                ) { phase -> if (phase == EnterExitState.Visible) 1f else 0f }
                val travel = LocalConfiguration.current.screenWidthDp.dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .appSlideMotionBlur(
                            moving = { transition.isRunning },
                            fraction = { slide.value },
                            travel = travel,
                        ),
                ) {
                    when (top.step) {
                        OnboardingStep.WELCOME -> WelcomeStep(
                            state = settings,
                            viewModel = settingsViewModel,
                            onNext = viewModel::next,
                        )

                        OnboardingStep.ACKNOWLEDGEMENT -> AcknowledgementStep(
                            state = settings,
                            viewModel = settingsViewModel,
                            onBack = onBack,
                            onNext = viewModel::next,
                        )

                        OnboardingStep.PREFERENCES -> PreferencesStep(
                            state = settings,
                            viewModel = settingsViewModel,
                            onBack = onBack,
                            onNext = viewModel::next,
                        )

                        OnboardingStep.PERMISSIONS -> PermissionsStep(
                            onBack = onBack,
                            onNext = viewModel::next,
                        )

                        OnboardingStep.WAY_IN -> WayInStep(viewModel = viewModel, onBack = onBack)

                        OnboardingStep.CLASS_CODE -> JoinScreen(
                            onBack = onBack,
                            onJoined = { viewModel.onJoined() },
                        )

                        OnboardingStep.REGION -> RegionStep(viewModel = viewModel, onBack = onBack)

                        OnboardingStep.SCHOOL -> SchoolStep(viewModel = viewModel, onBack = onBack)

                        OnboardingStep.PROVIDER -> ProviderStep(viewModel = viewModel, onBack = onBack)

                        OnboardingStep.SIGN_IN -> SignInPage(viewModel = viewModel, onBack = onBack)

                        OnboardingStep.IMPORT -> ImportPage(viewModel = viewModel)

                        OnboardingStep.SUMMARY -> SummaryPage(settings = settings, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

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
private fun WelcomeStep(
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
                        tone = accentTone(4),
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

/**
 * Step two: what the app is, what it is not, and the one thing it can record.
 *
 * The text is deliberately the whole screen. It is the only place the app gets
 * to say that the timetable is a copy of somebody else's data and may be behind
 * it, which is the single most useful thing a pupil can know about it before
 * they start trusting it at eight in the morning.
 */
@Composable
private fun AcknowledgementStep(
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
                    .padding(horizontal = 20.dp, vertical = 16.dp),
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
private fun PreferencesStep(
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
private fun PermissionsStep(
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

/**
 * Label of a language in the first-run picker.
 *
 * A copy of the settings page's mapping rather than a shared one, because the
 * settings page's is `private` to its own file and this file may not change it —
 * but it is a copy of three `when` branches over the same `R.string` names, not
 * of the strings themselves. `settings_language_*` already exists in `values/`
 * and `values-en/`; a first-run set beside it would be four more names for the
 * translation test to keep in step and four more chances for the same word to
 * end up spelled two ways.
 */
internal val AppLanguage.labelRes: Int
    get() = when (this) {
        AppLanguage.SYSTEM -> R.string.settings_language_system
        AppLanguage.RUSSIAN -> R.string.settings_language_russian
        AppLanguage.ENGLISH -> R.string.settings_language_english
    }

/** The two answers to "may the app keep a crash report", in picker order. */
private val CrashReportChoices = listOf(false, true)

/** How far the prose panel is lifted off the page behind it. */
private const val ProseTint = 0.5f

/**
 * A group under a coloured label, which is what the Essentials setup pages use
 * and its settings pages do not. See the note on `SectionHeader.titleColor`.
 */
@Composable
internal fun AccentSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        SectionHeader(title = title, titleColor = MaterialTheme.colorScheme.primary)
        RoundedCardContainer(content = content)
    }
}

/**
 * The shape a first-run step has: a scrolling body that grows, then the action
 * row, which does not move.
 *
 * The status-bar inset is **not** here any more. It moved to [OnboardingHero],
 * which now sits above every step and is therefore what passes under the clock;
 * a spacer here as well would count the inset twice and leave each step
 * starting a status bar's height below where it should. The rule the settings
 * pages follow — content softened under the bar rather than stopping short of
 * it — is kept, one level up.
 */
@Composable
internal fun StepScaffold(
    actions: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The keyboard padding on the outer column, as the join screen has it:
    // on the sign-in step the keyboard then shrinks the scrolling body and
    // lifts the row, rather than covering the form it was opened for.
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = ScreenPadding),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        actions()
    }
}
