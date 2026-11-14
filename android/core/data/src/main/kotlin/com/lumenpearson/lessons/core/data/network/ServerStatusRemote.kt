package com.lumenpearson.lessons.core.data.network

import com.lumenpearson.lessons.core.data.network.dto.WarmupDto
import com.lumenpearson.lessons.core.data.repository.ServerStatus
import kotlinx.coroutines.CancellationException
import retrofit2.http.GET

/** Metadata deliberately stays plain HTTP, independent of the selected business transport. */
internal fun interface ServerStatusRemote {
    suspend fun read(): ServerStatus
}

internal interface ServerStatusApi {
    @GET("api/v1/warmup")
    suspend fun warmup(): WarmupDto
}

internal class RestServerStatusRemote(private val warmup: suspend () -> WarmupDto) : ServerStatusRemote {
    override suspend fun read(): ServerStatus = try {
        val body = warmup()
        when (body.status) {
            "ok" -> ServerStatus.Ok(apiVersion = body.apiVersion, schema = body.revision)
            else -> ServerStatus.Degraded(schema = body.revision, expected = body.expectedSchema, detail = body.detail)
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: ServerNeedsHttpsException) {
        ServerStatus.NeedsHttps
    } catch (_: Exception) {
        // A URL-bearing transport exception must not become the settings badge's text.
        ServerStatus.Unreachable
    }
}
