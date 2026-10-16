package com.lumenpearson.lessons.core.data.datastore

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
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every setting goes into the preferences file under its key and comes back
 * out under the same one.
 *
 * Five things, each the answer to a way that could fail quietly: an empty
 * file reads as the defaults; every field written comes back; the value
 * written here differs from the defaults in every field, held by reflection
 * so a field added to [AppSettings] or [AlertPreferences] without a key fails
 * here rather than quietly resetting on every launch; the name each is
 * stored under is pinned to what it has always been, because a rename
 * round-trips fine on its own and only resets every user's choice on the
 * next write; and each switch comes back on its own key, because with only
 * two values a boolean crossed with another holding the same one passes
 * unnoticed otherwise.
 *
 * Through the store's internal constructor rather than through the two
 * halves themselves, so this keeps holding the whole codec however it is
 * split between files from here on.
 */
class SettingsRoundTripTest {

    private val everyFieldChanged = AppSettings(
        baseUrl = "https://lessons.example",
        themeMode = ThemeMode.DARK,
        dynamicColor = false,
        pitchBlack = true,
        hapticsEnabled = false,
        hapticStrength = HapticStrength.CLICK,
        appFont = AppFont.SYSTEM,
        language = AppLanguage.ENGLISH,
        textScale = 1.15f,
        animations = false,
        motionSpeed = 1.5f,
        swipeTabs = false,
        defaultTab = HomeTab.WEEK,
        tabOrder = listOf(HomeTab.HOMEWORK, HomeTab.TODAY, HomeTab.WEEK),
        motionBlur = true,
        motionBlurScale = 2f,
        edgeBlur = false,
        showTeacher = false,
        widgetShowProgress = false,
        todayShowHero = false,
        todayLayout = TodayLayout.HOMEWORK_FIRST,
        todayWholeDay = true,
        todayHomeworkPreview = 5,
        todayShowEvents = false,
        weekStart = WeekStart.TODAY,
        weekShowWeekends = false,
        weekShowLoad = false,
        weekShowEvents = false,
        weekShowHomework = false,
        calendarFilters = setOf(DayFilter.HAS_HOMEWORK, DayFilter.HAS_EVENTS),
        calendarOrder = DayOrder.DATE_DESC,
        dayRibbonFlow = RibbonFlow.UPWARD,
        dayMode = DayMode.LIST,
        dayRibbonSnap = false,
        dayRibbonDepth = false,
        syncIntervalMinutes = 30,
        debugMode = true,
        onboardingDone = true,
        alerts = AlertPreferences(
            lessonSoon = true,
            lessonLeadMinutes = 15,
            lessonDetail = LessonAlertDetail.SUBJECT,
            morningSummary = true,
            morningAtMinutes = 6 * 60 + 30,
            morningWeekdays = setOf(1, 3, 5),
            homeworkReminder = true,
            homeworkAtMinutes = 19 * 60,
            scheduleChanges = true,
            quietHours = true,
            quietFromMinutes = 23 * 60,
            quietToMinutes = 6 * 60,
            skipHolidays = false,
        ),
        rippleEffects = false,
        themeReveal = false,
        autoCheckUpdates = false,
        includePrerelease = true,
        notifyNewUpdates = false,
    )

    // A key whose name changes is read as absent, so every user's choice
    // silently returns to its default the next time the settings are
    // written — nothing else fails, and nothing logs it. Pinned to the
    // literal strings at 014b1c0, before any of them moved into
    // PreferenceKeys, so a rename anywhere after that is caught here rather
    // than by somebody's settings resetting in production.
    private val namesBeforeTheMove = listOf(
        "alert_homework",
        "alert_homework_at_minutes",
        "alert_lesson_detail",
        "alert_lesson_lead_minutes",
        "alert_lesson_soon",
        "alert_morning",
        "alert_morning_at_minutes",
        "alert_morning_weekdays",
        "alert_quiet_from_minutes",
        "alert_quiet_hours",
        "alert_quiet_to_minutes",
        "alert_schedule_changes",
        "alert_skip_holidays",
        "calendar_filters",
        "calendar_order",
        "day_mode",
        "day_ribbon_depth",
        "day_ribbon_flow",
        "day_ribbon_snap",
        "settings_animations",
        "settings_app_font",
        "settings_auto_check_updates",
        "settings_base_url",
        "settings_debug_mode",
        "settings_default_tab",
        "settings_dynamic_color",
        "settings_edge_blur",
        "settings_haptic_strength",
        "settings_haptics_enabled",
        "settings_include_prerelease",
        "settings_language",
        "settings_motion_blur",
        "settings_motion_blur_scale",
        "settings_motion_speed",
        "settings_notify_updates",
        "settings_onboarding_done",
        "settings_pitch_black",
        "settings_ripple_effects",
        "settings_show_teacher",
        "settings_swipe_tabs",
        "settings_sync_interval_minutes",
        "settings_tab_order",
        "settings_text_scale",
        "settings_theme_mode",
        "settings_theme_reveal",
        "settings_today_homework_preview",
        "settings_today_layout",
        "settings_today_show_events",
        "settings_today_show_hero",
        "settings_today_whole_day",
        "settings_week_events",
        "settings_week_homework",
        "settings_week_load",
        "settings_week_start",
        "settings_week_weekends",
        "settings_widget_show_progress",
    )

    @Test
    fun `a file that holds nothing reads as the defaults`() = runBlocking {
        assertEquals(AppSettings(), LessonsPreferences(MemoryStore(), testVault()).currentSettings())
    }

    @Test
    fun `every setting comes back as it was written`() = runBlocking {
        val store = MemoryStore()
        LessonsPreferences(store, testVault()).updateSettings { everyFieldChanged }

        // Read by a second instance over what the first wrote, so nothing can
        // have come back from memory rather than from the file.
        val reader = LessonsPreferences(MemoryStore(store.value), testVault())

        assertEquals(everyFieldChanged, reader.currentSettings())
        assertEquals(everyFieldChanged, reader.settings.first())
    }

    @Test
    fun `the settings written differ from the defaults in every field`() {
        assertEquals(emptyList<String>(), unchanged(everyFieldChanged, AppSettings()))
        assertEquals(emptyList<String>(), unchanged(everyFieldChanged.alerts, AlertPreferences()))
    }

    @Test
    fun `the preferences file keeps the names it has always had`() = runBlocking {
        val store = MemoryStore()
        LessonsPreferences(store, testVault()).updateSettings { everyFieldChanged }

        assertEquals(namesBeforeTheMove, store.value.asMap().keys.map { it.name }.sorted())
    }

    @Test
    fun `each switch comes back on its own key`() = runBlocking {
        // Two switches crossed in either half — a write that lands under the
        // other one's key, or a read that looks at it — round-trip
        // unnoticed whenever both hold the same value, and with only two
        // values that is most pairs. Flipping one switch at a time off a
        // value that already differs from the default in every field is
        // what makes a crossing visible.
        val broken = mutableListOf<String>()

        for (field in booleanFieldsOf(AppSettings::class.java)) {
            val mutated = everyFieldChanged.copy()
            field.isAccessible = true
            field.setBoolean(mutated, !field.getBoolean(mutated))
            if (!settingsRoundTrip(mutated)) broken += field.name
        }

        for (field in booleanFieldsOf(AlertPreferences::class.java)) {
            val mutatedAlerts = everyFieldChanged.alerts.copy()
            field.isAccessible = true
            field.setBoolean(mutatedAlerts, !field.getBoolean(mutatedAlerts))
            if (!settingsRoundTrip(everyFieldChanged.copy(alerts = mutatedAlerts))) broken += field.name
        }

        assertEquals(emptyList<String>(), broken)
    }

    /** Whether [settings] comes back unchanged through a store of its own. */
    private suspend fun settingsRoundTrip(settings: AppSettings): Boolean {
        val store = MemoryStore()
        LessonsPreferences(store, testVault()).updateSettings { settings }
        return LessonsPreferences(MemoryStore(store.value), testVault()).currentSettings() == settings
    }

    /**
     * The fields [value] holds as [default] does. By reflection, so that a field
     * added later is asked about without anybody remembering to name it here.
     */
    private fun unchanged(value: Any, default: Any): List<String> =
        value.javaClass.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .onEach { it.setAccessible(true) }
            .filter { it.get(value) == it.get(default) }
            .map { it.name }

    /**
     * Every non-static `Boolean` field of [type]. By reflection, so a switch
     * added later is covered here without anybody remembering to name it.
     *
     * Checked by [java.lang.Boolean.TYPE] rather than by the boxed class:
     * Kotlin's non-null `Boolean` compiles to the JVM primitive `boolean`.
     */
    private fun booleanFieldsOf(type: Class<*>): List<Field> =
        type.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .filter { it.type == java.lang.Boolean.TYPE }
}
