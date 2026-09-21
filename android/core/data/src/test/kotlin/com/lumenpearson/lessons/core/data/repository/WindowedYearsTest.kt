package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.database.InMemoryTimetableDao
import com.lumenpearson.lessons.core.data.network.LessonsApi
import com.lumenpearson.lessons.core.data.network.dto.BundleDto
import com.lumenpearson.lessons.core.data.network.dto.DayDto
import com.lumenpearson.lessons.core.data.network.dto.JoinRequestDto
import com.lumenpearson.lessons.core.data.network.dto.LessonDto
import com.lumenpearson.lessons.core.data.network.dto.SchoolClassDto
import com.lumenpearson.lessons.core.model.SchoolYear
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

/**
 * The cache holds several school years, and a sync of one leaves the others be.
 *
 * It used to hold exactly one window per class, and `replaceAll` wiped that
 * class's rows on every sync. So scrolling the calendar into the next school
 * year could not work at all — arriving there would have destroyed the year
 * being left, and coming back would have destroyed the one just fetched. Every
 * date outside the one window read «Нет данных», which on screen is
 * indistinguishable from a week with no lessons in it and reads as a timetable
 * that simply stops.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class WindowedYearsTest {

    private val zone = ZoneId.of("Europe/Moscow")

    /** Mid-October 2026, so the year holding "today" is the one opening in 2026. */
    private val clock: Clock =
        Clock.fixed(LocalDate.parse("2026-10-15").atStartOfDay(zone).toInstant(), zone)

    private class Store : BundleTagStore {
        val written = mutableMapOf<String, String>()
        override suspend fun tagFor(signature: String): String? = written[signature]
        override suspend fun remember(signature: String, etag: String) {
            written[signature] = etag
        }

        override suspend fun forget(signature: String) {
            written.remove(signature)
        }
    }

    /**
     * Answers with one teaching day inside whatever year was asked for.
     *
     * The date is derived from the request rather than fixed, because the
     * question every test below asks is «did the right year land, and did it
     * land beside the others» — a fake that always answered September 2026
     * could not tell a window that was written from one that was ignored.
     */
    private class YearApi(
        private val etag: String? = null,
        private val nextSchoolDay: Boolean = false,
    ) : LessonsApi {
        val requested = mutableListOf<String>()
        val tagsSeen = mutableListOf<String?>()
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
            requested += start
            tagsSeen += ifNoneMatch
            val raw = OkResponse.Builder()
                .code(200)
                .message("")
                .protocol(Protocol.HTTP_1_1)
                .request(Request.Builder().url("http://test/api/v1/bundle").build())
                .apply { etag?.let { header("ETag", it) } }
                .build()
            if (answerNotModified && ifNoneMatch != null) {
                return Response.error(
                    okhttp3.ResponseBody.Companion.create(null, ""),
                    raw.newBuilder().code(304).build(),
                )
            }
            val first = LocalDate.parse(start)
            return Response.success(
                BundleDto(
                    apiVersion = 1,
                    schoolClass = SchoolClassDto(id = 1, name = "9А"),
                    days = listOf(
                        DayDto(
                            date = first.plusDays(1).toString(),
                            weekday = first.plusDays(1).dayOfWeek.value,
                            lessons = listOf(
                                LessonDto(
                                    index = 1,
                                    subject = "Алгебра",
                                    startsAt = "08:30",
                                    endsAt = "09:15",
                                ),
                            ),
                        ),
                    ),
                    nextSchoolDay = if (!nextSchoolDay) {
                        null
                    } else {
                        // Where the server actually puts it: the first teaching
                        // day *after* the requested window, which for a window
                        // that ends in May is the September of the year after —
                        // that is, squarely inside the next window. Placing it
                        // anywhere inside the window being fetched, as the first
                        // draft of this fake did, makes it unreachable by the
                        // ranged delete and the test proves nothing.
                        val after = SchoolYear.start(SchoolYear.openingYearOf(first) + 1)
                        DayDto(
                            date = after.toString(),
                            weekday = after.dayOfWeek.value,
                            lessons = listOf(
                                LessonDto(
                                    index = 1,
                                    subject = "Физика",
                                    startsAt = "08:30",
                                    endsAt = "09:15",
                                ),
                            ),
                        )
                    },
                ),
                raw.newBuilder().code(200).build(),
            )
        }
    }

    private fun repository(
        api: LessonsApi,
        dao: InMemoryTimetableDao,
        tags: BundleTagStore = Store(),
    ) = TimetableRepositoryImpl(
        dao = dao,
        api = api,
        activeClassId = flowOf(1L),
        clock = clock,
        ioDispatcher = UnconfinedTestDispatcher(),
        bundleTags = tags,
    )

    @Test
    fun `a second year lands beside the first rather than over it`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        repository.refresh()
        repository.refreshYear(2027)

        assertEquals(listOf(2027, 2026), dao.cachedYearsOf(1))
        // Two days, one per year. Before this change the second sync deleted
        // the first year's rows on its way in.
        assertEquals(2, dao.dayCountOf(1))
    }

    @Test
    fun `syncing a year again replaces that year and only that year`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        repository.refresh()
        repository.refreshYear(2027)
        repository.refreshYear(2027)

        assertEquals(2, dao.dayCountOf(1))
        assertEquals(listOf(2027, 2026), dao.cachedYearsOf(1))
    }

    @Test
    fun `the years actually held are reported, and nothing else is`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        assertEquals(emptySet<Int>(), repository.syncedYears.first())

        repository.refresh()

        // The claim is what the calendar reads to tell «no lessons» from «not
        // fetched», so it has to be the years that were fetched — not the
        // years that happen to have rows, which a class made in March would
        // answer wrongly for the whole autumn before it.
        assertEquals(setOf(2026), repository.syncedYears.first())
    }

    @Test
    fun `a fourth year drops the one furthest from today`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        repository.refreshYear(2023)
        repository.refreshYear(2024)
        repository.refreshYear(2025)
        repository.refreshYear(2027)

        // Today is in 2026, so 2023 is three years out and the first to go.
        // The cap is what stops a reader who scrolled a long way from carrying
        // ten years of lessons that every observer of this class re-reads on
        // every write.
        assertEquals(listOf(2027, 2025, 2024), dao.cachedYearsOf(1))
    }

    @Test
    fun `the order of eviction does not depend on the clock`() = runTest {
        // Written after the first rule here — «least recently fetched» — was
        // proved not to be a rule at all. This clock is fixed, so all four
        // windows carry the same millisecond, and a sort by that timestamp left
        // the order to whatever the query returned: newest year first, so the
        // year just asked for was the one thrown away. A real phone ties the
        // same way whenever two fetches land inside one millisecond.
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        repository.refreshYear(2027)
        repository.refreshYear(2025)
        repository.refreshYear(2024)
        repository.refreshYear(2023)

        // The same answer as the test above, from the opposite fetch order.
        assertEquals(listOf(2027, 2025, 2024), dao.cachedYearsOf(1))
    }

    @Test
    fun `a tie in distance keeps the later year`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        // 2025 and 2027 are both one year from 2026, and 2023 is three.
        repository.refreshYear(2023)
        repository.refreshYear(2025)
        repository.refreshYear(2027)
        repository.refreshYear(2029)

        // 2023 goes first on distance. Of what is left nothing is tied, but
        // the rule that decides a tie is worth pinning: a school calendar is
        // asked forward far more than back, so the later year stays.
        assertEquals(listOf(2029, 2027, 2025), dao.cachedYearsOf(1))
    }

    @Test
    fun `the year holding today is never the one dropped`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(), dao)

        // 2026 first, so by fetch order it is the oldest and would go.
        repository.refresh()
        repository.refreshYear(2028)
        repository.refreshYear(2029)
        repository.refreshYear(2030)

        // It is what the home screen, the widget and every notification read.
        // Evicting it to make room for 2030 would empty the app to fill a
        // screen nobody is looking at any more.
        assertTrue(2026 in dao.cachedYearsOf(1))
        assertEquals(3, dao.cachedYearsOf(1).size)
    }

    @Test
    fun `each year carries its own tag, and an evicted year loses it`() = runTest {
        val dao = InMemoryTimetableDao()
        val tags = Store()
        val repository = repository(YearApi(etag = "\"y\""), dao, tags)

        repository.refreshYear(2023)
        repository.refreshYear(2024)

        // A tag per window, or every step between two years would be a full
        // download in both directions — the cost the conditional request
        // exists to avoid, paid twice per scroll.
        assertEquals(setOf("1|2023", "1|2024"), tags.written.keys)

        repository.refreshYear(2025)
        repository.refreshYear(2027)

        // And the tag goes with the rows: one kept for a dropped year is a
        // `304` over data this phone no longer holds.
        assertNull(tags.written["1|2023"])
        assertNotNull(tags.written["1|2027"])
    }

    @Test
    fun `a 304 about a year this phone does not hold is asked again without the tag`() = runTest {
        val dao = InMemoryTimetableDao()
        val tags = Store()
        // The tag outlives the cache — it is in the preferences, which
        // `clearSession` and a destructive Room migration both leave standing.
        tags.written["1|2027"] = "\"stale\""
        val api = YearApi().apply { answerNotModified = true }
        val repository = repository(api, dao, tags)

        repository.refreshYear(2027)

        // Two calls: the conditional one, and the unconditional retry once the
        // repository found it holds no such window. Without the retry the phone
        // reports a successful sync over an empty grid, for ever.
        assertEquals(2, api.tagsSeen.size)
        assertEquals(listOf("\"stale\"", null), api.tagsSeen)
        assertEquals(listOf(2027), dao.cachedYearsOf(1))
    }

    @Test
    fun `only the year holding today writes the lookahead row`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(nextSchoolDay = true), dao)

        repository.refresh()
        val afterCurrent = dao.nextSchoolDay(1)?.day?.date
        repository.refreshYear(2029)

        // The lookahead answers «what is the next school day» from *now* — it
        // is what the widget and the notifications read across a fortnight of
        // holidays. A fetch of 2029 rewriting it would point them at a Monday
        // three years out, and nothing on any screen would say why.
        assertNotNull(afterCurrent)
        assertEquals(afterCurrent, dao.nextSchoolDay(1)?.day?.date)
    }

    @Test
    fun `a neighbouring year's sync does not delete the lookahead row`() = runTest {
        val dao = InMemoryTimetableDao()
        val repository = repository(YearApi(nextSchoolDay = true), dao)

        repository.refresh()
        repository.refreshYear(2027)

        // The stored lookahead sits at a date that falls inside some window by
        // construction — it is the day *past* the one it was resolved for — so
        // a ranged delete that did not exclude it would sweep it up while
        // replacing a year it has nothing to do with.
        assertNotNull(dao.nextSchoolDay(1))
    }

    @Test
    fun `the window asked for is the year itself, with no anchor on today`() = runTest {
        val dao = InMemoryTimetableDao()
        val api = YearApi()
        val repository = repository(api, dao)

        repository.refresh()

        // The Monday anchor is gone with the whole-class wipe that needed it,
        // and so is the summer bug it carried: applied in July it reached back
        // far enough to ask for some 320 days, past what the server accepts,
        // and the window came back silently clipped in April.
        assertEquals(SchoolYear.start(2026).toString(), api.requested.single())
    }
}
