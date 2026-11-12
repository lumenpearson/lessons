package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.MethodSpec
import com.connectrpc.ProtocolClientConfig
import com.connectrpc.ResponseMessage
import com.connectrpc.http.Cancelable
import com.connectrpc.http.HTTPClientInterface
import com.connectrpc.http.HTTPResponse
import com.connectrpc.http.UnaryHTTPRequest
import com.connectrpc.impl.ProtocolClient
import com.connectrpc.okhttp.ConnectOkHttpClient
import com.connectrpc.protocols.NetworkProtocol
import okhttp3.OkHttpClient

internal class RpcCalls(
    private val http: (CallObservation) -> HTTPClientInterface,
    protocol: NetworkProtocol,
) {
    constructor(http: HTTPClientInterface, protocol: NetworkProtocol) : this({ http }, protocol)

    private val config = ProtocolClientConfig(
        host = "http://base-url.invalid/api/rpc",
        serializationStrategy = LiteProtobufStrategy(),
        networkProtocol = protocol,
        // The shared OkHttp client retains the existing measured read/call timeouts.
        timeoutOracle = { null },
    )

    suspend fun <Input : Any, Output : Any> unary(
        method: MethodSpec<Input, Output>,
        request: Input,
        observation: CallObservation = CallObservation(),
    ): Output {
        val client = http(observation)
        val observed = object : HTTPClientInterface by client {
            override fun unary(request: UnaryHTTPRequest, onResult: (HTTPResponse) -> Unit): Cancelable =
                client.unary(request) { response ->
                    observation.status = response.status ?: observation.status
                    observation.failure = response.cause?.exception ?: response.cause ?: observation.failure
                    onResult(response)
                }
        }
        return when (val result = ProtocolClient(observed, config).unary(request, emptyMap(), method)) {
            is ResponseMessage.Success -> result.message
            is ResponseMessage.Failure -> {
                val status = observation.status
                if (status == null) throw (observation.failure ?: result.cause.exception ?: result.cause)
                throw RpcRefusals.decode(result.cause, status)
            }
        }
    }

    companion object {
        fun using(client: OkHttpClient, protocol: NetworkProtocol): RpcCalls =
            RpcCalls({ observation -> ConnectOkHttpClient(client.observing(observation)) }, protocol)
    }
}
