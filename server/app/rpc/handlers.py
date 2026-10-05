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

from app.rpc import audit, class_device, device, diary, me, schedule, subject, watch

#: A handler: ``async def handler(call: Call, request: <Method>Request) -> <Method>Response``.
Handler = Callable[[Any, Any], Awaitable[Any]]

#: Keyed as ``rpc.methods.METHODS`` is: ``"lessons.v2.<Service>/<Method>"``.
HANDLERS: dict[str, Handler] = {
    "lessons.v2.AuditService/ListAuditEntries": audit.list_audit_entries,
    "lessons.v2.ClassDeviceService/ListClassDevices": class_device.list_class_devices,
    "lessons.v2.ClassDeviceService/RevokeClassDevice": class_device.revoke_class_device,
    "lessons.v2.ClassDeviceService/UnlinkClassDevice": class_device.unlink_class_device,
    "lessons.v2.DeviceService/CreateDevice": device.create_device,
    "lessons.v2.DiaryService/GetDiaryCapabilities": diary.get_diary_capabilities,
    "lessons.v2.MeService/GetMe": me.get_me,
    "lessons.v2.ScheduleService/GetScheduleWindow": schedule.get_schedule_window,
    "lessons.v2.SubjectService/CreateSubject": subject.create_subject,
    "lessons.v2.SubjectService/DeleteSubject": subject.delete_subject,
    "lessons.v2.SubjectService/GetSubject": subject.get_subject,
    "lessons.v2.SubjectService/ListSubjects": subject.list_subjects,
    "lessons.v2.SubjectService/UpdateSubject": subject.update_subject,
    "lessons.v2.WatchService/WatchClass": watch.watch_class,
}
