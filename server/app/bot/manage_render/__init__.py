"""Text for the management pages, one module per screen of «⚙️ Класс», named
like the screens in ``handlers/manage/`` — each handler module reads its own as
``mr`` — and ``_common`` for what more than one of them prints.

Kept apart from ``render.py`` for the same reason ``manage_keyboards`` is kept
apart from ``keyboards``: the day-to-day views and the structural ones are read
and changed by different people at different times, and a helper renamed in one
must never be able to quietly reword the other.

Everything here escapes what it is handed. The rule across this codebase is
that a row is stored exactly as it was typed and escaped once, at render time -
so an admin who names a subject «Алгебра & геометрия» sees that name back and
not an entity, and a name typed with an angle bracket cannot close a tag the
message opened.

Every list is capped by row count, but a row carries free text — a note, an
assignment, an audit summary — and thirty long ones would overrun a limit no
row count can express; the budget and its three tools live in ``app.wording``,
which ``render`` re-exports. A page's cap is declared beside its renderer and
read by the same screen's keyboard, so what is drawn is what can be pressed.
"""
