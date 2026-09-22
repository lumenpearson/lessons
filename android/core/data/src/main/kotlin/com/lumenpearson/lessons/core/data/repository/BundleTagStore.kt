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
 * every wipe of the cache takes the matching tags with it, so the number of
 * tags kept is the number of windows kept. A store that grew on its own would
 * be a preferences file that only ever gets bigger, one entry per year anybody
 * ever scrolled past.
 *
 * That was written before it was true. [forget] had exactly one caller — the
 * eviction inside `prune` — and nothing on the path of leaving a class reached
 * it, so a phone accumulated one entry per class-year it had ever synced and
 * then left, for the life of the install. Nothing on screen showed it: a re-join sends the
 * stale tag, the server matches it, and the `304` is caught by the check that
 * this phone holds no such window. That is why the three wipes below exist in
 * this interface at all — one per shape of wipe the cache has.
 */
internal interface BundleTagStore {

    /** The tag stored for [signature], or `null` for anything else. */
    suspend fun tagFor(signature: String): String?

    /** Remembers this window's tag, leaving the other windows' alone. */
    suspend fun remember(signature: String, etag: String)

    /** Drops one window's tag, for a year being evicted from the cache. */
    suspend fun forget(signature: String)

    /**
     * Drops every year's tag for one class, for a class being left or joined.
     *
     * Both ends of that sentence are the same event as far as this store is
     * concerned: the rows go, so the tags that describe them have to go too.
     */
    suspend fun forgetClass(classId: Long)

    /**
     * Drops the tags of every class outside [keep].
     *
     * The twin of `TimetableDao.retainOnly`, and an empty [keep] means the same
     * thing there as it does here: everything. That is what a full sign-out is.
     */
    suspend fun forgetClassesOtherThan(keep: Collection<Long>)

    companion object {
        /** Remembers nothing, so every sync asks for the whole window. */
        val None: BundleTagStore = object : BundleTagStore {
            override suspend fun tagFor(signature: String): String? = null
            override suspend fun remember(signature: String, etag: String) = Unit
            override suspend fun forget(signature: String) = Unit
            override suspend fun forgetClass(classId: Long) = Unit
            override suspend fun forgetClassesOtherThan(keep: Collection<Long>) = Unit
        }
    }
}
