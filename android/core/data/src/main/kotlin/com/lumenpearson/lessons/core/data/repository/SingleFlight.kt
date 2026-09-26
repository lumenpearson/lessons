package com.lumenpearson.lessons.core.data.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * At most one run of a piece of work per key at a time; a second caller for a
 * key already running waits for that run and takes its answer.
 *
 * Nothing is remembered after a run ends: a caller arriving later starts a new
 * one. This coalesces requests that overlap, not requests that repeat.
 *
 * If the caller that started a run is cancelled rather than finished, the
 * callers waiting on it are not — they were never asked to stop — so each of
 * them starts the work again for itself instead of inheriting the cancellation.
 */
internal class SingleFlight<K : Any, V> {

    private val lock = Mutex()
    private val running = HashMap<K, CompletableDeferred<V>>()

    suspend fun run(key: K, block: suspend () -> V): V {
        val (flight, owner) = lock.withLock {
            // A run that has ended but whose owner has not yet taken its entry
            // out is not joined: its answer is already spent, and a waiter
            // retrying after a cancelled owner would otherwise find the same
            // dead run again and again until the owner got the lock back.
            val existing = running[key]?.takeIf { it.isActive }
            if (existing != null) {
                existing to false
            } else {
                CompletableDeferred<V>().also { running[key] = it } to true
            }
        }
        if (!owner) {
            return try {
                flight.await()
            } catch (cancelled: CancellationException) {
                // Either this caller was cancelled, which the check rethrows, or
                // the one it was waiting on was, and the work is still wanted.
                currentCoroutineContext().ensureActive()
                run(key, block)
            }
        }
        try {
            return block().also { flight.complete(it) }
        } catch (failure: Throwable) {
            flight.completeExceptionally(failure)
            throw failure
        } finally {
            // Non-cancellable: a cancelled owner still has to take its entry
            // out, or every later caller would wait on a run that is over.
            withContext(NonCancellable) {
                lock.withLock { if (running[key] === flight) running.remove(key) }
            }
        }
    }
}
