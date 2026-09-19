"""Which database a one-shot script is about to write to, and whether it may.

Both scripts in this directory run DDL. ``init_db`` runs ``create_all``;
``seed_demo`` runs it and then puts a class carrying the published join code
``DEMO24`` into the result, in :attr:`~app.models.JoinMode.OPEN`. Either is
fine against a throwaway file and neither is fine against production — and the
shell that runs them is, two lines apart in ``CLAUDE.md``, the shell that runs
``DATABASE_URL='postgresql+asyncpg://…' alembic upgrade head``.

So the check lives here rather than in one of them. It was in one of them:
``seed_demo`` grew a refusal after it wrote to whatever ``DATABASE_URL``
pointed at and said nothing about it, and ``init_db`` — the one that actually
runs the DDL — kept neither the refusal nor an honest line about where it was
going, printing the word «local» for any URL that carried no password.
"""

from __future__ import annotations

import os

from app.database_url import describe

#: What counts as a database nobody minds losing. A file, and only a file.
LOCAL_SCHEMES = ("sqlite",)


def is_local(database_url: str) -> bool:
    """``sqlite+aiosqlite:///./lessons.db`` yes, anything reached over a network no.

    The dialect is compared, not the driver: SQLAlchemy spells it
    ``<dialect>+<driver>://``, and it is the dialect that says whether there is
    a host on the other end.
    """
    scheme = database_url.split(":", 1)[0]
    return scheme.split("+", 1)[0].lower() in LOCAL_SCHEMES


def where(database_url: str) -> str:
    """Where this is about to write, in a form that is safe to print.

    :func:`app.database_url.describe` rather than a second attempt at the same
    thing: it drops the password by splitting at the *last* ``@``, because a
    generated one may contain an unescaped ``@`` of its own, and
    ``tests/test_database_url.py`` holds both halves of that.
    """
    return describe(database_url) or "(no DATABASE_URL)"


def refuse_unless_local(database_url: str, *, variable: str, because: str) -> None:
    """Stop, unless this is a local file or the caller said the quiet part.

    ``variable`` is the environment variable that overrides the refusal and
    ``because`` is the sentence saying what the caller would be doing — both
    are the script's, not this module's, because «what you are about to do» is
    different for each and a shared wording would be a shared lie.
    """
    if is_local(database_url):
        return
    if os.environ.get(variable) == "yes":
        print(f"Writing to a NON-LOCAL database on request: {where(database_url)}")
        return
    raise SystemExit(
        f"Refusing to write to {where(database_url)}: this is not a local database.\n"
        f"{because}\n"
        f"If that is genuinely what you want: {variable}=yes"
    )
