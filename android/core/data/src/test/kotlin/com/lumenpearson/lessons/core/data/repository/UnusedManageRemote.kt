package com.lumenpearson.lessons.core.data.repository

import com.lumenpearson.lessons.core.data.network.v2.CallObservation
import com.lumenpearson.lessons.core.data.network.v2.ManageRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageClassRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageSubjectRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageBellRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageTimetableRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageDeviceRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageRequestRemote
import com.lumenpearson.lessons.core.data.network.v2.ManageReportRemote

internal object UnusedManageRemote : ManageRemote,
    ManageClassRemote by UnusedManageClassRemote,
    ManageSubjectRemote by UnusedManageSubjectRemote,
    ManageBellRemote by UnusedManageBellRemote,
    ManageTimetableRemote by UnusedManageTimetableRemote,
    ManageDeviceRemote by UnusedManageDeviceRemote,
    ManageRequestRemote by UnusedManageRequestRemote,
    ManageReportRemote by UnusedManageReportRemote

private object UnusedManageClassRemote : ManageClassRemote {
    override suspend fun classCard(observation: CallObservation): ManagedClass =
        error("This management operation is not used by the fixture")
    override suspend fun updateClass(
        before: ManagedClass,
        edit: ClassEdit,
        observation: CallObservation,
    ): ManagedClass =
        error("This management operation is not used by the fixture")
    override suspend fun setJoinMode(mode: ClassJoinMode, observation: CallObservation): ManagedClass =
        error("This management operation is not used by the fixture")
    override suspend fun deleteClass(confirmName: String, observation: CallObservation): Unit =
        error("This management operation is not used by the fixture")
    override suspend fun searchSchools(query: String, observation: CallObservation): SchoolPage =
        error("This management operation is not used by the fixture")
}

private object UnusedManageSubjectRemote : ManageSubjectRemote {
    override suspend fun subjects(observation: CallObservation): List<ManagedSubject> =
        error("This management operation is not used by the fixture")
    override suspend fun createSubject(form: SubjectForm, observation: CallObservation): SubjectSaved =
        error("This management operation is not used by the fixture")
    override suspend fun updateSubject(
        before: ManagedSubject,
        form: SubjectForm,
        observation: CallObservation,
    ): SubjectSaved =
        error("This management operation is not used by the fixture")
    override suspend fun deleteSubject(id: Long, observation: CallObservation): Unit =
        error("This management operation is not used by the fixture")
}

private object UnusedManageBellRemote : ManageBellRemote {
    override suspend fun bells(observation: CallObservation): List<BellSchedule> =
        error("This management operation is not used by the fixture")
    override suspend fun createBellSchedule(
        name: String,
        periods: List<BellPeriod>,
        observation: CallObservation,
    ): BellSchedule =
        error("This management operation is not used by the fixture")
    override suspend fun renameBellSchedule(id: Long, name: String, observation: CallObservation): BellSchedule =
        error("This management operation is not used by the fixture")
    override suspend fun makeBellScheduleDefault(id: Long, observation: CallObservation): BellsWritten =
        error("This management operation is not used by the fixture")
    override suspend fun writeBellPeriods(
        id: Long,
        periods: List<BellPeriod>,
        observation: CallObservation,
    ): BellsWritten =
        error("This management operation is not used by the fixture")
    override suspend fun deleteBellSchedule(id: Long, observation: CallObservation): Unit =
        error("This management operation is not used by the fixture")
}

private object UnusedManageTimetableRemote : ManageTimetableRemote {
    override suspend fun timetable(observation: CallObservation): TimetableExport =
        error("This management operation is not used by the fixture")
    override suspend fun importTimetable(
        text: String,
        replace: Boolean,
        observation: CallObservation,
    ): TimetableImport =
        error("This management operation is not used by the fixture")
}

private object UnusedManageDeviceRemote : ManageDeviceRemote {
    override suspend fun devices(includeRevoked: Boolean, observation: CallObservation): List<ManagedDevice> =
        error("This management operation is not used by the fixture")
    override suspend fun revokeDevice(id: Long, observation: CallObservation): ManagedDevice =
        error("This management operation is not used by the fixture")
    override suspend fun unlinkDevice(id: Long, observation: CallObservation): ManagedDevice =
        error("This management operation is not used by the fixture")
}

private object UnusedManageRequestRemote : ManageRequestRemote {
    override suspend fun requests(observation: CallObservation): List<AccessRequest> =
        error("This management operation is not used by the fixture")
    override suspend fun approveRequest(id: Long, role: ClassRole?, observation: CallObservation): RequestDecision =
        error("This management operation is not used by the fixture")
    override suspend fun declineRequest(id: Long, observation: CallObservation): RequestDecision =
        error("This management operation is not used by the fixture")
}

private object UnusedManageReportRemote : ManageReportRemote {
    override suspend fun log(pageSize: Int, pageToken: String, observation: CallObservation): AuditPage =
        error("This management operation is not used by the fixture")
    override suspend fun stats(observation: CallObservation): ClassStats =
        error("This management operation is not used by the fixture")
}
