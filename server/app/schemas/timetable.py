"""The weekly template as text, out and back in: «📤 Экспорт» and «📥 Импорт».

Part of the management surface; what holds across it is in the package
docstring.
"""

from __future__ import annotations

from pydantic import BaseModel, Field


class TimetableExportOut(BaseModel):
    """The whole weekly template in the bot's paste format.

    Byte-for-byte what «📤 Экспорт» sends, so a text saved from the bot can be
    imported by the app and the other way round.
    """

    text: str
    lessons: int


class TimetableImportIn(BaseModel):
    text: str = Field(min_length=1, max_length=20000)
    # Whether to overwrite weekdays that already have lessons. Without it an
    # import that would replace something answers with the conflicts instead.
    replace: bool = False


class ImportConflictOut(BaseModel):
    """One weekday the paste would overwrite: what is there now, what would
    replace it. Weekday is 1=Monday .. 7=Sunday, as everywhere else."""

    weekday: int
    existing: int
    incoming: int


class TimetableImportOut(BaseModel):
    """``applied: false`` means nothing was written - either the paste held no
    day the parser recognised, or it collided and ``replace`` was not set."""

    applied: bool
    days: list[int] = []
    lessons: int = 0
    bells: int = 0
    conflicts: list[ImportConflictOut] = []
    # Lines the parser could not read. Echoed back so an admin can fix the two
    # that were typos rather than re-reading the whole paste.
    rejected: list[str] = []
