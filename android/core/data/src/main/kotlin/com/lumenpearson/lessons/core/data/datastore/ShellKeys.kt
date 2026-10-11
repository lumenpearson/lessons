package com.lumenpearson.lessons.core.data.datastore

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import com.lumenpearson.lessons.core.data.repository.ShellModeSource
import com.lumenpearson.lessons.core.data.repository.ShellState
import com.lumenpearson.lessons.core.data.repository.shellModeOf

/**
 * The keys of the app shell's own state, which belongs to neither account.
 *
 * `onboarding_held` is kept until onboarding releases it, not cleared by a
 * sign-out of either account: it describes the flow on screen, and only the
 * flow — or a cold start that finds nothing to finish — may end it.
 */
internal object ShellKeys {
    val ONBOARDING_HELD = booleanPreferencesKey("onboarding_held")
}

/**
 * [shellModeOf] and the hold, from one emission; see [ShellModeSource].
 *
 * The class is the one whose token opens: a phone whose key is gone holds
 * sealed tokens it cannot use, and is in no class — so it opens on the join
 * screen, or on the diary's, exactly as a phone that never had the token would.
 */
internal fun Preferences.shellState(vault: TokenVault): ShellState = ShellState(
    mode = shellModeOf(openActiveMembership(vault), toDiaryTarget()),
    held = this[ShellKeys.ONBOARDING_HELD] ?: false,
)
