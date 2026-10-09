package com.lumenpearson.lessons.appicon

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The icon the launcher shows, for everything in the app that draws it, and
 * the one door every component write goes through.
 *
 * One at a time, behind [lock]: the reconcile a process start runs and a
 * switch somebody presses at that moment would otherwise read the same states
 * and each write half of an answer. Off the main thread, on [io]: each
 * component read and write is a call into the system server.
 */
class AppIconStore(
    private val aliases: LauncherAliases,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + io),
) {

    private val lock = Mutex()
    private val state = MutableStateFlow<AppIconVariant?>(null)

    /** The icon in use; `null` until it has been read once, which a reader shows as the default. */
    val current: StateFlow<AppIconVariant?> = state.asStateFlow()

    suspend fun refresh() {
        locked { state.value = aliases.current() }
    }

    suspend fun reconcile() {
        locked { state.value = aliases.reconcile() }
    }

    /**
     * Throws whatever the platform throws, and then [current] is what it was.
     *
     * The assignment runs inside [locked], after the write, rather than after
     * this suspend function resumes: a caller cancelled while the write is on
     * [io] would otherwise resume into a thrown `CancellationException` and
     * never reach `state.value = target`, leaving [current] naming the old
     * icon for the rest of the process while the launcher already shows the
     * new one.
     */
    suspend fun switchTo(target: AppIconVariant) {
        locked {
            aliases.switchTo(target)
            state.value = target
        }
    }

    /**
     * [reconcile] for a caller that cannot wait: a process start and a
     * broadcast. A failure is swallowed, because neither has anybody to tell,
     * and the next process start tries again; [onDone] runs either way.
     */
    fun reconcileInBackground(onDone: () -> Unit = {}): Job = scope.launch {
        try {
            runCatching { reconcile() }
        } finally {
            onDone()
        }
    }

    private suspend fun <T> locked(block: () -> T): T = lock.withLock { withContext(io) { block() } }
}

/**
 * The process's one [AppIconStore].
 *
 * Built on first use from whatever context asks: the application at start, a
 * receiver after an update, a screen. Not in `Graph`, which lives in
 * `:core:data` and cannot see this module's launcher aliases.
 */
object AppIcons {

    @Volatile
    private var instance: AppIconStore? = null

    fun store(context: Context): AppIconStore = instance ?: synchronized(this) {
        instance ?: AppIconStore(LauncherAliases(PackageManagerComponents(context.applicationContext)))
            .also { instance = it }
    }

    @VisibleForTesting
    internal fun override(store: AppIconStore?) {
        instance = store
    }
}
