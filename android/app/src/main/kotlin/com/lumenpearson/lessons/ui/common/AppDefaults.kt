package com.lumenpearson.lessons.ui.common

import com.lumenpearson.lessons.core.data.repository.AppSettings

/**
 * The settings the UI paints with during the handful of frames between the first
 * composition and the first emission of `SettingsRepository.settings`.
 *
 * It is intentionally *not* the source of truth — :core:data owns that — it only
 * has to be close enough that the theme does not visibly flip once the stored
 * values arrive. It used to spell every field out again, which is exactly how a
 * mirror drifts: taking `AppSettings`'s own defaults means it cannot.
 */
internal val DefaultAppSettings: AppSettings = AppSettings()

/**
 * Sync cadences offered in settings.
 *
 * 15 minutes is WorkManager's floor for periodic work, so anything shorter would
 * silently be rounded up and lie to the user.
 */
internal val SyncIntervalOptionsMinutes: List<Int> = listOf(15, 30, 60, 180, 360, 720)

/**
 * Lengths a class invite code may have; the join screen validates against it.
 *
 * A range, not a fixed number. Newly issued codes are eight characters — six of
 * a 32-symbol alphabet is a small enough space to guess at — while codes handed
 * out before that are six and still work, because the server matches whatever
 * is stored. Pinning one length here would lock out one of the two.
 */
internal val ClassCodeLengths: IntRange = 4..16
