"""``MeService``: what a phone does for itself.

Who it is, its link to an account, the class's calendar feed, and the linked
account's own tasks and homework ticks, which nobody else in the class sees.
Nothing here changes the class. 3a serves ``GetMe`` and 3b-4 the rest, each
v1's ``/me``, ``/me/unlink``, ``/calendar``, ``/tasks`` or
``/homework/{id}/done`` through the same services. What v2 does not repeat is
minting on a read: ``GetMe`` and ``GetCalendarFeed`` mint nothing, and
``CreateLinkCode`` and ``CreateCalendarFeed`` do
(``docs/specs/2026-10-05-server-v2-design.md``, decision 10).
"""

from __future__ import annotations

from typing import TYPE_CHECKING

from app.contract.lessons.v2.me_pb import (
    CreateLinkCodeRequest,
    CreateLinkCodeResponse,
    GetMeRequest,
    GetMeResponse,
    LinkCode,
    Me,
    UnlinkMeRequest,
    UnlinkMeResponse,
)
from app.models import DeviceToken
from app.rpc import values
from app.services import linking
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call


def _me(device: DeviceToken, access: Access) -> Me:
    return Me(
        device_name=device.device_name,
        linked=access.linked,
        role=values.role(access.role),
        can_edit=access.can_edit,
    )


async def get_me(call: Call, request: GetMeRequest) -> GetMeResponse:
    """Who this device is. Mints nothing: v1's ``/me`` issued a link code as a
    side effect, which ``CreateLinkCode`` does in v2 (decision 10). The role is
    the one the gate read for this call."""
    device, _school_class = call.device_and_class()
    return GetMeResponse(me=_me(device, Access.of(device, call.role)))


async def unlink_me(call: Call, request: UnlinkMeRequest) -> UnlinkMeResponse:
    """Back to read-only, keeping the phone in the class: v1's ``/me/unlink``.

    A phone no account is behind changes nothing, its link code included, and
    is answered as it is, so a retry lands on the first answer. No audit line,
    as v1 wrote none: the phone did it to itself.
    """
    device, _school_class = call.device_and_class()
    await linking.unlink_self(call.session, device)
    # Nobody is behind the phone now, so it holds no role, whatever the gate read.
    return UnlinkMeResponse(me=_me(device, Access.of(device, None)))


async def create_link_code(call: Call, request: CreateLinkCodeRequest) -> CreateLinkCodeResponse:
    """The code to send the bot, minted on the first ask and the same until it
    is used, which is the code v1's ``/me`` shows too; none for a phone that is
    linked already. The deep link is there when the deployment names its bot.
    Never cached (``rest.NO_STORE_CREDENTIAL``): it links the phone to whoever
    sends it."""
    device, _school_class = call.device_and_class()
    code = await linking.link_code_for(call.session, device)
    if code is None:
        return CreateLinkCodeResponse()
    return CreateLinkCodeResponse(
        link_code=LinkCode(
            code=code, bot_deep_link=linking.deep_link(call.settings.bot_username, code)
        )
    )
