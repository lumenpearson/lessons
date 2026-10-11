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
import java.lang.reflect.Modifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Every setting goes into the preferences file under its key and comes back
 * out under the same one.
 *
 * The write half and the read half each name fifty-six keys, and a setting
 * one of them forgets fails nothing on its own: it is written and never read
 * back, or read and always its default. So the value written here differs
 * from the defaults in every field, and the last test holds that by
 * reflection — a field added to [AppSettings] or [AlertPreferences] without a
 * key then fails here instead of quietly resetting on every launch.
 *
 * Through the store's internal constructor rather than through the two halves
 * themselves, so that it ran green on the code before they were moved side by
 * side, and proves the move.
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
}
