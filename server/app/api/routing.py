"""The route class every router in this package is built with.

``DishkaRoute`` is what lets an endpoint ask for a dependency by writing
``session: FromDishka[AsyncSession]`` instead of taking it through FastAPI's
own ``Depends``. It wraps the endpoint, and the wrapper is compiled: it carries
the original's name, docstring and parameters, but its ``__globals__`` are
dishka's, not this module's.

That matters here because every module in this project starts with
``from __future__ import annotations``, so an endpoint's ``-> Response`` is the
*string* ``"Response"`` until somebody resolves it. FastAPI resolves a return
annotation against the endpoint's own globals to decide the response model —
and against the wrapper's globals the name is not there, so what came back was
a bare ``ForwardRef``. FastAPI then took the ForwardRef for a response model,
which on ``DELETE /tasks/{id}`` is an outright ``AssertionError`` at import
(«Status code 204 must not have a response body») and on any endpoint without
an explicit ``response_model=`` would have been a schema built from a name.

So the annotation is resolved here, once, before the wrapping — against the
globals that actually contain it. Everything downstream then sees the class
FastAPI would have seen without any of this.

The route class is also where v1 says, once, that a row another writer
deleted between a write's read and its commit is not found (#384): the
``StaleDataError`` of a flush whose ``UPDATE`` matched nothing becomes the
``404`` the route's resource answers for a row that is not there
(:data:`GONE`), rather than a ``500`` — or, for a term and a diary
correction, the status :data:`GONE_STATUS` gives them.
Nothing was written, and an upsert — ``PUT /overrides``, ``PUT /days``, a
diary correction — does not make the row again (``services/gone.py`` says
why). A path :data:`GONE` does not name lets the error through, as it did.
"""

from __future__ import annotations

from collections.abc import Callable, Coroutine, Mapping
from inspect import Signature, signature
from typing import Any

from dishka.integrations.fastapi import DishkaRoute
from fastapi import HTTPException, Request, Response, status
from fastapi.dependencies.utils import get_typed_return_annotation
from sqlalchemy.ext.asyncio import AsyncSession
from sqlalchemy.orm.exc import StaleDataError

from app import wording
from app.services import terms as terms_service

#: Where every path :data:`GONE` is asked about begins.
_PREFIX = "/api/v1/"

#: What a write under each resource answers when the row it changed was
#: deleted by another writer before its commit: the route's own ``404`` for a
#: row that is not there, and for a substitution and a day's mark, which v1
#: names by date and never answered ``404`` for, the sentence v2 says. Keyed
#: by a path's segments with its numbers left out, and the longest key the
#: path begins with wins: ``manage/terms/scheme`` is the class, deleted with
#: its terms, and ``manage/terms`` the term a scheme change replaced. The
#: class is the row ``/me`` and the scheme meet: deleted, it takes the phone's
#: device and the year's terms with it (#654).
GONE: Mapping[str, str] = {
    "homework": wording.UNKNOWN_HOMEWORK_DETAIL,
    "overrides": wording.UNKNOWN_SUBSTITUTION_DETAIL,
    "events": wording.UNKNOWN_EVENT_DETAIL,
    "days": wording.DAY_NOT_MARKED_DETAIL,
    "tasks": wording.UNKNOWN_TASK_DETAIL,
    "me": wording.CLASS_GONE_DETAIL,
    "diary/students/overrides": wording.CORRECTION_GONE_DETAIL,
    "manage/subjects": wording.UNKNOWN_SUBJECT_DETAIL,
    "manage/bells": wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
    "manage/devices": wording.UNKNOWN_DEVICE_DETAIL,
    "manage/requests": wording.UNKNOWN_ACCESS_REQUEST_DETAIL,
    "manage/class": wording.CLASS_GONE_DETAIL,
    "manage/terms": terms_service.NO_SUCH_TERM,
    "manage/terms/scheme": wording.CLASS_GONE_DETAIL,
}

#: The status of a :data:`GONE` key that does not answer ``404``. A term's is
#: the ``422`` ``PUT /manage/terms/{index}`` answers every ``TermError`` with,
#: which v2's ``TERM_BOUNDS_REFUSED`` names as its v1 twin. A correction's is
#: a ``409``, because a ``404`` on the diary's routes means a child not on the
#: account, and the app reads it so whatever ``detail`` says: it would tell a
#: parent whose relative had cleared a correction that the child is not theirs.
#: A ``409`` it shows as a failure to save, with the form left open (#654).
GONE_STATUS: Mapping[str, int] = {
    "manage/terms": status.HTTP_422_UNPROCESSABLE_CONTENT,
    "diary/students/overrides": status.HTTP_409_CONFLICT,
}


def gone_key(path: str) -> str | None:
    """The :data:`GONE` key a write to ``path`` falls under, or ``None``."""
    if not path.startswith(_PREFIX):
        return None
    names = [part for part in path[len(_PREFIX) :].split("/") if part and not part.isdigit()]
    while names:
        key = "/".join(names)
        if key in GONE:
            return key
        names.pop()
    return None


def gone_answer(path: str) -> tuple[int, str] | None:
    """The status and sentence for a row deleted under a write to ``path``,
    or ``None`` when the path names no resource :data:`GONE` holds."""
    key = gone_key(path)
    if key is None:
        return None
    return GONE_STATUS.get(key, status.HTTP_404_NOT_FOUND), GONE[key]


class DishkaAnnotatedRoute(DishkaRoute):
    """``DishkaRoute`` for a module with postponed annotations."""

    def __init__(self, path: str, endpoint: Callable[..., Any], **kwargs: Any) -> None:
        current = signature(endpoint)
        if current.return_annotation is not Signature.empty:
            # `get_typed_return_annotation` is FastAPI's own resolver, asked
            # here rather than left to be asked later from the wrong place.
            endpoint.__signature__ = current.replace(  # type: ignore[attr-defined]
                return_annotation=get_typed_return_annotation(endpoint),
            )
        super().__init__(path, endpoint, **kwargs)

    def get_route_handler(self) -> Callable[[Request], Coroutine[Any, Any, Response]]:
        handler = super().get_route_handler()

        async def answer(request: Request) -> Response:
            try:
                return await handler(request)
            except StaleDataError:
                answer = gone_answer(request.url.path)
                if answer is None:
                    raise
                # Rolled back before the answer, as v2 and the bot do: the
                # request scope closes the session only once the answer is
                # sent, and on SQLite the failed transaction holds the write
                # lock until then.
                await (await request.state.dishka_container.get(AsyncSession)).rollback()
                code, detail = answer
                raise HTTPException(status_code=code, detail=detail) from None

        return answer
