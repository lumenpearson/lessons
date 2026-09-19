"""Running the class from the phone: the bot's management commands as an API.

Everything here has a twin in ``app.bot.handlers.manage`` - «📚 Предметы»,
«🔔 Звонки», «📱 Устройства», «📜 Журнал», «⚙️ Класс», export, import,
«📊 Статистика» and the access requests - and the twin is the authority. The
same minimum role guards the same operation on both surfaces, the same rows
move when a subject is renamed, and the same line lands in the audit log. An
admin who does something in the app and then opens the bot must find the class
in the state the app said it was in.

Two rules this module never bends:

* **The role is looked up per request from the linked Telegram account.**
  Nothing the client sends about itself is consulted, and there is no second
  permission system for phones: revoking somebody in the bot revokes their
  phone in the same instant (``linking.effective_role``, exactly as
  ``app.api.edit`` does it).
* **Every id is re-scoped by the query that reads it.** A subject, schedule,
  device or request is fetched by ``(id, class_id)``, so an id naming another
  class's row finds nothing rather than editing it.

Days, substitutions, events and homework are *not* here: they are the day-to-day
writes and they already live in ``app.api.edit`` under the editor's role.
"""

from __future__ import annotations

import logging
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from datetime import UTC, datetime
from html import escape
from typing import Any

from dishka.integrations.fastapi import FromDishka, inject
from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import func, select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.deps import current_class, current_device
from app.api.routing import DishkaAnnotatedRoute
from app.bot.render import WEEKDAYS
from app.bot.roles import can_grant
from app.config import get_settings
from app.models import (
    AccessRequest,
    AuditEntry,
    BellSchedule,
    BotUser,
    DayOverride,
    DeviceToken,
    JoinMode,
    Role,
    SchoolClass,
    Subject,
    TermKind,
    TimetableEntry,
)
from app.providers import dadata
from app.schemas import (
    AccessRequestOut,
    AuditEntryOut,
    AuditPageOut,
    BellPeriodOut,
    BellPeriodsIn,
    BellScheduleIn,
    BellScheduleOut,
    BellSchedulePatch,
    ClassDeleteIn,
    ClassPatch,
    DeletedOut,
    ImportConflictOut,
    ManagedClassOut,
    ManagedDeviceOut,
    ManagedSubjectOut,
    RequestDecisionIn,
    RequestDecisionOut,
    SchoolOut,
    SchoolSearchOut,
    StatsOut,
    SubjectHoursOut,
    SubjectIn,
    SubjectPatch,
    SubjectSavedOut,
    TermBoundsIn,
    TermOut,
    TermSchemeIn,
    TermsOut,
    TimetableExportOut,
    TimetableImportIn,
    TimetableImportOut,
)
from app.services import audit, linking, structure, timetable_io
from app.services import schools as schools_service
from app.services import stats as stats_service
from app.services import subjects as subjects_service
from app.services import terms as terms_service
from app.timezones import is_supported, label_for

log = logging.getLogger(__name__)

router = APIRouter(route_class=DishkaAnnotatedRoute, prefix="/api/v1/manage", tags=["manage"])

#: Audit lines per page, matching «📜 Журнал» in the bot.
AUDIT_PAGE = 30


# --------------------------------------------------------------------------
# Who may manage
# --------------------------------------------------------------------------


@dataclass(frozen=True)
class Actor:
    """Who is making the change: the device, the Telegram account behind it,
    and that account's role in the class *right now*."""

    device: DeviceToken
    telegram_id: int
    role: Role


def _role_at_least(minimum: Role) -> Callable[..., Awaitable[Actor]]:
    """A dependency demanding ``minimum`` of the linked account.

    One factory rather than a guard written out in each endpoint, for the
    reason the bot keeps ``_allowed`` in one place: a check that is copied is a
    check that will eventually be copied wrong. The refusals are the two the
    app already knows from ``app.api.edit`` - «привяжите телефон» and «нужна
    роль» - with the role named, because the app shows a different screen for
    each and an admin-only page has to say «admin», not «editor».
    """

    @inject
    async def dependency(
        device: DeviceToken = Depends(current_device),
        *,
        session: FromDishka[AsyncSession],
    ) -> Actor:
        if device.telegram_id is None:
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail="device is not linked"
            )
        role = await linking.effective_role(session, device)
        if role is None or not role.at_least(minimum):
            raise HTTPException(
                status_code=status.HTTP_403_FORBIDDEN, detail=f"{minimum.value} role required"
            )
        return Actor(device=device, telegram_id=device.telegram_id, role=role)

    return dependency


editor_actor = _role_at_least(Role.EDITOR)
admin_actor = _role_at_least(Role.ADMIN)
owner_actor = _role_at_least(Role.OWNER)


# --------------------------------------------------------------------------
# Small shared pieces
# --------------------------------------------------------------------------


def _wall(stamp: datetime | None, school_class: SchoolClass) -> datetime | None:
    """A stored UTC stamp on the class's own wall clock.

    ``created_at`` and friends are naive UTC in the database. An admin in
    Vladivostok reading a Moscow server's log should not see yesterday evening
    against this morning's change, so they are converted here the same way the
    bot's ``render_audit`` converts them.
    """
    if stamp is None:
        return None
    return stamp.replace(tzinfo=UTC).astimezone(school_class.tz).replace(tzinfo=None)


def _person(full_name: str | None, username: str | None, telegram_id: int | None) -> str:
    """The best name we hold for somebody, in the same order the bot picks it.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can actually act on, and this surface is admin-only.
    """
    if username:
        return f"@{username}"
    if full_name:
        return full_name
    return str(telegram_id) if telegram_id is not None else "—"


async def _member_names(session: AsyncSession, class_id: int) -> dict[int, str]:
    """Telegram id -> the name to show. Plain text: this is JSON, not HTML."""
    members = await session.scalars(select(BotUser).where(BotUser.class_id == class_id))
    return {
        member.telegram_id: _person(member.full_name, member.username, member.telegram_id)
        for member in members
    }


async def _count(session: AsyncSession, model: Any, *where: Any) -> int:
    return int(await session.scalar(select(func.count()).select_from(model).where(*where)) or 0)


def _build_bot() -> Any | None:
    """A bot for one send, or ``None`` when the deployment has no token.

    Imported lazily: aiogram costs seconds to import and a management endpoint
    should not pay that on a deployment that has no bot to notify with. One
    per router module, as in ``app.api.edit`` and ``app.api.cron`` - it is the
    seam the tests replace.
    """
    if not get_settings().bot_token:
        return None
    from app.bot.bot import build_bot

    return build_bot()


async def _tell(telegram_id: int, text: str) -> None:
    """Tell one person what was decided. Never fails the request: the decision
    is already committed, and a Telegram outage does not undo it."""
    bot = _build_bot()
    if bot is None:
        return
    try:
        await bot.send_message(telegram_id, text)
    except Exception:  # noqa: BLE001 - see the docstring
        log.warning("could not tell %s about the decision", telegram_id, exc_info=True)
    finally:
        bot_session = getattr(bot, "session", None)
        if bot_session is not None:
            await bot_session.close()


def _conflict(detail: str) -> HTTPException:
    """409, for a request that is well-formed and refused by the class's own
    state: a name already taken, a schedule days still point at."""
    return HTTPException(status_code=status.HTTP_409_CONFLICT, detail=detail)


# --------------------------------------------------------------------------
# ⚙️ The class card
# --------------------------------------------------------------------------


async def _class_out(session: AsyncSession, school_class: SchoolClass) -> ManagedClassOut:
    return ManagedClassOut(
        id=school_class.id,
        name=school_class.name,
        school=school_class.school,
        city=school_class.city,
        timezone=school_class.timezone_name,
        timezone_label=label_for(school_class.timezone_name),
        join_code=school_class.join_code,
        members=await _count(session, BotUser, BotUser.class_id == school_class.id),
        devices=await _count(
            session,
            DeviceToken,
            DeviceToken.class_id == school_class.id,
            DeviceToken.revoked.is_(False),
        ),
        pending_requests=await _count(
            session,
            AccessRequest,
            AccessRequest.class_id == school_class.id,
            AccessRequest.status == "pending",
        ),
        bell_schedule_id=school_class.bell_schedule_id,
        calendar_ready=bool(school_class.calendar_token),
        # `.value`, so the wire says «open». The column stores the member name
        # because that is how SQLAlchemy persists an enum, and the two are not
        # the same string.
        join_mode=school_class.join_mode.value,
    )


@router.get("/class", response_model=ManagedClassOut)
async def class_card(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedClassOut:
    """What «⚙️ Класс» shows, as data."""
    return await _class_out(session, school_class)


@router.patch("/class", response_model=ManagedClassOut)
async def class_update(
    payload: ClassPatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedClassOut:
    """Rename the class, re-home it, move its time zone, or change who may join.

    One audit line per field changed rather than one for the request, because
    that is what the log is read for: «что изменилось», not «кто открыл
    настройки». Changing the zone moves no stored time - a bell rings at 08:30
    whatever the zone says - it changes which instant the class calls «сейчас».
    """
    changes = payload.model_dump(exclude_unset=True)
    zone = changes.pop("timezone", None)
    mode = changes.pop("join_mode", None)
    if zone is not None and not is_supported(zone):
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="unknown timezone",
        )

    for column, value in changes.items():
        setattr(school_class, column, value)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            f"class.{column}",
            f"{column}: {value or 'убрано'}",
        )
    if zone is not None:
        school_class.timezone = zone
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "class.timezone",
            f"часовой пояс: {zone}",
        )
    if mode is not None:
        # Through the enum rather than by the string, because the attribute is
        # read back as one by `_class_out` in this same request - and because
        # the audit line should say what changed rather than echo a wire value.
        school_class.join_mode = JoinMode(mode)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            # The same action name the bot's own toggle writes, so the log
            # reads as one history however the switch was flipped.
            "access.join_mode",
            "вход только по личным приглашениям"
            if school_class.join_mode is JoinMode.INVITE
            else "вход по коду класса снова разрешён",
        )

    await session.commit()
    return await _class_out(session, school_class)


@router.delete("/class", response_model=DeletedOut)
async def class_delete(
    payload: ClassDeleteIn,
    actor: Actor = Depends(owner_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Delete the class and everything hanging off it. Owner only.

    ``confirm_name`` must be the class's name, exactly as the bot makes an
    owner type it back: a confirmation dialog is answered by the same thumb
    that opened it, while a name has to be read off the screen first. The app
    will show its own «вы уверены» sheet, but the check that matters is this
    one, because the endpoint is reachable without the sheet.

    Nothing is written to the audit log: the log lives in the class and goes
    with it. Every device token goes too, so the caller's own token stops
    working - which is correct, there is nothing left for it to read.
    """
    if payload.confirm_name.strip() != school_class.name:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
            detail="confirm_name does not match the class name",
        )

    class_id = school_class.id
    log.info("class %s deleted by %s", class_id, actor.telegram_id)
    await session.delete(school_class)
    await session.commit()
    return DeletedOut(id=class_id)


# --------------------------------------------------------------------------
# 📚 Subjects
# --------------------------------------------------------------------------


def _subject_out(subject: Subject) -> ManagedSubjectOut:
    return ManagedSubjectOut(
        id=subject.id,
        name=subject.name,
        short_name=subject.short_name,
        teacher=subject.teacher,
        color=subject.color,
    )


async def _subject_or_404(
    session: AsyncSession, school_class: SchoolClass, subject_id: int
) -> Subject:
    subject = await session.scalar(
        select(Subject).where(Subject.id == subject_id, Subject.class_id == school_class.id)
    )
    if subject is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown subject")
    return subject


async def _name_taken(
    session: AsyncSession, class_id: int, name: str, *, besides: int | None = None
) -> bool:
    """Ignores case, because everything downstream of it does."""
    return await subjects_service.clashing(session, class_id, name, besides=besides) is not None


@router.get("/subjects", response_model=list[ManagedSubjectOut])
async def subjects_list(
    _: Actor = Depends(editor_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedSubjectOut]:
    """The dictionary with ids, for a screen that edits it. An editor may
    read it - it is the same list ``GET /api/v1/subjects`` gives any device.

    Adopts whatever the timetable already uses on the way, so this list is
    never emptily lying about a class with thirty-five lessons in it. Free once
    the two agree, which after the first read they do.
    """
    # Committed whatever the count says, the way `public.bundle` does it. The
    # number that comes back is how many dictionary entries were *created*, and
    # the function also links the timetable rows to them — so a class whose
    # dictionary was already complete but whose lessons were not yet pointed at
    # it got its UPDATEs run and then dropped when the session closed, on every
    # read, forever. It healed only because `/bundle` commits unconditionally
    # and a phone polls it; the screen that exists to edit this list did the
    # work and threw it away.
    await subjects_service.sync_from_timetable(session, school_class.id)
    await session.commit()
    rows = await session.scalars(
        select(Subject).where(Subject.class_id == school_class.id).order_by(Subject.name)
    )
    return [_subject_out(row) for row in rows]


@router.post("/subjects", response_model=SubjectSavedOut, status_code=status.HTTP_201_CREATED)
async def subject_create(
    payload: SubjectIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Add a subject. The name is unique within the class - that uniqueness is
    the whole point of the dictionary, so a duplicate is a 409, not a silent
    second «Алгебра»."""
    if await _name_taken(session, school_class.id, payload.name):
        raise _conflict("a subject with that name is already in this class")

    subject = Subject(
        class_id=school_class.id,
        name=payload.name,
        short_name=payload.short_name,
        teacher=payload.teacher,
        color=payload.color,
    )
    session.add(subject)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "subject.add",
        f"добавлен предмет «{payload.name}»",
    )
    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject))


@router.patch("/subjects/{subject_id}", response_model=SubjectSavedOut)
async def subject_update(
    subject_id: int,
    payload: SubjectPatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> SubjectSavedOut:
    """Rename a subject, or set its short name, teacher or colour.

    A rename is a cascade: the timetable, the homework and the substitutions store the
    subject as text, so all three move with it in this one transaction, and
    ``moved`` says how many rows did. Renaming onto a name the class already
    uses is refused - merging two subjects is a different operation, and doing
    it by accident cannot be undone.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    changes = payload.model_dump(exclude_unset=True)
    new_name = changes.pop("name", None)
    moved = 0

    if new_name is not None and new_name != subject.name:
        if await _name_taken(session, school_class.id, new_name, besides=subject.id):
            raise _conflict("a subject with that name is already in this class")
        # The dictionary check above cannot see this one: homework may be
        # written under a name that is not a dictionary entry, and homework is
        # unique per subject per day. See `structure.homework_clashing`.
        clash = await structure.homework_clashing(
            session, school_class.id, subject.name, new_name
        )
        if clash:
            days = ", ".join(day.isoformat() for day in clash)
            raise _conflict(
                f"homework under both names on the same day: {days}"
            )
        old_name = subject.name
        moved = await structure.rename_subject(session, school_class.id, subject, new_name)
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "subject.rename",
            f"предмет «{old_name}» → «{new_name}», строк обновлено: {moved}",
        )

    #: Column -> (audit tag, the word the log line uses). The bot writes the
    #: colour as «цвет», not «color», and the two logs are read side by side.
    labels = {
        "short_name": ("subject.short_name", "сокращение"),
        "teacher": ("subject.teacher", "учитель"),
        "color": ("subject.colour", "цвет"),
    }
    for column, value in changes.items():
        setattr(subject, column, value)
        action, label = labels[column]
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            action,
            f"{label} предмета «{subject.name}»: {value or 'убрано'}",
        )

    await session.commit()
    await session.refresh(subject)
    return SubjectSavedOut(subject=_subject_out(subject), moved=moved)


@router.delete("/subjects/{subject_id}", response_model=DeletedOut)
async def subject_delete(
    subject_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Deleting a subject the timetable still uses is refused.

    It used to be allowed, and it left the lessons alone: the timetable stores
    the name as well as the link, so the class kept its timetable and lost
    only the colour and the teacher. That stopped being true when the
    dictionary started keeping itself. The name is still in the template, so
    the next read adopts it again - the entry returns within one poll, without
    its colour, its short name or its teacher, and the admin is left believing
    they deleted something.

    So the two halves of one list are deleted in one order: take the subject
    out of the weekly template, and then out of the dictionary. A subject
    nothing teaches still deletes in one step, which is the case this endpoint
    was really for.
    """
    subject = await _subject_or_404(session, school_class, subject_id)
    in_use = await subjects_service.lessons_using(session, school_class.id, subject)
    if in_use:
        raise _conflict(f"{in_use} lesson(s) still use this subject")
    name = subject.name
    await session.delete(subject)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "subject.delete",
        f"удалён предмет «{name}»",
    )
    await session.commit()
    return DeletedOut(id=subject_id)


# --------------------------------------------------------------------------
# 🔔 Bell schedules
# --------------------------------------------------------------------------


def _schedule_out(
    schedule: BellSchedule, school_class: SchoolClass, silenced: int = 0
) -> BellScheduleOut:
    return BellScheduleOut(
        id=schedule.id,
        name=schedule.name,
        is_default=schedule.id == school_class.bell_schedule_id,
        silenced_lessons=silenced,
        periods=[
            BellPeriodOut(index=row.index, starts_at=row.starts_at, ends_at=row.ends_at)
            for row in sorted(schedule.periods, key=lambda row: row.index)
        ],
    )


async def _schedule_or_404(
    session: AsyncSession, school_class: SchoolClass, schedule_id: int
) -> BellSchedule:
    schedule = await session.scalar(
        select(BellSchedule).where(
            BellSchedule.id == schedule_id, BellSchedule.class_id == school_class.id
        )
    )
    if schedule is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown bell schedule")
    return schedule


def _rows_of(payload: Any) -> list[tuple[int, Any, Any]]:
    """The wire shape as ``structure.write_bell_periods`` takes it."""
    return [(row.index, row.starts_at, row.ends_at) for row in payload.periods]


@router.get("/bells", response_model=list[BellScheduleOut])
async def bells_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[BellScheduleOut]:
    """Every schedule the class keeps - «Обычное», «Сокращённое», «Суббота» -
    with the class default marked."""
    rows = await session.scalars(
        select(BellSchedule)
        .where(BellSchedule.class_id == school_class.id)
        .order_by(BellSchedule.id)
    )
    return [_schedule_out(row, school_class) for row in rows]


@router.post("/bells", response_model=BellScheduleOut, status_code=status.HTTP_201_CREATED)
async def bells_create(
    payload: BellScheduleIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """A new named schedule, with its rows if they came with it.

    It does not become the class default: a shortened schedule is created in
    order to be pointed at by particular days, and making it the default the
    moment it exists would move every day onto it.
    """
    schedule = BellSchedule(class_id=school_class.id, name=payload.name)
    session.add(schedule)
    await session.flush()
    if payload.periods:
        await structure.write_bell_periods(session, schedule, _rows_of(payload))
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.create",
        f"создано расписание звонков «{payload.name}»: {len(payload.periods)} уроков",
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class)


async def _rung_by_default(
    session: AsyncSession, school_class: SchoolClass
) -> set[int]:
    """The lesson numbers the class rings today, before anything is changed.

    An empty set when the class has no default at all, which is the honest
    reading: nothing was ringing, so nothing can stop.
    """
    if school_class.bell_schedule_id is None:
        return set()
    current = await session.scalar(
        select(BellSchedule).where(BellSchedule.id == school_class.bell_schedule_id)
    )
    return {period.index for period in current.periods} if current is not None else set()


@router.patch("/bells/{schedule_id}", response_model=BellScheduleOut)
async def bells_update(
    schedule_id: int,
    payload: BellSchedulePatch,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """Rename a schedule, or make it the one the class runs on by default.

    ``is_default: false`` is refused: a class with no default schedule has no
    times for an ordinary day, so the way to stop using this one is to make
    another one the default.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)
    silenced: list[tuple[int, int]] = []

    if payload.name is not None and payload.name != schedule.name:
        old_name = schedule.name
        schedule.name = payload.name
        await audit.record(
            session,
            school_class.id,
            actor.telegram_id,
            "bells.rename",
            f"звонки «{old_name}» → «{payload.name}»",
        )

    if payload.is_default is not None:
        if not payload.is_default:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="make another schedule the default instead",
            )
        # A schedule may be created empty and filled in afterwards, which is
        # the point of the two-step flow — but the class default is what every
        # ordinary day rings, and a default that rings nothing draws nothing:
        # no lesson on any weekday can be placed on a timeline, so `/bundle`
        # answers zero lessons, `/now` answers «day_off» on a Monday, and the
        # phone, the widget, the calendar feed and the morning digest all go
        # blank at once with nothing anywhere reporting a problem. Worse,
        # `rung_indexes` then returns an empty set, which `can_ring` reads as
        # «this class has not set its bells up yet» and waves every lesson
        # number through. `edit.day_put` refuses the same thing at day level.
        if not schedule.periods:
            raise HTTPException(
                status_code=status.HTTP_422_UNPROCESSABLE_ENTITY,
                detail="в этом расписании звонков нет ни одного урока",
            )
        if school_class.bell_schedule_id != schedule.id:
            # Moving the default to a *shorter* schedule takes lessons off
            # every weekday exactly as shrinking the current one does — the
            # rows past its last rung stay in the database and are drawn,
            # logged and announced nowhere. `write_bell_periods` has answered
            # this question since the other way in was closed, but it is only
            # reached when a schedule's rows are rewritten, and re-pointing
            # the class rewrites none. Asked here with the incoming
            # schedule's numbers, through the same function, so the two
            # cannot answer differently.
            # What the class rang a moment ago, against what it will ring
            # now. Asking only the incoming schedule counts rows that were
            # already silent, which said «перестали звонить уроков: N» on a
            # move to a schedule ringing exactly the same numbers.
            was = await _rung_by_default(session, school_class)
            silenced = await structure.lessons_silenced_by(
                session,
                school_class.id,
                was,
                {period.index for period in schedule.periods},
            )
            school_class.bell_schedule_id = schedule.id
            summary = f"основное расписание звонков: «{schedule.name}»"
            if silenced:
                summary += f", перестали звонить уроков: {len(silenced)}"
            await audit.record(
                session,
                school_class.id,
                actor.telegram_id,
                "bells.default",
                summary,
            )

    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class, len(silenced))


@router.put("/bells/{schedule_id}/periods", response_model=BellScheduleOut)
async def bells_periods(
    schedule_id: int,
    payload: BellPeriodsIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> BellScheduleOut:
    """Replace a schedule's rows wholesale.

    Wholesale rather than row by row because that is what editing bells is:
    the times of the lessons after the one that moved all shift with it, and
    sending the six rows that are now true is one intention, not six.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)
    orphaned = await structure.write_bell_periods(session, schedule, _rows_of(payload))
    summary = f"звонки «{schedule.name}»: {len(payload.periods)} уроков"
    if orphaned:
        # Shrinking the class's own schedule takes lessons off every weekday
        # that carried a number past the new last rung. They are still stored
        # and they are drawn nowhere, so the log is where an admin finds out.
        summary += f", перестали звонить уроков: {len(orphaned)}"
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.edit",
        summary,
    )
    await session.commit()
    await session.refresh(schedule, ["periods"])
    return _schedule_out(schedule, school_class, len(orphaned))


@router.delete("/bells/{schedule_id}", response_model=DeletedOut)
async def bells_delete(
    schedule_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> DeletedOut:
    """Refused for the class default and for anything a day still points at.

    Both are a ``SET NULL`` at the database level, which would silently move
    those days onto the default schedule - a change nobody asked for, on dates
    an admin is not looking at.
    """
    schedule = await _schedule_or_404(session, school_class, schedule_id)

    if schedule.id == school_class.bell_schedule_id:
        raise _conflict("this is the class default; make another one the default first")

    used = await _count(
        session,
        DayOverride,
        DayOverride.class_id == school_class.id,
        DayOverride.bell_schedule_id == schedule.id,
    )
    if used:
        raise _conflict(f"{used} special day(s) still use this schedule")

    name = schedule.name
    await session.delete(schedule)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "bells.delete",
        f"удалено расписание звонков «{name}»",
    )
    await session.commit()
    return DeletedOut(id=schedule_id)


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
    entries = list(
        await session.scalars(
            select(TimetableEntry)
            .where(TimetableEntry.class_id == school_class.id)
            .order_by(TimetableEntry.weekday, TimetableEntry.index)
        )
    )
    periods = []
    if school_class.bell_schedule_id:
        schedule = await session.get(BellSchedule, school_class.bell_schedule_id)
        if schedule is not None:
            periods = list(schedule.periods)
    return TimetableExportOut(
        text=timetable_io.export_timetable(entries, periods), lessons=len(entries)
    )


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

    result = await structure.apply_timetable(session, school_class, days, bells)
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
    summary = f"импорт расписания: дней {len(days)}, уроков {total}"
    if bells:
        summary += f", звонков {len(bells)}"
    if result.dropped:
        summary += f", без звонка пропущено {len(result.dropped)}"
    if result.orphaned:
        summary += f", перестали звонить {len(result.orphaned)}"
    await audit.record(
        session, school_class.id, actor.telegram_id, "timetable.import", summary
    )
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


# --------------------------------------------------------------------------
# 📱 Devices
# --------------------------------------------------------------------------


def _device_out(
    device: DeviceToken,
    school_class: SchoolClass,
    names: dict[int, str],
    role: Role | None,
) -> ManagedDeviceOut:
    return ManagedDeviceOut(
        id=device.id,
        device_name=device.device_name,
        linked=device.is_linked,
        owner=names.get(device.telegram_id) if device.telegram_id is not None else None,
        role=role.value if role is not None else None,
        revoked=device.revoked,
        created_at=_wall(device.created_at, school_class),
        last_seen_at=_wall(device.last_seen_at, school_class),
        linked_at=_wall(device.linked_at, school_class),
    )


async def _device_or_404(
    session: AsyncSession, school_class: SchoolClass, device_id: int
) -> DeviceToken:
    device = await session.scalar(
        select(DeviceToken).where(
            DeviceToken.id == device_id, DeviceToken.class_id == school_class.id
        )
    )
    if device is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown device")
    return device


@router.get("/devices", response_model=list[ManagedDeviceOut])
async def devices_list(
    include_revoked: bool = Query(default=False),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[ManagedDeviceOut]:
    """The phones on the class's list, oldest first.

    The role is a lookup, not a stored field: a device acts with whatever role
    its owner holds right now, so revoking somebody in the bot has already
    changed this list by the time it is drawn. ``include_revoked`` brings back
    the ones that were switched off, which the bot's page leaves out.
    """
    devices = await linking.devices_of(session, school_class.id, include_revoked=include_revoked)
    names = await _member_names(session, school_class.id)
    # Once per owner, not once per phone: the role hangs off the account, and a
    # class where everybody joined from their own phone was paying a round trip
    # a row for an answer already in hand. «📱 Устройства» in the bot resolves
    # it the same way.
    roles: dict[int, Role | None] = {}
    for device in devices:
        if device.telegram_id is not None and device.telegram_id not in roles:
            roles[device.telegram_id] = await linking.effective_role(session, device)
    return [
        _device_out(device, school_class, names, roles.get(device.telegram_id))
        for device in devices
    ]


@router.post("/devices/{device_id}/revoke", response_model=ManagedDeviceOut)
async def device_revoke(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Switch a phone off. Revoked, not deleted: the row is what a token is
    checked against, and keeping it is what makes the refusal instant and
    permanent. Revoking an already revoked device changes nothing and adds no
    second line to the log.

    An admin may do this to the phone they are holding, and then the next
    request from it is a 401. That is the point - it is how a lost phone is
    dealt with from the one that is still in a pocket.
    """
    device = await _device_or_404(session, school_class, device_id)
    names = await _member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    if device.revoked:
        return _device_out(device, school_class, names, role)

    device.revoked = True
    name = device.device_name or f"Устройство {device.id}"
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "device.revoke",
        f"отключено устройство «{name}»",
    )
    await session.commit()
    return _device_out(device, school_class, names, role)


@router.post("/devices/{device_id}/unlink", response_model=ManagedDeviceOut)
async def device_unlink(
    device_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> ManagedDeviceOut:
    """Back to read-only, without taking the phone off the class.

    The device keeps reading the timetable and loses the role it borrowed from
    its owner's account. A device that is not linked has nothing to unlink,
    which is a 409 rather than a silent success: the admin pressed it expecting
    something to change.
    """
    device = await _device_or_404(session, school_class, device_id)
    if device.telegram_id is None:
        raise _conflict("device is not linked")

    name = device.device_name or f"Устройство {device.id}"
    await linking.unlink_device(session, device)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "device.unlink",
        f"отвязано устройство «{name}» — снова только чтение",
    )
    await session.commit()
    names = await _member_names(session, school_class.id)
    role = await linking.effective_role(session, device)
    return _device_out(device, school_class, names, role)


# --------------------------------------------------------------------------
# 📜 The audit log
# --------------------------------------------------------------------------


@router.get("/log", response_model=AuditPageOut)
async def audit_log(
    limit: int = Query(default=AUDIT_PAGE, ge=1, le=100),
    offset: int = Query(default=0, ge=0),
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> AuditPageOut:
    """Who changed what, newest first.

    One row more than ``limit`` is read and thrown away: that is what
    ``has_more`` is, and it costs one row instead of the count of an
    append-only table on every page turn.
    """
    entries = await audit.recent(session, school_class.id, limit=limit + 1, offset=offset)
    has_more = len(entries) > limit
    entries = entries[:limit]
    names = await _member_names(session, school_class.id)

    def _entry(row: AuditEntry) -> AuditEntryOut:
        return AuditEntryOut(
            id=row.id,
            action=row.action,
            summary=row.summary,
            who=names.get(row.telegram_id) if row.telegram_id is not None else None,
            at=_wall(row.created_at, school_class),
        )

    return AuditPageOut(
        entries=[_entry(row) for row in entries],
        limit=limit,
        offset=offset,
        has_more=has_more,
    )


# --------------------------------------------------------------------------
# 📊 Stats
# --------------------------------------------------------------------------


@router.get("/stats", response_model=StatsOut)
async def stats(
    _: Actor = Depends(editor_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> StatsOut:
    """The numbers «📊 Статистика» shows, without the sentence around them.

    An editor may read them, as in the bot: they say whether the timetable is
    complete and whether homework is being entered, which is exactly what the
    person entering it wants to know.
    """
    numbers = await stats_service.class_stats(session, school_class)
    return StatsOut(
        today=numbers["today"],
        lessons_per_week=numbers["lessons_per_week"],
        subjects_count=numbers["subjects_count"],
        subjects=[
            SubjectHoursOut(name=name, hours=hours) for name, hours in numbers["subjects"]
        ],
        homework_open=numbers["homework_open"],
        homework_total=numbers["homework_total"],
        members_by_role={role.value: count for role, count in numbers["members_by_role"].items()},
        devices_active=numbers["devices_active"],
        overrides_upcoming=numbers["overrides_upcoming"],
        events_upcoming=numbers["events_upcoming"],
    )


# --------------------------------------------------------------------------
# 🙋 Access requests
# --------------------------------------------------------------------------


async def _request_or_404(
    session: AsyncSession, school_class: SchoolClass, request_id: int
) -> AccessRequest:
    """Pending, and this class's. A request that has already been answered is
    gone as far as this endpoint is concerned, so two admins tapping «Выдать»
    at once cannot grant twice."""
    request = await session.scalar(
        select(AccessRequest).where(
            AccessRequest.id == request_id,
            AccessRequest.class_id == school_class.id,
            AccessRequest.status == "pending",
        )
    )
    if request is None:
        raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail="Unknown request")
    return request


@router.get("/requests", response_model=list[AccessRequestOut])
async def requests_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> list[AccessRequestOut]:
    """Everybody waiting for a role, oldest first."""
    rows = list(
        await session.scalars(
            select(AccessRequest)
            .where(AccessRequest.class_id == school_class.id, AccessRequest.status == "pending")
            .order_by(AccessRequest.id)
        )
    )
    names = await _member_names(session, school_class.id)
    return [
        AccessRequestOut(
            id=row.id,
            who=names.get(row.telegram_id, str(row.telegram_id)),
            requested_role=row.requested_role.value,
            message=row.message,
            created_at=_wall(row.created_at, school_class),
        )
        for row in rows
    ]


@router.post("/requests/{request_id}/approve", response_model=RequestDecisionOut)
async def request_approve(
    request_id: int,
    payload: RequestDecisionIn | None = None,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> RequestDecisionOut:
    """Grant the role, through the same rules as «👥 Доступ» in the bot.

    ``can_grant`` is the single source of "may I hand out this role" - nobody
    may grant at or above their own level, and OWNER is granted by the
    environment alone - and the rank guard below is the one the bot applies
    when changing an existing member: a peer's role is not yours to change.
    Without both, an admin could promote a friend to admin and be demoted by
    them a moment later.

    The requester is told in Telegram, because that is where they asked.
    """
    request = await _request_or_404(session, school_class, request_id)
    target = Role(payload.role) if payload is not None and payload.role else request.requested_role

    if not can_grant(actor.role, target):
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="cannot grant a role at or above your own",
        )

    member = await session.scalar(
        select(BotUser).where(
            BotUser.class_id == school_class.id, BotUser.telegram_id == request.telegram_id
        )
    )
    if member is not None and member.role.rank >= actor.role.rank:
        raise HTTPException(
            status_code=status.HTTP_403_FORBIDDEN,
            detail="cannot change this member's role",
        )

    if member is None:
        member = BotUser(
            telegram_id=request.telegram_id,
            class_id=school_class.id,
            role=target,
            granted_by=actor.telegram_id,
        )
        session.add(member)
    elif member.role.rank < target.rank:
        member.role = target
        member.granted_by = actor.telegram_id

    request.status = "approved"
    request.decided_by = actor.telegram_id
    request.decided_at = datetime.now(UTC).replace(tzinfo=None)
    who = _person(member.full_name, member.username, member.telegram_id)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "access.approve",
        f"выдана роль {target.title_ru}: {member.full_name or member.telegram_id}",
    )
    await session.commit()

    await _tell(
        request.telegram_id,
        f"✅ Доступ выдан: <b>{target.title_ru}</b> в классе "
        f"<b>{escape(school_class.name)}</b>. Откройте /start.",
    )
    return RequestDecisionOut(id=request_id, status="approved", role=target.value, who=who)


@router.post("/requests/{request_id}/decline", response_model=RequestDecisionOut)
async def request_decline(
    request_id: int,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> RequestDecisionOut:
    """Say no. The person keeps whatever role they already had, and is told -
    silence would leave them asking again."""
    request = await _request_or_404(session, school_class, request_id)
    names = await _member_names(session, school_class.id)
    who = names.get(request.telegram_id, str(request.telegram_id))

    request.status = "declined"
    request.decided_by = actor.telegram_id
    request.decided_at = datetime.now(UTC).replace(tzinfo=None)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "access.decline",
        f"отклонён запрос доступа от {request.telegram_id}",
    )
    await session.commit()

    await _tell(
        request.telegram_id,
        f"✖️ Запрос доступа в классе <b>{escape(school_class.name)}</b> отклонён.",
    )
    return RequestDecisionOut(id=request_id, status="declined", who=who)


# --------------------------------------------------------------------------
# Quarters and half-years
#
# The same service the bot's editor calls, for the same reason the rest of
# this module does it that way: «пересекается с периодом 2» decided twice
# disagrees within a month.
# --------------------------------------------------------------------------


def _terms_out(school_class: SchoolClass, year: int, rows) -> TermsOut:
    return TermsOut(
        kind=terms_service.scheme_of(school_class).value,
        year=year,
        terms=[
            TermOut(
                index=term.index,
                kind=term.kind.value,
                starts_on=term.starts_on,
                ends_on=term.ends_on,
            )
            for term in rows
        ],
    )


def _term_year(school_class: SchoolClass) -> int:
    """The school year in force for this class, in the class's own zone."""
    return terms_service.opening_year_of(datetime.now(school_class.tz).date())


@router.get("/terms", response_model=TermsOut)
async def terms_list(
    _: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """This class's terms, seeding the conventional set if it has none."""
    year = _term_year(school_class)
    rows = await terms_service.ensure(session, school_class, year)
    await session.commit()
    return _terms_out(school_class, year, rows)


@router.put("/terms/scheme", response_model=TermsOut)
async def terms_set_scheme(
    payload: TermSchemeIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """Switch between quarters and half-years, reseeding the year.

    A replacement rather than an edit: four quarters and two halves do not map
    onto each other, and a leftover third quarter inside a year that has two is
    not a state worth keeping.
    """
    year = _term_year(school_class)
    wanted = TermKind(payload.kind)
    rows = await terms_service.set_scheme(session, school_class, wanted, year)
    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "class.term_kind",
        f"схема: {'полугодия' if wanted is TermKind.SEMESTER else 'четверти'}",
    )
    await session.commit()
    return _terms_out(school_class, year, rows)


@router.put("/terms/{index}", response_model=TermsOut)
async def terms_set_bounds(
    index: int,
    payload: TermBoundsIn,
    actor: Actor = Depends(admin_actor),
    school_class: SchoolClass = Depends(current_class),
    *,
    session: FromDishka[AsyncSession],
) -> TermsOut:
    """Move one term's edges.

    422 with the service's own Russian sentence rather than a field-shaped
    error: what is wrong is the relationship between this term and the year or
    its neighbours, and «конец периода раньше его начала» is the thing worth
    putting on the screen.
    """
    year = _term_year(school_class)
    await terms_service.ensure(session, school_class, year)
    try:
        await terms_service.set_bounds(
            session, school_class, year, index, payload.starts_on, payload.ends_on
        )
    except terms_service.TermError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)
        ) from error

    await audit.record(
        session,
        school_class.id,
        actor.telegram_id,
        "class.term",
        f"период {index}: {payload.starts_on:%d.%m.%Y} — {payload.ends_on:%d.%m.%Y}",
    )
    await session.commit()
    rows = await terms_service.read(session, school_class.id, year)
    return _terms_out(school_class, year, rows)


@router.get("/schools", response_model=SchoolSearchOut)
async def schools_search(
    q: str = Query(..., description="Название или номер школы"),
    page: int = Query(1, ge=1),
    page_size: int = Query(
        schools_service.PAGE_SIZE,
        ge=1,
        le=dadata.MAX_SUGGESTIONS,
        description="Строк на странице; 20 отдаёт всё найденное за один запрос",
    ),
    region: str | None = Query(None, max_length=120),
    _: Actor = Depends(admin_actor),
    __: SchoolClass = Depends(current_class),
) -> SchoolSearchOut:
    """Search the school directory, the same way «⚙️ Класс» does in the bot.

    Reads nothing and writes nothing: it is a lookup the app needs before it
    can `PATCH /class` with a school name, and the name is all that is stored.
    Admin-only despite being read-only, because every call spends part of a
    daily allowance somebody else pays for, and the class's own members are the
    only people with a reason to spend it.

    503 rather than 500 when the directory is not configured or not answering:
    nothing here is broken, the feature is simply unavailable right now, and
    the client's answer to that is to let the name be typed.

    **Every call is one upstream search**, whatever ``page`` says — the
    directory has no offset to page with, so there is nothing to resume. A
    client that pages should therefore ask once with ``page_size=20`` and cut
    the answer up itself, which is what the bot and the app both do; asking for
    four pages of five is four searches for one question.
    """
    try:
        result = await schools_service.search(q, region=region)
    except schools_service.SearchError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)
        ) from error
    except dadata.DirectoryError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=error.message
        ) from error

    found = schools_service.page_of(
        result.schools, page, size=page_size, truncated=result.truncated
    )
    return SchoolSearchOut(
        items=[SchoolOut(**school.model_dump()) for school in found.items],
        page=found.page,
        pages=found.pages,
        total=found.total,
        truncated=found.truncated,
    )
