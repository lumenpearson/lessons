"""What an admin reads about the class rather than changes: the log and the stats.

Part of the management surface; what holds across it is in the package
docstring.
"""

from __future__ import annotations

from datetime import date as Date
from datetime import datetime

from pydantic import BaseModel

from app.schemas.bundle import RoleName


class AuditEntryOut(BaseModel):
    id: int
    # Machine tag: homework.add, subject.rename, timetable.import, ...
    action: str
    summary: str
    who: str | None = None
    at: datetime | None = None


class AuditPageOut(BaseModel):
    """``has_more`` rather than a total: the log is append-only and unbounded,
    and a count of it would be a full scan on every page turn."""

    entries: list[AuditEntryOut] = []
    limit: int
    offset: int
    has_more: bool = False


class SubjectHoursOut(BaseModel):
    """Lessons a week. A subject that alternates weeks counts a half, which is
    how a school's own paperwork writes «часов в неделю»."""

    name: str
    hours: float


class StatsOut(BaseModel):
    today: Date
    lessons_per_week: float
    subjects_count: int
    subjects: list[SubjectHoursOut] = []
    homework_open: int
    homework_total: int
    members_by_role: dict[RoleName, int] = {}
    devices_active: int
    overrides_upcoming: int
    events_upcoming: int
