"""Which methods this deployment serves, and the handler of each.

A method missing here answers ``UNIMPLEMENTED`` on both transports, before
any gate or scope, exactly as the generated ``Protocol``'s default does. 3a
served ``WatchClass``'s refusal, ``GetMe``, ``GetDiaryCapabilities``,
``CreateDevice`` and ``GetScheduleWindow``; 3b fills the rest in, one service
at a time (``docs/specs/2026-10-05-server-v2-3b-plan.md``).

Handler modules import ``Call`` only for their annotations, so that
``call.py``, which imports this table, is never imported back.
"""

from __future__ import annotations

from collections.abc import Awaitable, Callable
from typing import Any

from app.rpc import (
    access_request,
    audit,
    bell,
    class_device,
    device,
    diary,
    directory,
    me,
    schedule,
    school_class,
    subject,
    timetable,
    watch,
)

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.AccessRequestService/ApproveAccessRequest": access_request.approve_access_request,
    "lessons.v2.AccessRequestService/DeclineAccessRequest": access_request.decline_access_request,
    "lessons.v2.AccessRequestService/ListAccessRequests": access_request.list_access_requests,
    "lessons.v2.AuditService/ListAuditEntries": audit.list_audit_entries,
    "lessons.v2.BellService/CreateBellSchedule": bell.create_bell_schedule,
    "lessons.v2.BellService/DeleteBellSchedule": bell.delete_bell_schedule,
    "lessons.v2.BellService/GetBellSchedule": bell.get_bell_schedule,
    "lessons.v2.BellService/ListBellSchedules": bell.list_bell_schedules,
    "lessons.v2.BellService/UpdateBellSchedule": bell.update_bell_schedule,
    "lessons.v2.ClassDeviceService/ListClassDevices": class_device.list_class_devices,
    "lessons.v2.ClassDeviceService/RevokeClassDevice": class_device.revoke_class_device,
    "lessons.v2.ClassDeviceService/UnlinkClassDevice": class_device.unlink_class_device,
    "lessons.v2.ClassService/DeleteClass": school_class.delete_class,
    "lessons.v2.ClassService/GetClass": school_class.get_class,
    "lessons.v2.ClassService/GetClassStats": school_class.get_class_stats,
    "lessons.v2.ClassService/GetTermScheme": school_class.get_term_scheme,
    "lessons.v2.ClassService/ListTerms": school_class.list_terms,
    "lessons.v2.ClassService/UpdateClass": school_class.update_class,
    "lessons.v2.ClassService/UpdateTerm": school_class.update_term,
    "lessons.v2.ClassService/UpdateTermScheme": school_class.update_term_scheme,
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.DirectoryService/ListSchoolRegions": directory.list_school_regions,
    "lessons.v2.DirectoryService/ListSchools": directory.list_schools,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.ScheduleService/GetScheduleWindow": schedule.get_schedule_window,
    "lessons.v2.SubjectService/CreateSubject": subject.create_subject,
    "lessons.v2.SubjectService/DeleteSubject": subject.delete_subject,
    "lessons.v2.SubjectService/GetSubject": subject.get_subject,
    "lessons.v2.SubjectService/ListSubjects": subject.list_subjects,
    "lessons.v2.SubjectService/UpdateSubject": subject.update_subject,
    "lessons.v2.TimetableService/GetTimetable": timetable.get_timetable,
    "lessons.v2.TimetableService/ImportTimetable": timetable.import_timetable,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
