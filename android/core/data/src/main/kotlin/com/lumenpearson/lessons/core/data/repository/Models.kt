package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.model.HapticStrength
import com.lumenpearson.lessons.core.model.HomeTab
import com.lumenpearson.lessons.core.model.ThemeMode

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
 */
data class Session(
    val classId: Long,
    val className: String,
    val school: String?,
    val token: String,
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
    val swipeTabs: Boolean = true,
    val defaultTab: HomeTab = HomeTab.TODAY,
    val motionBlur: Boolean = false,
    val motionBlurScale: Float = DEFAULT_MOTION_BLUR_SCALE,
    val edgeBlur: Boolean = true,
    val showTeacher: Boolean = true,
    val widgetShowProgress: Boolean = true,
    val syncIntervalMinutes: Int = DEFAULT_SYNC_INTERVAL_MINUTES,
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

    /** Anything transient - no network, server down, malformed payload. Retry later. */
    data class Failed(val message: String) : SyncResult
}
