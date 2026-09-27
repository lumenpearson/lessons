"""``/timetable``: the weekly template as text, «📤 Экспорт» and «📥 Импорт».

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass
from app.schemas import ImportConflictOut, TimetableExportOut, TimetableImportIn, TimetableImportOut
from app.services import structure, timetable_io
from app.services.manage import timetable as timetable_service
from app.wording import WEEKDAYS

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
    """
    days, rejected = timetable_io.parse_timetable_block(payload.text)
    bells, _rejected_bells = timetable_io.parse_bells_block(payload.text)
    if not days and not bells:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="no weekday header and no bells block found in the text",
        )

    existing = await structure.lessons_per_weekday(session, school_class.id, list(days))
    conflicts = [
        ImportConflictOut(weekday=weekday, existing=count, incoming=len(days[weekday]))
        for weekday, count in sorted(existing.items())
        if count
    ]
    if conflicts and not payload.replace:
        return TimetableImportOut(
            applied=False,
            days=sorted(days),
            lessons=sum(len(rows) for rows in days.values()),
            bells=len(bells),
            conflicts=conflicts,
            rejected=rejected,
        )

    result = await timetable_service.apply(session, school_class, actor.telegram_id, days, bells)
    total = result.written
    schedule = result.schedule
    # Reported, not silently dropped: a lesson past the last bell has nowhere
    # to be drawn, and «applied: true, lessons: N» with N short of what was
    # pasted is exactly the answer that hides it. One line per dropped row,
    # named by its weekday: the same number under two weekdays is two lessons
    # gone, and while this counted the distinct numbers it admitted to one.
    rejected = rejected + [
        f"{WEEKDAYS[weekday - 1]}, урок {index}: "
        "нет такого звонка в расписании звонков"
        for weekday, index in result.dropped
    ]
    # The other direction, and the one nothing used to report: these lessons
    # were already stored and the bells this import wrote no longer ring them,
    # so they are still in the database and drawn nowhere.
    rejected = rejected + [
        f"{WEEKDAYS[weekday - 1]}, урок {index}: "
        "больше не звонит — новые звонки короче"
        for weekday, index in result.orphaned
    ]
    await session.commit()
    if schedule is not None:
        await session.refresh(schedule, ["periods"])

    return TimetableImportOut(
        applied=True,
        days=sorted(days),
        lessons=total,
        bells=len(bells),
        conflicts=conflicts,
        rejected=rejected,
    )
