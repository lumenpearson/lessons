package com.lumenpearson.lessons.ui.common

import androidx.compose.runtime.Composable
import com.lumenpearson.lessons.R
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.designsystem.text.correctedString

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
     * The address is plain `http://` and this build speaks https only (#202).
     * Not [NotConfigured]: the address is there, and a sentence saying it is
     * not would send the reader looking for a field they have already filled.
     */
    data object NeedsHttps : SyncMessage

    /**
     * Anything else: no network, a 5xx, a malformed payload.
     *
     * No detail, on purpose. This carried one, documented as the server's own
     * words, and nothing ever filled it with those: the repository's failures
     * are OkHttp's messages, «Server returned HTTP 502» and exception class
     * names, and a pull-to-refresh with the server down read «Не удалось
     * обновить: Failed to connect to /127.0.0.1:8000» (#177). What a user can
     * do about any of them is the same — try again later — and the sentence
     * written for that says so. The repository still keeps its message for
     * whoever reads a log.
     */
    data object Failed : SyncMessage

    /** A bug report did not reach GitHub. Not a sync, but the same snackbar. */
    data object IssueFailed : SyncMessage
}

/**
 * Maps a repository result onto something to show, or `null` when the sync
 * succeeded and the fresh data on screen is the only feedback needed.
 */
fun SyncResult.toMessageOrNull(): SyncMessage? = when (this) {
    SyncResult.Success -> null
    SyncResult.Unauthorised -> SyncMessage.Unauthorised
    SyncResult.NotConfigured -> SyncMessage.NotConfigured
    SyncResult.NeedsHttps -> SyncMessage.NeedsHttps
    is SyncResult.Failed -> SyncMessage.Failed
}

/** Localizes a [SyncMessage] at the point where it is actually rendered. */
@Composable
fun SyncMessage.asText(): String = when (this) {
    SyncMessage.Unauthorised -> correctedString(R.string.sync_error_unauthorised)
    SyncMessage.NotConfigured -> correctedString(R.string.sync_error_not_configured)
    SyncMessage.NeedsHttps -> correctedString(R.string.sync_error_needs_https)
    SyncMessage.Failed -> correctedString(R.string.sync_error_generic)
    SyncMessage.IssueFailed -> correctedString(R.string.issue_error_failed)
}
