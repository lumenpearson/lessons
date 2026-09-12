package com.lumenpearson.lessons.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.data.repository.TimetableExport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One thing the management page reads from the server.
 *
 * [value] survives a failed reload on purpose: a list that empties itself
 * because the network blinked is a list an admin will try to fix by adding the
 * rows again.
 */
data class Remote<T>(
    val value: T? = null,
    val loading: Boolean = false,
    val failure: ManageFailure? = null,
)

/**
 * An import that would overwrite something, held between the two taps.
 *
 * The text is kept with the conflicts because «Заменить» sends the *same* text
 * again with `replace: true` — re-reading it off the form would let a stray
 * keystroke between the two taps change what is applied from what was agreed to.
 */
data class PendingImport(
    val text: String,
    val conflicts: List<ImportConflict>,
)

/**
 * Something that has just happened and is worth one line about.
 *
 * A type rather than a string because the sentence belongs in
 * `strings_admin.xml` in two languages, and because two of these carry a number
 * that is the whole reason they exist: [SubjectRenamed] says how many timetable,
 * homework and замена rows a rename dragged with it, which is a change nothing
 * on this page can show, and [Imported] says what an import actually wrote.
 */
sealed interface ManagementNotice {
    data class SubjectAdded(val name: String) : ManagementNotice
    data class SubjectRenamed(val name: String, val moved: Int) : ManagementNotice
    data class SubjectSaved(val name: String) : ManagementNotice
    data class SubjectDeleted(val name: String) : ManagementNotice
    data object ClassSaved : ManagementNotice
    data class BellsSaved(val name: String) : ManagementNotice
    data class BellsDefault(val name: String) : ManagementNotice
    data class BellsDeleted(val name: String) : ManagementNotice
    data class Imported(val days: Int, val lessons: Int, val bells: Int) : ManagementNotice
    data object DeviceRevoked : ManagementNotice
    data object DeviceUnlinked : ManagementNotice

    data class RequestApproved(val who: String, val role: ClassRole?) : ManagementNotice
    data class RequestDeclined(val who: String) : ManagementNotice
}

/**
 * The whole management page, as one state object.
 *
 * @property gone the refusal that took the page away — a dead token, an
 *   unlinked phone, or a role that was demoted in the bot while this was open.
 *   Everything else on the page is hidden while it is set, because every one of
 *   those buttons would now fail, and a screen of buttons that all fail says
 *   less than one sentence explaining why.
 * @property working a write is in flight. One flag for the page rather than one
 *   per row: these are eight sheets that open one at a time, and two writes at
 *   once on the same class is not a thing worth supporting.
 * @property writeFailure what the last write answered. Shown where it happened
 *   and cleared by the next attempt — never swallowed, and never turned into a
 *   silent no-op.
 */
data class ManagementUiState(
    val gone: ManageFailure? = null,
    val recheckingRole: Boolean = false,
    val classCard: Remote<ManagedClass> = Remote(),
    val subjects: Remote<List<ManagedSubject>> = Remote(),
    val bells: Remote<List<BellSchedule>> = Remote(),
    val timetable: Remote<TimetableExport> = Remote(),
    val devices: Remote<List<ManagedDevice>> = Remote(),
    val log: Remote<AuditPage> = Remote(),
    val stats: Remote<ClassStats> = Remote(),
    val requests: Remote<List<AccessRequest>> = Remote(),
    val showRevokedDevices: Boolean = false,
    val pendingImport: PendingImport? = null,
    /**
     * Lines of the last paste the parser could not read.
     *
     * Kept whether or not the import went through, because they are the server
     * telling an admin about two typos — information that is worth exactly as
     * much after a successful import as after a refused one, and that nothing
     * else on this surface will ever mention again.
     */
    val importRejected: List<String> = emptyList(),
    val working: Boolean = false,
    val writeFailure: ManageFailure? = null,
    val notice: ManagementNotice? = null,
    /** The class was deleted from this phone. Nothing below it exists any more. */
    val classDeleted: Boolean = false,
)

/**
 * The state holder behind every management sheet.
 *
 * One view model for eight screens rather than eight, because they share the
 * thing that matters most about them: the answer to "is this page still mine".
 * The server decides that per request from the linked Telegram account, so it
 * can change between two taps, and it arrives as an ordinary `403` on whatever
 * call happened to be next. [note] is the single place that reads it, and it
 * empties the page for all eight at once — an admin demoted while the subjects
 * sheet is open must not be able to go back and open the devices sheet.
 *
 * Nothing here caches. Every sheet loads when it opens; see
 * [ManageRepository] for why a management surface with a cache would be a
 * second place for the class to be wrong.
 */
class ManagementViewModel(
    private val repository: ManageRepository,
    private val deviceLinks: DeviceLinkRepository,
) : ViewModel() {

    private val state = MutableStateFlow(ManagementUiState())
    val uiState: StateFlow<ManagementUiState> = state.asStateFlow()

    // -- the class card -----------------------------------------------------

    fun loadClass() = read({ it.copy(classCard = it.classCard.copy(loading = true, failure = null)) }) {
        val result = repository.classCard()
        finish(result) { current, value, failure ->
            current.copy(classCard = Remote(value ?: current.classCard.value, false, failure))
        }
    }

    /**
     * Saves the four boxes against the card they were filled in from.
     *
     * The card comes out of the state rather than off the screen: the
     * repository needs it to send only the fields that actually changed, and
     * the state is where the form's own initial values came from.
     */
    fun saveClass(edit: ClassEdit) = write {
        val before = state.value.classCard.value ?: return@write Result.success(Unit)
        val result = repository.updateClass(before, edit)
        result.onSuccess { card ->
            state.update {
                it.copy(classCard = Remote(card), notice = ManagementNotice.ClassSaved)
            }
        }
        result
    }

    /**
     * Deletes the class, and with it this phone's own token.
     *
     * [classDeleted] is set rather than the page being reloaded: there is
     * nothing left to load, and the next call would be a `401` that would be
     * shown as "you were signed out" — true, but a strange thing to tell
     * somebody who has just deliberately deleted their class.
     */
    fun deleteClass(confirmName: String) = write {
        val result = repository.deleteClass(confirmName)
        result.onSuccess { state.update { it.copy(classDeleted = true) } }
        result
    }

    // -- subjects -----------------------------------------------------------

    fun loadSubjects() = read({ it.copy(subjects = it.subjects.copy(loading = true, failure = null)) }) {
        val result = repository.subjects()
        finish(result) { current, value, failure ->
            current.copy(subjects = Remote(value ?: current.subjects.value, false, failure))
        }
    }

    fun addSubject(form: SubjectForm) = write {
        val result = repository.createSubject(form)
        result.onSuccess { saved ->
            state.update { it.copy(notice = ManagementNotice.SubjectAdded(saved.subject.name)) }
            loadSubjects()
        }
        result
    }

    /**
     * Saves an edit, and says what a rename cost.
     *
     * [ManagementNotice.SubjectRenamed] is raised only when rows actually moved,
     * because «строк обновлено: 0» after changing a teacher's name would read as
     * a warning about something that did not happen.
     */
    fun saveSubject(before: ManagedSubject, form: SubjectForm) = write {
        val result = repository.updateSubject(before, form)
        result.onSuccess { saved ->
            state.update {
                it.copy(
                    notice = if (saved.moved > 0) {
                        ManagementNotice.SubjectRenamed(saved.subject.name, saved.moved)
                    } else {
                        ManagementNotice.SubjectSaved(saved.subject.name)
                    },
                )
            }
            loadSubjects()
        }
        result
    }

    fun deleteSubject(subject: ManagedSubject) = write {
        val result = repository.deleteSubject(subject.id)
        result.onSuccess {
            state.update { it.copy(notice = ManagementNotice.SubjectDeleted(subject.name)) }
            loadSubjects()
        }
        result
    }

    // -- bells --------------------------------------------------------------

    fun loadBells() = read({ it.copy(bells = it.bells.copy(loading = true, failure = null)) }) {
        val result = repository.bells()
        finish(result) { current, value, failure ->
            current.copy(bells = Remote(value ?: current.bells.value, false, failure))
        }
    }

    fun addBellSchedule(name: String, periods: List<BellPeriod>) = write {
        val result = repository.createBellSchedule(name, periods)
        result.onSuccess { schedule ->
            state.update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun renameBellSchedule(id: Long, name: String) = write {
        val result = repository.renameBellSchedule(id, name)
        result.onSuccess { schedule ->
            state.update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun makeBellScheduleDefault(id: Long) = write {
        val result = repository.makeBellScheduleDefault(id)
        result.onSuccess { schedule ->
            state.update { it.copy(notice = ManagementNotice.BellsDefault(schedule.name)) }
            loadBells()
        }
        result
    }

    fun saveBellPeriods(id: Long, periods: List<BellPeriod>) = write {
        val result = repository.writeBellPeriods(id, periods)
        result.onSuccess { schedule ->
            state.update { it.copy(notice = ManagementNotice.BellsSaved(schedule.name)) }
            loadBells()
        }
        result
    }

    fun deleteBellSchedule(schedule: BellSchedule) = write {
        val result = repository.deleteBellSchedule(schedule.id)
        result.onSuccess {
            state.update { it.copy(notice = ManagementNotice.BellsDeleted(schedule.name)) }
            loadBells()
        }
        result
    }

    // -- export and import --------------------------------------------------

    fun loadTimetable() = read({ it.copy(timetable = it.timetable.copy(loading = true, failure = null)) }) {
        val result = repository.timetable()
        finish(result) { current, value, failure ->
            current.copy(timetable = Remote(value ?: current.timetable.value, false, failure))
        }
    }

    /**
     * The first tap: parse and apply, unless something would be overwritten.
     *
     * A refusal is not a failure and is not reported as one. `applied: false`
     * with conflicts is the preview the bot draws before «Применить», and it
     * lands in [PendingImport] so the sheet can ask.
     */
    fun importTimetable(text: String) = write {
        val result = repository.importTimetable(text, replace = false)
        result.onSuccess { outcome ->
            state.update {
                if (outcome.applied) {
                    it.copy(
                        pendingImport = null,
                        importRejected = outcome.rejected,
                        notice = ManagementNotice.Imported(
                            days = outcome.days.size,
                            lessons = outcome.lessons,
                            bells = outcome.bells,
                        ),
                    )
                } else {
                    it.copy(
                        pendingImport = PendingImport(text = text, conflicts = outcome.conflicts),
                        importRejected = outcome.rejected,
                    )
                }
            }
            if (outcome.applied) loadTimetable()
        }
        result
    }

    /** The second tap: the same text, with consent. */
    fun replacePendingImport() {
        val pending = state.value.pendingImport ?: return
        write {
            val result = repository.importTimetable(pending.text, replace = true)
            result.onSuccess { outcome ->
                state.update {
                    it.copy(
                        pendingImport = null,
                        importRejected = outcome.rejected,
                        notice = ManagementNotice.Imported(
                            days = outcome.days.size,
                            lessons = outcome.lessons,
                            bells = outcome.bells,
                        ),
                    )
                }
                loadTimetable()
            }
            result
        }
    }

    /** Cancels it. Nothing was written, so there is nothing to undo. */
    fun cancelPendingImport() {
        state.update { it.copy(pendingImport = null) }
    }

    // -- devices ------------------------------------------------------------

    fun loadDevices(includeRevoked: Boolean = state.value.showRevokedDevices) =
        read({
            it.copy(
                showRevokedDevices = includeRevoked,
                devices = it.devices.copy(loading = true, failure = null),
            )
        }) {
            val result = repository.devices(includeRevoked)
            finish(result) { current, value, failure ->
                current.copy(devices = Remote(value ?: current.devices.value, false, failure))
            }
        }

    fun revokeDevice(device: ManagedDevice) = write {
        val result = repository.revokeDevice(device.id)
        result.onSuccess {
            state.update { it.copy(notice = ManagementNotice.DeviceRevoked) }
            loadDevices()
        }
        result
    }

    fun unlinkDevice(device: ManagedDevice) = write {
        val result = repository.unlinkDevice(device.id)
        result.onSuccess {
            state.update { it.copy(notice = ManagementNotice.DeviceUnlinked) }
            loadDevices()
        }
        result
    }

    // -- the log ------------------------------------------------------------

    fun loadLog(offset: Int = 0) =
        read({ it.copy(log = it.log.copy(loading = true, failure = null)) }) {
            val result = repository.log(offset = offset)
            finish(result) { current, value, failure ->
                current.copy(log = Remote(value ?: current.log.value, false, failure))
            }
        }

    /** One page forward, or one back; both are the same read at a new offset. */
    fun showNextLogPage() {
        val page = state.value.log.value ?: return
        if (!page.hasMore) return
        loadLog(page.offset + page.limit)
    }

    fun showPreviousLogPage() {
        val page = state.value.log.value ?: return
        if (page.offset <= 0) return
        loadLog((page.offset - page.limit).coerceAtLeast(0))
    }

    // -- stats and requests -------------------------------------------------

    fun loadStats() = read({ it.copy(stats = it.stats.copy(loading = true, failure = null)) }) {
        val result = repository.stats()
        finish(result) { current, value, failure ->
            current.copy(stats = Remote(value ?: current.stats.value, false, failure))
        }
    }

    fun loadRequests() = read({ it.copy(requests = it.requests.copy(loading = true, failure = null)) }) {
        val result = repository.requests()
        finish(result) { current, value, failure ->
            current.copy(requests = Remote(value ?: current.requests.value, false, failure))
        }
    }

    fun approveRequest(request: AccessRequest, role: ClassRole?) = write {
        val result = repository.approveRequest(request.id, role)
        result.onSuccess { decision ->
            state.update {
                it.copy(notice = ManagementNotice.RequestApproved(decision.who, decision.role))
            }
            loadRequests()
        }
        result
    }

    fun declineRequest(request: AccessRequest) = write {
        val result = repository.declineRequest(request.id)
        result.onSuccess { decision ->
            state.update { it.copy(notice = ManagementNotice.RequestDeclined(decision.who)) }
            loadRequests()
        }
        result
    }

    // -- the page itself ----------------------------------------------------

    /**
     * Asks the server what this phone may do now.
     *
     * The way back onto the page after a `403`. It goes through the device link
     * rather than through this surface because that is the call that answers
     * "what is my role" without needing the role to answer — asking
     * `GET /manage/class` again would be the same refusal, forever.
     */
    fun recheckRole() {
        if (state.value.recheckingRole) return
        viewModelScope.launch {
            state.update { it.copy(recheckingRole = true) }
            val link = deviceLinks.refresh().getOrNull()
            state.update {
                it.copy(
                    recheckingRole = false,
                    // Only a role that manages clears it. Anything else — a
                    // failed call, a phone that is still unlinked — leaves the
                    // sentence up, because nothing has changed.
                    gone = if (isClassManager(link?.role)) null else it.gone,
                )
            }
        }
    }

    fun consumeNotice() {
        if (state.value.notice != null) state.update { it.copy(notice = null) }
    }

    fun dismissWriteFailure() {
        if (state.value.writeFailure != null) state.update { it.copy(writeFailure = null) }
    }

    // -- the plumbing under all of it ---------------------------------------

    /** A read: mark it in flight, run it, and let [note] see whatever it answers. */
    private fun read(
        begin: (ManagementUiState) -> ManagementUiState,
        block: suspend () -> Unit,
    ) {
        if (state.value.gone != null) return
        state.update(begin)
        viewModelScope.launch { block() }
    }

    /**
     * A write: one at a time, and its failure kept where the screen can show it.
     *
     * Refused rather than queued while another write is in flight, because two
     * of these change the same row and the second would be answered against a
     * class the user has not seen yet.
     */
    private fun write(block: suspend () -> Result<*>) {
        if (state.value.gone != null || state.value.working) return
        state.update { it.copy(working = true, writeFailure = null) }
        viewModelScope.launch {
            val failure = block().exceptionOrNull()?.let(ManageFailure::of)
            // [note] rewrites the whole state when it recognises the refusal, so
            // it runs before this update rather than inside it: an update that
            // updates again is one of the two that loses.
            if (!note(failure)) {
                state.update { it.copy(working = false, writeFailure = failure) }
            }
        }
    }

    /** Stores a read's answer, after [note] has had its say about the failure. */
    private fun <T> finish(
        result: Result<T>,
        apply: (ManagementUiState, T?, ManageFailure?) -> ManagementUiState,
    ) {
        val failure = result.exceptionOrNull()?.let(ManageFailure::of)
        val ends = note(failure)
        state.update { apply(it, result.getOrNull(), failure.takeUnless { ends }) }
    }

    /**
     * The one place a refusal is read as "the page is no longer yours".
     *
     * @return whether it was one, so the caller can drop the failure instead of
     *   also drawing it next to the button: the page is about to say the whole
     *   of it, once, and an error card underneath would be the same news twice.
     */
    private fun note(failure: ManageFailure?): Boolean {
        if (failure == null || !failure.endsTheSession) return false
        state.update {
            // Nothing that was read under the old role is shown again: it was
            // true for somebody who is not this user any more.
            ManagementUiState(gone = failure)
        }
        return true
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ManagementViewModel(
                    repository = Graph.container.manageRepository,
                    deviceLinks = Graph.container.deviceLinkRepository,
                )
            }
        }
    }
}
