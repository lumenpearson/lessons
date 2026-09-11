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
import com.lumenpearson.lessons.core.data.repository.GithubRepository
import com.lumenpearson.lessons.core.data.repository.UpdateRepository
import com.lumenpearson.lessons.core.data.sync.DataSyncBroadcast
import com.lumenpearson.lessons.core.data.update.UpdateRepositoryImpl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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

    private val api: LessonsApi by lazy {
        NetworkModule.lessonsApi(
            tokenProvider = { preferences.tokenBlocking() },
            baseUrlProvider = { preferences.baseUrlBlocking() },
        )
    }

    override val timetableRepository: TimetableRepository by lazy {
        TimetableRepositoryImpl(
            dao = database.timetableDao(),
            api = api,
            // The widget cannot be called directly from here — it depends on
            // this module, not the other way round — so the broadcast it already
            // listens for is handed in instead. The alerts live in this module
            // and are called directly.
            onDataChanged = {
                DataSyncBroadcast.send(appContext)
                SchoolAlerts.onDataChanged(appContext)
            },
        )
    }

    override val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            preferences = preferences,
            api = api,
            dao = database.timetableDao(),
            onSignedOut = { SchoolAlerts.clear(appContext) },
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

    override val githubRepository: GithubRepository by lazy {
        GithubRepositoryImpl(
            context = appContext,
            clientId = githubClientId,
            scope = containerScope,
        )
    }
}
