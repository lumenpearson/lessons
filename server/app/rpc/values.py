"""Model ↔ message conversions every handler shares.

One place for the conventions ``common.proto`` states, so that two handlers
cannot write a date, a time or a role two ways: a date is ``"YYYY-MM-DD"``, a
time of day ``"HH:MM"`` (v1 wrote seconds; v2 does not), an instant a
``Timestamp``, and an enum is matched to the model's by its member name —
``DayKind.SELF_STUDY`` is ``DAY_KIND_SELF_STUDY`` — so a value added to one
and not the other is a ``KeyError`` in a test rather than a silent default.
"""

from __future__ import annotations

from datetime import UTC, datetime
from datetime import date as Date
from datetime import time as Time

from protobuf import Enum
from protobuf.wkt import Timestamp

from app.contract.lessons.v2 import common_pb, options_pb, schedule_pb
from app.models import Role, TermKind
from app.schedule import ResolvedDay
from app.services.linking import Access
from app.services.terms import TermSpan


def proto_name(member: Enum) -> str:
    """The value's name as the proto writes it — ``"ROLE_ADMIN"``, not the
    generated member's ``ADMIN`` — which is what metadata and JSON carry."""
    return next(value.name for value in type(member).desc().values if value.number == member.value)


def date_string(day: Date) -> str:
    return day.isoformat()


def time_string(clock: Time) -> str:
    return clock.strftime("%H:%M")


def instant(moment: datetime) -> Timestamp:
    """A ``Timestamp`` for ``moment``. A naive datetime is UTC, as every naive
    ``DateTime`` column of the model is."""
    if moment.tzinfo is None:
        moment = moment.replace(tzinfo=UTC)
    return Timestamp.from_datetime(moment)


def role(value: Role | None) -> options_pb.Role:
    return options_pb.Role[value.name] if value is not None else options_pb.Role.UNSPECIFIED


def term_kind(value: TermKind) -> common_pb.TermKind:
    return common_pb.TermKind[value.name]


def term(span: TermSpan) -> common_pb.Term:
    return common_pb.Term(
        index=span.index,
        kind=term_kind(span.kind),
        starts_on=date_string(span.starts_on),
        ends_on=date_string(span.ends_on),
    )


def device_access(access: Access) -> schedule_pb.DeviceAccess:
    return schedule_pb.DeviceAccess(
        linked=access.linked, role=role(access.role), can_edit=access.can_edit
    )


def day(resolved: ResolvedDay) -> schedule_pb.ScheduleDay:
    """One resolved date as ``ScheduleDay``: v1's ``DayOut``, field for field."""
    return schedule_pb.ScheduleDay(
        date=date_string(resolved.date),
        weekday=resolved.weekday,
        kind=common_pb.DayKind[resolved.kind.name],
        note=resolved.note,
        holiday=schedule_pb.Holiday(
            code=resolved.holiday.code,
            title=resolved.holiday.title,
            stops_lessons=resolved.holiday.stops_lessons,
        )
        if resolved.holiday is not None
        else None,
        off_reason=schedule_pb.DayOffReason[resolved.off_reason.name]
        if resolved.off_reason is not None
        else schedule_pb.DayOffReason.UNSPECIFIED,
        lessons=[
            schedule_pb.Lesson(
                index=lesson.index,
                subject=lesson.subject,
                starts_at=time_string(lesson.starts_at),
                ends_at=time_string(lesson.ends_at),
                room=lesson.room,
                teacher=lesson.teacher,
                color=lesson.color,
                is_replaced=lesson.is_replaced,
                is_cancelled=lesson.is_cancelled,
                note=lesson.note,
            )
            for lesson in resolved.lessons
        ],
        events=[
            schedule_pb.ScheduleEvent(
                title=event.title,
                kind=common_pb.EventKind[event.kind.name],
                starts_at=time_string(event.starts_at),
                ends_at=time_string(event.ends_at),
                location=event.location,
                covers_lesson=event.covers_lesson,
            )
            for event in resolved.events
        ],
        homework=[
            schedule_pb.ScheduleHomework(
                subject=item.subject, text=item.text, attachment_url=item.attachment_url
            )
            for item in resolved.homework
        ],
    )


def now() -> datetime:
    """The instant an answer is made, for ``generated_at``. A function, so a
    test can pin it."""
    return datetime.now(UTC)
