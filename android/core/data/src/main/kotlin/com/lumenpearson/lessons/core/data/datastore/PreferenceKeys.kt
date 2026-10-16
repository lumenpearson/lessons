package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey

/**
 * The keys of the settings, the remembered roles and the bundle tags.
 *
 * An object of its own, internal like [MembershipKeys], because the settings
 * codec and the bundle tags read them from files of their own, and Kotlin's
 * `private` ends at the file.
 */
internal object PreferenceKeys {
    // The membership keys live in `MembershipKeys`, beside the code that reads them.

    // The diary's keys live in `DiaryKeys`, beside the reads that decode them.

    val KEY_CALENDAR_FILTERS = stringSetPreferencesKey("calendar_filters")
    val KEY_CALENDAR_ORDER = stringPreferencesKey("calendar_order")
    val KEY_DAY_RIBBON_FLOW = stringPreferencesKey("day_ribbon_flow")
    val KEY_DAY_MODE = stringPreferencesKey("day_mode")
    val KEY_DAY_RIBBON_SNAP = booleanPreferencesKey("day_ribbon_snap")
    val KEY_DAY_RIBBON_DEPTH = booleanPreferencesKey("day_ribbon_depth")
    val KEY_DEBUG_MODE = booleanPreferencesKey("settings_debug_mode")
    val KEY_ONBOARDING_DONE = booleanPreferencesKey("settings_onboarding_done")

    val KEY_ALERT_LESSON = booleanPreferencesKey("alert_lesson_soon")
    val KEY_ALERT_LEAD = intPreferencesKey("alert_lesson_lead_minutes")
    val KEY_ALERT_MORNING = booleanPreferencesKey("alert_morning")
    val KEY_ALERT_MORNING_AT = intPreferencesKey("alert_morning_at_minutes")
    val KEY_ALERT_HOMEWORK = booleanPreferencesKey("alert_homework")
    val KEY_ALERT_HOMEWORK_AT = intPreferencesKey("alert_homework_at_minutes")
    val KEY_ALERT_CHANGES = booleanPreferencesKey("alert_schedule_changes")
    val KEY_ALERT_LESSON_DETAIL = stringPreferencesKey("alert_lesson_detail")
    val KEY_ALERT_MORNING_DAYS = stringSetPreferencesKey("alert_morning_weekdays")
    val KEY_ALERT_QUIET = booleanPreferencesKey("alert_quiet_hours")
    val KEY_ALERT_QUIET_FROM = intPreferencesKey("alert_quiet_from_minutes")
    val KEY_ALERT_QUIET_TO = intPreferencesKey("alert_quiet_to_minutes")
    val KEY_ALERT_SKIP_HOLIDAYS = booleanPreferencesKey("alert_skip_holidays")
    val KEY_SCHEDULE_FINGERPRINT = stringPreferencesKey("alert_schedule_fingerprint")
    /**
     * One key per window, named after its signature.
     *
     * The two keys this replaced — `bundle_etag_signature` and
     * `bundle_etag` — are deliberately not read any more and not migrated
     * either: a tag whose window is no longer described the same way is
     * worth exactly one avoided download, and the cost of getting the
     * migration wrong is a `304` over rows this phone does not hold. They
     * are left where they are, which costs two strings.
     */
    fun bundleTagKey(signature: String) = stringPreferencesKey("bundle_etag|$signature")

    /** @see LessonsPreferences.lastRole */
    fun roleKey(classId: Long) = stringPreferencesKey("$ROLE_PREFIX$classId")

    /** What every remembered role's key starts with. */
    const val ROLE_PREFIX = "device_role|"

    /**
     * What every one of this class's tags starts with.
     *
     * The trailing separator is the whole of it: without it, forgetting
     * class 1 would take class 12 and class 100 with it, and the symptom
     * would be one avoided download turning into a full one — invisible
     * until somebody read the bill.
     */
    fun bundleTagPrefix(classId: Long) = "bundle_etag|$classId|"

    /** What every tag starts with, whatever class and year it is about. */
    const val BUNDLE_TAG_PREFIX = "bundle_etag|"

    val KEY_BASE_URL = stringPreferencesKey("settings_base_url")
    val KEY_THEME_MODE = stringPreferencesKey("settings_theme_mode")
    val KEY_DYNAMIC_COLOR = booleanPreferencesKey("settings_dynamic_color")
    val KEY_PITCH_BLACK = booleanPreferencesKey("settings_pitch_black")
    val KEY_HAPTICS = booleanPreferencesKey("settings_haptics_enabled")
    val KEY_HAPTIC_STRENGTH = stringPreferencesKey("settings_haptic_strength")
    val KEY_APP_FONT = stringPreferencesKey("settings_app_font")
    val KEY_LANGUAGE = stringPreferencesKey("settings_language")
    val KEY_TEXT_SCALE = floatPreferencesKey("settings_text_scale")
    val KEY_ANIMATIONS = booleanPreferencesKey("settings_animations")
    val KEY_MOTION_SPEED = floatPreferencesKey("settings_motion_speed")
    val KEY_SWIPE_TABS = booleanPreferencesKey("settings_swipe_tabs")
    val KEY_DEFAULT_TAB = stringPreferencesKey("settings_default_tab")
    val KEY_TAB_ORDER = stringPreferencesKey("settings_tab_order")
    val KEY_MOTION_BLUR = booleanPreferencesKey("settings_motion_blur")
    val KEY_MOTION_BLUR_SCALE = floatPreferencesKey("settings_motion_blur_scale")
    val KEY_EDGE_BLUR = booleanPreferencesKey("settings_edge_blur")
    val KEY_SHOW_TEACHER = booleanPreferencesKey("settings_show_teacher")
    val KEY_WIDGET_SHOW_PROGRESS = booleanPreferencesKey("settings_widget_show_progress")
    val KEY_TODAY_SHOW_HERO = booleanPreferencesKey("settings_today_show_hero")
    val KEY_TODAY_LAYOUT = stringPreferencesKey("settings_today_layout")
    val KEY_TODAY_WHOLE_DAY = booleanPreferencesKey("settings_today_whole_day")
    val KEY_TODAY_HOMEWORK_PREVIEW = intPreferencesKey("settings_today_homework_preview")
    val KEY_TODAY_SHOW_EVENTS = booleanPreferencesKey("settings_today_show_events")
    val KEY_WEEK_START = stringPreferencesKey("settings_week_start")
    val KEY_WEEK_WEEKENDS = booleanPreferencesKey("settings_week_weekends")
    val KEY_WEEK_LOAD = booleanPreferencesKey("settings_week_load")
    val KEY_WEEK_EVENTS = booleanPreferencesKey("settings_week_events")
    val KEY_WEEK_HOMEWORK = booleanPreferencesKey("settings_week_homework")
    val KEY_SYNC_INTERVAL = intPreferencesKey("settings_sync_interval_minutes")
    val KEY_RIPPLE_EFFECTS = booleanPreferencesKey("settings_ripple_effects")
    val KEY_THEME_REVEAL = booleanPreferencesKey("settings_theme_reveal")
    val KEY_AUTO_CHECK_UPDATES = booleanPreferencesKey("settings_auto_check_updates")
    val KEY_INCLUDE_PRERELEASE = booleanPreferencesKey("settings_include_prerelease")
    val KEY_NOTIFY_UPDATES = booleanPreferencesKey("settings_notify_updates")
}
