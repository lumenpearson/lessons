package com.lumenpearson.lessons.core.data.diary

import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import kotlinx.coroutines.flow.Flow

/**
 * The steps of filling the phone with a diary account's first fortnight, in
 * the order they run, each with its share of the progress bar.
 *
 * There is no registration step: the session is registered on the sign-in
 * screen, whose failures need the form, and the import starts from the bearer
 * that left behind. There is no «saving» step either — every step's answer is
 * written as it arrives.
 *
 * @property weight the step's share of the bar; the weights sum to one. Rough
 *   costs, not measured ones: a week of «Сетевой город» is several upstream
 *   calls behind one of ours, and the list of pupils is one.
 */
enum class DiaryImportPhase(val weight: Float) {
    STUDENTS(0.10f),
    CHOOSE_STUDENT(0f),
    PERIODS(0.10f),
    SCHEDULE(0.30f),
    HOMEWORK(0.25f),
    MARKS(0.25f),
}

/**
 * What the import screen draws.
 *
 * Every state carries how much of the bar is done. [Running] also says where
 * the step under way will leave it, so the bar can move toward [Running.target]
 * while the request is out rather than sitting still and jumping.
 */
sealed interface DiaryImportProgress {

    /** The fraction of the bar that is done, `0..1`; it never goes backwards. */
    val completed: Float

    data class Running(
        val phase: DiaryImportPhase,
        override val completed: Float,
        val target: Float,
    ) : DiaryImportProgress

    /** The account sees several pupils and none was chosen before; the flow awaits `choose`. */
    data class ChoosingStudent(
        val students: List<DiaryStudent>,
        override val completed: Float,
    ) : DiaryImportProgress

    /**
     * Finished. [skipped] names the steps that failed without stopping it —
     * the terms, the homework, the marks — so the screen can say what is
     * missing; their weeks stay «не загружено» in the cache rather than
     * turning into an empty «нет заданий».
     */
    data class Done(
        val student: DiaryStudent,
        val skipped: Set<DiaryImportPhase>,
        val lessons: Int,
        val homework: Int,
        val marks: Int,
    ) : DiaryImportProgress {
        override val completed: Float get() = 1f
    }

    /**
     * Stopped at [phase]. When [resumable], `run(resumeFrom = phase)` picks up
     * there without repeating what already landed — after a re-sign-in, when
     * [problem] is `ReauthRequired`.
     */
    data class Failed(
        val phase: DiaryImportPhase,
        val problem: DiarySignInProblem,
        val resumable: Boolean,
        override val completed: Float,
    ) : DiaryImportProgress
}

/**
 * Filling the cache from a registered diary session, and keeping the week in it
 * current while the app is open.
 *
 * Both read through `DiaryRepository`, whose successful reads are the only
 * thing that writes the cache — so what the import fetched and what the diary
 * screen will ask for are the same requests, and the screen opens from the
 * cache. Neither is called from the background: the sync worker and the widget
 * never reach the diary (`SyncWorkerSourceTest`), because a read is what keeps
 * the server's copy of the session alive and that has to mean somebody used the
 * app.
 */
interface DiaryImport {

    /**
     * The import, as a cold flow: nothing is asked until it is collected, one
     * request is out at a time, and cancelling the collector cancels the
     * request in flight.
     *
     * @param resumeFrom the step a [DiaryImportProgress.Failed] stopped at; the
     *   steps before it are not asked again. `null` starts from the beginning.
     * @param choose asked only when the account sees several pupils and the
     *   stored choice is not one of them.
     */
    fun run(
        resumeFrom: DiaryImportPhase? = null,
        choose: suspend (List<DiaryStudent>) -> DiaryStudent,
    ): Flow<DiaryImportProgress>

    /**
     * The foreground «activity»: when a session exists and the current week
     * was last read more than [DiaryFreshFor] ago, reads this week and the next
     * again (lessons and homework). `true` when it read anything.
     *
     * Answers `false` without asking while an import is running, so opening the
     * app mid-import does not send every request twice. A failure is a
     * [DiarySignInProblem]; the screen decides whether it is worth showing.
     */
    suspend fun refreshIfStale(): Result<Boolean>
}
