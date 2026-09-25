package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiaryRegistration
import com.lumenpearson.lessons.core.data.repository.DiarySignIn
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import com.lumenpearson.lessons.core.data.upstream.UpstreamSession
import kotlinx.coroutines.CancellationException

/**
 * [DiarySignIn], with the pupils the registration answered written into the
 * cache — which is what lets the import skip asking for them a second time
 * seconds later (every pupil list is an upstream call behind ours).
 *
 * A decorator rather than a line inside the sign-in, so the sign-in keeps no
 * idea that a cache exists: it already empties the cache through its
 * `forgetLocal` hook when the account changes, and this runs after that, so
 * the pupils land in a cache that belongs to the account that just signed in.
 * The generation is taken after the registration returns for the same reason.
 */
internal class SeedingDiarySignIn(
    private val delegate: DiarySignIn,
    private val cache: DiaryCacheWriter,
) : DiarySignIn by delegate {

    override suspend fun register(upstream: UpstreamSession): Result<DiaryRegistration> =
        delegate.register(upstream).also { seed(it) }

    override suspend fun signIn(target: DiaryTarget, password: String): Result<DiaryRegistration> =
        delegate.signIn(target, password).also { seed(it) }

    private suspend fun seed(result: Result<DiaryRegistration>) {
        val students = result.getOrNull()?.students?.takeIf { it.isNotEmpty() } ?: return
        try {
            cache.putStudents(cache.generation(), students)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            // The sign-in succeeded; a cache that could not take the list only
            // costs the import one request.
        }
    }
}
