"""``/timetable``: the weekly template as text, «📤 Экспорт» and «📥 Импорт».

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring. The import's rules - the parse, the
conflicts, and a line for each lesson dropped or silenced - are
``services/manage/timetable.import_paste``, which v2's ``ImportTimetable``
calls too.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app import wording
from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass
from app.schemas import ImportConflictOut, TimetableExportOut, TimetableImportIn, TimetableImportOut
from app.services.manage import timetable as timetable_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


# --------------------------------------------------------------------------
# 📤 Export and 📥 import
# --------------------------------------------------------------------------


@router.get("/timetable", response_model=TimetableExportOut)
async def timetable_export(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TimetableExportOut:
    """The weekly template as the text «📤 Экспорт» sends.

    The same format in both directions, which is what makes it a backup: a
    text saved out of the bot goes back in through the app, and the other way
    round. An empty timetable is an empty string, not a 404 - there is nothing
    wrong with a class that has not filled one in yet.
    """
    text, lessons = await timetable_service.export(session, school_class)
    return TimetableExportOut(text=text, lessons=lessons)


@router.post("/timetable/import", response_model=TimetableImportOut)
async def timetable_import(
    payload: TimetableImportIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TimetableImportOut:
    """Parse a paste and replace exactly the weekdays it names.

    The bot shows a preview and asks «Применить»; an API has no screen to show
    one on, so the preview is the refusal: if a weekday in the paste already
    has lessons, nothing is written and the response lists what stands to be
    overwritten. Sending it again with ``replace: true`` is the second tap.

    Days the paste does not mention are left alone, so importing one day's
    block is a legitimate thing to do, and a day named with nothing under it is
    emptied - that is how a paste says «в четверг уроков нет». A
    ``== Звонки ==`` block replaces the default schedule's rows outright, as it
    does in the bot; it is a schedule, not a day, and nothing else points at it.
    The rules are ``services/manage/timetable.import_paste``.
    """
    try:
        outcome = await timetable_service.import_paste(
            session, school_class, actor.telegram_id, payload.text, replace=payload.replace
        )
    except timetable_service.PasteEmpty as empty:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail=wording.TIMETABLE_PASTE_EMPTY_DETAIL,
        ) from empty
    if outcome.applied:
        await session.commit()
        if outcome.schedule is not None:
            await session.refresh(outcome.schedule, ["periods"])

    return TimetableImportOut(
        applied=outcome.applied,
        days=outcome.days,
        lessons=outcome.lessons,
        bells=outcome.bells,
        conflicts=[
            ImportConflictOut(
                weekday=conflict.weekday, existing=conflict.existing, incoming=conflict.incoming
            )
            for conflict in outcome.conflicts
        ],
        rejected=outcome.rejected,
    )
