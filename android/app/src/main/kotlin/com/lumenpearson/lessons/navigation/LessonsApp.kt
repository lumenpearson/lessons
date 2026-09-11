package com.lumenpearson.lessons.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.lumenpearson.lessons.core.designsystem.modifier.liquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.theme.BottomBarGap
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollOffset
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
    CompositionLocalProvider(
        LocalScrollBlur provides ScrollBlurSettings(
            enabled = settings.motionBlur,
            scale = settings.motionBlurScale,
        ),
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

    // A counter rather than a flag, because the thing being answered is a tap:
    // pressing the bug button twice should give two waves, and a boolean has no
    // way to say "again". Deliberately not saved across configuration changes —
    // a ripple restored on rotation would be a wave from nowhere.
    var rippleTrigger by remember { mutableIntStateOf(0) }
    var rippleOrigin by remember { mutableStateOf(Offset.Unspecified) }
    var openSectionName by rememberSaveable { mutableStateOf<String?>(null) }
    val openSection = remember(openSectionName) { SettingsSection.fromName(openSectionName) }

    // The section still being drawn, which outlives the one navigated away from.
    // Closing a section leaves this pointing at it so that the page has
    // something to render for the 320 ms it spends sliding away; without it the
    // page empties on the first frame of its own exit. A plain box rather than
    // snapshot state: it only ever changes in a composition that is already
    // happening because `openSection` changed.
    val sectionLatch = remember { arrayOfNulls<SettingsSection>(1) }
    if (openSection != null) sectionLatch[0] = openSection
    val shownSection = sectionLatch[0] ?: SettingsSection.APPEARANCE

    // Measured rather than assumed: the pill's height depends on the gesture bar.
    // Seeded with the local's own default so the first frame — drawn before
    // onSizeChanged lands — reserves a plausible gap rather than none, which
    // showed as the bottom of the first list jumping once after sign-in.
    val seedBarHeight = LocalBottomBarSpace.current
    var barHeight by remember { mutableStateOf(seedBarHeight) }

    // Whether a settings page has finished sliding over the tabs.
    //
    // The tabs are then dropped from the composition rather than covered, which
    // is the only way to stop them receiving touches. A layer on top cannot do
    // it, and that is measured rather than argued: `OverlayLayerTest` presses a
    // row inside such a layer and the press never arrives. Compose runs each
    // pass over the whole of one subtree before the next, so a layer that
    // consumes early enough to stop the pager is early enough to cancel taps on
    // its own rows, and one that waits until its rows are safe has already let
    // the pager through. This shipped twice before that test existed.
    //
    // The delay is the slide: while the page is still moving the tabs are behind
    // it and have to be drawn.
    var tabsCovered by remember { mutableStateOf(false) }
    LaunchedEffect(settingsOpen) {
        if (!settingsOpen) {
            tabsCovered = false
        } else {
            delay(PageTransitionMillis.toLong())
            tabsCovered = true
        }
    }

    // Keeps each tab's scroll position across that removal. Without it, opening
    // settings and coming back would put every list at the top — the scroll
    // position of a LazyColumn is `rememberSaveable`, and a `rememberSaveable`
    // in a composable that leaves the tree is gone unless something holds it.
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
        tabsCovered = false
        calendarViewModel.select(date)
        calendarViewModel.setView(ScheduleView.DAY)
        pagerState.animateScrollToPage(tabs.indexOf(HomeTab.WEEK).coerceAtLeast(0))
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
            scope.launch { pagerState.animateScrollToPage(homePage) }
            scope.launch { backProgress.animateTo(0f, tween(BackReturnMillis)) }
        } catch (_: CancellationException) {
            scope.launch { backProgress.animateTo(0f, tween(BackSettleMillis)) }
        }
    }

    val statusBarHeightPx = with(density) {
        WindowInsets.statusBars.asPaddingValues().calculateTopPadding().toPx()
    }
    val barHeightPx = with(density) { barHeight.toPx() }

    // Whichever screen is actually in front decides how far the top fade is in.
    val frontOffset = when {
        openSection != null -> sectionOffset
        settingsOpen -> settingsOffset
        else -> pageOffsets[pagerState.currentPage]
    }
    val topFraction by animateFloatAsState(
        targetValue = (frontOffset.value / TopBlurRampPx).coerceIn(0f, 1f),
        label = "top_blur_fraction",
    )

    if (showDebugSheet) {
        DebugSheet(
            enabled = settingsState.settings.debugMode,
            onEnabledChange = settingsViewModel::setDebugMode,
            onDismiss = { showDebugSheet = false },
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // On the whole shell, toolbar included, rather than on the content
            // box below: the wave starts at the bug button, and a ripple that
            // left the button it came from perfectly still would look like it
            // came from somewhere else.
            .liquidRipple(trigger = rippleTrigger, origin = rippleOrigin),
    ) {
        CompositionLocalProvider(LocalBottomBarSpace provides barHeight + BottomBarGap) {
            // The blur goes on the content, never on the parent that also holds
            // the toolbar: a bottom fade applied there would dissolve the
            // toolbar along with the list running underneath it.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .progressiveBlur(
                        blurRadius = if (settings.edgeBlur) StatusBarBlurRadius else 0f,
                        topHeight = statusBarHeightPx * StatusBarBlurExtent,
                        bottomHeight = barHeightPx,
                        topFraction = topFraction,
                        showGradientOverlay = settings.edgeBlur,
                    ),
            ) {
                if (!tabsCovered) {
                    tabStates.SaveableStateProvider(TabsStateKey) {
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = settings.swipeTabs && !settingsOpen,
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
                        ) { page ->
                            CompositionLocalProvider(
                                LocalScrollOffset provides pageOffsets[page],
                            ) {
                                when (tabs[page]) {
                                    HomeTab.TODAY -> TodayScreen(
                                        onOpenHomework = {
                                            scope.launch {
                                                pagerState.animateScrollToPage(
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
                }

                SettingsLayer(visible = settingsOpen) {
                    CompositionLocalProvider(LocalScrollOffset provides settingsOffset) {
                        SettingsRootScreen(
                            viewModel = settingsViewModel,
                            onOpenSection = { section -> openSectionName = section.name },
                        )
                    }
                }

                SettingsLayer(visible = openSection != null) {
                    CompositionLocalProvider(LocalScrollOffset provides sectionOffset) {
                        SettingsSectionScreen(
                            // shownSection, not openSection: the latter is
                            // already null for the whole slide out.
                            section = shownSection,
                            onOpenSection = { next -> openSectionName = next.name },
                            viewModel = settingsViewModel,
                        )
                    }
                }
            }
        }

        LessonsFloatingToolbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(1f)
                .onSizeChanged { size ->
                    barHeight = with(density) { size.height.toDp() }
                },
            selectedIndex = if (settingsOpen) -1 else pagerState.currentPage,
            items = if (settingsOpen) {
                emptyList()
            } else {
                tabs.mapIndexed { index, tab ->
                    ToolbarItem(
                        icon = tab.icon,
                        label = stringResource(tab.labelRes),
                        onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                    )
                }
            },
            title = when {
                openSection != null -> stringResource(openSection.titleRes)
                settingsOpen -> stringResource(R.string.settings_title)
                else -> null
            },
            onBackClick = when {
                openSection != null -> ::closeSection
                settingsOpen -> ::closeSettings
                else -> null
            },
            action = shellAction(
                settingsOpen = settingsOpen,
                onOpenSettings = { settingsOpen = true },
                onOpenDebug = { at ->
                    rippleOrigin = at
                    rippleTrigger++
                    showDebugSheet = true
                },
            ),
        )
    }
}

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
 * One page of the settings tree, sliding in from the side over what is beneath.
 *
 * Opaque on purpose: the pager underneath stays composed so that the tabs keep
 * their scroll position, and a translucent layer would show it moving.
 */
@Composable
private fun SettingsLayer(
    visible: Boolean,
    content: @Composable () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = slideInHorizontally(tween(PageTransitionMillis)) { width -> width / LayerTravelDivisor } +
            fadeIn(tween(PageTransitionMillis)),
        exit = slideOutHorizontally(tween(PageTransitionMillis)) { width -> width / LayerTravelDivisor } +
            fadeOut(tween(PageTransitionMillis)),
    ) {
        // The same float the slide is drawn from, so the blur is driven by the
        // movement rather than by a second timer that has to be kept in step
        // with it. `transition` belongs to this AnimatedVisibility and is only
        // running while the page is actually travelling.
        val slide = transition.animateFloat(
            transitionSpec = { tween(PageTransitionMillis) },
            label = "layer_slide",
        ) { state -> if (state == EnterExitState.Visible) 1f else 0f }
        val travel = LocalConfiguration.current.screenWidthDp.dp / LayerTravelDivisor

        Box(
            modifier = Modifier
                .fillMaxSize()
                .appSlideMotionBlur(
                    moving = { transition.isRunning },
                    fraction = { slide.value },
                    travel = travel,
                )
                .background(MaterialTheme.colorScheme.surfaceContainer),
        ) {
            content()
        }
    }
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
