package com.lumenpearson.lessons.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
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
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
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
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.modifier.swallowGestures
import com.lumenpearson.lessons.core.designsystem.theme.BottomBarGap
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScrollBlurSettings
import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.settings.SettingsRootScreen
import com.lumenpearson.lessons.ui.settings.SettingsSection
import com.lumenpearson.lessons.ui.settings.SettingsSectionScreen
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.today.TodayScreen
import com.lumenpearson.lessons.ui.week.WeekScreen
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
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
 */
@Composable
fun LessonsApp(
    signedIn: Boolean?,
    settings: AppSettings,
    modifier: Modifier = Modifier,
) {
    when (signedIn) {
        null -> SplashShell(modifier = modifier)
        false -> JoinScreen(modifier = modifier)
        true -> HomeShell(settings = settings, modifier = modifier)
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
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
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

    // One holder per page plus one per settings layer. The pager keeps its
    // neighbours composed, so a single shared holder would have an off-screen
    // page reporting its own scroll over the visible one's.
    val pageOffsets = remember(tabs.size) { List(tabs.size) { ScrollOffsetHolder() } }
    val settingsOffset = remember { ScrollOffsetHolder() }
    val sectionOffset = remember { ScrollOffsetHolder() }

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

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalBottomBarSpace provides barHeight + BottomBarGap,
            LocalScrollBlur provides ScrollBlurSettings(
                enabled = settings.motionBlur,
                scale = settings.motionBlurScale,
            ),
        ) {
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
                HorizontalPager(
                    state = pagerState,
                    userScrollEnabled = settings.swipeTabs && !settingsOpen,
                    // Off-screen pages stay composed so a swipe back to a tab
                    // shows the list where it was left rather than re-running
                    // its loader.
                    beyondViewportPageCount = 1,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val scale = 1f - backProgress.value * BackScaleDepth
                            scaleX = scale
                            scaleY = scale
                        },
                ) { page ->
                    CompositionLocalProvider(LocalScrollOffset provides pageOffsets[page]) {
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

                            HomeTab.WEEK -> WeekScreen()
                            HomeTab.HOMEWORK -> HomeworkScreen()
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
                section = openSection,
                isRefreshing = settingsState.isRefreshing,
                onOpenSettings = { settingsOpen = true },
                onRefresh = settingsViewModel::refreshNow,
            ),
        )
    }
}

/**
 * The button beside the pill, chosen by where the shell is.
 *
 * On the tabs it is the way into settings, which is why settings stopped being
 * a tab. Inside settings it is the one thing that page can do from here, and on
 * the pages where there is nothing it is absent rather than disabled — an
 * always-present button that is grey on four pages out of six teaches nobody
 * anything.
 */
@Composable
private fun shellAction(
    settingsOpen: Boolean,
    section: SettingsSection?,
    isRefreshing: Boolean,
    onOpenSettings: () -> Unit,
    onRefresh: () -> Unit,
): ToolbarAction? = when {
    !settingsOpen -> ToolbarAction(
        icon = Icons.Rounded.Settings,
        contentDescription = stringResource(R.string.nav_settings),
        onClick = onOpenSettings,
    )

    section == null || section == SettingsSection.SYNC -> ToolbarAction(
        icon = Icons.Rounded.Refresh,
        contentDescription = stringResource(R.string.settings_refresh_now),
        onClick = { if (!isRefreshing) onRefresh() },
    )

    else -> null
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
        enter = slideInHorizontally(tween(PageTransitionMillis)) { width -> width / 3 } +
            fadeIn(tween(PageTransitionMillis)),
        exit = slideOutHorizontally(tween(PageTransitionMillis)) { width -> width / 3 } +
            fadeOut(tween(PageTransitionMillis)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surfaceContainer)
                // The pager underneath stays composed and stays hit-testable, so
                // without this a tap on an empty part of a settings page reached
                // whatever row happened to be behind it, and a horizontal drag
                // there changed tabs under the page the user was looking at.
                .swallowGestures(),
        ) {
            content()
        }
    }
}

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
