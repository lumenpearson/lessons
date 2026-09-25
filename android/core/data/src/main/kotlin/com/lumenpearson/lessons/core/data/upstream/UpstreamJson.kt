package com.lumenpearson.lessons.core.data.upstream

import java.math.BigInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Reading a diary's JSON the way the server's Python reads it.
 *
 * The ports are held to the server by the shared vectors, and the vectors are
 * full of answers that are the wrong shape on purpose — a `cacheVer` of `true`,
 * a `salt` of `333`, an `id` of `6.0`. Python's `isinstance` checks and
 * truthiness decide those, so these helpers spell the same decisions out, one
 * each, rather than leaving every call site to guess what `if not x` meant.
 */
internal object UpstreamJson {

    private val json = Json { isLenient = false }

    private val INTEGER = Regex("-?(0|[1-9][0-9]*)")

    /**
     * The same answer as Python's `json.loads`: the literals it reads, and
     * nothing else. JavaScript's `NaN` and `Infinity` are among them.
     */
    private val LITERAL = Regex("true|false|null|NaN|-?Infinity|-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][-+]?[0-9]+)?")

    /**
     * The parsed body, or `null` for one that is not JSON at all — the server's
     * `response.json()` raising `ValueError`, held to it by the vectors'
     * `raw_answer_cases`.
     *
     * kotlinx reads any unquoted token as a literal, lenient or not: «<html>»,
     * «Error» and «01» on their own, `False` or `abc` as a value inside an
     * object. Each of those is a refusal to the server, which calls it
     * «unexpected»; the phone used to carry on with a word, and then called a
     * firewall page a dead diary and a garbled login answer a wrong password.
     */
    fun parse(text: String): JsonElement? {
        val element = try {
            json.parseToJsonElement(text)
        } catch (_: Exception) {
            return null
        }
        return element.takeIf(::strict)
    }

    /**
     * Walked with a stack of its own rather than by recursion: kotlinx parses a
     * two-megabyte «[[[[…» without overflowing, and a recursive walk over what
     * it built would not.
     */
    private fun strict(root: JsonElement): Boolean {
        val pending = ArrayDeque<JsonElement>().apply { add(root) }
        while (pending.isNotEmpty()) {
            when (val element = pending.removeLast()) {
                is JsonObject -> pending.addAll(element.values)
                is JsonArray -> pending.addAll(element)
                is JsonNull -> Unit
                is JsonPrimitive -> if (!element.isString && !LITERAL.matches(element.content)) return false
            }
        }
        return true
    }

    /**
     * A JSON string as itself, a JSON integer as its digits, and anything else
     * as `""` — the server's `_scalar_text`. A `true` is not `"true"` and a
     * missing value is not `"null"`: both used to be posted back upstream as
     * words the diary never sent.
     */
    fun scalarText(value: JsonElement?): String {
        val primitive = value as? JsonPrimitive ?: return ""
        if (primitive is JsonNull) return ""
        if (primitive.isString) return primitive.content
        return integerOrNull(primitive)?.toString().orEmpty()
    }

    /** A JSON integer, or `null` — never a bool, a float, or a string of digits. */
    fun integer(value: JsonElement?): BigInteger? {
        val primitive = value as? JsonPrimitive ?: return null
        if (primitive is JsonNull || primitive.isString) return null
        return integerOrNull(primitive)
    }

    /** A JSON string's content, or `null` for anything else. */
    fun string(value: JsonElement?): String? =
        (value as? JsonPrimitive)?.takeIf { it !is JsonNull && it.isString }?.content

    /**
     * Python's truthiness: `null`, `false`, zero, `""`, `[]` and `{}` are
     * false; everything else, including the string `"false"`, is true.
     */
    fun truthy(value: JsonElement?): Boolean = when (value) {
        null, JsonNull -> false
        is JsonObject -> value.isNotEmpty()
        is JsonArray -> value.isNotEmpty()
        is JsonPrimitive -> when {
            value.isString -> value.content.isNotEmpty()
            value.content == "true" -> true
            value.content == "false" -> false
            else -> value.content.toBigDecimalOrNull()?.signum() != 0
        }
    }

    private fun integerOrNull(primitive: JsonPrimitive): BigInteger? =
        primitive.content.takeIf { INTEGER.matches(it) }?.let(::BigInteger)
}

/**
 * The two ways a stored session value can go into a header and stay one
 * value, shared with the server (`providers/diary/http.py`) and pinned to it by
 * the vectors: an RFC 6265 cookie octet run, and an RFC 7235 `token68`.
 */
internal object UpstreamValues {

    /** A cookie value that cannot become two cookies: no `;`, space, quote, comma or backslash. */
    val COOKIE_VALUE: Regex = Regex("[\\x21\\x23-\\x2B\\x2D-\\x3A\\x3C-\\x5B\\x5D-\\x7E]{1,4096}")

    /** A bearer-shaped header value that cannot end the header and start another. */
    val HEADER_TOKEN: Regex = Regex("[A-Za-z0-9._~+/=-]+")

    fun cookieValueOk(value: String?): Boolean = value != null && COOKIE_VALUE.matches(value)

    fun headerValueOk(value: String?): Boolean = value != null && HEADER_TOKEN.matches(value)
}
