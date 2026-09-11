package com.lumenpearson.lessons.core.data.repository

import java.time.Instant
import kotlinx.coroutines.flow.StateFlow

/**
 * One published release, as much of it as the update sheet needs.
 *
 * @property tag the git tag, "v0.2.0". What the version comparison reads.
 * @property name the release title, which the workflow sets to the tag.
 * @property notes the release body as GitHub stores it: Markdown, possibly
 *   empty. The sheet renders headings and bullets and leaves the rest alone.
 * @property htmlUrl the release page, for "посмотреть на GitHub".
 * @property publishedAt `null` for a draft, which the checker never surfaces.
 * @property isPreRelease GitHub's own flag, not inferred from the tag.
 * @property apkUrl the direct download of the first `.apk` asset, or `null`
 *   when a release has none — in which case the sheet can only offer the page.
 * @property apkBytes size of that asset, for the download label.
 */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val htmlUrl: String,
    val publishedAt: Instant?,
    val isPreRelease: Boolean,
    val apkUrl: String?,
    val apkBytes: Long?,
)

/**
 * Where the update check stands.
 *
 * A state rather than a one-shot result because two screens read it — the
 * settings page shows a spinner and a verdict, the app shell raises the sheet —
 * and they must agree.
 */
sealed interface UpdateCheck {

    /** Never run this session. */
    data object Idle : UpdateCheck

    data object Checking : UpdateCheck

    /**
     * Nothing newer. [current] is the release matching the installed version,
     * when GitHub has one, so the sheet can show its notes as "what you have".
     */
    data class UpToDate(val current: ReleaseInfo?) : UpdateCheck

    data class Available(val release: ReleaseInfo) : UpdateCheck

    /** Network or parsing trouble. [reason] is for the debug log, not the user. */
    data class Failed(val reason: String?) : UpdateCheck
}

/**
 * The update checker.
 *
 * Talks to the GitHub Releases API for this project and compares against the
 * installed version. Nothing here downloads or installs; the sheet hands the
 * APK URL to the browser, which is where the package installer takes over.
 */
interface UpdateRepository {

    /** The installed `versionName`, with build suffixes stripped. */
    val installedVersion: String

    val state: StateFlow<UpdateCheck>

    /**
     * Runs a check and publishes the outcome to [state].
     *
     * @param includePrerelease whether a release GitHub marks as pre-release
     *   may be offered. Drafts are never offered either way.
     */
    suspend fun check(includePrerelease: Boolean): UpdateCheck

    /**
     * The tag the user said "later" to, so the automatic check does not raise
     * the same sheet on every launch. A manual check ignores it.
     */
    val dismissedTag: StateFlow<String?>

    suspend fun dismiss(tag: String)

    /**
     * When the last check finished, epoch millis, or `null` for never. The app
     * shell reads it to keep the automatic check to once a day: GitHub's
     * unauthenticated allowance is sixty requests an hour per address, and a
     * classroom of phones behind one router shares that address.
     */
    suspend fun lastCheckMillis(): Long?
}
