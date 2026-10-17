package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.data.repository.ImportConflict
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.School
import com.lumenpearson.lessons.core.data.repository.TimetableExport

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
 * homework and substitution rows a rename dragged with it, which is a change nothing
 * on this page can show, and [Imported] says what an import actually wrote.
 */
sealed interface ManagementNotice {
    data class SubjectAdded(val name: String) : ManagementNotice
    data class SubjectRenamed(val name: String, val moved: Int) : ManagementNotice
    data class SubjectSaved(val name: String) : ManagementNotice
    data class SubjectDeleted(val name: String) : ManagementNotice
    data object ClassSaved : ManagementNotice

    /**
     * Carries the mode it landed in rather than «сохранено», because this is
     * the one row on the card whose two states read as opposites and whose
     * confirmation is the whole reassurance: the sentence has to say which way
     * the class went.
     */
    data class JoinModeChanged(val mode: ClassJoinMode) : ManagementNotice
    /**
     * @property silenced lessons this write stopped ringing, and almost always
     *   zero. It is the one thing about a bells write that a reader cannot see
     *   on the screen they are looking at: the rows are still in the database,
     *   the schedule saved cleanly, and the lessons past its last rung are now
     *   drawn nowhere at all. The bot says it in an alert, and until this
     *   carried the number the phone said «Сохранено» and nothing else.
     */
    data class BellsSaved(val name: String, val silenced: Int = 0) : ManagementNotice
    data class BellsDefault(val name: String, val silenced: Int = 0) : ManagementNotice
    data class BellsDeleted(val name: String) : ManagementNotice
    data class Imported(val days: Int, val lessons: Int, val bells: Int) : ManagementNotice
    data object DeviceRevoked : ManagementNotice
    data object DeviceUnlinked : ManagementNotice

    data class RequestApproved(val who: String, val role: ClassRole?) : ManagementNotice
    data class RequestDeclined(val who: String) : ManagementNotice
}

/**
 * A school search, held between the typing and the tapping.
 *
 * The whole result set is kept and paged here rather than asked for a page at
 * a time: the directory has no offset, so the server searches again on every
 * request, and turning a page would be a second search for the same question.
 *
 * @property truncated the directory's ceiling of twenty rows was reached. Not
 *   «есть ещё страницы» — [pages] counts those — but «это первые двадцать из
 *   неизвестно скольких», whose only answer is a longer query.
 * @property unavailable the directory is not configured on this server, or is
 *   not answering. Carries the server's own sentence, which already says to
 *   type the name instead.
 */
data class SchoolSearch(
    val query: String = "",
    val results: List<School> = emptyList(),
    val page: Int = 1,
    val total: Int = 0,
    val truncated: Boolean = false,
    val searching: Boolean = false,
    /** A search ran and found nothing, as against one that has not run. */
    val searched: Boolean = false,
    val unavailable: String? = null,
    val failure: ManageFailure? = null,
) {
    val pages: Int get() = maxOf(1, (results.size + PAGE - 1) / PAGE)

    /** The rows of [page], clamped — the pager is a button pressed twice. */
    val visible: List<School>
        get() = results.drop((page.coerceIn(1, pages) - 1) * PAGE).take(PAGE)

    companion object {
        /** Five to a screen, the same as the bot's keyboard. */
        const val PAGE: Int = 5
    }
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
    val schoolSearch: SchoolSearch = SchoolSearch(),
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
