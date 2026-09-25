package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.CHOOSE_STUDENT
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.HOMEWORK
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.MARKS
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.PERIODS
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.SCHEDULE
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase.STUDENTS
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress.ChoosingStudent
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress.Done
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress.Failed
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress.Running
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiarySessionStore
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [DiaryImport] over the repository, the stored session and the cache.
 *
 * It never writes the cache itself: every read goes through [repository], whose
 * successful answers are written through. That is what keeps the import from
 * being a second, subtly different client of the diary.
 *
 * What stops it and what does not:
 *
 *  * the pupils and the lessons are the import — without them there is
 *    nothing to open — so their failure is [Failed];
 *  * the terms, the homework and the marks are not: a failure there finishes
 *    with the step in `skipped`, and the screen asks again when it is opened;
 *  * but a dead session stops it wherever it happens, because every step after
 *    it would fail the same way, and each failure costs a request upstream.
 */
internal class DiaryImportImpl(
    private val repository: DiaryRepository,
    private val store: DiarySessionStore,
    private val cache: DiaryCache,
    private val clock: Clock = Clock.systemUTC(),
) : DiaryImport {

    /** Held for a whole import, so [refreshIfStale] can tell it would only repeat one. */
    private val running = Mutex()

    override fun run(
        resumeFrom: DiaryImportPhase?,
        choose: suspend (List<DiaryStudent>) -> DiaryStudent,
    ): Flow<DiaryImportProgress> = flow {
        running.withLock { import(resumeFrom, choose) }
    }

    private suspend fun FlowCollector<DiaryImportProgress>.import(
        resumeFrom: DiaryImportPhase?,
        choose: suspend (List<DiaryStudent>) -> DiaryStudent,
    ) {
        var done = 0f
        val skipped = linkedSetOf<DiaryImportPhase>()
        val start = resumeFrom ?: STUDENTS

        val session = store.currentDiarySession()
        if (session == null) {
            emit(Failed(start, DiarySignInProblem.SignInRequired, resumable = false, completed = done))
            return
        }
        val plan = DiaryImportPlan.of(DiaryWindows.today(session.target.zoneId(), clock))

        // ---- STUDENTS: usually already here, from the registration's answer.
        val cached = cache.students.first()
        var students = cached.students
        val ask = when {
            students.isEmpty() -> true
            start > STUDENTS -> false
            resumeFrom == null -> !diaryIsFresh(cached.loadedAt, clock.instant())
            else -> true
        }
        if (ask) {
            emit(Running(STUDENTS, done, done + STUDENTS.weight))
            students = repository.students().getOrElse { return fail(STUDENTS, it, done) }
        }
        done += STUDENTS.weight
        if (students.isEmpty()) {
            emit(Failed(STUDENTS, DiarySignInProblem.NoStudent, resumable = false, completed = done))
            return
        }

        // ---- CHOOSE_STUDENT: the stored choice, the only pupil, or ask.
        val stored = store.selectedStudentId.first()
        val chosen = students.firstOrNull { it.id == stored }
            ?: students.singleOrNull()
            ?: run {
                emit(ChoosingStudent(students, done))
                val picked = choose(students)
                students.firstOrNull { it.id == picked.id } ?: picked
            }
        if (chosen.id != stored) {
            try {
                store.selectStudent(chosen.id)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Exception) {
                // A disk that refused the choice: not the network, and not
                // worth reading as «нет сети».
                val problem = DiarySignInProblem.Unexpected("the chosen pupil could not be stored", failure)
                emit(Failed(CHOOSE_STUDENT, problem, resumable = true, completed = done))
                return
            }
        }
        done += CHOOSE_STUDENT.weight

        // ---- PERIODS: only to cut the marks window the way the screen cuts it.
        var current: DiaryPeriod? = null
        if (start <= PERIODS) {
            emit(Running(PERIODS, done, done + PERIODS.weight))
            repository.periods(chosen.id).fold(
                onSuccess = { periods -> current = periods.firstOrNull { it.isCurrent } },
                onFailure = { failure ->
                    if (stops(failure)) return fail(PERIODS, failure, done)
                    skipped += PERIODS
                },
            )
        } else {
            current = cache.periods(chosen.id).first().periods.firstOrNull { it.isCurrent }
        }
        done += PERIODS.weight

        // ---- SCHEDULE: two whole weeks in one request.
        var lessons = 0
        if (start <= SCHEDULE) {
            emit(Running(SCHEDULE, done, done + SCHEDULE.weight))
            lessons = repository.schedule(chosen.id, plan.weeksFrom, plan.weeksTo)
                .getOrElse { return fail(SCHEDULE, it, done) }
                .size
        } else {
            lessons = plan.mondays.sumOf { cache.week(chosen.id, it).first().lessons.size }
        }
        done += SCHEDULE.weight

        // ---- HOMEWORK: the same two weeks; missing homework is not a failure.
        var homework = 0
        if (start <= HOMEWORK) {
            emit(Running(HOMEWORK, done, done + HOMEWORK.weight))
            repository.homework(chosen.id, plan.weeksFrom, plan.weeksTo).fold(
                onSuccess = { homework = it.size },
                onFailure = { failure ->
                    if (stops(failure)) return fail(HOMEWORK, failure, done)
                    skipped += HOMEWORK
                },
            )
        } else {
            homework = plan.mondays.sumOf { cache.week(chosen.id, it).first().homework.size }
        }
        done += HOMEWORK.weight

        // ---- MARKS: the window the marks tab will ask for.
        var marks = 0
        emit(Running(MARKS, done, done + MARKS.weight))
        val window = plan.marksWindow(current)
        repository.grades(chosen.id, window.from, window.to).fold(
            onSuccess = { marks = it.size },
            onFailure = { failure ->
                if (stops(failure)) return fail(MARKS, failure, done)
                skipped += MARKS
            },
        )

        emit(Done(chosen, skipped, lessons, homework, marks))
    }

    override suspend fun refreshIfStale(): Result<Boolean> {
        if (!running.tryLock()) return Result.success(false)
        try {
            val session = store.currentDiarySession() ?: return Result.success(false)
            val known = cache.students.first().students
            val stored = store.selectedStudentId.first()
            val studentId = known.firstOrNull { it.id == stored }?.id
                ?: known.singleOrNull()?.id
                // Nothing cached yet, but a choice was made: trust the choice.
                ?: stored?.takeIf { known.isEmpty() }
                ?: return Result.success(false)
            val plan = DiaryImportPlan.of(DiaryWindows.today(session.target.zoneId(), clock))
            val week = cache.week(studentId, plan.weeksFrom).first()
            if (diaryIsFresh(week.lessonsLoadedAt, clock.instant())) return Result.success(false)

            repository.schedule(studentId, plan.weeksFrom, plan.weeksTo).onFailure {
                return Result.failure(DiarySignInProblem.of(it))
            }
            repository.homework(studentId, plan.weeksFrom, plan.weeksTo).onFailure {
                if (stops(it)) return Result.failure(DiarySignInProblem.of(it))
            }
            return Result.success(true)
        } finally {
            running.unlock()
        }
    }

    private suspend fun FlowCollector<DiaryImportProgress>.fail(
        phase: DiaryImportPhase,
        failure: Throwable,
        done: Float,
    ) {
        val problem = DiarySignInProblem.of(failure)
        emit(Failed(phase, problem, resumable = resumable(problem), completed = done))
    }

    /** A dead session ends the import wherever it is met; see the class note. */
    private fun stops(failure: Throwable): Boolean = when (DiarySignInProblem.of(failure)) {
        DiarySignInProblem.ReauthRequired, DiarySignInProblem.SignInRequired -> true
        else -> false
    }

    /**
     * Whether picking up at the failed step can work. Not after the bearer is
     * gone — that is a new sign-in, and a new sign-in starts a new import —
     * and not for an account with no pupil in it.
     */
    private fun resumable(problem: DiarySignInProblem): Boolean =
        problem != DiarySignInProblem.SignInRequired && problem != DiarySignInProblem.NoStudent
}
