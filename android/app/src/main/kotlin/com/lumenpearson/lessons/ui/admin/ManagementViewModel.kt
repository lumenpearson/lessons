package com.lumenpearson.lessons.ui.admin

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.lumenpearson.lessons.core.data.di.Graph
import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.SessionRepository
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The state holder behind every management sheet.
 *
 * One view model for eight screens rather than eight, because they share the
 * thing that matters most about them: the answer to "is this page still mine".
 * The server decides that per request from the linked Telegram account, so it
 * can change between two taps, and it arrives as an ordinary `403` on whatever
 * call happened to be next. [ManagementPlumbing.note] is the single place
 * that reads it, and it empties the page for all eight at once — an admin
 * demoted while the subjects sheet is open must not be able to go back and
 * open the devices sheet.
 *
 * Each sheet's work is a class of its own over one [ManagementPlumbing]:
 * [ClassCardActions], [SubjectsActions], [BellsActions], [TimetableActions],
 * [DevicesActions], [LogActions] and [RequestsActions]. Every public method
 * here hands its call on in one line, so the sheets and the tests take this
 * class as they did.
 *
 * Nothing here caches. Every sheet loads when it opens; see
 * [ManageRepository] for why a management surface with a cache would be a
 * second place for the class to be wrong.
 */
class ManagementViewModel(
    private val repository: ManageRepository,
    private val deviceLinks: DeviceLinkRepository,
    private val session: SessionRepository,
) : ViewModel() {

    /** Built first, so the seven classes after it always take one that exists. */
    private val plumbing = ManagementPlumbing(viewModelScope)
    val uiState: StateFlow<ManagementUiState> = plumbing.state.asStateFlow()

    // Each sheet's work, over the one plumbing: built before init, so that
    // nothing the view model runs can reach one that is not there yet.
    private val classCardActions = ClassCardActions(plumbing, repository, session, viewModelScope)
    private val subjectsActions = SubjectsActions(plumbing, repository)
    private val bellsActions = BellsActions(plumbing, repository)
    private val timetableActions = TimetableActions(plumbing, repository)
    private val devicesActions = DevicesActions(plumbing, repository)
    private val logActions = LogActions(plumbing, repository)
    private val requestsActions = RequestsActions(plumbing, repository)

    init {
        // Everything in this state belongs to one class, and this object
        // outlives it. There is one Activity and no nav graph, so the store
        // this view model comes from is the Activity's: signing out only takes
        // `HomeShell` out of composition, and every field here survives into
        // whatever class the phone joins next.
        //
        // `classDeleted` is the one that bites. It is the first branch of the
        // class card, ahead of the load, so the card opened on the *new* class
        // announcing that it had been deleted — and both of its buttons call
        // `leaveDeletedClass`, whose only guard is that same stale flag, so
        // either one threw the user out of the class they had just joined and
        // wiped the cache again. The rest is quieter and still wrong: the
        // previous class's join code, school and member counts are drawn for
        // one round trip before `loadClass` answers, which on a shared phone
        // is one class's invite code shown to another.
        //
        // Keyed on the class id rather than on `null`, because leaving is no
        // longer the only way the class under this screen changes. A phone may
        // hold several and switch between them, which goes from one class
        // straight to the next with no null in between — so a reset that waited
        // for one would never come, and every line above would describe the
        // class the user had just switched away from.
        viewModelScope.launch {
            session.session
                .map { it?.classId }
                .distinctUntilChanged()
                // The first emission is the class this view model was built
                // for; resetting on it would wipe a load already in flight.
                .drop(1)
                .collect { plumbing.state.value = ManagementUiState() }
        }
    }

    // -- the class card -----------------------------------------------------

    fun loadClass() = classCardActions.loadClass()

    /** @see ClassCardActions.saveClass */
    fun saveClass(edit: ClassEdit) = classCardActions.saveClass(edit)

    /** @see ClassCardActions.setJoinMode */
    fun setJoinMode(mode: ClassJoinMode) = classCardActions.setJoinMode(mode)

    // -- the school directory -----------------------------------------------

    /** @see ClassCardActions.searchSchools */
    fun searchSchools(query: String) = classCardActions.searchSchools(query)

    fun showSchoolPage(page: Int) = classCardActions.showSchoolPage(page)

    /** @see ClassCardActions.clearSchoolSearch */
    fun clearSchoolSearch() = classCardActions.clearSchoolSearch()

    /** @see ClassCardActions.deleteClass */
    fun deleteClass(confirmName: String) = classCardActions.deleteClass(confirmName)

    /** @see ClassCardActions.leaveDeletedClass */
    fun leaveDeletedClass() = classCardActions.leaveDeletedClass()

    // -- subjects -----------------------------------------------------------

    fun loadSubjects() = subjectsActions.loadSubjects()

    fun addSubject(form: SubjectForm) = subjectsActions.addSubject(form)

    /** @see SubjectsActions.saveSubject */
    fun saveSubject(before: ManagedSubject, form: SubjectForm) = subjectsActions.saveSubject(before, form)

    fun deleteSubject(subject: ManagedSubject) = subjectsActions.deleteSubject(subject)

    // -- bells --------------------------------------------------------------

    fun loadBells() = bellsActions.loadBells()

    fun addBellSchedule(name: String, periods: List<BellPeriod>) = bellsActions.addBellSchedule(name, periods)

    fun renameBellSchedule(id: Long, name: String) = bellsActions.renameBellSchedule(id, name)

    fun makeBellScheduleDefault(id: Long) = bellsActions.makeBellScheduleDefault(id)

    fun saveBellPeriods(id: Long, periods: List<BellPeriod>) = bellsActions.saveBellPeriods(id, periods)

    fun deleteBellSchedule(schedule: BellSchedule) = bellsActions.deleteBellSchedule(schedule)

    // -- export and import --------------------------------------------------

    /** @see TimetableActions.loadTimetable */
    fun loadTimetable() = timetableActions.loadTimetable()

    /** @see TimetableActions.importTimetable */
    fun importTimetable(text: String) = timetableActions.importTimetable(text)

    /** @see TimetableActions.replacePendingImport */
    fun replacePendingImport() = timetableActions.replacePendingImport()

    /** @see TimetableActions.cancelPendingImport */
    fun cancelPendingImport() = timetableActions.cancelPendingImport()

    // -- devices ------------------------------------------------------------

    fun loadDevices(includeRevoked: Boolean = plumbing.state.value.showRevokedDevices) =
        devicesActions.loadDevices(includeRevoked)

    fun revokeDevice(device: ManagedDevice) = devicesActions.revokeDevice(device)

    fun unlinkDevice(device: ManagedDevice) = devicesActions.unlinkDevice(device)

    // -- the log ------------------------------------------------------------

    fun loadLog(offset: Int = 0) = logActions.loadLog(offset)

    /** @see LogActions.showNextLogPage */
    fun showNextLogPage() = logActions.showNextLogPage()

    fun showPreviousLogPage() = logActions.showPreviousLogPage()

    // -- stats and requests -------------------------------------------------

    fun loadStats() = requestsActions.loadStats()

    fun loadRequests() = requestsActions.loadRequests()

    fun approveRequest(request: AccessRequest, role: ClassRole?) = requestsActions.approveRequest(request, role)

    fun declineRequest(request: AccessRequest) = requestsActions.declineRequest(request)

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
        if (plumbing.state.value.recheckingRole) return
        viewModelScope.launch {
            plumbing.state.update { it.copy(recheckingRole = true) }
            val link = deviceLinks.refresh().getOrNull()
            plumbing.state.update {
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
        if (plumbing.state.value.notice != null) plumbing.state.update { it.copy(notice = null) }
    }

    fun dismissWriteFailure() {
        if (plumbing.state.value.writeFailure != null) plumbing.state.update { it.copy(writeFailure = null) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                ManagementViewModel(
                    repository = Graph.container.manageRepository,
                    deviceLinks = Graph.container.deviceLinkRepository,
                    session = Graph.container.sessionRepository,
                )
            }
        }
    }
}
