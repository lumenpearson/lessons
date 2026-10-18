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
(:data:`GONE`), rather than a ``500``. Nothing was written, and an upsert —
``PUT /overrides``, ``PUT /days`` — does not make the row again
(``services/gone.py`` says why). A path :data:`GONE` does not name lets the
error through, as it did.
"""

from __future__ import annotations

import re
from collections.abc import Callable, Coroutine, Mapping
from inspect import Signature, signature
from typing import Any

from dishka.integrations.fastapi import DishkaRoute
from fastapi import HTTPException, Request, Response, status
from fastapi.dependencies.utils import get_typed_return_annotation
from sqlalchemy.orm.exc import StaleDataError

from app import wording

#: The resource a v1 path names: its first segment, and its second under
#: ``/manage``.
_RESOURCE = re.compile(r"/api/v1/((?:manage/)?[a-z]+)")

#: What a write under each resource answers when the row it changed was
#: deleted by another writer before its commit: the route's own ``404`` for a
#: row that is not there, and for a substitution and a day's mark, which v1
#: names by date and never answered ``404`` for, the sentence v2 says.
GONE: Mapping[str, str] = {
    "homework": wording.UNKNOWN_HOMEWORK_DETAIL,
    "overrides": wording.UNKNOWN_SUBSTITUTION_DETAIL,
    "events": wording.UNKNOWN_EVENT_DETAIL,
    "days": wording.DAY_NOT_MARKED_DETAIL,
    "tasks": wording.UNKNOWN_TASK_DETAIL,
    "manage/subjects": wording.UNKNOWN_SUBJECT_DETAIL,
    "manage/bells": wording.UNKNOWN_BELL_SCHEDULE_DETAIL,
    "manage/devices": wording.UNKNOWN_DEVICE_DETAIL,
    "manage/requests": wording.UNKNOWN_ACCESS_REQUEST_DETAIL,
    "manage/class": wording.CLASS_GONE_DETAIL,
}


def gone_detail(path: str) -> str | None:
    """The ``404`` sentence for a row deleted under a write to ``path``, or
    ``None`` when the path names no resource :data:`GONE` holds."""
    found = _RESOURCE.match(path)
    return GONE.get(found.group(1)) if found is not None else None


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
                detail = gone_detail(request.url.path)
                if detail is None:
                    raise
                # The session is closed with the request scope, which rolls
                # back what the failed flush left.
                raise HTTPException(status_code=status.HTTP_404_NOT_FOUND, detail=detail) from None

        return answer
