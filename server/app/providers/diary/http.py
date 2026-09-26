"""The HTTP a diary provider is built on, shared so its traps are solved once.

Two of them cost Petersburg real failures and are written down here so the
second provider does not pay them again:

- **A shared client keeps a cookie jar, and that jar leaks between families.**
  ``httpx`` stores every ``Set-Cookie`` on the *client* and merges it into the
  next request. With one client per process, the first family's session cookie
  rides out on the second family's call, and onto a sign-in that must carry
  none. :class:`NoCookieJar` keeps nothing; the session travels per request.
- **Reading a cookie can raise past the error family.**
  ``httpx.Cookies.get`` raises ``CookieConflict`` when one response sets a name
  twice under different paths, the ordinary shape of a PHP app rotating a
  scoped session. ``CookieConflict`` is not an ``httpx.HTTPError``, so a bare
  ``response.cookies.get`` walks out of a provider as an exception the API edge
  cannot map — a 500 on every call. :func:`session_cookie` is the only safe
  reader: use it, never a bare ``.get``.

``build_client`` also pins ``follow_redirects=False`` (a cross-origin redirect
would carry the session bearer to another host — the one way around a region
allow-list) and never turns TLS verification off.

And a third, which arrived with the phone opening sessions itself: **a stored
session value is only ever written into a header if it is safe there.** A
cookie value holding ``;`` smuggles a second cookie, and one holding CR/LF a
second header. :func:`cookie_value_ok` and :func:`header_value_ok` are the one
definition of "safe", used by the schema that accepts a session from a phone
*and* by the clients that write the headers, so a credential that reached the
database by any path is checked again on the way out.
"""

from __future__ import annotations

import http.cookiejar
import logging
import re

import httpx

log = logging.getLogger(__name__)

#: A browser's, because these upstreams are browsers' backends and have been
#: known to answer differently to anything else.
USER_AGENT = (
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
    "Chrome/124.0.0.0 Safari/537.36"
)

#: RFC 6265 ``cookie-octet``: printable ASCII less space, the double quote,
#: the comma, the semicolon and the backslash. What a ``Cookie:`` header can
#: carry as one value and nothing more.
_COOKIE_OCTETS = re.compile(r"[\x21\x23-\x2B\x2D-\x3A\x3C-\x5B\x5D-\x7E]+")

#: RFC 7235 ``token68``: what a bearer-shaped header value is made of. No
#: space, no quote, no CR or LF — so no way to end the header and start another.
_HEADER_TOKEN = re.compile(r"[A-Za-z0-9._~+/=-]+")


def cookie_value_ok(value: object) -> bool:
    """Whether ``value`` can be sent as one cookie's value without becoming two."""
    return isinstance(value, str) and _COOKIE_OCTETS.fullmatch(value) is not None


def header_value_ok(value: object) -> bool:
    """Whether ``value`` can be sent as a bearer-shaped header value, whole."""
    return isinstance(value, str) and _HEADER_TOKEN.fullmatch(value) is not None


class NoCookieJar(http.cookiejar.CookieJar):
    """A jar that keeps nothing it is handed.

    See the module docstring: the session is passed per request, so there is
    nothing here worth remembering, and remembering it is how two families'
    sessions meet.
    """

    def extract_cookies(self, response, request) -> None:  # noqa: ARG002
        return None


def session_cookie(response: httpx.Response, name: str) -> str | None:
    """The named cookie this answer carries, or ``None`` — CookieConflict-safe.

    **The only way to read a Set-Cookie in a provider.** A bare
    ``response.cookies.get(name)`` raises ``CookieConflict`` when the answer
    sets ``name`` twice under different paths, and that exception is not an
    ``httpx.HTTPError``, so it escapes the provider's error family and becomes a
    500 on every call. The last value wins, which is what a browser sending the
    next request to the same path would use; guessing is acceptable here and
    refusing (a session that never refreshes and dies) is not.
    """
    try:
        return response.cookies.get(name)
    except httpx.CookieConflict:
        values = [
            cookie.value
            for cookie in response.cookies.jar
            if cookie.name == name and cookie.value
        ]
        log.warning("answer carried %d %s cookies; taking the last", len(values), name)
        return values[-1] if values else None


def build_client(
    *,
    base_url: str = "",
    timeout: httpx.Timeout,
    limits: httpx.Limits | None = None,
    headers: dict[str, str] | None = None,
) -> httpx.AsyncClient:
    """A client that carries no cookies of its own, follows no redirect, and
    verifies TLS. Every diary provider's pooled client is built here."""
    base_headers = {"user-agent": USER_AGENT, "accept": "application/json"}
    if headers:
        base_headers.update(headers)
    return httpx.AsyncClient(
        base_url=base_url,
        timeout=timeout,
        limits=limits or httpx.Limits(max_connections=10, max_keepalive_connections=5),
        cookies=NoCookieJar(),
        headers=base_headers,
        follow_redirects=False,
        # verify defaults to True and is never set False. A region whose
        # server presents a root the system does not trust (the Russian
        # Trusted Root) is left out of the allow-list rather than given a
        # CA of its own or a verification switched off — there is no
        # per-origin trust store here, and none is read.
    )
