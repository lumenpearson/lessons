package com.lumenpearson.lessons.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.diary.DiaryCache
import com.lumenpearson.lessons.core.data.diary.DiaryCachedWeek
import com.lumenpearson.lessons.core.data.diary.diaryIsFresh
import com.lumenpearson.lessons.core.data.repository.DiaryBinding
import com.lumenpearson.lessons.core.data.repository.DiaryFailure
import com.lumenpearson.lessons.core.data.repository.DiaryField
import com.lumenpearson.lessons.core.data.repository.DiaryPeriod
import com.lumenpearson.lessons.core.data.repository.DiaryRepository
import com.lumenpearson.lessons.core.data.repository.DiarySession
import com.lumenpearson.lessons.core.data.repository.DiarySignInProblem
import com.lumenpearson.lessons.core.data.repository.DiaryStudent
import com.lumenpearson.lessons.core.data.repository.DiaryTarget
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The diary section's state holder — and, on a phone in no class, the diary
 * home's.
 *
 * It owns three loads — the pupils, a week of the timetable, a term of marks —
 * and two rules that run through all of them.
 *
 * The first: what the phone saved is drawn before the network answers, and a
 * save younger than [com.lumenpearson.lessons.core.data.diary.DiaryFreshFor]
 * is not asked for again. The cache is filled by the repository's own reads
 * (write-through), so the week drawn from it is the week the diary answered,
 * not a second derivation. When the diary cannot be reached, the saved rows
 * stay on screen with the moment they were saved, rather than being replaced
 * by a failure card over nothing.
 *
 * The second: a failure that means "the diary logged us out upstream" flips
 * [DiaryUiState.reauth] instead of becoming an error message. Anything else is
 * kept as a typed [DiaryFailure] and turned into a sentence by the screen,
 * which is the only place that knows how to phrase one.
 *
 * The password never reaches this class as state: it arrives as an argument to
 * [signIn], is handed to the repository, and is gone when the call returns.
 */
class DiaryViewModel(
    private val repository: DiaryRepository,
    private val cache: DiaryCache = NoDiaryCache,
    binding: Flow<DiaryBinding?> = flowOf(null),
    private val describe: suspend (DiaryTarget) -> DiaryPlace? = { null },
    private val clock: Clock = Clock.systemUTC(),
    /** The shell's hold: the first run owns the screen; see [Heard.onboarding]. */
    held: Flow<Boolean> = flowOf(false),
) : ViewModel() {

    private val state = MutableStateFlow(
        DiaryUiState(weekStart = diaryWeekStart(diaryToday(DefaultDiaryZone, clock))),
    )
    val uiState: StateFlow<DiaryUiState> = state.asStateFlow()

    // What follows is everything this class holds beside [state], and none of
    // it is state: nothing on the screen draws a job, a memo or the last word
    // of a flow. Each is a private holder that alone changes what it holds,
    // declared above `init` because the collectors there can reach them before
    // `init` has returned.

    /** Cancelled and replaced whenever the pupil, the week or the tab changes. */
    private val loadJob = JobSlot(viewModelScope)

    /** The home's start refresh while it runs; see [refreshOnStart]. */
    private val startRefresh = JobSlot(viewModelScope)

    /** Asked for once per pupil: terms do not change while a screen is open. */
    private val periods = PeriodMemo()

    /**
     * The latest from the flows this view model is handed besides the session;
     * see [Heard]. Replaced by [hear], from their collectors below, and
     * nowhere else.
     */
    private var heard = Heard()

    init {
        viewModelScope.launch {
            repository.session.collect { session ->
                val had = state.value.session
                val zone = session?.target?.zoneId() ?: DefaultDiaryZone
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
                        savedAt = if (session == null) null else it.savedAt,
                        zone = zone,
                        // The week follows the zone only while nobody has moved
                        // it: a session in Tomsk arriving after the screen opened
                        // on Moscow's date must not leave the pupil on the week
                        // Moscow was in, and must not undo a week they chose.
                        weekStart = if (it.weekStart == diaryWeekStart(diaryToday(it.zone, clock))) {
                            diaryWeekStart(diaryToday(zone, clock))
                        } else {
                            it.weekStart
                        },
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
                settleTarget()
                if (session != null && state.value.students.isEmpty() && !heard.onboarding) loadStudents()
            }
        }
        viewModelScope.launch {
            held.distinctUntilChanged().collect { holding ->
                hear { copy(onboarding = holding) }
                if (holding) {
                    // What an earlier session left running is not the new one's.
                    loadJob.cancel()
                    state.update { it.copy(students = emptyList(), selectedStudentId = null, studentsLoading = false) }
                } else if (state.value.session != null && state.value.students.isEmpty()) {
                    loadStudents()
                }
            }
        }
        viewModelScope.launch {
            repository.target.collect { target ->
                hear { copy(storedTarget = target) }
                settleTarget()
            }
        }
        viewModelScope.launch {
            binding.map { it?.targetFor(login = "") }
                .distinctUntilChanged()
                .collect { target ->
                    hear { copy(boundTarget = target) }
                    settleTarget()
                }
        }
    }

    /** The one place [heard] is written. */
    private fun hear(change: Heard.() -> Heard) {
        heard = heard.change()
    }

    /**
     * Works out [DiaryUiState.signInTarget] again, and what the catalog says
     * about it when that changed.
     */
    private fun settleTarget() {
        val target = signInTargetOf(
            state.value.chosen,
            state.value.session,
            heard.storedTarget,
            heard.boundTarget,
        )
        if (target == state.value.signInTarget && state.value.place != null) return
        val sameDiary = state.value.signInTarget.sameDiaryAs(target)
        state.update { it.copy(signInTarget = target, place = if (sameDiary) it.place else null) }
        if (sameDiary && state.value.place != null) return
        viewModelScope.launch {
            val place = runCatching { describe(target) }.getOrNull()
            // A later target may have arrived while the catalog was read.
            if (state.value.signInTarget.sameDiaryAs(target)) {
                state.update { it.copy(place = place) }
            }
        }
    }

    /** Opens the diary picker in place of the sign-in form. */
    fun startPicking() {
        state.update { it.copy(picking = true) }
    }

    /** Closes the picker without changing the diary. */
    fun cancelPicking() {
        state.update { it.copy(picking = false) }
    }

    /**
     * The picker's answer: the next sign-in goes to [target]. Held in memory
     * only — it carries no secret, but nothing is stored until the diary has
     * said yes, which is when the repository writes the session and its target.
     */
    fun choose(target: DiaryTarget) {
        state.update { it.copy(chosen = target, picking = false, signInError = null) }
        settleTarget()
    }

    /**
     * Signs in, or answers the re-authentication prompt; both are the same call.
     *
     * To [DiaryUiState.signInTarget] — so a re-authentication goes to the same
     * provider, region and school with only the password typed, and a family
     * whose class names its diary signs in to that one without searching.
     */
    fun signIn(login: String, password: String) {
        if (state.value.signingIn) return
        viewModelScope.launch {
            state.update { it.copy(signingIn = true, signInError = null) }
            // Read again rather than taken from the state: the stored target
            // is a DataStore read that may not have reached the collector yet.
            val known = state.value.chosen
                ?: state.value.session?.target
                ?: repository.target.first()
                ?: heard.boundTarget
            val target = known?.copy(login = login.trim()) ?: DiaryTarget.petersburg(login.trim())
            val result = repository.signIn(target, password)
            val problem = result.exceptionOrNull()?.let { DiarySignInProblem.of(it) }
            state.update {
                it.copy(
                    signingIn = false,
                    signInError = problem,
                    reauth = if (problem == null) false else it.reauth,
                    signInOutcome = problem?.let(DiarySignInOutcome::Failed)
                        // The login as it was typed, not as the server echoed
                        // it: the session row arrives moments later through the
                        // repository's flow, and the pop-up is about the
                        // attempt that has just finished.
                        ?: DiarySignInOutcome.Succeeded(login),
                )
            }
            if (problem == null) {
                // The pupils are re-read rather than kept: the same phone may
                // have signed in as a different parent.
                periods.forget()
                state.update { it.copy(chosen = null, students = emptyList(), selectedStudentId = null) }
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
            periods.forget()
            // A fresh state, which forgets the diary picked on the form with
            // everything else; what [heard] holds is not the screen's to forget.
            state.update {
                DiaryUiState(
                    ready = true,
                    zone = DefaultDiaryZone,
                    weekStart = diaryWeekStart(diaryToday(DefaultDiaryZone, clock)),
                )
            }
            settleTarget()
        }
    }

    fun selectStudent(id: Long) {
        if (state.value.selectedStudentId == id) return
        state.update { it.copy(selectedStudentId = id, days = emptyList(), subjects = emptyList()) }
        // Kept, so the next launch — and the start refresh, which reads the
        // stored choice — are about the same child.
        viewModelScope.launch { runCatching { cache.selectStudent(id) } }
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

    /**
     * The week holding [date], on the timetable tab — what a stale widget tap
     * asks for when it lands on the diary home rather than on a class calendar.
     */
    fun showWeekOf(date: LocalDate) {
        val start = diaryWeekStart(date)
        if (state.value.weekStart == start && state.value.tab == DiaryTab.SCHEDULE) return
        state.update { it.copy(weekStart = start, tab = DiaryTab.SCHEDULE) }
        reload()
    }

    /** What the "повторить" button on every failure card does: always the network. */
    fun retry() {
        if (state.value.students.isEmpty()) loadStudents(force = true) else reload(force = true)
    }

    /**
     * The foreground refresh, run by the diary home each time it comes to the
     * front: [refresh] is `DiaryImport.refreshIfStale`, which re-reads this week
     * and the next when the saved copy is older than
     * [com.lumenpearson.lessons.core.data.diary.DiaryFreshFor] — and is the only
     * thing that reads the diary without somebody pressing something.
     *
     * The screen's own week load waits for it and then reads the save again,
     * so a cold start asks the diary for the week once and not once per caller:
     * the week the refresh just saved is fresh, and the load finds it so. Rows
     * already on screen are redrawn from the save when it lands.
     */
    fun refreshOnStart(refresh: suspend () -> Result<Boolean>) {
        if (startRefresh.running != null) return
        val job = startRefresh.launch { runCatching { refresh() } }
        viewModelScope.launch {
            job.join()
            if (loadJob.running == null && state.value.student != null) reload()
        }
    }

    /**
     * Waits for a start refresh that is under way; `true` when there was one,
     * so the caller knows the save may have changed under it.
     */
    private suspend fun awaitStartRefresh(): Boolean {
        val running = startRefresh.running ?: return false
        running.join()
        return true
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

    private fun loadStudents(force: Boolean = false) {
        loadJob.launch { loadStudentsNow(force) }
    }

    /**
     * The pupils: what was saved first, then the diary's answer unless the save
     * is fresh. A choice stored earlier — by the import, or by this screen — is
     * the child shown, so a family with two children opens on the one they
     * picked rather than on whoever the diary lists first.
     */
    private suspend fun loadStudentsNow(force: Boolean) {
        val saved = runCatching { cache.students.first() }.getOrNull() ?: NoStudents
        val remembered = runCatching { cache.selectedStudentId.first() }.getOrNull()
        if (saved.students.isNotEmpty()) applyStudents(saved.students, remembered)
        if (!force && saved.students.isNotEmpty() && diaryIsFresh(saved.loadedAt, clock.instant())) {
            load(force = false)
            return
        }

        state.update {
            it.copy(studentsLoading = it.students.isEmpty(), studentsError = null)
        }
        val result = repository.students()
        val found = result.getOrNull()
        if (found == null) {
            val failure = DiaryFailure.of(result.exceptionOrNull() ?: DiaryFailure.Unavailable)
            if (saved.students.isNotEmpty() && failure.keepsSavedRows) {
                state.update { it.copy(studentsLoading = false, savedAt = saved.loadedAt) }
                load(force = false)
                return
            }
            state.update {
                it.copy(
                    studentsLoading = false,
                    studentsError = failure.takeUnless { f -> f is DiaryFailure.ReauthRequired },
                    reauth = it.reauth || failure is DiaryFailure.ReauthRequired,
                )
            }
            return
        }
        state.update { it.copy(studentsLoading = false) }
        applyStudents(found, remembered)
        load(force = false)
    }

    private suspend fun applyStudents(found: List<DiaryStudent>, remembered: Long?) {
        // The chosen child across a refresh, then the stored choice, then the
        // first — which for most accounts is the only one, and is why the
        // picker is hidden at that size.
        val current = state.value.selectedStudentId
        val selected = found.firstOrNull { it.id == current }?.id
            ?: found.firstOrNull { it.id == remembered }?.id
            ?: found.firstOrNull()?.id
        state.update { it.copy(students = found, selectedStudentId = selected) }
        // Written down when it was a default rather than a choice, because the
        // start refresh reads the stored one and would otherwise refresh no one
        // for an account with two children.
        if (selected != null && selected != remembered && found.size > 1) {
            runCatching { cache.selectStudent(selected) }
        }
    }

    private fun reload(force: Boolean = false) {
        loadJob.launch { load(force) }
    }

    /**
     * Fetches the open tab again, from the diary and not from the save.
     *
     * Public because writing a correction has to be followed by a read: the
     * server is what applies a correction, the save holds the rows from before
     * it, and `retry` — the only other way back in — is reachable only from a
     * failure card.
     */
    fun refresh() = reload(force = true)

    private suspend fun load(force: Boolean) {
        val current = state.value
        val student = current.student ?: return
        when (current.tab) {
            DiaryTab.SCHEDULE -> loadSchedule(student.id, current.weekStart, force)
            DiaryTab.GRADES -> loadGrades(student.id, current.today, force)
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
    private suspend fun loadSchedule(studentId: Long, weekStart: LocalDate, force: Boolean) {
        var saved = savedWeek(studentId, weekStart)
        showSavedWeek(weekStart, saved)
        // The start refresh may be reading this very week; its answer is the
        // one to draw, and asking again beside it would be the same request
        // twice.
        if (awaitStartRefresh()) {
            saved = savedWeek(studentId, weekStart)
            showSavedWeek(weekStart, saved)
        }
        val savedAt = saved?.savedAt
        if (!force && savedAt != null && diaryIsFresh(savedAt, clock.instant())) return

        val weekEnd = weekStart.plusDays(6)
        val (lessons, homework) = coroutineScope {
            val timetable = async { repository.schedule(studentId, weekStart, weekEnd) }
            val assignments = async { repository.homework(studentId, weekStart, weekEnd) }
            timetable.await() to assignments.await()
        }
        val failure = lessons.exceptionOrNull() ?: homework.exceptionOrNull()
        if (failure != null) {
            if (savedAt != null && DiaryFailure.of(failure).keepsSavedRows) {
                state.update { it.copy(scheduleLoading = false, savedAt = savedAt) }
                return
            }
            applyFailure(failure) { copy(scheduleLoading = false, scheduleError = it) }
            return
        }
        val days = diaryWeek(
            weekStart = weekStart,
            lessons = lessons.getOrDefault(emptyList()),
            homework = homework.getOrDefault(emptyList()),
        )
        state.update { it.copy(scheduleLoading = false, days = days, savedAt = null) }
    }

    private suspend fun savedWeek(studentId: Long, weekStart: LocalDate): DiaryCachedWeek? =
        runCatching { cache.week(studentId, weekStart).first() }.getOrNull()

    /** Draws a saved week, or the skeleton when there is none to draw. */
    private fun showSavedWeek(weekStart: LocalDate, saved: DiaryCachedWeek?) {
        if (saved?.savedAt != null) {
            state.update {
                it.copy(
                    scheduleLoading = false,
                    scheduleError = null,
                    savedAt = null,
                    days = diaryWeek(weekStart, saved.lessons, saved.homework),
                )
            }
        } else {
            state.update { it.copy(scheduleLoading = true, scheduleError = null, savedAt = null) }
        }
    }

    /**
     * The marks of the current term, clipped to what the server will serve.
     *
     * The term is asked for first because a quarter is the unit a parent thinks
     * in — and then [diaryGradeRange] narrows it, because a quarter is usually
     * longer than the 62 days one request may cover. A term that cannot be read
     * is not an error: the range simply falls back to the last 62 days.
     */
    private suspend fun loadGrades(studentId: Long, today: LocalDate, force: Boolean) {
        val period = currentPeriod(studentId, force)
        val range = diaryGradeRange(today, period)
        val saved = runCatching { cache.marks(studentId).first() }.getOrNull()
        val savedAt = saved?.takeIf { it.covers(range) }?.loadedAt
        if (saved != null && savedAt != null) {
            state.update {
                it.copy(
                    gradesLoading = false,
                    gradesError = null,
                    savedAt = null,
                    subjects = summariseMarks(saved.marks),
                    gradeRange = range,
                )
            }
            if (!force && diaryIsFresh(savedAt, clock.instant())) return
        } else {
            state.update { it.copy(gradesLoading = true, gradesError = null, savedAt = null) }
        }

        val result = repository.grades(studentId, range.from, range.to)
        val failure = result.exceptionOrNull()
        if (failure != null) {
            if (savedAt != null && DiaryFailure.of(failure).keepsSavedRows) {
                state.update { it.copy(gradesLoading = false, savedAt = savedAt) }
                return
            }
            applyFailure(failure) { copy(gradesLoading = false, gradesError = it) }
            return
        }
        val subjects = summariseMarks(result.getOrDefault(emptyList()))
        state.update {
            it.copy(gradesLoading = false, subjects = subjects, gradeRange = range, savedAt = null)
        }
    }

    /**
     * The term today falls in: remembered for the screen's life, else the saved
     * terms when they are fresh, else the diary's — and the saved ones again
     * when the diary cannot answer, since a term from last week is a far better
     * window than none.
     */
    private suspend fun currentPeriod(studentId: Long, force: Boolean): DiaryPeriod? =
        periods.of(studentId, force) {
            val saved = runCatching { cache.periods(studentId).first() }.getOrNull()
            if (!force && saved != null && diaryIsFresh(saved.loadedAt, clock.instant())) {
                saved.periods.firstOrNull { it.isCurrent }
            } else {
                repository.periods(studentId).getOrNull()?.firstOrNull { it.isCurrent }
                    ?: saved?.periods?.firstOrNull { it.isCurrent }
            }
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
     *
     * "What changed" is measured against the row as the sheet opened on it,
     * not against a fresh read — and the row is not this account's alone. The
     * corrections are the child's (#165), so the other parent may have
     * rewritten or reset a field since: a field the snapshot already shows
     * corrected to the typed value sends nothing, and a field typed back to the
     * diary's value resets whatever is there now, theirs included. That is
     * last-writer-wins, which is what the server is too — it keeps no version
     * to compare against — and the reload after every save is what puts the
     * outcome on screen.
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
        // Left open, on purpose: what the person typed is still in the fields,
        // and closing the sheet would throw it away to show them a message
        // about why it had not been saved. The one exception is a failure that
        // asks for the password, and `applyFailure` closes it for that — a
        // modal with no message in it, over a form nobody can see, is how
        // somebody concludes the button is broken.
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
            it.apply(classified.takeUnless { _ -> reauth }).copy(
                reauth = it.reauth || reauth,
                // Whatever raised the prompt, the sheet goes with it. The sheet
                // is drawn above the branch that chooses between the form and
                // the page, so it survives the swap: leave it and a modal with
                // a «Сохранить» that can only fail sits on top of the password
                // field. The reachable way in is not the save itself — that
                // case is handled where it happens — but the reload fired
                // right after a *different* failure, which can come back
                // asking for the password.
                editing = if (it.reauth || reauth) null else it.editing,
            )
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val container = Graph.container
                DiaryViewModel(
                    repository = container.diaryRepository,
                    cache = container.diaryCache,
                    binding = container.sessionRepository.session.map { it?.diary },
                    describe = { target -> diaryPlaceOf(container.regionLookup.catalog(), target) },
                    held = container.shellMode.state.map { it.held },
                )
            }
        }
    }
}

/**
 * Which diary a sign-in goes to; see [DiaryUiState.signInTarget]. The login is
 * whatever the source carried, and the form replaces it with what was typed.
 */
internal fun signInTargetOf(
    chosen: DiaryTarget?,
    session: DiarySession?,
    stored: DiaryTarget?,
    bound: DiaryTarget?,
): DiaryTarget = chosen ?: session?.target ?: stored ?: bound ?: DiaryTarget.petersburg("")

/** The same diary, region and school, whoever is signing in to it. */
private fun DiaryTarget.sameDiaryAs(other: DiaryTarget): Boolean =
    provider == other.provider && region == other.region && schoolId == other.schoolId

/**
 * Whether a failed read leaves the saved rows on screen, with when they were
 * saved, instead of a failure card.
 *
 * The failures that say nothing about the rows — the diary or our server could
 * not be reached just now — and nothing else: a sign-in that ended, or an
 * answer that says this app asked for the wrong thing, is not something the
 * saved rows can stand in for.
 */
private val DiaryFailure.keepsSavedRows: Boolean
    get() = this is DiaryFailure.Offline || this is DiaryFailure.Unavailable

/** When a saved week is from: its older half, since both are drawn together. */
private val DiaryCachedWeek.savedAt: Instant?
    get() {
        val lessons = lessonsLoadedAt ?: return null
        val homework = homeworkLoadedAt ?: return null
        return minOf(lessons, homework)
    }

/**
 * One coroutine at a time: launching another cancels the one before.
 *
 * A handle, not state. Nothing draws a job — what a load does is drawn, through
 * the flags it writes into [DiaryUiState] — and a `Job` in a data class that
 * the screen compares on every emission would be a value that is never equal
 * to anything but itself.
 */
private class JobSlot(private val scope: CoroutineScope) {

    private var job: Job? = null

    /** The job in the slot while it runs; `null` once it has finished or before any. */
    val running: Job? get() = job?.takeIf { it.isActive }

    fun launch(block: suspend CoroutineScope.() -> Unit): Job {
        job?.cancel()
        return scope.launch(block = block).also { job = it }
    }

    fun cancel() {
        job?.cancel()
    }
}

/**
 * The term each pupil is in, asked for once: terms do not change while a
 * screen is open.
 *
 * A memo, not state. Nothing draws a term — what is drawn is the range cut from
 * it, [DiaryUiState.gradeRange] — and it lives as long as the account rather
 * than the screen: [forget] runs when a sign-in lands and when the diary is
 * signed out of, the two moments the pupils it is keyed on can stop being this
 * phone's.
 */
private class PeriodMemo {

    private val periods = mutableMapOf<Long, DiaryPeriod?>()

    /** The remembered term, unless [force] or there is none yet; then [read]'s, remembered. */
    suspend fun of(studentId: Long, force: Boolean, read: suspend () -> DiaryPeriod?): DiaryPeriod? {
        if (!force && periods.containsKey(studentId)) return periods[studentId]
        return read().also { periods[studentId] = it }
    }

    fun forget() {
        periods.clear()
    }
}

/**
 * What the view model last heard from the flows it is handed besides the
 * session.
 *
 * Not in [DiaryUiState], and deliberately. None of the three is the screen's
 * to reset: they belong to the repository, the class and the shell, and each
 * flow says when it changes. The state is reset wholesale by a sign-out, and
 * the binding kept there would be forgotten by the one call after which it
 * matters most — its flow does not say it again, so the next sign-in would go
 * to Petersburg rather than to the class's diary. Nor does the screen draw
 * any of them: what they decide is drawn, as [DiaryUiState.signInTarget] and
 * as pupils loaded or not.
 *
 * @property storedTarget what a bare `401` left; see [DiaryUiState.signInTarget].
 * @property boundTarget what the class's join said; see [DiaryUiState.signInTarget].
 */
private data class Heard(
    val storedTarget: DiaryTarget? = null,
    val boundTarget: DiaryTarget? = null,
    /**
     * The first run holds the screen, and its import is the one reading the
     * diary — pupils included.
     *
     * This view model is the activity's, so one made by an earlier diary home
     * (or by «Настройки → Дневник» on a class phone) is still collecting when a
     * later first run signs in. It used to take that session for its own: it
     * read the pupils, wrote the first of two as the stored choice — the key
     * the import asks before it offers its chooser, so a family with two
     * children was never asked — and kept that child in memory even when the
     * chooser won, so the home opened on the pupil nobody picked. While held
     * it loads nothing; on the release it reads the pupils and the choice the
     * import stored, as a home opened for the first time would.
     */
    val onboarding: Boolean = false,
)
