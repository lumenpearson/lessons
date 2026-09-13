"""The HTTP half of the Petersburg diary integration.

This is the only file in the project that knows the upstream's URLs, its
parameter names and its habit of carrying the session in a cookie. Everything
it returns is the raw ``data`` object from a response; turning that into this
project's models is :mod:`app.providers.petersburg.mapper`'s job, and keeping
the two apart is the whole point - when the upstream renames a field, the
repair is in the mapper, and when it moves an endpoint, the repair is here.

Nothing about it is documented by its authors. The endpoints and parameters
below were established from open-source clients that talk to it, chiefly
`kewldan/TelegramDnevnik`, and are used as a map rather than copied: the
request shapes are the upstream's, the code is this project's.

One client per process, not one per request: a serverless invocation may make
five upstream calls in a row, and five TLS handshakes is most of the latency
of a page.
"""

from __future__ import annotations

import asyncio
import http.cookiejar
import logging
from datetime import date as Date
from datetime import datetime
from typing import Any
from zoneinfo import ZoneInfo

import httpx

from app.providers.petersburg.exceptions import (
    BadCredentials,
    SessionExpired,
    UnexpectedResponse,
    UpstreamUnavailable,
)

log = logging.getLogger(__name__)

BASE_URL = "https://dnevnik2.petersburgedu.ru"

#: The zone the diary's own days are cut at.
#:
#: Every other "today" in this project comes from ``SchoolClass.timezone``,
#: because the project serves schools across eleven zones. A diary session has
#: no class behind it and needs none: this upstream is one city's, and that
#: city keeps Moscow time. Asking the server's own clock instead put a pupil
#: opening the diary after nine in the evening - Vercel runs in UTC - into
#: yesterday, which is the half of the day they are most likely to be checking
#: tomorrow's lessons in.
TIMEZONE = ZoneInfo("Europe/Moscow")


def today() -> Date:
    """The date it is in the city whose diary this is."""
    return datetime.now(TIMEZONE).date()

#: The cookie the upstream keeps its session in, and hands back refreshed on
#: most calls. Not a header, despite the name.
SESSION_COOKIE = "X-JWT-Token"

#: A browser's, because the upstream is a browser's backend and has been known
#: to answer differently to anything else.
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

#: Their own pagination ceiling. Asked for explicitly because the default is
#: small enough that a term's worth of marks would arrive in pages, and paging
#: an undocumented API is a second thing that can break.
PAGE_LIMIT = "500"
MARKS_LIMIT = "1000"

_TIMEOUT = httpx.Timeout(connect=5.0, read=20.0, write=10.0, pool=5.0)
_LIMITS = httpx.Limits(max_connections=10, max_keepalive_connections=5)

_client: httpx.AsyncClient | None = None
_client_lock = asyncio.Lock()


class _NoCookieJar(http.cookiejar.CookieJar):
    """A jar that keeps nothing it is handed.

    httpx stores every ``Set-Cookie`` it sees on the *client* and merges what
    it holds into each outgoing request. The upstream refreshes the session
    cookie on the way past nearly every call, so with one client per process
    the first family's refreshed session was merged into the second family's
    next request - two cookies of the same name in one header, theirs first -
    and onto a login, which must carry none at all. The session is passed per
    request, so there is nothing here worth remembering.
    """

    def extract_cookies(self, response, request) -> None:
        return None


async def shared_client() -> httpx.AsyncClient:
    """The one client, built on first use.

    Cookies are *not* shared: this client carries none of its own, and every
    call passes the session it should use. Two families' sessions travelling
    through one client must never meet, and a shared cookie jar is exactly how
    they would.
    """
    global _client
    if _client is None:
        async with _client_lock:
            if _client is None:
                _client = httpx.AsyncClient(
                    base_url=BASE_URL,
                    timeout=_TIMEOUT,
                    limits=_LIMITS,
                    cookies=_NoCookieJar(),
                    headers={
                        "user-agent": USER_AGENT,
                        "accept": "application/json",
                        "accept-charset": "UTF-8",
                    },
                    follow_redirects=False,
                )
    return _client


async def close_client() -> None:
    """Closes the shared client. For the lifespan's shutdown and for tests."""
    global _client
    if _client is not None:
        await _client.aclose()
        _client = None


def serialise_date(value: Date) -> str:
    """``15.09.2026`` - the only date format the upstream accepts."""
    return value.strftime("%d.%m.%Y")


def serialise_datetime_range(value: Date) -> str:
    """The odd one: a date, a plus, and a wall time.

    The lesson endpoint wants ``15.09.2026+00:00:00`` rather than a date. It is
    not ISO 8601 and not a timestamp; it is what their frontend sends, so it is
    what this sends.
    """
    return f"{serialise_date(value)}+00:00:00"


class PetersburgClient:
    """Calls the upstream on behalf of one session.

    @param token the upstream session, or ``None`` for the login call. Sessions
        are passed in rather than held, so one instance is as cheap as a
        function call and two callers can never see each other's.
    """

    __slots__ = ("token",)

    def __init__(self, token: str | None = None) -> None:
        self.token = token

    # ---- the one write ------------------------------------------------

    async def login(self, login: str, password: str) -> str:
        """Exchanges an email and a password for an upstream session token.

        @raises BadCredentials when the upstream refuses the pair.
        """
        payload = {
            # Their field names, including the two that look like leftovers.
            # Sending fewer of them gets a 400.
            "type": "email",
            "login": login,
            "activation_code": None,
            "password": password,
            "_isEmpty": False,
        }
        response = await self._raw("POST", "/api/user/auth/login", json=payload)
        if response.status_code in (400, 401, 403):
            raise BadCredentials()
        data = self._unwrap(response)

        # The token arrives twice - in the body and as a cookie - and the two
        # have been seen to differ, with the cookie being the one later calls
        # accept. Prefer it; fall back to the body rather than failing.
        token = response.cookies.get(SESSION_COOKIE) or _string(data, "token")
        if not token:
            raise UnexpectedResponse("Дневник не выдал сессию")
        return token

    # ---- reads --------------------------------------------------------

    async def children(self) -> list[dict[str, Any]]:
        """Every pupil this account can see. One for a parent of one child."""
        data = await self._get("/api/journal/person/related-child-list", {"p_page": "1"})
        return _items(data)

    async def periods(self, group_id: int) -> list[dict[str, Any]]:
        """Academic periods (quarters, terms) of one class."""
        data = await self._get(
            "/api/group/group/get-list-period",
            {"p_limit": PAGE_LIMIT, "p_page": "1", "p_group_ids[]": str(group_id)},
        )
        return _items(data)

    async def subjects(self, group_id: int, period_id: int) -> list[dict[str, Any]]:
        data = await self._get(
            "/api/journal/subject/list-studied",
            {
                "p_limit": PAGE_LIMIT,
                "p_page": "1",
                "p_groups[]": str(group_id),
                "p_periods[]": str(period_id),
            },
        )
        return _items(data)

    async def teachers(self, education_id: int) -> list[dict[str, Any]]:
        data = await self._get(
            "/api/journal/teacher/list",
            {"p_page": "1", "p_limit": PAGE_LIMIT, "p_educations[]": str(education_id)},
        )
        return _items(data)

    async def marks(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[dict[str, Any]]:
        data = await self._get(
            "/api/journal/estimate/table",
            {
                "p_limit": MARKS_LIMIT,
                "p_page": "1",
                "p_date_from": serialise_date(date_from),
                "p_date_to": serialise_date(date_to),
                "p_educations[]": str(education_id),
            },
        )
        return _items(data)

    async def lessons(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[dict[str, Any]]:
        """Lessons with their homework. Note the odd date format; see above."""
        data = await self._get(
            "/api/journal/lesson/list-by-education",
            {
                "p_limit": PAGE_LIMIT,
                "p_page": "1",
                "p_datetime_from": serialise_datetime_range(date_from),
                "p_datetime_to": serialise_datetime_range(date_to),
                "p_educations[]": str(education_id),
            },
        )
        return _items(data)

    async def schedule(
        self, education_id: int, date_from: Date, date_to: Date
    ) -> list[dict[str, Any]]:
        """The timetable. Dates here are plain, unlike :meth:`lessons`."""
        data = await self._get(
            "/api/journal/schedule/list-by-education",
            {
                "p_limit": PAGE_LIMIT,
                "p_page": "1",
                "p_datetime_from": serialise_date(date_from),
                "p_datetime_to": serialise_date(date_to),
                "p_educations[]": str(education_id),
            },
        )
        return _items(data)

    async def attendance(self, education_id: int, limit: int = 30) -> list[dict[str, Any]]:
        """Turnstile records - in and out of the building."""
        data = await self._get(
            "/api/journal/acs/list",
            {"p_limit": str(limit), "p_page": "1", "p_education": str(education_id)},
        )
        return _items(data)

    # ---- plumbing -----------------------------------------------------

    async def _get(self, path: str, params: dict[str, str]) -> dict[str, Any]:
        response = await self._raw("GET", path, params=params)
        if response.status_code in (401, 403):
            raise SessionExpired()
        return self._unwrap(response)

    async def _raw(self, method: str, path: str, **kwargs: Any) -> httpx.Response:
        client = await shared_client()
        cookies = {SESSION_COOKIE: self.token} if self.token else None
        try:
            response = await client.request(method, path, cookies=cookies, **kwargs)
        except httpx.TimeoutException as failure:
            raise UpstreamUnavailable("Дневник не ответил вовремя") from failure
        except httpx.HTTPError as failure:
            log.warning("petersburg %s %s failed: %s", method, path, failure)
            raise UpstreamUnavailable() from failure

        # Their session is refreshed on the way past, so a long-lived session
        # stays alive as long as it is used. The caller persists what it finds.
        refreshed = response.cookies.get(SESSION_COOKIE)
        if refreshed:
            self.token = refreshed
        if response.status_code >= 500:
            raise UpstreamUnavailable()
        return response

    @staticmethod
    def _unwrap(response: httpx.Response) -> dict[str, Any]:
        """Their envelope: ``{"data": ...}`` on success, HTML when logged out.

        A body that is not JSON is the shape of "your session ended and you are
        looking at the login page", so it is reported as a session problem
        rather than as a parse failure - which is what it is for the person
        holding the phone.
        """
        try:
            body = response.json()
        except ValueError as failure:
            if response.status_code == 200:
                raise SessionExpired() from failure
            raise UnexpectedResponse() from failure

        if not isinstance(body, dict):
            raise UnexpectedResponse()
        data = body.get("data")
        if isinstance(data, dict):
            return data
        if data is None:
            # Some errors answer 200 with only a message.
            message = _string(body, "message") or _first_error(body)
            if message:
                raise UnexpectedResponse(message)
            raise UnexpectedResponse()
        return {"items": data}


def _items(data: dict[str, Any]) -> list[dict[str, Any]]:
    """``data.items``, or nothing at all rather than an exception.

    An empty list is the honest reading of "this pupil has no marks this week",
    and the upstream expresses that by omitting the key as often as by sending
    an empty array.
    """
    items = data.get("items")
    if items is None:
        return []
    if not isinstance(items, list):
        raise UnexpectedResponse()
    return [item for item in items if isinstance(item, dict)]


def _string(source: dict[str, Any], key: str) -> str | None:
    value = source.get(key)
    return value.strip() if isinstance(value, str) and value.strip() else None


def _first_error(body: dict[str, Any]) -> str | None:
    errors = body.get("errors")
    if isinstance(errors, list) and errors:
        first = errors[0]
        if isinstance(first, dict):
            return _string(first, "message")
        if isinstance(first, str):
            return first
    return None
