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
        from app.config import get_settings

        return self.timezone or get_settings().timezone

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
    created_by: Mapped[int | None] = mapped_column(Integer)  # telegram user id
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
    telegram_id: Mapped[int] = mapped_column(Integer, index=True, nullable=False)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    role: Mapped[Role] = mapped_column(SAEnum(Role, native_enum=False), nullable=False)
    username: Mapped[str | None] = mapped_column(String(64))
    full_name: Mapped[str | None] = mapped_column(String(200))
    phone: Mapped[str | None] = mapped_column(String(32), index=True)
    granted_by: Mapped[int | None] = mapped_column(Integer)
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
    invited_by: Mapped[int | None] = mapped_column(Integer)
    used_by: Mapped[int | None] = mapped_column(Integer)
    used_at: Mapped[datetime | None] = mapped_column(DateTime)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())

    @property
    def is_used(self) -> bool:
        return self.used_by is not None


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


DEFAULT_BELLS: list[tuple[int, time, time]] = [
    (1, time(8, 30), time(9, 15)),
    (2, time(9, 25), time(10, 10)),
    (3, time(10, 25), time(11, 10)),
    (4, time(11, 25), time(12, 10)),
    (5, time(12, 30), time(13, 15)),
    (6, time(13, 25), time(14, 10)),
    (7, time(14, 20), time(15, 5)),
]
