package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.SchoolClassDto
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response as OkResponse
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The conditional sync: what the phone asks for when it already has the answer.
 *
 * The server has always answered `304` to a matching `If-None-Match` — its own
 * docstring says a widget's timer poll should cost a hash comparison rather
 * than a two-week payload — and the client never sent the header, so every sync
 * downloaded the whole school year. Since the window grew from a rolling month
 * to ~273 days, that is roughly twenty times the payload it was written for.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConditionalSyncTest {

    private val zone = ZoneId.of("Europe/Moscow")
    private val clock: Clock =
        Clock.fixed(LocalDate.parse("2026-10-15").atStartOfDay(zone).toInstant(), zone)

    private class Store : BundleTagStore {
        val written = mutableMapOf<String, String>()
        override suspend fun tagFor(signature: String): String? = written[signature]
        override suspend fun remember(signature: String, etag: String) {
            written[signature] = etag
        }
    }

    private class FakeApi(private val etag: String?) : LessonsApi {
        var seenTag: String? = null
        var calls = 0
        var answerNotModified = false

        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")

        override suspend fun bundle(
            start: String,
            days: Int,
            ifNoneMatch: String?,
        ): Response<BundleDto> {
            calls += 1
            seenTag = ifNoneMatch
            val raw = OkResponse.Builder()
                .code(if (answerNotModified) 304 else 200)
                .message("")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://test/api/v1/bundle").build())
                .apply { etag?.let { header("ETag", it) } }
                .build()
            return if (answerNotModified) {
                Response.error(
                    "".toResponseBody("application/json".toMediaType()),
                    raw.newBuilder().code(304).build(),
                )
            } else {
                Response.success(
                    BundleDto(
                        apiVersion = 1,
                        schoolClass = SchoolClassDto(id = 1, name = "9А"),
                        days = emptyList(),
                    ),
                    raw.newBuilder().code(200).build(),
                )
            }
        }
    }

    private fun repository(api: LessonsApi, tags: BundleTagStore, dao: InMemoryTimetableDao, onData: () -> Unit) =
        TimetableRepositoryImpl(
            dao = dao,
            api = api,
            activeClassId = flowOf(1L),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
            onDataChanged = onData,
            bundleTags = tags,
        )

    @Test
    fun `the first sync sends no tag and remembers the one it is given`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = Store()

        repository(api, tags, InMemoryTimetableDao(), onData = {}).refresh(days = 31)

        assertNull("there is nothing to compare against yet", api.seenTag)
        assertEquals(1, tags.written.size)
        assertTrue(tags.written.values.single() == "\"abc\"")
    }

    @Test
    fun `the next sync sends it back and a 304 costs no write and no widget redraw`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = Store()
        val dao = InMemoryTimetableDao()
        var redraws = 0

        repository(api, tags, dao, onData = { redraws += 1 }).refresh(days = 31)
        val afterFirst = dao.schoolClass(1L)?.syncedAtEpochMillis
        assertNotNull(afterFirst)

        api.answerNotModified = true
        val result = repository(api, tags, dao, onData = { redraws += 1 }).refresh(days = 31)

        assertEquals(SyncResult.Success, result)
        assertEquals("\"abc\"", api.seenTag)
        // The mark moves — the check did happen, and «обновлено N назад» is a
        // claim about the check, not about the payload.
        assertEquals(clock.millis(), dao.schoolClass(1L)?.syncedAtEpochMillis)
        // The widget is not poked: nothing it draws has changed, and a redraw
        // per poll is the cost this request exists to avoid.
        assertEquals("only the first sync had anything to announce", 1, redraws)
    }

    @Test
    fun `a tag is not reused for a different window`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = Store()
        repository(api, tags, InMemoryTimetableDao(), onData = {}).refresh(days = 31)

        // 1 September moves the window; the stored signature stops matching and
        // the sync asks for the whole thing again rather than comparing against
        // an answer to a different question.
        val nextYear = TimetableRepositoryImpl(
            dao = InMemoryTimetableDao(),
            api = api,
            activeClassId = flowOf(1L),
            clock = Clock.fixed(
                LocalDate.parse("2027-10-15").atStartOfDay(zone).toInstant(),
                zone,
            ),
            ioDispatcher = UnconfinedTestDispatcher(),
            bundleTags = tags,
        )
        nextYear.refresh(days = 31)

        assertNull("a tag for last year's window is no tag at all", api.seenTag)
        assertEquals(2, tags.written.size)
    }
}
