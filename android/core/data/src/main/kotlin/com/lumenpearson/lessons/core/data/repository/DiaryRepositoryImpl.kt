package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.diary.DiaryCacheWriter
import com.lumenpearson.lessons.core.data.network.RequestCredentials
import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.DiaryRemote
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Where the diary session is kept.
 *
 * An interface with one production implementation — `LessonsPreferences` — so
 * that the repository can be exercised without DataStore, and so that the only
 * thing this repository is allowed to do to storage is written down here. It
 * never receives a class bearer from here, which is the mechanical half of
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

    /** Clears only the still-current address/bearer actually refused by the server, in one store write. */
    suspend fun rejectDiaryRequest(sent: RequestCredentials)

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

    /** Serializes local identity transitions and cache commits, never a network request. */
    suspend fun <T> withDiaryIdentity(block: suspend DiarySessionStore.() -> T): T = block(this)

    suspend fun currentDiaryRequest(): RequestCredentials =
        RequestCredentials("", null, currentDiarySession()?.token)

    suspend fun ownsDiaryRequest(sent: RequestCredentials): Boolean =
        !sent.diaryToken.isNullOrBlank() && sent.diaryToken == currentDiarySession()?.token
}

/**
 * Every call the app makes over [DiaryRemote], a token in [DiarySessionStore], and
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
    private val api: DiaryRemote,
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
        try {
            val owner = store.withDiaryIdentity { currentDiaryRequest() to currentDiaryTarget() }
            try {
                api.logout()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                // A failed remote goodbye must not prevent an explicitly requested local sign-out.
            }
            currentCoroutineContext().ensureActive()
            store.withDiaryIdentity {
                val current = currentDiaryRequest()
                if (current.baseUrl == owner.first.baseUrl && current.diaryToken == owner.first.diaryToken &&
                    currentDiaryTarget() == owner.second
                ) {
                    currentCoroutineContext().ensureActive()
                    withContext(NonCancellable) {
                        // Once credentials commit, cancellation cannot strand this account's offline rows.
                        forgetDiary()
                        forgetLocal()
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(DiaryFailure.of(failure))
        }
    }

    override suspend fun students(): Result<List<DiaryStudent>> = remembered(
        read = { observation -> call(observation = observation) { api.students(it) } },
        write = { generation, rows -> cache.putStudents(generation, rows) },
    )

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryLesson>> = remembered(
        read = { observation ->
            ranged(from, to, observation) {
                api.schedule(studentId, from, to, it)
            }
        },
        write = { generation, rows -> cache.putLessons(generation, studentId, from, to, rows) },
    )

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryHomework>> = remembered(
        read = { observation ->
            ranged(from, to, observation) {
                api.homework(studentId, from, to, it)
            }
        },
        write = { generation, rows -> cache.putHomework(generation, studentId, from, to, rows) },
    )

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryMark>> = remembered(
        read = { observation ->
            ranged(from, to, observation) {
                api.grades(studentId, from, to, it)
            }
        },
        write = { generation, rows -> cache.putMarks(generation, studentId, from, to, rows) },
    )

    override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> = remembered(
        read = { observation -> call(observation = observation) { api.periods(studentId, it) } },
        write = { generation, rows -> cache.putPeriods(generation, studentId, rows) },
    )

    override suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>> = call {
        api.overrides(studentId, it)
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
        api.correct(studentId, target, field, value, original, it)
    }

    override suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
    ): Result<Unit> = call(unprocessable = DiaryFailure.Rejected) {
        api.reset(studentId, target, field, it)
    }

    override suspend fun resetAll(studentId: Long): Result<Unit> =
        call(unprocessable = DiaryFailure.Rejected) {
            api.resetAll(studentId, it)
        }

    /**
     * [read], and on success its answer written through to the cache.
     *
     * The generation is taken **before** the request and checked together with
     * its actual sent diary bearer and address under the identity gate. A read
     * starting between a cache clear and a new account commit must also be dropped. The write is
     * guarded: a disk that will not take it costs the offline copy, never the
     * answer the screen asked for.
     */
    private suspend fun <T> remembered(
        read: suspend (CallObservation) -> Result<T>,
        write: suspend (generation: Long, value: T) -> Unit,
    ): Result<T> {
        val generation = cache.generation()
        val observation = CallObservation()
        val result = read(observation)
        result.onSuccess { value ->
            try {
                observation.credentials?.let { sent ->
                    store.withDiaryIdentity {
                        if (ownsDiaryRequest(sent)) write(generation, value)
                    }
                }
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
        observation: CallObservation,
        block: suspend (CallObservation) -> T,
    ): Result<T> {
        val span = to.toEpochDay() - from.toEpochDay()
        if (span < 0 || span > DiaryRepository.MAX_RANGE_DAYS) {
            return Result.failure(DiaryFailure.BadRange)
        }
        return call(observation = observation, block = block)
    }

    /**
     * @param unprocessable what a `422` from this call means. The reads leave
     *   it at [DiaryFailure.BadRange]; the corrections pass
     *   [DiaryFailure.Rejected], because on `/overrides` a 422 is a target or a
     *   field the server will not file, and nothing but the endpoint says so.
     */
    private suspend fun <T> call(
        unprocessable: DiaryFailure = DiaryFailure.BadRange,
        observation: CallObservation = CallObservation(),
        block: suspend (CallObservation) -> T,
    ): Result<T> = withContext(ioDispatcher) {
        try {
            Result.success(block(observation))
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val classified = DiaryFailure.of(failure, unprocessable)
            if (classified is DiaryFailure.SignInRequired) {
                // An old refusal cannot authorize a write against a replacement account or server.
                observation.credentials?.let { sent ->
                    runCatching { store.rejectDiaryRequest(sent) }.onFailure {
                        // A disk failure preserves the refusal; cancellation still belongs to the caller.
                        if (it is CancellationException) throw it
                    }
                }
            }
            Result.failure(classified)
        }
    }
}
