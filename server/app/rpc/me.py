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

from app.contract.lessons.v2.errors_pb import ErrorReason
from app.contract.lessons.v2.me_pb import (
    CalendarFeed,
    CreateCalendarFeedRequest,
    CreateCalendarFeedResponse,
    CreateLinkCodeRequest,
    CreateLinkCodeResponse,
    GetCalendarFeedRequest,
    GetCalendarFeedResponse,
    GetMeRequest,
    GetMeResponse,
    LinkCode,
    Me,
    UnlinkMeRequest,
    UnlinkMeResponse,
)
from app.models import DeviceToken
from app.rpc import values
from app.rpc.errors import Refusal
from app.services import calendar as calendar_service
from app.services import linking
from app.services.linking import Access

if TYPE_CHECKING:
    from app.rpc.call import Call

#: ``FEATURE_UNSUPPORTED``'s ``feature`` for a deployment with no public
#: address, a server capability as ``errors.proto`` allows beside a
#: ``DiaryFeature`` name.
CALENDAR_FEED = "calendar_feed"

#: New in v2, so English like v1's own generic answers.
NO_FEED_ADDRESS = "This server has no public address to give a calendar feed"


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


def _feed_origin(call: Call) -> str:
    """The origin the feed's address is written on, ``PUBLIC_BASE_URL``, or
    ``FEATURE_UNSUPPORTED``. v1 falls back to the request's own host, which
    behind Vercel is an internal one (``docs/api.md``); v2 hands out no
    address it cannot stand behind, as «📅 Календарь» hands out none."""
    origin = call.settings.public_base_url.rstrip("/")
    if not origin:
        raise Refusal(ErrorReason.FEATURE_UNSUPPORTED, NO_FEED_ADDRESS, feature=CALENDAR_FEED)
    return origin


async def get_calendar_feed(call: Call, request: GetCalendarFeedRequest) -> GetCalendarFeedResponse:
    """The class's subscription address when the class has a feed secret, and
    none when it has not. Mints nothing, where v1's ``GET /calendar`` did:
    ``CreateCalendarFeed`` mints now. Writes nothing."""
    _device, school_class = call.device_and_class()
    origin = _feed_origin(call)
    secret = school_class.calendar_token
    return GetCalendarFeedResponse(
        calendar_feed=CalendarFeed(
            url=calendar_service.feed_url(origin, secret) if secret else None
        )
    )


async def create_calendar_feed(
    call: Call, request: CreateCalendarFeedRequest
) -> CreateCalendarFeedResponse:
    """The class's subscription address, minting its secret when it has none,
    and the same address on every ask after (``calendar.ensure_calendar_token``,
    which v1 and the bot call too). Asks for a linked account, where v1 let any
    phone of the class mint it; no rotation, since the feed is the whole
    class's. Never cached (``rest.NO_STORE_CREDENTIAL``)."""
    _device, school_class = call.device_and_class()
    origin = _feed_origin(call)
    secret = await calendar_service.ensure_calendar_token(call.session, school_class)
    return CreateCalendarFeedResponse(
        calendar_feed=CalendarFeed(url=calendar_service.feed_url(origin, secret))
    )
