"""The timetable paste format: reading it and writing it.

One home for the grammar, so that the single-day editor in the bot, the
whole-week import and «Экспорт» agree on what a line means - a paste produced
by the export has to survive the import unchanged, and a parity suffix typed
into the day editor has to mean the same thing as one in a week paste.

The line grammar — the keywords are Russian because they are typed by the
people using the bot::

    N. subject[, room[, teacher]][ <parity>]

where the parity suffix, always at the very end, is any of ``[чис]``,
``[знам]``, ``(чис)``, ``(знам)``, ``[1]``, ``[2]``, or a bare ``числ`` /
``знам`` (also ``чис``, ``числитель``, ``знаменатель``), case-insensitive.
A week paste has a header before each day: ``== Понедельник ==``,
``Понедельник:`` or ``Пн:``; a ``== Звонки ==`` block may follow.
"""

from __future__ import annotations

import re
from datetime import time as Time

from app.bot.render import WEEKDAYS
from app.models import BellPeriod, TimetableEntry, WeekParity

#: One parsed lesson line: (index, subject, room, teacher, parity).
LessonRow = tuple[int, str, str | None, str | None, WeekParity]

SUBJECT_MAX = 120
ROOM_MAX = 32
TEACHER_MAX = 120

LESSON_LINE = re.compile(r"^\s*(\d{1,2})\s*[.)]?\s*(.+?)\s*$")
BELL_LINE = re.compile(
    r"^\s*(\d{1,2})\s*[.)]?\s*(\d{1,2}[:.]\d{2})\s*[-–—]\s*(\d{1,2}[:.]\d{2})\s*$"
)

# ``(?<!\w)`` before the bare form: «знам» at the end of a longer word is
# that word, not a suffix.
PARITY_SUFFIX = re.compile(
    r"\s*(?:"
    r"[\[(]\s*(?P<bracketed>чис(?:л(?:итель)?)?|знам(?:енатель)?|1|2)\s*[\])]"
    r"|(?<!\w)(?P<bare>чис(?:л(?:итель)?)?|знам(?:енатель)?)"
    r")\s*$",
    re.IGNORECASE,
)

_HEADER = re.compile(r"^[\s=#*\-–—]*(?P<name>[а-яё]+)[\s=#*\-–—:]*$", re.IGNORECASE)

_WEEKDAY_BY_NAME: dict[str, int] = {
    "понедельник": 1, "пн": 1,
    "вторник": 2, "вт": 2,
    "среда": 3, "ср": 3,
    "четверг": 4, "чт": 4,
    "пятница": 5, "пт": 5,
    "суббота": 6, "сб": 6,
    "воскресенье": 7, "вс": 7,
}  # fmt: skip
_BELLS_HEADER = "звонки"

# Where a paste's lines are going while no weekday header is in effect.
_NOWHERE = None
_BELLS = 0

_PARITY_LABEL = {WeekParity.ODD: "[чис]", WeekParity.EVEN: "[знам]"}
_PARITY_ORDER = {WeekParity.ANY: 0, WeekParity.ODD: 1, WeekParity.EVEN: 2}


def _parity_of(token: str) -> WeekParity:
    token = token.lower()
    if token == "1" or token.startswith("чис"):
        return WeekParity.ODD
    return WeekParity.EVEN


def split_parity(text: str) -> tuple[str, WeekParity]:
    """Strip a trailing parity suffix: «Физика, 305 [чис]» -> («Физика, 305», ODD)."""
    match = PARITY_SUFFIX.search(text)
    if match is None:
        return text.strip(), WeekParity.ANY
    token = match.group("bracketed") or match.group("bare")
    return text[: match.start()].strip(), _parity_of(token)


def split_fields(text: str, limit: int) -> list[str]:
    """Split on commas, up to ``limit`` fields, honouring ``"…"`` quoting.

    Two rules, and between them a subject, a room or a teacher may contain a
    comma without the line changing meaning:

    * a field wrapped in double quotes keeps the commas inside it, and ``""``
      within such a field is one literal quote;
    * the last field takes everything that is left, so the trailing one never
      needs quoting at all — «Иванов И.И., к.п.н.» in the teacher position is
      simply the teacher.

    Both are needed. The second alone cannot help a *subject* with a comma in
    it, and the first alone would make every «к.п.н.» a quoting lesson.
    A field that neither starts with a quote nor is last behaves exactly as it
    did before, which is every line anybody has pasted so far.
    """
    fields: list[str] = []
    rest = text.strip()
    while rest and len(fields) < limit - 1:
        rest = rest.lstrip()
        if rest.startswith('"'):
            value, remainder = _read_quoted(rest)
            fields.append(value)
            remainder = remainder.lstrip()
            if remainder.startswith(","):
                rest = remainder[1:]
                continue
            rest = remainder
            break
        head, separator, tail = rest.partition(",")
        if not separator:
            break
        fields.append(head.strip())
        rest = tail
    tail = rest.strip()
    if tail or not fields:
        # Whatever is left is the last field, quotes and all if it has them.
        fields.append(_read_quoted(tail)[0] if tail.startswith('"') else tail)
    return fields


def _read_quoted(text: str) -> tuple[str, str]:
    """A ``"…"`` field and what follows it. An unclosed quote runs to the end."""
    out: list[str] = []
    index = 1
    while index < len(text):
        char = text[index]
        if char == '"':
            if index + 1 < len(text) and text[index + 1] == '"':
                out.append('"')
                index += 2
                continue
            return "".join(out).strip(), text[index + 1 :]
        out.append(char)
        index += 1
    return "".join(out).strip(), ""


def split_lesson_body(text: str) -> tuple[str | None, str | None, str | None]:
    """«Физика, 305, Петров П.П.» → the three parts, or ``(None, None, None)``.

    The half of a lesson line that is not its number. Split out so that the
    button editor, which already knows which lesson it is editing, reads a
    typed subject by exactly the rule a pasted line does — the alternative is
    two comma conventions, and the one people learn is whichever they hit
    first.

    A parity suffix is stripped and ignored here: in the button editor the
    week is chosen by the button, and leaving «[чис]» in would put it in the
    subject *name*, which is the bug that made the day editor and the week
    import disagree once already.

    Commas inside a field are handled by :func:`split_fields`. Before that,
    a subject like «Иностранный язык, второй» came back as the subject
    «Иностранный язык» in a room called «второй» — the export wrote it, the
    import read it back as two different things, and nothing was rejected, so
    the round trip this module's docstring promises quietly rewrote the row.
    """
    rest, _ = split_parity(text)
    parts = split_fields(rest, 3)
    subject = parts[0][:SUBJECT_MAX]
    # «12.» on its own: the optional separator backtracks and the dot becomes
    # the subject. A subject with no letter or digit in it is not one.
    if not any(char.isalnum() for char in subject):
        return None, None, None
    room = parts[1][:ROOM_MAX] if len(parts) > 1 and parts[1] else None
    teacher = parts[2][:TEACHER_MAX] if len(parts) > 2 and parts[2] else None
    return subject, room, teacher


def parse_lesson_line(line: str) -> LessonRow | None:
    """One lesson, or ``None`` for a line that is not one.

    Empty subjects and a lesson number of zero are refused here rather than
    at the database: (weekday, number, parity) is a unique key and a bad row
    used to abort the whole save with an IntegrityError.
    """
    match = LESSON_LINE.match(line)
    if match is None:
        return None
    index = int(match.group(1))
    if index < 1:
        return None

    rest, parity = split_parity(match.group(2))
    subject, room, teacher = split_lesson_body(rest)
    if subject is None:
        return None
    return index, subject, room, teacher, parity


def _conflicts(existing: list[LessonRow], row: LessonRow) -> bool:
    """A slot is one ANY lesson, or one ODD and one EVEN. Anything else is a
    repeat that would either violate the unique key or silently shadow a
    lesson when the week is resolved."""
    index, parity = row[0], row[4]
    for other in existing:
        if other[0] != index:
            continue
        if parity is WeekParity.ANY or other[4] is WeekParity.ANY or other[4] is parity:
            return True
    return False


def parse_timetable_block(raw: str) -> tuple[dict[int, list[LessonRow]], list[str]]:
    """A whole-week paste -> ({weekday: rows}, rejected lines).

    A weekday appears in the result as soon as its header does, even with no
    rows under it - that is how a paste says "Tuesday is empty". Lines before
    the first header are rejected rather than guessed at. Lines under
    «== Звонки ==» are skipped, not rejected: they are the bell schedule and
    :func:`parse_bells_block` reads them.
    """
    days: dict[int, list[LessonRow]] = {}
    rejected: list[str] = []
    current: int | None = _NOWHERE

    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped:
            continue

        header = _HEADER.match(stripped)
        if header is not None:
            name = header.group("name").lower()
            if name == _BELLS_HEADER:
                current = _BELLS
                continue
            weekday = _WEEKDAY_BY_NAME.get(name)
            if weekday is not None:
                current = weekday
                days.setdefault(weekday, [])
                continue

        if current == _BELLS:
            continue
        if current is _NOWHERE:
            rejected.append(stripped)
            continue
        # The day editor's own spelling of "nothing on this day".
        if stripped in {"-", "—"}:
            continue

        row = parse_lesson_line(stripped)
        if row is None or _conflicts(days[current], row):
            rejected.append(stripped)
            continue
        days[current].append(row)

    return days, rejected


def parse_bells_block(raw: str) -> tuple[list[tuple[int, Time, Time]], list[str]]:
    """The «== Звонки ==» block of a paste -> (rows, rejected lines).

    Only lines under that header are read; a paste without one yields nothing,
    so a week import never touches the bells by accident.
    """
    rows: list[tuple[int, Time, Time]] = []
    rejected: list[str] = []
    seen: set[int] = set()
    inside = False

    for line in raw.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        header = _HEADER.match(stripped)
        if header is not None:
            name = header.group("name").lower()
            if name == _BELLS_HEADER or name in _WEEKDAY_BY_NAME:
                inside = name == _BELLS_HEADER
                continue
        if not inside:
            continue

        match = BELL_LINE.match(stripped)
        if match is None:
            rejected.append(stripped)
            continue
        try:
            start = Time.fromisoformat(match.group(2).replace(".", ":"))
            end = Time.fromisoformat(match.group(3).replace(".", ":"))
        except ValueError:
            rejected.append(stripped)
            continue
        index = int(match.group(1))
        if start >= end or index < 1 or index in seen:
            rejected.append(stripped)
            continue
        seen.add(index)
        rows.append((index, start, end))

    return rows, rejected


def quote_field(value: str) -> str:
    """A field as the parser will read it back.

    Quoted only when it has to be, so an ordinary line is written exactly as
    it always was: an unquoted comma is the separator, and «Иностранный язык,
    второй» came back as a subject and a room.

    Only the subject and the room go through this. The teacher is third and
    :func:`split_fields` hands the last field everything that is left, so
    «Иванов И.И., к.п.н.» needs no quoting and stays legible. The rule is
    positional, not "whichever field happens to be last": a subject alone on
    its line is still field one of three as far as the parser is concerned,
    and a comma in it would still split.
    """
    if "," not in value and not value.startswith('"'):
        return value
    return '"' + value.replace('"', '""') + '"'


def format_lesson_line(entry: TimetableEntry) -> str:
    """The inverse of :func:`parse_lesson_line` for one stored row."""
    parts = [quote_field(entry.subject_name)]
    if entry.room or entry.teacher:
        # An empty middle field keeps the teacher in the third position, which
        # is where the parser looks for it.
        parts.append(quote_field(entry.room or ""))
    if entry.teacher:
        parts.append(entry.teacher)
    line = f"{entry.index}. {', '.join(parts)}"
    label = _PARITY_LABEL.get(entry.parity)
    return f"{line} {label}" if label else line


def export_timetable(entries: list[TimetableEntry], bells: list[BellPeriod]) -> str:
    """The whole template in the paste format, one block per weekday, bells last.

    Weekdays with nothing on them are left out rather than written as empty
    headers, so the text an admin gets is the timetable and nothing else.
    """
    by_day: dict[int, list[TimetableEntry]] = {}
    for entry in entries:
        by_day.setdefault(entry.weekday, []).append(entry)

    blocks: list[str] = []
    for weekday in sorted(by_day):
        lines = [f"== {WEEKDAYS[weekday - 1].capitalize()} =="]
        ordered = sorted(by_day[weekday], key=lambda e: (e.index, _PARITY_ORDER[e.parity]))
        lines.extend(format_lesson_line(entry) for entry in ordered)
        blocks.append("\n".join(lines))

    if bells:
        lines = ["== Звонки =="]
        lines.extend(
            f"{period.index}. {period.starts_at:%H:%M}-{period.ends_at:%H:%M}"
            for period in sorted(bells, key=lambda p: p.index)
        )
        blocks.append("\n".join(lines))

    return "\n\n".join(blocks)
