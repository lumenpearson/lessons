package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.DiaryApi
import com.lumenpearson.lessons.core.data.network.dto.DiaryLoginRequestDto
import com.lumenpearson.lessons.core.data.network.dto.DiaryOverrideRequestDto
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
 * thing this repository is allowed to do to storage is written down in four
 * methods. It cannot reach the class token from here, which is the mechanical
 * half of "signing out of one does not touch the other".
 */
internal interface DiarySessionStore {
    val diarySession: Flow<DiarySession?>
    suspend fun currentDiarySession(): DiarySession?
    suspend fun writeDiarySession(value: DiarySession)
    suspend fun clearDiarySession()
}

/**
 * Every call the app makes over [DiaryApi], a token in [DiarySessionStore], and
 * the rule for what a failure means.
 *
 * Two behaviours are worth knowing about:
 *
 *  * a `401` that is *not* a re-auth request clears the stored session. The
 *    token is demonstrably dead — the server has just refused it — and leaving
 *    it in place would leave the app on a screen it cannot load, with a sign-in
 *    button the user has no reason to press;
 *  * a `401` that *is* a re-auth request leaves it alone. The session is still
 *    ours, only the upstream half expired, and the login in it is what the
 *    password prompt is about to ask for.
 */
internal class DiaryRepositoryImpl(
    private val api: DiaryApi,
    private val store: DiarySessionStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : DiaryRepository {

    override val session: Flow<DiarySession?> = store.diarySession

    override suspend fun current(): DiarySession? = withContext(ioDispatcher) {
        store.currentDiarySession()
    }

    override suspend fun signIn(login: String, password: String): Result<DiarySession> =
        call(clearOnSignInRequired = false) {
            val response = api.login(
                DiaryLoginRequestDto(login = login.trim(), password = password),
            )
            val opened = DiarySession(
                // The server echoes the login it accepted; falling back to what
                // was typed keeps the prompt honest if it ever stops.
                login = response.login.trim().ifBlank { login.trim() },
                token = response.token,
            )
            store.writeDiarySession(opened)
            opened
        }

    override suspend fun signOut(): Result<Unit> = withContext(ioDispatcher) {
        // Best effort, and deliberately ignored: the server forgetting its row
        // is good hygiene, but the user asked to be signed out of this phone
        // and a dead network is not a reason to refuse.
        runCatching { api.logout() }
        try {
            store.clearDiarySession()
            Result.success(Unit)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            Result.failure(DiaryFailure.of(failure))
        }
    }

    override suspend fun students(): Result<List<DiaryStudent>> = call {
        api.students().map { it.toDomain() }
    }

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryLesson>> = ranged(from, to) {
        api.schedule(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
    }

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryHomework>> = ranged(from, to) {
        api.homework(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
    }

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryMark>> = ranged(from, to) {
        api.grades(studentId, from.toString(), to.toString()).mapNotNull { it.toDomain() }
    }

    override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> = call {
        api.periods(studentId).map { it.toDomain() }
    }

    override suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>> = call {
        api.overrides(studentId).mapNotNull { it.toDomain() }
    }

    // The three writes below leave `clearOnSignInRequired` at true, like every
    // read, and that is a decision rather than the default going unexamined.
    //
    // A `401` without the re-auth header means the server has just refused this
    // token: it is dead for writing and for reading alike, and the correction
    // was refused before it was stored, so keeping the token could not have
    // saved it. What keeping it would buy is a family left on a diary screen
    // that looks signed in and fails everything, with a sign-in button they
    // have no reason to press — the trap the reads clear the token to avoid.
    // Signing out mid-edit is a real cost and it is the smaller one; the text
    // they typed is in the editor above this layer, not in anything this clears.
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
        api.resetOverride(studentId, target = target, field = field.wire())
    }

    override suspend fun resetAll(studentId: Long): Result<Unit> =
        call(unprocessable = DiaryFailure.Rejected) {
            api.resetOverrides(studentId)
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
        clearOnSignInRequired: Boolean = true,
        unprocessable: DiaryFailure = DiaryFailure.BadRange,
        block: suspend () -> T,
    ): Result<T> = withContext(ioDispatcher) {
        try {
            Result.success(block())
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            val classified = DiaryFailure.of(failure, unprocessable)
            if (clearOnSignInRequired && classified is DiaryFailure.SignInRequired) {
                store.clearDiarySession()
            }
            Result.failure(classified)
        }
    }
}
