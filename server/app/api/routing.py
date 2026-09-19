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
"""

from __future__ import annotations

from collections.abc import Callable
from inspect import Signature, signature
from typing import Any

from dishka.integrations.fastapi import DishkaRoute
from fastapi.dependencies.utils import get_typed_return_annotation


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
