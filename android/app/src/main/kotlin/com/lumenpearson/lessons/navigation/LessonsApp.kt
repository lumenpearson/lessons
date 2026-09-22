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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.text.intl.Locale
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.designsystem.component.LessonsFloatingToolbar
import com.lumenpearson.lessons.core.designsystem.component.LessonsLoadingIndicator
import com.lumenpearson.lessons.core.designsystem.component.ToolbarAction
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.BottomBlurHeight
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurExtent
import com.lumenpearson.lessons.core.designsystem.modifier.StatusBarBlurRadius
import com.lumenpearson.lessons.core.designsystem.modifier.TopBlurRampPx
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleState
import com.lumenpearson.lessons.core.designsystem.modifier.LocalLiquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.liquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.progressiveBlur
import com.lumenpearson.lessons.core.designsystem.text.correctedString
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
import com.lumenpearson.lessons.ui.admin.isClassManager
import com.lumenpearson.lessons.ui.debug.DebugSheet
import com.lumenpearson.lessons.ui.docs.DocsScreen
import com.lumenpearson.lessons.ui.docs.DocsViewModel
import com.lumenpearson.lessons.ui.docs.docsToolbarItems
import com.lumenpearson.lessons.ui.docs.docsToolbarSelection
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.join.JoinScreen
import com.lumenpearson.lessons.ui.onboarding.OnboardingScreen
import com.lumenpearson.lessons.ui.settings.SettingsRootScreen
import com.lumenpearson.lessons.ui.settings.SettingsSection
import com.lumenpearson.lessons.ui.settings.SettingsSectionScreen
import com.lumenpearson.lessons.ui.settings.UpdateHost
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.settings.role
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
    // The reader's own order, repaired on the way out of storage — see
    // `HomeTab.order`, which guarantees this is every tab exactly once however
    // old the string behind it was. Every index below is an index into *this*
    // list, and the whole of the arranging gesture is written in terms of tabs
    // rather than positions for that reason.
    val tabs = settings.tabOrder
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
    //
    // What is latched is the tab, and its index is looked up every time.
    // Latching the index instead would survive a reorder as a number that has
    // since come to mean another tab, and back would then return to a screen
    // nobody chose — the same defect as the pager's, one level up.
    val homeTab = remember { settings.defaultTab }
    val homePage = tabs.indexOf(homeTab).coerceAtLeast(0)
    val pagerState = rememberPagerState(initialPage = homePage) { tabs.size }

    // The order the bar is drawing while its tabs are being arranged, and null
    // whenever they are not. One state rather than two, because «in the mode»
    // and «the list the mode started from» are never separately true.
    //
    // It is latched because the bar reports a permutation of the items it was
    // *handed* and goes on drawing from those same items until the mode closes.
    // Feeding the committed order back in mid-gesture would therefore apply the
    // reader's drag a second time, and the bar would appear to undo it — and a
    // second drag in the same session would report a permutation of a list
    // neither side still had.
    //
    // What that leaves is one frame: the tap that closes the mode swaps the
    // bar's list and the bar's own permutation back in two steps rather than
    // one, so the previous order can be drawn once on the way. Holding the
    // latch a frame longer would close it, and would rest on the order two
    // `LaunchedEffect`s in two modules happen to run in — which is the kind of
    // dependency that has gone quietly wrong here under a version bump before
    // (see `OverlayLayerTest`). A flicker that can be named beats one that
    // cannot.
    var arranging by remember { mutableStateOf<List<HomeTab>?>(null) }
    val reordering = arranging != null
    val barTabs = arranging ?: tabs

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

    // Whether the guide is open. A flag rather than a path: its sections are
    // peers on one pager now, so there is no history inside it to remember —
    // back means "leave", and the pager keeps its own position across a
    // rotation.
    var docsOpen by rememberSaveable { mutableStateOf(false) }

    val docsViewModel: DocsViewModel = viewModel(factory = DocsViewModel.Factory)
    val docsState by docsViewModel.state.collectAsStateWithLifecycle()
    val docsPages = docsState.library?.guide?.pages.orEmpty()

    // The guide's own pager, hoisted for the reason the tabs' is: the toolbar
    // is composed by the shell, and it both shows which section is current and
    // scrolls to another.
    val docsPagerState = rememberPagerState(pageCount = { docsPages.size })

    // Which language the reader is actually looking at. Below Android 13 the
    // app's chosen language is applied by wrapping the `Context`, so the
    // composition is the only place that knows — a repository reading the
    // phone's locale would hand a Russian reader the English guide.
    val docsLanguage = Locale.current.language

    // Read as it opens, then checked against the repository. Keyed on the
    // language too, so switching it while the guide is closed still loads the
    // right one on the next visit.
    LaunchedEffect(docsOpen, docsLanguage) {
        if (docsOpen) docsViewModel.open(docsLanguage)
    }

    // Where the shell is, as one value. Derived from the saved flags rather
    // than replacing them, so what survives process death is unchanged.
    val destination = remember(settingsOpen, openSection, docsOpen) {
        when {
            docsOpen -> ShellPage.Docs
            openSection != null -> ShellPage.Section(openSection)
            settingsOpen -> ShellPage.SettingsRoot
            else -> ShellPage.Tabs
        }
    }

    // Keeps each tab's scroll position while the tabs are out of the tree.
    //
    // They leave it entirely once a settings page is in front, which is what
    // stops them receiving touches — and that has to be removal rather than
    // cover. A covering layer that consumed early enough to stop the pager was
    // early enough to cancel taps on its own rows, and this shipped twice before
    // a test existed for it. `OverlayLayerTest` now records that compose-bom
    // 2026.09.00 changed that dispatch, which is the argument for removal rather
    // than against it: the ordering is unspecified, it moved once under a
    // dependency bump without a word, and a layer would go silently dead again.
    // Removal leaves nothing to block. `AnimatedContent` removes the slot that is
    // no longer current, so the property now falls out of the navigation rather
    // than being maintained beside it.
    //
    // Without the holder, coming back would put every list at the top: the
    // scroll position of a LazyColumn is `rememberSaveable`, and a
    // `rememberSaveable` in a composable that leaves the tree is gone unless
    // something holds it.
    val tabStates = rememberSaveableStateHolder()

    // One holder per page plus one per settings layer. The pager keeps its
    // neighbours composed, so a single shared holder would have an off-screen
    // page reporting its own scroll over the visible one's.
    //
    // Per tab rather than per page index, because the bar can be rearranged:
    // a holder that stayed with the index would hand the screen arriving there
    // the scroll depth of the one that left, and the top fade would start
    // halfway down a list that is at the top.
    val pageOffsets = remember { HomeTab.entries.associateWith { ScrollOffsetHolder() } }

    /** The holder belonging to whatever tab sits at [page] of the pager. */
    fun offsetOf(page: Int): ScrollOffsetHolder =
        pageOffsets.getValue(tabs.getOrElse(page) { tabs.first() })

    val settingsOffset = remember { ScrollOffsetHolder() }
    val sectionOffset = remember { ScrollOffsetHolder() }
    // One per section, for the reason the tabs have one per tab: the pager keeps
    // its neighbours composed, and a shared holder would let an off-screen page
    // report its scroll over the visible one's.
    val docsOffset = remember { ScrollOffsetHolder() }
    val docsOffsets = remember(docsPages.size) {
        List(docsPages.size.coerceAtLeast(1)) { ScrollOffsetHolder() }
    }

    // The tab the reader was looking at when the bar was last rearranged, held
    // until the new order has actually come back out of storage.
    //
    // Which is the whole difficulty of this gesture: `pagerState.currentPage` is
    // an index, and the reorder changes what that index means, so a reader who
    // drags «Задания» to the front would silently be moved to another screen.
    // The tab is captured before the write and the pager is put back on it
    // after — and the write is a round trip through DataStore, so «after» is a
    // frame or two later and cannot be done in the same breath.
    var keepOnTab by remember { mutableStateOf<HomeTab?>(null) }
    LaunchedEffect(tabs) {
        val tab = keepOnTab ?: return@LaunchedEffect
        keepOnTab = null
        val target = tabs.indexOf(tab)
        // `scrollToPage`, never the animated one: the page did not go
        // anywhere, the index under it did, and an animation here is a screen
        // visibly sliding to a place it never left.
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
    }

    // A widget tap lands here. Closing the settings layers first, because the
    // request is "show me this day" and a page that slides in over the calendar
    // would answer it with a screen the user did not ask for.
    LaunchedEffect(openDate) {
        val date = openDate ?: return@LaunchedEffect
        docsOpen = false
        openSectionName = null
        settingsOpen = false
        // A deep link is somebody arriving with a question, and a bar that is
        // still being arranged is in the way of answering it.
        arranging = null
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

    /**
     * Leaving the mode where the tabs are arranged.
     *
     * Called by everything that takes the reader off the tabs as well as by the
     * back press: the mode is a property of a bar that is about to be replaced
     * by another page's, and one left armed behind a settings page would be
     * waiting, jiggling, when that page was closed.
     */
    fun stopArranging() {
        arranging = null
    }

    /**
     * A drag on the tab bar has finished. See [reorderTabs] for the arithmetic,
     * which is separate because it is the half of this that can be wrong
     * quietly.
     *
     * [moved] is relative to the list the bar was handed, which is [barTabs] and
     * deliberately not the stored one — see where that is latched.
     */
    fun commitTabOrder(moved: List<Int>) {
        // Captured before the write, because after it the index means another
        // tab; the effect above puts the pager back on this one.
        keepOnTab = tabs.getOrNull(pagerState.currentPage)
        settingsViewModel.setTabOrder(reorderTabs(barTabs, moved))
    }

    /** Opening the guide from the «О приложении» page. */
    fun openDocs() {
        docsOpen = true
        stopArranging()
    }

    /** A tap on a section in the toolbar: a scroll, not a screen. */
    fun openDocsPage(index: Int) {
        scope.launch { docsPagerState.goToPage(motion, index) }
    }

    /**
     * The one definition of "back" inside the documentation.
     *
     * The arrow beside the pill and the system gesture both run this, which is
     * the point: two ways out that disagreed about where back goes is a bug a
     * reader cannot work around. There is nothing to walk off any more — the
     * sections are peers on one pager, and a reader on the fourth of them asked
     * for the fourth rather than arrived at it through three others.
     *
     * Leaving goes home rather than back to the settings page it was opened
     * from. The guide is somewhere you go to read, and handing a reader who has
     * finished the settings tree they came through would make them press back
     * twice more to reach the thing the documentation was about.
     */
    fun docsBack() {
        docsOpen = false
        closeSettings()
    }

    // One layer per press, innermost first, and exactly one handler enabled at
    // a time: a back gesture in settings has to leave settings, not scroll the
    // pager underneath it, and a back press while the tabs are being arranged
    // has to leave that mode rather than do either.
    //
    // The guards are mutually exclusive rather than merely ordered, and they
    // are now one function rather than four conditions that have to be read
    // together to see that — the documentation is opened from inside a settings
    // section, so while it is up two of the others are also true. Which of them
    // acts is a rule, so [shellBack] states it once and `ShellBackTest` holds
    // it; the handlers below only carry it out.
    val back = shellBack(
        arranging = reordering,
        docsOpen = docsOpen,
        sectionOpen = openSection != null,
        settingsOpen = settingsOpen,
        onHomePage = pagerState.currentPage == homePage,
    )
    BackHandler(enabled = back == ShellBack.LEAVE_ARRANGING) { stopArranging() }
    BackHandler(enabled = back == ShellBack.CLOSE_DOCS) { docsBack() }
    BackHandler(enabled = back == ShellBack.CLOSE_SECTION) { closeSection() }
    BackHandler(enabled = back == ShellBack.CLOSE_SETTINGS) { closeSettings() }

    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = back == ShellBack.HOME) { events ->
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
                    ShellPage.Tabs -> offsetOf(pagerState.currentPage)
                    ShellPage.SettingsRoot -> settingsOffset
                    is ShellPage.Section -> sectionOffset
                    ShellPage.Docs -> docsOffsets.getOrElse(docsPagerState.currentPage) { docsOffset }
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
                        selectedIndex = when (page) {
                            // Which *tab* is in front, expressed as a position
                            // in the list this bar is drawing. The two lists
                            // are the same one outside the arranging mode, and
                            // inside it the bar's is a frame behind the stored
                            // one on purpose.
                            ShellPage.Tabs -> tabs.getOrNull(pagerState.currentPage)
                                ?.let(barTabs::indexOf) ?: -1

                            ShellPage.Docs -> docsToolbarSelection(
                                docsPagerState.currentPage,
                                docsPages.size,
                            )
                            else -> -1
                        },
                        items = when (page) {
                            // Each item carries its tab rather than its
                            // position: where a tab is drawn and where its page
                            // is differ for the frames between a drag and the
                            // order coming back out of storage, and a tap in
                            // that window has to reach the screen the icon is
                            // of.
                            ShellPage.Tabs -> barTabs.map { tab ->
                                ToolbarItem(
                                    icon = tab.icon,
                                    label = correctedString(tab.labelRes),
                                    onClick = {
                                        val target = tabs.indexOf(tab).coerceAtLeast(0)
                                        scope.launch { pagerState.goToPage(motion, target) }
                                    },
                                )
                            }

                            // The bar is the documentation's only navigation,
                            // so it carries every section rather than a way
                            // back to a list of them: there is no list. The
                            // sections come from the fetched guide, so one
                            // added to the documentation appears here without
                            // a new build.
                            ShellPage.Docs -> docsToolbarItems(docsPages, ::openDocsPage)

                            else -> emptyList()
                        },
                        // Only the destinations that are peers of each other
                        // overflow a phone; the three tabs never will.
                        scrollableItems = page == ShellPage.Docs,
                        // The tabs only. The documentation's bar is the same
                        // component, and its order is the document's rather
                        // than the reader's: a mode that opened there would let
                        // somebody rearrange a table of contents into one the
                        // text no longer matches.
                        reorderable = page == ShellPage.Tabs,
                        // Read against the page for the same reason everything
                        // else here is: both bars are composed at once while a
                        // slide runs, and the one leaving must not start
                        // jiggling on its way out.
                        reordering = reordering && page == ShellPage.Tabs,
                        onReorderingChange = { on ->
                            arranging = if (on) tabs else null
                        },
                        onReorder = ::commitTabOrder,
                        title = when (page) {
                            ShellPage.Tabs, ShellPage.Docs -> null
                            ShellPage.SettingsRoot -> correctedString(R.string.settings_title)
                            is ShellPage.Section -> correctedString(page.section.titleRes)
                        },
                        onBackClick = when (page) {
                            // Null keeps the bar in its tabbed mode. On the
                            // documentation that is deliberate: the pill is the
                            // table of contents, and back is the button beside
                            // it — see [shellActionKind].
                            ShellPage.Tabs, ShellPage.Docs -> null
                            ShellPage.SettingsRoot -> ::closeSettings
                            is ShellPage.Section -> ::closeSection
                        },
                        action = shellAction(
                            destination = page.destination,
                            // The shortcut exists for the people who have the
                            // page it shortcuts to.
                            manager = isClassManager(settingsState.deviceLink.role),
                            onOpenSettings = {
                                settingsOpen = true
                                stopArranging()
                            },
                            onOpenDebug = { at ->
                                ripple.fire(at)
                                showDebugSheet = true
                            },
                            onBack = ::docsBack,
                        ),
                    )
                },
            ) {
                when (page) {
                    ShellPage.Tabs -> tabStates.SaveableStateProvider(TabsStateKey) {
                        HorizontalPager(
                            state = pagerState,
                            userScrollEnabled = settings.swipeTabs,
                            // Keyed by the tab, not by the position it is in.
                            // The default key is the index, and an index is
                            // exactly what a reorder changes: the saved scroll
                            // position filed under «page 1» would be restored
                            // into whichever screen had moved there.
                            key = { index -> tabs[index] },
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
                                LocalScrollOffset provides offsetOf(tabIndex),
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
                            onOpenDocs = ::openDocs,
                            viewModel = settingsViewModel,
                        )
                    }

                    ShellPage.Docs -> CompositionLocalProvider(
                        // The current section's holder, chosen the same way the
                        // tabs choose theirs; the screen itself provides one
                        // per page inside the pager.
                        LocalScrollOffset provides docsOffsets
                            .getOrElse(docsPagerState.currentPage) { docsOffset },
                    ) {
                        DocsScreen(
                            state = docsState,
                            pagerState = docsPagerState,
                            onRefresh = { docsViewModel.refresh(docsLanguage) },
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

    /**
     * The guide, all of it.
     *
     * One destination rather than one per section, which is the change: the
     * sections are peers on a pager and moving between them is a swipe, so the
     * shell's `AnimatedContent` has nothing to do between them. It used to push
     * a screen per section, with a depth per section to make the slide follow
     * the finger; a pager does that itself, in the same gesture the home tabs
     * use, and without a history to walk back out of.
     *
     * Deeper than a settings section, so opening the guide still slides forward
     * and leaving it slides back.
     */
    data object Docs : ShellPage {
        override val depth: Int = 3
    }
}

/** As much of the page as the toolbar's action button needs to know. */
private val ShellPage.destination: ShellDestination
    get() = when (this) {
        ShellPage.Tabs -> ShellDestination.TABS
        ShellPage.SettingsRoot, is ShellPage.Section -> ShellDestination.SETTINGS
        ShellPage.Docs -> ShellDestination.DOCS
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

    // Essentials' distance, not the toolbar's height. The bar measures around
    // 60 dp here, so a fade tied to it only began where the toolbar already
    // covered the list — the rows arrived sharp and were cut off rather than
    // dissolving into it. `coerceAtLeast` is the one thing not copied: it keeps
    // the guarantee the measured height gave, that the fade is never shorter
    // than the bar it has to reach behind, on a device whose gesture inset
    // makes the toolbar taller than Essentials' 130 dp.
    val bottomBlurPx = with(density) { BottomBlurHeight.toPx() }.coerceAtLeast(barHeightPx)

    // This page's own scroll drives this page's own fade. The shell used to pick
    // whichever screen was in front and hand one number to everybody, which the
    // page sliding away then wore for the length of the slide.
    // derivedStateOf, because the raw offset changes every scrolled pixel and
    // the fraction it produces does not: past the ramp it is 1f and stays
    // there. Read directly, this composable — the whole page, toolbar included
    // — was invalidated on every frame of every scroll for a number that had
    // stopped moving. The derived read only invalidates when the clamped value
    // actually changes, which is a handful of frames per swipe instead of all
    // of them.
    val target by remember(offset) {
        derivedStateOf { (offset.value / TopBlurRampPx).coerceIn(0f, 1f) }
    }
    val topFraction by animateFloatAsState(targetValue = target, label = "top_blur_fraction")

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
                        bottomHeight = bottomBlurPx,
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
 *
 * On the documentation it is the way back, because the pill beside it is full:
 * it carries the sections, so the bar's standard mode — a back arrow and a
 * title — is not available there. Which of the three it is, is decided by
 * [shellActionKind]; this only dresses the answer.
 */
@Composable
private fun shellAction(
    destination: ShellDestination,
    manager: Boolean,
    onOpenSettings: () -> Unit,
    onOpenDebug: (at: Offset) -> Unit,
    onBack: () -> Unit,
): ToolbarAction? = when (shellActionKind(destination, manager)) {
    ShellActionKind.SETTINGS -> ToolbarAction(
        icon = Icons.Rounded.Settings,
        contentDescription = correctedString(R.string.nav_settings),
        onClick = { onOpenSettings() },
    )

    ShellActionKind.DEBUG -> ToolbarAction(
        icon = Icons.Rounded.BugReport,
        contentDescription = correctedString(R.string.debug_open),
        onClick = onOpenDebug,
    )

    ShellActionKind.BACK -> ToolbarAction(
        icon = Icons.AutoMirrored.Rounded.ArrowBack,
        contentDescription = correctedString(R.string.docs_back),
        onClick = { onBack() },
    )

    ShellActionKind.NONE -> null
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
        LessonsLoadingIndicator()
    }
}
