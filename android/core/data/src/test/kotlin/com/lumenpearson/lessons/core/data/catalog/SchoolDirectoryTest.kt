package com.lumenpearson.lessons.core.data.catalog

import com.lumenpearson.lessons.core.data.diary.statusError
import com.lumenpearson.lessons.core.data.network.DirectoryApi
import com.lumenpearson.lessons.core.data.network.SchoolRegionDto
import com.lumenpearson.lessons.core.data.network.SchoolRegionsDto
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import java.io.IOException
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The school directory as the phone asks it: what is never asked, how every
 * refusal is told apart by its header, and how the answer is placed against the
 * bundled catalog.
 */
class SchoolDirectoryTest {

    private val catalog: RegionCatalog by lazy {
        RegionCatalog.parse(
            javaClass.classLoader!!.getResourceAsStream(RegionCatalog.ASSET)!!
                .bufferedReader(Charsets.UTF_8).use { it.readText() },
        )
    }

    private class FakeDirectoryApi(
        var answer: SchoolRegionsDto = SchoolRegionsDto(),
        var failure: Throwable? = null,
    ) : DirectoryApi {
        val asked = mutableListOf<String>()
        override suspend fun schoolRegions(query: String): SchoolRegionsDto {
            asked += query
            failure?.let { throw it }
            return answer
        }
    }

    private object NoSchools : DiarySchoolSearch {
        override suspend fun search(regionKey: String, query: String): Result<List<DiarySchool>> =
            Result.success(listOf(DiarySchool(1, "$regionKey:$query", null)))
    }

    private fun directory(api: DirectoryApi) = ServerSchoolDirectory(
        api = api,
        search = { RegionSearch(catalog) },
        schools = NoSchools,
        ioDispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `a query under three characters asks nothing`() = runTest {
        val api = FakeDirectoryApi()

        val result = directory(api).regionsForSchool("  л1 ")

        assertEquals(DirectoryProblem.TooShort, result.exceptionOrNull())
        assertTrue(api.asked.isEmpty())
    }

    /** A generic name places nothing and would spend the caller's searches and the shared allowance. */
    @Test
    fun `a generic name is answered here and asks nothing`() = runTest {
        val api = FakeDirectoryApi()

        val hits = directory(api).regionsForSchool("Школа № 5").getOrThrow()

        assertTrue(hits.generic)
        assertTrue(hits.hits.isEmpty())
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `the query is sent as the server normalises it`() = runTest {
        val api = FakeDirectoryApi()

        directory(api).regionsForSchool("  лицей   1535 ")

        assertEquals(listOf("лицей 1535"), api.asked)
    }

    @Test
    fun `hits are placed against the bundled catalog, and an unknown key is kept unplaced`() = runTest {
        val api = FakeDirectoryApi(
            answer = SchoolRegionsDto(
                query = "лицей 1535",
                regions = listOf(
                    SchoolRegionDto(region = "moscow", code = "77", schools = 3, cities = listOf("Москва")),
                    SchoolRegionDto(region = "somewhere-new", code = "99", schools = 1),
                    SchoolRegionDto(region = null, code = "94", label = "г Байконур", schools = 1),
                ),
                truncated = true,
            ),
        )

        val hits = directory(api).regionsForSchool("лицей 1535").getOrThrow()

        assertEquals("moscow", hits.hits[0].region?.key)
        assertNull(hits.hits[0].label)
        assertNull(hits.hits[1].region)
        assertEquals("somewhere-new", hits.hits[1].label)
        assertNull(hits.hits[2].region)
        assertEquals("г Байконур", hits.hits[2].label)
        assertTrue(hits.truncated)
    }

    @Test
    fun `every refusal is told apart by its header, not its words`() = runTest {
        val cases = listOf(
            statusError(503, DirectoryApi.UNAVAILABLE_HEADER to "disabled") to DirectoryProblem.Disabled,
            statusError(503, DirectoryApi.UNAVAILABLE_HEADER to "spent", "Retry-After" to "3600") to
                DirectoryProblem.Spent(3600),
            statusError(503, DirectoryApi.UNAVAILABLE_HEADER to "upstream") to DirectoryProblem.Upstream,
            statusError(503) to DirectoryProblem.Upstream,
            statusError(504) to DirectoryProblem.Upstream,
            statusError(429, "Retry-After" to "120") to DirectoryProblem.Throttled(120),
            statusError(422) to DirectoryProblem.TooShort,
            statusError(404) to DirectoryProblem.ServerTooOld,
            IOException("no network") to DirectoryProblem.Offline,
            ServerAddressMissingException() to DirectoryProblem.ServerMissing,
        )
        for ((failure, expected) in cases) {
            val result = directory(FakeDirectoryApi(failure = failure)).regionsForSchool("лицей 1535")
            assertEquals("$failure", expected, result.exceptionOrNull())
        }
        val odd = directory(FakeDirectoryApi(failure = statusError(500))).regionsForSchool("лицей 1535")
        assertTrue(odd.exceptionOrNull() is DirectoryProblem.Unexpected)
    }

    @Test
    fun `a region's own schools are the region's own server's`() = runTest {
        val schools = directory(FakeDirectoryApi()).providerSchools("samara", "лицей").getOrThrow()

        assertEquals("samara:лицей", schools.single().name)
    }

    // ---- the combined lookup -------------------------------------------------

    private fun lookup(api: DirectoryApi) = RegionLookup(
        search = { RegionSearch(catalog) },
        directory = directory(api),
    )

    @Test
    fun `a place is looked up in the catalog alone`() = runTest {
        val api = FakeDirectoryApi()

        val result = lookup(api).find("Тольятти")

        assertEquals(SchoolLookup.NotAsked, result.bySchool)
        assertEquals("samara", result.suggested.first().key)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a school is looked up in the directory, and its regions come first`() = runTest {
        val api = FakeDirectoryApi(
            answer = SchoolRegionsDto(
                query = "гимназия 1 казань",
                regions = listOf(SchoolRegionDto(region = "tatarstan", code = "16", schools = 2)),
            ),
        )

        val result = lookup(api).find("гимназия 1 казань")

        assertTrue(result.bySchool is SchoolLookup.Found)
        assertEquals("tatarstan", result.suggested.first().key)
        assertEquals(1, result.suggested.count { it.key == "tatarstan" })
    }

    @Test
    fun `a generic school name is said so, without a request`() = runTest {
        val api = FakeDirectoryApi()

        val result = lookup(api).find("школа 5")

        assertEquals(SchoolLookup.Generic, result.bySchool)
        assertTrue(api.asked.isEmpty())
    }

    @Test
    fun `a directory that fails leaves the catalog's answer standing`() = runTest {
        val api = FakeDirectoryApi(failure = statusError(503, DirectoryApi.UNAVAILABLE_HEADER to "spent"))

        val result = lookup(api).find("лицей 1535")

        assertEquals(SchoolLookup.Failed(DirectoryProblem.Spent(null)), result.bySchool)
    }
}
