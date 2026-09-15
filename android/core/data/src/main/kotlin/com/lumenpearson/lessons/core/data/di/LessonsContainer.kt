package com.lumenpearson.lessons.core.data.di

import android.content.Context
import com.lumenpearson.lessons.core.data.database.LessonsDatabase
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.notifications.SchoolAlerts
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SessionRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepositoryImpl
import com.lumenpearson.lessons.core.data.github.GithubRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
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

    override val timetableRepository: TimetableRepository by lazy {
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
            // Resolved when it fires, not here: `sessionRepository` is a lazy
            // in this same container and asking for it now would build it on
            // the cold-start path of a class this device may not even be in.
            onTokenRejected = { sessionRepository.signOut() },
        )
    }

    override val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            preferences = preferences,
            api = api,
            dao = database.timetableDao(),
            // The broadcast as well as the alarms, because the widget redraws
            // on exactly two things: this broadcast, and its own armed tick.
            // Leaving a class at four on a Friday puts the state at
            // `AfterSchool`, whose tick is midnight — so without this the home
            // screen went on showing the lessons of a class the phone had been
            // thrown out of for the next eight hours, and after a 401 nobody
            // had even pressed anything.
            onSignedOut = {
                DataSyncBroadcast.send(appContext)
                SchoolAlerts.clear(appContext)
            },
            // Switching classes is not signing out, so nothing is cancelled:
            // the widget is told to redraw, the alarm chain is re-planned from
            // the class now on screen, and a sync is asked for because the
            // window being switched to is as old as the last time it was
            // looked at. The cached one is drawn in the meantime, which is what
            // keeps the switch instant and usable with no network.
            onActiveClassChanged = {
                DataSyncBroadcast.send(appContext)
                SchoolAlerts.onDataChanged(appContext)
                SyncScheduler.syncNow(appContext)
            },
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(
            preferences = preferences,
            onAlertsChanged = { SchoolAlerts.reschedule(appContext) },
        )
    }

    override val updateRepository: UpdateRepository by lazy {
        UpdateRepositoryImpl(appContext)
    }

    override val deviceLinkRepository: DeviceLinkRepository by lazy {
        DeviceLinkRepositoryImpl(api = api)
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
            // case an admin makes themselves by deleting the class.
            onTokenRejected = { sessionRepository.signOut() },
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
