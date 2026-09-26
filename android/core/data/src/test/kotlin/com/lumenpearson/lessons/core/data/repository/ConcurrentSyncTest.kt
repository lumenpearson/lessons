package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.SchoolClassDto
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response as OkResponse
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

/**
 * Two syncs of one class's year that overlap make one request (#171).
 *
 * A join asked for the year twice in the same second — the join screen's own
 * refresh and the one-off worker the class switch schedules — before either had
 * stored an `ETag`, so each downloaded all ~273 days. The fake server holds its
 * answer until released, which is the window in which the two overlapped.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConcurrentSyncTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val clock: Clock =
        Clock.fixed(LocalDate.parse("2026-10-15").atStartOfDay(zone).toInstant(), zone)

    private class GatedApi : LessonsApi {
        var calls = 0
        var gate = CompletableDeferred<Unit>()

        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun health() = error("unused")
        override suspend fun warmup() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")

        override suspend fun bundle(start: String, days: Int, ifNoneMatch: String?): Response<BundleDto> {
            calls += 1
            gate.await()
            val raw = OkResponse.Builder()
                .code(200)
                .message("")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://test/api/v1/bundle").build())
                .build()
            return Response.success(
                BundleDto(apiVersion = 1, schoolClass = SchoolClassDto(id = 1, name = "9А"), days = emptyList()),
                raw,
            )
        }
    }

    private fun repository(api: LessonsApi) = TimetableRepositoryImpl(
        dao = InMemoryTimetableDao(),
        api = api,
        activeClassId = flowOf(1L),
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
        bundleTags = RecordingTagStore(),
    )

    @Test
    fun `two refreshes on the wire at once make one request and share its answer`() = runTest {
        val api = GatedApi()
        val repository = repository(api)

        val first = async(UnconfinedTestDispatcher(testScheduler)) { repository.refresh() }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { repository.refresh() }
        api.gate.complete(Unit)

        assertEquals(SyncResult.Success, first.await())
        assertEquals(SyncResult.Success, second.await())
        assertEquals("the year was downloaded more than once", 1, api.calls)
    }

    @Test
    fun `a refresh after the first has finished asks again`() = runTest {
        val api = GatedApi().apply { gate.complete(Unit) }
        val repository = repository(api)

        repository.refresh()
        repository.refresh()

        assertEquals("a finished sync must not answer for a later one", 2, api.calls)
    }

    @Test
    fun `a waiting refresh is not cancelled with the one it was waiting on`() = runTest {
        val api = GatedApi()
        val repository = repository(api)

        val first = async(UnconfinedTestDispatcher(testScheduler)) { repository.refresh() }
        val second = async(UnconfinedTestDispatcher(testScheduler)) { repository.refresh() }
        first.cancel()
        api.gate.complete(Unit)

        assertEquals(SyncResult.Success, second.await())
        assertEquals("the waiter should have made its own request", 2, api.calls)
    }
}
