package com.lumenpearson.lessons.ui.common

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lumenpearson.lessons.core.data.repository.AppSettings

/**
 * The settings the UI paints with during the handful of frames between the first
 * composition and the first emission of `SettingsRepository.settings`.
 *
 * It is intentionally *not* the source of truth — :core:data owns that — it only
 * has to be close enough that the theme does not visibly flip once the stored
 * values arrive. Mirror any change to the data layer's defaults here.
 */
internal val DefaultAppSettings: AppSettings = AppSettings(
    baseUrl = "",
    dynamicColor = true,
    pitchBlack = false,
    showTeacher = true,
    widgetShowProgress = true,
    syncIntervalMinutes = 60,
)

/**
 * Sync cadences offered in settings.
 *
 * 15 minutes is WorkManager's floor for periodic work, so anything shorter would
 * silently be rounded up and lie to the user.
 */
internal val SyncIntervalOptionsMinutes: List<Int> = listOf(15, 30, 60, 180, 360, 720)

/** Length of a class invite code; the join screen validates against it. */
internal const val ClassCodeLength: Int = 6

/**
 * Room a scrolling screen leaves at its bottom for the floating navigation bar.
 *
 * The bar is drawn over the content rather than beside it, so nothing else knows
 * how tall it is; this is that number, in one place.
 */
internal val FloatingBarSpace: Dp = 96.dp
