"""``DiaryService``: one family's account with an electronic diary.

3a serves ``GetDiaryCapabilities``; sessions and every read are 3b's, with the
per-provider registry table (decision 12).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.diary_pb import (
    DiaryCapabilities,
    GetDiaryCapabilitiesRequest,
    GetDiaryCapabilitiesResponse,
    ProviderCapabilities,
)
from app.crypto import diary_enabled
from app.providers.diary.registry import KEYS, NETSCHOOL

if TYPE_CHECKING:
    from app.rpc.call import Call


async def get_diary_capabilities(
    call: Call, request: GetDiaryCapabilitiesRequest
) -> GetDiaryCapabilitiesResponse:
    """What this server's diary can do, before the phone takes a password.

    The same answer v1's ``/diary/capabilities`` gives, in v2's shape
    (decision 12): whether the diary runs here at all, every provider the
    registry knows, and for «Сетевой город» the allow-list's regions that take
    a password. ``sign_in_methods`` and ``features`` stay empty, because v1's
    answer has neither and what each provider declares is 3b's registry table
    to say, from what its connection really implements. Anonymous and
    database-free, as v1's: the gate opens a scope, and nothing asks it for a
    query.
    """
    from app.providers.netschool import regions

    listed = [region.key for region in regions.listed()]
    return GetDiaryCapabilitiesResponse(
        capabilities=DiaryCapabilities(
            enabled=diary_enabled(),
            providers=[
                ProviderCapabilities(provider=key, regions=listed if key == NETSCHOOL else [])
                for key in KEYS
            ],
        )
    )
