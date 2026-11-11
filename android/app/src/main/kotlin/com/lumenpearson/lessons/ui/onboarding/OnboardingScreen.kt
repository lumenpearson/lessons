package com.lumenpearson.lessons.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.appSlideMotionBlur
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.settings.SettingsViewModel

/** How long one step takes to slide the next one in, before the reader's motion setting. */
private const val StepTransitionMillis = 400

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
        //
        // While the sign-in is under way the gesture is taken and does nothing
        // (`back()` refuses then too): left alone at the floor it would finish
        // the activity, and cancel a registration our server completes anyway.
        val signingIn by viewModel.signIn.state.collectAsStateWithLifecycle()
        val busy = state.current == OnboardingStep.SIGN_IN && signingIn.busy
        BackHandler(enabled = state.canGoBack || busy) { viewModel.back() }
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
