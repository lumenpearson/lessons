package com.lumenpearson.lessons.core.data.catalog

/**
 * The region step's one question — «which region do you mean» — answered from
 * both places that know: the bundled catalog, for a region's own name, alias,
 * city or code, and the server's directory, for the name of a school.
 *
 * The directory is asked only when the query [RegionSearch.looksLikeSchool] and
 * is not [generic][RegionSearch.isGenericSchool]: a region's name costs no
 * request and a generic school name places nothing, and every directory call
 * is counted against this phone and spent from an allowance the bot needs too.
 * The screen debounces ([SchoolDirectory.DEBOUNCE_MILLIS]); this runs one
 * lookup per call.
 */
class RegionLookup internal constructor(
    private val search: suspend () -> RegionSearch,
    private val directory: SchoolDirectory,
) {

    /** The bundled catalog, for the screens that list regions and their diaries. */
    suspend fun catalog(): RegionCatalog = search().catalog

    /** The catalog alone, with no request: for filtering the list as somebody types. */
    suspend fun byName(query: String): List<RegionMatch> = search().search(query)

    /** Both, combined; see [RegionLookupResult]. */
    suspend fun find(query: String): RegionLookupResult {
        val search = search()
        val byName = search.search(query)
        val bySchool = when {
            query.isBlank() || !search.looksLikeSchool(query) -> SchoolLookup.NotAsked
            search.isGenericSchool(query) -> SchoolLookup.Generic
            else -> directory.regionsForSchool(query).fold(
                onSuccess = { hits -> if (hits.generic) SchoolLookup.Generic else SchoolLookup.Found(hits) },
                onFailure = { failure -> SchoolLookup.Failed(DirectoryProblem.of(failure)) },
            )
        }
        return RegionLookupResult(query = query, byName = byName, bySchool = bySchool)
    }
}

/** What the directory said about a school name, if it was asked. */
sealed interface SchoolLookup {
    /** The query is a place, not a school; nothing was asked. */
    data object NotAsked : SchoolLookup

    /** Too common to place — «школа № 5» is in every region. «Выберите регион». */
    data object Generic : SchoolLookup

    data class Found(val hits: SchoolRegionHits) : SchoolLookup

    /** Every failure means «выберите регион из списка»; the problem says whether waiting helps. */
    data class Failed(val problem: DirectoryProblem) : SchoolLookup
}

/**
 * @property byName the catalog's own matches, best first.
 * @property bySchool the directory's answer about a school of that name.
 */
data class RegionLookupResult(
    val query: String,
    val byName: List<RegionMatch>,
    val bySchool: SchoolLookup,
) {
    /**
     * The regions to offer, once each: where the directory placed the school
     * first, in its order, then the catalog's own matches. A region the
     * directory named but the catalog cannot place is not here — it cannot be
     * picked — and is in [bySchool] for the screen to mention.
     */
    val suggested: List<CatalogRegion>
        get() {
            val placed = (bySchool as? SchoolLookup.Found)?.hits?.hits.orEmpty().mapNotNull { it.region }
            return (placed + byName.map { it.region }).distinctBy { it.key }
        }
}
