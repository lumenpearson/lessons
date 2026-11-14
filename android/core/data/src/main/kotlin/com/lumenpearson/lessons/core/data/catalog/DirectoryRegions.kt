package com.lumenpearson.lessons.core.data.catalog

/** An unplaced directory reply; only the bundled allow-list can offer a diary host. */
internal data class DirectoryRegions(
    val query: String = "",
    val regions: List<DirectoryRegion> = emptyList(),
    val truncated: Boolean = false,
    val generic: Boolean = false,
)

internal data class DirectoryRegion(
    val region: String? = null,
    val code: String? = null,
    val label: String? = null,
    val schools: Int = 0,
    val cities: List<String> = emptyList(),
    val examples: List<String> = emptyList(),
)
