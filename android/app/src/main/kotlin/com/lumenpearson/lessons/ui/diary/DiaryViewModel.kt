package com.lumenpearson.lessons.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
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
            val failure = result.exceptionOrNull()
            state.update {
                it.copy(
                    signingIn = false,
                    signInError = failure?.let(DiaryFailure::of),
                    reauth = if (failure == null) false else it.reauth,
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
