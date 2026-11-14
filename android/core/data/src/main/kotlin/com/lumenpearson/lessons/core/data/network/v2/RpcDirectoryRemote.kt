package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.StreamType
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsRequest
import com.lumenpearson.lessons.contract.v2.ListSchoolRegionsResponse
import com.lumenpearson.lessons.core.data.catalog.DirectoryRegion
import com.lumenpearson.lessons.core.data.catalog.DirectoryRegions

internal class RpcDirectoryRemote(private val rpc: RpcCalls) : DirectoryRemote {
    override suspend fun schoolRegions(query: String): DirectoryRegions {
        val value = rpc.unary(Method, ListSchoolRegionsRequest.newBuilder().setQuery(query).build(), CallObservation())
        return DirectoryRegions(value.query, value.regionsList.map {
            DirectoryRegion(it.region.takeIf { _ -> it.hasRegion() }, it.code.takeIf { _ -> it.hasCode() },
                it.label.takeIf { _ -> it.hasLabel() }, it.schools, it.citiesList, it.examplesList)
        }, value.truncated, value.generic)
    }

    private companion object {
        val Method = MethodSpec("lessons.v2.DirectoryService/ListSchoolRegions",
            ListSchoolRegionsRequest::class, ListSchoolRegionsResponse::class, StreamType.UNARY)
    }
}
