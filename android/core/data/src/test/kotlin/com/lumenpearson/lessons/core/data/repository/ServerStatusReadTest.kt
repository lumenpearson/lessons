package com.lumenpearson.lessons.core.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.lumenpearson.lessons.core.data.datastore.LessonsPreferences
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.DeviceMeDto
import com.lumenpearson.lessons.core.data.network.dto.HealthDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.JoinResponseDto
import com.lumenpearson.lessons.core.data.network.dto.UnlinkResponseDto
import com.lumenpearson.lessons.core.data.network.dto.WarmupDto
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.Response

/**
 * The server badge asks the preferences whether an address is set before it
 * asks the server anything — from inside a coroutine, where a `runBlocking`
 * read parks the dispatcher thread for as long as the file takes to answer.
 *
 * Held on one thread, so the difference is visible: the test's own thread runs
 * the repository's coroutine, and the preferences cannot answer until the test
 * says so. A read that suspends lets the test go on and say it; a read that
 * blocks holds the only thread that could, and the test times out instead of
 * failing on an assertion — which is why it carries a timeout at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerStatusReadTest {

    @Test(timeout = 20_000)
    fun `asking for the server's status suspends on the preferences instead of blocking`() = runTest {
        val answer = CompletableDeferred<Unit>()
        val repository = SessionRepositoryImpl(
            preferences = LessonsPreferences(SlowStore(answer)),
            api = NoCallsApi,
            cache = NoCache,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )

        val status = async { repository.serverStatus() }
        runCurrent()
        assertFalse("still waiting for the file, and not holding the thread", status.isCompleted)

        answer.complete(Unit)

        assertEquals(
            "no address stored is «not configured», asked of nobody",
            ServerStatus.NotConfigured,
            status.await(),
        )
    }

    /** A file that answers "nothing stored" once it is allowed to. */
    private class SlowStore(private val answer: CompletableDeferred<Unit>) : DataStore<Preferences> {
        override val data: Flow<Preferences> = flow {
            answer.await()
            emit(emptyPreferences())
        }

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences =
            error("nothing here writes")
    }

    /** With no address the badge must not reach the network at all. */
    private object NoCallsApi : LessonsApi {
        override suspend fun join(body: JoinRequestDto): JoinResponseDto = error("no call expected")
        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?): Response<BundleDto> =
            error("no call expected")
        override suspend fun health(): HealthDto = error("no call expected")
        override suspend fun warmup(): WarmupDto = error("no call expected")
        override suspend fun me(): DeviceMeDto = error("no call expected")
        override suspend fun unlink(): UnlinkResponseDto = error("no call expected")
    }

    private object NoCache : TimetableCache {
        override suspend fun forgetClass(classId: Long) = Unit
        override suspend fun forgetEverything() = Unit
    }
}
