"""The container: what has a lifetime, and who ends it.

This project is deliberately two thin shells over one implementation — the bot
writes and the API reads, and ``app/services/`` holds the rules both of them
call, because two implementations of "rename a subject" disagree within a
month. The shells had no such agreement about the things that are *built* per
unit of work. A session came from :func:`app.db.get_session` in an endpoint,
from ``SessionLocal()`` in the bot's middleware and from
:func:`app.db.session_scope` in ``scripts/seed_demo``: three answers to one
question, and each of them decided on its own whether the session was
committed for the caller or left to it. ``session_scope`` is the one still
standing, because a script is not a request and has nobody to take a scope
from; the other two are this module now.

Dishka is here for that, and for nothing more ambitious. ``Scope.APP`` holds
what lives as long as the process — the settings and the session factory —
and ``Scope.REQUEST`` holds what lives as long as one HTTP request or one
Telegram update. FastAPI is wired in :mod:`app.main` and aiogram in
:mod:`app.bot.bot`, so a handler on either side asks for ``AsyncSession`` and
gets the one session that unit of work is entitled to.

**Do not import this module's aiogram wiring here.** ``dishka.integrations.
aiogram`` imports aiogram, which costs about two and a half seconds, and this
module is on the cold-start path of every request including the ones that never
touch the bot — the same reason ``app/main.py`` defers ``app.bot.bot`` and
``app/api/telegram.py`` defers the dispatcher. Measured on this machine, what
this module *does* add on top of fastapi and sqlalchemy is 26 ms.

**What is deliberately not in here.** The two providers' HTTP clients
(``providers/petersburg/client.py``, ``providers/dadata/client.py``) are
process-wide singletons built on first use and closed in the lifespan. They
could be ``Scope.APP`` objects, but every call that wants one reaches for it
from inside a module-level function that takes no container, so moving them
would mean threading a client through the whole provider layer to change
nothing about when it is opened or closed. The engine itself stays in
``app/db.py`` for a harder reason: it is built at import time, which is what
makes a missing or unusable ``DATABASE_URL`` fail at import rather than on the
first query, and :mod:`app.config` refuses at that same door. A container built
later cannot move that check earlier.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

from dishka import (
    STRICT_VALIDATION,
    AsyncContainer,
    Provider,
    Scope,
    make_async_container,
    provide,
)
from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.config import Settings, get_settings
from app.db import SessionLocal


class SettingsProvider(Provider):
    """The deployment's configuration, read once.

    ``get_settings`` is already an ``lru_cache``, so this adds no second copy —
    it gives the object a name the container can hand out, which is what lets a
    handler on either side declare that it needs configuration instead of
    reaching into a module for it.
    """

    scope = Scope.APP

    @provide
    def settings(self) -> Settings:
        return get_settings()


class DatabaseProvider(Provider):
    """One session per unit of work, from one factory per process."""

    @provide(scope=Scope.APP)
    def sessions(self) -> async_sessionmaker[AsyncSession]:
        return SessionLocal

    @provide(scope=Scope.REQUEST)
    async def session(
        self, factory: async_sessionmaker[AsyncSession]
    ) -> AsyncIterator[AsyncSession]:
        """Opened for the request, closed with it, and **not** committed here.

        Every write in this project commits where it decides it is finished,
        because several of them commit twice on purpose — a notification is
        sent only after the thing it announces is durable, and the audit line
        goes in the same transaction as the change it describes. A container
        that committed on the way out would turn a handler that raised after
        its own rollback into a handler that saved anyway.

        A session that is closed without committing rolls back, which is the
        behaviour ``get_session`` had and the one the endpoints are written
        against.
        """
        async with factory() as session:
            yield session


def make_container() -> AsyncContainer:
    """A container, for a caller that wants to own one.

    A test builds its own with this and closes it; everything in the running
    process shares :func:`container` instead.

    ``STRICT_VALIDATION`` and not the default, for the reason the bot keeps
    one ``_allowed`` and ``services/`` holds the rules both shells call: two
    providers of one type is the same defect as two implementations of one
    rule, and by default dishka lets the second quietly win — which would put
    the answer to «where does a session come from» back in the hands of import
    order. It is now an error at startup unless the second one writes
    ``override=True``, which one test does, on purpose, for one request.
    """
    return make_async_container(
        SettingsProvider(),
        DatabaseProvider(),
        validation_settings=STRICT_VALIDATION,
    )


_container: AsyncContainer | None = None


def container() -> AsyncContainer:
    """The one container this process uses, built on the first ask.

    One and not one per shell: the API and the bot want the same settings and
    the same session factory, and on a long-polling deployment they are one
    process anyway. It is reached by name rather than passed down for the same
    reason ``shared_client`` in both providers is — the alternative is
    threading one object through every construction site to change nothing
    about what it is.

    No lock, unlike those two: this is called for the first time while
    :mod:`app.main` is being imported, which is before anything concurrent
    exists, and the providers inside it are what is lazy.
    """
    global _container
    if _container is None:
        _container = make_container()
    return _container


async def close_container() -> None:
    """Finalise every APP-scope object and forget the container.

    Called from the lifespan, after the bot has stopped: a handler still
    running would otherwise be holding a session out of a container that has
    shut its scopes.

    Forgetting it, rather than leaving a closed one in place, is what makes a
    second lifespan in the same process get a working container — and that is
    not hypothetical: ``tests/test_startup.py`` runs the lifespan twice.
    Whoever holds a *reference* to the old one has to ask again, which is why
    :mod:`app.main` sets ``app.state.dishka_container`` on the way in and not
    only at import. Today nothing APP-scoped here holds a resource, so closing
    finalises nothing and a closed container goes on serving; the first one
    that does would have torn itself down for the rest of the process instead,
    with nothing saying so.
    """
    global _container
    if _container is not None:
        await _container.close()
        _container = None
