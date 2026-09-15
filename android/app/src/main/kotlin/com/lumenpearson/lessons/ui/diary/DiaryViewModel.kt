package com.lumenpearson.lessons.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import java.time.LocalDate
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Everything the diary section is, as one state object.
 *
 * @property ready false only until the stored session has been read once. The
 *   screen shows placeholders rather than the sign-in form in that moment: a
 *   login page that flashes up for somebody who is already signed in is the
 *   worst frame this section can draw.
 * @property reauth the upstream session died. The login is still known, so the
 *   screen asks for the password alone — the case the server sends
 *   `X-Diary-Reauth: required` for, and the whole reason it is a header rather
 *   than one 401 for both meanings.
 * @property student the pupil everything below is about; `null` only while the
 *   list is still loading or empty.
 */
data class DiaryUiState(
    val ready: Boolean = false,
    val session: DiarySession? = null,
    val reauth: Boolean = false,
    val signingIn: Boolean = false,
    val signInError: DiaryFailure? = null,
    /**
     * What the last sign-in attempt came to, waiting to be shown once.
     *
     * Separate from [signInError] because the two answer different questions
     * and live for different lengths of time. [signInError] is the state of the
     * form — it paints the fields red and is cleared by the next keystroke.
     * This is an event: it is shown in a pop-up, acknowledged, and gone, and it
     * carries the success case too, which the form has nothing to say about
     * because the form is no longer on screen by then.
     */
    val signInOutcome: DiarySignInOutcome? = null,
    val signingOut: Boolean = false,
    val students: List<DiaryStudent> = emptyList(),
    val studentsLoading: Boolean = false,
    val studentsError: DiaryFailure? = null,
    val selectedStudentId: Long? = null,
    val tab: DiaryTab = DiaryTab.SCHEDULE,
    val weekStart: LocalDate = diaryWeekStart(diaryToday()),
    val scheduleLoading: Boolean = false,
    val scheduleError: DiaryFailure? = null,
    val days: List<DiaryDayUi> = emptyList(),
    val gradesLoading: Boolean = false,
    val gradesError: DiaryFailure? = null,
    val subjects: List<DiarySubjectMarks> = emptyList(),
    val gradeRange: DiaryRange? = null,
    /** The row whose corrections are open in a sheet, or `null` for none. */
    val editing: DiaryCorrections? = null,
    val savingEdit: Boolean = false,
    val editError: DiaryFailure? = null,
) {
    /**
     * The date it is in the city whose diary this is.
     *
     * Read on every access rather than frozen into the state, because the
     * state object is built once when the screen opens and carried forward by
     * `copy` from then on: a value stored here was whatever the date was when
     * the pupil first opened the diary, and a phone left on the screen
     * overnight kept offering «на этой неделе» for the week that had ended.
     */
    val today: LocalDate get() = diaryToday()

    val student: DiaryStudent? get() = students.firstOrNull { it.id == selectedStudentId }

    /** Signed in and not being asked for the password again. */
    val signedIn: Boolean get() = session != null && !reauth

    /** A picker is only worth its row when there is something to pick. */
    val showStudentPicker: Boolean get() = students.size > 1

    /** Whether stepping back to this week would move anything. */
    val canReturnToThisWeek: Boolean get() = weekStart != diaryWeekStart(today)
}

/**
 * The result of one sign-in attempt, for the pop-up that reports it.
 *
 * Both cases are worth saying out loud. «Не удалось войти» because the reason
 * matters — a wrong password and a diary that is down need different things
 * from the person reading; «Вход выполнен» because a form that answers a
 * correct password with silence is a form you cannot tell you have finished
 * with.
 */
sealed interface DiarySignInOutcome {

    /** @param login the account, echoed back so it can be checked for typos. */
    data class Succeeded(val login: String) : DiarySignInOutcome

    data class Failed(val failure: DiaryFailure) : DiarySignInOutcome
}

/**
 * The diary section's state holder.
 *
 * It owns three loads — the pupils, a week of the timetable, a term of marks —
 * and one rule that runs through all of them: a failure that means "the diary
 * logged us out upstream" flips [DiaryUiState.reauth] instead of becoming an
 * error message. Anything else is kept as a typed [DiaryFailure] and turned
 * into a sentence by the screen, which is the only place that knows how to
 * phrase one.
 *
 * The password never reaches this class as state: it arrives as an argument to
 * [signIn], is handed to the repository, and is gone when the call returns.
 */
class DiaryViewModel(
    private val repository: DiaryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(DiaryUiState())
    val uiState: StateFlow<DiaryUiState> = state.asStateFlow()

    /** Cancelled and replaced whenever the pupil, the week or the tab changes. */
    private var loadJob: Job? = null

    /** Asked for once per pupil: terms do not change while a screen is open. */
    private val periods = mutableMapOf<Long, DiaryPeriod?>()

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                val had = state.value.session
                state.update {
                    it.copy(
                        ready = true,
                        session = session,
                        // A session that has just gone cannot be re-authenticated,
                        // and one that has just arrived has been.
                        reauth = if (session == null || session != had) false else it.reauth,
                        students = if (session == null) emptyList() else it.students,
                        selectedStudentId = if (session == null) null else it.selectedStudentId,
                        days = if (session == null) emptyList() else it.days,
                        subjects = if (session == null) emptyList() else it.subjects,
                        // The correction sheet goes with everything else. A
                        // write that meets a dead token clears the session from
                        // under it, and the sheet would stay on top of the
                        // sign-in form — over a «Сохранить» that is enabled and
                        // provably does nothing, because the pupil it was
                        // saving for has just been forgotten too.
                        editing = if (session == null) null else it.editing,
                        savingEdit = if (session == null) false else it.savingEdit,
                        editError = if (session == null) null else it.editError,
                    )
                }
                if (session != null && state.value.students.isEmpty()) loadStudents()
            }
        }
    }

    /** Signs in, or answers the re-authentication prompt; both are the same call. */
    fun signIn(login: String, password: String) {
        if (state.value.signingIn) return
        viewModelScope.launch {
            state.update { it.copy(signingIn = true, signInError = null) }
            val result = repository.signIn(login, password)
            val failure = result.exceptionOrNull()?.let(DiaryFailure::of)
            state.update {
                it.copy(
                    signingIn = false,
                    signInError = failure,
                    reauth = if (failure == null) false else it.reauth,
                    signInOutcome = failure?.let(DiarySignInOutcome::Failed)
                        // The login as it was typed, not as the server echoed
                        // it: the session row arrives moments later through the
                        // repository's flow, and the pop-up is about the
                        // attempt that has just finished.
                        ?: DiarySignInOutcome.Succeeded(login),
                )
            }
            if (failure == null) {
                // The pupils are re-read rather than kept: the same phone may
                // have signed in as a different parent.
                periods.clear()
                state.update { it.copy(students = emptyList(), selectedStudentId = null) }
                loadStudents()
            }
        }
    }

    /** Clears the diary session and nothing else; the class token is untouched. */
    fun signOut() {
        if (state.value.signingOut) return
        viewModelScope.launch {
            state.update { it.copy(signingOut = true) }
            repository.signOut()
            periods.clear()
            state.update {
                DiaryUiState(
                    ready = true,
                    weekStart = diaryWeekStart(it.today),
                )
            }
        }
    }

    fun selectStudent(id: Long) {
        if (state.value.selectedStudentId == id) return
        state.update { it.copy(selectedStudentId = id, days = emptyList(), subjects = emptyList()) }
        reload()
    }

    fun setTab(tab: DiaryTab) {
        if (state.value.tab == tab) return
        state.update { it.copy(tab = tab) }
        reload()
    }

    fun showPreviousWeek() = moveWeek(-1)

    fun showNextWeek() = moveWeek(1)

    /** Back to the week containing today; only offered when it would move. */
    fun showCurrentWeek() {
        val start = diaryWeekStart(state.value.today)
        if (state.value.weekStart == start) return
        state.update { it.copy(weekStart = start) }
        reload()
    }

    /** What the "повторить" button on every failure card does. */
    fun retry() {
        if (state.value.students.isEmpty()) loadStudents() else reload()
    }

    /** Dismisses the inline sign-in error so the next keystroke starts clean. */
    fun clearSignInError() {
        if (state.value.signInError != null) state.update { it.copy(signInError = null) }
    }

    /** Called once the pop-up has been acknowledged, so it is not shown twice. */
    fun consumeSignInOutcome() {
        if (state.value.signInOutcome != null) state.update { it.copy(signInOutcome = null) }
    }

    private fun moveWeek(direction: Long) {
        state.update { it.copy(weekStart = it.weekStart.plusWeeks(direction)) }
        reload()
    }

    private fun loadStudents() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            state.update { it.copy(studentsLoading = true, studentsError = null) }
            val result = repository.students()
            val found = result.getOrNull()
            if (found == null) {
                val failure = DiaryFailure.of(result.exceptionOrNull() ?: DiaryFailure.Unavailable)
                state.update {
                    it.copy(
                        studentsLoading = false,
                        studentsError = failure.takeUnless { f -> f is DiaryFailure.ReauthRequired },
                        reauth = it.reauth || failure is DiaryFailure.ReauthRequired,
                    )
                }
                return@launch
            }
            state.update {
                it.copy(
                    studentsLoading = false,
                    students = found,
                    // Keeps the chosen child across a refresh, and falls back to
                    // the first one — which for most accounts is the only one,
                    // and is why the picker is hidden at that size.
                    selectedStudentId = found.firstOrNull { student ->
                        student.id == it.selectedStudentId
                    }?.id ?: found.firstOrNull()?.id,
                )
            }
            load()
        }
    }

    private fun reload() {
        loadJob?.cancel()
        loadJob = viewModelScope.launch { load() }
    }

    /**
     * Fetches the open tab again.
     *
     * Public because writing a correction has to be followed by a read: none of
     * this is cached, the server is what applies a correction, and `retry` — the
     * only other way back in — is reachable only from a failure card.
     */
    fun refresh() = reload()

    private suspend fun load() {
        val current = state.value
        val student = current.student ?: return
        when (current.tab) {
            DiaryTab.SCHEDULE -> loadSchedule(student.id, current.weekStart)
            DiaryTab.GRADES -> loadGrades(student.id, current.today)
        }
    }

    /**
     * A week of lessons and the homework due in it, asked for together.
     *
     * Two calls rather than one because the server publishes them as two
     * resources — upstream the homework is a field of a lesson, and the server
     * pulls it out precisely so that a client does not have to. They are
     * awaited in parallel: one week is one round trip's worth of waiting, not
     * two.
     */
    private suspend fun loadSchedule(studentId: Long, weekStart: LocalDate) {
        state.update { it.copy(scheduleLoading = true, scheduleError = null) }
        val weekEnd = weekStart.plusDays(6)
        val (lessons, homework) = coroutineScope {
            val timetable = async { repository.schedule(studentId, weekStart, weekEnd) }
            val assignments = async { repository.homework(studentId, weekStart, weekEnd) }
            timetable.await() to assignments.await()
        }
        val failure = lessons.exceptionOrNull() ?: homework.exceptionOrNull()
        if (failure != null) {
            applyFailure(failure) { copy(scheduleLoading = false, scheduleError = it) }
            return
        }
        val days = diaryWeek(
            weekStart = weekStart,
            lessons = lessons.getOrDefault(emptyList()),
            homework = homework.getOrDefault(emptyList()),
        )
        state.update { it.copy(scheduleLoading = false, days = days) }
    }

    /**
     * The marks of the current term, clipped to what the server will serve.
     *
     * The term is asked for first because a quarter is the unit a parent thinks
     * in — and then [diaryGradeRange] narrows it, because a quarter is usually
     * longer than the 62 days one request may cover. A term that cannot be read
     * is not an error: the range simply falls back to the last 62 days.
     */
    private suspend fun loadGrades(studentId: Long, today: LocalDate) {
        state.update { it.copy(gradesLoading = true, gradesError = null) }
        val period = if (periods.containsKey(studentId)) {
            periods[studentId]
        } else {
            repository.periods(studentId).getOrNull()
                ?.firstOrNull { it.isCurrent }
                .also { periods[studentId] = it }
        }
        val range = diaryGradeRange(today, period)
        val result = repository.grades(studentId, range.from, range.to)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            applyFailure(failure) { copy(gradesLoading = false, gradesError = it) }
            return
        }
        val subjects = summariseMarks(result.getOrDefault(emptyList()))
        state.update { it.copy(gradesLoading = false, subjects = subjects, gradeRange = range) }
    }

    // -- corrections --------------------------------------------------------
    //
    // The diary stays read-only upstream: what these write is a value the
    // server lays *over* the answer on the way out, and a reset takes it off
    // again. The sheet edits a whole row at once because that is how a person
    // thinks about it — "this lesson is in 204, not 12" — and the difference
    // between what they typed and what the diary says is what becomes a
    // per-field correction or a per-field reset.

    /** Opens the sheet for one row. */
    fun edit(corrections: DiaryCorrections) {
        state.update { it.copy(editing = corrections, editError = null) }
    }

    fun cancelEdit() {
        state.update { it.copy(editing = null, editError = null, savingEdit = false) }
    }

    /**
     * Writes what changed and nothing else.
     *
     * A field typed back to what the diary says is a **reset**, not a
     * correction equal to the upstream: keeping a row that says "show exactly
     * what you were going to show anyway" would leave the value marked as
     * corrected forever, with a reset button that appears to do nothing.
     */
    fun saveEdit(typed: Map<DiaryField, String>) {
        val open = state.value.editing ?: return
        val student = state.value.selectedStudentId ?: return
        if (state.value.savingEdit) return

        state.update { it.copy(savingEdit = true, editError = null) }
        viewModelScope.launch {
            val failure = write {
                typed.mapNotNull { (field, raw) ->
                    val wanted = raw.trim()
                    val upstream = open.upstreamOf(field)
                    val corrected = field in open.corrected
                    // Nothing is sent for a field nobody touched. The sheet
                    // hands back every field it drew, so without this a save of
                    // one room is four round trips, three of them asking the
                    // server to delete corrections that were never there.
                    when {
                        wanted == upstream.orEmpty().trim() ->
                            if (corrected) {
                                suspend { repository.reset(student, open.target, field) }
                            } else {
                                null
                            }
                        // Re-sent, not skipped, when the diary has moved under
                        // this field. The value is the same but `original` is
                        // not: without the write, «в дневнике теперь другое»
                        // comes back every time the sheet is opened and there
                        // is no way to say "yes, I know, keep mine".
                        corrected &&
                            wanted == open.values[field].orEmpty().trim() &&
                            field !in open.changedUpstream -> null
                        else -> suspend {
                            repository.correct(student, open.target, field, wanted, upstream)
                        }
                    }
                }
            }
            finishEdit(failure)
        }
    }

    /**
     * Takes every correction off this row.
     *
     * The fields that **are** corrected, not the ones the sheet drew. They are
     * not the same set — a correction can exist on a field this screen does not
     * offer — and resetting the drawn ones deletes nothing while leaving the
     * row still marked as corrected, which is a destructive-looking button that
     * provably does nothing, forever.
     */
    fun resetEdit() {
        val open = state.value.editing ?: return
        val student = state.value.selectedStudentId ?: return
        if (state.value.savingEdit) return

        state.update { it.copy(savingEdit = true, editError = null) }
        viewModelScope.launch {
            val failure = write {
                open.corrected.map { field ->
                    suspend { repository.reset(student, open.target, field) }
                }
            }
            finishEdit(failure)
        }
    }

    /**
     * Runs the writes one at a time and stops at the first failure.
     *
     * @return what went wrong, or `null`.
     */
    private suspend fun write(
        calls: () -> List<suspend () -> Result<Unit>>,
    ): Throwable? {
        for (call in calls()) {
            // `exceptionOrNull()` rather than a cast: a failure that is not a
            // `DiaryFailure` was being read as a success, which closed the
            // sheet on a save that never happened. Nothing produces one today
            // — the repository classifies everything — and that is a property
            // of a layer below this one, not a reason to depend on it.
            val failure = call().exceptionOrNull()
            if (failure != null) return failure
        }
        return null
    }

    /**
     * Closes the sheet, or leaves it open with the reason.
     *
     * Either way the screen is reloaded. A save is several writes, so a failure
     * halfway leaves some of them applied; without the reload the week still
     * shows the old value with no badge, and the correction that *was* written
     * turns up later as a change nobody made just then.
     */
    private fun finishEdit(failure: Throwable?) {
        if (failure == null) {
            state.update { it.copy(editing = null, savingEdit = false) }
            refresh()
            return
        }
        if (DiaryFailure.of(failure) is DiaryFailure.ReauthRequired) {
            // The sheet has to go: `applyFailure` puts the password prompt on
            // screen underneath it, and a modal sheet with no message in it
            // over a form nobody can see is how somebody concludes the button
            // is broken.
            state.update { it.copy(editing = null) }
        }
        // Left open otherwise, on purpose: what the person typed is still in
        // the fields, and closing the sheet would throw it away to show them a
        // message about why it had not been saved.
        applyFailure(failure) { copy(savingEdit = false, editError = it) }
        refresh()
    }

    /**
     * One place where a failure becomes either a prompt or a message.
     *
     * [DiaryFailure.ReauthRequired] is never shown as an error: it is a request
     * for the password, and the screen it belongs on is the sign-in form with
     * the login already filled in.
     */
    private fun applyFailure(
        failure: Throwable,
        apply: DiaryUiState.(DiaryFailure?) -> DiaryUiState,
    ) {
        val classified = DiaryFailure.of(failure)
        val reauth = classified is DiaryFailure.ReauthRequired
        state.update {
            it.apply(classified.takeUnless { _ -> reauth }).copy(reauth = it.reauth || reauth)
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DiaryViewModel(repository = Graph.container.diaryRepository)
            }
        }
    }
}
