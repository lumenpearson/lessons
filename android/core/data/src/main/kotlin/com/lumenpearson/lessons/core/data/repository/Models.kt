package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.DayOrder
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart

/**
 * Value types of the data layer's public API.
 *
 * They are plain data classes rather than entities or DTOs so that the UI and
 * the widget never see a Room row or a wire field, and so that changing either
 * storage or the wire format is invisible above this line.
 */

/**
 * Proof that this device belongs to a class.
 *
 * The [token] is long-lived and read-only; there is no refresh and no password,
 * so its presence is the whole of "signed in" and its absence sends the user
 * back to the join screen.
 *
 * [diary] is the class's diary binding as the join answered it, or `null` —
 * see [DiaryBinding]. Last and defaulted, so every place that builds a session
 * without one still means «no binding».
 */
data class Session(
    val classId: Long,
    val className: String,
    val school: String?,
    val token: String,
    val diary: DiaryBinding? = null,
)

/**
 * The bot's role ladder, as the server names it.
 *
 * Mirrors `Role` in `server/app/models.py`, weakest first. The app never
 * grants or compares these; it only shows the one the linked account holds and
 * lets the server decide what a write is allowed to do.
 */
enum class ClassRole {
    VIEWER,
    EDITOR,
    ADMIN,
    OWNER,
    ;

    companion object {
        /** `null` for an unknown or absent wire value, never a guess. */
        fun fromWire(raw: String?): ClassRole? =
            raw?.trim()?.uppercase()?.let { name -> entries.firstOrNull { it.name == name } }
    }
}

/**
 * Whether this phone is tied to a Telegram account, and what that buys it.
 *
 * A device starts unlinked and read-only. Linking it — typing [linkCode] into
 * the bot, or opening [botDeepLink] — ties the token to the account, and from
 * then on the server derives every write permission from that account's role
 * in the class at the moment of the request. There is no second permission
 * system on the phone: [canEdit] is what the server said last time we asked.
 *
 * @property role `null` while unlinked, and also for a linked account that is
 *   no longer a member of the class — in which case the server says linked
 *   but nothing can be edited.
 */
data class DeviceLink(
    val deviceName: String?,
    val linked: Boolean,
    val role: ClassRole?,
    val canEdit: Boolean,
    val linkCode: String?,
    val botDeepLink: String?,
)

/**
 * Everything the user can change.
 *
 * [baseUrl] is a setting rather than a build constant because every school hosts
 * its own server; the rest are display preferences the widget and the app share,
 * which is why they live here and not in a UI module.
 *
 * The personalization block — theme, haptics, tab behaviour, the two blur
 * effects — mirrors the "Customizations" section of
 * [Essentials](https://github.com/sameerasw/essentials), the app this one takes
 * its design language from.
 *
 * @property motionBlur blur a list along its scroll axis while it is moving.
 *   Off by default: it is a runtime shader on every scrolling frame, which is
 *   the one setting here a cheap phone can feel.
 * @property edgeBlur fade content out under the status bar. On by default,
 *   because without it a scrolled list collides with the clock.
 */
data class AppSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pitchBlack: Boolean = false,
    val hapticsEnabled: Boolean = true,
    val hapticStrength: HapticStrength = HapticStrength.SUBTLE,
    /**
     * The typeface the app is set in. [AppFont.BUNDLED] is the design's own
     * face; the alternative is whatever the phone reads in everywhere else.
     */
    val appFont: AppFont = AppFont.BUNDLED,
    /**
     * The language the app is read in.
     *
     * Stored here rather than left to the platform alone because the platform
     * only has somewhere to put it from API 33 onwards, and this app starts at
     * 26. One field, two mechanisms above it: see `AppLocales` in `:app`.
     */
    val language: AppLanguage = AppLanguage.SYSTEM,
    /**
     * Multiplies every size in the type scale.
     *
     * One of [TEXT_SCALE_OPTIONS] rather than any float, and stored as the
     * number rather than as an enum so that a future step between two of these
     * does not orphan what people already chose. It multiplies the *system's*
     * font size rather than replacing it: a phone already set to large type
     * stays large, and this moves from there.
     */
    val textScale: Float = DEFAULT_TEXT_SCALE,
    /**
     * Whether the app animates at all.
     *
     * Off makes transitions instant rather than quick — see
     * `MotionSettings.enabled`. Separate from the ripple and the theme wipe,
     * which are ornaments a person may want gone while still wanting the app to
     * move; this one is the movement itself.
     */
    val animations: Boolean = true,
    /** How fast it moves when it does; see `AppSettings.MOTION_SPEED_RANGE`. */
    val motionSpeed: Float = DEFAULT_MOTION_SPEED,
    val swipeTabs: Boolean = true,
    val defaultTab: HomeTab = HomeTab.TODAY,
    /**
     * The bottom bar's tabs, in the order this device draws them.
     *
     * Stored as a list rather than as an index per tab: the bar is a
     * permutation, and a permutation held as three separate numbers can
     * disagree with itself. What comes back out of storage is repaired by
     * [HomeTab.order], so this field is always every tab exactly once however
     * old the string behind it was.
     *
     * A `List` field costs nothing here: `compose-stability.conf` deliberately
     * leaves `:core:data` off the promise — it names this class among the four
     * that would qualify — so `AppSettings` is compared by identity either way.
     */
    val tabOrder: List<HomeTab> = HomeTab.entries,
    val motionBlur: Boolean = false,
    val motionBlurScale: Float = DEFAULT_MOTION_BLUR_SCALE,
    val edgeBlur: Boolean = true,
    val showTeacher: Boolean = true,
    val widgetShowProgress: Boolean = true,
    /**
     * The countdown card at the top of the home screen.
     *
     * Off leaves the screen a plain list. It is the one block up there that is
     * about the next five minutes rather than about the day, so somebody who
     * opens the app in the evening to read homework is looking past it every
     * time — and it is the tallest thing on the page.
     */
    val todayShowHero: Boolean = true,
    /** Which of the home screen's two big blocks leads; see [TodayLayout]. */
    val todayLayout: TodayLayout = TodayLayout.AUTOMATIC,
    /**
     * List the whole day rather than only what is left of it.
     *
     * Off — the screen's own rule — a pupil in the fourth lesson is not
     * re-read the first three. On is for the people who use the home screen as
     * the timetable and find a list that shrinks through the day disorienting.
     */
    val todayWholeDay: Boolean = false,
    /** How many homework rows the home screen previews; see [HOMEWORK_PREVIEW_OPTIONS]. */
    val todayHomeworkPreview: Int = DEFAULT_HOMEWORK_PREVIEW,
    /** Today's non-lesson entries — an assembly, lunch, an excursion — on the home screen. */
    val todayShowEvents: Boolean = true,
    /** Which date the calendar's week strip begins on; see [WeekStart]. */
    val weekStart: WeekStart = WeekStart.MONDAY,
    /**
     * Keep Saturday and Sunday in the week strip.
     *
     * Off drops them from the strip only. The month grid keeps all seven
     * columns whatever this says: a month whose rows were five days wide would
     * no longer line up with any calendar the user has ever seen.
     */
    val weekShowWeekends: Boolean = true,
    /** The dots under each date that say how many lessons it holds. */
    val weekShowLoad: Boolean = true,
    /** The day's events, under whatever drew its lessons. */
    val weekShowEvents: Boolean = true,
    /** The day's homework, under whatever drew its lessons. */
    val weekShowHomework: Boolean = true,
    /**
     * Which days the calendar narrows to. Empty is no filter at all, which is
     * where this rests — see [DayFilter], where the empty set and the OR
     * between several chosen facets are both deliberate.
     */
    val calendarFilters: Set<DayFilter> = emptySet(),
    /**
     * The order a list of days is drawn in. Reaches only the views that are
     * genuinely lists: a month grid cannot be sorted and stay a calendar.
     */
    val calendarOrder: DayOrder = DayOrder.DATE_ASC,
    /**
     * Which way the day ribbon's progress travels — see [RibbonFlow].
     *
     * The ribbon's own three settings live here rather than in the screen,
     * because a screen's `remember` is gone the moment the calendar changes
     * view, and a reader who turned the progress the other way up meant it for
     * longer than one visit.
     */
    val dayRibbonFlow: RibbonFlow = RibbonFlow.DOWNWARD,
    /**
     * Which reading of «День» the calendar opens on — see [DayMode].
     *
     * Stored beside the ribbon's own three settings rather than treated as
     * navigation, because it is the same kind of choice: a way of reading the
     * same days, kept between visits.
     */
    val dayMode: DayMode = DayMode.RIBBON,
    /**
     * Whether the ribbon settles on a whole entry when the scroll stops.
     *
     * On by default: the ribbon is read one entry at a time, and a fling that
     * leaves two half-rows on screen is a scroll somebody has to correct. Off
     * for anybody who would rather the list simply went where they threw it.
     */
    val dayRibbonSnap: Boolean = true,
    /**
     * Whether the ribbon draws its depth — the shader, the blur, the gradients.
     *
     * Its own switch rather than [animations], because this is the one screen
     * in the app that asks the GPU for something every frame, and the reason to
     * turn it off is a warm phone rather than a dislike of movement.
     */
    val dayRibbonDepth: Boolean = true,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
    /**
     * Whether the app keeps a crash report when it dies.
     *
     * Off by default, and it is a real choice rather than a formality: a crash
     * report contains the device model, the app version and a stack trace, and
     * nothing should be written to disk about somebody's phone because the
     * developer would find it convenient.
     */
    val debugMode: Boolean = false,
    /**
     * Whether the first-run introduction has been seen.
     *
     * Separate from "has a session" because the two answer different questions.
     * Signing out has to put the user back on the code field, but it must not
     * replay four screens of introduction at somebody who has been using the app
     * all term. This flag is therefore set once, when the introduction is
     * finished, and never cleared.
     */
    val onboardingDone: Boolean = false,
    /**
     * What the app is allowed to interrupt the user about.
     *
     * A nested value rather than seven more fields here: the planner in
     * :core:model takes exactly this type, so the settings screen, the store and
     * the scheduler all pass the same object around and no one has to rebuild it
     * field by field.
     */
    val alerts: AlertPreferences = AlertPreferences(),
    /**
     * The liquid ripple over the whole screen — on the debug switch, on a theme
     * change, on an update decision. A full-screen runtime shader for a second
     * and a half, and the one ornament here that a cheap phone can feel, so it
     * can be turned off on its own.
     */
    val rippleEffects: Boolean = true,
    /**
     * The circular wipe from the old theme to the new one. Without it the
     * colours simply swap in a frame, which is what a phone with reduced
     * motion asks for and what some people prefer regardless.
     */
    val themeReveal: Boolean = true,
    /**
     * Ask GitHub for a newer release when the app opens. One request, once
     * per launch, and only when the last one was long enough ago.
     */
    val autoCheckUpdates: Boolean = true,
    /** Whether a release GitHub flags as pre-release may be offered. */
    val includePrerelease: Boolean = false,
    /**
     * Raise the release sheet by itself when the automatic check finds one.
     * Off, the check still runs and the settings row shows the verdict, but
     * nothing interrupts.
     */
    val notifyNewUpdates: Boolean = true,
) {
    companion object {
        /**
         * Empty on purpose: there is no address that is right for a second user.
         *
         * This used to default to http://10.0.2.2:8000/, the emulator's alias
         * for the developer machine. On a real phone that host does not exist,
         * so the app shipped pointing at a phantom server and the first thing a
         * new user saw was a connection failure they had no way to interpret.
         * Empty makes the join screen ask, which is the honest behaviour.
         */
        const val DEFAULT_BASE_URL: String = ""

        /** Hourly is enough: a school timetable changes a few times a term. */
        const val DEFAULT_SYNC_INTERVAL_MINUTES: Int = 60

        /** WorkManager's own floor for periodic work; anything less is silently raised. */
        const val MIN_SYNC_INTERVAL_MINUTES: Int = 15

        /** Neutral motion-blur amount; the slider runs from half to two and a half. */
        const val DEFAULT_MOTION_BLUR_SCALE: Float = 1f

        /** Ends of the motion-blur slider, straight from the Essentials settings screen. */
        val MOTION_BLUR_SCALE_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2.5f

        /** The designed size; every other option is named against it. */
        const val DEFAULT_TEXT_SCALE: Float = 1f

        /**
         * The four text sizes offered, as a picker rather than a slider.
         *
         * A slider would let somebody land on 1.07 and have no way back to the
         * size the app was drawn at. Four named steps is a choice a person can
         * undo, and four is as many as fit a segmented picker on a 360 dp
         * screen once the labels are Russian.
         */
        val TEXT_SCALE_OPTIONS: List<Float> = listOf(0.85f, 1f, 1.15f, 1.3f)

        /** Clamped to this on read; the picker only ever offers the four steps. */
        val TEXT_SCALE_RANGE: ClosedFloatingPointRange<Float> =
            TEXT_SCALE_OPTIONS.first()..TEXT_SCALE_OPTIONS.last()

        /** Neutral speed: the transitions as they were tuned. */
        const val DEFAULT_MOTION_SPEED: Float = 1f

        /**
         * Ends of the animation-speed slider.
         *
         * Half speed is slow enough to watch a transition and not so slow that
         * the app feels stuck; double is quick enough to feel immediate while
         * still showing which way the page went. Anything outside that is
         * better served by the switch above it.
         */
        val MOTION_SPEED_RANGE: ClosedFloatingPointRange<Float> = 0.5f..2f

        /** What the home screen previewed before the count was a choice. */
        const val DEFAULT_HOMEWORK_PREVIEW: Int = 3

        /**
         * The homework preview lengths offered.
         *
         * One is "there is something to do"; three is the shipped preview; five
         * is most of a school day's worth, which is as much as belongs above a
         * tab that exists to hold the rest. Anything longer is the homework tab
         * with a different title.
         */
        val HOMEWORK_PREVIEW_OPTIONS: List<Int> = listOf(1, 3, 5)

        /**
         * The offered length closest to [count].
         *
         * Applied when the number is read and when it is written, so what is on
         * disk is always one of the three steps and the picker always has a
         * segment to light. Without it a four — from a hand-edited file, or from
         * a build that offered a fourth step — would draw a picker with nothing
         * selected and no way to find out which value was in force.
         */
        fun nearestHomeworkPreview(count: Int): Int =
            HOMEWORK_PREVIEW_OPTIONS.minBy { kotlin.math.abs(it - count) }
    }
}

/**
 * Outcome of a sync attempt.
 *
 * [Unauthorised] is separate from [Failed] because it is the one failure the
 * user can act on: the token was revoked server-side and the only cure is
 * joining again. Callers must not retry it.
 */
sealed interface SyncResult {

    /** The cache now holds a fresh window. */
    data object Success : SyncResult

    /** HTTP 401: the device token is gone or revoked. Send the user to the join screen. */
    data object Unauthorised : SyncResult

    /**
     * No usable server address is stored, so nothing was even attempted.
     *
     * Separate from [Failed] because it is the one failure with a specific
     * remedy — open settings and type an address — and because retrying it on a
     * timer forever, as [Failed] invites, can never succeed.
     */
    data object NotConfigured : SyncResult

    /** Anything transient - no network, server down, malformed payload. Retry later. */
    data class Failed(val message: String) : SyncResult
}
