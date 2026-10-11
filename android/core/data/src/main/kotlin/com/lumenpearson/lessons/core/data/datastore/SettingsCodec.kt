package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.model.AlertPreferences
import com.lumenpearson.lessons.core.model.AppFont
import com.lumenpearson.lessons.core.model.AppLanguage
import com.lumenpearson.lessons.core.model.DayFilter
import com.lumenpearson.lessons.core.model.DayMode
import com.lumenpearson.lessons.core.model.DayOrder
import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.LessonAlertDetail
import com.lumenpearson.lessons.core.model.RibbonFlow
import com.lumenpearson.lessons.core.model.ThemeMode
import com.lumenpearson.lessons.core.model.TodayLayout
import com.lumenpearson.lessons.core.model.WeekStart

/**
 * Every field of [updated] under its key: the write half of [toSettings].
 *
 * The two are a pair, and a setting one of them forgets fails nothing — it is
 * written and never read back, or read and always its default — so
 * `SettingsRoundTripTest` writes a value that differs from the defaults in every
 * field and reads it back through both.
 */
internal fun MutablePreferences.putSettings(updated: AppSettings) {
    this[PreferenceKeys.KEY_BASE_URL] = updated.baseUrl.trim()
    this[PreferenceKeys.KEY_THEME_MODE] = updated.themeMode.name
    this[PreferenceKeys.KEY_DYNAMIC_COLOR] = updated.dynamicColor
    this[PreferenceKeys.KEY_PITCH_BLACK] = updated.pitchBlack
    this[PreferenceKeys.KEY_HAPTICS] = updated.hapticsEnabled
    this[PreferenceKeys.KEY_HAPTIC_STRENGTH] = updated.hapticStrength.name
    this[PreferenceKeys.KEY_APP_FONT] = updated.appFont.name
    this[PreferenceKeys.KEY_LANGUAGE] = updated.language.name
    this[PreferenceKeys.KEY_TEXT_SCALE] = updated.textScale.coerceIn(AppSettings.TEXT_SCALE_RANGE)
    this[PreferenceKeys.KEY_ANIMATIONS] = updated.animations
    this[PreferenceKeys.KEY_MOTION_SPEED] = updated.motionSpeed.coerceIn(AppSettings.MOTION_SPEED_RANGE)
    this[PreferenceKeys.KEY_SWIPE_TABS] = updated.swipeTabs
    this[PreferenceKeys.KEY_DEFAULT_TAB] = updated.defaultTab.name
    this[PreferenceKeys.KEY_TAB_ORDER] = HomeTab.storedOrder(updated.tabOrder)
    this[PreferenceKeys.KEY_MOTION_BLUR] = updated.motionBlur
    this[PreferenceKeys.KEY_MOTION_BLUR_SCALE] = updated.motionBlurScale
        .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE)
    this[PreferenceKeys.KEY_EDGE_BLUR] = updated.edgeBlur
    this[PreferenceKeys.KEY_SHOW_TEACHER] = updated.showTeacher
    this[PreferenceKeys.KEY_WIDGET_SHOW_PROGRESS] = updated.widgetShowProgress
    this[PreferenceKeys.KEY_TODAY_SHOW_HERO] = updated.todayShowHero
    this[PreferenceKeys.KEY_TODAY_LAYOUT] = updated.todayLayout.name
    this[PreferenceKeys.KEY_TODAY_WHOLE_DAY] = updated.todayWholeDay
    this[PreferenceKeys.KEY_TODAY_HOMEWORK_PREVIEW] =
        AppSettings.nearestHomeworkPreview(updated.todayHomeworkPreview)
    this[PreferenceKeys.KEY_TODAY_SHOW_EVENTS] = updated.todayShowEvents
    this[PreferenceKeys.KEY_WEEK_START] = updated.weekStart.name
    this[PreferenceKeys.KEY_WEEK_WEEKENDS] = updated.weekShowWeekends
    this[PreferenceKeys.KEY_WEEK_LOAD] = updated.weekShowLoad
    this[PreferenceKeys.KEY_WEEK_EVENTS] = updated.weekShowEvents
    this[PreferenceKeys.KEY_WEEK_HOMEWORK] = updated.weekShowHomework
    // Names rather than ordinals: an ordinal is a number that silently
    // means something else the day somebody reorders the enum, and a
    // stored filter that turned into a different one would be a
    // calendar quietly narrowed to the wrong thing.
    this[PreferenceKeys.KEY_CALENDAR_FILTERS] = updated.calendarFilters.map { it.name }.toSet()
    this[PreferenceKeys.KEY_CALENDAR_ORDER] = updated.calendarOrder.name
    this[PreferenceKeys.KEY_DAY_RIBBON_FLOW] = updated.dayRibbonFlow.name
    this[PreferenceKeys.KEY_DAY_MODE] = updated.dayMode.name
    this[PreferenceKeys.KEY_DAY_RIBBON_SNAP] = updated.dayRibbonSnap
    this[PreferenceKeys.KEY_DAY_RIBBON_DEPTH] = updated.dayRibbonDepth
    this[PreferenceKeys.KEY_DEBUG_MODE] = updated.debugMode
    this[PreferenceKeys.KEY_ONBOARDING_DONE] = updated.onboardingDone
    this[PreferenceKeys.KEY_ALERT_LESSON] = updated.alerts.lessonSoon
    this[PreferenceKeys.KEY_ALERT_LEAD] = updated.alerts.lessonLeadMinutes
    this[PreferenceKeys.KEY_ALERT_MORNING] = updated.alerts.morningSummary
    this[PreferenceKeys.KEY_ALERT_MORNING_AT] = updated.alerts.morningAtMinutes
    this[PreferenceKeys.KEY_ALERT_HOMEWORK] = updated.alerts.homeworkReminder
    this[PreferenceKeys.KEY_ALERT_HOMEWORK_AT] = updated.alerts.homeworkAtMinutes
    this[PreferenceKeys.KEY_ALERT_CHANGES] = updated.alerts.scheduleChanges
    this[PreferenceKeys.KEY_ALERT_LESSON_DETAIL] = updated.alerts.lessonDetail.name
    // Stored as a set of decimal weekday numbers. An empty set is a
    // real answer — "не показывать ни в один день" — and DataStore
    // keeps an empty set as a present key, so it survives the read
    // below rather than falling back to all seven.
    this[PreferenceKeys.KEY_ALERT_MORNING_DAYS] = updated.alerts.morningWeekdays.map(Int::toString).toSet()
    this[PreferenceKeys.KEY_ALERT_QUIET] = updated.alerts.quietHours
    this[PreferenceKeys.KEY_ALERT_QUIET_FROM] = updated.alerts.quietFromMinutes
    this[PreferenceKeys.KEY_ALERT_QUIET_TO] = updated.alerts.quietToMinutes
    this[PreferenceKeys.KEY_ALERT_SKIP_HOLIDAYS] = updated.alerts.skipHolidays
    this[PreferenceKeys.KEY_SYNC_INTERVAL] = updated.syncIntervalMinutes
        .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES)
    this[PreferenceKeys.KEY_RIPPLE_EFFECTS] = updated.rippleEffects
    this[PreferenceKeys.KEY_THEME_REVEAL] = updated.themeReveal
    this[PreferenceKeys.KEY_AUTO_CHECK_UPDATES] = updated.autoCheckUpdates
    this[PreferenceKeys.KEY_INCLUDE_PRERELEASE] = updated.includePrerelease
    this[PreferenceKeys.KEY_NOTIFY_UPDATES] = updated.notifyNewUpdates
}

/**
 * Enums are stored by name rather than by ordinal, and unknown names fall
 * back to the default instead of throwing: reordering an enum must not be
 * able to silently change what a user already chose, and a value written by
 * a newer build must not crash an older one.
 */
internal fun Preferences.toSettings(): AppSettings = AppSettings(
    baseUrl = this[PreferenceKeys.KEY_BASE_URL]?.takeIf { it.isNotBlank() } ?: AppSettings.DEFAULT_BASE_URL,
    themeMode = ThemeMode.fromName(this[PreferenceKeys.KEY_THEME_MODE]),
    dynamicColor = this[PreferenceKeys.KEY_DYNAMIC_COLOR] ?: true,
    pitchBlack = this[PreferenceKeys.KEY_PITCH_BLACK] ?: false,
    hapticsEnabled = this[PreferenceKeys.KEY_HAPTICS] ?: true,
    hapticStrength = HapticStrength.fromName(this[PreferenceKeys.KEY_HAPTIC_STRENGTH]),
    appFont = AppFont.fromName(this[PreferenceKeys.KEY_APP_FONT]),
    language = AppLanguage.fromName(this[PreferenceKeys.KEY_LANGUAGE]),
    textScale = (this[PreferenceKeys.KEY_TEXT_SCALE] ?: AppSettings.DEFAULT_TEXT_SCALE)
        .coerceIn(AppSettings.TEXT_SCALE_RANGE),
    animations = this[PreferenceKeys.KEY_ANIMATIONS] ?: true,
    motionSpeed = (this[PreferenceKeys.KEY_MOTION_SPEED] ?: AppSettings.DEFAULT_MOTION_SPEED)
        .coerceIn(AppSettings.MOTION_SPEED_RANGE),
    swipeTabs = this[PreferenceKeys.KEY_SWIPE_TABS] ?: true,
    defaultTab = HomeTab.fromName(this[PreferenceKeys.KEY_DEFAULT_TAB]),
    tabOrder = HomeTab.order(this[PreferenceKeys.KEY_TAB_ORDER]),
    motionBlur = this[PreferenceKeys.KEY_MOTION_BLUR] ?: false,
    motionBlurScale = (this[PreferenceKeys.KEY_MOTION_BLUR_SCALE] ?: AppSettings.DEFAULT_MOTION_BLUR_SCALE)
        .coerceIn(AppSettings.MOTION_BLUR_SCALE_RANGE),
    edgeBlur = this[PreferenceKeys.KEY_EDGE_BLUR] ?: true,
    showTeacher = this[PreferenceKeys.KEY_SHOW_TEACHER] ?: true,
    widgetShowProgress = this[PreferenceKeys.KEY_WIDGET_SHOW_PROGRESS] ?: true,
    todayShowHero = this[PreferenceKeys.KEY_TODAY_SHOW_HERO] ?: true,
    todayLayout = TodayLayout.fromName(this[PreferenceKeys.KEY_TODAY_LAYOUT]),
    todayWholeDay = this[PreferenceKeys.KEY_TODAY_WHOLE_DAY] ?: false,
    todayHomeworkPreview = AppSettings.nearestHomeworkPreview(
        this[PreferenceKeys.KEY_TODAY_HOMEWORK_PREVIEW] ?: AppSettings.DEFAULT_HOMEWORK_PREVIEW,
    ),
    todayShowEvents = this[PreferenceKeys.KEY_TODAY_SHOW_EVENTS] ?: true,
    weekStart = WeekStart.fromName(this[PreferenceKeys.KEY_WEEK_START]),
    weekShowWeekends = this[PreferenceKeys.KEY_WEEK_WEEKENDS] ?: true,
    weekShowLoad = this[PreferenceKeys.KEY_WEEK_LOAD] ?: true,
    weekShowEvents = this[PreferenceKeys.KEY_WEEK_EVENTS] ?: true,
    weekShowHomework = this[PreferenceKeys.KEY_WEEK_HOMEWORK] ?: true,
    // A name this build does not know is dropped rather than throwing: a
    // downgrade after a filter was added must narrow to less, not crash on
    // every read of the settings.
    calendarFilters = this[PreferenceKeys.KEY_CALENDAR_FILTERS]
        .orEmpty()
        .mapNotNull { name -> DayFilter.entries.firstOrNull { it.name == name } }
        .toSet(),
    calendarOrder = DayOrder.entries
        .firstOrNull { it.name == this[PreferenceKeys.KEY_CALENDAR_ORDER] }
        ?: DayOrder.DATE_ASC,
    dayRibbonFlow = RibbonFlow.fromName(this[PreferenceKeys.KEY_DAY_RIBBON_FLOW]),
    dayMode = DayMode.fromName(this[PreferenceKeys.KEY_DAY_MODE]),
    dayRibbonSnap = this[PreferenceKeys.KEY_DAY_RIBBON_SNAP] ?: true,
    dayRibbonDepth = this[PreferenceKeys.KEY_DAY_RIBBON_DEPTH] ?: true,
    debugMode = this[PreferenceKeys.KEY_DEBUG_MODE] ?: false,
    // False only for a genuinely fresh install. The key arrived with the
    // introduction, so on every phone that had the app before it there is
    // no value here — and a plain `?: false` therefore promised four
    // screens of introduction to everyone who had been using the app all
    // term, the first time they signed out.
    //
    // A stored class token is the sentinel because it cannot be there by
    // accident: it is only written after a successful join, which is the
    // screen the introduction ends on. Someone holding one has been past
    // it, whatever else they have or have not touched.
    //
    // Both keys, because the membership list replaced the flat token and
    // the phones this sentinel exists for are exactly the ones that predate
    // it: reading only `KEY_TOKEN` would promise four screens of
    // introduction to every long-standing user the first time their
    // memberships were rewritten as a list.
    onboardingDone = this[PreferenceKeys.KEY_ONBOARDING_DONE]
        ?: (this[MembershipKeys.TOKEN] != null || this[MembershipKeys.SESSIONS] != null),
    alerts = AlertPreferences(
        lessonSoon = this[PreferenceKeys.KEY_ALERT_LESSON] ?: false,
        lessonLeadMinutes = this[PreferenceKeys.KEY_ALERT_LEAD] ?: AlertPreferences.DefaultLeadMinutes,
        morningSummary = this[PreferenceKeys.KEY_ALERT_MORNING] ?: false,
        morningAtMinutes = this[PreferenceKeys.KEY_ALERT_MORNING_AT] ?: AlertPreferences.DefaultMorningMinutes,
        homeworkReminder = this[PreferenceKeys.KEY_ALERT_HOMEWORK] ?: false,
        homeworkAtMinutes = this[PreferenceKeys.KEY_ALERT_HOMEWORK_AT] ?: AlertPreferences.DefaultHomeworkMinutes,
        scheduleChanges = this[PreferenceKeys.KEY_ALERT_CHANGES] ?: false,
        lessonDetail = LessonAlertDetail.fromName(this[PreferenceKeys.KEY_ALERT_LESSON_DETAIL]),
        // Anything unparseable is dropped rather than defaulted: a set that
        // half survived an older build should lose the bad entries, not the
        // days the user actually picked.
        morningWeekdays = this[PreferenceKeys.KEY_ALERT_MORNING_DAYS]
            ?.mapNotNull { it.toIntOrNull()?.takeIf { day -> day in 1..7 } }
            ?.toSet()
            ?: AlertPreferences.AllWeekdays,
        quietHours = this[PreferenceKeys.KEY_ALERT_QUIET] ?: false,
        quietFromMinutes = this[PreferenceKeys.KEY_ALERT_QUIET_FROM] ?: AlertPreferences.DefaultQuietFromMinutes,
        quietToMinutes = this[PreferenceKeys.KEY_ALERT_QUIET_TO] ?: AlertPreferences.DefaultQuietToMinutes,
        skipHolidays = this[PreferenceKeys.KEY_ALERT_SKIP_HOLIDAYS] ?: true,
    ),
    syncIntervalMinutes = (this[PreferenceKeys.KEY_SYNC_INTERVAL] ?: AppSettings.DEFAULT_SYNC_INTERVAL_MINUTES)
        .coerceAtLeast(AppSettings.MIN_SYNC_INTERVAL_MINUTES),
    rippleEffects = this[PreferenceKeys.KEY_RIPPLE_EFFECTS] ?: true,
    themeReveal = this[PreferenceKeys.KEY_THEME_REVEAL] ?: true,
    autoCheckUpdates = this[PreferenceKeys.KEY_AUTO_CHECK_UPDATES] ?: true,
    includePrerelease = this[PreferenceKeys.KEY_INCLUDE_PRERELEASE] ?: false,
    notifyNewUpdates = this[PreferenceKeys.KEY_NOTIFY_UPDATES] ?: true,
)
