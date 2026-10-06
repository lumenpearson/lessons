"""``/schools``: the school directory, looked up before ``PATCH /class``.

Part of :mod:`app.api.manage`; the rules every endpoint of it
follows are in that package's docstring.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, Query, status

from app.api.deps import current_class
from app.api.manage._common import Actor, admin_actor
from app.api.routing import DishkaAnnotatedRoute
from app.models import SchoolClass
from app.providers import dadata
from app.schemas import SchoolOut, SchoolSearchOut
from app.services import schools as schools_service

router = APIRouter(route_class=DishkaAnnotatedRoute)


@router.get("/schools", response_model=SchoolSearchOut)
async def schools_search(
    q: str = Query(..., description="Название или номер школы"),
    page: int = Query(1, ge=1),
    page_size: int = Query(
        schools_service.PAGE_SIZE,
        ge=1,
        le=dadata.MAX_SUGGESTIONS,
        description="Строк на странице; 20 отдаёт всё найденное за один запрос",
    ),
    region: str | None = Query(None, max_length=schools_service.MAX_REGION),
    _: Actor = Depends(admin_actor),
    __: SchoolClass = Depends(current_class),
) -> SchoolSearchOut:
    """Search the school directory, the same way «⚙️ Класс» does in the bot.

    Reads nothing and writes nothing: it is a lookup the app needs before it
    can `PATCH /class` with a school name, and the name is all that is stored.
    Admin-only despite being read-only, because every call spends part of a
    daily allowance somebody else pays for, and the class's own members are the
    only people with a reason to spend it.

    503 rather than 500 when the directory is not configured or not answering:
    nothing here is broken, the feature is simply unavailable right now, and
    the client's answer to that is to let the name be typed.

    **Every call is one upstream search**, whatever ``page`` says — the
    directory has no offset to page with, so there is nothing to resume. A
    client that pages should therefore ask once with ``page_size=20`` and cut
    the answer up itself, which is what the bot and the app both do; asking for
    four pages of five is four searches for one question.
    """
    try:
        result = await schools_service.search(q, region=region)
    except schools_service.SearchError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)
        ) from error
    except dadata.DirectoryError as error:
        raise HTTPException(
            status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=error.message
        ) from error

    found = schools_service.page_of(
        result.schools, page, size=page_size, truncated=result.truncated
    )
    return SchoolSearchOut(
        items=[SchoolOut(**school.model_dump()) for school in found.items],
        page=found.page,
        pages=found.pages,
        total=found.total,
        truncated=found.truncated,
    )
