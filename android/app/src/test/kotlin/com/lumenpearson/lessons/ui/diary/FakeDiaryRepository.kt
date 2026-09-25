package com.lumenpearson.lessons.ui.diary

import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryHomework
import com.lumenpearson.lessons.core.data.repository.DiaryLesson
import com.lumenpearson.lessons.core.data.repository.DiaryMark
import com.lumenpearson.lessons.core.data.repository.DiaryOverrideRecord
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.LocalDate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/** One correction as the fake was asked to store it. */
internal data class Written(
    val target: String,
    val field: DiaryField,
    val value: String,
    val original: String?,
)

/**
 * The one fake diary every `:app` test uses.
 *
 * There were two, each private to its test file, and every change to
 * [DiaryRepository]'s interface had to be made in both — the signature change
 * this batch makes to how a sign-in reaches the server would have been two
 * edits that could disagree. One file means one edit, and a new test picks the
 * behaviour it needs from the knobs below rather than writing a third.
 *
 * @property readFailure when set, every read answers it: for tests about the
 *   sign-in and nothing after it, where a students call that succeeded would
 *   pull the state holder into a load whose answers would have to be arranged.
 * @property failAfter the number of writes that succeed before [failure] is
 *   answered instead; `null` never fails a write.
 * @property lessons what a schedule read answers, when it answers.
 * @property scheduleFailure when set, schedule and homework reads answer it and
 *   the others do not — the diary unreachable on a screen whose pupils were
 *   saved.
 * @property scheduleGate when set, schedule reads wait for it: the network
 *   that has not answered yet.
 */
internal class FakeDiaryRepository : DiaryRepository {

    var students: List<DiaryStudent> = emptyList()
    var readFailure: DiaryFailure? = null
    var failAfter: Int? = null
    var failure: DiaryFailure = DiaryFailure.Unavailable
    var reloads: Int = 0
    var lessons: List<DiaryLesson> = emptyList()
    var scheduleFailure: DiaryFailure? = null
    var scheduleGate: CompletableDeferred<Unit>? = null
    var studentReads: Int = 0

    /** What a sign-in answers; a success is also stored as the session. */
    var signInResult: Result<DiarySession>? = null

    /** The diary each sign-in was asked for, in order. */
    val signedInTo = mutableListOf<DiaryTarget>()

    /** What [target] reads while no session holds one — a bare `401`'s leftover. */
    val targets = MutableStateFlow<DiaryTarget?>(null)

    val corrections = mutableListOf<Written>()
    val resets = mutableListOf<Pair<String, DiaryField>>()

    val sessions = MutableStateFlow<DiarySession?>(null)
    override val session: Flow<DiarySession?> = sessions
    override val target: Flow<DiaryTarget?> = targets

    private var writes = 0

    private fun <T> read(value: T): Result<T> =
        readFailure?.let { Result.failure(it) } ?: Result.success(value)

    private fun <T> answer(value: T): Result<T> {
        val limit = failAfter
        val outcome = if (limit != null && writes >= limit) {
            Result.failure<T>(failure)
        } else {
            Result.success(value)
        }
        writes += 1
        return outcome
    }

    override suspend fun current(): DiarySession? = sessions.value

    override suspend fun signIn(target: DiaryTarget, password: String): Result<DiarySession> {
        signedInTo += target
        val result = signInResult
            ?: Result.success(DiarySession(login = target.login, token = "t", target = target))
        result.getOrNull()?.let {
            sessions.value = it
            targets.value = it.target
        }
        return result
    }

    override suspend fun signOut(): Result<Unit> {
        sessions.value = null
        targets.value = null
        return Result.success(Unit)
    }

    override suspend fun students(): Result<List<DiaryStudent>> {
        studentReads += 1
        return read(students)
    }

    override suspend fun schedule(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryLesson>> {
        reloads += 1
        scheduleGate?.await()
        scheduleFailure?.let { return Result.failure(it) }
        return read(lessons)
    }

    override suspend fun homework(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryHomework>> {
        scheduleFailure?.let { return Result.failure(it) }
        return read(emptyList())
    }

    override suspend fun grades(
        studentId: Long,
        from: LocalDate,
        to: LocalDate,
    ): Result<List<DiaryMark>> = read(emptyList())

    override suspend fun periods(studentId: Long): Result<List<DiaryPeriod>> = read(emptyList())

    override suspend fun overrides(studentId: Long): Result<List<DiaryOverrideRecord>> =
        read(emptyList())

    override suspend fun correct(
        studentId: Long,
        target: String,
        field: DiaryField,
        value: String,
        original: String?,
    ): Result<Unit> {
        readFailure?.let { return Result.failure(it) }
        val outcome = answer(Unit)
        if (outcome.isSuccess) corrections += Written(target, field, value, original)
        return outcome
    }

    override suspend fun reset(
        studentId: Long,
        target: String,
        field: DiaryField,
    ): Result<Unit> {
        readFailure?.let { return Result.failure(it) }
        val outcome = answer(Unit)
        if (outcome.isSuccess) resets += target to field
        return outcome
    }

    override suspend fun resetAll(studentId: Long): Result<Unit> {
        readFailure?.let { return Result.failure(it) }
        return answer(Unit)
    }
}
