package com.lumenpearson.lessons.core.data.di

import android.content.Context
import com.lumenpearson.lessons.core.data.database.LessonsDatabase
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.NetworkModule
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SessionRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.SettingsRepository
import com.lumenpearson.lessons.core.data.repository.SettingsRepositoryImpl
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.data.repository.TimetableRepositoryImpl
import com.lumenpearson.lessons.core.data.sync.DataSyncBroadcast

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
}

/**
 * The real graph: one database, one OkHttp client, one preferences file.
 *
 * Every property is `lazy` so that constructing the container - which happens in
 * `Application.onCreate`, on the main thread - opens no files and starts no
 * threads. Room's builder, DataStore's file handle and OkHttp's pools are all
 * created on first use, which for the widget process may be never.
 */
class DefaultLessonsContainer(context: Context) : LessonsContainer {

    /** Never hold the passed-in Context: it may be an Activity. */
    private val appContext: Context = context.applicationContext

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
            // listens for is handed in instead.
            onDataChanged = { DataSyncBroadcast.send(appContext) },
        )
    }

    override val sessionRepository: SessionRepository by lazy {
        SessionRepositoryImpl(
            preferences = preferences,
            api = api,
            dao = database.timetableDao(),
        )
    }

    override val settingsRepository: SettingsRepository by lazy {
        SettingsRepositoryImpl(preferences)
    }
}
