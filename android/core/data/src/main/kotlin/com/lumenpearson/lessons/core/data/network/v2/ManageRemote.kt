package com.lumenpearson.lessons.core.data.network.v2

import com.lumenpearson.lessons.core.data.repository.AccessRequest
import com.lumenpearson.lessons.core.data.repository.AuditPage
import com.lumenpearson.lessons.core.data.repository.BellPeriod
import com.lumenpearson.lessons.core.data.repository.BellSchedule
import com.lumenpearson.lessons.core.data.repository.BellsWritten
import com.lumenpearson.lessons.core.data.repository.ClassEdit
import com.lumenpearson.lessons.core.data.repository.ClassJoinMode
import com.lumenpearson.lessons.core.data.repository.ClassRole
import com.lumenpearson.lessons.core.data.repository.ClassStats
import com.lumenpearson.lessons.core.data.repository.ManagedClass
import com.lumenpearson.lessons.core.data.repository.ManagedDevice
import com.lumenpearson.lessons.core.data.repository.ManagedSubject
import com.lumenpearson.lessons.core.data.repository.RequestDecision
import com.lumenpearson.lessons.core.data.repository.SchoolPage
import com.lumenpearson.lessons.core.data.repository.SubjectForm
import com.lumenpearson.lessons.core.data.repository.SubjectSaved
import com.lumenpearson.lessons.core.data.repository.TimetableExport
import com.lumenpearson.lessons.core.data.repository.TimetableImport

/** One selected transport; service-sized contracts keep wire details inside each binding. */
internal interface ManageRemote : ManageClassRemote, ManageSubjectRemote, ManageBellRemote,
    ManageTimetableRemote, ManageDeviceRemote, ManageRequestRemote, ManageReportRemote

internal interface ManageClassRemote {
    suspend fun classCard(observation: CallObservation): ManagedClass
    suspend fun updateClass(before: ManagedClass, edit: ClassEdit, observation: CallObservation): ManagedClass
    suspend fun setJoinMode(mode: ClassJoinMode, observation: CallObservation): ManagedClass
    suspend fun deleteClass(confirmName: String, observation: CallObservation)
    suspend fun searchSchools(query: String, observation: CallObservation): SchoolPage
}

internal interface ManageSubjectRemote {
    suspend fun subjects(observation: CallObservation): List<ManagedSubject>
    suspend fun createSubject(form: SubjectForm, observation: CallObservation): SubjectSaved
    suspend fun updateSubject(before: ManagedSubject, form: SubjectForm, observation: CallObservation): SubjectSaved
    suspend fun deleteSubject(id: Long, observation: CallObservation)
}

internal interface ManageBellRemote {
    suspend fun bells(observation: CallObservation): List<BellSchedule>
    suspend fun createBellSchedule(name: String, periods: List<BellPeriod>, observation: CallObservation): BellSchedule
    suspend fun renameBellSchedule(id: Long, name: String, observation: CallObservation): BellSchedule
    suspend fun makeBellScheduleDefault(id: Long, observation: CallObservation): BellsWritten
    suspend fun writeBellPeriods(id: Long, periods: List<BellPeriod>, observation: CallObservation): BellsWritten
    suspend fun deleteBellSchedule(id: Long, observation: CallObservation)
}

internal interface ManageTimetableRemote {
    suspend fun timetable(observation: CallObservation): TimetableExport
    suspend fun importTimetable(text: String, replace: Boolean, observation: CallObservation): TimetableImport
}

internal interface ManageDeviceRemote {
    suspend fun devices(includeRevoked: Boolean, observation: CallObservation): List<ManagedDevice>
    suspend fun revokeDevice(id: Long, observation: CallObservation): ManagedDevice
    suspend fun unlinkDevice(id: Long, observation: CallObservation): ManagedDevice
}

internal interface ManageRequestRemote {
    suspend fun requests(observation: CallObservation): List<AccessRequest>
    suspend fun approveRequest(id: Long, role: ClassRole?, observation: CallObservation): RequestDecision
    suspend fun declineRequest(id: Long, observation: CallObservation): RequestDecision
}

internal interface ManageReportRemote {
    suspend fun log(pageSize: Int, pageToken: String, observation: CallObservation): AuditPage
    suspend fun stats(observation: CallObservation): ClassStats
}
