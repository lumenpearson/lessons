package com.lumenpearson.lessons.core.data.di

import android.content.Context
import com.lumenpearson.lessons.core.data.database.LessonsDatabase
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.notifications.SchoolAlerts
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.BundleTagStore
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SessionRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepositoryImpl
import com.lumenpearson.lessons.core.data.docs.DocsRepositoryImpl
import com.lumenpearson.lessons.core.data.github.GithubRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DocsRepository
import com.lumenpearson.lessons.core.data.repository.DiaryRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManageRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import com.lumenpearson.lessons.core.data.sync.DataSyncBroadcast
import com.lumenpearson.lessons.core.data.sync.SyncScheduler
import com.lumenpearson.lessons.core.data.update.UpdateRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.map

/**
 * Everything the rest of the app is allowed to reach for.
 *
 * An interface rather than a concrete object so tests - and the widget's
 * previews - can swap in fakes through [Graph.override] without a DI framework.
 */
interface LessonsContainer {
    val timetableRepository: TimetableRepository
    val sessionRepository: SessionRepository
    val settingsRepository: SettingsRepository
    val updateRepository: UpdateRepository
    val githubRepository: GithubRepository
    val deviceLinkRepository: DeviceLinkRepository

    /**
     * The guide. Present on every install and needing no session: an install
     * that has never joined a class is exactly the one whose reader is looking
     * for the documentation.
     */
    val docsRepository: DocsRepository

    /**
     * The Petersburg diary. Present whether or not anybody has signed in to
     * one: it is the repository that knows, and the section asks it.
     */
    val diaryRepository: DiaryRepository

    /**
     * Running the class. Present on every install, because whether this phone
     * may use it is the server's answer and not a fact the graph could hold:
     * the role is looked up per request from the linked Telegram account.
     */
    val manageRepository: ManageRepository
}

/**
 * The real graph: one database, one OkHttp client, one preferences file.
 *
 * Every property is `lazy` so that constructing the container - which happens in
 * `Application.onCreate`, on the main thread - opens no files and starts no
 * threads. Room's builder, DataStore's file handle and OkHttp's pools are all
 * created on first use, which for the widget process may be never.
 *
 * @param githubClientId the OAuth App the GitHub sign-in talks to, or blank for
 *   a build that has none. It is the app module's build constant, which this
 *   module cannot see, so it arrives as an argument from `Application.onCreate`.
 */
class DefaultLessonsContainer(
    context: Context,
    private val githubClientId: String = "",
) : LessonsContainer {

    /** Never hold the passed-in Context: it may be an Activity. */
    private val appContext: Context = context.applicationContext

    /**
     * For work that must outlive whatever screen started it — the GitHub device
     * flow keeps polling after its sheet is closed. Process-scoped and never
     * cancelled, like the application's own scope, and for the same reason: the
     * process ending is the only thing that should end it.
     */
    private val containerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val preferences: LessonsPreferences by lazy { LessonsPreferences(appContext) }

    /**
     * What a join, a switch and a sign-out have to announce; see
     * [SessionEffects], which is where the decisions are and where they are
     * tested. This is only the wiring of each name to the thing that does it.
     *
     * Not `lazy` like everything below it, and for the same reason everything
     * below it is: six lambdas that capture a context are less work than the
     * delegate that would defer them, and none of them runs until something
     * calls it.
     */
    private val sessionEffects = SessionEffects(
        redrawWidget = { DataSyncBroadcast.send(appContext) },
        clearAlerts = { SchoolAlerts.clear(appContext) },
        replanAlerts = { SchoolAlerts.onDataChanged(appContext) },
        stopBackgroundSync = { SyncScheduler.cancelPeriodic(appContext) },
        // The stored interval, not a constant: the settings screen owns that
        // number, and re-arming at the default would quietly undo a user who
        // had asked for a different one the last time the app was open.
        startBackgroundSync = {
            SyncScheduler.schedulePeriodic(appContext, preferences.syncIntervalMinutesBlocking())
        },
        syncNow = { SyncScheduler.syncNow(appContext, wantsDifferentData = true) },
    )

    private val database: LessonsDatabase by lazy { LessonsDatabase.build(appContext) }

    /**
     * One client, two interfaces, two bearers.
     *
     * The diary's token is read through its own provider — see
     * `DiaryAuthInterceptor` — so that neither account can ever be signed with
     * the other's credentials.
     */
    private val apis: NetworkModule.Apis by lazy {
        NetworkModule.apis(
            tokenProvider = { preferences.tokenBlocking() },
            baseUrlProvider = { preferences.baseUrlBlocking() },
            diaryTokenProvider = { preferences.diaryTokenBlocking() },
        )
    }

    private val api: LessonsApi by lazy { apis.lessons }

    /**
     * The ETag of the window this phone already holds, so a poll that changes
     * nothing costs a hash comparison instead of a school year of JSON.
     *
     * In the preferences rather than in Room: it describes a *request*, not a
     * row, and it has to outlive the table a sync replaces. Hoisted out of the
     * timetable repository because it is not only that repository's to drop —
     * leaving a class has to take its tags with it, and that starts in the
     * session repository, through [TimetableCache].
     */
    private val bundleTags: BundleTagStore = object : BundleTagStore {
        override suspend fun tagFor(signature: String): String? =
            runCatching { preferences.bundleTag(signature) }.getOrNull()

        override suspend fun remember(signature: String, etag: String) {
            // Guarded because it writes: a full disk must cost the next sync
            // its shortcut, not the sync that just succeeded.
            runCatching { preferences.writeBundleTag(signature, etag) }
        }

        override suspend fun forget(signature: String) {
            // Guarded for the same reason, and the cost is smaller still: a tag
            // left behind for an evicted year is one request that answers 304
            // and then asks again.
            runCatching { preferences.forgetBundleTag(signature) }
        }

        override suspend fun forgetClass(classId: Long) {
            runCatching { preferences.forgetBundleTagsOf(classId) }
        }

        override suspend fun forgetClassesOtherThan(keep: Collection<Long>) {
            runCatching { preferences.forgetBundleTagsOutside(keep) }
        }
    }

    /**
     * The concrete type, because two interfaces are drawn from it: the
     * repository the screens read, and the [TimetableCache] the session
     * repository empties. One object either way — a second would be a second
     * ETag store and a second `prune`.
     */
    private val timetable: TimetableRepositoryImpl by lazy {
        TimetableRepositoryImpl(
            dao = database.timetableDao(),
            api = api,
            // Taken from the preferences rather than from `sessionRepository`,
            // which is a lazy in this same container: the timetable is what the
            // widget's cold start asks for first, and routing it through the
            // session repository would build that one too, on that path, for a
            // class id it could have read directly.
            activeClassId = preferences.session.map { it?.classId },
            // The widget cannot be called directly from here — it depends on
            // this module, not the other way round — so the broadcast it already
            // listens for is handed in instead. The alerts live in this module
            // and are called directly.
            onDataChanged = {
                DataSyncBroadcast.send(appContext)
                SchoolAlerts.onDataChanged(appContext)
            },
            // A `304` changes nothing the widget draws, so it is not told; the
            // alarm chain is asked whether it is still standing, because what
            // ends a chain is time passing rather than the data moving. Not
            // `onDataChanged`: there is no new schedule to compare against a
            // fingerprint, and running that comparison against an unchanged
            // cache would be asking a question whose answer is known. And not
            // `reschedule` either — that re-reads the whole cached year to
            // re-derive an alarm it almost always finds already armed; see
            // [SchoolAlerts.ensureArmed] for what the cheap answer gives up.
            onNothingChanged = { SchoolAlerts.ensureArmed(appContext) },
            // Resolved when it fires, not here: `sessionRepository` is a lazy
            // in this same container and asking for it now would build it on
            // the cold-start path of a class this device may not even be in.
            //
            // `leave`, not `signOut`. The token the server refused is the one
            // the request carried, which is the class on screen — and since a
            // phone may now hold several, signing out of all of them would
            // answer one class revoking a device by deleting the other classes'
            // tokens too, in the background, with the app closed and nothing on
            // screen to say where they went.
            onTokenRejected = { sessionRepository.leaveActive() },
            bundleTags = bundleTags,
        )
    }

    override val timetableRepository: TimetableRepository get() = timetable

    override val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            preferences = preferences,
            api = api,
            // The repository rather than the DAO, so that emptying a class's
            // cache is one call that knows about all of it — rows, window
            // claims and ETags. Building it here is free of the cold-start
            // worry the note above describes: that one is about the widget
            // reaching the timetable first, and this direction opens the same
            // database this constructor already opened.
            cache = timetable,
            // Both answers live in [SessionEffects], which is where the
            // reasoning for each of them is written down and where the
            // difference between them is held by a test. The widget redraws on
            // exactly two things — this broadcast and its own armed tick — so
            // leaving a class at four on a Friday, whose next tick is midnight,
            // depends entirely on being told.
            onSignedOut = sessionEffects::onSignedOut,
            onActiveClassChanged = sessionEffects::onActiveClassChanged,
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            preferences = preferences,
            // `onAlertsChanged` and not `reschedule`: a preference change is
            // the one moment that also has to answer for what is already on
            // the shade, and it is the only caller that may — see the KDoc.
            onAlertsChanged = { SchoolAlerts.onAlertsChanged(appContext) },
        )
    }

    override val updateRepository: UpdateRepository by lazy {
        UpdateRepositoryImpl(appContext)
    }

    override val deviceLinkRepository: DeviceLinkRepository by lazy {
        DeviceLinkRepositoryImpl(api = api)
    }

    override val docsRepository: DocsRepository by lazy {
        // Only a context: the guide is fetched from GitHub with the client in
        // `GithubApi`, never through `api`, whose interceptor would rewrite the
        // host to the school server's address.
        DocsRepositoryImpl(appContext)
    }

    override val diaryRepository: DiaryRepository by lazy {
        DiaryRepositoryImpl(
            api = apis.diary,
            // The preferences object is the store: it is the one thing in the
            // process that may open the DataStore file, and the interface it
            // implements is narrow enough that this repository cannot reach
            // the class token through it.
            store = preferences,
        )
    }

    override val manageRepository: ManageRepository by lazy {
        ManageRepositoryImpl(
            api = apis.manage,
            // The management surface carries the same class bearer, so a `401`
            // there means the same thing it means on a sync — including the
            // case an admin makes themselves by deleting the class. And it
            // means it about that one class: see the note on the sync above.
            onTokenRejected = { sessionRepository.leaveActive() },
        )
    }

    override val githubRepository: GithubRepository by lazy {
        GithubRepositoryImpl(
            context = appContext,
            clientId = githubClientId,
            scope = containerScope,
        )
    }
}
