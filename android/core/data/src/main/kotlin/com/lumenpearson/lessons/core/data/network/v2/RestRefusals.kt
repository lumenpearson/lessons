package com.lumenpearson.lessons.core.data.network.v2

import com.connectrpc.Code
import java.io.IOException
import java.time.Duration
import java.time.format.DateTimeParseException
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import retrofit2.Response

internal object RestRefusals {
    fun decode(status: Int, body: String, retryAfter: String? = null): RemoteError {
        val envelope = envelope(body)
        val details = (envelope?.get("details") as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
        val info = details.firstOrNull { it.detailType() == "google.rpc.ErrorInfo" }
        val violations = details.filter { it.detailType() == "google.rpc.BadRequest" }.flatMap { detail ->
            (detail["fieldViolations"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>().map {
                RemoteViolation(it.text("field").orEmpty(), it.text("description").orEmpty())
            }
        }
        val retry = details.firstOrNull { it.detailType() == "google.rpc.RetryInfo" }
        return RemoteError(
            code = Code.fromName(envelope?.text("status")?.lowercase(), canonicalHttpCode(status)),
            reason = info?.text("reason"),
            metadata = (info?.get("metadata") as? JsonObject).orEmpty().mapNotNull { (key, value) ->
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content?.let { key to it }
            }.toMap(),
            message = envelope?.text("message") ?: "HTTP $status",
            httpStatus = status,
            violations = violations,
            retryDelay = retry?.text("retryDelay")?.let(::retryDuration)
                ?: retryAfter?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Duration::ofSeconds),
        )
    }

    fun <T> body(response: Response<T>): T {
        if (!response.isSuccessful) throw refusal(response)
        return response.body() ?: throw malformedV2Payload("The server returned no response body", response.code())
    }

    private fun refusal(response: Response<*>): RemoteError = try {
        decode(response.code(), response.errorBody()?.string().orEmpty(), response.headers()["Retry-After"])
    } catch (failure: IOException) {
        RemoteError(canonicalHttpCode(response.code()), httpStatus = response.code(), cause = failure)
    }

    suspend fun <T> request(
        observation: CallObservation = CallObservation(),
        send: suspend (CallObservation) -> Response<T>,
    ): Response<T> {
        return try {
            send(observation)
        } catch (failure: IOException) {
            throw observedOrOriginal(observation, failure)
        } catch (failure: SerializationException) {
            throw observedOrOriginal(observation, failure)
        }
    }

    private fun observedOrOriginal(observation: CallObservation, failure: Exception): Throwable {
        val original = observation.failure ?: failure
        val status = observation.status
        return if (status == null) original else RemoteError(
            code = canonicalHttpCode(status), httpStatus = status,
            message = "The server response could not be read", cause = original,
        )
    }

    private fun envelope(body: String): JsonObject? = try {
        (Json.parseToJsonElement(body) as? JsonObject)?.get("error") as? JsonObject
    } catch (_: SerializationException) {
        null
    }

    private fun JsonObject.text(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.detailType(): String? = text("@type")?.substringAfterLast('/')

    private fun retryDuration(value: String): Duration? = try {
        if (value.endsWith('s')) Duration.parse("PT${value.uppercase()}").takeUnless { it.isNegative } else null
    } catch (_: DateTimeParseException) {
        null
    }
}
