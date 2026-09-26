package com.lumenpearson.lessons.ui.join

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import com.lumenpearson.lessons.core.data.repository.Session
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SyncResult
import com.lumenpearson.lessons.core.data.repository.TimetableRepository
import com.lumenpearson.lessons.core.model.Timetable
import com.lumenpearson.lessons.ui.onboarding.FakeSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * The join's two halves as a test holds them: the session write, and the first
 * sync after it — which [syncGate], when set, parks, as a slow network does
 * while the shell has already swapped on the session.
 */
internal class JoinRig {

    val joined = MutableStateFlow<List<Session>>(emptyList())
    var syncGate: CompletableDeferred<Unit>? = null
    val codes = mutableListOf<String>()

    private val sessionRepository = object : SessionRepository {
        override val session: Flow<Session?> = joined.map { it.lastOrNull() }
        override val sessions: Flow<List<Session>> = joined
        override suspend fun current(): Session? = joined.value.lastOrNull()
        override suspend fun serverStatus(): ServerStatus = ServerStatus.NotConfigured
        override suspend fun currentAll(): List<Session> = joined.value
        override suspend fun join(code: String, deviceName: String?): Result<Session> {
            codes += code
            val session = Session(classId = 10L + codes.size, className = "9А", school = null, token = "t")
            joined.value = joined.value + session
            return Result.success(session)
        }
        override suspend fun select(classId: Long) = Unit
        override suspend fun leave(classId: Long) = Unit
        override suspend fun leaveActive() = Unit
        override suspend fun signOut() = Unit
    }

    private val timetables = object : TimetableRepository {
        override val timetable: Flow<Timetable?> = MutableStateFlow(null)
        override val syncedYears: Flow<Set<Int>> = MutableStateFlow(emptySet())
        override suspend fun snapshot(): Timetable? = null
        override suspend fun snapshotAroundToday(): Timetable? = null
        override suspend fun refresh(): SyncResult {
            syncGate?.await()
            return SyncResult.Success
        }
        override suspend fun refreshYear(openingYear: Int): SyncResult = refresh()
        override suspend fun forgetClassesOtherThan(keep: Set<Long>) = Unit
    }

    fun model() = JoinViewModel(sessionRepository, timetables, FakeSettings(), deviceName = null)

    /** Hands [model] to `viewModel(factory = …)`, the way the app's own factory would. */
    fun factory(model: JoinViewModel = model()) = object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = model as T
    }
}
