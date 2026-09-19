"""The container, and the two shells that draw from it.

A session used to be made in three places: :func:`app.db.get_session` for an
endpoint, ``SessionLocal()`` in the bot's middleware, and
:func:`app.db.session_scope` for the cron tick — each with its own answer to
whether the caller or the maker commits. These pin the one answer that
replaced the first two, in the two places that were written against it, and
the shape of the thing that would break if a provider were re-declared
somewhere else.
"""

from __future__ import annotations

from collections.abc import AsyncIterator

import pytest
from dishka import (
    STRICT_VALIDATION,
    FromDishka,
    Provider,
    Scope,
    make_async_container,
    provide,
)
from dishka.exceptions import ImplicitOverrideDetectedError
from dishka.integrations.aiogram import CONTAINER_NAME
from fastapi import APIRouter, Response
from sqlalchemy import select
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.routing import DishkaAnnotatedRoute
from app.config import Settings
from app.db import SessionLocal
from app.di import DatabaseProvider, SettingsProvider, container, make_container
from app.main import app
from app.models import SchoolClass


async def test_one_session_per_unit_of_work_and_a_new_one_for_the_next():
    """What «request scope» has to mean for the rest of this to be true.

    Every dependency of one request asks the container separately — the device,
    the class, the endpoint — and they have to be handed the same session or
    `current_class` writes `last_seen_at` on one connection while the endpoint
    reads the class on another. The next request must not get that session
    back: it would carry the previous caller's identity map into a reply about
    somebody else.
    """
    made = make_container()
    try:
        async with made() as request:
            first = await request.get(AsyncSession)
            again = await request.get(AsyncSession)
            assert first is again

        async with made() as other_request:
            assert await other_request.get(AsyncSession) is not first
    finally:
        await made.close()


async def test_the_session_is_closed_with_the_request_and_not_committed():
    """Two halves of one contract, and the endpoints depend on both.

    Nothing in this project commits on the way out of a request: several writes
    commit twice on purpose, because a notification is sent only once the thing
    it announces is durable. A container that committed would turn a handler
    that rolled back and raised into one that saved anyway.
    """
    made = make_container()
    try:
        async with made() as request:
            session = await request.get(AsyncSession)
            session.add(SchoolClass(name="9Б", school="Школа № 2", join_code="DIPROB"))
            # Flushed, so the row really is on the connection — and left
            # uncommitted, which is the half of the contract being asserted.
            await session.flush()

        async with SessionLocal() as after:
            assert await after.scalar(
                select(SchoolClass).where(SchoolClass.join_code == "DIPROB")
            ) is None
    finally:
        await made.close()


async def test_the_settings_are_one_object_for_the_whole_process():
    """App scope, so a deployment cannot half-reconfigure itself mid-request."""
    made = make_container()
    try:
        first = await made.get(Settings)
        async with made() as request:
            assert await request.get(Settings) is first
    finally:
        await made.close()


async def test_the_application_serves_requests_from_the_process_container():
    """The wiring itself, which nothing else would notice was missing.

    `setup_dishka` puts the container on `app.state`, and the middleware it
    installs is what opens a request scope. Without this line every endpoint
    would raise on its first `FromDishka` — but only at run time, and only on
    the endpoints a test happened to call.
    """
    assert app.state.dishka_container is container()


async def test_a_provider_declared_twice_is_refused_unless_it_says_so():
    """Why a test that rigs a session has to write `override=True`.

    Two providers of one type is almost always a mistake — the same session
    built two ways, and which one a caller gets decided by import order. Dishka
    refuses it, and that refusal is the reason the container can be trusted to
    have one answer; `test_hardening` opts out of it on purpose, for one
    request.
    """

    class SecondOpinion(Provider):
        @provide(scope=Scope.REQUEST)
        async def session(self) -> AsyncIterator[AsyncSession]:
            async with SessionLocal() as db:
                yield db

    # `make_container` asks for this; dishka's own default would let the
    # second provider quietly win, which is how «where does a session come
    # from» would go back to being answered by import order.
    with pytest.raises(ImplicitOverrideDetectedError):
        make_async_container(
            SettingsProvider(),
            DatabaseProvider(),
            SecondOpinion(),
            validation_settings=STRICT_VALIDATION,
        )

    # And the container this project actually builds refuses it too.
    assert make_container  # the provider list above is the one it uses


async def test_the_bot_takes_its_session_from_the_same_provider(school_class):
    """The point of the whole thing, in the shell that had no dependencies.

    `ContextMiddleware` used to open `SessionLocal()` itself. It now reads the
    scope `setup_dishka` opened on the dispatcher, which is the provider an
    endpoint is served by — so «a session for one unit of work» is one
    definition. The commit is still the middleware's, because for a handler
    this is the finish line.
    """
    from app.bot.middlewares import ContextMiddleware

    made = make_container()
    seen: dict[str, object] = {}

    async def handler(event, data):
        seen["session"] = data["session"]
        return "done"

    try:
        async with made() as request:
            data: dict[str, object] = {CONTAINER_NAME: request, "event_from_user": None}
            result = await ContextMiddleware()(handler, object(), data)
            assert result == "done"
            assert seen["session"] is await request.get(AsyncSession)
    finally:
        await made.close()


async def test_a_route_that_returns_a_response_is_not_given_a_response_model():
    """The one thing `DishkaRoute` alone gets wrong in this codebase.

    Every module here opens with `from __future__ import annotations`, so
    `-> Response` is the string «Response» until something resolves it. FastAPI
    resolves a return annotation against the endpoint's own globals to decide
    whether there is a response model — and dishka's wrapper is compiled, so
    its globals are dishka's and the name is not in them. What came back was a
    bare `ForwardRef`, which FastAPI took for a model.

    On `DELETE /tasks/{id}` that is an `AssertionError` at import time
    («Status code 204 must not have a response body»), which is loud. The
    quiet half is every endpoint that has no explicit `response_model=`: it
    would have been given a schema built out of a name. `DishkaAnnotatedRoute`
    resolves the annotation first, where the name is in scope.
    """
    router = APIRouter(route_class=DishkaAnnotatedRoute)

    @router.delete("/nothing", status_code=204)
    async def nothing(*, session: FromDishka[AsyncSession]) -> Response:
        return Response(status_code=204)

    @router.get("/something")
    async def something(*, session: FromDishka[AsyncSession]) -> Settings:
        return await session.get(Settings, 1)  # never called

    by_path = {route.path: route for route in router.routes}
    assert by_path["/nothing"].response_model is None
    # And an ordinary endpoint still gets the model FastAPI would have inferred
    # — the class, not a name that happens to look like one.
    assert by_path["/something"].response_model is Settings
