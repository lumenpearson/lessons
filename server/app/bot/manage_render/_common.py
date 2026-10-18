"""What more than one management page prints."""

from __future__ import annotations

from html import escape


def cut_escaped(text: str, limit: int) -> str:
    """``text``, escaped, cut to at most ``limit`` *escaped* characters, with
    «…» appended when anything had to go.

    A raw-length budget (``wording.cut``'s) bounds the wrong thing for a
    field nothing stops from being nothing but ``&``: ``&`` alone is one raw
    character and five escaped ones, so a device name or a display name made
    of it can grow to five times a raw-length cap - the gap issue #352 was
    reopened over, once the owner's name was capped by its raw length and
    the device's own name was not capped at all. This walks the *raw* text
    one character at a time, growing the escaped length as it goes, and
    stops before the next character would push it past ``limit``, so the cut
    always falls between two whole characters - never, as slicing the
    already-escaped string can, inside an entity, leaving something like
    «&am» that is a refused message of its own. The ellipsis is appended
    unescaped (it is not one of ``html.escape``'s five characters) and does
    not count against its own budget, so ``limit`` is the most the result can
    ever be.
    """
    whole = escape(text)
    if len(whole) <= limit:
        return whole
    budget = limit - 1  # the ellipsis's own place
    kept = ""
    used = 0
    for char in text:
        grown = len(escape(char))
        if used + grown > budget:
            break
        kept += char
        used += grown
    return escape(kept.rstrip()) + "…"


def person(
    name: str | None,
    username: str | None,
    telegram_id: int | None,
    *,
    limit: int | None = None,
) -> str:
    """The best name we hold for somebody, already escaped.

    Falls back to the numeric id rather than to «неизвестный»: an id is
    something an admin can actually act on.

    ``limit`` bounds a long ``name``'s *escaped* length (:func:`cut_escaped`)
    rather than its raw one, because a name is exactly the free text nothing
    stops from being filled with the characters escaping grows the most.
    ``None`` (every caller but one, today) leaves a name exactly as long as
    whatever is held for it; a page whose keyboard builds one row per item
    regardless of a name's length (``manage_render.devices.OWNER_NAME_MAX``)
    passes one instead, because without it a handful of long names cut the
    page to fewer rows than the keyboard offers (#352). A username is never
    cut: Telegram already bounds it to 32 characters, far under any budget a
    page needs.
    """
    if username:
        return f"@{escape(username)}"
    if name:
        return cut_escaped(name, limit) if limit is not None else escape(name)
    return str(telegram_id) if telegram_id is not None else "—"
