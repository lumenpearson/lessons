"""The anonymous school directory's door: «which regions is a school of this name in».

v1's ``GET /directory/school-regions`` held the order of its checks in its
router, and v2's ``ListSchoolRegions`` asks the same question, so the order
moved here (``docs/specs/2026-10-05-server-v2-design.md``, decisions 2 and 11).
Both versions count the caller in one bucket, scoped :data:`BUCKET_SCOPE`, on
``security.directory_limiter``, and spend from one anonymous share of the
directory's daily allowance (``services/quota.py``): a caller alternating the
two versions has twenty searches, not forty.

A refusal is an exception carrying facts, never a sentence, and each shell
words it in ``app/wording.py``'s sentences: v1 as a 429, a 422 or a 503 with
``X-Directory-Unavailable``, v2 through its error table. The admins' search
(``/manage/schools``, ``ListSchools``) has no door here: it is
``schools.search``, unmetered, and its refusals are the provider's own.

It commits, as the limiter and the allowance it stands on do: an attempt
counted and a unit spent stay so whatever the caller does next, because the
directory counts its requests either way.
"""

from __future__ import annotations

from sqlalchemy.ext.asyncio import AsyncSession

from app.providers import dadata
from app.security import Throttled, directory_limiter
from app.services import schools

#: Mixed into the caller's bucket, so that twenty searches spend nothing of a
#: phone's thirty join attempts or its ten diary sign-ins: the three doors
#: count on one table (``security.JoinThrottle``).
BUCKET_SCOPE = "directory:"


class DirectoryThrottled(Throttled):
    """This caller has made its twenty searches inside the window."""


class DirectoryDisabled(Exception):
    """This deployment has no ``DADATA_TOKEN``, so there is no directory to ask."""


class DirectoryUnavailable(Exception):
    """The directory failed: down, refusing the key or its own daily allowance,
    or answering something new. One fact for all of them, because to a phone
    with no class they are one «not now», and the region list is the way on."""


async def school_regions(
    session: AsyncSession, raw_query: str | None, *, client_key: str
) -> schools.RegionSearch:
    """The regions a school called ``raw_query`` may be in, best first.

    In this order, which is the contract (``directory.proto``):

    1. the attempt is counted, and a caller over its limit is refused;
    2. a query under three characters is refused, and the attempt handed back,
       because nothing was asked of anybody;
    3. a deployment with no directory is refused;
    4. a name too common to place is answered with nothing spent; any other
       spends one unit of today's anonymous share first, or is refused;
    5. the directory failing, in any way, is refused.

    ``client_key`` is the caller's bucket under :data:`BUCKET_SCOPE`.

    @raises DirectoryThrottled over the limit, with the seconds to wait.
    @raises schools.SearchError for a query too short to search with.
    @raises DirectoryDisabled without a key.
    @raises quota.AllowanceSpent when today's anonymous share is gone.
    @raises DirectoryUnavailable when the directory failed.
    """
    # Counted first and handed back on a short query, rather than checked here
    # and recorded after the validation: between a check and a later record,
    # every request of a parallel burst from one address read the count from
    # before any of them, and each went on to spend the anonymous share.
    attempt = await directory_limiter.admit(session, client_key)
    if attempt.retry_after is not None:
        raise DirectoryThrottled(attempt.retry_after)
    try:
        query = schools.normalise_query(raw_query)
    except schools.SearchError:
        await directory_limiter.forgive(session, attempt)
        raise
    if not schools.available():
        raise DirectoryDisabled("DADATA_TOKEN is empty")
    try:
        return await schools.regions_for(session, query)
    except dadata.DirectoryError as failure:
        raise DirectoryUnavailable(type(failure).__name__) from failure
