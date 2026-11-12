package com.lumenpearson.lessons.core.data.network.v2.dto

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class CanonicalDto<T>(private val serializer: KSerializer<T>) {
    fun canonical(json: Json, value: JsonElement): JsonElement =
        json.encodeToJsonElement(serializer, json.decodeFromJsonElement(serializer, value))
}

internal data class CanonicalMethod(val request: CanonicalDto<*>, val response: CanonicalDto<*>)

internal object CanonicalMethods {
    val unary: Map<String, CanonicalMethod> = mapOf(
        "lessons.v2.AccessRequestService/ApproveAccessRequest" to CanonicalMethod(
            CanonicalDto(ApproveAccessRequestRequestDto.serializer()),
            CanonicalDto(ApproveAccessRequestResponseDto.serializer()),
        ),
        "lessons.v2.AccessRequestService/DeclineAccessRequest" to CanonicalMethod(
            CanonicalDto(DeclineAccessRequestRequestDto.serializer()),
            CanonicalDto(DeclineAccessRequestResponseDto.serializer()),
        ),
        "lessons.v2.AccessRequestService/ListAccessRequests" to CanonicalMethod(
            CanonicalDto(ListAccessRequestsRequestDto.serializer()),
            CanonicalDto(ListAccessRequestsResponseDto.serializer()),
        ),
        "lessons.v2.AuditService/ListAuditEntries" to CanonicalMethod(
            CanonicalDto(ListAuditEntriesRequestDto.serializer()),
            CanonicalDto(ListAuditEntriesResponseDto.serializer()),
        ),
        "lessons.v2.BellService/CreateBellSchedule" to CanonicalMethod(
            CanonicalDto(CreateBellScheduleRequestDto.serializer()),
            CanonicalDto(CreateBellScheduleResponseDto.serializer()),
        ),
        "lessons.v2.BellService/DeleteBellSchedule" to CanonicalMethod(
            CanonicalDto(DeleteBellScheduleRequestDto.serializer()),
            CanonicalDto(DeleteBellScheduleResponseDto.serializer()),
        ),
        "lessons.v2.BellService/GetBellSchedule" to CanonicalMethod(
            CanonicalDto(GetBellScheduleRequestDto.serializer()),
            CanonicalDto(GetBellScheduleResponseDto.serializer()),
        ),
        "lessons.v2.BellService/ListBellSchedules" to CanonicalMethod(
            CanonicalDto(ListBellSchedulesRequestDto.serializer()),
            CanonicalDto(ListBellSchedulesResponseDto.serializer()),
        ),
        "lessons.v2.BellService/UpdateBellSchedule" to CanonicalMethod(
            CanonicalDto(UpdateBellScheduleRequestDto.serializer()),
            CanonicalDto(UpdateBellScheduleResponseDto.serializer()),
        ),
        "lessons.v2.ClassDeviceService/ListClassDevices" to CanonicalMethod(
            CanonicalDto(ListClassDevicesRequestDto.serializer()),
            CanonicalDto(ListClassDevicesResponseDto.serializer()),
        ),
        "lessons.v2.ClassDeviceService/RevokeClassDevice" to CanonicalMethod(
            CanonicalDto(RevokeClassDeviceRequestDto.serializer()),
            CanonicalDto(RevokeClassDeviceResponseDto.serializer()),
        ),
        "lessons.v2.ClassDeviceService/UnlinkClassDevice" to CanonicalMethod(
            CanonicalDto(UnlinkClassDeviceRequestDto.serializer()),
            CanonicalDto(UnlinkClassDeviceResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/DeleteClass" to CanonicalMethod(
            CanonicalDto(DeleteClassRequestDto.serializer()),
            CanonicalDto(DeleteClassResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/GetClass" to CanonicalMethod(
            CanonicalDto(GetClassRequestDto.serializer()),
            CanonicalDto(GetClassResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/GetClassStats" to CanonicalMethod(
            CanonicalDto(GetClassStatsRequestDto.serializer()),
            CanonicalDto(GetClassStatsResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/GetTermScheme" to CanonicalMethod(
            CanonicalDto(GetTermSchemeRequestDto.serializer()),
            CanonicalDto(GetTermSchemeResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/ListTerms" to CanonicalMethod(
            CanonicalDto(ListTermsRequestDto.serializer()),
            CanonicalDto(ListTermsResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/UpdateClass" to CanonicalMethod(
            CanonicalDto(UpdateClassRequestDto.serializer()),
            CanonicalDto(UpdateClassResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/UpdateTerm" to CanonicalMethod(
            CanonicalDto(UpdateTermRequestDto.serializer()),
            CanonicalDto(UpdateTermResponseDto.serializer()),
        ),
        "lessons.v2.ClassService/UpdateTermScheme" to CanonicalMethod(
            CanonicalDto(UpdateTermSchemeRequestDto.serializer()),
            CanonicalDto(UpdateTermSchemeResponseDto.serializer()),
        ),
        "lessons.v2.DayService/GetDay" to CanonicalMethod(
            CanonicalDto(GetDayRequestDto.serializer()),
            CanonicalDto(GetDayResponseDto.serializer()),
        ),
        "lessons.v2.DayService/UpdateDay" to CanonicalMethod(
            CanonicalDto(UpdateDayRequestDto.serializer()),
            CanonicalDto(UpdateDayResponseDto.serializer()),
        ),
        "lessons.v2.DeviceService/CreateDevice" to CanonicalMethod(
            CanonicalDto(CreateDeviceRequestDto.serializer()),
            CanonicalDto(CreateDeviceResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/BatchUpdateCorrections" to CanonicalMethod(
            CanonicalDto(BatchUpdateCorrectionsRequestDto.serializer()),
            CanonicalDto(BatchUpdateCorrectionsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ClearCorrections" to CanonicalMethod(
            CanonicalDto(ClearCorrectionsRequestDto.serializer()),
            CanonicalDto(ClearCorrectionsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/CreateDiarySession" to CanonicalMethod(
            CanonicalDto(CreateDiarySessionRequestDto.serializer()),
            CanonicalDto(CreateDiarySessionResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/DeleteDiarySession" to CanonicalMethod(
            CanonicalDto(DeleteDiarySessionRequestDto.serializer()),
            CanonicalDto(DeleteDiarySessionResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/GetDiaryCapabilities" to CanonicalMethod(
            CanonicalDto(GetDiaryCapabilitiesRequestDto.serializer()),
            CanonicalDto(GetDiaryCapabilitiesResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListCorrections" to CanonicalMethod(
            CanonicalDto(ListCorrectionsRequestDto.serializer()),
            CanonicalDto(ListCorrectionsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListDiaryHomework" to CanonicalMethod(
            CanonicalDto(ListDiaryHomeworkRequestDto.serializer()),
            CanonicalDto(ListDiaryHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListDiarySubjects" to CanonicalMethod(
            CanonicalDto(ListDiarySubjectsRequestDto.serializer()),
            CanonicalDto(ListDiarySubjectsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListMarks" to CanonicalMethod(
            CanonicalDto(ListMarksRequestDto.serializer()),
            CanonicalDto(ListMarksResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListPeriods" to CanonicalMethod(
            CanonicalDto(ListPeriodsRequestDto.serializer()),
            CanonicalDto(ListPeriodsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListScheduleDays" to CanonicalMethod(
            CanonicalDto(ListScheduleDaysRequestDto.serializer()),
            CanonicalDto(ListScheduleDaysResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListStudents" to CanonicalMethod(
            CanonicalDto(ListStudentsRequestDto.serializer()),
            CanonicalDto(ListStudentsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListTeachers" to CanonicalMethod(
            CanonicalDto(ListTeachersRequestDto.serializer()),
            CanonicalDto(ListTeachersResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ListTurnstileEvents" to CanonicalMethod(
            CanonicalDto(ListTurnstileEventsRequestDto.serializer()),
            CanonicalDto(ListTurnstileEventsResponseDto.serializer()),
        ),
        "lessons.v2.DiaryService/ResetCorrections" to CanonicalMethod(
            CanonicalDto(ResetCorrectionsRequestDto.serializer()),
            CanonicalDto(ResetCorrectionsResponseDto.serializer()),
        ),
        "lessons.v2.DirectoryService/ListSchoolRegions" to CanonicalMethod(
            CanonicalDto(ListSchoolRegionsRequestDto.serializer()),
            CanonicalDto(ListSchoolRegionsResponseDto.serializer()),
        ),
        "lessons.v2.DirectoryService/ListSchools" to CanonicalMethod(
            CanonicalDto(ListSchoolsRequestDto.serializer()),
            CanonicalDto(ListSchoolsResponseDto.serializer()),
        ),
        "lessons.v2.EventService/CreateEvent" to CanonicalMethod(
            CanonicalDto(CreateEventRequestDto.serializer()),
            CanonicalDto(CreateEventResponseDto.serializer()),
        ),
        "lessons.v2.EventService/DeleteEvent" to CanonicalMethod(
            CanonicalDto(DeleteEventRequestDto.serializer()),
            CanonicalDto(DeleteEventResponseDto.serializer()),
        ),
        "lessons.v2.EventService/GetEvent" to CanonicalMethod(
            CanonicalDto(GetEventRequestDto.serializer()),
            CanonicalDto(GetEventResponseDto.serializer()),
        ),
        "lessons.v2.EventService/ListEvents" to CanonicalMethod(
            CanonicalDto(ListEventsRequestDto.serializer()),
            CanonicalDto(ListEventsResponseDto.serializer()),
        ),
        "lessons.v2.EventService/UpdateEvent" to CanonicalMethod(
            CanonicalDto(UpdateEventRequestDto.serializer()),
            CanonicalDto(UpdateEventResponseDto.serializer()),
        ),
        "lessons.v2.HomeworkService/CreateHomework" to CanonicalMethod(
            CanonicalDto(CreateHomeworkRequestDto.serializer()),
            CanonicalDto(CreateHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.HomeworkService/DeleteHomework" to CanonicalMethod(
            CanonicalDto(DeleteHomeworkRequestDto.serializer()),
            CanonicalDto(DeleteHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.HomeworkService/GetHomework" to CanonicalMethod(
            CanonicalDto(GetHomeworkRequestDto.serializer()),
            CanonicalDto(GetHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.HomeworkService/ListHomework" to CanonicalMethod(
            CanonicalDto(ListHomeworkRequestDto.serializer()),
            CanonicalDto(ListHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.HomeworkService/UpdateHomework" to CanonicalMethod(
            CanonicalDto(UpdateHomeworkRequestDto.serializer()),
            CanonicalDto(UpdateHomeworkResponseDto.serializer()),
        ),
        "lessons.v2.MeService/CreateCalendarFeed" to CanonicalMethod(
            CanonicalDto(CreateCalendarFeedRequestDto.serializer()),
            CanonicalDto(CreateCalendarFeedResponseDto.serializer()),
        ),
        "lessons.v2.MeService/CreateHomeworkTick" to CanonicalMethod(
            CanonicalDto(CreateHomeworkTickRequestDto.serializer()),
            CanonicalDto(CreateHomeworkTickResponseDto.serializer()),
        ),
        "lessons.v2.MeService/CreateLinkCode" to CanonicalMethod(
            CanonicalDto(CreateLinkCodeRequestDto.serializer()),
            CanonicalDto(CreateLinkCodeResponseDto.serializer()),
        ),
        "lessons.v2.MeService/CreateTask" to CanonicalMethod(
            CanonicalDto(CreateTaskRequestDto.serializer()),
            CanonicalDto(CreateTaskResponseDto.serializer()),
        ),
        "lessons.v2.MeService/DeleteHomeworkTick" to CanonicalMethod(
            CanonicalDto(DeleteHomeworkTickRequestDto.serializer()),
            CanonicalDto(DeleteHomeworkTickResponseDto.serializer()),
        ),
        "lessons.v2.MeService/DeleteTask" to CanonicalMethod(
            CanonicalDto(DeleteTaskRequestDto.serializer()),
            CanonicalDto(DeleteTaskResponseDto.serializer()),
        ),
        "lessons.v2.MeService/GetCalendarFeed" to CanonicalMethod(
            CanonicalDto(GetCalendarFeedRequestDto.serializer()),
            CanonicalDto(GetCalendarFeedResponseDto.serializer()),
        ),
        "lessons.v2.MeService/GetMe" to CanonicalMethod(
            CanonicalDto(GetMeRequestDto.serializer()),
            CanonicalDto(GetMeResponseDto.serializer()),
        ),
        "lessons.v2.MeService/GetTask" to CanonicalMethod(
            CanonicalDto(GetTaskRequestDto.serializer()),
            CanonicalDto(GetTaskResponseDto.serializer()),
        ),
        "lessons.v2.MeService/ListTasks" to CanonicalMethod(
            CanonicalDto(ListTasksRequestDto.serializer()),
            CanonicalDto(ListTasksResponseDto.serializer()),
        ),
        "lessons.v2.MeService/UnlinkMe" to CanonicalMethod(
            CanonicalDto(UnlinkMeRequestDto.serializer()),
            CanonicalDto(UnlinkMeResponseDto.serializer()),
        ),
        "lessons.v2.MeService/UpdateTask" to CanonicalMethod(
            CanonicalDto(UpdateTaskRequestDto.serializer()),
            CanonicalDto(UpdateTaskResponseDto.serializer()),
        ),
        "lessons.v2.ScheduleService/GetScheduleWindow" to CanonicalMethod(
            CanonicalDto(GetScheduleWindowRequestDto.serializer()),
            CanonicalDto(GetScheduleWindowResponseDto.serializer()),
        ),
        "lessons.v2.SubjectService/CreateSubject" to CanonicalMethod(
            CanonicalDto(CreateSubjectRequestDto.serializer()),
            CanonicalDto(CreateSubjectResponseDto.serializer()),
        ),
        "lessons.v2.SubjectService/DeleteSubject" to CanonicalMethod(
            CanonicalDto(DeleteSubjectRequestDto.serializer()),
            CanonicalDto(DeleteSubjectResponseDto.serializer()),
        ),
        "lessons.v2.SubjectService/GetSubject" to CanonicalMethod(
            CanonicalDto(GetSubjectRequestDto.serializer()),
            CanonicalDto(GetSubjectResponseDto.serializer()),
        ),
        "lessons.v2.SubjectService/ListSubjects" to CanonicalMethod(
            CanonicalDto(ListSubjectsRequestDto.serializer()),
            CanonicalDto(ListSubjectsResponseDto.serializer()),
        ),
        "lessons.v2.SubjectService/UpdateSubject" to CanonicalMethod(
            CanonicalDto(UpdateSubjectRequestDto.serializer()),
            CanonicalDto(UpdateSubjectResponseDto.serializer()),
        ),
        "lessons.v2.SubstitutionService/CreateSubstitution" to CanonicalMethod(
            CanonicalDto(CreateSubstitutionRequestDto.serializer()),
            CanonicalDto(CreateSubstitutionResponseDto.serializer()),
        ),
        "lessons.v2.SubstitutionService/DeleteSubstitution" to CanonicalMethod(
            CanonicalDto(DeleteSubstitutionRequestDto.serializer()),
            CanonicalDto(DeleteSubstitutionResponseDto.serializer()),
        ),
        "lessons.v2.SubstitutionService/GetSubstitution" to CanonicalMethod(
            CanonicalDto(GetSubstitutionRequestDto.serializer()),
            CanonicalDto(GetSubstitutionResponseDto.serializer()),
        ),
        "lessons.v2.SubstitutionService/ListSubstitutions" to CanonicalMethod(
            CanonicalDto(ListSubstitutionsRequestDto.serializer()),
            CanonicalDto(ListSubstitutionsResponseDto.serializer()),
        ),
        "lessons.v2.SubstitutionService/UpdateSubstitution" to CanonicalMethod(
            CanonicalDto(UpdateSubstitutionRequestDto.serializer()),
            CanonicalDto(UpdateSubstitutionResponseDto.serializer()),
        ),
        "lessons.v2.TimetableService/GetTimetable" to CanonicalMethod(
            CanonicalDto(GetTimetableRequestDto.serializer()),
            CanonicalDto(GetTimetableResponseDto.serializer()),
        ),
        "lessons.v2.TimetableService/ImportTimetable" to CanonicalMethod(
            CanonicalDto(ImportTimetableRequestDto.serializer()),
            CanonicalDto(ImportTimetableResponseDto.serializer()),
        ),
    )
    val credentials: Map<String, String> = MethodCredentials.all
    val streaming: Set<String> = setOf("lessons.v2.WatchService/WatchClass")
}
