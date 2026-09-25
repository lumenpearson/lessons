"""The school directory, for a phone that belongs to no class yet.

One question: «which regions is a school of this name in». The phone asks it
on its first screen, before it has a class, a diary or any token at all, so
that somebody who types «лицей 1535» is offered Москва rather than a list of
eighty-nine regions to scroll. It is a convenience and nothing depends on it:
picking the region from the list always works, and every failure here says so.

**Anonymous, and therefore metered twice.** Every call past validation counts
against its caller (twenty in fifteen minutes, on the table `/join` counts in),
and every request it makes upstream is spent from the anonymous share of the
directory's daily allowance (``services/quota.py``) — so neither one address
nor many can spend what the bot and ``/manage/schools`` need to create a
class. Those two keep their own search, unchanged and unmetered here.

Every 503 carries ``X-Directory-Unavailable`` — ``disabled``, ``spent`` or
``upstream`` — for the same reason every diary 503 carries its own header: the
app picks its sentence without reading the Russian.
"""

from __future__ import annotations

from dishka.integrations.fastapi import FromDishka
from fastapi import APIRouter, HTTPException, Query, Request, status
from sqlalchemy.ext.asyncio import AsyncSession

from app.api.public import caller_bucket
from app.api.routing import DishkaAnnotatedRoute
from app.providers import dadata
from app.schemas import SchoolRegionOut, SchoolRegionsOut
from app.security import JoinThrottle
from app.services import quota
from app.services import schools as schools_service

router = APIRouter(
    route_class=DishkaAnnotatedRoute, prefix="/api/v1/directory", tags=["directory"]
)

#: Searches one caller may make in a quarter of an hour, **all** of them
#: counted — a search that finds its school has spent the same upstream request
#: as one that finds nothing. Twenty is a person trying spellings with room to
#: spare, and a school's NAT full of phones on an open day will meet it; they
#: then pick the region from the list, which is what the limit costs.
#:
#: The window is `/join`'s and the diary sign-in's, and has to be: the three
#: share one table, and every recorded attempt prunes the whole table to its
#: own window (``JoinThrottle``).
directory_limiter = JoinThrottle(limit=20, window=900.0)

#: The header every 503 here carries, and its three values.
UNAVAILABLE_HEADER = "X-Directory-Unavailable"

DISABLED_DETAIL = "Поиск школ не настроен — выберите регион из списка"
SPENT_DETAIL = "Поиск школ на сегодня исчерпан — выберите регион из списка"
UPSTREAM_DETAIL = "Поиск школ сейчас недоступен — выберите регион из списка"
THROTTLED_DETAIL = "Слишком много поисков подряд. Выберите регион из списка или попробуйте позже."


def _unavailable(detail: str, reason: str, *, retry_after: int | None = None) -> HTTPException:
    headers = {UNAVAILABLE_HEADER: reason}
    if retry_after is not None:
        headers["Retry-After"] = str(retry_after)
    return HTTPException(
        status_code=status.HTTP_503_SERVICE_UNAVAILABLE, detail=detail, headers=headers
    )


@router.get("/school-regions", response_model=SchoolRegionsOut)
async def school_regions(
    request: Request,
    q: str | None = Query(None, description="Название школы, как его пишут люди"),
    *,
    session: FromDishka[AsyncSession],
) -> SchoolRegionsOut:
    """The regions a school called ``q`` may be in, best-ranked first.

    In this order, and the order is the contract:

    1. a caller over its limit is 429 with ``Retry-After``;
    2. a query under three characters is 422, **not counted** — nothing was
       asked of anybody;
    3. everything after that is counted against the caller;
    4. no directory key on this deployment is 503 ``disabled``;
    5. a name that is only a kind of school and a small number («школа № 5»)
       is 200 with ``generic`` and no upstream call; anything else spends from
       today's anonymous share, and a spent share is 503 ``spent`` with
       ``Retry-After`` until the next day;
    6. the directory failing, in any way, is 503 ``upstream``.

    Not per keystroke: the phone asks on submit or after a pause in typing.
    """
    # Scoped, so twenty searches do not spend a phone's thirty join attempts,
    # and hashed with the scope inside it — see `caller_bucket` for why a
    # prefix on the digest is a 500 on Postgres.
    client = caller_bucket(request, scope="directory:")
    retry_after = await directory_limiter.blocked_for(session, client)
    if retry_after is not None:
        raise HTTPException(
            status_code=status.HTTP_429_TOO_MANY_REQUESTS,
            detail=THROTTLED_DETAIL,
            headers={"Retry-After": str(int(retry_after) + 1)},
        )

    try:
        query = schools_service.normalise_query(q)
    except schools_service.SearchError as error:
        raise HTTPException(
            status_code=status.HTTP_422_UNPROCESSABLE_ENTITY, detail=str(error)
        ) from error

    await directory_limiter.record(session, client)

    if not schools_service.available():
        raise _unavailable(DISABLED_DETAIL, "disabled")

    try:
        found = await schools_service.regions_for(session, query)
    except quota.AllowanceSpent as spent:
        raise _unavailable(SPENT_DETAIL, "spent", retry_after=spent.retry_after) from spent
    except dadata.DirectoryError as error:
        # The bot's own wording for these ends «введите название вручную»,
        # which is the bot's way out and not the phone's. DaData's refusal of
        # the key or of its own daily allowance lands here too: it is the
        # owner's to fix, and to the person holding the phone it is the same
        # «not now».
        raise _unavailable(UPSTREAM_DETAIL, "upstream") from error

    return SchoolRegionsOut(
        query=found.query,
        regions=[
            SchoolRegionOut(
                region=group.region,
                code=group.code,
                label=group.label,
                schools=group.schools,
                cities=group.cities,
                examples=group.examples,
            )
            for group in found.groups
        ],
        truncated=found.truncated,
        generic=found.generic,
    )
