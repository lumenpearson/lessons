package com.lumenpearson.lessons.ui.settings

import com.lumenpearson.lessons.core.data.repository.AppSettings
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceFlow
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.GithubAccount
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.ui.common.DefaultAppSettings
import com.lumenpearson.lessons.ui.common.SyncMessage

/**
 * @property session `null` right after signing out, for the frame before the app
 *   shell navigates away.
 * @property isRefreshing an "обновить сейчас" run is in flight.
 */
data class SettingsUiState(
    val settings: AppSettings = DefaultAppSettings,
    val session: Session? = null,
    /**
     * Every class this device has joined, the one in [session] among them.
     *
     * Kept beside [session] rather than replacing it because the two answer
     * different questions: the rest of the screen wants "which class am I
     * looking at", and only the class group wants "which could I look at".
     */
    val sessions: List<Session> = emptyList(),
    val isRefreshing: Boolean = false,
    val message: SyncMessage? = null,
    /** Where the last update check stands; see [UpdateCheck]. */
    val update: UpdateCheck = UpdateCheck.Idle,
    /** The signed-in GitHub account, or `null`. */
    val github: GithubAccount? = null,
    /** Where the Telegram link of this phone stands; see [DeviceLinkState]. */
    val deviceLink: DeviceLinkState = DeviceLinkState.Idle,
    /** The role the server gave this class last time, from disk; see [effectiveRole]. */
    val cachedRole: ClassRole? = null,
    /** Whether this build can sign in at all; the row hides otherwise. */
    val githubConfigured: Boolean = false,
    /** The device flow, for the sign-in sheet. */
    val signIn: DeviceFlow = DeviceFlow.Idle,
    /** An issue is on its way to GitHub. */
    val isFilingIssue: Boolean = false,
    /**
     * The release sheet is up. Held here rather than in a screen because two
     * places raise it — the updates page on a tap, the shell when the check at
     * launch finds something — and one host in the shell shows it for both.
     */
    val showReleaseSheet: Boolean = false,
    /** How the bound server answered, for the about card's badge. */
    val serverStatus: ServerStatus = ServerStatus.Checking,
)

/**
 * The Telegram link as the class page sees it.
 *
 * [Loading] and [Failed] carry the last good answer so a refresh that is in
 * flight, or one that failed, does not blank a card that was showing a code
 * the user may be typing into the bot right now.
 */
sealed interface DeviceLinkState {
    /** Nobody has asked yet; the page asks when it opens. */
    data object Idle : DeviceLinkState

    data class Loading(override val known: DeviceLink?) : DeviceLinkState

    data class Ready(val link: DeviceLink) : DeviceLinkState

    data class Failed(val cause: Throwable, override val known: DeviceLink?) : DeviceLinkState

    /**
     * The last good answer, whichever state is carrying it.
     *
     * Asked here rather than at each call site, because each call site asked
     * `as? Ready` and therefore threw the code away on exactly the press that
     * exists to recover from a failure: «Повторить» on a failed check blanked
     * the card, and a phone still without signal never got it back. The card
     * was showing a code somebody was halfway through typing into the bot.
     */
    val known: DeviceLink?
        get() = when (this) {
            Idle -> null
            is Loading -> known
            is Ready -> link
            is Failed -> known
        }
}

/**
 * The role the server last reported, whatever the state is doing right now.
 *
 * A refresh in flight and a refresh that failed both still know who this phone
 * is, from the answer before — which is what keeps the management page from
 * flickering out of the list every time the page reloads, or the moment the
 * train goes into a tunnel. It is `null` only when nothing has ever been
 * answered, or when the phone is tied to no account at all.
 */
val DeviceLinkState.role: ClassRole?
    get() = when (this) {
        DeviceLinkState.Idle -> null
        is DeviceLinkState.Loading -> known?.role
        is DeviceLinkState.Ready -> link.role
        is DeviceLinkState.Failed -> known?.role
    }

/**
 * The role to draw with: the server's answer once this launch has one, and
 * until then the one it gave last time for this class.
 *
 * What decides whether the settings root has its debug button, and the page
 * its «Управление классом» row. Read from [DeviceLinkState.role] alone, both
 * started every launch as «no role» and waited for `/me` — a moment late when
 * it answered, and until the page was opened again when it did not (#228). A
 * fresh answer wins over the remembered one, «no role» included.
 */
val SettingsUiState.effectiveRole: ClassRole?
    get() = when (val link = deviceLink) {
        is DeviceLinkState.Ready -> link.link.role
        else -> link.role ?: cachedRole
    }
