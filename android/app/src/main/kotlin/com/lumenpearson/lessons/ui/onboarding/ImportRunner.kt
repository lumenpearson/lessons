package com.lumenpearson.lessons.ui.onboarding

import androidx.compose.runtime.Immutable
import com.lumenpearson.lessons.core.data.diary.DiaryImport
import com.lumenpearson.lessons.core.data.diary.DiaryImportPhase
import com.lumenpearson.lessons.core.data.diary.DiaryImportProgress
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.ui.diary.offersRetry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A pupil on the chooser; see `DiaryPlace` for why not the data layer's type. */
@Immutable
data class StudentUi(val id: Long, val name: String, val className: String?, val school: String?)

/** What a finished import brought, and what it had to leave out. */
@Immutable
data class ImportDone(
    val lessons: Int,
    val homework: Int,
    val marks: Int,
    val skipped: Set<DiaryImportPhase>,
)

/** Where an import stopped, and whether picking up there can work. */
@Immutable
data class ImportFailure(
    val phase: DiaryImportPhase,
    val problem: DiarySignInProblem,
    val resumable: Boolean,
) {
    /** The diary ended the session: only a new sign-in continues. */
    val needsSignIn: Boolean
        get() = problem is DiarySignInProblem.ReauthRequired || problem is DiarySignInProblem.SignInRequired
}

/**
 * The import screen's state.
 *
 * @property completed the part of the bar that is done.
 * @property target where the step under way will leave it — the bar creeps
 *   toward it while the request is out rather than sitting still and jumping.
 * @property choosing the pupils to pick from, while the import waits for one.
 */
@Immutable
data class ImportUi(
    val completed: Float = 0f,
    val target: Float = 0f,
    val phase: DiaryImportPhase? = null,
    val choosing: List<StudentUi>? = null,
    val done: ImportDone? = null,
    val failed: ImportFailure? = null,
)

/** The import screen's one action, by what the import is doing. */
enum class ImportAction {
    RUNNING,
    CONTINUE,

    /** The diary ended the session: a new sign-in continues. */
    SIGN_IN_AGAIN,

    /** «Повторить»: the failure is one a second try can get past. */
    RETRY,

    /**
     * «Выйти из дневника и начать заново» and nothing else: a retry would meet
     * the same answer — an account with no pupil, a diary that refuses our
     * server's address (#153's rule, as `offersRetry` has it elsewhere).
     */
    START_OVER,
}

fun importActionOf(ui: ImportUi): ImportAction {
    val failed = ui.failed
    return when {
        ui.done != null -> ImportAction.CONTINUE
        failed == null -> ImportAction.RUNNING
        failed.needsSignIn -> ImportAction.SIGN_IN_AGAIN
        failed.problem.offersRetry -> ImportAction.RETRY
        else -> ImportAction.START_OVER
    }
}

/** One row of the import screen. */
enum class StageState { WAITING, RUNNING, DONE, SKIPPED, FAILED }

/** The phases the screen shows a row for; choosing a pupil is a card, not a row. */
val ImportStages: List<DiaryImportPhase> = listOf(
    DiaryImportPhase.STUDENTS,
    DiaryImportPhase.PERIODS,
    DiaryImportPhase.SCHEDULE,
    DiaryImportPhase.HOMEWORK,
    DiaryImportPhase.MARKS,
)

/**
 * The state of [stage]'s row. Phases run in declaration order, so everything
 * before the one under way is done — including a phase the import skipped
 * because the cache already had it (the pupils a registration returned).
 */
fun stageStateOf(ui: ImportUi, stage: DiaryImportPhase): StageState {
    ui.done?.let { done -> return if (stage in done.skipped) StageState.SKIPPED else StageState.DONE }
    ui.failed?.let { failed ->
        return when {
            stage == failed.phase -> StageState.FAILED
            stage.ordinal < failed.phase.ordinal -> StageState.DONE
            else -> StageState.WAITING
        }
    }
    val current = ui.phase ?: return StageState.WAITING
    return when {
        stage.ordinal < current.ordinal -> StageState.DONE
        stage == current -> StageState.RUNNING
        else -> StageState.WAITING
    }
}

/**
 * Runs [DiaryImport] for the import step and turns its progress into [ImportUi].
 *
 * The pupil chooser is a suspension, not a callback into the screen: the
 * import awaits [choose], which the screen reaches through [ImportUi.choosing].
 */
class ImportRunner(
    private val scope: CoroutineScope,
    private val import: DiaryImport,
) {
    private val mutable = MutableStateFlow(ImportUi())
    val state: StateFlow<ImportUi> = mutable.asStateFlow()

    private var job: Job? = null
    private var pending: CompletableDeferred<DiaryStudent>? = null
    private var offered: List<DiaryStudent> = emptyList()

    val running: Boolean get() = job?.isActive == true

    /**
     * Starts, or picks up after a failure. The bar keeps what was done: a
     * resumed import does not repeat the phases before [resumeFrom].
     */
    fun start(resumeFrom: DiaryImportPhase? = null) {
        if (running) return
        mutable.update { it.copy(done = null, failed = null, choosing = null) }
        job = scope.launch {
            import.run(resumeFrom = resumeFrom, choose = ::awaitChoice).collect { progress ->
                when (progress) {
                    is DiaryImportProgress.Running -> mutable.update {
                        it.copy(
                            completed = maxOf(it.completed, progress.completed),
                            target = progress.target,
                            phase = progress.phase,
                            choosing = null,
                        )
                    }
                    is DiaryImportProgress.ChoosingStudent -> mutable.update {
                        it.copy(completed = maxOf(it.completed, progress.completed), phase = DiaryImportPhase.CHOOSE_STUDENT)
                    }
                    is DiaryImportProgress.Done -> mutable.update {
                        it.copy(
                            completed = 1f,
                            target = 1f,
                            choosing = null,
                            done = ImportDone(progress.lessons, progress.homework, progress.marks, progress.skipped),
                        )
                    }
                    is DiaryImportProgress.Failed -> mutable.update {
                        it.copy(
                            completed = maxOf(it.completed, progress.completed),
                            target = maxOf(it.completed, progress.completed),
                            choosing = null,
                            failed = ImportFailure(progress.phase, progress.problem, progress.resumable),
                        )
                    }
                }
            }
        }
    }

    /** The pupil picked on the chooser; the import goes on with them. */
    fun choose(id: Long) {
        val student = offered.firstOrNull { it.id == id } ?: return
        mutable.update { it.copy(choosing = null) }
        pending?.complete(student)
    }

    /** Leaving the step: the request in flight is cancelled with the collector. */
    fun stop() {
        job?.cancel()
        job = null
        pending?.cancel()
        pending = null
        mutable.value = ImportUi()
    }

    private suspend fun awaitChoice(students: List<DiaryStudent>): DiaryStudent {
        val choice = CompletableDeferred<DiaryStudent>()
        pending = choice
        offered = students
        mutable.update {
            it.copy(choosing = students.map { student -> StudentUi(student.id, student.shortName, student.className, student.school) })
        }
        return choice.await()
    }
}
