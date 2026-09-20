package com.lumenpearson.lessons.core.data.github

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * What every call to GitHub has in common: the addresses, the headers, the
 * client, and the name this build gives itself.
 *
 * Two repositories talk to GitHub — the update check and the sign-in — and
 * neither may borrow the school server's stack. The client that
 * [NetworkModule][com.lumenpearson.lessons.core.data.network.NetworkModule]
 * builds carries an interceptor that rewrites the host of every request to the
 * configured server address, so a call to `api.github.com` sent through it
 * would arrive at the school. This one has no interceptors at all.
 */
internal object GithubApi {

    private const val TAG = "Lessons"

    const val OWNER = "lumenpearson"
    const val REPO = "lessons"
    const val API_BASE = "https://api.github.com"
    const val WEB_BASE = "https://github.com"
    const val RAW_BASE = "https://raw.githubusercontent.com"

    /** Where a file is read from: the branch a merge deploys, not a tag. */
    private const val DEFAULT_BRANCH = "main"

    /**
     * The REST API's own media type and the dated version that pins response
     * shapes. GitHub asks every client for both; without the version header it
     * is free to change a shape under an app that has already shipped.
     */
    private const val ACCEPT = "application/vnd.github+json"
    private const val API_VERSION = "2022-11-28"

    /** What the debug build type appends to `versionName`; see [installedVersion]. */
    private const val DEBUG_SUFFIX = "-debug"

    val JSON_MEDIA_TYPE: MediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Tolerant for the same reason the school server's parser is: GitHub adds
     * fields whenever it likes, and an update check that fails because a release
     * grew a new attribute would be failing at its one job.
     */
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        explicitNulls = false
    }

    /**
     * One client for both repositories, built on first use. Shorter timeouts
     * than the school server gets: GitHub is either quick or down, and the
     * automatic check runs at app start, where a slow failure is the one that
     * gets noticed.
     */
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** `/releases`, `/issues` — a path under this project's repository. */
    fun repoUrl(path: String): String = "$API_BASE/repos/$OWNER/$REPO$path"

    /**
     * A file's bytes, from the default branch.
     *
     * Not the REST API: `/contents` answers with base64 inside JSON and counts
     * against a rate limit that is sixty requests an hour for a caller with no
     * token. `raw` serves the file itself, is cached by GitHub's CDN, and needs
     * no headers beyond the user agent — which matters, because the one caller
     * is the documentation and it asks on every visit to the screen.
     */
    fun rawUrl(path: String): String = "$RAW_BASE/$OWNER/$REPO/$DEFAULT_BRANCH/$path"

    /**
     * GitHub rejects requests with no `User-Agent` and asks that it name the
     * app. Carrying the version too means a misbehaving build can be told apart
     * in the logs on the other side.
     */
    fun userAgent(installedVersion: String): String = "lessons-android/$installedVersion"

    /** A request carrying the headers every REST API call needs. */
    fun apiRequest(url: String, userAgent: String): Request.Builder = Request.Builder()
        .url(url)
        .header("Accept", ACCEPT)
        .header("X-GitHub-Api-Version", API_VERSION)
        .header("User-Agent", userAgent)

    /**
     * The installed `versionName`, as the update check should read it.
     *
     * The debug build type appends `-debug`, and semver reads a `-` suffix as a
     * pre-release — older than the same numbers without it. Left in place, every
     * debug install of 0.2.0 would be offered the 0.2.0 release, forever. So the
     * suffix comes off here, once, before anything compares it.
     *
     * A build with no version name reads as "0", which makes every release an
     * upgrade — the honest verdict for a build nobody versioned.
     */
    fun installedVersion(context: Context): String {
        val raw = try {
            val manager = context.packageManager
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                manager.getPackageInfo(context.packageName, 0)
            }
            info.versionName
        } catch (failure: Exception) {
            // Our own package cannot be missing, but the call is a binder round
            // trip and the manager has been known to be unavailable during a
            // package update. "0" keeps the check alive rather than the app dead.
            Log.w(TAG, "Could not read the installed version", failure)
            null
        }
        return raw?.trim()?.removeSuffix(DEBUG_SUFFIX)?.takeIf { it.isNotEmpty() } ?: "0"
    }
}
