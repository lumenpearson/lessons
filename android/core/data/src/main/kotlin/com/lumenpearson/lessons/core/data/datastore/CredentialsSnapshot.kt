package com.lumenpearson.lessons.core.data.datastore

import com.lumenpearson.lessons.core.data.network.RequestCredentials
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The request credentials, held in memory for the one reader that cannot
 * suspend.
 *
 * OkHttp runs an interceptor on its own dispatcher thread and gives it no way
 * to suspend, so the three of them used to read the preferences through
 * `runBlocking`: a DataStore round trip on a parked thread for the address and
 * another for the bearer, on every request the app made. This is what they
 * read instead — a field.
 *
 * It is kept current two ways, and both are needed.
 *
 *  * **After every write, in the writer's own coroutine** ([refresh], called by
 *    [LessonsPreferences] once its `edit` has returned). A switch of class or
 *    of server is followed at once by a request — the join asks for a sync, the
 *    settings screen asks the new server how it is — and a copy that waited for
 *    DataStore's emission to reach a collector would sign that request for the
 *    class the user had just left, or send it to the old address.
 *  * **On every emission of the file** ([follow]), for a write that does not go
 *    through the instance holding this copy: `SchoolAlerts` opens preferences
 *    of its own.
 *
 * Both re-read the store under one lock rather than publishing the value they
 * were handed, and that is what keeps the copy from ever going backwards. An
 * emission can be delivered after a later write has already been published,
 * and publishing *it* would put the old class's token back until the next
 * emission arrived. DataStore's current value only moves forward, so reads
 * that are each published before the next one begins only move forward too.
 */
internal class CredentialsSnapshot(
    private val read: suspend () -> RequestCredentials,
) {
    @Volatile
    private var held: RequestCredentials? = null

    private val publishing = Mutex()

    /**
     * For an interceptor, and for nothing else: memory, once anything at all
     * has been read.
     *
     * The exception is a request that arrives before the first read of the
     * process has landed — the widget's first sync after a cold start can get
     * there before [follow] has had its first emission — and that one reads the
     * store, blocking, on OkHttp's thread. That thread is not a coroutine and
     * is never the main thread, and an empty copy is not an answer: it would
     * send the request to no address with no bearer, and a sync that fails for
     * that reason says nothing true about the phone.
     */
    fun current(): RequestCredentials = held ?: runBlocking { refresh() }

    /** Reads the store again and publishes what it says; see the class notes. */
    suspend fun refresh(): RequestCredentials = publishing.withLock {
        read().also { held = it }
    }

    /**
     * Keeps the copy current for as long as [changes] emits, which from the
     * container's scope is the life of the process. What each emission carries
     * is ignored on purpose; see the class notes.
     */
    suspend fun follow(changes: Flow<*>) {
        changes.collect { refresh() }
    }
}
