package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.database.decodeTerms
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.SchoolClassDto
import com.lumenpearson.lessons.core.data.network.dto.TermDto
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
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
 * A term moved in the bot has to reach the phone's cache.
 *
 * Reported from a real class: «второе полугодие» was given a new end date in
 * «🗓 Четверти», and the app went on naming the old one — through a manual
 * refresh, through leaving the class and through re-joining it with a fresh
 * code. The server half of that is proved elsewhere (the row is stored and the
 * bundle carries it, and the `ETag` changes with it); this is the other half,
 * and the one place a changed date could be dropped without anything failing.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditedTermReachesTheCacheTest {

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

    /** Answers whatever terms it is currently holding, with a tag of its own. */
    private class FakeApi(var terms: List<TermDto>, var etag: String) : LessonsApi {
        override suspend fun join(body: JoinRequestDto) = error("unused")
        override suspend fun health() = error("unused")
        override suspend fun me() = error("unused")
        override suspend fun unlink() = error("unused")

        override suspend fun bundle(
            start: String,
            days: Int,
            ifNoneMatch: String?,
        ): Response<BundleDto> {
            val raw = OkResponse.Builder()
                .code(200)
                .message("")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://test/api/v1/bundle").build())
                .header("ETag", etag)
                .build()
            return Response.success(
                BundleDto(
                    apiVersion = 1,
                    schoolClass = SchoolClassDto(
                        id = 1,
                        name = "9А",
                        termKind = "semester",
                        terms = terms,
                    ),
                    days = emptyList(),
                ),
                raw,
            )
        }
    }

    private fun semesters(secondEnds: String) = listOf(
        TermDto(index = 1, kind = "semester", startsOn = "2026-09-01", endsOn = "2026-12-31"),
        TermDto(index = 2, kind = "semester", startsOn = "2027-01-01", endsOn = secondEnds),
    )

    @Test
    fun `the second sync replaces the term the first one cached`() = runTest {
        val api = FakeApi(terms = semesters("2027-05-31"), etag = "\"one\"")
        val dao = InMemoryTimetableDao()
        val repository = TimetableRepositoryImpl(
            dao = dao,
            api = api,
            activeClassId = flowOf(1L),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
            onDataChanged = {},
            onNothingChanged = {},
            onTokenRejected = {},
            bundleTags = Store(),
        )

        repository.refresh(days = 31)
        assertEquals(LocalDate.parse("2027-05-31"), cachedSecondTermEnd(dao))

        // The admin moves it in the bot: a different body, and therefore a
        // different tag, which is what makes the second answer a 200.
        api.terms = semesters("2027-05-28")
        api.etag = "\"two\""
        repository.refresh(days = 31)

        assertEquals(
            "the edited end date never reached the cache",
            LocalDate.parse("2027-05-28"),
            cachedSecondTermEnd(dao),
        )
    }

    /** The class row as it sits in the cache, decoded the way the app decodes it. */
    private suspend fun cachedSecondTermEnd(dao: InMemoryTimetableDao): LocalDate =
        decodeTerms(dao.schoolClass(1L)!!.terms)[1].endsOn

    @Test
    fun `the timetable the screens read carries it too`() = runTest {
        val api = FakeApi(terms = semesters("2027-05-31"), etag = "\"one\"")
        val dao = InMemoryTimetableDao()
        val repository = TimetableRepositoryImpl(
            dao = dao,
            api = api,
            activeClassId = flowOf(1L),
            clock = clock,
            ioDispatcher = UnconfinedTestDispatcher(),
            onDataChanged = {},
            onNothingChanged = {},
            onTokenRejected = {},
            bundleTags = Store(),
        )
        repository.refresh(days = 31)
        api.terms = semesters("2027-05-28")
        api.etag = "\"two\""
        repository.refresh(days = 31)

        // Through the flow the calendar actually collects, not the DAO.
        val timetable = repository.timetable.first()
        assertEquals(
            LocalDate.parse("2027-05-28"),
            timetable!!.schoolClass.terms[1].endsOn,
        )
    }
}
