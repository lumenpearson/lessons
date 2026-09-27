"""The cleaning every free-text field of the wire format goes through.

Shared by the modules beside this one rather than repeated in each, because a
name is compared as text in places that cannot see each other: two spellings of
one cleaning are two subjects that look like one.
"""

from __future__ import annotations


def _strip_control_chars(value: str) -> str:
    """Drop anything unprintable, including NUL.

    ``device_name`` and ``code`` are client-supplied and land in a database
    column. Postgres rejects NUL inside text outright, so a device that sent one
    got a 500 out of what should be a clean request.
    """
    return "".join(ch for ch in value if ch == " " or ch.isprintable())


def _clean_optional_text(value: str | None) -> str | None:
    """One line, single-spaced, or ``None``.

    Runs of whitespace are collapsed, not merely trimmed at the ends, because
    the bot has always done exactly that - ``" ".join(text.split())`` on every
    name it is typed - and a name is compared as text in three places that
    cannot see each other: the uniqueness check that makes the subject
    dictionary a dictionary, the rename that carries the timetable, the
    homework and the substitutions along by name, and the widget's own matching. A
    class where «Алгебра и начала» was added from the phone and «Алгебра  и
    начала» from the bot has two subjects that look like one, and a rename of
    either moves none of the other's lessons.

    Every whitespace character becomes a space first, so a tab is a word break
    rather than something ``_strip_control_chars`` silently deletes - it is
    neither a space nor printable, so «а\tб» used to be stored as «аб».
    """
    if value is None:
        return None
    spaced = "".join(" " if ch.isspace() else ch for ch in value)
    return " ".join(_strip_control_chars(spaced).split()) or None


def _clean_notes(value: str | None) -> str | None:
    """Notes keep their line breaks; everything else unprintable goes."""
    if value is None:
        return None
    cleaned = "".join(ch for ch in value if ch == "\n" or ch == " " or ch.isprintable())
    cleaned = cleaned.strip()
    return cleaned or None
