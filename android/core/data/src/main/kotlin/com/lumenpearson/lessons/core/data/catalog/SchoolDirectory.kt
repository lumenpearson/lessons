package com.lumenpearson.lessons.core.data.catalog

import com.lumenpearson.lessons.core.data.network.DirectoryApi
import com.lumenpearson.lessons.core.data.network.SchoolRegionsDto
import com.lumenpearson.lessons.core.data.network.ServerAddressMissingException
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.upstream.DiarySchool
import com.lumenpearson.lessons.core.data.upstream.DiarySchoolSearch
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.HttpException

/**
 * Finding a school, in the two places a school can be looked up.
 *
 *  * [regionsForSchool] asks our server's directory which regions a school of
 *    that name is in — for somebody who knows their school and not what their
 *    region is called in a list. Anonymous; a convenience only, because
 *    picking the region from the list always works, and every failure says so.
 *  * [providerSchools] asks a «Сетевой город» region's own server for its
 *    schools, phone to region directly — [DiarySchoolSearch].
 */
interface SchoolDirectory {

    /**
     * The regions a school called [query] may be in, best first.
     *
     * Asks nothing when the query is under [MIN_QUERY_LENGTH] characters
     * ([DirectoryProblem.TooShort]) or is a name too common to place
     * ([SchoolRegionHits.generic] with no hits) — the second saves the
     * server's allowance and the caller's own twenty searches a quarter-hour.
     * Every failure is a [DirectoryProblem].
     */
    suspend fun regionsForSchool(query: String): Result<SchoolRegionHits>

    /** A region's own school list; see [DiarySchoolSearch.search]. */
    suspend fun providerSchools(regionKey: String, query: String): Result<List<DiarySchool>>

    companion object {
        /** The server's `MIN_QUERY`: below it the directory ranks on too little. */
        const val MIN_QUERY_LENGTH = 3

        /**
         * How long typing has to pause before the phone asks (r04 §2.2): every
         * call is counted against the caller and spent from a shared allowance,
         * so never per keystroke.
         */
        const val DEBOUNCE_MILLIS = 600L
    }
}

/**
 * What the directory answered.
 *
 * @property truncated the directory's twenty-row ceiling was reached, so these
 *   are the regions of the first twenty matches.
 * @property generic the name is in too many regions to place; the screen says
 *   «выберите регион» rather than guessing.
 */
data class SchoolRegionHits(
    val query: String,
    val hits: List<SchoolRegionHit>,
    val truncated: Boolean,
    val generic: Boolean,
)

/**
 * One region the directory placed schools in.
 *
 * @property region the bundled catalog's row, or `null` when neither the
 *   server's catalog nor this phone's could place it — then [label] is the
 *   directory's own name for it, and it cannot be picked (no diary can be
 *   offered for a region the catalog does not know).
 */
data class SchoolRegionHit(
    val region: CatalogRegion?,
    val code: String?,
    val label: String?,
    val schools: Int,
    val cities: List<String>,
    val examples: List<String>,
)

/**
 * Why the directory did not answer. Every case ends the same way on screen —
 * «выберите регион из списка» — and differs only in whether waiting helps.
 */
sealed class DirectoryProblem(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** Under [SchoolDirectory.MIN_QUERY_LENGTH]; nothing was asked. */
    data object TooShort : DirectoryProblem("The query is too short to search with")

    /** `X-Directory-Unavailable: disabled`: this server has no directory key. */
    data object Disabled : DirectoryProblem("The school directory is not configured on this server")

    /** `spent`: today's anonymous allowance is gone; [retryAfterSeconds] is until it resets. */
    data class Spent(val retryAfterSeconds: Long?) :
        DirectoryProblem("The school directory's daily allowance is spent")

    /** `upstream`, or a bare `503`: the directory itself failed. */
    data object Upstream : DirectoryProblem("The school directory is not answering")

    /** `429`: this caller's twenty searches a quarter-hour are used. */
    data class Throttled(val retryAfterSeconds: Long?) : DirectoryProblem("Too many school searches")

    /** `404`: a server from before the directory. */
    data object ServerTooOld : DirectoryProblem("The server has no school directory")

    /** No server address is set. */
    data object ServerMissing : DirectoryProblem("No server address is set")

    /** No answer at all. */
    data object Offline : DirectoryProblem("Could not reach the server")

    /** Anything else, kept with its cause for a bug report. */
    class Unexpected(val detail: String?, cause: Throwable? = null) :
        DirectoryProblem("Unexpected directory failure: ${detail ?: "unknown"}", cause)

    companion object {

        /** Classifies whatever a directory call threw; never a cancellation. */
        fun of(failure: Throwable): DirectoryProblem = when (failure) {
            is CancellationException -> throw failure
            is DirectoryProblem -> failure
            is HttpException -> ofHttp(failure)
            is IOException ->
                if (generateSequence<Throwable>(failure) { it.cause }.take(8).any { it is ServerAddressMissingException }) {
                    ServerMissing
                } else {
                    Offline
                }
            else -> Unexpected(failure::class.simpleName, failure)
        }

        private fun ofHttp(failure: HttpException): DirectoryProblem = when (failure.code()) {
            404 -> ServerTooOld
            // The server's length rule, met here only by a phone whose own
            // check disagreed with it.
            422 -> TooShort
            429 -> Throttled(DiaryFailure.retryAfterSeconds(failure))
            // The header, not the Russian `detail`, says which 503 this is.
            503 -> when (
                failure.response()?.headers()?.get(DirectoryApi.UNAVAILABLE_HEADER)?.trim()?.lowercase()
            ) {
                "disabled" -> Disabled
                "spent" -> Spent(DiaryFailure.retryAfterSeconds(failure))
                else -> Upstream
            }
            // The platform's own ceiling; the directory was slow, not wrong.
            504 -> Upstream
            else -> Unexpected("HTTP ${failure.code()}", failure)
        }
    }
}

/**
 * [SchoolDirectory] over our server's directory and the regions' own servers.
 *
 * @param search the search over the bundled catalog, built on first use off
 *   the main thread: its generic-name rule is the server's, from the same file.
 */
internal class ServerSchoolDirectory(
    private val api: DirectoryApi,
    private val search: suspend () -> RegionSearch,
    private val schools: DiarySchoolSearch,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SchoolDirectory {

    override suspend fun regionsForSchool(query: String): Result<SchoolRegionHits> = withContext(ioDispatcher) {
        // As the server normalises: whitespace collapsed, then counted.
        val asked = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        if (asked.codePointCount(0, asked.length) < SchoolDirectory.MIN_QUERY_LENGTH) {
            return@withContext Result.failure(DirectoryProblem.TooShort)
        }
        try {
            val search = search()
            if (search.isGenericSchool(asked)) {
                return@withContext Result.success(
                    SchoolRegionHits(query = asked, hits = emptyList(), truncated = false, generic = true),
                )
            }
            Result.success(api.schoolRegions(asked).toHits(search.catalog))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(DirectoryProblem.of(failure))
        }
    }

    override suspend fun providerSchools(regionKey: String, query: String): Result<List<DiarySchool>> =
        schools.search(regionKey, query)
}

/**
 * The server's answer, placed against the bundled catalog. A key this build's
 * catalog does not know — a region the server's newer catalog added — is kept
 * as unplaced, with the code as its label when the directory gave no other,
 * rather than dropped: the person still learns where the school is.
 */
internal fun SchoolRegionsDto.toHits(catalog: RegionCatalog): SchoolRegionHits = SchoolRegionHits(
    query = query,
    hits = regions.map { row ->
        val region = catalog.region(row.region)
        SchoolRegionHit(
            region = region,
            code = row.code ?: region?.code,
            label = if (region == null) row.label ?: row.region else null,
            schools = row.schools,
            cities = row.cities,
            examples = row.examples,
        )
    },
    truncated = truncated,
    generic = generic,
)
