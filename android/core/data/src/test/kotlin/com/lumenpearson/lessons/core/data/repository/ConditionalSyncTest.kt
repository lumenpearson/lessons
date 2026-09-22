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

    private class FakeApi(private val etag: String?) : LessonsApi {
        var seenTag: String? = null
        var calls = 0
        var answerNotModified = false
        var status = 0
        val tagsSeen = mutableListOf<String?>()

        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun health() = error("unused")
        override suspend fun warmup() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")

        override suspend fun bundle(
            start: String,
            days: Int,
            ifNoneMatch: String?,
        ): Response<BundleDto> {
            calls += 1
            seenTag = ifNoneMatch
            tagsSeen += ifNoneMatch
            if (status != 0) {
                val failed = OkResponse.Builder()
                    .code(status)
                    .message("")
                    .protocol(Protocol.HTTP_1_1)
                    .request(Request.Builder().url("http://test/api/v1/bundle").build())
                    .build()
                return Response.error(
                    "".toResponseBody("application/json".toMediaType()),
                    failed,
                )
            }
            // A 304 is an answer to a conditional request. The fake used to
            // give one to any request at all, which is not a thing a server
            // does — and it hid the retry this repository makes when the tag
            // turns out to describe a window the phone no longer holds.
            val notModified = answerNotModified && ifNoneMatch != null
            val raw = OkResponse.Builder()
                .code(if (notModified) 304 else 200)
                .message("")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://test/api/v1/bundle").build())
                .apply { etag?.let { header("ETag", it) } }
                .build()
            return if (notModified) {
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

    private fun repository(
        api: LessonsApi,
        tags: BundleTagStore,
        dao: InMemoryTimetableDao,
        onData: () -> Unit,
        onRejected: () -> Unit = {},
        onUnchanged: () -> Unit = {},
    ) =
        TimetableRepositoryImpl(
            dao = dao,
            api = api,
            activeClassId = flowOf(1L),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
            onDataChanged = onData,
            onNothingChanged = onUnchanged,
            onTokenRejected = onRejected,
            bundleTags = tags,
        )

    @Test
    fun `the first sync sends no tag and remembers the one it is given`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = RecordingTagStore()

        repository(api, tags, InMemoryTimetableDao(), onData = {}).refresh()

        assertNull("there is nothing to compare against yet", api.seenTag)
        assertEquals(1, tags.written.size)
        assertTrue(tags.written.values.single() == "\"abc\"")
    }

    @Test
    fun `the next sync sends it back and a 304 costs no write and no widget redraw`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = RecordingTagStore()
        val dao = InMemoryTimetableDao()
        var redraws = 0

        repository(api, tags, dao, onData = { redraws += 1 }).refresh()
        val afterFirst = dao.schoolClass(1L)?.syncedAtEpochMillis
        assertNotNull(afterFirst)

        api.answerNotModified = true
        val result = repository(api, tags, dao, onData = { redraws += 1 }).refresh()

        assertEquals(SyncResult.Success, result)
        assertEquals("\"abc\"", api.seenTag)
        // The mark moves — the check did happen, and «обновлено N назад» is a
        // claim about the check, not about the payload.
        assertEquals(clock.millis(), dao.schoolClass(1L)?.syncedAtEpochMillis)
        // The widget is not poked: nothing it draws has changed, and a redraw
        // per poll is the cost this request exists to avoid.
        assertEquals("only the first sync had anything to announce", 1, redraws)
    }

    /**
     * The other half of «and no widget redraw»: the alarm chain still has to
     * hear about it.
     *
     * The chain is one alarm long and re-arms itself only when an alarm fires,
     * so a gap wider than the planner's horizon — a fortnight of holidays —
     * ends it with nothing standing behind it. On a phone whose window has not
     * changed, every sync is this branch: it was the only thing left that
     * could notice, and it was the one path that deliberately said nothing.
     *
     * The two announcements are separate because they answer different
     * questions. The widget draws the data, and the data has not moved; the
     * chain is about time passing, and time passes on a `304` exactly as fast.
     */
    @Test
    fun `a 304 re-arms the alarm chain even though it redraws nothing`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = RecordingTagStore()
        val dao = InMemoryTimetableDao()
        var redraws = 0
        var replans = 0

        repository(api, tags, dao, onData = { redraws += 1 }, onUnchanged = { replans += 1 })
            .refresh()
        assertEquals("the first sync has data, so it announces data", 1, redraws)
        assertEquals(0, replans)

        api.answerNotModified = true
        val result = repository(api, tags, dao, onData = { redraws += 1 }, onUnchanged = { replans += 1 })
            .refresh()

        assertEquals(SyncResult.Success, result)
        assertEquals("nothing it draws has changed", 1, redraws)
        assertEquals("but the chain is not allowed to go quiet", 1, replans)
    }

    /**
     * And a sync that *did* bring data does not announce it twice: the widget
     * path already re-plans the alarms on its way past, so calling both would
     * read the whole cached year a second time for nothing.
     */
    @Test
    fun `a sync with new data announces the data and not the other thing`() = runTest {
        var redraws = 0
        var replans = 0

        repository(
            FakeApi(etag = "\"abc\""),
            RecordingTagStore(),
            InMemoryTimetableDao(),
            onData = { redraws += 1 },
            onUnchanged = { replans += 1 },
        ).refresh()

        assertEquals(1, redraws)
        assertEquals(0, replans)
    }

    @Test
    fun `a tag is not reused for a different window`() = runTest {
        val api = FakeApi(etag = "\"abc\"")
        val tags = RecordingTagStore()
        repository(api, tags, InMemoryTimetableDao(), onData = {}).refresh()

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
        nextYear.refresh()

        assertNull("a tag for last year's window is no tag at all", api.seenTag)
        assertEquals(2, tags.written.size)
    }

    @Test
    fun `a 304 against an empty cache asks again instead of reporting success`() = runTest {
        // The tag lives in the preferences and outlives every wipe of the
        // cache: `clearSession`, `removeSession`, `clearMemberships` and a
        // destructive Room migration all leave it standing, and the signature
        // is `classId|start|window`, none of which moves across a leave and a
        // re-join of the same class. So the phone sent a tag the server still
        // matched while holding nothing at all — `touchSyncedAt` matched zero
        // rows, `refresh` answered `Success`, and the home screen, the widget
        // and the notifications all went on reading an empty cache. Every pull
        // to refresh repeated the 304.
        val api = FakeApi(etag = "\"abc\"")
        val tags = RecordingTagStore()
        val populated = InMemoryTimetableDao()
        repository(api, tags, populated, onData = {}).refresh()

        api.answerNotModified = true
        val empty = InMemoryTimetableDao()
        val result = repository(api, tags, empty, onData = {}).refresh()

        assertEquals("\"abc\"", api.tagsSeen[1])
        assertNull("the second attempt asks for the whole window", api.tagsSeen[2])
        assertEquals(SyncResult.Success, result)
        assertNotNull("and the window actually lands this time", empty.schoolClass(1L))
    }

    @Test
    fun `a 401 on the bundle throws the device out of the class`() = runTest {
        // Retrofit throws `HttpException` only for a *body-typed* suspend
        // method; a `Response<T>`-typed one is handed the error response
        // verbatim. So the `catch (http: HttpException)` that calls
        // `onTokenRejected` has been unreachable for this call ever since it
        // started asking «changed since?», and `TokenRejectedTest` could not
        // see it because its fake *throws* what real Retrofit never would.
        //
        // The cost: a revoked device kept the class, the token and a year of
        // somebody else's timetable, and the screen said «Server returned HTTP
        // 401» in English instead of the sentence written for it.
        val api = FakeApi(etag = null)
        api.status = 401
        var rejected = 0

        val result = repository(
            api,
            RecordingTagStore(),
            InMemoryTimetableDao(),
            onData = {},
            onRejected = { rejected += 1 },
        ).refresh()

        assertEquals(SyncResult.Unauthorised, result)
        assertEquals(1, rejected)
    }
}
