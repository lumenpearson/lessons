package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.diary.DiaryCacheWriter
import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryResetRequestDto
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where the diary session is kept.
 *
 * An interface with one production implementation — `LessonsPreferences` — so
 * that the repository can be exercised without DataStore, and so that the only
 * thing this repository is allowed to do to storage is written down here. It
 * cannot reach the class token from here, which is the mechanical half of
 * "signing out of one does not touch the other".
 *
 * Two ways to let go, because a session and the account it belongs to die at
 * different times: [clearDiaryToken] is a bare `401` — our bearer is dead, but
 * which diary, region, school and login this phone uses is not, so the next
 * screen asks for the same account's password instead of starting over —
 * and [forgetDiary] is signing out, which forgets all of it.
 */
internal interface DiarySessionStore {
    val diarySession: Flow<DiarySession?>
    suspend fun currentDiarySession(): DiarySession?

    /** Our bearer, the login and the target, written together. */
    suspend fun writeDiarySession(value: DiarySession)

    /** The bearer only; the target and the login stay. */
    suspend fun clearDiaryToken()

    /** The bearer, the login, the target and the chosen pupil. */
    suspend fun forgetDiary()

    /**
     * Which diary this phone signs in to, with or without a live bearer: it
     * outlives a bare `401` and ends only at [forgetDiary].
     */
    val diaryTarget: Flow<DiaryTarget?>
    suspend fun currentDiaryTarget(): DiaryTarget?

    /** The pupil the diary shows, when the account has several. */
    val selectedStudentId: Flow<Long?>
    suspend fun selectStudent(id: Long?)
}

/**
 * Every call the app makes over [DiaryApi], a token in [DiarySessionStore], and
 * the rule for what a failure means.
 *
 * Two behaviours are worth knowing about:
 *
 *  * a `401` that is *not* a re-auth request clears the stored bearer. The
 *    token is demonstrably dead — the server has just refused it — and leaving
 *    it in place would leave the app on a screen it cannot load, with a sign-in
 *    button the user has no reason to press. The [DiaryTarget] stays, so the
 *    sign-in that follows is to the same diary and the same account;
 *  * a `401` that *is* a re-auth request leaves it alone. The session is still
 *    ours, only the upstream half expired, and the login in it is what the
 *    password prompt is about to ask for.
 *
 * Signing in is [DiarySignIn]'s: this class only hands it the target and the
 * password and reports the session it stored.
 *
 * And it is the one writer of the offline diary: every read that succeeds is
 * written through to [cache] — the pupils, the terms, the marks window asked
 * for, and lessons and homework when the range is whole Monday-to-Sunday weeks
 * (any other range passes through uncached, because stamping a week fetched
 * when only part of it was would draw its missing days as empty). The import
 * reads through here too, so the cache only ever holds what a screen asking
 * the same question would have been answered.
 */
internal class DiaryRepositoryImpl(
    private val api: DiaryApi,
    private val store: DiarySessionStore,
    private val diarySignIn: DiarySignIn,
    private val forgetLocal: suspend () -> Unit = {},
    private val cache: DiaryCacheWriter = DiaryCacheWriter.None,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiaryRepository {

    override val session: Flow<DiarySession?> = store.diarySession

    override val target: Flow<DiaryTarget?> = store.diaryTarget

    override suspend fun current(): DiarySession? = withContext(ioDispatcher) {
        store.currentDiarySession()
    }

    override suspend fun signIn(target: DiaryTarget, password: String): Result<DiarySession> =
        diarySignIn.signIn(target, password).map { it.session }

    override suspend fun signOut(): Result<Unit> = withContext(ioDispatcher) {
        // Best effort, and deliberately ignored: the server forgetting its row
        // (and saying goodbye upstream, for «Сетевой город») is good hygiene,
        // but the user asked to be signed out of this phone and a dead network
        // is not a reason to refuse.
        runCatching { api.logout() }
        try {
            store.forgetDiary()
            // After the store, so a disk that refuses the first write is the
            // failure reported, and nothing is emptied under a session that
            // is still there.
            forgetLocal()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(DiaryFailure.of(failure))
        }
    }

    override suspend fun students(): Result<List<DiaryStudent>> = remembered(
        read = { call { api.students().map { it.toDomain() } } },
        write = { generation, rows -> cache.putStudents(generation, rows) },
    )

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryLesson>> = remembered(
        read = {
            ranged(from, to) {
                api.schedule(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
            }
        },
        write = { generation, rows -> cache.putLessons(generation, studentId, from, to, rows) },
    )

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryHomework>> = remembered(
        read = {
            ranged(from, to) {
                api.homework(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
            }
        },
        write = { generation, rows -> cache.putHomework(generation, studentId, from, to, rows) },
    )

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryMark>> = remembered(
        read = {
            ranged(from, to) {
                api.grades(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
            }
        },
        write = { generation, rows -> cache.putMarks(generation, studentId, from, to, rows) },
    )

    override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> = remembered(
        read = { call { api.periods(studentId).map { it.toDomain() } } },
        write = { generation, rows -> cache.putPeriods(generation, studentId, rows) },
    )

    override suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>> = call {
        api.overrides(studentId).mapNotNull { it.toDomain() }
    }

    // The three writes below drop the token on a bare `401`, like every read,
    // and that is a decision rather than the rule going unexamined.
    //
    // A `401` without the re-auth header means the server has just refused this
    // token: it is dead for writing and for reading alike, and the correction
    // was refused before it was stored, so keeping the token could not have
    // saved it. What keeping it would buy is a family left on a diary screen
    // that looks signed in and fails everything, with a sign-in button they
    // have no reason to press — the trap the reads clear the token to avoid.
    // Signing out mid-edit is a real cost — and it costs the text as well as
    // the screen: the sheet's fields are a plain `remember`, and the view model
    // deliberately closes the sheet when the session goes, so what was typed
    // goes with it. It is still the smaller cost, because the alternative is a
    // screen that looks signed in and refuses everything; but it is not free,
    // and an earlier version of this comment claimed it was.
    //
    // The re-auth `401` still leaves the session alone, here as everywhere: it
    // is the upstream half that died, the login is what the password prompt is
    // about to say out loud, and the correction can be retried after it.
    override suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
    ): Result<Unit> = call(unprocessable = DiaryFailure.Rejected) {
        api.putOverride(
            studentId,
            DiaryOverrideRequestDto(
                // Verbatim: the server composed this key and matches it exactly
                // when it lays the correction back over the lesson.
                target = target,
                field = field.wire(),
                value = value,
                original = original,
            ),
        )
        // The stored row comes back and is dropped: the screen redraws from the
        // schedule it refetches, and a correction kept in two places is a
        // correction shown two ways after the next upstream change.
    }

    override suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
    ): Result<Unit> = call(unprocessable = DiaryFailure.Rejected) {
        // In the body, not in the query string: the key is the server's own and
        // it can carry an ampersand, so it travels where nothing re-encodes it.
        api.resetOverride(
            studentId,
            DiaryResetRequestDto(target = target, field = field.wire()),
        )
    }

    override suspend fun resetAll(studentId: Long): Result<Unit> =
        call(unprocessable = DiaryFailure.Rejected) {
            api.resetOverrides(studentId)
        }

    /**
     * [read], and on success its answer written through to the cache.
     *
     * The generation is taken **before** the request, so an answer that lands
     * after a sign-out — or after another account signed in — is dropped by
     * the cache rather than written under the wrong account. The write is
     * guarded: a disk that will not take it costs the offline copy, never the
     * answer the screen asked for.
     */
    private suspend fun <T> remembered(
        read: suspend () -> Result<T>,
        write: suspend (generation: Long, value: T) -> Unit,
    ): Result<T> {
        val generation = cache.generation()
        val result = read()
        result.onSuccess { value ->
            try {
                write(generation, value)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // See above: the read stands, the copy is lost.
            }
        }
        return result
    }

    /** [call], with the server's own range rule applied before the round trip. */
    private suspend fun <T> ranged(
        from: LocalDate,
        to: LocalDate,
        block: suspend () -> T,
    ): Result<T> {
        val span = to.toEpochDay() - from.toEpochDay()
        if (span < 0 || span > DiaryRepository.MAX_RANGE_DAYS) {
            return Result.failure(DiaryFailure.BadRange)
        }
        return call(block = block)
    }

    /**
     * @param unprocessable what a `422` from this call means. The reads leave
     *   it at [DiaryFailure.BadRange]; the corrections pass
     *   [DiaryFailure.Rejected], because on `/overrides` a 422 is a target or a
     *   field the server will not file, and nothing but the endpoint says so.
     */
    private suspend fun <T> call(
        unprocessable: DiaryFailure = DiaryFailure.BadRange,
        block: suspend () -> T,
    ): Result<T> = withContext(ioDispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val classified = DiaryFailure.of(failure, unprocessable)
            if (classified is DiaryFailure.SignInRequired) {
                // Guarded, because it writes: the store is DataStore, whose
                // write side rethrows `IOException`, and this runs inside the
                // `catch` that is turning a refusal into a `Result`. A full
                // disk therefore threw out of a function that promises not to,
                // into the bare `viewModelScope.launch` every caller uses — so
                // the one thing worse than staying signed in on a dead token
                // was the app disappearing while the screen was open. The
                // token stays until the next failure tries again; see
                // `signOut`, which has always guarded its own clear.
                //
                // The token only: which diary this phone uses outlives it, so
                // the next screen asks for the same account (see the store).
                runCatching { store.clearDiaryToken() }
            }
            Result.failure(classified)
        }
    }
}
