package com.lumenpearson.lessons.ui.admin

import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.data.repository.DeviceLink
import com.lumenpearson.lessons.core.data.repository.DeviceLinkRepository
import com.lumenpearson.lessons.core.data.repository.ManageFailure
import com.lumenpearson.lessons.core.data.repository.ManageRepository
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.RequestDecision
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.data.repository.SubjectSaved
import com.lumenpearson.lessons.core.data.repository.TimetableExport
import com.lumenpearson.lessons.core.data.repository.TimetableImport
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A management server that answers exactly what a test tells it to, when the
 * test tells it to.
 *
 * Every call parks on a [CompletableDeferred] the test holds, which is the
 * whole point: the interesting questions about this view model are all about
 * *ordering* — a read that lands after the page was taken away, two reads of
 * the same list that come back in the wrong order — and none of them can be
 * asked of a repository that answers immediately.
 */
internal class FakeManageRepository : ManageRepository {

    /** Calls still waiting, in the order they were made. */
    val pending: MutableList<CompletableDeferred<Result<Any?>>> = mutableListOf()

    /** `include_revoked` as each [devices] call was made with. */
    val deviceCalls: MutableList<Boolean> = mutableListOf()

    /** `replace` as each [importTimetable] call was made with. */
    val importCalls: MutableList<Pair<String, Boolean>> = mutableListOf()

    private suspend fun <T> park(): Result<T> {
        val gate = CompletableDeferred<Result<Any?>>()
        pending += gate
        @Suppress("UNCHECKED_CAST")
        return gate.await() as Result<T>
    }

    /** Answers the [index]th outstanding call with [result]. */
    fun answer(index: Int, result: Result<Any?>) {
        pending[index].complete(result)
    }

    override suspend fun classCard(): Result<ManagedClass> = park()

    override suspend fun updateClass(
        before: ManagedClass,
        edit: ClassEdit,
    ): Result<ManagedClass> = park()

    override suspend fun deleteClass(confirmName: String): Result<Unit> = park()

    override suspend fun subjects(): Result<List<ManagedSubject>> = park()

    override suspend fun createSubject(form: SubjectForm): Result<SubjectSaved> = park()

    override suspend fun updateSubject(
        before: ManagedSubject,
        form: SubjectForm,
    ): Result<SubjectSaved> = park()

    override suspend fun deleteSubject(id: Long): Result<Unit> = park()

    override suspend fun bells(): Result<List<BellSchedule>> = park()

    override suspend fun createBellSchedule(
        name: String,
        periods: List<BellPeriod>,
    ): Result<BellSchedule> = park()

    override suspend fun renameBellSchedule(id: Long, name: String): Result<BellSchedule> = park()

    override suspend fun makeBellScheduleDefault(id: Long): Result<BellSchedule> = park()

    override suspend fun writeBellPeriods(
        id: Long,
        periods: List<BellPeriod>,
    ): Result<BellSchedule> = park()

    override suspend fun deleteBellSchedule(id: Long): Result<Unit> = park()

    override suspend fun timetable(): Result<TimetableExport> = park()

    override suspend fun importTimetable(text: String, replace: Boolean): Result<TimetableImport> {
        importCalls += text to replace
        return park()
    }

    override suspend fun devices(includeRevoked: Boolean): Result<List<ManagedDevice>> {
        deviceCalls += includeRevoked
        return park()
    }

    override suspend fun revokeDevice(id: Long): Result<ManagedDevice> = park()

    override suspend fun unlinkDevice(id: Long): Result<ManagedDevice> = park()

    override suspend fun log(limit: Int, offset: Int): Result<AuditPage> = park()

    override suspend fun stats(): Result<ClassStats> = park()

    override suspend fun requests(): Result<List<AccessRequest>> = park()

    override suspend fun approveRequest(id: Long, role: ClassRole?): Result<RequestDecision> =
        park()

    override suspend fun declineRequest(id: Long): Result<RequestDecision> = park()
}

/** A link the test can set before it is asked for; [refresh] never fails. */
internal class FakeDeviceLinkRepository(role: ClassRole?) : DeviceLinkRepository {

    private val state = MutableStateFlow(link(role))

    override val link: StateFlow<DeviceLink?> = state.asStateFlow()

    var role: ClassRole? = role
        set(value) {
            field = value
            state.value = link(value)
        }

    override suspend fun refresh(): Result<DeviceLink> = Result.success(link(role))

    override suspend fun unlink(): Result<DeviceLink> = Result.success(link(null))

    private fun link(role: ClassRole?) = DeviceLink(
        deviceName = "Pixel",
        linked = role != null,
        role = role,
        canEdit = role != null,
        linkCode = null,
        botDeepLink = null,
    )
}

/** The refusal that means "this page is no longer yours". */
internal val RoleLost: ManageFailure = ManageFailure.RoleLost("admin")
