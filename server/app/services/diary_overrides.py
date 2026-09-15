"""Corrections laid over what the diary sent down.

The Petersburg diary is read-only to this project and stays that way: nothing
here is sent upstream, and a family's correction never becomes a claim about
what the school recorded. What this module does is put a value **over** the one
that came down, on the way out, and say so — so that a room that is wrong every
Tuesday can be fixed once, and so that «сбросить» is a delete rather than a
second guess at what the upstream used to say.

Three rules live here, and they are the reason this is a module rather than a
few lines in the route.

**A target is semantic, never positional.** A homework item is addressed by its
upstream id when it has one and by (day, subject) when it does not; a lesson by
(day, number) or (day, subject). This project has already shipped positional
keys once — the calendar's UIDs were ``homework-…-1..3`` — and deleting one item
moved every key after it onto somebody else's subject, silently, for everyone
subscribed. The keys here are built once, on the way out, and the client echoes
back the exact string it was given rather than building its own.

**The field set is closed, and marks are not in it.** A grade and a turnstile
record are claims about what happened. An app that lets you rewrite them
produces a false record that looks official — a child hiding a two from a
parent, in an app the parent installed to see them. What can be corrected is
what the school typed as a description: the homework text, and a lesson's room,
teacher, topic or homework.

Corrections are never fetched by date range. A target built from a date is a
string, and asking the database to reason about the date inside one would make
the key format something the schema knows about; the read path loads every
correction for the student — a handful per family — and matches them in memory,
which is also what keeps this module free of SQLAlchemy.

**A correction is never silently kept over a changed upstream.** ``original``
holds what the diary said when the correction was written; when the diary later
says something else, that is reported alongside the value rather than hidden
behind it. It is not reset automatically: the person typed the correction, and
throwing it away because a teacher edited a field is not this code's decision.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.providers.petersburg.models import DiaryLesson, HomeworkItem

#: The longest a target may be, matching ``DiaryOverride.target``. A key is
#: built from a date and either a number or a subject name, so real ones are a
#: few dozen characters; the cap exists so that a pathological subject is
#: refused at the door rather than truncated into a collision with another.
MAX_TARGET_LENGTH = 300

#: What may be corrected on a lesson.
#:
#: ``subject`` is deliberately absent. It is half of the key for a lesson that
#: has no number, so renaming it would move the correction onto a different
#: lesson — or nowhere — and a rule that applies to some lessons and not others
#: is worse than not having it.
LESSON_FIELDS = frozenset({"homework", "room", "teacher", "topic"})

#: What may be corrected on a homework item. Its ``subject`` is half of the key
#: for the same reason, and its date is the whole of the other half.
HOMEWORK_FIELDS = frozenset({"text"})

#: Fields whose value is optional upstream. An empty correction on one of them
#: means "there is nothing here", which is a real answer — the upstream often
#: carries a placeholder — so it becomes ``None`` rather than an empty line.
NULLABLE_FIELDS = frozenset({"homework", "room", "teacher", "topic"})

_LESSON = "lesson"
_HOMEWORK = "hw"

FIELDS_BY_KIND: dict[str, frozenset[str]] = {
    _LESSON: LESSON_FIELDS,
    _HOMEWORK: HOMEWORK_FIELDS,
}


class UnknownTarget(ValueError):
    """The target names nothing this module knows how to correct."""


class UnsupportedField(ValueError):
    """The target is known; that field of it is not correctable."""


@dataclass(frozen=True)
class Edit:
    """One correction, as the client needs to see it.

    @property value what is being shown in place of the upstream's answer.
    @property original what the upstream says **now** — not what it said when
        the correction was made. The client renders it as «в дневнике: …».
    @property changed_upstream the diary has moved since this was written, so
        the value being hidden is not the one the person decided to replace.
    """

    field: str
    value: str
    original: str | None
    changed_upstream: bool


@dataclass(frozen=True)
class OverlaidLesson:
    """A lesson as it should be shown.

    @property ambiguous this lesson shares its key with another on the same
        day, so a correction written for it cannot be told from one written for
        the other. Nothing is applied, and the client says so rather than
        putting one group's room on both halves of a split class.
    """

    lesson: DiaryLesson
    target: str
    edits: tuple[Edit, ...]
    ambiguous: bool = False


@dataclass(frozen=True)
class OverlaidHomework:
    item: HomeworkItem
    target: str
    edits: tuple[Edit, ...]


def lesson_target(lesson: DiaryLesson) -> str:
    """The key for a lesson: the day, its number when it has one, and its subject.

    Both halves are needed and neither is enough. The number alone collides on a
    day a class splits into groups — the diary numbers lessons per day and is
    not above giving two of them the same number — and the subject alone
    collides on a double lesson, which is the ordinary shape of a Tuesday.

    Together they are still not a guarantee: a subject taught to two groups at
    the same hour produces one key for two lessons, and there is nothing else in
    what the diary sends that would tell them apart — the room and the teacher
    do, but those are the fields being corrected, so keying on them would make a
    correction unfindable the moment it was applied. That case is handled where
    it can be handled honestly, in [overlay_lessons]: a key that matches more
    than one lesson corrects neither, and says so.
    """
    number = "" if lesson.number is None else f"n{lesson.number}"
    return f"{_LESSON}:{lesson.date.isoformat()}:{number}:{lesson.subject}"


def homework_target(item: HomeworkItem) -> str:
    """The key for a homework item: its upstream id when it has one."""
    if item.id is not None:
        return f"{_HOMEWORK}:id:{item.id}"
    return f"{_HOMEWORK}:{item.due_date.isoformat()}:{item.subject}"


def kind_of(target: str) -> str:
    """Which family a target belongs to, or [UnknownTarget]."""
    kind, _, rest = target.partition(":")
    if not rest or kind not in FIELDS_BY_KIND:
        raise UnknownTarget(target)
    return kind


def check(target: str, field: str) -> None:
    """Refuses a correction this module would not know how to apply.

    Called before anything is written. A row that no read path can match is
    worse than a rejection: it would sit in the table looking like a correction
    somebody made, and the reset button for it would never appear.
    """
    if len(target) > MAX_TARGET_LENGTH:
        raise UnknownTarget(target)
    allowed = FIELDS_BY_KIND[kind_of(target)]
    if field not in allowed:
        raise UnsupportedField(field)


def _applied(value: str, field: str) -> str | None:
    return (value or None) if field in NULLABLE_FIELDS else value


def _overlay(
    current: dict[str, str | None],
    rows: dict[str, tuple[str, str | None]],
) -> tuple[dict[str, str | None], tuple[Edit, ...]]:
    """Folds the corrections for one item into its fields.

    @param current the upstream's values, by field.
    @param rows the corrections for this item: field -> (value, original as it
        was when written).
    """
    changes: dict[str, str | None] = {}
    edits: list[Edit] = []
    for field, (value, was) in sorted(rows.items()):
        upstream = current.get(field)
        changes[field] = _applied(value, field)
        edits.append(
            Edit(
                field=field,
                value=value,
                original=upstream,
                # `was` is what the diary said when this correction was
                # written. If it says something else now, the value being
                # covered up is no longer the one the person looked at.
                changed_upstream=(upstream or None) != (was or None),
            )
        )
    return changes, tuple(edits)


def overlay_lessons(
    lessons: list[DiaryLesson],
    corrections: dict[str, dict[str, tuple[str, str | None]]],
) -> list[OverlaidLesson]:
    """Applies corrections to a day's lessons, keeping upstream order.

    @param corrections target -> field -> (value, original when written).
    """
    # Counted over the whole fetched range rather than per day, which comes to
    # the same thing: a key carries its date, so two lessons can only share one
    # if they are on the same day. Counting first means the decision is the same
    # whichever order the upstream returned them in.
    shared: dict[str, int] = {}
    for lesson in lessons:
        key = lesson_target(lesson)
        shared[key] = shared.get(key, 0) + 1

    result: list[OverlaidLesson] = []
    for lesson in lessons:
        target = lesson_target(lesson)
        rows = corrections.get(target)
        if not rows:
            result.append(OverlaidLesson(lesson=lesson, target=target, edits=()))
            continue
        if shared[target] > 1:
            # A correction exists and is deliberately not applied: see
            # [lesson_target]. Reported rather than dropped, because the person
            # wrote it and the only thing they can do about it is reset it.
            result.append(
                OverlaidLesson(lesson=lesson, target=target, edits=(), ambiguous=True)
            )
            continue
        current = {field: getattr(lesson, field, None) for field in LESSON_FIELDS}
        changes, edits = _overlay(current, {f: v for f, v in rows.items() if f in LESSON_FIELDS})
        result.append(
            OverlaidLesson(
                lesson=lesson.model_copy(update=changes) if changes else lesson,
                target=target,
                edits=edits,
            )
        )
    return result


def overlay_homework(
    items: list[HomeworkItem],
    corrections: dict[str, dict[str, tuple[str, str | None]]],
) -> list[OverlaidHomework]:
    """Applies corrections to homework, keeping upstream order. @see overlay_lessons"""
    result: list[OverlaidHomework] = []
    for item in items:
        target = homework_target(item)
        rows = corrections.get(target)
        if not rows:
            result.append(OverlaidHomework(item=item, target=target, edits=()))
            continue
        current = {field: getattr(item, field, None) for field in HOMEWORK_FIELDS}
        changes, edits = _overlay(current, {f: v for f, v in rows.items() if f in HOMEWORK_FIELDS})
        result.append(
            OverlaidHomework(
                item=item.model_copy(update=changes) if changes else item,
                target=target,
                edits=edits,
            )
        )
    return result


def upstream_value(lesson: DiaryLesson | HomeworkItem, field: str) -> str | None:
    """What the diary currently says for a field, for storing as ``original``."""
    return getattr(lesson, field, None)

