package com.lumenpearson.lessons.core.data.developer

/**
 * What GitHub says the signed-in account may do in this project's repository:
 * the `permissions` object of `GET /repos/{owner}/{repo}`, which describes the
 * caller and nobody else. Asking about the caller rather than listing the
 * collaborators is the point — the list needs push access to read, and the
 * question is only ever «may *this* account open the tools».
 */
data class RepositoryPermissions(
    val admin: Boolean = false,
    val maintain: Boolean = false,
    val push: Boolean = false,
)

/** The strongest of GitHub's grants a developer of this project holds. */
enum class DeveloperRole { ADMIN, MAINTAIN, WRITE }

/**
 * Whether [permissions] make a developer of this project, and as what.
 *
 * Push and above, because push is what deploys: a merge to `main` deploys the
 * server, and `apk.yml` needs write access to be run at all. Triage and read do
 * not count — read is what every GitHub account has on a public repository.
 */
fun developerRoleOf(permissions: RepositoryPermissions): DeveloperRole? = when {
    permissions.admin -> DeveloperRole.ADMIN
    permissions.maintain -> DeveloperRole.MAINTAIN
    permissions.push -> DeveloperRole.WRITE
    else -> null
}

/**
 * GitHub's last answer about one account, as the phone keeps it.
 *
 * @property role `null` when GitHub said the account is not a developer — kept
 *   rather than dropped, so a refusal is not asked again on every visit.
 */
data class DeveloperVerdict(
    val login: String,
    val role: DeveloperRole?,
    val checkedAtMillis: Long,
)

/**
 * How long a verdict stands before GitHub is asked again: a day, so a developer
 * who loses the permission loses the tools by tomorrow, and one who keeps it is
 * asked once a day at most.
 */
const val VerdictLifetimeMillis: Long = 24L * 60 * 60 * 1000

/**
 * Whether this verdict is still GitHub's answer about [login] at [nowMillis].
 *
 * Every way it goes stale is here: another account signed in since, a day gone
 * by — or a clock moved back past the moment it was asked, which reads as stale
 * rather than as an answer from the future.
 */
fun DeveloperVerdict.standsFor(login: String?, nowMillis: Long): Boolean =
    login != null &&
        this.login.equals(login, ignoreCase = true) &&
        nowMillis - checkedAtMillis in 0..VerdictLifetimeMillis

/**
 * Where the gate stands for the account signed in to GitHub on this phone.
 *
 * Asked by the developer page and by everything the mode switches on, through
 * [DeveloperState.tools]: nothing is on unless this is [Granted].
 */
sealed interface DeveloperAccess {

    /** No GitHub account on this phone. */
    data object SignedOut : DeveloperAccess

    /** GitHub is being asked, and no standing verdict says yes meanwhile. */
    data class Checking(val login: String) : DeveloperAccess

    data class Granted(
        val login: String,
        val role: DeveloperRole,
        val checkedAtMillis: Long,
    ) : DeveloperAccess

    /** GitHub said this account cannot push to the repository. */
    data class Denied(val login: String) : DeveloperAccess

    /**
     * No verdict stands for this account: GitHub has not been asked yet, or
     * could not be — [reason] says why, when there was one.
     */
    data class Unknown(val login: String, val reason: String?) : DeveloperAccess
}

/**
 * The gate, from what is stored and what is happening.
 *
 * A standing yes wins over a check in flight, so the tools do not blink off
 * while the daily re-check runs; a standing no wins over nothing else.
 */
fun accessOf(
    verdict: DeveloperVerdict?,
    login: String?,
    checking: Boolean,
    failure: String?,
    nowMillis: Long,
): DeveloperAccess {
    if (login == null) return DeveloperAccess.SignedOut
    val standing = verdict?.takeIf { it.standsFor(login, nowMillis) }
    val role = standing?.role
    return when {
        standing != null && role != null -> DeveloperAccess.Granted(login, role, standing.checkedAtMillis)
        checking -> DeveloperAccess.Checking(login)
        standing != null -> DeveloperAccess.Denied(login)
        else -> DeveloperAccess.Unknown(login, failure)
    }
}

/** What the mode can switch on. Each is off until it is chosen, and all are off without access. */
enum class DeveloperTool {
    /** Every request the app makes, recorded without its secrets; see `NetworkLog`. */
    NETWORK_LOG,

    /** What the app did, in order; see `ActivityLog`. */
    ACTIVITY_LOG,

    /** A layout grid over the whole app. */
    LAYOUT_GRID,

    /** Every string the app draws, longer, so clipping shows before a translation finds it. */
    STRETCHED_STRINGS,

    /** A text scale past the largest the settings offer. */
    LARGE_TEXT,
}

/**
 * The mode as the rest of the app reads it.
 *
 * @property revealed the section has been found (seven taps on the version)
 *   and is listed in the settings. Revealing grants nothing.
 * @property chosen what the developer switched on, whether or not it is in force.
 */
data class DeveloperState(
    val revealed: Boolean = false,
    val access: DeveloperAccess = DeveloperAccess.SignedOut,
    val chosen: Set<DeveloperTool> = emptySet(),
) {
    val granted: Boolean get() = access is DeveloperAccess.Granted

    /** What is actually on: what was chosen, while the access stands, and nothing otherwise. */
    val tools: Set<DeveloperTool> get() = if (granted) chosen else emptySet()
}
