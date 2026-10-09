"""Wire format shared with the Android client.

Field names are the contract. Anything renamed here must be renamed in
``core/network`` on the Android side and bumped in ``API_VERSION``.

One module per area, because in one file a change to one entity meant reading
all the others (#208). Every public name is re-exported from here, so
``from app.schemas import X`` is still the one way in, and no caller had to
learn where a class went:

- ``bundle`` — what the widget reads: the bundle, its days, lessons, events
  and terms, ``/now`` and the calendar feed; ``API_VERSION`` and ``RoleName``
- ``join`` — getting a phone into a class and linking it to an account
- ``homework`` and ``tasks`` — the class's homework and a person's own tasks
- ``subjects`` — the subject dictionary, read by every phone, managed by an
  admin
- ``edit`` — substitutions, events and marked days, written from the phone
- ``cron`` — what one tick of the clock did
- ``diary_session`` and ``diary`` — signing in to the electronic diary, and
  what it reads back with the corrections laid over it
- ``school_class``, ``bells``, ``timetable``, ``access`` and ``reports`` —
  the management surface, below
- ``directory`` — the school directory
- ``_text`` — the cleaning every free-text field goes through

Managing the class
------------------

The phone's half of the bot's /class, /subjects, /bells, /devices, /log,
/stats, /export and /import. Nothing there carries the caller's own role or
class: both are derived server-side from the bearer token and the Telegram
account it is linked to, so what the client believes about itself has never
been part of this contract.

Timestamps on that surface are class wall time, like every other clock the
app is given - the audit log is read by a person sitting in the class's own
zone, not by one sitting next to the server.
"""

from __future__ import annotations

# Private, and re-exported only because tests/test_hardening.py reads it from
# here; the redundant alias is what marks it as meant.
from app.schemas._text import _clean_optional_text as _clean_optional_text
from app.schemas.access import (
    AccessRequestOut,
    ManagedDeviceOut,
    RequestDecisionIn,
    RequestDecisionOut,
)
from app.schemas.bells import (
    BellPeriodIn,
    BellPeriodOut,
    BellPeriodsIn,
    BellScheduleIn,
    BellScheduleOut,
    BellSchedulePatch,
)
from app.schemas.bundle import (
    API_VERSION,
    BundleOut,
    CalendarOut,
    ClassOut,
    DayOut,
    DeviceOut,
    EventOut,
    HolidayOut,
    HomeworkOut,
    LessonOut,
    NowOut,
    NowState,
    RoleName,
    TermOut,
)
from app.schemas.cron import (
    TickOut,
)
from app.schemas.diary import (
    DiaryAttendanceOut,
    DiaryEditOut,
    DiaryHomeworkOut,
    DiaryLessonOut,
    DiaryMarkOut,
    DiaryOverrideIn,
    DiaryOverrideOut,
    DiaryPeriodOut,
    DiaryResetIn,
    DiarySubjectOut,
    DiaryTeacherOut,
)
from app.schemas.diary_session import (
    DiaryCapabilitiesOut,
    DiaryLoginIn,
    DiaryLoginOut,
    DiaryProvidersOut,
    DiarySessionBody,
    DiarySessionOut,
    DiaryStudentOut,
    NetSchoolCapabilitiesOut,
    NetSchoolCookiesIn,
    NetSchoolCredentialIn,
    NetSchoolSessionIn,
    PetersburgCapabilitiesOut,
    PetersburgCredentialIn,
    PetersburgSessionIn,
)
from app.schemas.directory import (
    SchoolOut,
    SchoolRegionOut,
    SchoolRegionsOut,
    SchoolSearchOut,
)
from app.schemas.edit import (
    DayIn,
    DayKindName,
    DayOverrideOut,
    DeletedOut,
    EventCreatedOut,
    EventIn,
    EventKindName,
    OverrideActionName,
    OverrideIn,
    OverrideOut,
)
from app.schemas.homework import (
    DateWindowIn,
    DoneIn,
    DoneOut,
    HomeworkIn,
    HomeworkItemOut,
)
from app.schemas.join import (
    DiaryBindingOut,
    JoinRequest,
    JoinResponse,
    MeOut,
    UnlinkOut,
)
from app.schemas.reports import (
    AuditEntryOut,
    AuditPageOut,
    StatsOut,
    SubjectHoursOut,
)
from app.schemas.school_class import (
    ClassDeleteIn,
    ClassPatch,
    ManagedClassOut,
    TermBoundsIn,
    TermSchemeIn,
    TermsOut,
)
from app.schemas.subjects import (
    ManagedSubjectOut,
    SubjectIn,
    SubjectOut,
    SubjectPatch,
    SubjectSavedOut,
)
from app.schemas.tasks import (
    TaskIn,
    TaskOut,
    TaskPatch,
)
from app.schemas.timetable import (
    ImportConflictOut,
    TimetableExportOut,
    TimetableImportIn,
    TimetableImportOut,
)

__all__ = [
    "API_VERSION",
    "AccessRequestOut",
    "AuditEntryOut",
    "AuditPageOut",
    "BellPeriodIn",
    "BellPeriodOut",
    "BellPeriodsIn",
    "BellScheduleIn",
    "BellScheduleOut",
    "BellSchedulePatch",
    "BundleOut",
    "CalendarOut",
    "ClassDeleteIn",
    "ClassOut",
    "ClassPatch",
    "DateWindowIn",
    "DayIn",
    "DayKindName",
    "DayOut",
    "DayOverrideOut",
    "DeletedOut",
    "DeviceOut",
    "DiaryAttendanceOut",
    "DiaryBindingOut",
    "DiaryCapabilitiesOut",
    "DiaryEditOut",
    "DiaryHomeworkOut",
    "DiaryLessonOut",
    "DiaryLoginIn",
    "DiaryLoginOut",
    "DiaryMarkOut",
    "DiaryOverrideIn",
    "DiaryOverrideOut",
    "DiaryPeriodOut",
    "DiaryProvidersOut",
    "DiaryResetIn",
    "DiarySessionBody",
    "DiarySessionOut",
    "DiaryStudentOut",
    "DiarySubjectOut",
    "DiaryTeacherOut",
    "DoneIn",
    "DoneOut",
    "EventCreatedOut",
    "EventIn",
    "EventKindName",
    "EventOut",
    "HolidayOut",
    "HomeworkIn",
    "HomeworkItemOut",
    "HomeworkOut",
    "ImportConflictOut",
    "JoinRequest",
    "JoinResponse",
    "LessonOut",
    "ManagedClassOut",
    "ManagedDeviceOut",
    "ManagedSubjectOut",
    "MeOut",
    "NetSchoolCapabilitiesOut",
    "NetSchoolCookiesIn",
    "NetSchoolCredentialIn",
    "NetSchoolSessionIn",
    "NowOut",
    "NowState",
    "OverrideActionName",
    "OverrideIn",
    "OverrideOut",
    "PetersburgCapabilitiesOut",
    "PetersburgCredentialIn",
    "PetersburgSessionIn",
    "RequestDecisionIn",
    "RequestDecisionOut",
    "RoleName",
    "SchoolOut",
    "SchoolRegionOut",
    "SchoolRegionsOut",
    "SchoolSearchOut",
    "StatsOut",
    "SubjectHoursOut",
    "SubjectIn",
    "SubjectOut",
    "SubjectPatch",
    "SubjectSavedOut",
    "TaskIn",
    "TaskOut",
    "TaskPatch",
    "TermBoundsIn",
    "TermOut",
    "TermSchemeIn",
    "TermsOut",
    "TickOut",
    "TimetableExportOut",
    "TimetableImportIn",
    "TimetableImportOut",
    "UnlinkOut",
]
