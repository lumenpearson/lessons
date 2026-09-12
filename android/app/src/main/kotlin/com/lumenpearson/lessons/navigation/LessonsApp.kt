package com.lumenpearson.lessons.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.LessonsFloatingToolbar
import com.lumenpearson.lessons.core.designsystem.component.ToolbarAction
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurExtent
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurRadius
import com.lumenpearson.lessons.core.designsystem.modifier.TopBlurRampPx
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleState
import com.lumenpearson.lessons.core.designsystem.modifier.LocalLiquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.liquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.theme.BottomBarGap
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.MotionSettings
import com.lumenpearson.lessons.core.designsystem.theme.ScrollBlurSettings
import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.designsystem.theme.appSlideMotionBlur
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.debug.DebugSheet
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.onboarding.OnboardingScreen
import com.lumenpearson.lessons.ui.settings.SettingsRootScreen
import com.lumenpearson.lessons.ui.settings.SettingsSection
import com.lumenpearson.lessons.ui.settings.SettingsSectionScreen
import com.lumenpearson.lessons.ui.settings.UpdateHost
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.today.TodayScreen
import com.lumenpearson.lessons.ui.week.ScheduleView
import com.lumenpearson.lessons.ui.week.WeekScreen
import com.lumenpearson.lessons.ui.week.WeekViewModel
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The whole app below the theme.
 *
 * The signed-in part is a pager rather than a navigation graph, which is how
 * [Essentials](https://github.com/sameerasw/essentials) builds its own shell:
 * the three destinations are peers, they keep their scroll position, and the
 * gesture between them is a swipe. A graph would give the same three screens
 * without the swipe, and the swipe is half of what the floating toolbar is for.
 *
 * @param signedIn `null` while the stored session is still being read — the
 *   splash is shown for that moment rather than guessing a destination and then
 *   yanking the user somewhere else a frame later.
 * @param openDate a day the widget asked for; the shell moves to the calendar
 *   and selects it, then calls [onDateOpened] so the request is acted on once.
 */
@Composable
fun LessonsApp(
    signedIn: Boolean?,
    settings: AppSettings,
    modifier: Modifier = Modifier,
    openDate: LocalDate? = null,
    onDateOpened: () -> Unit = {},
) {
    // Published here rather than inside the signed-in shell, which is where it
    // used to live: the first-run steps and the join screen slide too, and a
    // local provided below them left those transitions permanently unblurred
    // however the setting was set.
    // The one wave for the whole window. Deliberately not saved across
    // configuration changes — a ripple restored on rotation would be a wave
    // from nowhere. Provided here, above every screen, so a theme switch three
    // pages down can fire it from its own row.
    val ripple = remember { LiquidRippleState() }

    CompositionLocalProvider(
        LocalScrollBlur provides ScrollBlurSettings(
            enabled = settings.motionBlur,
            scale = settings.motionBlurScale,
        ),
        // Published beside the blur settings and for the same reason: what has
        // to act on the preference is a transition spec below every screen, and
        // the first-run steps slide too.
        LocalMotion provides MotionSettings(
            enabled = settings.animations,
            speed = settings.motionSpeed,
        ),
        LocalLiquidRipple provides ripple,
    ) {
        // The page colour, painted once for the whole app.
        //
        // Nothing used to paint it. The tabs draw rows and nothing behind them,
        // so what showed between the rows was the *window* background — an
        // Android resource that follows the system's night mode and cannot
        // follow an in-app setting. While the two agreed it looked deliberate.
        // Choosing "светлая" on a phone in dark mode gave white rows and black
        // text on a black page, and "чёрная тема" appeared to do nothing at all,
        // because the only surface in the app that painted itself was the
        // settings layer — which is exactly where both settings did seem to work.
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            when (signedIn) {
                null -> SplashShell(modifier = modifier)

                false -> {
                    // Latched on the first composition of this branch rather than
                    // read live. The introduction records itself as seen the moment
                    // it reaches its last step, and re-reading the flag there would
                    // swap the whole screen for a bare join page halfway through the
                    // slide that was carrying the user to it.
                    //
                    // Safe to latch because settings are real by the time this
                    // branch exists at all: the shell's state combines the settings
                    // flow with the session, so nothing is emitted — and the splash
                    // above stays — until preferences have actually been read from
                    // disk.
                    val introduce = rememberSaveable { !settings.onboardingDone }
                    if (introduce) {
                        OnboardingScreen(modifier = modifier)
                    } else {
                        JoinScreen(modifier = modifier)
                    }
                }

                true -> HomeShell(
                    settings = settings,
                    openDate = openDate,
                    onDateOpened = onDateOpened,
                    modifier = modifier,
                )
            }
        }
    }
}

/** How far the whole pager shrinks while a predictive back gesture is in flight. */
private const val BackScaleDepth = 0.06f

/** Time the shell takes to settle back to rest after a cancelled back gesture. */
private const val BackSettleMillis = 300

/** …and after a completed one, which travels further and so takes longer. */
private const val BackReturnMillis = 400

/** The swipe rumble fires once per tenth of a page travelled. */
private const val SwipeHapticBuckets = 10

/** How long a settings page takes to slide in over the tabs. */
private const val PageTransitionMillis = 320

/** Key the tabs' saved state is filed under while they are out of the tree. */
private const val TabsStateKey = "home-tabs"

/**
 * The signed-in app: three tabs under one floating toolbar, with the settings
 * tree layered over them.
 *
 * Everything that makes the shell feel like Essentials is here rather than in
 * the screens: the swipe between tabs, the haptic that ticks through it, the
 * predictive-back gesture that scales the page down and returns to the default
 * tab, and the blur that lets content pass under the status bar.
 *
 * Settings is a layer, not a tab. Opening it slides a page in from the right
 * over the pager, and opening one of its sections slides another over that, so
 * the tabs underneath keep their scroll position and their loaded data — which
 * is the thing a real navigation graph would have cost here. Back pops one layer
 * at a time and only then reaches the predictive-back gesture below.
 *
 * The toolbar does not fold away on scroll. It used to, driven from here, and
 * the idea cost more than it bought: on a page too short to scroll it folded
 * with nothing left to unfold it, so the destinations vanished for good; and
 * once folded it sat inside its own container's side padding as a lopsided
 * halo, because that padding is sized for a row of items and not for one.
 * Destinations that are always present and always reachable — by a screen reader
 * too — beat an animation nobody asked for.
 */
@Composable
private fun HomeShell(
    settings: AppSettings,
    openDate: LocalDate?,
    onDateOpened: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = HomeTab.entries
    val view = rememberHapticView()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // Read here rather than inside the transition spec below: `transitionSpec`
    // is a plain lambda, not a composable one, so a composition local cannot be
    // reached from inside it.
    val motion = LocalMotion.current

    // Only read on the first composition — a pager cannot be re-seeded without
    // yanking the page out from under the user, so changing the default tab
    // takes effect the next time the app is opened. Essentials behaves the same.
    val homePage = remember { tabs.indexOf(settings.defaultTab).coerceAtLeast(0) }
    val pagerState = rememberPagerState(initialPage = homePage) { tabs.size }

    // Hoisted rather than left to the settings screen: the toolbar is drawn here
    // and its action button acts on this view model, so the shell needs it. Both
    // call sites resolve to the same instance through the activity's store, so
    // the screens below can keep their own default and nothing is passed down.
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    // Hoisted for the same reason: a day chip on the widget has to be able to
    // put a date into the calendar before the calendar has been composed.
    val calendarViewModel: WeekViewModel = viewModel(factory = WeekViewModel.Factory)
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var showDebugSheet by rememberSaveable { mutableStateOf(false) }

    // The app's wave, provided by [LessonsApp]; a fresh one only for a preview
    // that composes the shell bare.
    val ripple = LocalLiquidRipple.current ?: remember { LiquidRippleState() }
    // Where the shell sits on the screen, for turning a point a modal sheet
    // measured in its own window into one of ours.
    var shellOnScreen by remember { mutableStateOf(Offset.Zero) }
    var openSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val openSection = remember(openSectionName) { SettingsSection.fromName(openSectionName) }

    // Where the shell is, as one value. Derived from the two saved flags rather
    // than replacing them, so what survives process death is unchanged.
    val destination = remember(settingsOpen, openSection) {
        when {
            openSection != null -> ShellPage.Section(openSection)
            settingsOpen -> ShellPage.SettingsRoot
            else -> ShellPage.Tabs
        }
    }

    // Keeps each tab's scroll position while the tabs are out of the tree.
    //
    // They leave it entirely once a settings page is in front, which is what
    // stops them receiving touches — and that has to be removal rather than
    // cover. `OverlayLayerTest` measures why: a layer that consumes early enough
    // to stop the pager is early enough to cancel taps on its own rows, and one
    // that waits until its rows are safe has already let the pager through. This
    // shipped twice before that test existed. `AnimatedContent` removes the slot
    // that is no longer current, so the property now falls out of the navigation
    // rather than being maintained beside it.
    //
    // Without the holder, coming back would put every list at the top: the
    // scroll position of a LazyColumn is `rememberSaveable`, and a
    // `rememberSaveable` in a composable that leaves the tree is gone unless
    // something holds it.
    val tabStates = rememberSaveableStateHolder()

    // One holder per page plus one per settings layer. The pager keeps its
    // neighbours composed, so a single shared holder would have an off-screen
    // page reporting its own scroll over the visible one's.
    val pageOffsets = remember(tabs.size) { List(tabs.size) { ScrollOffsetHolder() } }
    val settingsOffset = remember { ScrollOffsetHolder() }
    val sectionOffset = remember { ScrollOffsetHolder() }

    // A widget tap lands here. Closing the settings layers first, because the
    // request is "show me this day" and a page that slides in over the calendar
    // would answer it with a screen the user did not ask for.
    LaunchedEffect(openDate) {
        val date = openDate ?: return@LaunchedEffect
        openSectionName = null
        settingsOpen = false
        calendarViewModel.select(date)
        calendarViewModel.setView(ScheduleView.DAY)
        pagerState.goToPage(motion, tabs.indexOf(HomeTab.WEEK).coerceAtLeast(0))
        onDateOpened()
    }

    // A tap when the page actually changes, however it was changed. Keyed on the
    // pager alone: re-keying on the swipe setting would restart the collector and
    // swallow the next change as if it were the initial one.
    LaunchedEffect(pagerState) {
        var first = true
        snapshotFlow { pagerState.currentPage }.collect {
            if (first) first = false else LessonsHaptics.tap(view)
        }
    }

    // The rumble while a swipe is in flight, bucketed so it ticks ten times
    // across a page instead of once per frame. Only while a finger is actually
    // on the screen: `isScrollInProgress` is also true for animateScrollToPage,
    // so gating on it turned one tap on a tab into a press, ten rumbles and a
    // tap — a buzz, and at the stronger levels three overlapping waveforms.
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(pagerState) {
        pagerState.interactionSource.interactions.collect { interaction ->
            dragging = when (interaction) {
                is DragInteraction.Start -> true
                is DragInteraction.Stop, is DragInteraction.Cancel -> false
                else -> dragging
            }
        }
    }

    var lastBucket by remember { mutableIntStateOf(0) }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPageOffsetFraction }.collect { offset ->
            if (!dragging) return@collect
            val bucket = (abs(offset) * SwipeHapticBuckets).toInt()
            if (bucket != lastBucket) {
                if (abs(offset) > 0f) LessonsHaptics.swipe(view)
                lastBucket = bucket
            }
        }
    }

    fun closeSection() {
        openSectionName = null
    }

    fun closeSettings() {
        openSectionName = null
        settingsOpen = false
    }

    // One layer per press, innermost first. Registered before the predictive
    // handler below so that it wins while anything is open: a back gesture in
    // settings has to leave settings, not scroll the pager underneath it.
    BackHandler(enabled = openSection != null) { closeSection() }
    BackHandler(enabled = settingsOpen && openSection == null) { closeSettings() }

    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(
        enabled = !settingsOpen && pagerState.currentPage != homePage,
    ) { events ->
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            scope.launch { pagerState.goToPage(motion, homePage) }
            scope.launch { backProgress.animateTo(0f, tween(BackReturnMillis)) }
        } catch (_: CancellationException) {
            scope.launch { backProgress.animateTo(0f, tween(BackSettleMillis)) }
        }
    }

    val statusBarHeightPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    if (showDebugSheet) {
        DebugSheet(
            enabled = settingsState.settings.debugMode,
            onEnabledChange = settingsViewModel::setDebugMode,
            onDismiss = { showDebugSheet = false },
        )
    }
    UpdateHost(
        state = settingsState,
        viewModel = settingsViewModel,
        ripple = ripple,
        screenToRoot = { onScreen -> onScreen - shellOnScreen },
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { shellOnScreen = it.positionOnScreen() }
            // On the whole shell, toolbar included, rather than on one page: the
            // wave starts at the bug button, and a ripple that left the button it
            // came from perfectly still would look like it came from somewhere
            // else. Outside the AnimatedContent for the same reason — a ripple
            // that only touched the page arriving would stop at its edge.
            .liquidRipple(ripple, enabled = settings.rippleEffects),
    ) {
        AnimatedContent(
            targetState = destination,
            transitionSpec = { pageTransition(targetState.depth > initialState.depth, motion) },
            label = "shell_page",
        ) { page ->
            ShellScaffold(
                edgeBlur = settings.edgeBlur,
                statusBarHeightPx = statusBarHeightPx,
                offset = when (page) {
                    ShellPage.Tabs -> pageOffsets[pagerState.currentPage]
                    ShellPage.SettingsRoot -> settingsOffset
                    is ShellPage.Section -> sectionOffset
                },
                // Everything here is read from `page`, never from the hoisted
                // state, and that is the whole discipline of this arrangement:
                // both slots are composed at once while the slide runs, so a
                // title read from outside would flip the instant you navigated
                // and the page would leave carrying the name of the one
                // arriving.
                toolbar = { barModifier ->
                    LessonsFloatingToolbar(
                        modifier = barModifier,
                        selectedIndex = if (page == ShellPage.Tabs) pagerState.currentPage else -1,
                        items = if (page == ShellPage.Tabs) {
                            tabs.mapIndexed { index, tab ->
                                ToolbarItem(
                                    icon = tab.icon,
                                    label = stringResource(tab.labelRes),
                                    onClick = {
                                        scope.launch { pagerState.goToPage(motion, index) }
                                    },
                                )
                            }
                        } else {
                            emptyList()
                        },
                        title = when (page) {
                            ShellPage.Tabs -> null
                            ShellPage.SettingsRoot -> stringResource(R.string.settings_title)
                            is ShellPage.Section -> stringResource(page.section.titleRes)
                        },
                        onBackClick = when (page) {
                            ShellPage.Tabs -> null
                            ShellPage.SettingsRoot -> ::closeSettings
                            is ShellPage.Section -> ::closeSection
                        },
                        action = shellAction(
                            settingsOpen = page != ShellPage.Tabs,
                            onOpenSettings = { settingsOpen = true },
                            onOpenDebug = { at ->
                                ripple.fire(at)
                                showDebugSheet = true
                            },
                        ),
                    )
                },
            ) {
                when (page) {
                    ShellPage.Tabs -> tabStates.SaveableStateProvider(TabsStateKey) {
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = settings.swipeTabs,
                            // Off-screen pages stay composed so a swipe back to a
                            // tab shows the list where it was left rather than
                            // re-running its loader.
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .fillMaxSize()
                                // The largest scroll in the app, and the one the
                                // blur setting was missing: it named lists and
                                // left out the gesture people actually spend
                                // their day on.
                                .appScrollMotionBlur(pagerState)
                                .graphicsLayer {
                                    val scale = 1f - backProgress.value * BackScaleDepth
                                    scaleX = scale
                                    scaleY = scale
                                },
                        ) { tabIndex ->
                            CompositionLocalProvider(
                                LocalScrollOffset provides pageOffsets[tabIndex],
                            ) {
                                when (tabs[tabIndex]) {
                                    HomeTab.TODAY -> TodayScreen(
                                        onOpenHomework = {
                                            scope.launch {
                                                pagerState.goToPage(
                                                    motion,
                                                    tabs.indexOf(HomeTab.HOMEWORK),
                                                )
                                            }
                                        },
                                    )

                                    HomeTab.WEEK -> WeekScreen(viewModel = calendarViewModel)
                                    HomeTab.HOMEWORK -> HomeworkScreen()
                                }
                            }
                        }
                    }

                    ShellPage.SettingsRoot -> CompositionLocalProvider(
                        LocalScrollOffset provides settingsOffset,
                    ) {
                        SettingsRootScreen(
                            viewModel = settingsViewModel,
                            onOpenSection = { section -> openSectionName = section.name },
                        )
                    }

                    is ShellPage.Section -> CompositionLocalProvider(
                        LocalScrollOffset provides sectionOffset,
                    ) {
                        // page.section, not the hoisted one: that is already null
                        // for the whole slide out, which is what the latch this
                        // replaces existed to paper over.
                        SettingsSectionScreen(
                            section = page.section,
                            onOpenSection = { next -> openSectionName = next.name },
                            viewModel = settingsViewModel,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Where the shell is, and how deep.
 *
 * The depth is declared rather than taken from an enum's ordinal, because an
 * ordinal is a declaration order and reordering the list would silently reverse
 * a transition.
 */
@Immutable
private sealed interface ShellPage {

    val depth: Int

    data object Tabs : ShellPage {
        override val depth: Int = 0
    }

    data object SettingsRoot : ShellPage {
        override val depth: Int = 1
    }

    data class Section(val section: SettingsSection) : ShellPage {
        override val depth: Int = 2
    }
}

/**
 * One page of the shell, with its own toolbar riding on it.
 *
 * The toolbar used to be drawn once, above everything, and stayed still while
 * the page moved under it — so opening settings slid a page in beneath a bar
 * that was already showing that page's title. Here it belongs to the page, so
 * the two travel together and each screen's bar arrives with it.
 *
 * The cost of that is one measurement per page instead of one for the app, and
 * that is deliberate too: during a slide there are two toolbars, and a single
 * shared height would be written twice per frame by two different bars.
 */
@Composable
private fun ShellScaffold(
    edgeBlur: Boolean,
    statusBarHeightPx: Float,
    offset: ScrollOffsetHolder,
    toolbar: @Composable (Modifier) -> Unit,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val seedBarHeight = LocalBottomBarSpace.current
    var barHeight by remember { mutableStateOf(seedBarHeight) }
    val barHeightPx = with(density) { barHeight.toPx() }

    // This page's own scroll drives this page's own fade. The shell used to pick
    // whichever screen was in front and hand one number to everybody, which the
    // page sliding away then wore for the length of the slide.
    val topFraction by animateFloatAsState(
        targetValue = (offset.value / TopBlurRampPx).coerceIn(0f, 1f),
        label = "top_blur_fraction",
    )

    Box(modifier = Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalBottomBarSpace provides barHeight + BottomBarGap) {
            // The blur goes on the content, never on the parent that also holds
            // the toolbar: a bottom fade applied there would dissolve the
            // toolbar along with the list running underneath it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .progressiveBlur(
                        blurRadius = if (edgeBlur) StatusBarBlurRadius else 0f,
                        topHeight = statusBarHeightPx * StatusBarBlurExtent,
                        bottomHeight = barHeightPx,
                        topFraction = topFraction,
                        showGradientOverlay = edgeBlur,
                    ),
            ) {
                content()
            }
        }

        toolbar(
            Modifier
                .align(Alignment.BottomCenter)
                .zIndex(1f)
                .onSizeChanged { size ->
                    barHeight = with(density) { size.height.toDp() }
                },
        )
    }
}

/**
 * Forward pushes the old page off to the left; back slides it away to the right.
 *
 * One spec for the movement and the fade, rather than a tween on one and a
 * spring on the other: given different curves the arriving page reaches full
 * opacity while it is still visibly moving, and the leaving one disappears
 * before it is off the screen.
 *
 * `SizeTransform(clip = false)` because settings pages are wildly different
 * heights, and the default animates the slot's size *and* clips to it — which
 * crops whichever page is taller for the length of the slide.
 */
private fun pageTransition(forward: Boolean, motion: MotionSettings): ContentTransform {
    // Instant, not quick. Scaling the durations towards zero would still slide
    // the page — a two-frame slide is a flicker, which is worse than no
    // animation for exactly the people who switch animations off.
    if (!motion.enabled) {
        return ContentTransform(
            targetContentEnter = EnterTransition.None,
            initialContentExit = ExitTransition.None,
            sizeTransform = SizeTransform(clip = false),
        )
    }

    val fade = tween<Float>(motion.durationMillis(PageTransitionMillis))
    val enter = slideInHorizontally(animationSpec = pageSlideSpring(motion)) { width ->
        if (forward) width else -width
    } + fadeIn(animationSpec = fade)

    val exit = slideOutHorizontally(animationSpec = pageSlideSpring(motion)) { width ->
        if (forward) -width else width
    } + fadeOut(animationSpec = fade)

    return ContentTransform(
        targetContentEnter = enter,
        initialContentExit = exit,
        sizeTransform = SizeTransform(clip = false),
    )
}

/** A stiffer spring is a faster one; see [MotionSettings.stiffness]. */
private fun pageSlideSpring(motion: MotionSettings) = spring<IntOffset>(
    dampingRatio = Spring.DampingRatioNoBouncy,
    stiffness = motion.stiffness(Spring.StiffnessMediumLow),
)

/**
 * The button beside the pill, chosen by where the shell is.
 *
 * On the tabs it is the way into settings, which is why settings stopped being a
 * tab. Inside settings it is the bug — the crash reports and the switch that
 * decides whether any are kept — which is where Essentials puts its own bug
 * button and for the same reason: this app ships as an APK inside one school,
 * with no crash service behind it, so the only place a crash can be read is the
 * phone it happened on.
 *
 * It used to be a refresh button here. Refresh already has a row of its own in
 * the sync section, two taps away, and spending the one permanent button in the
 * app on a duplicate of a row is a poor trade.
 */
@Composable
private fun shellAction(
    settingsOpen: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDebug: (at: Offset) -> Unit,
): ToolbarAction = if (settingsOpen) {
    ToolbarAction(
        icon = Icons.Rounded.BugReport,
        contentDescription = stringResource(R.string.debug_open),
        onClick = onOpenDebug,
    )
} else {
    ToolbarAction(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.nav_settings),
        onClick = { onOpenSettings() },
    )
}

/**
 * Moves the pager to [page] — instantly when the user has switched animations
 * off, and with the usual glide otherwise.
 *
 * A tab tap is the movement people make most, so leaving it animated while the
 * settings pages had become instant would have made the switch look broken from
 * the very screen it lives on.
 */
private suspend fun PagerState.goToPage(motion: MotionSettings, page: Int) {
    if (motion.enabled) animateScrollToPage(page) else scrollToPage(page)
}

/**
 * A settings page slides in across a third of the screen, not the whole of it.
 *
 * Named because the blur needs the same number: the shader is fed how far the
 * layer really travels, and a second copy of "3" would silently stop matching
 * the first the day the animation is retuned.
 */
private const val LayerTravelDivisor = 3

/**
 * Shown only while the session is being read. It is a deliberate blank with a
 * spinner: anything richer would flash for 30 ms and read as a glitch.
 */
@Composable
private fun SplashShell(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
}
