package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.catalog.DirectoryRegions

internal interface DirectoryRemote {
    suspend fun schoolRegions(query: String): DirectoryRegions
}
