"""The dates a v2 request names: read as v1 reads them, bounded as ``services/clock`` bounds them.

Lists answer a window of the class's days — ``ListHomework`` and
``ListEvents``, and from 3b-6 ``ListSubstitutions`` — and writes name a day.
Written once here, so that no two methods read a date two ways: each is
parsed by pydantic, as FastAPI parsed v1's, and refused naming its field and
never its value (``rpc/errors.validate``).
"""

from __future__ import annotations

from datetime import date as Date

from protobuf import Message

from app.rpc.errors import validate
from app.schemas import DateWindowIn
from app.services import clock


def window(request: Message, today: Date) -> tuple[Date, Date]:
    """The first and last day a list request covers, both included.

    Its ``start_date`` and ``end_date`` are ``"YYYY-MM-DD"`` when set, as v1's
    ``from`` and ``to`` were, and ``clock.window`` decides the rest: today and
    three weeks on when unset, sixty-two days at most. A date that does not
    parse is ``VALIDATION_FAILED`` on its field; a window ``clock`` refuses is
    too, worded by the error table on the edge at fault.
    """
    sent = {
        name: getattr(request, name) if request.has_field(name) else None
        for name in ("start_date", "end_date")
    }
    form = validate(DateWindowIn, sent)
    return clock.window(form.start_date, form.end_date, today)
