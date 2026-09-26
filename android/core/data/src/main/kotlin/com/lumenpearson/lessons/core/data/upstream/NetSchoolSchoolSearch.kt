package com.lumenpearson.lessons.core.data.upstream

import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request

/** One school a «Сетевой город» region lists. [address] is what tells apart its many «Школа № 5». */
data class DiarySchool(val id: Long, val name: String, val address: String?)

/**
 * The schools of one «Сетевой город» region, asked of the region's own server.
 *
 * Straight from the phone, not through ours: the phone is about to sign in to
 * exactly that server, so a search that cannot reach it is the honest early
 * warning that the sign-in could not either; nothing new is opened on our
 * server for anybody to spend; and we learn nothing about which school a family
 * looked for.
 *
 * A failure is a [DiarySignInProblem], the same type the sign-in fails with,
 * so the screen says one sentence about a region that refuses the phone,
 * whichever step found it out.
 */
interface DiarySchoolSearch {

    /**
     * Schools whose name matches [query] in the region under [regionKey],
     * as the region's server ranks them, at most [MAX_ROWS].
     *
     * A query shorter than [MIN_QUERY_LENGTH] once trimmed is answered with an
     * empty list and no request.
     */
    suspend fun search(regionKey: String, query: String): Result<List<DiarySchool>>

    companion object {
        /** The bot's minimum too; one letter matches half a region. */
        const val MIN_QUERY_LENGTH: Int = 2

        /**
         * How long the screen waits after the last keystroke before asking.
         * One constant for every screen that searches schools, so the upstream
         * sees one person typing, not one request per letter.
         */
        const val DEBOUNCE_MILLIS: Long = 500

        /** More than a screen can usefully show; past it the query needs another word. */
        const val MAX_ROWS: Int = 50
    }
}

/**
 * [DiarySchoolSearch] over `GET {origin}/webapi/schools/search?name=…`.
 *
 * The Kotlin twin of the server's `NetSchoolClient.schools_search`, held to it
 * by the `schools_search` cases of the shared vectors: the same query cut, the
 * same refusal rule, and the same row rule — a row counts only with an `id`
 * that is a JSON integer (not a string of digits, not `true`, not `6.0`) and a
 * name that is non-blank text.
 *
 * No session rides a search: the region answers it to its own login page, and
 * there is no session yet anyway. The query is a query *parameter* — the only
 * user input that ever reaches a diary's URL — and never part of the host.
 */
internal class NetSchoolSchoolSearch(
    private val client: () -> OkHttpClient,
    private val directory: () -> UpstreamDirectory,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiarySchoolSearch {

    override suspend fun search(regionKey: String, query: String): Result<List<DiarySchool>> =
        withContext(ioDispatcher) {
            val region = directory().netschool(regionKey)
                ?: return@withContext Result.failure(DiarySignInProblem.RegionNotServed)
            // A region that takes Госуслуги only is sent nothing: there is no
            // school to pick for a sign-in this app cannot make.
            if (!region.password) {
                return@withContext Result.failure(DiarySignInProblem.GosuslugiOnly(region.handoffUrl))
            }
            val wanted = query.trim()
            if (wanted.codePointCount(0, wanted.length) < DiarySchoolSearch.MIN_QUERY_LENGTH) {
                return@withContext Result.success(emptyList())
            }
            try {
                Result.success(fetch(region, wanted))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                Result.failure(DiarySignInProblem.of(failure, host = region.origin.host))
            }
        }

    private suspend fun fetch(region: NetSchoolRegion, query: String): List<DiarySchool> {
        val url = region.origin.newBuilder()
            .encodedPath(PATH)
            .addQueryParameter(QUERY_PARAMETER, cut(query))
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Accept", NetSchoolSignIn.ACCEPT)
            .header("X-Requested-With", "XMLHttpRequest")
            .get()
            .build()
        val answer = UpstreamHttp.fetch(client(), request)
        if (NetSchoolSignIn.looksRefused(answer)) throw UpstreamFailure.AddressRefused()
        return rows(NetSchoolSignIn.decode(answer) as? JsonArray ?: throw UpstreamFailure.Unexpected())
            .take(DiarySchoolSearch.MAX_ROWS)
    }

    internal companion object {
        const val PATH = "/webapi/schools/search"
        const val QUERY_PARAMETER = "name"

        /** The server's `query[:60]`: whole code points, never half a surrogate pair. */
        const val QUERY_CUT_CODE_POINTS = 60

        fun cut(query: String): String =
            if (query.codePointCount(0, query.length) <= QUERY_CUT_CODE_POINTS) {
                query
            } else {
                query.substring(0, query.offsetByCodePoints(0, QUERY_CUT_CODE_POINTS))
            }

        /** The rows worth showing, by the server's rule; see the class comment. */
        fun rows(body: JsonArray): List<DiarySchool> = body.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            val id = UpstreamJson.integer(row["id"])
                ?.takeIf { it.bitLength() < 64 }
                ?.toLong()
                ?: return@mapNotNull null
            val name = UpstreamJson.string(row["name"])?.trim()?.takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val address = UpstreamJson.string(row["addressString"])?.trim()?.takeIf { it.isNotEmpty() }
            DiarySchool(id = id, name = name, address = address)
        }
    }
}
