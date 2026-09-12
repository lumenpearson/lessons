"""Domain model.

Design notes
------------
* All times are *local wall time* for the school (``Settings.timezone``). A bell
  rings at 08:30 regardless of DST, so storing naive local times is correct here
  and avoids a whole class of timezone bugs on the client.
* The timetable is a weekly template. Anything that deviates from it on a given
  date is expressed as a ``LessonOverride`` or a ``DayOverride`` — the template
  itself is never mutated for a one-off change.
* Homework is attached to the date it is *due*, not the date it was given.
"""

from __future__ import annotations

import enum
from datetime import date as Date
from datetime import datetime, time
from datetime import time as Time

from sqlalchemy import (
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    String,
    Text,
    UniqueConstraint,
    func,
)
from sqlalchemy import (
    Date as SADate,
)
from sqlalchemy import (
    Enum as SAEnum,
)
from sqlalchemy import (
    Time as SATime,
)
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db import Base


class Role(enum.StrEnum):
    """Bot permission levels, ordered from weakest to strongest."""

    VIEWER = "viewer"
    EDITOR = "editor"
    ADMIN = "admin"
    OWNER = "owner"

    @property
    def rank(self) -> int:
        return _ROLE_RANK[self]

    def at_least(self, other: Role) -> bool:
        return self.rank >= other.rank

    @property
    def title_ru(self) -> str:
        return _ROLE_TITLE_RU[self]


_ROLE_RANK: dict[Role, int] = {Role.VIEWER: 0, Role.EDITOR: 1, Role.ADMIN: 2, Role.OWNER: 3}
_ROLE_TITLE_RU: dict[Role, str] = {
    Role.VIEWER: "Наблюдатель",
    Role.EDITOR: "Редактор",
    Role.ADMIN: "Администратор",
    Role.OWNER: "Владелец",
}


class WeekParity(enum.StrEnum):
    """Some schools alternate the timetable between odd and even weeks."""

    ANY = "any"
    ODD = "odd"
    EVEN = "even"


class OverrideAction(enum.StrEnum):
    REPLACE = "replace"  # замена: другой предмет/кабинет на этом уроке
    CANCEL = "cancel"  # урок отменён
    ADD = "add"  # дополнительный урок, которого нет в шаблоне


class DayKind(enum.StrEnum):
    NORMAL = "normal"
    HOLIDAY = "holiday"  # каникулы / выходной
    SHORTENED = "shortened"  # сокращённые уроки (другое расписание звонков)
    REMOTE = "remote"  # дистанционное обучение


class EventKind(enum.StrEnum):
    EVENT = "event"
    CANTEEN = "canteen"
    EXAM = "exam"
    TRIP = "trip"
    MEETING = "meeting"


class SchoolClass(Base):
    """A single class-group ("9А"), the unit everything else hangs off."""

    __tablename__ = "classes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    school: Mapped[str | None] = mapped_column(String(200))
    city: Mapped[str | None] = mapped_column(String(120))
    # Russia spans eleven time zones, so this belongs to the class rather than
    # to the deployment. Null means "use the server default".
    timezone: Mapped[str | None] = mapped_column(String(64))
    join_code: Mapped[str] = mapped_column(String(16), unique=True, index=True, nullable=False)
    # Secret path segment of the class's iCal feed. Separate from the join code
    # on purpose: a calendar subscription URL ends up in Google Calendar's
    # settings, a family laptop and the odd screenshot, and none of those
    # should be able to mint device tokens. Null until somebody asks for the
    # feed; rotated from the bot by an admin.
    calendar_token: Mapped[str | None] = mapped_column(String(64), unique=True, index=True)
    # use_alter breaks the classes <-> bell_schedules cycle so the metadata can
    # be created and dropped in a deterministic order on SQLite.
    bell_schedule_id: Mapped[int | None] = mapped_column(
        ForeignKey("bell_schedules.id", ondelete="SET NULL", use_alter=True,
                   name="fk_class_bell_schedule")
    )
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    bell_schedule: Mapped[BellSchedule | None] = relationship(
        foreign_keys=[bell_schedule_id], lazy="selectin"
    )

    @property
    def tz(self):
        """The class's own zone, falling back to the server default."""
        from app.config import get_settings
        from app.timezones import resolve

        return resolve(self.timezone, get_settings().timezone)

    @property
    def timezone_name(self) -> str:
        """The name of the zone actually in effect — never an unresolvable one.

        The client is told to derive "now" from this field, so it has to be a
        zone the client can look up. Returning a stored typo (or a zone this
        build has no data for) while ``tz`` silently fell back to Moscow would
        put the app an unknown number of hours off.
        """
        from app.config import get_settings
        from app.timezones import resolve

        settings = get_settings()
        return resolve(self.timezone, settings.timezone).key

    def __repr__(self) -> str:  # pragma: no cover - debugging aid
        return f"<SchoolClass {self.id} {self.name!r}>"


class BellSchedule(Base):
    """A named set of period start/end times ("Обычное", "Сокращённое")."""

    __tablename__ = "bell_schedules"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(64), nullable=False)

    periods: Mapped[list[BellPeriod]] = relationship(
        back_populates="schedule",
        cascade="all, delete-orphan",
        order_by="BellPeriod.index",
        lazy="selectin",
    )


class BellPeriod(Base):
    """One row of the bell schedule: lesson N runs from ``starts_at`` to ``ends_at``."""

    __tablename__ = "bell_periods"
    __table_args__ = (UniqueConstraint("schedule_id", "index", name="uq_bell_period"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    schedule_id: Mapped[int] = mapped_column(
        ForeignKey("bell_schedules.id", ondelete="CASCADE"), index=True, nullable=False
    )
    index: Mapped[int] = mapped_column(Integer, nullable=False)  # 1-based lesson number
    starts_at: Mapped[Time] = mapped_column(SATime, nullable=False)
    ends_at: Mapped[Time] = mapped_column(SATime, nullable=False)

    schedule: Mapped[BellSchedule] = relationship(back_populates="periods")


class Subject(Base):
    """Subject dictionary — keeps naming consistent across the timetable."""

    __tablename__ = "subjects"
    __table_args__ = (UniqueConstraint("class_id", "name", name="uq_subject_name"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    name: Mapped[str] = mapped_column(String(120), nullable=False)
    short_name: Mapped[str | None] = mapped_column(String(16))
    teacher: Mapped[str | None] = mapped_column(String(120))
    color: Mapped[str | None] = mapped_column(String(9))  # #RRGGBB, optional accent


class TimetableEntry(Base):
    """One cell of the weekly template."""

    __tablename__ = "timetable_entries"
    __table_args__ = (
        UniqueConstraint("class_id", "weekday", "index", "parity", name="uq_timetable_cell"),
        Index("ix_timetable_lookup", "class_id", "weekday"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    weekday: Mapped[int] = mapped_column(Integer, nullable=False)  # 1=Mon .. 7=Sun
    index: Mapped[int] = mapped_column(Integer, nullable=False)  # lesson number
    subject_id: Mapped[int | None] = mapped_column(ForeignKey("subjects.id", ondelete="SET NULL"))
    subject_name: Mapped[str] = mapped_column(String(120), nullable=False)
    room: Mapped[str | None] = mapped_column(String(32))
    teacher: Mapped[str | None] = mapped_column(String(120))
    parity: Mapped[WeekParity] = mapped_column(
        SAEnum(WeekParity, native_enum=False), default=WeekParity.ANY, nullable=False
    )


class DayOverride(Base):
    """Marks a whole date as a holiday / shortened day / remote day."""

    __tablename__ = "day_overrides"
    __table_args__ = (UniqueConstraint("class_id", "date", name="uq_day_override"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    date: Mapped[Date] = mapped_column(SADate, nullable=False)
    kind: Mapped[DayKind] = mapped_column(
        SAEnum(DayKind, native_enum=False), default=DayKind.NORMAL, nullable=False
    )
    bell_schedule_id: Mapped[int | None] = mapped_column(
        ForeignKey("bell_schedules.id", ondelete="SET NULL")
    )
    note: Mapped[str | None] = mapped_column(Text)


class LessonOverride(Base):
    """Замена: a per-date change to a single lesson slot."""

    __tablename__ = "lesson_overrides"
    __table_args__ = (
        UniqueConstraint("class_id", "date", "index", name="uq_lesson_override"),
        Index("ix_lesson_override_lookup", "class_id", "date"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    date: Mapped[Date] = mapped_column(SADate, nullable=False)
    index: Mapped[int] = mapped_column(Integer, nullable=False)
    action: Mapped[OverrideAction] = mapped_column(
        SAEnum(OverrideAction, native_enum=False), nullable=False
    )
    subject_name: Mapped[str | None] = mapped_column(String(120))
    room: Mapped[str | None] = mapped_column(String(32))
    teacher: Mapped[str | None] = mapped_column(String(120))
    note: Mapped[str | None] = mapped_column(Text)


class Homework(Base):
    """Homework due on ``due_date``."""

    __tablename__ = "homework"
    __table_args__ = (Index("ix_homework_lookup", "class_id", "due_date"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    due_date: Mapped[Date] = mapped_column(SADate, nullable=False)
    subject_name: Mapped[str] = mapped_column(String(120), nullable=False)
    text: Mapped[str] = mapped_column(Text, nullable=False)
    attachment_url: Mapped[str | None] = mapped_column(String(500))
    # Telegram user id. BigInteger, not Integer: Telegram ids passed 2^31 in
    # 2021 and the API documents them as up to 52 bits, while Integer maps to
    # int4 on Postgres. The first teacher with a modern account would have hit
    # NumericValueOutOfRange — invisibly, because the webhook answers 200 on an
    # exception, so the bot would simply have stopped replying. SQLite, which
    # the dev setup and the tests run on, has no integer width and never showed
    # it. The same applies to every other telegram id column below.
    created_by: Mapped[int | None] = mapped_column(BigInteger)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class DayEvent(Base):
    """Anything on the timeline that is not a lesson: столовая, линейка, экскурсия."""

    __tablename__ = "day_events"
    __table_args__ = (Index("ix_event_lookup", "class_id", "date"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    date: Mapped[Date] = mapped_column(SADate, nullable=False)
    starts_at: Mapped[Time] = mapped_column(SATime, nullable=False)
    ends_at: Mapped[Time] = mapped_column(SATime, nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    kind: Mapped[EventKind] = mapped_column(
        SAEnum(EventKind, native_enum=False), default=EventKind.EVENT, nullable=False
    )
    location: Mapped[str | None] = mapped_column(String(120))
    # A canteen break usually replaces a normal break rather than overlapping a lesson.
    covers_lesson: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)


class BotUser(Base):
    """A Telegram account known to the bot, with its role in one class."""

    __tablename__ = "bot_users"
    __table_args__ = (UniqueConstraint("telegram_id", "class_id", name="uq_bot_user_class"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    role: Mapped[Role] = mapped_column(SAEnum(Role, native_enum=False), nullable=False)
    username: Mapped[str | None] = mapped_column(String(64))
    full_name: Mapped[str | None] = mapped_column(String(200))
    phone: Mapped[str | None] = mapped_column(String(32), index=True)
    granted_by: Mapped[int | None] = mapped_column(BigInteger)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class PhoneInvite(Base):
    """Pre-authorised phone number: the role is applied when that person shares
    their contact with the bot. This is the "добавить по номеру" flow."""

    __tablename__ = "phone_invites"
    __table_args__ = (UniqueConstraint("class_id", "phone", name="uq_phone_invite"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    phone: Mapped[str] = mapped_column(String(32), index=True, nullable=False)
    role: Mapped[Role] = mapped_column(SAEnum(Role, native_enum=False), nullable=False)
    label: Mapped[str | None] = mapped_column(String(120))
    invited_by: Mapped[int | None] = mapped_column(BigInteger)
    used_by: Mapped[int | None] = mapped_column(BigInteger)
    used_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    @property
    def is_used(self) -> bool:
        return self.used_by is not None


class JoinAttempt(Base):
    """One failed join attempt, recorded so the limit survives a cold start.

    The limiter used to hold its counters in a process-local dict, which it
    documented as correct because "the deployment is a single uvicorn worker".
    That premise is false for this deployment: on Vercel every concurrent
    invocation is its own Python process and instances are recycled constantly,
    so the dict was empty almost every time it was consulted and the ceiling of
    thirty attempts per fifteen minutes did not exist. A six-character code from
    a 32-symbol alphabet is 2**30 possibilities, and each guess is tested
    against every class at once — so an unthrottled endpoint was the whole of
    the security around somebody's timetable.

    The database is the one thing every invocation shares, so the counter lives
    here. Rows are pruned whenever the table is read, which keeps it to roughly
    the failures of one window.

    ``client_key`` is a hash, not an address: this only ever needs to tell two
    clients apart, and storing the addresses themselves would be collecting
    personal data to answer a question that does not need it.
    """

    __tablename__ = "join_attempts"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    client_key: Mapped[str] = mapped_column(String(64), nullable=False, index=True)
    created_at: Mapped[datetime] = mapped_column(DateTime, nullable=False, index=True)


class DeviceToken(Base):
    """Read-only token handed to an Android device after it enters a join code."""

    __tablename__ = "device_tokens"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    device_name: Mapped[str | None] = mapped_column(String(120))
    revoked: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    last_seen_at: Mapped[datetime | None] = mapped_column(DateTime)
    # The Telegram account this device belongs to, once its owner has sent the
    # bot ``/link <link_code>``. Null for an unlinked device, which is the
    # common case and the read-only one. Every write endpoint derives its
    # permission from the linked account's role in ``class_id`` at request
    # time - there is no second permission system for devices, and revoking
    # someone in the bot revokes their phone in the same instant.
    telegram_id: Mapped[int | None] = mapped_column(BigInteger, index=True)
    # Short code the app shows and the user types into the bot. Cleared once
    # used; a device that is unlinked again gets a fresh one on request.
    link_code: Mapped[str | None] = mapped_column(String(16), unique=True, index=True)
    linked_at: Mapped[datetime | None] = mapped_column(DateTime)

    @property
    def is_linked(self) -> bool:
        return self.telegram_id is not None


class TaskPriority(enum.IntEnum):
    """Three levels is what fits on a phone keyboard; anything finer is noise."""

    LOW = 0
    NORMAL = 1
    HIGH = 2


class PersonalTask(Base):
    """One person's to-do item: «купить тетрадь», «сдать реферат до пятницы».

    Personal, not shared: ``telegram_id`` scopes every query. A task may point
    at a homework row (``homework_id``) when it was created from one, so that
    ticking it off in the bot and ticking the homework off in the app are the
    same fact - but it survives the homework being deleted, because the
    person's plan is theirs, not the editor's.

    Times are class wall time like everything else in this schema; ``remind_at``
    is compared against the class's own clock by the reminder tick.
    """

    __tablename__ = "personal_tasks"
    __table_args__ = (Index("ix_task_owner", "class_id", "telegram_id", "done", "due_date"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    title: Mapped[str] = mapped_column(String(200), nullable=False)
    notes: Mapped[str | None] = mapped_column(Text)
    subject_name: Mapped[str | None] = mapped_column(String(120))
    due_date: Mapped[Date | None] = mapped_column(SADate)
    due_time: Mapped[Time | None] = mapped_column(SATime)
    priority: Mapped[int] = mapped_column(Integer, default=int(TaskPriority.NORMAL), nullable=False)
    done: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    done_at: Mapped[datetime | None] = mapped_column(DateTime)
    homework_id: Mapped[int | None] = mapped_column(ForeignKey("homework.id", ondelete="SET NULL"))
    # Class wall time at which to nudge the owner once. Cleared after sending.
    remind_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


class HomeworkDone(Base):
    """«Сделал»: one person's tick on one homework row.

    Kept apart from ``Homework`` because the homework is the class's and the
    tick is the pupil's; thirty pupils marking the same задание must not write
    to the same row.
    """

    __tablename__ = "homework_done"
    __table_args__ = (UniqueConstraint("homework_id", "telegram_id", name="uq_homework_done"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    homework_id: Mapped[int] = mapped_column(
        ForeignKey("homework.id", ondelete="CASCADE"), index=True, nullable=False
    )
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    done_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class ReminderSettings(Base):
    """What the bot may message this person about, and when.

    All three times are class wall time. ``last_*_sent`` is the date (in the
    class's zone) of the last digest of that kind, which is what makes the
    reminder tick idempotent: a tick that runs twice in the same minute, or
    fifteen minutes late, sends each digest once per day and never twice.
    """

    __tablename__ = "reminder_settings"
    __table_args__ = (UniqueConstraint("class_id", "telegram_id", name="uq_reminder_owner"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    # Today's lessons, замены and events, sent in the morning.
    morning_at: Mapped[Time | None] = mapped_column(SATime)
    # Homework due on the next school day, sent the evening before.
    evening_at: Mapped[Time | None] = mapped_column(SATime)
    # Immediate messages when an editor adds a замена / event for the next days.
    notify_changes: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)
    # Immediate messages when homework is added or changed.
    notify_homework: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    last_morning_sent: Mapped[Date | None] = mapped_column(SADate)
    last_evening_sent: Mapped[Date | None] = mapped_column(SADate)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class AccessRequest(Base):
    """«Хочу редактировать»: a member asking the admins for a higher role.

    Raised from the bot or from a linked phone; answered by an admin in the bot.
    One open request per person per class - a second one replaces the first.
    """

    __tablename__ = "access_requests"
    __table_args__ = (Index("ix_access_request_lookup", "class_id", "status"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    requested_role: Mapped[Role] = mapped_column(SAEnum(Role, native_enum=False), nullable=False)
    # pending | approved | declined
    status: Mapped[str] = mapped_column(String(16), default="pending", nullable=False)
    message: Mapped[str | None] = mapped_column(String(300))
    decided_by: Mapped[int | None] = mapped_column(BigInteger)
    decided_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


class AuditEntry(Base):
    """Who changed what. Written by every bot handler and API endpoint that
    writes class data, read from «⚙️ Класс → 📜 Журнал»."""

    __tablename__ = "audit_log"
    __table_args__ = (Index("ix_audit_lookup", "class_id", "created_at"),)

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    telegram_id: Mapped[int | None] = mapped_column(BigInteger)
    # Short machine tag: homework.add, override.cancel, timetable.replace, ...
    action: Mapped[str] = mapped_column(String(64), nullable=False)
    # One human line, already safe to show (escaped at render time, not here).
    summary: Mapped[str] = mapped_column(String(500), nullable=False)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())


DEFAULT_BELLS: list[tuple[int, time, time]] = [
    (1, time(8, 30), time(9, 15)),
    (2, time(9, 25), time(10, 10)),
    (3, time(10, 25), time(11, 10)),
    (4, time(11, 25), time(12, 10)),
    (5, time(12, 30), time(13, 15)),
    (6, time(13, 25), time(14, 10)),
    (7, time(14, 20), time(15, 5)),
]
