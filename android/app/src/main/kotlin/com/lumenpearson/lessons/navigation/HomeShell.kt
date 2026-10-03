package com.lumenpearson.lessons.navigation

import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Settings
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.intl.Locale
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.diagnostics.ActivityKind
import com.lumenpearson.lessons.core.data.diagnostics.ActivityLog
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.ShellMode
import com.lumenpearson.lessons.core.designsystem.component.LessonsFloatingToolbar
import com.lumenpearson.lessons.core.designsystem.component.ToolbarAction
import com.lumenpearson.lessons.core.designsystem.component.ToolbarItem
import com.lumenpearson.lessons.core.designsystem.haptic.LessonsHaptics
import com.lumenpearson.lessons.core.designsystem.haptic.rememberHapticView
import com.lumenpearson.lessons.core.designsystem.modifier.LiquidRippleState
import com.lumenpearson.lessons.core.designsystem.modifier.LocalLiquidRipple
import com.lumenpearson.lessons.core.designsystem.modifier.liquidRipple
import com.lumenpearson.lessons.core.designsystem.text.correctedString
import com.lumenpearson.lessons.core.designsystem.theme.LocalBottomBarSpace
import com.lumenpearson.lessons.core.designsystem.theme.LocalMotion
import com.lumenpearson.lessons.core.designsystem.theme.LocalScrollOffset
import com.lumenpearson.lessons.core.designsystem.theme.ScrollOffsetHolder
import com.lumenpearson.lessons.core.designsystem.theme.appScrollMotionBlur
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.ui.admin.isClassManager
import com.lumenpearson.lessons.ui.debug.DebugSheet
import com.lumenpearson.lessons.ui.diary.DiaryScreen
import com.lumenpearson.lessons.ui.diary.DiaryTab
import com.lumenpearson.lessons.ui.diary.DiaryViewModel
import com.lumenpearson.lessons.ui.diary.icon
import com.lumenpearson.lessons.ui.diary.labelRes
import com.lumenpearson.lessons.ui.docs.DocsScreen
import com.lumenpearson.lessons.ui.docs.DocsViewModel
import com.lumenpearson.lessons.ui.docs.docsToolbarItems
import com.lumenpearson.lessons.ui.docs.docsToolbarSelection
import com.lumenpearson.lessons.ui.homework.HomeworkScreen
import com.lumenpearson.lessons.ui.settings.SettingsRootScreen
import com.lumenpearson.lessons.ui.settings.SettingsSection
import com.lumenpearson.lessons.ui.settings.SettingsSectionScreen
import com.lumenpearson.lessons.ui.settings.SettingsViewModel
import com.lumenpearson.lessons.ui.settings.UpdateHost
import com.lumenpearson.lessons.ui.settings.effectiveRole
import com.lumenpearson.lessons.ui.today.TodayScreen
import com.lumenpearson.lessons.ui.week.ScheduleView
import com.lumenpearson.lessons.ui.week.WeekScreen
import com.lumenpearson.lessons.ui.week.WeekViewModel
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** How far the whole pager shrinks while a predictive back gesture is in flight. */
private const val BackScaleDepth = 0.06f

/** Time the shell takes to settle back to rest after a cancelled back gesture. */
private const val BackSettleMillis = 300

/** …and after a completed one, which travels further and so takes longer. */
private const val BackReturnMillis = 400

/** The swipe rumble fires once per tenth of a page travelled. */
private const val SwipeHapticBuckets = 10

/** Key the tabs' saved state is filed under while they are out of the tree. */
private const val TabsStateKey = "home-tabs"

/**
 * Key the settings root's saved state is filed under while a page is over it.
 * The sections are filed under their names, which no section can share with
 * this.
 */
private const val SettingsRootStateKey = "settings-root"

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
 *
 * The same shell draws both homes ([home]). On the diary home the tabs' pager is
 * replaced by the diary itself and the toolbar carries its two halves instead
 * of the class tabs; everything else — the settings layers, the guide, back,
 * the update host — is shared, because a second shell would be a second copy of
 * the rules `ShellBackTest` holds. The diary's halves are not swiped between
 * and not rearranged: their order is the diary's, and there are two.
 */
@Composable
internal fun HomeShell(
    home: ShellHome,
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
    // The one bar's height, measured where it is drawn and handed to every
    // page, which pads its content by it (#264).
    val seedBarHeight = LocalBottomBarSpace.current
    var barHeight by remember { mutableStateOf(seedBarHeight) }
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
    // It is latched so that both drags of one session describe permutations of
    // the same list: the bar reports a permutation of the items it was *handed*,
    // and a list that changed under it between two drags would leave the second
    // one describing something neither side still had.
    //
    // It is not latched to keep the bar from applying a drag twice. The bar
    // holds its permutation against the list it belongs to and drops it when
    // that list changes, so a caller that hands the committed order straight
    // back is drawn correctly — which is also what makes the frame this mode
    // closes on correct, rather than one frame of the previous order.
    var arranging by remember { mutableStateOf<List<HomeTab>?>(null) }
    val reordering = arranging != null
    val barTabs = arranging ?: tabs

    // Hoisted rather than left to the settings screen: the toolbar is drawn here
    // and its action button acts on this view model, so the shell needs it. Both
    // call sites resolve to the same instance through the activity's store, so
    // the screens below can keep their own default and nothing is passed down.
    val settingsViewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.Factory)
    // Hoisted for the same reason: a day chip on the widget has to be able to
    // put a date into the calendar before the calendar has been composed. Only
    // on the class home — the diary home has no calendar, and building one
    // would start a timetable load for a class this phone is not in. The
    // branch is stable for the life of this composition: the shell is keyed
    // on [home].
    val calendarViewModel: WeekViewModel? =
        if (home == ShellHome.TIMETABLE) viewModel(factory = WeekViewModel.Factory) else null
    // The diary's, on the diary home: the toolbar switches its tabs and back
    // reads which one is open, so the shell holds it — the activity's store
    // hands the settings page the same instance.
    val diaryViewModel: DiaryViewModel? =
        if (home == ShellHome.DIARY) viewModel(factory = DiaryViewModel.Factory) else null
    val diaryTab = diaryViewModel?.uiState?.collectAsStateWithLifecycle()?.value?.tab
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val shellMode = if (home == ShellHome.DIARY) ShellMode.DIARY else ShellMode.CLASS

    // The one refresh of the diary that nobody pressed for: each time the diary
    // home comes to the front, the week is read again if the saved copy is
    // stale. Here and nowhere else — not in the application, a receiver or the
    // worker — because a read is what keeps the server's copy of the session
    // alive, and that has to mean somebody opened the app (G4). What does the
    // reading is handed to the view model by its factory, which is where
    // `Graph` is read: the shell is a screen, not an entry point.
    if (diaryViewModel != null) {
        LifecycleStartEffect(diaryViewModel) {
            diaryViewModel.refreshOnStart()
            onStopOrDispose { }
        }
    }

    // `/me` with the shell rather than with the settings page, so that the
    // role the bar is drawn with is usually confirmed before the gear is
    // pressed; the remembered one covers the launch until then (#228).
    if (home == ShellHome.TIMETABLE) {
        LaunchedEffect(Unit) { settingsViewModel.refreshDeviceLink() }
    }

    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var showDebugSheet by rememberSaveable { mutableStateOf(false) }

    // The app's wave, provided by [LessonsApp]; a fresh one only for a preview
    // that composes the shell bare.
    val ripple = LocalLiquidRipple.current ?: remember { LiquidRippleState() }
    // Where the shell sits on the screen, for turning a point a modal sheet
    // measured in its own window into one of ours.
    var shellOnScreen by remember { mutableStateOf(Offset.Zero) }
    // The settings pages open over the root, in the order they were opened
    // (#243). Saved as their names, so the path survives process death the way
    // the single open section it replaced did.
    var openSections by rememberSaveable { mutableStateOf("") }
    val trail = remember(openSections) { SettingsTrail.decode(openSections) }

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
    val destination = remember(settingsOpen, trail, docsOpen) {
        when {
            docsOpen -> ShellPage.Docs
            else -> trail.page() ?: if (settingsOpen) ShellPage.SettingsRoot else ShellPage.Tabs
        }
    }

    // The developer mode's activity record of what is on screen (#237): one
    // line per page, and per tab while the tabs are in front. A no-op unless
    // that record is switched on.
    val shownTab = tabs.getOrNull(pagerState.currentPage)
    LaunchedEffect(destination, shownTab) {
        ActivityLog.record(ActivityKind.SCREEN, screenName(destination, shownTab))
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
    val diaryOffset = remember { ScrollOffsetHolder() }

    /** The holder belonging to whatever tab sits at [page] of the pager. */
    fun offsetOf(page: Int): ScrollOffsetHolder =
        pageOffsets.getValue(tabs.getOrElse(page) { tabs.first() })

    val settingsOffset = remember { ScrollOffsetHolder() }
    // One per section now that one can be opened over another (#243): both are
    // composed while the slide runs, and a shared holder let the page leaving
    // report its scroll over the one arriving.
    val sectionOffsets = remember { SettingsSection.entries.associateWith { ScrollOffsetHolder() } }

    // Each settings page's saved state — its scroll, above all — for while
    // another page is over it. A page leaves the composition when one slides
    // over it, so without this, back from «Разрешения» put «Уведомления» at the
    // top, wherever the reader had left it. Forgotten when the page closes, so
    // opening it again starts where a page starts.
    val settingsStates = rememberSaveableStateHolder()
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
        val target = tabs.indexOf(tab)
        // `scrollToPage`, never the animated one: the page did not go
        // anywhere, the index under it did, and an animation here is a screen
        // visibly sliding to a place it never left.
        if (target >= 0 && target != pagerState.currentPage) pagerState.scrollToPage(target)
        // Let go only once the pager is on it: the bar reads this tab as the
        // selected one until then, and releasing it first left a frame in which
        // the selection came from an index that still meant the old order.
        keepOnTab = null
    }

    // A widget tap lands here. Closing the settings layers first, because the
    // request is "show me this day" and a page that slides in over the calendar
    // would answer it with a screen the user did not ask for.
    LaunchedEffect(openDate) {
        val date = openDate ?: return@LaunchedEffect
        docsOpen = false
        openSections = ""
        settingsOpen = false
        // A deep link is somebody arriving with a question, and a bar that is
        // still being arranged is in the way of answering it.
        arranging = null
        // Only a widget tap from before the phone left its class can reach the
        // diary home — the widget draws nothing to tap in the diary mode — and
        // the nearest answer to «this day» there is the diary's week holding it.
        diaryViewModel?.showWeekOf(date)
        calendarViewModel?.let { calendar ->
            calendar.select(date)
            calendar.setView(ScheduleView.DAY)
            pagerState.goToPage(motion, tabs.indexOf(HomeTab.WEEK).coerceAtLeast(0))
        }
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

    /**
     * [section] opened from [from] — the root when null (#243). Read from the
     * saved path as it is at the tap, not as it was when the row was composed.
     */
    fun openSection(section: SettingsSection, from: SettingsSection?) {
        openSections = SettingsTrail.decode(openSections).opened(section, from).encode()
    }

    /** Back from a settings page: onto the page it was opened from (#243). */
    fun closeSection() {
        val trail = SettingsTrail.decode(openSections)
        trail.top?.let { settingsStates.removeState(it.name) }
        openSections = trail.closed().encode()
    }

    fun closeSettings() {
        SettingsTrail.decode(openSections).sections.forEach { settingsStates.removeState(it.name) }
        settingsStates.removeState(SettingsRootStateKey)
        openSections = ""
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
     * Leaving goes back to the settings page it was opened from, like every
     * other back in the tree (#243). It used to go home, on the reasoning that a
     * reader done with the guide was done with settings too; the owner found it
     * the other way — back that lands two layers up is back that skipped a page.
     */
    fun docsBack() {
        docsOpen = false
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
        sectionOpen = trail.top != null,
        settingsOpen = settingsOpen,
        // The diary's home page is its timetable, as the class's is the
        // default tab: back from the marks goes there before it leaves.
        onHomePage = if (home == ShellHome.DIARY) {
            diaryTab == null || diaryTab == DiaryTab.SCHEDULE
        } else {
            pagerState.currentPage == homePage
        },
    )
    BackHandler(enabled = back == ShellBack.LEAVE_ARRANGING) { stopArranging() }
    BackHandler(enabled = back == ShellBack.CLOSE_DOCS) { docsBack() }
    BackHandler(enabled = back == ShellBack.CLOSE_SECTION) { closeSection() }
    BackHandler(enabled = back == ShellBack.CLOSE_SETTINGS) { closeSettings() }

    val backProgress = remember { Animatable(0f) }
    PredictiveBackHandler(enabled = back == ShellBack.HOME) { events ->
        try {
            events.collect { event -> backProgress.snapTo(event.progress) }
            if (diaryViewModel != null) {
                diaryViewModel.setTab(DiaryTab.SCHEDULE)
            } else {
                scope.launch { pagerState.goToPage(motion, homePage) }
            }
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
                    ShellPage.Tabs -> if (home == ShellHome.DIARY) diaryOffset else offsetOf(pagerState.currentPage)
                    ShellPage.SettingsRoot -> settingsOffset
                    is ShellPage.Section -> sectionOffsets.getValue(page.section)
                    ShellPage.Docs -> docsOffsets.getOrElse(docsPagerState.currentPage) { docsOffset }
                },
                barHeight = barHeight,
                // Read against the page: the page sliding away must not
                // keep a layer that swallows touches.
                onTouchOutsideBar = if (
                    reordering && page == ShellPage.Tabs && home == ShellHome.TIMETABLE
                ) {
                    ::stopArranging
                } else {
                    null
                },
            ) {
                when (page) {
                    ShellPage.Tabs -> if (diaryViewModel != null) {
                        tabStates.SaveableStateProvider(TabsStateKey) {
                            CompositionLocalProvider(LocalScrollOffset provides diaryOffset) {
                                DiaryScreen(
                                    asHome = true,
                                    insecureServer = isInsecure(settings.baseUrl),
                                    viewModel = diaryViewModel,
                                    modifier = Modifier.graphicsLayer {
                                        val scale = 1f - backProgress.value * BackScaleDepth
                                        scaleX = scale
                                        scaleY = scale
                                    },
                                )
                            }
                        }
                    } else tabStates.SaveableStateProvider(TabsStateKey) {
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

                                    HomeTab.WEEK -> WeekScreen(
                                        viewModel = checkNotNull(calendarViewModel) {
                                            "the class home always has its calendar"
                                        },
                                    )
                                    HomeTab.HOMEWORK -> HomeworkScreen()
                                }
                            }
                        }
                    }

                    ShellPage.SettingsRoot -> CompositionLocalProvider(
                        LocalScrollOffset provides settingsOffset,
                    ) {
                        settingsStates.SaveableStateProvider(SettingsRootStateKey) {
                            SettingsRootScreen(
                                mode = shellMode,
                                viewModel = settingsViewModel,
                                onOpenSection = { section -> openSection(section, from = null) },
                            )
                        }
                    }

                    is ShellPage.Section -> CompositionLocalProvider(
                        LocalScrollOffset provides sectionOffsets.getValue(page.section),
                    ) {
                        // page.section, not the hoisted one: that is already null
                        // for the whole slide out, which is what the latch this
                        // replaces existed to paper over. And opened *from*
                        // page.section, for the same reason: a row tapped on a
                        // page that is sliding away opens from where it was seen.
                        settingsStates.SaveableStateProvider(page.section.name) {
                            SettingsSectionScreen(
                                section = page.section,
                                mode = shellMode,
                                onOpenSection = { next -> openSection(next, from = page.section) },
                                onOpenDocs = ::openDocs,
                                viewModel = settingsViewModel,
                            )
                        }
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
        // One bar for every page, outside the pages' own `AnimatedContent`
        // (#264). The pages slide under it and it morphs into each page's form
        // — the tabs into a back button and a title, one title into the next,
        // the tabs into the guide's table of contents — rather than leaving with
        // its page while another arrives with the next. It reads the page being
        // travelled to, so it starts to change the moment the page does, and the
        // change runs alongside the slide instead of being over before the page
        // has arrived, which is what a bar that merely stood still used to do.
        val page = destination
        LessonsFloatingToolbar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .zIndex(1f)
                .onSizeChanged { size -> barHeight = with(density) { size.height.toDp() } },
            depth = page.depth,
            selectedIndex = when (page) {
                // Which *tab* is in front, expressed as a position
                // in the list this bar is drawing. The two lists
                // are the same one outside the arranging mode, and
                // inside it the bar's is a frame behind the stored
                // one on purpose.
                ShellPage.Tabs -> if (home == ShellHome.DIARY) {
                    diaryTab?.ordinal ?: 0
                } else {
                    // The tab being kept on, while there is one. A
                    // drop writes the new order, and the frame it
                    // arrives in still has the pager on the old
                    // *index* — the keyed pager moves it during its
                    // own measure, after this was read — so the
                    // index named the tab that had moved into it,
                    // and the selected circle hopped to that tab
                    // for a frame and back (#180).
                    //
                    // The page being travelled to, not the one in
                    // front: a tap two tabs away scrolls the pager
                    // through the page between, and while that one
                    // was in front the bar selected a tab nobody
                    // chose — its label began to open and closed
                    // again, and the pill turned towards it and
                    // back (#260). It also moved nothing until the
                    // page had scrolled halfway.
                    (keepOnTab ?: tabs.getOrNull(pagerState.targetPage))
                        ?.let(barTabs::indexOf) ?: -1
                }

                ShellPage.Docs -> docsToolbarSelection(
                    // As the tabs' bar, for the same reason (#260).
                    docsPagerState.targetPage,
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
                // The diary's own halves, in the diary's order.
                ShellPage.Tabs -> if (diaryViewModel != null) {
                    DiaryTab.entries.map { tab ->
                        ToolbarItem(
                            icon = tab.icon,
                            label = correctedString(tab.labelRes()),
                            onClick = { diaryViewModel.setTab(tab) },
                        )
                    }
                } else barTabs.map { tab ->
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
            reorderable = page == ShellPage.Tabs && home == ShellHome.TIMETABLE,
            // Only on the tabs: the bar morphs away from them as
            // settings open, and must not go on jiggling while it does.
            reordering = reordering && page == ShellPage.Tabs && home == ShellHome.TIMETABLE,
            onReorderingChange = { on ->
                arranging = if (on) tabs else null
            },
            onReorder = ::commitTabOrder,
            // Where back goes, not where the reader is (#244): the
            // page's own name is its heading already. On the root
            // that is the tab it was opened from, which does not
            // change while settings are open.
            title = backLabel(
                page = page,
                tabLabel = if (diaryViewModel != null) {
                    (diaryTab ?: DiaryTab.SCHEDULE).labelRes()
                } else {
                    tabs.getOrElse(pagerState.currentPage) { tabs.first() }.labelRes
                },
            )?.let { correctedString(it) },
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
                // page it shortcuts to — never on the diary home,
                // where there is no class to manage.
                manager = home == ShellHome.TIMETABLE &&
                    isClassManager(settingsState.effectiveRole),
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
