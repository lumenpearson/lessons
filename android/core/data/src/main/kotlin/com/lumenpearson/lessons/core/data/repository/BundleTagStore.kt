package com.lumenpearson.lessons.core.data.repository

/**
 * Where the last `/bundle` ETag is kept between syncs.
 *
 * The server has always answered `304` to a matching `If-None-Match` — the
 * point being that a widget's timer poll costs a hash comparison rather than a
 * school year of JSON — and the client has never sent one, so every sync
 * downloaded the whole window. There is nothing to compare against unless the
 * tag survives the process, because the syncs that matter are a cold start and
 * a worker waking up.
 *
 * Keyed by a *signature* rather than stored loose: a tag is only valid for the
 * exact request that produced it, and this app changes that request twice a
 * year (1 September moves the window) and whenever the phone switches class. A
 * signature that does not match is simply no tag, which asks for the whole
 * window — the safe direction, and the one that heals by itself.
 */
internal interface BundleTagStore {

    /** The tag stored for [signature], or `null` for anything else. */
    suspend fun tagFor(signature: String): String?

    /** Replaces whatever was there: one window is remembered, not a history. */
    suspend fun remember(signature: String, etag: String)

    companion object {
        /** Remembers nothing, so every sync asks for the whole window. */
        val None: BundleTagStore = object : BundleTagStore {
            override suspend fun tagFor(signature: String): String? = null
            override suspend fun remember(signature: String, etag: String) = Unit
        }
    }
}
