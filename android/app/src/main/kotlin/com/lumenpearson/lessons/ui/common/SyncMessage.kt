package com.lumenpearson.lessons.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.SyncResult

/**
 * A sync outcome the user should see, in a form a view model is allowed to hold.
 *
 * View models must not touch resources — a rotated device would otherwise keep
 * showing the message in the language it was created in — so the failure is
 * carried as data and turned into text at the call site by [asText].
 */
sealed interface SyncMessage {

    /** The class or the token is no longer valid; the user has to join again. */
    data object Unauthorised : SyncMessage

    /**
     * No server address is stored, so nothing was even attempted.
     *
     * Its own case rather than a [Failed] string because it is the one failure
     * the user can fix in ten seconds, and because the message has to name the
     * setting rather than repeat a DNS error about a host they never typed.
     */
    data object NotConfigured : SyncMessage

    /**
     * Anything else: no network, a 5xx, a malformed payload.
     *
     * @property detail server-provided text, shown verbatim when present because
     *   it is usually more specific than anything this app could invent.
     */
    data class Failed(val detail: String?) : SyncMessage
}

/**
 * Maps a repository result onto something to show, or `null` when the sync
 * succeeded and the fresh data on screen is the only feedback needed.
 */
fun SyncResult.toMessageOrNull(): SyncMessage? = when (this) {
    SyncResult.Success -> null
    SyncResult.Unauthorised -> SyncMessage.Unauthorised
    SyncResult.NotConfigured -> SyncMessage.NotConfigured
    is SyncResult.Failed -> SyncMessage.Failed(message)
}

/** Localizes a [SyncMessage] at the point where it is actually rendered. */
@Composable
fun SyncMessage.asText(): String = when (this) {
    SyncMessage.Unauthorised -> stringResource(R.string.sync_error_unauthorised)
    SyncMessage.NotConfigured -> stringResource(R.string.sync_error_not_configured)
    is SyncMessage.Failed ->
        detail?.let { stringResource(R.string.sync_error_failed, it) }
            ?: stringResource(R.string.sync_error_generic)
}
