"""The dates a v2 request names: read as v1 reads them, bounded as ``services/clock`` bounds them.

Lists answer a window of the class's days — ``ListHomework`` and
``ListEvents``, and from 3b-6 ``ListSubstitutions`` — or of a pupil's diary —
``ListScheduleDays``, ``ListDiaryHomework`` and ``ListMarks``, from 3b-7 —
and writes name a day.
Written once here, so that no two methods read a date two ways: each is
parsed by pydantic, as FastAPI parsed v1's, and refused naming its field and
never its value (``rpc/errors.validate``).
"""

from __future__ import annotations

from datetime import date as Date

from protobuf import Message

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.rpc.errors import Refusal, validate
from app.schemas import DateIn, DateWindowIn
from app.services import clock


def asked(request: Message) -> tuple[Date | None, Date | None]:
    """The ``start_date`` and ``end_date`` a list request names, ``None`` where
    unset: ``"YYYY-MM-DD"``, parsed as v1's ``from`` and ``to`` were, or
    ``VALIDATION_FAILED`` on the field that does not parse."""
    sent = {
        name: getattr(request, name) if request.has_field(name) else None
        for name in ("start_date", "end_date")
    }
    form = validate(DateWindowIn, sent)
    return form.start_date, form.end_date


def window(request: Message, today: Date) -> tuple[Date, Date]:
    """The first and last day a list of the class's days covers, both
    included: what the request names (:func:`asked`), and ``clock.window``
    decides the rest — today and three weeks on when unset, sixty-two days at
    most. A window ``clock`` refuses is ``VALIDATION_FAILED``, worded by the
    error table on the edge at fault. A diary's window is
    ``services/diary.window``'s, from the diary's own today.
    """
    return clock.window(*asked(request), today)


def bounded(day: Date, field: str) -> Date:
    """``day``, a date a write names, or ``VALIDATION_FAILED`` on ``field`` in
    the words of v1's ``_check_date``: the resolver does arithmetic on top of
    a stored date, and near ``date.max`` that overflows."""
    if not clock.in_bounds(day):
        raise Refusal(
            ErrorReason.VALIDATION_FAILED,
            clock.DATE_OUT_OF_BOUNDS,
            violations=[(field, clock.DATE_OUT_OF_BOUNDS)],
        )
    return day


def named(value: str, at: str = "") -> Date:
    """The date a request names a day by — ``GetDay``'s ``date``, and
    ``UpdateDay``'s ``day.date`` with ``at`` ``"day."`` — parsed as v1's
    ``DayIn`` parses one and held to the bounds a written date is held to,
    or ``VALIDATION_FAILED`` on the field. A read is held to them too: no day
    out of them can carry a mark."""
    form = validate(DateIn, {"date": value}, at=at)
    return bounded(form.date, f"{at}date")
