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


class TermKind(enum.StrEnum):
    """How a school year is cut up.

    Younger classes are taught in four quarters; 10 and 11 are usually taught
    in two semesters, because that is how the leaving exams are organised. The
    scheme follows the grade by default and is editable, since a school is free
    to do neither — and plenty do тримест­ры, which is why this is stored per
    class rather than derived on every read.
    """

    QUARTER = "quarter"  # четверть
    SEMESTER = "semester"  # полугодие


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
    # The display name ("9А"). Still the one field every screen renders, and
    # still free-form for a class that calls itself something else — but it is
    # now composed from grade + letter when those are known, rather than typed.
    name: Mapped[str] = mapped_column(String(64), nullable=False)
    # Which year of school this is, 1 to 11. Typed as a number rather than read
    # out of the name because the rest of the app has to reason about it: the
    # term scheme follows it, and "9А" is not something to parse — a class may
    # be "9 инж", "11 ФМ" or "5-й Б", and a regular expression over that is a
    # guess that fails silently on the one class that is written differently.
    #
    # Nullable: every class that existed before this column has a name and no
    # number, and inventing one from the name is exactly the guess above.
    grade: Mapped[int | None] = mapped_column(Integer)
    # "А", "Б", … or whatever distinguishes two classes of the same year.
    letter: Mapped[str | None] = mapped_column(String(8))
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
    # Which electronic diary this class reads, or NULL for none. A string
    # rather than a boolean because there is one provider today and the name of
    # the column should not have to change when there are two.
    #
    # Binding a class does not give the class anything: the timetable, the
    # homework and the замены stay the class's own. What it does is offer every
    # *member* a way to sign in to their own account and read their own diary
    # in the same chat — which is why nothing here holds a credential.
    diary_provider: Mapped[str | None] = mapped_column(String(32))
    # Whether anyone with the join code may read the class, or only people an
    # admin let in. A public class is the honest default for a school whose
    # timetable is on a wall anyway; a private one is for a class that treats
    # its roster as its own business.
    is_public: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)
    # Which scheme this class's year is cut into. Null means "follow the
    # grade" — quarters up to 9, semesters at 10 and 11 — which is what
    # `term_kind_for` resolves; storing the answer only once somebody has
    # chosen it keeps "not decided" different from "deliberately quarters".
    term_kind: Mapped[TermKind | None] = mapped_column(SAEnum(TermKind, native_enum=False))
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
    #: Which break the столовая falls on: lunch is after lesson N. Kept on the
    #: schedule rather than as a weekly event because that is what it actually
    #: is — the same break every day this schedule is in force, moving with the
    #: bells when a shortened day moves them. A recurring DayEvent would have
    #: to be re-derived every time a bell row shifted by five minutes.
    canteen_after_index: Mapped[int | None] = mapped_column(Integer)

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


class Term(Base):
    """One четверть or полугодие, as this class actually runs it.

    Stored rather than computed because the dates are a school's own decision:
    the конец четверти moves for каникулы, for a quarantine, for a region that
    starts its spring break a week early. `app/services/terms.py` seeds a set
    of conventional ones when a class is created, and every one of them is
    meant to be edited afterwards — which is the whole reason they are rows.

    ``index`` is 1-based and counts within the year: quarters 1..4, semesters
    1..2. It is not derived from the dates, so a class that has not filled in
    the third quarter yet still knows the fourth is the fourth.
    """

    __tablename__ = "terms"
    __table_args__ = (
        UniqueConstraint("class_id", "year", "index", name="uq_term_slot"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    # The year the school year *opened* in — 2026 for 2026/27. One number
    # rather than a span, so "which terms are this year's" is an equality
    # rather than a range query over two columns that could disagree.
    year: Mapped[int] = mapped_column(Integer, nullable=False)
    kind: Mapped[TermKind] = mapped_column(
        SAEnum(TermKind, native_enum=False), default=TermKind.QUARTER, nullable=False
    )
    index: Mapped[int] = mapped_column(Integer, nullable=False)
    starts_on: Mapped[Date] = mapped_column(SADate, nullable=False)
    ends_on: Mapped[Date] = mapped_column(SADate, nullable=False)


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

    Two clocks, and which column is on which one matters. ``due_date``,
    ``due_time`` and ``remind_at`` are class wall time like the rest of this
    schema - a task due "at 15:00" is due at three o'clock where the school is,
    and the reminder tick compares ``remind_at`` against the class's own clock.
    ``done_at``, ``created_at`` and ``updated_at`` are naive UTC, because they
    record *when something happened* rather than a time somebody wrote down: an
    instant is the same instant in every zone, and the ICS export's
    ``COMPLETED:`` is a UTC stamp for exactly that reason. Reading one of the
    three as wall time would be wrong by the class's offset, which in this
    project reaches twelve hours.
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


class DiarySession(Base):
    """A signed-in session with the Petersburg electronic diary.

    What is stored here is the upstream's own session token and nothing else.
    Not the password: the reference implementations this integration was
    modelled on keep it so they can silently re-authenticate once a day, and
    that trade — a plaintext password for every family, sitting in a database,
    to save one login screen — is not one worth making. When the upstream
    session dies the app asks the person to sign in again, which is what every
    other service does too.

    The token is a bearer credential, so it is treated like one: it never
    leaves the server, never reaches the Android client, and the client is
    handed a token of ours instead (`token_hash`, hashed exactly like
    :class:`DeviceToken`). One row is one browser-shaped session; a person
    signing in twice gets two, and signing out drops one.
    """

    __tablename__ = "diary_sessions"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    # Our token, as a hash. The plaintext is shown to the client once.
    token_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    # The upstream's ``X-JWT-Token``, **sealed** — see ``app/crypto.py``. It is
    # the one credential here that cannot be a hash, because it is replayed to
    # the upstream on every call, so it is the one that is encrypted instead.
    # Refreshed in place (and re-sealed) whenever the upstream hands back a new
    # one, which it does on most calls.
    upstream_token: Mapped[str] = mapped_column(Text, nullable=False)
    # Who signed in, for the "you are signed in as" line and nothing else.
    login: Mapped[str] = mapped_column(String(200), nullable=False)
    # The Telegram account this session belongs to, when it was created from a
    # linked device. Null for a session created by login alone.
    telegram_id: Mapped[int | None] = mapped_column(BigInteger, index=True)
    # The class the session was opened from, when it was opened in the bot.
    # Carried so that leaving a class can take its diary session with it, and
    # so that «Дневник» in one class does not answer with a session opened in
    # another. Null for the Android client, which has no class in this flow.
    class_id: Mapped[int | None] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True
    )
    # Which child this session is reading, once the person has chosen. A parent
    # account can carry several; the bot asks once and remembers, because
    # asking on every screen is a question with the same answer every time.
    student_id: Mapped[int | None] = mapped_column(BigInteger)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    last_used_at: Mapped[datetime | None] = mapped_column(DateTime)
    # When the upstream refused us and the person has to sign in again.
    expired_at: Mapped[datetime | None] = mapped_column(DateTime)

    @property
    def is_live(self) -> bool:
        return self.expired_at is None


class DiaryLinkCode(Base):
    """A one-time ticket from a Telegram chat to the sign-in form.

    The password is the whole confidentiality question, and the answer this
    project gives is that it never enters Telegram at all. The bot hands out a
    URL; the form is served over HTTPS by this same app; the password goes
    from the browser straight to the upstream and is never written down. What
    Telegram ever sees is this code, which is worth one sign-in, for fifteen
    minutes, for one account.

    Typing the password to the bot instead would put it in the chat history, on
    Telegram's servers, in the notification that pops up on a locked screen and
    in whatever backs that phone up — and deleting the message afterwards
    undoes exactly none of those.

    Stored as a hash like every other credential here: a leak of this table is
    a list of tickets that cannot be used.
    """

    __tablename__ = "diary_link_codes"

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    code_hash: Mapped[str] = mapped_column(String(64), unique=True, index=True, nullable=False)
    telegram_id: Mapped[int] = mapped_column(BigInteger, index=True, nullable=False)
    class_id: Mapped[int] = mapped_column(
        ForeignKey("classes.id", ondelete="CASCADE"), index=True, nullable=False
    )
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    # Set the moment the form is submitted, successfully or not. One ticket is
    # one attempt: a code that survived a wrong password would let whoever has
    # the link keep guessing against the upstream from our address.
    used_at: Mapped[datetime | None] = mapped_column(DateTime)


class DiaryOverride(Base):
    """One correction a family laid over something the diary sent down.

    Nothing here changes dnevnik2. The upstream is read-only to this project
    and will stay that way; what this table holds is a value put **over** the
    one that came down, on the way out, so that «сбросить» is a delete rather
    than a second guess at what was there before. A row is the correction; its
    absence is the upstream's own answer.

    **Keyed by the account, not by the session.** Signing out and back in makes
    a new :class:`DiarySession` row, and a correction that went with it would
    make the reset button meaningless — the correction would already be gone,
    silently, the first time the upstream session expired. ``login`` is what
    survives, and it is the same string the session row already stores.

    ``target`` names the thing being corrected **semantically** rather than by
    position: a homework item by its upstream id when it has one and by (day,
    subject) when it does not, a lesson by (day, number) or (day, subject).
    Positional keys were tried once in this project, for calendar UIDs, and
    deleting one item silently moved every correction after it onto a different
    lesson. Nothing in this schema is allowed to be addressed by its index in a
    list again.

    ``original`` is what the upstream said **at the moment the correction was
    made**, and it is kept for one reason: so that the upstream moving
    afterwards can be noticed. A teacher who finally fills in the homework a
    family had typed in themselves must not have it hidden behind the older
    correction with nothing on screen to say so. The read path compares and
    flags it; it never resets on its own, because that would throw away what a
    person wrote.
    """

    __tablename__ = "diary_overrides"
    __table_args__ = (
        UniqueConstraint("login", "student_id", "target", "field", name="uq_diary_override"),
        Index("ix_diary_override_owner", "login", "student_id"),
    )

    id: Mapped[int] = mapped_column(Integer, primary_key=True)
    #: The upstream account. Stored as it is, like ``DiarySession.login``: it is
    #: a user name, not a credential, and it is already shown back to the person
    #: on the "вы вошли как" line.
    login: Mapped[str] = mapped_column(String(200), nullable=False)
    #: Which child, for an account that carries several.
    student_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    #: See the class docstring: semantic, never positional.
    target: Mapped[str] = mapped_column(String(300), nullable=False)
    #: Which field of that item. The set is closed and lives in
    #: ``app/services/diary_overrides.py``; marks and attendance are not in it.
    field: Mapped[str] = mapped_column(String(40), nullable=False)
    value: Mapped[str] = mapped_column(Text, nullable=False)
    #: What the upstream said when this was written; null when it said nothing.
    original: Mapped[str | None] = mapped_column(Text)
    created_at: Mapped[datetime] = mapped_column(DateTime, server_default=func.now())
    updated_at: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now()
    )


DEFAULT_BELLS: list[tuple[int, time, time]] = [
    (1, time(8, 30), time(9, 15)),
    (2, time(9, 25), time(10, 10)),
    (3, time(10, 25), time(11, 10)),
    (4, time(11, 25), time(12, 10)),
    (5, time(12, 30), time(13, 15)),
    (6, time(13, 25), time(14, 10)),
    (7, time(14, 20), time(15, 5)),
]
