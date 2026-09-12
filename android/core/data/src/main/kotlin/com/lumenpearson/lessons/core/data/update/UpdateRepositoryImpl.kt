package com.lumenpearson.lessons.core.data.update

import android.content.Context
import android.util.Log
import com.lumenpearson.lessons.core.data.github.GithubApi
import com.lumenpearson.lessons.core.data.repository.UpdateCheck
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import com.lumenpearson.lessons.core.model.AppVersion
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer

/**
 * The update check against this project's GitHub releases.
 *
 * Internal because the container hands out the interface; the only thing a
 * caller could do with the class itself is build a second one, and there must
 * be one: [state] is the agreement between the settings page and the app shell.
 */
internal class UpdateRepositoryImpl(context: Context) : UpdateRepository {

    private val appContext: Context = context.applicationContext
    private val preferences = UpdatePreferences(appContext)

    override val installedVersion: String = GithubApi.installedVersion(appContext)

    private val userAgent: String = GithubApi.userAgent(installedVersion)

    /**
     * Where a check runs, and why it is not the caller's scope: the automatic
     * check is started by the app shell and the settings page may ask again
     * while it is in flight — or the shell may be gone by the time GitHub
     * answers. A check that belonged to whoever started it would be cancelled
     * with them, and the second caller would be left awaiting nothing. So
     * checks belong to the repository, live as long as the process, and callers
     * only ever await one.
     */
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, error ->
            // Nothing launched here is allowed to throw; this is the last net
            // under a bug, and a logged line beats a dead process.
            Log.w(TAG, "Unhandled failure in the update checker", error)
        },
    )

    private val mutableState = MutableStateFlow<UpdateCheck>(UpdateCheck.Idle)
    override val state: StateFlow<UpdateCheck> = mutableState.asStateFlow()

    /**
     * Mirrors the stored tag. A `stateIn` would do the same, except at the one
     * moment that matters: the app shell compares against this the instant a
     * verdict lands, and on a cold start the first read of the file may still
     * be in flight then. So [perform] reads the stored value itself just before it
     * publishes, and [dismiss] writes the mirror synchronously.
     */
    private val dismissed = MutableStateFlow<String?>(null)
    override val dismissedTag: StateFlow<String?> = dismissed.asStateFlow()

    private val gate = Mutex()
    private var inFlight: Deferred<UpdateCheck>? = null

    init {
        scope.launch { preferences.dismissedTag.collect { dismissed.value = it } }
    }

    /**
     * One request at a time. A second caller during a check gets the first
     * check's verdict — including its [includePrerelease], which is a setting
     * that changes rarely and never in the second a check takes.
     */
    override suspend fun check(includePrerelease: Boolean): UpdateCheck {
        val task = gate.withLock {
            val running = inFlight?.takeIf { it.isActive }
            if (running != null) return@withLock running
            // Published before the request starts, so a screen that reads
            // [state] right after calling this already sees the spinner.
            mutableState.value = UpdateCheck.Checking
            scope.async { perform(includePrerelease) }.also { inFlight = it }
        }
        return task.await()
    }

    override suspend fun dismiss(tag: String) {
        try {
            preferences.writeDismissedTag(tag)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            // Not fatal: the sheet comes back next launch, which is the worst
            // that a full disk can do here.
            Log.w(TAG, "Could not remember the dismissed release", failure)
        }
        dismissed.value = tag
    }

    override suspend fun lastCheckMillis(): Long? = try {
        preferences.lastCheckMillis()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: Exception) {
        Log.w(TAG, "Could not read when the last check ran", failure)
        null
    }

    /** The check itself, on the repository's own scope; see [check]. */
    private suspend fun perform(includePrerelease: Boolean): UpdateCheck {
        val verdict = try {
            verdict(fetchReleases(), includePrerelease)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            Log.w(TAG, "Update check failed", failure)
            UpdateCheck.Failed(failure.message ?: failure::class.java.simpleName)
        }
        try {
            // Recorded for a failure too. The shell's once-a-day rule guards an
            // allowance that a classroom shares, and a request GitHub refused
            // spent it exactly as much as one it answered. A phone that was
            // merely offline pays a day's delay for the two being
            // indistinguishable; the manual check is unaffected.
            preferences.writeLastCheckMillis(System.currentTimeMillis())
            dismissed.value = preferences.dismissedTag()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Log.w(TAG, "Could not record the update check", failure)
        }
        mutableState.value = verdict
        return verdict
    }

    private suspend fun fetchReleases(): List<ReleaseDto> = withContext(Dispatchers.IO) {
        val request = GithubApi.apiRequest(RELEASES_URL, userAgent).get().build()
        GithubApi.client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("GitHub returned HTTP ${response.code}")
            val body = response.body?.string() ?: error("GitHub returned no body")
            GithubApi.json.decodeFromString(ListSerializer(ReleaseDto.serializer()), body)
        }
    }

    /**
     * Newest by version, not by position or date. GitHub lists releases in the
     * order they were made, and a hotfix on an older line — 0.1.1 published
     * after 0.2.0 — is the first entry and the wrong answer.
     */
    private fun verdict(releases: List<ReleaseDto>, includePrerelease: Boolean): UpdateCheck {
        // Drafts are never offered and never "current": a draft is a release
        // page somebody has not finished, and its tag may not even exist yet.
        val published = releases
            .filter { !it.draft }
            .mapNotNull { dto -> AppVersion.parse(dto.tagName)?.let { version -> version to dto } }

        val offered = published
            .filter { (_, dto) -> includePrerelease || !dto.prerelease }
            .maxByOrNull { (version, _) -> version }
        if (offered != null && AppVersion.isNewer(offered.second.tagName, installedVersion)) {
            return UpdateCheck.Available(offered.second.toReleaseInfo())
        }

        // "What you have" is looked for among every published release, whatever
        // the pre-release setting: somebody running 0.3.0-rc.1 is on a
        // pre-release, and hiding its notes would be hiding their own build.
        val installed = AppVersion.parse(installedVersion)
        val current = published.firstOrNull { (version, _) ->
            installed != null && version.compareTo(installed) == 0
        }
        return UpdateCheck.UpToDate(current?.second?.toReleaseInfo())
    }

    private companion object {
        const val TAG = "Lessons"

        /**
         * Fifteen is more lines than this project will ever have live at once,
         * and when it is not, the ones that fall off the end are the oldest —
         * which is the right end.
         */
        val RELEASES_URL = GithubApi.repoUrl("/releases?per_page=15")
    }
}
