package com.lumenpearson.lessons.navigation

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.LessonsFloatingToolbar
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurExtent
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurRadius
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.theme.BottomBarGap
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollBlur
import com.lumenpearson.lessons.core.designsystem.theme.ScrollBlurSettings
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.settings.SettingsScreen
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
 * the four destinations are peers, they keep their scroll position, and the
 * gesture between them is a swipe. A graph would give the same four screens
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

/**
 * The signed-in app: four tabs under one floating toolbar.
 *
 * Everything that makes the shell feel like Essentials is here rather than in
 * the screens: the swipe between tabs, the haptic that ticks through it, the
 * predictive-back gesture that scales the page down and returns to the default
 * tab, and the blur that lets content pass under the status bar.
 *
 * The toolbar does not fold away on scroll. It used to, driven from here, and
 * the idea cost more than it bought: on a page too short to scroll it folded
 * with nothing left to unfold it, so three destinations vanished for good; and
 * once folded it sat inside its own container's side padding as a lopsided
 * halo, because that padding is sized for four items and not for one. Four
 * destinations that are always present and always reachable — by a screen
 * reader too — beat an animation nobody asked for.
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

    // Measured rather than assumed: the pill's height depends on the gesture bar.
    // Seeded with the local's own default so the first frame — drawn before
    // onSizeChanged lands — reserves a plausible gap rather than none, which
    // showed as the bottom of the first list jumping once after sign-in.
    val seedBarHeight = LocalBottomBarSpace.current
    var barHeight by remember { mutableStateOf(seedBarHeight) }

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

    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = pagerState.currentPage != homePage) { events ->
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

    Box(modifier = modifier.fillMaxSize()) {
        CompositionLocalProvider(
            LocalBottomBarSpace provides barHeight + BottomBarGap,
            LocalScrollBlur provides ScrollBlurSettings(
                enabled = settings.motionBlur,
                scale = settings.motionBlurScale,
            ),
        ) {
            HorizontalPager(
                state = pagerState,
                userScrollEnabled = settings.swipeTabs,
                // Off-screen pages stay composed so a swipe back to a tab shows
                // the list where it was left rather than re-running its loader.
                beyondViewportPageCount = 1,
                modifier = Modifier
                    .fillMaxSize()
                    // On the content, not on the Box: the Box also holds the
                    // toolbar, and a bottom fade applied there would dissolve
                    // the toolbar along with the list running underneath it.
                    .progressiveBlur(
                        blurRadius = if (settings.edgeBlur) StatusBarBlurRadius else 0f,
                        topHeight = statusBarHeightPx * StatusBarBlurExtent,
                        bottomHeight = barHeightPx,
                        showGradientOverlay = settings.edgeBlur,
                    )
                    .graphicsLayer {
                        val scale = 1f - backProgress.value * BackScaleDepth
                        scaleX = scale
                        scaleY = scale
                    },
            ) { page ->
                when (tabs[page]) {
                    HomeTab.TODAY -> TodayScreen(
                        onOpenHomework = {
                            scope.launch {
                                pagerState.animateScrollToPage(tabs.indexOf(HomeTab.HOMEWORK))
                            }
                        },
                    )

                    HomeTab.WEEK -> WeekScreen()
                    HomeTab.HOMEWORK -> HomeworkScreen()
                    HomeTab.SETTINGS -> SettingsScreen()
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
            selectedIndex = pagerState.currentPage,
            items = tabs.mapIndexed { index, tab ->
                ToolbarItem(
                    icon = tab.icon,
                    label = stringResource(tab.labelRes),
                    onClick = { scope.launch { pagerState.animateScrollToPage(index) } },
                )
            },
        )
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
