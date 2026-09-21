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
 * exact request that produced it, and this app changes that request whenever
 * the phone switches class or looks at another school year. A signature that
 * does not match is simply no tag, which asks for the whole window — the safe
 * direction, and the one that heals by itself.
 *
 * **One tag per window, not one tag.** While this remembered a single
 * signature, the cache held a single window and the two matched. Now the cache
 * holds a school year per row, and a store with room for one would have made
 * every step between two years a full download in both directions — the very
 * cost the conditional request exists to avoid, paid twice per scroll.
 *
 * What bounds it is the cache's own cap rather than a second rule of its own:
 * [forget] is called when a year is evicted, so the number of tags kept is the
 * number of windows kept. A store that grew on its own would be a preferences
 * file that only ever gets bigger, one entry per year anybody ever scrolled
 * past.
 */
internal interface BundleTagStore {

    /** The tag stored for [signature], or `null` for anything else. */
    suspend fun tagFor(signature: String): String?

    /** Remembers this window's tag, leaving the other windows' alone. */
    suspend fun remember(signature: String, etag: String)

    /** Drops one window's tag, for a year being evicted from the cache. */
    suspend fun forget(signature: String)

    companion object {
        /** Remembers nothing, so every sync asks for the whole window. */
        val None: BundleTagStore = object : BundleTagStore {
            override suspend fun tagFor(signature: String): String? = null
            override suspend fun remember(signature: String, etag: String) = Unit
            override suspend fun forget(signature: String) = Unit
        }
    }
}
