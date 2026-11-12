package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.protocols.NetworkProtocol

internal enum class AppTransport {
    REST, CONNECT, GRPC;

    val rpcProtocol: NetworkProtocol get() = when (this) {
        REST -> error("REST has no RPC protocol")
        CONNECT -> NetworkProtocol.CONNECT
        GRPC -> NetworkProtocol.GRPC
    }

    companion object {
        fun parse(value: String): AppTransport = entries.single { it.name.lowercase() == value }
    }
}
