"""Running a class: what «⚙️ Класс» in the bot and ``/api/v1/manage`` both do.

``app.bot.handlers.manage`` and ``app.api.manage`` are two shells over the same
operations - rename a subject, make a bell schedule the default, switch a phone
off - and each used to carry its own copy of every one of them: the check, the
write, the audit line and its Russian wording. The copies agreed only while
somebody kept checking, and by the time they were brought here several had
drifted: the bot never logged a change of time zone, and a colour taken off a
subject was «убран» in one log and «убрано» in the other, which are read side
by side.

One module per screen, named like the shells' own, so the twin of
``bot/handlers/manage/bells.py`` and of ``api/manage/bells.py`` is
``services/manage/bells.py``.

What stays in a shell is translation: reading a payload, a paste or a callback,
and saying the outcome - in Russian in a message or an alert, or as JSON with
an English ``detail``. So a refusal here is an exception carrying the *facts*
(how many lessons, which days, which row), never a sentence, and each shell
keeps its own words for it.

Nothing here commits, like the rest of ``app/services/``: the caller commits
the change together with its audit line, so the two land as one fact.
"""
